// 由隔离的 Spring Boot HTTPS + H2 测试启动；不访问学习数据库。
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:collab_notes_client/data/api_client.dart';
import 'package:collab_notes_client/data/api_error.dart';
import 'package:collab_notes_client/data/api_transport.dart';
import 'package:collab_notes_client/data/http_notes_repository.dart';
import 'package:collab_notes_client/domain/note.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final url = Platform.environment['CLIENT_CONTRACT_URL'];
  final certificateFile = Platform.environment['CLIENT_CONTRACT_CERT'];
  if (url == null || certificateFile == null) {
    test(
      '没有隔离服务器时不执行真实请求',
      () {},
      skip: '请运行获准的 ClientHttpContractTests，不指向学习数据库。',
    );
    return;
  }
  final origin = validateApiOrigin(url);
  final cert = base64Encode(File(certificateFile).readAsBytesSync());
  final clients = <ApiClient>[];
  ApiClient client({bool trusted = true}) {
    final api = ApiClient(
      origin: origin,
      transport: IoApiTransport(
        origin,
        localCertificateBase64: trusted ? cert : '',
      ),
    );
    clients.add(api);
    return api;
  }

  String name() =>
      'client_${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}_${Random.secure().nextInt(100000)}';
  const password = 'isolated-contract-password';
  Future<(ApiClient, Account, HttpNotesRepository)> registered() async {
    final api = client();
    final username = name();
    await api.register(username, password);
    expect(api.account, isNull);
    final account = await api.login(username, password);
    return (api, account, HttpNotesRepository(api, account.id));
  }

  NoteDraft draft({
    int? id,
    String title = '联调😀',
    String content = '完整正文',
    ReminderAction action = ReminderAction.keep,
    DateTime? due,
  }) => NoteDraft(
    id: id,
    title: title,
    content: content,
    reminderAction: action,
    reminderAt: due,
  );
  tearDown(() {
    for (final api in clients) {
      api.close();
    }
    clients.clear();
  });

  test('真实 HTTPS 注册登录、错误密码、独立会话与退出', () async {
    final (first, account, _) = await registered();
    final second = client();
    await expectLater(
      second.login(account.username, 'wrong'),
      throwsA(
        isA<ApiException>().having(
          (e) => e.code,
          'code',
          'INVALID_CREDENTIALS',
        ),
      ),
    );
    await second.login(account.username, password);
    expect((await first.me()).id, account.id);
    await first.logout();
    await expectLater(
      first.me(),
      throwsA(
        isA<ApiException>().having((e) => e.needsLogin, 'needsLogin', true),
      ),
    );
    expect((await second.me()).id, account.id);
  });
  test('真实统一保存 SET/KEEP/CANCEL、UTC、完成与提醒批次', () async {
    final (_, _, repo) = await registered();
    final time = DateTime.now().toUtc().add(const Duration(hours: 3));
    final note = await repo.save(draft(action: ReminderAction.set, due: time));
    expect(note.reminderGeneration, 1);
    expect(note.reminderAt!.isUtc, isTrue);
    final changed = await repo.save(
      draft(
        id: note.id,
        title: '改期',
        action: ReminderAction.set,
        due: time.add(const Duration(hours: 1)),
      ),
    );
    expect(changed.reminderGeneration, 2);
    final kept = await repo.save(draft(id: note.id));
    expect(kept.reminderGeneration, 2);
    final cancelled = await repo.save(
      draft(id: note.id, action: ReminderAction.cancel),
    );
    expect(cancelled.reminderStatus, 'CANCELLED');
    expect(cancelled.reminderAt, isNull);
    await repo.save(draft(id: note.id, action: ReminderAction.set, due: time));
    await repo.setCompleted(note.id, true);
    expect((await repo.detail(note.id)).reminderAt, isNull);
    await repo.setCompleted(note.id, false);
    expect((await repo.detail(note.id)).reminderAt, isNull);
  });
  test('真实分页摘要不丢正文，未完成优先；Unicode 边界', () async {
    final (_, _, repo) = await registered();
    final original = await repo.save(
      draft(title: '😀' * 120, content: '😀' * 10000),
    );
    for (var i = 0; i < 22; i++) {
      await repo.save(draft(title: '分页 $i'));
    }
    await repo.setCompleted(original.id, true);
    final page = await repo.loadPage(0);
    expect(page.items.length, 20);
    expect(page.hasNext, isTrue);
    expect(
      page.items.every(
        (item) => item.isSummary && item.content.isEmpty && !item.completed,
      ),
      isTrue,
    );
    final last = await repo.loadPage(1);
    expect(last.items.length, 3);
    expect(last.hasNext, isFalse);
    expect(last.items.last.id, original.id);
    expect((await repo.detail(original.id)).content.runes.length, 10000);
  });
  test('两个真实会话互相刷新，客户端重建不丢服务端记录', () async {
    final (first, account, repo) = await registered();
    final note = await repo.save(draft(title: '电脑创建'));
    final second = client();
    await second.login(account.username, password);
    final other = HttpNotesRepository(second, account.id);
    expect((await other.loadPage(0)).items.single.id, note.id);
    await other.save(draft(id: note.id, title: '手机修改', content: '改过正文'));
    expect((await repo.loadPage(0)).items.single.title, '手机修改');
    first.close();
    final restarted = client();
    await restarted.login(account.username, password);
    expect(
      (await HttpNotesRepository(restarted, account.id).detail(note.id))
          .content,
      '改过正文',
    );
  });
  test('真实删除与恢复产生新 ID，原文/创建时间保留而提醒不恢复', () async {
    final (_, _, repo) = await registered();
    final original = await repo.save(
      draft(
        action: ReminderAction.set,
        due: DateTime.now().add(const Duration(hours: 1)),
      ),
    );
    await repo.delete(original.id);
    expect((await repo.loadPage(0)).items, isEmpty);
    final trash = (await repo.listTrash()).single;
    expect(trash.note.content, original.content);
    final restored = await repo.restore(trash.id);
    expect(restored.id, isNot(original.id));
    expect(restored.createdAt, original.createdAt);
    expect((await repo.detail(restored.id)).reminderAt, isNull);
    expect(await repo.listTrash(), isEmpty);
  });
  test('真实用户隔离与服务端过去时间拒绝，不修改已有记录', () async {
    final (_, _, repo) = await registered();
    final note = await repo.save(draft());
    final (otherApi, otherAccount, other) = await registered();
    await expectLater(
      other.detail(note.id),
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'NOTE_NOT_FOUND'),
      ),
    );
    await expectLater(
      otherApi.request(
        'POST',
        '/api/notes/save',
        ownerId: otherAccount.id,
        body: {
          'title': '无效',
          'content': '正文',
          'reminderAction': 'SET',
          'dueAt': DateTime.now()
              .subtract(const Duration(days: 1))
              .toUtc()
              .toIso8601String(),
        },
      ),
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'INVALID_TIME'),
      ),
    );
    expect((await other.loadPage(0)).items, isEmpty);
  });
  test('没有明确开发证书时 HTTPS 校验失败，不绕过 TLS', () async {
    await expectLater(
      client(trusted: false).me(),
      throwsA(isA<ApiException>().having((e) => e.code, 'code', 'TLS_FAILED')),
    );
  });
  test('真实长轮询在保存提交后立即唤醒另一端，完成删除恢复均自动可见', () async {
    final (_, account, writer) = await registered();
    final reader = client();
    await reader.login(account.username, password);
    final other = HttpNotesRepository(reader, account.id);
    var cursor = (await reader.watchChanges(account.id, null)).cursor;
    Future<void> changed(Future<void> Function() write) async {
      var finished = false;
      final waiting = reader.watchChanges(account.id, cursor).then((signal) {
        finished = true;
        return signal;
      });
      await Future<void>.delayed(const Duration(milliseconds: 150));
      expect(finished, isFalse);
      await write();
      final signal = await waiting.timeout(const Duration(seconds: 3));
      expect(signal.changed, isTrue);
      expect(signal.cursor, isNot(cursor));
      cursor = signal.cursor;
    }

    Note? note;
    await changed(() async {
      note = await writer.save(draft(title: '自动出现'));
    });
    expect((await other.loadPage(0)).items.single.title, '自动出现');
    await changed(() async {
      await writer.save(draft(id: note!.id, title: '自动改名'));
    });
    expect((await other.loadPage(0)).items.single.title, '自动改名');
    await changed(() async {
      await writer.setCompleted(note!.id, true);
    });
    expect((await other.loadPage(0)).items.single.completed, isTrue);
    await changed(() async {
      await writer.delete(note!.id);
    });
    expect((await other.loadPage(0)).items, isEmpty);
    final trash = (await other.listTrash()).single;
    await changed(() async {
      await writer.restore(trash.id);
    });
    expect((await other.loadPage(0)).items.single.title, '自动改名');
  });
  test('真实失败事务不改变同步游标，空闲心跳不误称变更', () async {
    final (api, account, repo) = await registered();
    final before = await api.watchChanges(account.id, null);
    await expectLater(
      api.request(
        'POST',
        '/api/notes/save',
        ownerId: account.id,
        body: {
          'title': '回滚',
          'content': '不应保存',
          'reminderAction': 'SET',
          'dueAt': DateTime.now()
              .subtract(const Duration(days: 1))
              .toUtc()
              .toIso8601String(),
        },
      ),
      throwsA(isA<ApiException>()),
    );
    final heartbeat = await api
        .watchChanges(account.id, before.cursor)
        .timeout(const Duration(seconds: 12));
    expect(heartbeat.changed, isFalse);
    expect(heartbeat.cursor, before.cursor);
    expect((await repo.loadPage(0)).items, isEmpty);
  });
  test('真实同步拒绝匿名访问，他人写入不唤醒本人等待', () async {
    final anonymous = client();
    expect(
      (await anonymous.transport.send(
        'GET',
        origin.resolve('/api/notes/changes'),
        headers: {},
      )).status,
      401,
    );
    final (_, _, other) = await registered();
    final (api, account, own) = await registered();
    final before = await api.watchChanges(account.id, null);
    var finished = false;
    final waiting = api.watchChanges(account.id, before.cursor).then((signal) {
      finished = true;
      return signal;
    });
    await Future<void>.delayed(const Duration(milliseconds: 150));
    await other.save(draft(title: '不属于本账号'));
    await Future<void>.delayed(const Duration(milliseconds: 150));
    expect(finished, isFalse);
    await own.save(draft(title: '属于本人'));
    expect((await waiting.timeout(const Duration(seconds: 3))).changed, isTrue);
    expect((await own.loadPage(0)).items.single.title, '属于本人');
  });
}
