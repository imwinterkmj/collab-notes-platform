import 'package:collab_notes_client/data/demo_notes_repository.dart';
import 'package:collab_notes_client/domain/note.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late DateTime now;
  late DemoNotesRepository repository;
  setUp(() {
    now = DateTime.utc(2030, 1, 1, 10);
    repository = DemoNotesRepository(seed: false, clock: () => now);
  });

  NoteDraft draft({
    int? id,
    String title = '备忘录',
    String content = '',
    ReminderAction action = ReminderAction.keep,
    DateTime? reminderAt,
  }) => NoteDraft(
    id: id,
    title: title,
    content: content,
    reminderAction: action,
    reminderAt: reminderAt,
  );

  group('输入与时间边界', () {
    test('空白标题被拒绝，不创建记录', () async {
      for (final title in ['', '  ', '\n\t']) {
        await expectLater(
          repository.save(draft(title: title)),
          throwsA(isA<DraftException>()),
        );
      }
      expect(await repository.listNotes(), isEmpty);
    });
    test('标题按 Unicode 码点限制，保留原文与空正文', () async {
      final title = '😀' * 120;
      final note = await repository.save(draft(title: title));
      expect(note.title, title);
      expect(note.content, '');
      await expectLater(
        repository.save(draft(title: '😀' * 121)),
        throwsA(isA<DraftException>()),
      );
      final spaced = await repository.save(draft(title: '  原文  '));
      expect(spaced.title, '  原文  ');
    });
    test('正文允许 10000 码点，超过后失败', () async {
      await repository.save(draft(content: '😀' * 10000));
      await expectLater(
        repository.save(draft(content: '😀' * 10001)),
        throwsA(isA<DraftException>()),
      );
      expect((await repository.listNotes()).length, 1);
    });
    test('SET 必须选择严格晚于当前时刻的时间', () async {
      for (final time in [
        null,
        now,
        now.subtract(const Duration(seconds: 1)),
      ]) {
        await expectLater(
          repository.save(draft(action: ReminderAction.set, reminderAt: time)),
          throwsA(isA<DraftException>()),
        );
      }
      expect(await repository.listNotes(), isEmpty);
      final note = await repository.save(
        draft(
          action: ReminderAction.set,
          reminderAt: now.add(const Duration(seconds: 1)),
        ),
      );
      expect(note.reminderAt, now.add(const Duration(seconds: 1)));
    });
    test('创建时间和提醒时间统一转 UTC', () async {
      now = DateTime(2030, 1, 1, 10);
      final chosen = now.add(const Duration(hours: 2));
      final note = await repository.save(
        draft(action: ReminderAction.set, reminderAt: chosen),
      );
      expect(note.createdAt.isUtc, isTrue);
      expect(note.updatedAt.isUtc, isTrue);
      expect(note.reminderAt!.isUtc, isTrue);
      expect(note.reminderAt!.isAtSameMomentAs(chosen), isTrue);
    });
    test('已完成记录不能重新设置提醒', () async {
      final note = await repository.save(draft());
      await repository.setCompleted(note.id, true);
      await expectLater(
        repository.save(
          draft(
            id: note.id,
            action: ReminderAction.set,
            reminderAt: now.add(const Duration(hours: 1)),
          ),
        ),
        throwsA(isA<DraftException>()),
      );
      expect((await repository.listNotes()).single.completed, isTrue);
    });
  });

  group('演示保存与状态', () {
    test('新建一次保存正文和未来提醒；编辑保留创建时间', () async {
      final note = await repository.save(
        draft(
          title: '第一版',
          content: '正文',
          action: ReminderAction.set,
          reminderAt: now.add(const Duration(hours: 1)),
        ),
      );
      now = now.add(const Duration(minutes: 1));
      final saved = await repository.save(
        draft(
          id: note.id,
          title: '第二版',
          content: '更新正文',
          action: ReminderAction.set,
          reminderAt: now.add(const Duration(hours: 2)),
        ),
      );
      expect(saved.id, note.id);
      expect(saved.createdAt, note.createdAt);
      expect(saved.updatedAt, now);
      expect(saved.title, '第二版');
      expect(saved.content, '更新正文');
      expect(saved.reminderAt, now.add(const Duration(hours: 2)));
      expect((await repository.listNotes()).length, 1);
    });
    test('保存失败不部分替换标题、正文或提醒', () async {
      final old = await repository.save(
        draft(
          title: '已保存',
          content: '旧正文',
          action: ReminderAction.set,
          reminderAt: now.add(const Duration(hours: 1)),
        ),
      );
      await expectLater(
        repository.save(
          draft(
            id: old.id,
            title: '草稿',
            content: '新正文',
            action: ReminderAction.set,
            reminderAt: now,
          ),
        ),
        throwsA(isA<DraftException>()),
      );
      expect((await repository.listNotes()).single, same(old));
    });
    test('KEEP 保留已过去的演示时间，CANCEL 清除时间', () async {
      final old = await repository.save(
        draft(
          action: ReminderAction.set,
          reminderAt: now.add(const Duration(minutes: 1)),
        ),
      );
      now = now.add(const Duration(hours: 1));
      final kept = await repository.save(draft(id: old.id));
      expect(kept.reminderAt, old.reminderAt);
      final cancelled = await repository.save(
        draft(id: old.id, action: ReminderAction.cancel),
      );
      expect(cancelled.reminderAt, isNull);
    });
    test('完成取消提醒，恢复未完成不复活旧提醒', () async {
      final note = await repository.save(
        draft(
          action: ReminderAction.set,
          reminderAt: now.add(const Duration(hours: 1)),
        ),
      );
      now = now.add(const Duration(minutes: 1));
      final completed = await repository.setCompleted(note.id, true);
      expect(completed.completed, isTrue);
      expect(completed.reminderAt, isNull);
      expect(completed.updatedAt, now);
      final restored = await repository.setCompleted(note.id, false);
      expect(restored.completed, isFalse);
      expect(restored.reminderAt, isNull);
    });
    test('重复同值完成不刷新更新时间', () async {
      final note = await repository.save(draft());
      now = now.add(const Duration(hours: 1));
      expect(await repository.setCompleted(note.id, false), same(note));
    });
    test('列表先未完成，再更新时间和 ID 倒序', () async {
      final older = await repository.save(draft(title: '旧未完成'));
      now = now.add(const Duration(minutes: 1));
      final first = await repository.save(draft(title: '同刻一'));
      final second = await repository.save(draft(title: '同刻二'));
      now = now.add(const Duration(minutes: 1));
      await repository.setCompleted(older.id, true);
      expect((await repository.listNotes()).map((note) => note.id), [
        second.id,
        first.id,
        older.id,
      ]);
    });
    test('默认演示种子也遵循未完成优先', () async {
      final seeded = DemoNotesRepository(clock: () => now);
      expect((await seeded.listNotes()).map((note) => note.id), [1, 2, 3]);
    });
    test('返回列表不可修改，旧列表不会跟随后续写入', () async {
      final snapshot = await repository.listNotes();
      await repository.save(draft());
      expect(snapshot, isEmpty);
      expect(() => snapshot.clear(), throwsUnsupportedError);
      expect(
        () async => (await repository.listTrash()).clear(),
        throwsUnsupportedError,
      );
    });
    test('未知记录不能保存、完成或删除', () async {
      await expectLater(
        repository.save(draft(id: 999)),
        throwsA(isA<DraftException>()),
      );
      await expectLater(
        repository.setCompleted(999, true),
        throwsA(isA<DraftException>()),
      );
      await expectLater(repository.delete(999), throwsA(isA<DraftException>()));
      expect(await repository.listNotes(), isEmpty);
      expect(await repository.listTrash(), isEmpty);
    });
  });

  group('最近 30 条回收站', () {
    test('删除保存快照，恢复使用新 ID 且不恢复旧提醒', () async {
      final old = await repository.save(
        draft(
          title: '保留原文',
          content: '正文',
          action: ReminderAction.set,
          reminderAt: now.add(const Duration(hours: 1)),
        ),
      );
      now = now.add(const Duration(minutes: 1));
      await repository.delete(old.id);
      final trash = (await repository.listTrash()).single;
      expect(trash.note, same(old));
      expect(trash.deletedAt, now);
      expect(await repository.listNotes(), isEmpty);
      now = now.add(const Duration(minutes: 1));
      final restored = await repository.restore(trash.id);
      expect(restored.id, isNot(old.id));
      expect(restored.title, old.title);
      expect(restored.content, old.content);
      expect(restored.createdAt, old.createdAt);
      expect(restored.updatedAt, now);
      expect(restored.completed, old.completed);
      expect(restored.reminderAt, isNull);
      expect(await repository.listTrash(), isEmpty);
      await expectLater(
        repository.restore(trash.id),
        throwsA(isA<DraftException>()),
      );
    });
    test('第 31 条淘汰最早快照，而非按天数保留', () async {
      for (var i = 0; i < 31; i++) {
        final note = await repository.save(draft(title: '$i'));
        await repository.delete(note.id);
      }
      final trash = await repository.listTrash();
      expect(trash.length, 30);
      expect(trash.first.note.title, '30');
      expect(trash.last.note.title, '1');
      await expectLater(repository.restore(1), throwsA(isA<DraftException>()));
    });
    test('恢复已完成记录时保留完成状态', () async {
      final note = await repository.save(draft());
      await repository.setCompleted(note.id, true);
      await repository.delete(note.id);
      final restored = await repository.restore(
        (await repository.listTrash()).single.id,
      );
      expect(restored.completed, isTrue);
    });
    test('独立演示实例不共享数据', () async {
      await repository.save(draft(title: '仅这次演示'));
      final other = DemoNotesRepository(seed: false, clock: () => now);
      expect(await other.listNotes(), isEmpty);
      expect(await other.listTrash(), isEmpty);
    });
  });
}
