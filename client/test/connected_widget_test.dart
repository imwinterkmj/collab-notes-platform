import 'dart:async';

import 'package:collab_notes_client/app.dart';
import 'package:collab_notes_client/data/api_error.dart';
import 'package:collab_notes_client/data/api_transport.dart';
import 'package:collab_notes_client/domain/note.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_transport.dart';

void main() {
  Future<void> tap(WidgetTester tester, String key) async {
    final finder = find.byKey(Key(key));
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  FakeTransport server({
    bool failSave = false,
    bool expireSave = false,
    bool failReminder = false,
    bool failLogout = false,
  }) {
    var saves = 0;
    return FakeTransport((r) {
      final path = r.uri.path;
      if (path.endsWith('csrf')) return csrfReply();
      if (path.endsWith('login')) {
        return reply({'id': 5, 'username': 'fixture_user'});
      }
      if (path.endsWith('logout')) {
        return failLogout
            ? reply({'code': 'INTERNAL_ERROR'}, status: 500)
            : reply(null, status: 204);
      }
      if (path.endsWith('register')) return reply({'id': 5}, status: 201);
      if (path == '/api/notes/trash') return reply({'items': [], 'limit': 30});
      if (path == '/api/notes') {
        return reply(pageJson([noteJson(content: null)]));
      }
      if (path.endsWith('reminder')) {
        return failReminder
            ? reply({'code': 'INTERNAL_ERROR'}, status: 500)
            : reply(reminderJson());
      }
      if (path.endsWith('save')) {
        saves++;
        if (failSave) {
          throw const ApiException(
            'NETWORK_ERROR',
            'fixture uncertainty',
            uncertain: true,
          );
        }
        if (expireSave && saves == 1) {
          return reply({'code': 'UNAUTHENTICATED'}, status: 401);
        }
        return reply({
          'note': noteJson(title: r.json['title'] as String),
          'reminder': null,
        }, status: 201);
      }
      return reply(noteJson());
    });
  }

  Future<void> login(
    WidgetTester tester,
    FakeTransport wire, {
    Size size = const Size(390, 844),
    double scale = 1,
    bool autoSync = false,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      NotesApp(apiFactory: () => fakeApi(wire), enableAutoSync: autoSync),
    );
    await tester.pumpAndSettle();
    if (scale != 1) {
      tester.platformDispatcher.textScaleFactorTestValue = scale;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.pumpAndSettle();
    }
    await tester.enterText(
      find.byKey(const Key('auth-username')),
      'fixture_user',
    );
    await tester.enterText(
      find.byKey(const Key('auth-password')),
      'fixture-password',
    );
    await tap(tester, 'submit-auth');
  }

  testWidgets('真实登录入口、账号列表与退出清理', (tester) async {
    final wire = server();
    await login(tester, wire);
    expect(find.byKey(const Key('server-banner')), findsOneWidget);
    expect(find.byKey(const Key('demo-banner')), findsNothing);
    expect(find.text('点击查看正文与提醒'), findsOneWidget);
    await tap(tester, 'logout');
    expect(find.byKey(const Key('server-banner')), findsNothing);
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('auth-password')))
          .controller!
          .text,
      '',
    );
    expect(wire.closed, isTrue);
  });
  testWidgets('注册成功仍停在登录入口，不冒充已经登录', (tester) async {
    final wire = server();
    await tester.pumpWidget(
      NotesApp(apiFactory: () => fakeApi(wire), enableAutoSync: false),
    );
    await tester.pumpAndSettle();
    await tap(tester, 'register-tab');
    await tester.enterText(
      find.byKey(const Key('auth-username')),
      'fixture_user',
    );
    await tester.enterText(
      find.byKey(const Key('auth-password')),
      'fixture-password',
    );
    await tap(tester, 'submit-auth');
    expect(find.byKey(const Key('auth-message')), findsOneWidget);
    expect(find.byKey(const Key('server-banner')), findsNothing);
    expect(wire.calls.any((r) => r.uri.path.endsWith('login')), isFalse);
  });
  testWidgets('点开摘要先获取完整正文，保存不覆盖为空', (tester) async {
    final wire = server();
    await login(tester, wire);
    await tap(tester, 'note-7');
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('note-content')))
          .controller!
          .text,
      '完整正文',
    );
    await tester.enterText(find.byKey(const Key('note-title')), '已修改标题');
    await tap(tester, 'save-note');
    final saved = wire.calls.singleWhere((r) => r.uri.path.endsWith('/7/save'));
    expect(saved.method, 'PUT');
    expect(saved.json['content'], '完整正文');
    expect(saved.json['reminderAction'], 'KEEP');
  });
  testWidgets('提醒加载失败不打开编辑，更不默默取消提醒', (tester) async {
    final wire = server(failReminder: true);
    await login(tester, wire);
    await tap(tester, 'note-7');
    expect(find.byKey(const Key('note-content')), findsNothing);
    expect(wire.calls.any((r) => r.uri.path.endsWith('save')), isFalse);
  });
  testWidgets('会话失效重新登录后草稿仍在且不自动重新提交', (tester) async {
    final wire = server(expireSave: true);
    await login(tester, wire);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '会话草稿');
    await tester.enterText(find.byKey(const Key('note-content')), '文字不能消失');
    await tap(tester, 'save-note');
    await tap(tester, 'editor-login-again');
    expect(
      tester.widget<TextField>(find.byKey(const Key('auth-username'))).enabled,
      isFalse,
    );
    await tester.enterText(
      find.byKey(const Key('auth-password')),
      'fixture-password',
    );
    await tap(tester, 'submit-auth');
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('note-title')))
          .controller!
          .text,
      '会话草稿',
    );
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('note-content')))
          .controller!
          .text,
      '文字不能消失',
    );
    expect(wire.calls.where((r) => r.uri.path.endsWith('save')).length, 1);
    await tap(tester, 'save-note');
    expect(wire.calls.where((r) => r.uri.path.endsWith('save')).length, 2);
  });
  testWidgets('断网结果不确定时草稿保留，保存禁用直到核对', (tester) async {
    final wire = server(failSave: true);
    await login(tester, wire);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '断网草稿');
    await tap(tester, 'save-note');
    expect(
      tester.widget<FilledButton>(find.byKey(const Key('save-note'))).onPressed,
      isNull,
    );
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('note-title')))
          .controller!
          .text,
      '断网草稿',
    );
    await tap(tester, 'verify-save-result');
    expect(find.text('先核对服务端结果'), findsOneWidget);
    await tester.tap(find.text('返回草稿'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<FilledButton>(find.byKey(const Key('save-note'))).onPressed,
      isNull,
    );
    expect(wire.calls.where((r) => r.uri.path.endsWith('save')).length, 1);
  });
  testWidgets('退出断网只称本机清除，不声称服务端退出确认', (tester) async {
    final wire = server(failLogout: true);
    await login(tester, wire);
    await tap(tester, 'logout');
    expect(find.textContaining('服务端退出未确认'), findsOneWidget);
    expect(find.byKey(const Key('server-banner')), findsNothing);
  });
  testWidgets('窄屏键盘下保存错误和恢复入口可滚动访问', (tester) async {
    final wire = server(expireSave: true);
    await login(tester, wire, size: const Size(320, 640), scale: 1.6);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '键盘草稿');
    tester.view.viewInsets = const FakeViewPadding(bottom: 230);
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpAndSettle();
    await tap(tester, 'save-note');
    expect(tester.takeException(), isNull);
    expect(find.byKey(const Key('editor-login-again')), findsOneWidget);
  });
  testWidgets('分页追加按 ID 去重，手动刷新重新从第一页开始', (tester) async {
    final base = server();
    final wire = FakeTransport((r) {
      if (r.uri.path == '/api/notes') {
        final page = int.parse(r.uri.queryParameters['page']!);
        return reply(
          pageJson(
            page == 0
                ? [noteJson(content: null)]
                : [noteJson(content: null), noteJson(id: 8, content: null)],
            page: page,
            hasNext: page == 0,
          ),
        );
      }
      return base.respond(r);
    });
    await login(tester, wire, size: const Size(1280, 1000));
    expect(find.text('已加载 1 条'), findsOneWidget);
    await tap(tester, 'load-more');
    expect(find.text('已加载 2 条'), findsOneWidget);
    expect(find.byKey(const Key('note-7')), findsOneWidget);
    expect(find.byKey(const Key('note-8')), findsOneWidget);
    expect(find.byKey(const Key('load-more')), findsNothing);
    await tap(tester, 'refresh-notes');
    expect(find.text('已加载 1 条'), findsOneWidget);
    expect(find.byKey(const Key('note-8')), findsNothing);
    expect(
      wire.calls
          .where((r) => r.uri.path == '/api/notes')
          .map((r) => r.uri.queryParameters['page']),
      ['0', '1', '0'],
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets('另一端更新自动显示，搜索和筛选保留', (tester) async {
    final base = server();
    final waiting = <Completer<WireResponse>>[];
    var title = '联动原标题';
    final wire = FakeTransport((r) {
      if (r.uri.path.endsWith('changes')) {
        final pending = Completer<WireResponse>();
        waiting.add(pending);
        return pending.future;
      }
      if (r.uri.path == '/api/notes') {
        return reply(pageJson([noteJson(title: title, content: null)]));
      }
      return base.respond(r);
    });
    addTearDown(() => tester.pumpWidget(const SizedBox.shrink()));
    await login(tester, wire, autoSync: true);
    waiting[0].complete(
      reply({'cursor': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1', 'changed': true}),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('search-notes')), '联动');
    await tap(tester, 'filter-1');
    await tester.pump(const Duration(milliseconds: 250));
    title = '联动另一端的新标题';
    waiting[1].complete(
      reply({'cursor': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-2', 'changed': true}),
    );
    await tester.pumpAndSettle();
    expect(find.text(title), findsOneWidget);
    expect(
      tester.widget<ChoiceChip>(find.byKey(const Key('filter-1'))).selected,
      isTrue,
    );
    expect(find.text('联动'), findsOneWidget);
    expect(wire.calls.where((r) => r.uri.path == '/api/notes').length, 3);
  });
  testWidgets('编辑中服务器更新仅提示，标题正文时间草稿不覆盖', (tester) async {
    final base = server();
    final waiting = <Completer<WireResponse>>[];
    final wire = FakeTransport((r) {
      if (r.uri.path.endsWith('changes')) {
        final pending = Completer<WireResponse>();
        waiting.add(pending);
        return pending.future;
      }
      return base.respond(r);
    });
    addTearDown(() => tester.pumpWidget(const SizedBox.shrink()));
    await login(tester, wire, autoSync: true);
    waiting[0].complete(
      reply({'cursor': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1', 'changed': true}),
    );
    await tester.pumpAndSettle();
    await tap(tester, 'note-7');
    await tester.enterText(find.byKey(const Key('note-title')), '我的标题草稿');
    await tester.enterText(find.byKey(const Key('note-content')), '我的正文草稿');
    final expectedTime = formatLocalTime(DateTime.utc(2030, 1, 1, 11));
    expect(find.text(expectedTime), findsOneWidget);
    final before = wire.calls.where((r) => r.uri.path == '/api/notes').length;
    await tester.pump(const Duration(milliseconds: 250));
    waiting[1].complete(
      reply({'cursor': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-2', 'changed': true}),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('editor-server-changed')), findsOneWidget);
    expect(find.text('我的标题草稿'), findsOneWidget);
    expect(find.text('我的正文草稿'), findsOneWidget);
    expect(find.text(expectedTime), findsOneWidget);
    expect(
      tester
          .widget<SwitchListTile>(find.byKey(const Key('reminder-switch')))
          .value,
      isTrue,
    );
    expect(wire.calls.where((r) => r.uri.path == '/api/notes').length, before);
    await tap(tester, 'save-note');
    expect(find.text('服务器有新变化'), findsOneWidget);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(find.text('我的标题草稿'), findsOneWidget);
    expect(wire.calls.any((r) => r.uri.path.endsWith('/7/save')), isFalse);
  });
  testWidgets('退出后迟到的同步响应不恢复旧账号页面', (tester) async {
    final base = server();
    final waiting = Completer<WireResponse>();
    final wire = FakeTransport(
      (r) => r.uri.path.endsWith('changes') ? waiting.future : base.respond(r),
    );
    addTearDown(() => tester.pumpWidget(const SizedBox.shrink()));
    await login(tester, wire, autoSync: true);
    await tap(tester, 'logout');
    waiting.complete(
      reply({'cursor': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1', 'changed': true}),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('server-banner')), findsNothing);
    expect(find.byKey(const Key('auth-username')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
