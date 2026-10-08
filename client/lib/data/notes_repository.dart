import '../domain/note.dart';

/// 后续新增 HTTP 实现；演示仓库不冒充真实 API。
abstract interface class NotesRepository {
  Future<List<Note>> listNotes();
  Future<List<TrashEntry>> listTrash();
  Future<Note> save(NoteDraft draft);
  Future<Note> setCompleted(int id, bool completed);
  Future<void> delete(int id);
  Future<Note> restore(int trashId);
}
