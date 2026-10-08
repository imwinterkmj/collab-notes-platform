import 'package:collab_notes_client/app.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('入口提供真实登录，也保留明确的离线演示入口', (tester) async {
    await tester.pumpWidget(const NotesApp());
    await tester.pumpAndSettle();
    expect(find.text('把重要的事记下来'), findsOneWidget);
    expect(find.textContaining('连接真实后端'), findsOneWidget);
    expect(find.byKey(const Key('auth-password')), findsOneWidget);
    await tester.tap(find.byKey(const Key('enter-demo')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('demo-banner')), findsOneWidget);
    expect(find.byKey(const Key('new-note')), findsOneWidget);
  });

  testWidgets('退出再进入重置本次演示，不保留草稿或记录', (tester) async {
    await tester.pumpWidget(const NotesApp());
    await tester.tap(find.byKey(const Key('enter-demo')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('new-note')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('note-title')), '临时演示记录');
    await tester.tap(find.byKey(const Key('save-note')));
    await tester.pumpAndSettle();
    expect(find.text('临时演示记录'), findsOneWidget);
    await tester.tap(find.byKey(const Key('exit-demo')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('enter-demo')), findsOneWidget);
    await tester.tap(find.byKey(const Key('enter-demo')));
    await tester.pumpAndSettle();
    expect(find.text('临时演示记录'), findsNothing);
  });
}
