import '../domain/note.dart';
import 'api_client.dart';
import 'api_error.dart';
import 'notes_repository.dart';

class NotePage {
  const NotePage(this.items, this.page, this.hasNext);
  final List<Note> items;
  final int page;
  final bool hasNext;
}

class HttpNotesRepository implements NotesRepository {
  HttpNotesRepository(this.api, this.ownerId);
  final ApiClient api;
  final int ownerId;
  Future<Object?> _get(String path) =>
      api.request('GET', path, ownerId: ownerId);
  Future<Object?> _write(String method, String path, {Object? body}) =>
      api.request(method, path, body: body, ownerId: ownerId);
  void _id(int id) {
    if (id <= 0) throw protocolError;
  }

  Note _note(Object? data, {Object? reminder, bool summary = false}) {
    final json = jsonObject(data);
    DateTime? dueAt;
    String? status;
    int? generation;
    if (reminder != null) {
      final selected = jsonObject(reminder);
      if (positiveId(selected['noteId']) != positiveId(json['id'])) {
        throw protocolError;
      }
      status = jsonString(selected['status']);
      if (!const ['SCHEDULED', 'FIRED', 'CANCELLED'].contains(status)) {
        throw protocolError;
      }
      generation = positiveId(selected['generation']);
      final time = utcTime(selected['dueAt']);
      if (status != 'CANCELLED') dueAt = time;
    }
    return Note(
      id: positiveId(json['id']),
      title: jsonString(json['title']),
      content: summary ? '' : jsonString(json['content']),
      completed: jsonBool(json['completed']),
      createdAt: utcTime(json['createdAt']),
      updatedAt: utcTime(json['updatedAt']),
      reminderAt: dueAt,
      reminderStatus: status,
      reminderGeneration: generation,
      isSummary: summary,
    );
  }

  Future<NotePage> loadPage(int page, {int size = 20}) async {
    if (page < 0 || page > 10000 || size < 1 || size > 100) throw protocolError;
    final json = jsonObject(await _get('/api/notes?page=$page&size=$size'));
    final items = json['items'];
    if (items is! List ||
        json['page'] != page ||
        json['size'] != size ||
        items.length > size) {
      throw protocolError;
    }
    return NotePage(
      List.unmodifiable(items.map((item) => _note(item, summary: true))),
      page,
      jsonBool(json['hasNext']),
    );
  }

  @override
  Future<List<Note>> listNotes() async => (await loadPage(0)).items;

  Future<Note> detail(int id) async {
    _id(id);
    final note = await _get('/api/notes/$id');
    Object? reminder;
    try {
      reminder = await _get('/api/notes/$id/reminder');
    } on ApiException catch (error) {
      if (error.status != 404 || error.code != 'REMINDER_NOT_FOUND') rethrow;
      // 404 可能由并发删除导致；重新核验本人记录仍存在，不把丢失视为无提醒。
      final checked = await _get('/api/notes/$id');
      if (positiveId(jsonObject(checked)['id']) != id) throw protocolError;
      return _note(checked);
    }
    final result = _note(note, reminder: reminder);
    if (result.id != id) throw protocolError;
    return result;
  }

  @override
  Future<List<TrashEntry>> listTrash() async {
    final data = jsonObject(await _get('/api/notes/trash'));
    final items = data['items'];
    if (data['limit'] != 30 || items is! List || items.length > 30) {
      throw protocolError;
    }
    return List.unmodifiable(
      items.map((item) {
        final json = jsonObject(item);
        return TrashEntry(
          id: positiveId(json['id']),
          note: _note(item),
          deletedAt: utcTime(json['deletedAt']),
        );
      }),
    );
  }

  @override
  Future<Note> save(NoteDraft draft) async {
    draft.validate(DateTime.now().toUtc());
    if (draft.id != null) _id(draft.id!);
    final body = <String, Object?>{
      'title': draft.title,
      'content': draft.content,
      'reminderAction': draft.reminderAction.name.toUpperCase(),
    };
    if (draft.reminderAction == ReminderAction.set) {
      body['dueAt'] = draft.reminderAt!.toUtc().toIso8601String();
    }
    final saved = jsonObject(
      await _write(
        draft.id == null ? 'POST' : 'PUT',
        draft.id == null ? '/api/notes/save' : '/api/notes/${draft.id}/save',
        body: body,
      ),
    );
    final result = _note(saved['note'], reminder: saved['reminder']);
    if (draft.id != null && result.id != draft.id) throw protocolError;
    return result;
  }

  @override
  Future<Note> setCompleted(int id, bool completed) async {
    _id(id);
    return _note(
      await _write(
        'PATCH',
        '/api/notes/$id/completion',
        body: {'completed': completed},
      ),
    );
  }

  @override
  Future<void> delete(int id) async {
    _id(id);
    // 不允许旧版硬删除后端混用：写入前确认新回收站接口存在且能访问。
    await listTrash();
    await _write('DELETE', '/api/notes/$id');
  }

  @override
  Future<Note> restore(int trashId) async {
    _id(trashId);
    return _note(await _write('POST', '/api/notes/trash/$trashId/restore'));
  }
}
