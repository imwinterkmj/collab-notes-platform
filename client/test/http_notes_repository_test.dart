import 'package:collab_notes_client/data/api_error.dart';
import 'package:collab_notes_client/data/http_notes_repository.dart';
import 'package:collab_notes_client/domain/note.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_transport.dart';

void main() {
  test('列表只取分页摘要，不为搜索批量拉正文', () async {
    final wire = FakeTransport(
      (r) => reply(pageJson([noteJson(content: null)], hasNext: true)),
    );
    final page = await HttpNotesRepository(
      fakeApi(wire, loggedIn: true),
      5,
    ).loadPage(0);
    expect(page.items.single.isSummary, isTrue);
    expect(page.items.single.content, '');
    expect(page.hasNext, isTrue);
    expect(wire.calls.length, 1);
    expect(wire.calls.single.uri.queryParameters, {'page': '0', 'size': '20'});
  });
  test('打开时获取完整正文和提醒，UTC 与 generation 保留', () async {
    final wire = FakeTransport(
      (r) =>
          reply(r.uri.path.endsWith('reminder') ? reminderJson() : noteJson()),
    );
    final note = await HttpNotesRepository(
      fakeApi(wire, loggedIn: true),
      5,
    ).detail(7);
    expect(note.content, '完整正文');
    expect(note.isSummary, isFalse);
    expect(note.createdAt.isUtc, isTrue);
    expect(note.reminderGeneration, 2);
    expect(note.reminderStatus, 'SCHEDULED');
    expect(wire.calls.length, 2);
  });
  test('提醒 404 重新核验记录，没有提醒不等于记录被删', () async {
    final wire = FakeTransport(
      (r) => r.uri.path.endsWith('reminder')
          ? reply({'code': 'REMINDER_NOT_FOUND'}, status: 404)
          : reply(noteJson()),
    );
    final repo = HttpNotesRepository(fakeApi(wire, loggedIn: true), 5);
    expect((await repo.detail(7)).reminderAt, isNull);
    expect(wire.calls.length, 3);
  });
  test('提醒读取失败不装作没有提醒，也不打开空正文编辑', () async {
    final wire = FakeTransport(
      (r) => r.uri.path.endsWith('reminder')
          ? reply({'code': 'INTERNAL_ERROR'}, status: 500)
          : reply(noteJson()),
    );
    await expectLater(
      HttpNotesRepository(fakeApi(wire, loggedIn: true), 5).detail(7),
      throwsA(isA<ApiException>()),
    );
  });
  test('取消状态不显示旧时间，已触发状态保留时间但不重新调度', () async {
    for (final state in ['CANCELLED', 'FIRED']) {
      final wire = FakeTransport(
        (r) => reply(
          r.uri.path.endsWith('reminder')
              ? reminderJson(status: state)
              : noteJson(),
        ),
      );
      final note = await HttpNotesRepository(
        fakeApi(wire, loggedIn: true),
        5,
      ).detail(7);
      expect(note.reminderStatus, state);
      expect(note.reminderAt == null, state == 'CANCELLED');
    }
  });
  test('统一保存是一个 POST/PUT，KEEP/CANCEL 不带 dueAt 或额外字段', () async {
    for (final action in ReminderAction.values) {
      final wire = FakeTransport(
        (r) => r.uri.path.endsWith('csrf')
            ? csrfReply()
            : reply({
                'note': noteJson(),
                'reminder': action == ReminderAction.set
                    ? reminderJson()
                    : null,
              }, status: 201),
      );
      final repo = HttpNotesRepository(fakeApi(wire, loggedIn: true), 5);
      await repo.save(
        NoteDraft(
          title: '文字😀',
          content: '正文',
          reminderAction: action,
          reminderAt: DateTime.now().add(const Duration(hours: 1)),
        ),
      );
      final writes = wire.calls.where((r) => r.method != 'GET').toList();
      expect(writes.length, 1);
      expect(writes.single.uri.path, '/api/notes/save');
      expect(writes.single.json['reminderAction'], action.name.toUpperCase());
      expect(
        writes.single.json.containsKey('dueAt'),
        action == ReminderAction.set,
      );
      expect(
        writes.single.json.keys.toSet(),
        action == ReminderAction.set
            ? {'title', 'content', 'reminderAction', 'dueAt'}
            : {'title', 'content', 'reminderAction'},
      );
    }
  });
  test('编辑使用 PUT /id/save，验证响应 ID 不被串换', () async {
    final wire = FakeTransport(
      (r) => r.uri.path.endsWith('csrf')
          ? csrfReply()
          : reply({'note': noteJson(id: 8), 'reminder': null}),
    );
    final repo = HttpNotesRepository(fakeApi(wire, loggedIn: true), 5);
    await expectLater(
      repo.save(
        const NoteDraft(
          id: 7,
          title: '编辑',
          content: '正文',
          reminderAction: ReminderAction.keep,
        ),
      ),
      throwsA(isA<ApiException>()),
    );
    expect(wire.calls.last.method, 'PUT');
    expect(wire.calls.last.uri.path, '/api/notes/7/save');
  });
  test('过去时间在本地拒绝，不发送写入', () async {
    final wire = FakeTransport((r) => reply({}));
    await expectLater(
      HttpNotesRepository(fakeApi(wire, loggedIn: true), 5).save(
        NoteDraft(
          title: '草稿',
          content: '正文',
          reminderAction: ReminderAction.set,
          reminderAt: DateTime.now().subtract(const Duration(days: 1)),
        ),
      ),
      throwsA(isA<DraftException>()),
    );
    expect(wire.calls, isEmpty);
  });
  test('缺少回收站接口的旧后端不会执行硬删除', () async {
    final wire = FakeTransport(
      (r) => reply({'code': 'NOTE_NOT_FOUND'}, status: 404),
    );
    await expectLater(
      HttpNotesRepository(fakeApi(wire, loggedIn: true), 5).delete(7),
      throwsA(isA<ApiException>()),
    );
    expect(wire.calls.any((r) => r.method == 'DELETE'), isFalse);
  });
  test('分页响应不匹配和无时区时间会拒绝', () async {
    for (final malformed in [
      pageJson([], page: 2),
      pageJson([
        {...noteJson(content: null), 'createdAt': '2030-01-01T10:00:00'},
      ]),
    ]) {
      final wire = FakeTransport((r) => reply(malformed));
      await expectLater(
        HttpNotesRepository(fakeApi(wire, loggedIn: true), 5).loadPage(0),
        throwsA(isA<ApiException>()),
      );
    }
  });
}
