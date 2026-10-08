import 'dart:async';

import 'package:collab_notes_client/data/api_client.dart';
import 'package:collab_notes_client/data/api_error.dart';
import 'package:collab_notes_client/data/api_transport.dart';
import 'package:collab_notes_client/data/auto_sync_controller.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';

import 'support/fake_transport.dart';

const cursor1 = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1';
const cursor2 = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-2';

void main() {
  test('长轮询不堵塞普通写入，迟到 401 不会清除重新登录', () async {
    final pending = Completer<WireResponse>();
    final wire = FakeTransport((r) {
      if (r.uri.path.endsWith('changes')) return pending.future;
      if (r.uri.path.endsWith('csrf')) return csrfReply();
      if (r.uri.path.endsWith('login')) {
        return reply({'id': 5, 'username': 'fixture_user'});
      }
      return reply({});
    });
    final api = fakeApi(wire, loggedIn: true);
    final old = api.watchChanges(5, cursor1);
    await api.request('POST', '/api/notes/save', ownerId: 5);
    await api.login('fixture_user', 'x');
    pending.complete(reply({'code': 'UNAUTHENTICATED'}, status: 401));
    await expectLater(
      old,
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'CLIENT_CLOSED'),
      ),
    );
    expect(api.account?.id, 5);
    api.close();
  });
  test('同步只读请求使用 Cookie 快照，不采纳响应 Cookie 或跨账号调用', () async {
    final wire = FakeTransport(
      (r) => r.uri.path.endsWith('csrf')
          ? csrfReply()
          : r.uri.path.endsWith('login')
          ? reply({'id': 5, 'username': 'fixture_user'})
          : reply({'cursor': cursor1, 'changed': true}),
    );
    final api = fakeApi(wire);
    await api.login('fixture_user', 'x');
    final signal = await api.watchChanges(5, null);
    expect(signal.cursor, cursor1);
    expect(wire.calls.last.uri.hasQuery, isFalse);
    await expectLater(
      api.watchChanges(8, cursor1),
      throwsA(isA<ApiException>()),
    );
    api.close();
  });
  testWidgets('无变化心跳不读取列表，变化自动核对且每次只有一个等待请求', (tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    final responses = <Completer<ChangeSignal>>[];
    var refreshes = 0;
    final sync = AutoSyncController(
      watch: (_) {
        final result = Completer<ChangeSignal>();
        responses.add(result);
        return result.future;
      },
      onChange: () async {
        refreshes++;
      },
      onStatus: (_) {},
    );
    addTearDown(sync.dispose);
    sync.start();
    await tester.pump(const Duration(milliseconds: 1));
    responses[0].complete(const ChangeSignal(cursor1, true));
    await tester.pump();
    expect(refreshes, 1);
    await tester.pump(const Duration(milliseconds: 250));
    responses[1].complete(const ChangeSignal(cursor1, false));
    await tester.pump();
    expect(refreshes, 1);
    await tester.pump(const Duration(milliseconds: 250));
    responses[2].complete(const ChangeSignal(cursor2, true));
    await tester.pump();
    expect(refreshes, 2);
    expect(responses.length, 3);
    sync.dispose();
  });
  testWidgets('断线按 1/2 秒退避，重新连接先核对，不自动重放写入', (tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    var calls = 0;
    var refreshed = 0;
    final statuses = <AutoSyncStatus>[];
    final cursors = <String?>[];
    final last = Completer<ChangeSignal>();
    final sync = AutoSyncController(
      watch: (cursor) async {
        calls++;
        cursors.add(cursor);
        if (calls <= 2) throw const ApiException('NETWORK_ERROR', 'fixture');
        if (calls == 3) return const ChangeSignal(cursor1, true);
        return last.future;
      },
      onChange: () async {
        refreshed++;
      },
      onStatus: statuses.add,
    );
    addTearDown(sync.dispose);
    sync.start();
    await tester.pump(const Duration(milliseconds: 1));
    expect(calls, 1);
    await tester.pump(const Duration(milliseconds: 998));
    expect(calls, 1);
    await tester.pump(const Duration(milliseconds: 2));
    expect(calls, 2);
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(calls, 3);
    expect(cursors.take(3), [null, null, null]);
    expect(refreshed, 1);
    expect(statuses.last, AutoSyncStatus.online);
    sync.dispose();
  });
  testWidgets('后台暂停、恢复前台与销毁都不叠加等待或采纳旧响应', (tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    final responses = <Completer<ChangeSignal>>[];
    final cursors = <String?>[];
    var refreshed = 0;
    final sync = AutoSyncController(
      watch: (cursor) {
        cursors.add(cursor);
        final result = Completer<ChangeSignal>();
        responses.add(result);
        return result.future;
      },
      onChange: () async {
        refreshed++;
      },
      onStatus: (_) {},
    );
    sync.start();
    await tester.pump(const Duration(milliseconds: 1));
    sync.pause();
    sync.start();
    await tester.pump();
    expect(responses.length, 1);
    responses[0].complete(const ChangeSignal(cursor1, true));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1));
    expect(refreshed, 0);
    expect(responses.length, 2);
    expect(cursors, [null, null]);
    sync.dispose();
    responses[1].complete(const ChangeSignal(cursor2, true));
    await tester.pump();
    expect(refreshed, 0);
  });
  testWidgets('会话失效停止重试，重新登录后才恢复', (tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    var calls = 0;
    final states = <AutoSyncStatus>[];
    final sync = AutoSyncController(
      watch: (_) async {
        calls++;
        throw const ApiException('UNAUTHENTICATED', 'fixture');
      },
      onChange: () async {},
      onStatus: states.add,
    );
    addTearDown(sync.dispose);
    sync.start();
    await tester.pump(const Duration(milliseconds: 1));
    await tester.pump(const Duration(minutes: 1));
    expect(calls, 1);
    expect(states.last, AutoSyncStatus.loginRequired);
    sync.start();
    await tester.pump(const Duration(milliseconds: 1));
    expect(calls, 2);
    sync.dispose();
  });
}
