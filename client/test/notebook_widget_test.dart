import 'dart:async';

import 'package:collab_notes_client/data/demo_notes_repository.dart';
import 'package:collab_notes_client/domain/note.dart';
import 'package:collab_notes_client/ui/notebook_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';

class SlowSaveRepository extends DemoNotesRepository {
  SlowSaveRepository() : super(seed: false);
  final ready = Completer<void>();
  int calls = 0;
  @override
  Future<Note> save(NoteDraft draft) async {
    calls++;
    await ready.future;
    return super.save(draft);
  }
}

void main() {
  Future<void> open(
    WidgetTester tester,
    DemoNotesRepository repository, {
    Size size = const Size(390, 844),
    double scale = 1,
    TargetPlatform platform = TargetPlatform.android,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = size;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh', 'CN'),
        supportedLocales: const [Locale('zh', 'CN')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        theme: ThemeData(useMaterial3: true, platform: platform),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: NotebookPage(repository: repository, onExit: () {}),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, String key) async {
    final finder = find.byKey(Key(key));
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  testWidgets('手机底部导航，未完成排在已完成之前', (tester) async {
    await open(tester, DemoNotesRepository());
    expect(find.byKey(const Key('mobile-navigation')), findsOneWidget);
    expect(find.byKey(const Key('desktop-navigation')), findsNothing);
    expect(
      tester.getTopLeft(find.byKey(const Key('note-1'))).dy,
      lessThan(tester.getTopLeft(find.byKey(const Key('note-2'))).dy),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('Windows 宽窗侧边导航，编辑使用同一弹窗', (tester) async {
    await open(
      tester,
      DemoNotesRepository(),
      size: const Size(1280, 800),
      platform: TargetPlatform.windows,
    );
    expect(find.byKey(const Key('desktop-navigation')), findsOneWidget);
    expect(find.byKey(const Key('mobile-navigation')), findsNothing);
    await tap(tester, 'note-1');
    expect(find.text('编辑备忘录'), findsOneWidget);
    expect(find.byKey(const Key('note-title')), findsOneWidget);
    expect(
      tester.getSize(find.byKey(const Key('editor-surface'))).width,
      lessThanOrEqualTo(720),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('搜索正文和完成筛选', (tester) async {
    await open(tester, DemoNotesRepository());
    await tester.enterText(find.byKey(const Key('search-notes')), '索引');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('note-1')), findsOneWidget);
    expect(find.byKey(const Key('note-2')), findsNothing);
    await tester.enterText(find.byKey(const Key('search-notes')), '');
    await tap(tester, 'filter-2');
    expect(find.byKey(const Key('note-3')), findsOneWidget);
    expect(find.byKey(const Key('note-1')), findsNothing);
    await tap(tester, 'filter-1');
    expect(find.byKey(const Key('note-3')), findsNothing);
    expect(find.byKey(const Key('note-1')), findsOneWidget);
  });

  testWidgets('第一次新建即可选择提醒并一次保存', (tester) async {
    final repository = DemoNotesRepository(seed: false);
    await open(tester, repository);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '同一次保存');
    await tester.enterText(find.byKey(const Key('note-content')), '保留正文');
    await tap(tester, 'reminder-switch');
    await tap(tester, 'remind-in-hour');
    await tap(tester, 'save-note');
    final note = (await repository.listNotes()).single;
    expect(note.title, '同一次保存');
    expect(note.content, '保留正文');
    expect(note.reminderAt!.isAfter(DateTime.now().toUtc()), isTrue);
    expect(find.byKey(const Key('note-title')), findsNothing);
    expect(find.text('同一次保存'), findsOneWidget);
  });

  testWidgets('非法标题失败保留文字与时间，不部分创建', (tester) async {
    final repository = DemoNotesRepository(seed: false);
    await open(tester, repository);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '  ');
    await tester.enterText(find.byKey(const Key('note-content')), '失败也要保留');
    await tap(tester, 'reminder-switch');
    await tap(tester, 'remind-in-hour');
    await tap(tester, 'save-note');
    expect(find.byKey(const Key('editor-error')), findsOneWidget);
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('note-content')))
          .controller!
          .text,
      '失败也要保留',
    );
    expect(
      tester
          .widget<SwitchListTile>(find.byKey(const Key('reminder-switch')))
          .value,
      isTrue,
    );
    expect(find.text('尚未选择时间'), findsNothing);
    expect(await repository.listNotes(), isEmpty);
    await tester.enterText(find.byKey(const Key('note-title')), '修正后重试');
    await tap(tester, 'save-note');
    expect((await repository.listNotes()).single.title, '修正后重试');
  });

  testWidgets('相对数据时钟已过去的时间失败后保留草稿', (tester) async {
    final repository = DemoNotesRepository(
      seed: false,
      clock: () => DateTime.now().add(const Duration(days: 1)),
    );
    await open(tester, repository);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '时间失败草稿');
    await tap(tester, 'reminder-switch');
    await tap(tester, 'remind-in-hour');
    await tap(tester, 'save-note');
    expect(find.text('请选择未来的提醒时间，草稿不会丢失'), findsOneWidget);
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('note-title')))
          .controller!
          .text,
      '时间失败草稿',
    );
    expect(await repository.listNotes(), isEmpty);
  });

  testWidgets('取消关闭确认保留草稿，确认放弃才关闭', (tester) async {
    final repository = DemoNotesRepository(seed: false);
    await open(tester, repository);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '未保存');
    await tap(tester, 'close-editor');
    expect(find.text('放弃草稿？'), findsOneWidget);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('note-title')))
          .controller!
          .text,
      '未保存',
    );
    await tap(tester, 'close-editor');
    await tester.tap(find.text('放弃草稿'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('note-title')), findsNothing);
    expect(await repository.listNotes(), isEmpty);
  });

  testWidgets('Android 返回键也先确认未保存草稿', (tester) async {
    await open(tester, DemoNotesRepository(seed: false));
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '返回保护');
    await tester.pump();
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('放弃草稿？'), findsOneWidget);
  });

  testWidgets('标记完成会取消演示提醒，恢复不复活旧计划', (tester) async {
    final repository = DemoNotesRepository();
    await open(tester, repository);
    await tap(tester, 'note-1');
    await tap(tester, 'complete-note');
    final note = (await repository.listNotes()).firstWhere((n) => n.id == 1);
    expect(note.completed, isTrue);
    expect(note.reminderAt, isNull);
    await tap(tester, 'filter-2');
    await tap(tester, 'note-1');
    expect(
      tester
          .widget<SwitchListTile>(find.byKey(const Key('reminder-switch')))
          .onChanged,
      isNull,
    );
    await tap(tester, 'complete-note');
    expect(
      (await repository.listNotes()).firstWhere((n) => n.id == 1).reminderAt,
      isNull,
    );
  });

  testWidgets('删除二次确认，回收站恢复生成新记录无旧提醒', (tester) async {
    final repository = DemoNotesRepository();
    await open(tester, repository);
    await tap(tester, 'note-1');
    await tap(tester, 'delete-note');
    expect(find.textContaining('不是 30 天'), findsOneWidget);
    await tester.tap(find.text('移至回收站'));
    await tester.pumpAndSettle();
    final trash = (await repository.listTrash()).single;
    await tester.tap(
      find.descendant(
        of: find.byKey(const Key('mobile-navigation')),
        matching: find.text('回收站'),
      ),
    );
    await tester.pumpAndSettle();
    await tap(tester, 'restore-${trash.id}');
    expect(await repository.listTrash(), isEmpty);
    final restored = (await repository.listNotes()).firstWhere(
      (n) => n.title == '准备后端面试',
    );
    expect(restored.id, isNot(1));
    expect(restored.reminderAt, isNull);
    expect(find.text('演示回收站为空'), findsOneWidget);
  });

  testWidgets('设置入口只放全局开关，未实现的功能不可开启', (tester) async {
    await open(tester, DemoNotesRepository());
    await tap(tester, 'settings');
    expect(find.text('到期播放提示音'), findsOneWidget);
    expect(find.text('系统通知'), findsOneWidget);
    final switches = tester.widgetList<SwitchListTile>(
      find.byType(SwitchListTile),
    );
    expect(switches.length, 2);
    expect(switches.every((tile) => tile.onChanged == null), isTrue);
    expect(find.textContaining('关闭程序后不会提醒'), findsOneWidget);
  });

  testWidgets('保存进行中禁止重复提交和关闭', (tester) async {
    final repository = SlowSaveRepository();
    await open(tester, repository);
    await tap(tester, 'new-note');
    await tester.enterText(find.byKey(const Key('note-title')), '一次请求');
    await tester.tap(find.byKey(const Key('save-note')));
    await tester.pump();
    expect(
      tester.widget<FilledButton>(find.byKey(const Key('save-note'))).onPressed,
      isNull,
    );
    expect(
      tester
          .widget<IconButton>(find.byKey(const Key('close-editor')))
          .onPressed,
      isNull,
    );
    expect(repository.calls, 1);
    repository.ready.complete();
    await tester.pumpAndSettle();
    expect((await repository.listNotes()).length, 1);
  });

  testWidgets('窄屏大字体和软键盘下编辑无布局溢出', (tester) async {
    await open(
      tester,
      DemoNotesRepository(),
      size: const Size(320, 640),
      scale: 1.6,
    );
    expect(tester.takeException(), isNull);
    await tap(tester, 'note-1');
    tester.view.viewInsets = const FakeViewPadding(bottom: 230);
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('save-note')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
