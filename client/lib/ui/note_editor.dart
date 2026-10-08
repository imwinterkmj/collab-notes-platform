import 'package:flutter/material.dart';

import '../data/notes_repository.dart';
import '../data/http_notes_repository.dart';
import '../data/api_error.dart';
import '../domain/note.dart';

class NoteEditor extends StatefulWidget {
  const NoteEditor({
    super.key,
    required this.repository,
    this.note,
    this.onReauthenticate,
    this.serverChanged,
  });
  final NotesRepository repository;
  final Note? note;
  final Future<bool> Function()? onReauthenticate;
  final ValueNotifier<bool>? serverChanged;
  @override
  State<NoteEditor> createState() => _NoteEditorState();
}

class _NoteEditorState extends State<NoteEditor> {
  late final TextEditingController _title;
  late final TextEditingController _content;
  late bool _reminderEnabled;
  DateTime? _reminderAt;
  bool _busy = false;
  bool _allowPop = false;
  bool _checkingClose = false;
  String? _error;
  bool _needsLogin = false;
  bool _uncertain = false;
  bool get _real => widget.repository is HttpNotesRepository;
  bool get _canWrite => !_busy && !_uncertain;

  @override
  void initState() {
    super.initState();
    _title = TextEditingController(text: widget.note?.title ?? '')
      ..addListener(_changed);
    _content = TextEditingController(text: widget.note?.content ?? '')
      ..addListener(_changed);
    _reminderAt = widget.note?.reminderAt;
    _reminderEnabled = _reminderAt != null;
  }

  void _changed() => setState(() {});
  bool get _dirty =>
      _title.text != (widget.note?.title ?? '') ||
      _content.text != (widget.note?.content ?? '') ||
      _reminderEnabled != (widget.note?.reminderAt != null) ||
      (_reminderEnabled && _reminderAt != widget.note?.reminderAt);

  @override
  void dispose() {
    _title.dispose();
    _content.dispose();
    super.dispose();
  }

  Future<bool> _confirm(String title, String message, String action) async =>
      await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: Text(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(action),
            ),
          ],
        ),
      ) ??
      false;

  void _finish([String? message]) {
    setState(() => _allowPop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.pop(context, message);
    });
  }

  Future<void> _close() async {
    if (_busy || _checkingClose) return;
    _checkingClose = true;
    final allowed =
        !_dirty || await _confirm('放弃草稿？', '未保存的文字和提醒时间将丢失。', '放弃草稿');
    _checkingClose = false;
    if (mounted && allowed) _finish();
  }

  Future<void> _pickReminder() async {
    final initial =
        (_reminderAt ?? DateTime.now().add(const Duration(hours: 1))).toLocal();
    final date = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(1970),
      lastDate: DateTime(2200),
      helpText: '选择提醒日期',
    );
    if (!mounted || date == null) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(initial),
      helpText: '选择提醒时间',
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(alwaysUse24HourFormat: true),
        child: child!,
      ),
    );
    if (!mounted || time == null) return;
    setState(
      () => _reminderAt = DateTime(
        date.year,
        date.month,
        date.day,
        time.hour,
        time.minute,
      ).toUtc(),
    );
  }

  Future<void> _run(Future<void> Function() action, String message) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
      if (mounted) _finish(message);
    } on DraftException catch (error) {
      if (mounted) setState(() => _error = error.message);
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = operationError(error);
          _needsLogin = error is ApiException && error.needsLogin;
          _uncertain =
              _real &&
              error is ApiException &&
              (error.uncertain || error.code == 'INVALID_RESPONSE');
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _save() async {
    if (!_canWrite) return;
    if (widget.note != null && widget.serverChanged?.value == true) {
      if (!await _confirm(
        '服务器有新变化',
        '当前草稿没有被覆盖，但可能已经落后于另一端。继续保存仍可能覆盖服务器新内容；也可取消并保留草稿。',
        '仍然保存',
      )) {
        return;
      }
      if (!mounted) return;
    }
    final reminderAction = !_reminderEnabled
        ? ReminderAction.cancel
        : widget.note?.reminderAt == _reminderAt && _reminderAt != null
        ? ReminderAction.keep
        : ReminderAction.set;
    await _run(() async {
      await widget.repository.save(
        NoteDraft(
          id: widget.note?.id,
          title: _title.text,
          content: _content.text,
          reminderAction: reminderAction,
          reminderAt: _reminderAt,
        ),
      );
    }, _real ? '已保存到后端数据库' : '已保存到本次演示内存');
  }

  Future<void> _complete() async {
    if (!_canWrite) return;
    if (_dirty &&
        !await _confirm('先放弃草稿？', '此操作只改变已保存记录的完成状态，不保存当前草稿。', '放弃并继续')) {
      return;
    }
    if (!mounted) return;
    final note = widget.note!;
    await _run(
      () async {
        await widget.repository.setCompleted(note.id, !note.completed);
      },
      note.completed
          ? '已恢复未完成；旧提醒不会恢复'
          : _real
          ? '已标记完成；待执行提醒已取消'
          : '已标记完成；演示提醒已取消',
    );
  }

  Future<void> _delete() async {
    if (!_canWrite) return;
    if (!await _confirm(
      _real ? '移至回收站？' : '移至演示回收站？',
      '保留最近 30 条已保存记录（不是 30 天）。未保存的草稿不会进入回收站。',
      '移至回收站',
    )) {
      return;
    }
    if (!mounted) return;
    await _run(
      () => widget.repository.delete(widget.note!.id),
      _real ? '已移至回收站' : '已移至演示回收站',
    );
  }

  Future<void> _loginAgain() async {
    if (_busy) return;
    final done = await widget.onReauthenticate?.call();
    if (mounted && done == true) {
      setState(() {
        _needsLogin = false;
        _error = null;
      });
    }
    // 明确等待用户再点保存；不自动回放失败请求，不覆盖草稿。
  }

  Future<void> _verify() async {
    if (_busy || !_real) return;
    setState(() => _busy = true);
    try {
      final repo = widget.repository as HttpNotesRepository;
      final results = widget.note == null
          ? (await repo.loadPage(0)).items
          : [await repo.detail(widget.note!.id)];
      if (!mounted) return;
      final acknowledged = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('先核对服务端结果'),
          content: SizedBox(
            width: 480,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '下方是服务端当前记录（新建只列第一页）。若本次已经保存，不要再次新建。无法核对时取消，草稿仍保留。',
                  ),
                  const SizedBox(height: 12),
                  for (final note in results)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text(
                        '#${note.id}  ${note.title}\n更新 ${formatLocalTime(note.updatedAt)}',
                      ),
                    ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('返回草稿'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('我已核对，允许继续操作'),
            ),
          ],
        ),
      );
      if (mounted && acknowledged == true) {
        setState(() {
          _uncertain = false;
          _error = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = operationError(error);
          _needsLogin = error is ApiException && error.needsLogin;
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope<String>(
    canPop: _allowPop || (!_dirty && !_busy),
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _close();
    },
    child: Dialog(
      insetPadding: const EdgeInsets.all(12),
      child: ConstrainedBox(
        key: const Key('editor-surface'),
        constraints: BoxConstraints(
          maxWidth: 720,
          maxHeight: MediaQuery.sizeOf(context).height * .9,
        ),
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 14, 8, 14),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      widget.note == null ? '新建备忘录' : '编辑备忘录',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  IconButton(
                    key: const Key('close-editor'),
                    tooltip: '关闭编辑',
                    onPressed: _busy ? null : _close,
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    TextField(
                      key: const Key('note-title'),
                      controller: _title,
                      enabled: !_busy,
                      textInputAction: TextInputAction.next,
                      decoration: InputDecoration(
                        labelText: '标题',
                        counterText: '${_title.text.runes.length}/120',
                      ),
                    ),
                    const SizedBox(height: 14),
                    TextField(
                      key: const Key('note-content'),
                      controller: _content,
                      enabled: !_busy,
                      minLines: 5,
                      maxLines: null,
                      decoration: InputDecoration(
                        labelText: '正文',
                        hintText: '写下需要记住的内容，可以留空',
                        counterText: '${_content.text.runes.length}/10000',
                      ),
                    ),
                    const SizedBox(height: 16),
                    SwitchListTile(
                      key: const Key('reminder-switch'),
                      contentPadding: EdgeInsets.zero,
                      title: const Text('一次性提醒'),
                      subtitle: Text(
                        widget.note?.completed == true
                            ? '先恢复为未完成，再设置提醒'
                            : _real
                            ? '时间保存在后端；App 系统提醒尚未接入'
                            : '演示时间字段，不会实际响铃或弹通知',
                      ),
                      value: _reminderEnabled,
                      onChanged: _busy || widget.note?.completed == true
                          ? null
                          : (value) => setState(() => _reminderEnabled = value),
                    ),
                    if (_reminderEnabled) ...[
                      if (widget.note?.reminderStatus == 'FIRED')
                        const Text('该批次站内通知已生成；保留时间不会重新触发。'),
                      Text(
                        _reminderAt == null
                            ? '尚未选择时间'
                            : formatLocalTime(_reminderAt!),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          OutlinedButton.icon(
                            key: const Key('pick-reminder'),
                            onPressed: _busy ? null : _pickReminder,
                            icon: const Icon(Icons.calendar_month),
                            label: const Text('选择日期和时间'),
                          ),
                          TextButton(
                            key: const Key('remind-in-hour'),
                            onPressed: _busy
                                ? null
                                : () => setState(() {
                                    _reminderAt = DateTime.now()
                                        .add(const Duration(hours: 1))
                                        .toUtc();
                                  }),
                            child: const Text('1 小时后'),
                          ),
                        ],
                      ),
                    ],
                    if (widget.note != null) ...[
                      const SizedBox(height: 18),
                      Text('创建：${formatLocalTime(widget.note!.createdAt)}'),
                      Text('更新：${formatLocalTime(widget.note!.updatedAt)}'),
                    ],
                    if (widget.serverChanged != null)
                      ValueListenableBuilder<bool>(
                        valueListenable: widget.serverChanged!,
                        builder: (context, changed, child) => changed
                            ? const Padding(
                                padding: EdgeInsets.only(top: 12),
                                child: Text(
                                  '同账号数据有更新待核对；当前文字与提醒时间草稿保留。',
                                  key: Key('editor-server-changed'),
                                ),
                              )
                            : const SizedBox.shrink(),
                      ),
                  ],
                ),
              ),
            ),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(
                        _error!,
                        key: const Key('editor-error'),
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  if (_needsLogin && widget.onReauthenticate != null)
                    TextButton(
                      key: const Key('editor-login-again'),
                      onPressed: _busy ? null : _loginAgain,
                      child: const Text('重新登录（保留草稿）'),
                    ),
                  if (_uncertain)
                    TextButton(
                      key: const Key('verify-save-result'),
                      onPressed: _busy ? null : _verify,
                      child: const Text('先核对保存结果'),
                    ),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilledButton.icon(
                        key: const Key('save-note'),
                        onPressed: _canWrite ? _save : null,
                        icon: const Icon(Icons.save_outlined),
                        label: Text(_busy ? '处理中…' : '保存'),
                      ),
                      if (widget.note != null) ...[
                        OutlinedButton(
                          key: const Key('complete-note'),
                          onPressed: _canWrite ? _complete : null,
                          child: Text(
                            widget.note!.completed ? '恢复未完成' : '标记完成',
                          ),
                        ),
                        TextButton(
                          key: const Key('delete-note'),
                          onPressed: _canWrite ? _delete : null,
                          child: Text(
                            '删除',
                            style: TextStyle(
                              color: Theme.of(context).colorScheme.error,
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
