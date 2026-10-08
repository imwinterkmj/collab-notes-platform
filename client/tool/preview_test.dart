// 可选组件渲染预览：产物在根目录已忽略的 target/，不充当真机截图。
import 'dart:io';

import 'package:collab_notes_client/app.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../test/support/fake_transport.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const fontPath = String.fromEnvironment('PREVIEW_FONT');
  setUpAll(() async {
    if (fontPath.isEmpty) {
      throw StateError('请通过 PREVIEW_FONT 指定本机已有的中文字体；不下载或提交字体。');
    }
    final loader = FontLoader('Microsoft YaHei');
    loader.addFont(
      File(fontPath).readAsBytes().then((bytes) => ByteData.sublistView(bytes)),
    );
    await loader.load();
    final icons = FontLoader('MaterialIcons');
    icons.addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'));
    await icons.load();
  });

  Future<void> start(
    WidgetTester tester,
    Size size, {
    bool connected = false,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = size;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final wire = FakeTransport((r) {
      if (r.uri.path.endsWith('csrf')) return csrfReply();
      if (r.uri.path.endsWith('login')) {
        return reply({'id': 5, 'username': 'preview_user'});
      }
      if (r.uri.path.endsWith('trash')) {
        return reply({'items': [], 'limit': 30});
      }
      return reply(
        pageJson([
          noteJson(title: '周五下午面试', content: null),
          noteJson(id: 8, title: '整理本周计划', content: null),
        ]),
      );
    });
    await tester.pumpWidget(
      RepaintBoundary(
        key: const Key('preview-root'),
        child: NotesApp(apiFactory: () => fakeApi(wire), enableAutoSync: false),
      ),
    );
    await tester.pumpAndSettle();
    if (connected) {
      await tester.enterText(
        find.byKey(const Key('auth-username')),
        'preview_user',
      );
      await tester.enterText(
        find.byKey(const Key('auth-password')),
        'preview-password',
      );
      await tester.tap(find.byKey(const Key('submit-auth')));
      await tester.pumpAndSettle();
    }
  }

  Future<void> capture(WidgetTester tester, String name) async {
    expect(tester.takeException(), isNull);
    await expectLater(
      find.byKey(const Key('preview-root')),
      matchesGoldenFile('../../target/client-previews/$name.png'),
    );
  }

  testWidgets('欢迎页组件预览', (tester) async {
    await start(tester, const Size(390, 844));
    await capture(tester, 'welcome-phone');
  });
  testWidgets('手机主页组件预览', (tester) async {
    await start(tester, const Size(390, 844));
    await tester.tap(find.byKey(const Key('enter-demo')));
    await tester.pumpAndSettle();
    await capture(tester, 'home-phone');
  });
  testWidgets('电脑主页组件预览', (tester) async {
    await start(tester, const Size(1280, 800));
    await tester.tap(find.byKey(const Key('enter-demo')));
    await tester.pumpAndSettle();
    await capture(tester, 'home-desktop');
  });
  testWidgets('手机编辑组件预览', (tester) async {
    await start(tester, const Size(390, 844));
    await tester.tap(find.byKey(const Key('enter-demo')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('note-1')));
    await tester.pumpAndSettle();
    await capture(tester, 'editor-phone');
  });
  testWidgets('真实账号手机布局预览（接口替身数据）', (tester) async {
    await start(tester, const Size(390, 844), connected: true);
    await capture(tester, 'connected-phone');
  });
  testWidgets('真实账号电脑布局预览（接口替身数据）', (tester) async {
    await start(tester, const Size(1280, 800), connected: true);
    await capture(tester, 'connected-desktop');
  });
}
