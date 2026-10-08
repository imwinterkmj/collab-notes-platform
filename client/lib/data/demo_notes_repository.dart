import '../domain/note.dart';
import 'notes_repository.dart';

/// 本次演示的内存数据：不发请求、不读文件、不注册定时器。
class DemoNotesRepository implements NotesRepository {
  DemoNotesRepository({DateTime Function()? clock, bool seed = true})
    : _clock = clock ?? DateTime.now {
    if (seed) {
      final now = _now;
      _notes.addAll([
        Note(
          id: 1,
          title: '准备后端面试',
          content: '复习事务、索引和幂等。\n这是演示数据，不属于你的真实账号。',
          completed: false,
          createdAt: now.subtract(const Duration(days: 2)),
          updatedAt: now.subtract(const Duration(minutes: 10)),
          reminderAt: now.add(const Duration(hours: 3)),
        ),
        Note(
          id: 2,
          title: '整理本周计划',
          content: '先完成一个小目标，再回顾实现。',
          completed: false,
          createdAt: now.subtract(const Duration(days: 1)),
          updatedAt: now.subtract(const Duration(hours: 1)),
        ),
        Note(
          id: 3,
          title: '安卓测试包验收',
          content: '已确认能安装、打开和点击。真实提醒尚未接入。',
          completed: true,
          createdAt: now.subtract(const Duration(days: 1)),
          updatedAt: now,
        ),
      ]);
      _nextId = 4;
    }
  }
  final DateTime Function() _clock;
  final List<Note> _notes = [];
  final List<TrashEntry> _trash = [];
  int _nextId = 1;
  int _nextTrashId = 1;
  DateTime get _now => _clock().toUtc();
  @override
  Future<List<Note>> listNotes() async =>
      List<Note>.unmodifiable(<Note>[..._notes]..sort(compareNotes));
  @override
  Future<List<TrashEntry>> listTrash() async => List.unmodifiable(_trash);
  Note _find(int id) => _notes.firstWhere(
    (note) => note.id == id,
    orElse: () => throw const DraftException('这条演示备忘录已经不存在'),
  );
  @override
  Future<Note> save(NoteDraft draft) async {
    final before = draft.id == null ? null : _find(draft.id!);
    final now = _now;
    // 检查完成后才替换整条记录，失败不部分写入演示状态。
    draft.validate(now, completed: before?.completed ?? false);
    final reminder = switch (draft.reminderAction) {
      ReminderAction.keep => before?.reminderAt,
      ReminderAction.set => draft.reminderAt!.toUtc(),
      ReminderAction.cancel => null,
    };
    final note = Note(
      id: before?.id ?? _nextId++,
      title: draft.title,
      content: draft.content,
      completed: before?.completed ?? false,
      createdAt: before?.createdAt ?? now,
      updatedAt: now,
      reminderAt: reminder,
    );
    _notes.removeWhere((item) => item.id == note.id);
    _notes.add(note);
    return note;
  }

  @override
  Future<Note> setCompleted(int id, bool completed) async {
    final old = _find(id);
    if (old.completed == completed) return old;
    final note = Note(
      id: old.id,
      title: old.title,
      content: old.content,
      completed: completed,
      createdAt: old.createdAt,
      updatedAt: _now,
      reminderAt: completed ? null : old.reminderAt,
    );
    _notes.removeWhere((item) => item.id == id);
    _notes.add(note);
    return note;
  }

  @override
  Future<void> delete(int id) async {
    final note = _find(id);
    _trash.insert(
      0,
      TrashEntry(id: _nextTrashId++, note: note, deletedAt: _now),
    );
    _notes.removeWhere((item) => item.id == id);
    if (_trash.length > 30) _trash.removeLast();
  }

  @override
  Future<Note> restore(int trashId) async {
    final entry = _trash.firstWhere(
      (item) => item.id == trashId,
      orElse: () => throw const DraftException('这条演示回收记录已经不存在'),
    );
    final old = entry.note;
    final note = Note(
      id: _nextId++,
      title: old.title,
      content: old.content,
      completed: old.completed,
      createdAt: old.createdAt,
      updatedAt: _now,
    );
    _notes.add(note);
    _trash.remove(entry);
    return note;
  }
}
