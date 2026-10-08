/// 客户端时间按 UTC 保存，展示时转换为本地时间。
class Note {
  const Note({
    required this.id,
    required this.title,
    required this.content,
    required this.completed,
    required this.createdAt,
    required this.updatedAt,
    this.reminderAt,
    this.reminderStatus,
    this.reminderGeneration,
    this.isSummary = false,
  });
  final int id;
  final String title;
  final String content;
  final bool completed;
  final DateTime createdAt;
  final DateTime updatedAt;
  final DateTime? reminderAt;
  final String? reminderStatus;
  final int? reminderGeneration;
  final bool isSummary;
}

enum ReminderAction { keep, set, cancel }

class NoteDraft {
  const NoteDraft({
    this.id,
    required this.title,
    required this.content,
    required this.reminderAction,
    this.reminderAt,
  });
  final int? id;
  final String title;
  final String content;
  final ReminderAction reminderAction;
  final DateTime? reminderAt;
  void validate(DateTime now, {bool completed = false}) {
    if (title.trim().isEmpty || title.runes.length > 120) {
      throw const DraftException('标题需要 1～120 个字符，不能全为空白');
    }
    if (content.runes.length > 10000) {
      throw const DraftException('正文不能超过 10000 个字符');
    }
    if (reminderAction == ReminderAction.set) {
      if (completed) throw const DraftException('请先恢复为未完成，再设置提醒');
      if (reminderAt == null || !reminderAt!.isAfter(now)) {
        throw const DraftException('请选择未来的提醒时间，草稿不会丢失');
      }
    }
  }
}

class TrashEntry {
  const TrashEntry({
    required this.id,
    required this.note,
    required this.deletedAt,
  });
  final int id;
  final Note note;
  final DateTime deletedAt;
}

class DraftException implements Exception {
  const DraftException(this.message);
  final String message;
}

int compareNotes(Note a, Note b) {
  if (a.completed != b.completed) return a.completed ? 1 : -1;
  final time = b.updatedAt.compareTo(a.updatedAt);
  return time != 0 ? time : b.id.compareTo(a.id);
}

String formatLocalTime(DateTime value) {
  final local = value.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)} '
      '${two(local.hour)}:${two(local.minute)}';
}
