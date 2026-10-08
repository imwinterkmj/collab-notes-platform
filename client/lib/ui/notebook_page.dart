import 'package:flutter/material.dart';

import '../data/notes_repository.dart';
import '../data/http_notes_repository.dart';
import '../data/api_error.dart';
import '../data/auto_sync_controller.dart';
import '../domain/note.dart';
import 'note_editor.dart';

class NotebookPage extends StatefulWidget {
  const NotebookPage({
    super.key,
    required this.repository,
    required this.onExit,
    this.accountName,
    this.onReauthenticate,
    this.leaving = false,
    this.enableAutoSync = true,
  });
  final NotesRepository repository;
  final VoidCallback onExit;
  final String? accountName;
  final Future<bool> Function()? onReauthenticate;
  final bool leaving;
  final bool enableAutoSync;
  @override
  State<NotebookPage> createState() => _NotebookPageState();
}

class _NotebookPageState extends State<NotebookPage>
    with WidgetsBindingObserver {
  List<Note> _notes = [];
  List<TrashEntry> _trash = [];
  bool _busy = true;
  String? _error;
  String _search = '';
  int _page = 0;
  int _filter = 0;
  int _loadedPage = 0;
  bool _hasNext = false;
  bool _loadingMore = false;
  bool _opening = false;
  bool _needsLogin = false;
  AutoSyncController? _sync;
  AutoSyncStatus _syncStatus = AutoSyncStatus.connecting;
  bool _foreground = true;
  bool _editing = false;
  bool _syncing = false;
  bool _pendingSync = false;
  final _editorChanged = ValueNotifier<bool>(false);
  bool get _real => widget.repository is HttpNotesRepository;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    _foreground =
        lifecycle != AppLifecycleState.paused &&
        lifecycle != AppLifecycleState.hidden &&
        lifecycle != AppLifecycleState.detached;
    _reload().then((_) => _configureSync());
  }

  void _configureSync() {
    if (!mounted || !_real || !widget.enableAutoSync) return;
    final repo = widget.repository as HttpNotesRepository;
    _sync = AutoSyncController(
      watch: (cursor) => repo.api.watchChanges(repo.ownerId, cursor),
      onChange: () async {
        _pendingSync = true;
        if (_editing || _opening) _editorChanged.value = true;
        await _flushSync();
      },
      onStatus: (status) {
        if (!mounted) return;
        setState(() {
          _syncStatus = status;
          if (status == AutoSyncStatus.loginRequired) {
            _needsLogin = true;
            _error = '登录已失效，请重新登录；草稿仍保留。';
          }
        });
      },
    );
    if (_foreground && !_needsLogin && !widget.leaving) _sync!.start();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground =
        state != AppLifecycleState.paused &&
        state != AppLifecycleState.hidden &&
        state != AppLifecycleState.detached;
    if (!_foreground) {
      _sync?.pause();
    } else if (!_needsLogin && !widget.leaving) {
      _sync?.start();
    }
  }

  @override
  void didUpdateWidget(NotebookPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.leaving) _sync?.pause();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sync?.dispose();
    _editorChanged.dispose();
    super.dispose();
  }

  void _resumePendingSync() {
    if (mounted &&
        _pendingSync &&
        _foreground &&
        !_editing &&
        !_opening &&
        !_busy &&
        !_loadingMore &&
        !_syncing &&
        !_needsLogin &&
        !widget.leaving) {
      _sync?.restart();
    }
  }

  Future<void> _flushSync() async {
    if (!mounted ||
        !_pendingSync ||
        !_foreground ||
        _editing ||
        _opening ||
        _busy ||
        _loadingMore ||
        _syncing ||
        _needsLogin ||
        widget.leaving) {
      return;
    }
    setState(() => _syncing = true);
    _pendingSync = false;
    try {
      final repo = widget.repository as HttpNotesRepository;
      final merged = <int, Note>{};
      var pageNumber = 0;
      var hasNext = false;
      for (; pageNumber <= _loadedPage && pageNumber < 50; pageNumber++) {
        final page = await repo.loadPage(pageNumber);
        for (final note in page.items) {
          merged[note.id] = note;
        }
        hasNext = page.hasNext;
        if (!hasNext || pageNumber == _loadedPage || merged.length >= 1000) {
          break;
        }
      }
      final trash = await repo.listTrash();
      if (!mounted) return;
      if (!_foreground ||
          _editing ||
          _opening ||
          _busy ||
          _loadingMore ||
          widget.leaving) {
        _pendingSync = true;
        return;
      }
      setState(() {
        final sorted = merged.values.toList()..sort(compareNotes);
        _notes = sorted.take(1000).toList();
        _trash = trash;
        _loadedPage = pageNumber;
        _hasNext = hasNext;
        _error = null;
      });
    } catch (error) {
      _pendingSync = true;
      rethrow;
    } finally {
      if (mounted) setState(() => _syncing = false);
    }
  }

  String get _syncCaption => !widget.enableAutoSync
      ? '手动刷新模式'
      : switch (_syncStatus) {
          AutoSyncStatus.connecting => '正在连接自动同步…',
          AutoSyncStatus.online => _pendingSync ? '有更新待同步 · 当前草稿保留' : '自动同步已连接',
          AutoSyncStatus.retrying => '同步断开，正在自动重连 · 现有数据保留',
          AutoSyncStatus.paused => '后台暂停同步，返回后自动补查',
          AutoSyncStatus.loginRequired => '同步暂停，请重新登录',
        };

  Future<void> _reload() async {
    if (_syncing || _editing) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final remote = _real
          ? await (widget.repository as HttpNotesRepository).loadPage(0)
          : null;
      final notes = remote?.items ?? await widget.repository.listNotes();
      final trash = await widget.repository.listTrash();
      if (mounted) {
        setState(() {
          _notes = notes;
          _trash = trash;
          _loadedPage = 0;
          _hasNext = remote?.hasNext ?? false;
          _needsLogin = false;
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
      _resumePendingSync();
    }
  }

  void _message(String message) =>
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
  Future<void> _loadMore() async {
    if (!_real ||
        _loadingMore ||
        _busy ||
        _syncing ||
        !_hasNext ||
        _notes.length >= 1000) {
      return;
    }
    setState(() => _loadingMore = true);
    try {
      final page = await (widget.repository as HttpNotesRepository).loadPage(
        _loadedPage + 1,
      );
      if (!mounted) return;
      setState(() {
        final merged = {for (final note in _notes) note.id: note};
        for (final note in page.items) {
          merged[note.id] = note;
        }
        final sorted = merged.values.toList()..sort(compareNotes);
        _notes = sorted.take(1000).toList();
        _loadedPage = page.page;
        _hasNext = page.hasNext || sorted.length > 1000;
        _error = null;
      });
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = operationError(error);
          _needsLogin = error is ApiException && error.needsLogin;
        });
      }
    } finally {
      if (mounted) setState(() => _loadingMore = false);
      _resumePendingSync();
    }
  }

  Future<bool> _reauthenticate() async {
    final success = await widget.onReauthenticate?.call() == true;
    if (success && mounted) {
      setState(() => _needsLogin = false);
      if (_foreground) _sync?.restart();
    }
    return success;
  }

  Future<void> _loginAgain() async {
    if (await _reauthenticate() && mounted) {
      _needsLogin = false;
      await _reload();
      if (_foreground) _sync?.restart();
    }
  }

  Future<void> _edit([Note? note]) async {
    if (_busy || _opening || _loadingMore || _syncing || widget.leaving) return;
    _editorChanged.value = false;
    Note? detail = note;
    var openingFailed = false;
    if (_real && note != null) {
      setState(() => _opening = true);
      try {
        detail = await (widget.repository as HttpNotesRepository).detail(
          note.id,
        );
      } catch (error) {
        openingFailed = true;
        if (mounted) {
          setState(() {
            _error = operationError(error);
            _needsLogin = error is ApiException && error.needsLogin;
          });
        }
        return;
      } finally {
        if (mounted) setState(() => _opening = false);
        if (openingFailed) _resumePendingSync();
      }
    }
    if (!mounted) return;
    _editing = true;
    final message = await showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (context) => NoteEditor(
        repository: widget.repository,
        note: detail,
        onReauthenticate: _real ? _reauthenticate : null,
        serverChanged: _real ? _editorChanged : null,
      ),
    );
    _editing = false;
    if (!mounted) return;
    if (message == null) {
      _resumePendingSync();
      return;
    }
    // 保存成功与随后列表刷新分开；刷新失败不声称保存回滚。
    _message(message);
    await _reload();
    _resumePendingSync();
  }

  Future<void> _restore(TrashEntry entry) async {
    if (_busy || _syncing || widget.leaving) return;
    setState(() => _busy = true);
    try {
      await widget.repository.restore(entry.id);
      if (!mounted) return;
      _message(_real ? '已恢复记录；旧提醒不恢复' : '已恢复演示记录；旧提醒不恢复');
      await _reload();
    } catch (error) {
      if (mounted) {
        setState(() => _busy = false);
        _message(operationError(error));
      }
    }
  }

  void _settings() => showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('设置'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Text('到期播放提示音'),
              subtitle: Text('尚未接入音频与系统定时'),
              value: false,
              onChanged: null,
            ),
            const SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Text('系统通知'),
              subtitle: Text('尚未接入平台通知权限'),
              value: false,
              onChanged: null,
            ),
            const SizedBox(height: 16),
            Text(
              _real
                  ? '记录保存在后端数据库，重开 App 后重新登录即可读取。\n当前没有托盘后台运行或手机锁屏提醒，关闭程序后不会提醒。'
                  : '当前没有托盘后台运行或手机锁屏提醒。关闭程序后不会提醒。\n演示记录仅在内存，退出演示或重开程序都会重置。',
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('知道了'),
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final wide = constraints.maxWidth >= 840;
      return Scaffold(
        appBar: AppBar(
          title: Text(_page == 0 ? '备忘录' : '回收站'),
          actions: [
            if (_real)
              IconButton(
                key: const Key('refresh-notes'),
                tooltip: '刷新另一端的修改',
                onPressed:
                    _busy ||
                        _opening ||
                        _loadingMore ||
                        _syncing ||
                        widget.leaving
                    ? null
                    : _reload,
                icon: const Icon(Icons.refresh),
              ),
            IconButton(
              key: const Key('settings'),
              tooltip: '设置',
              onPressed: _settings,
              icon: const Icon(Icons.settings_outlined),
            ),
            IconButton(
              key: Key(_real ? 'logout' : 'exit-demo'),
              tooltip: _real ? '退出登录' : '退出演示',
              onPressed: _busy || _opening || _loadingMore || widget.leaving
                  ? null
                  : widget.onExit,
              icon: const Icon(Icons.logout),
            ),
            const SizedBox(width: 8),
          ],
        ),
        body: SafeArea(
          child: Row(
            children: [
              if (wide)
                NavigationRail(
                  key: const Key('desktop-navigation'),
                  selectedIndex: _page,
                  onDestinationSelected: (value) =>
                      setState(() => _page = value),
                  labelType: NavigationRailLabelType.all,
                  destinations: const [
                    NavigationRailDestination(
                      icon: Icon(Icons.notes_rounded),
                      label: Text('备忘录'),
                    ),
                    NavigationRailDestination(
                      icon: Icon(Icons.delete_outline),
                      label: Text('回收站'),
                    ),
                  ],
                ),
              if (wide) const VerticalDivider(width: 1),
              Expanded(
                child: Column(
                  children: [
                    Container(
                      width: double.infinity,
                      color: const Color(0xffe9efe6),
                      padding: const EdgeInsets.all(12),
                      child: Text(
                        _real
                            ? '${widget.accountName ?? '当前账号'} · 数据保存在后端 · 系统提醒尚未接入'
                            : '离线演示 · 仅内存数据 · 不连接真实账号 · 时间字段不会实际触发提醒',
                        key: Key(_real ? 'server-banner' : 'demo-banner'),
                      ),
                    ),
                    if (_real)
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 6,
                        ),
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            _syncCaption,
                            key: const Key('sync-status'),
                          ),
                        ),
                      ),
                    if (_opening || _syncing || widget.leaving)
                      const LinearProgressIndicator(),
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.all(12),
                        child: Row(
                          children: [
                            Expanded(child: Text(_error!)),
                            TextButton(
                              onPressed:
                                  _needsLogin && widget.onReauthenticate != null
                                  ? _loginAgain
                                  : _reload,
                              child: Text(_needsLogin ? '重新登录' : '重试'),
                            ),
                          ],
                        ),
                      ),
                    Expanded(
                      child: _busy
                          ? const Center(child: CircularProgressIndicator())
                          : _page == 0
                          ? _notesView(wide)
                          : _trashView(),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        bottomNavigationBar: wide
            ? null
            : NavigationBar(
                key: const Key('mobile-navigation'),
                selectedIndex: _page,
                onDestinationSelected: (value) => setState(() => _page = value),
                destinations: const [
                  NavigationDestination(
                    icon: Icon(Icons.notes_rounded),
                    label: '备忘录',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.delete_outline),
                    label: '回收站',
                  ),
                ],
              ),
      );
    },
  );

  Widget _notesView(bool wide) {
    final query = _search.toLowerCase();
    final filtered =
        _notes
            .where(
              (note) =>
                  (_filter == 0 || note.completed == (_filter == 2)) &&
                  ('${note.title}\n${note.content}'.toLowerCase().contains(
                    query,
                  )),
            )
            .toList()
          ..sort(compareNotes);
    return CustomScrollView(
      key: const Key('notes-list'),
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: EdgeInsets.all(wide ? 24 : 16),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _real
                            ? '已加载 ${_notes.length} 条'
                            : '${_notes.length} 条演示记录',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    FilledButton.icon(
                      key: const Key('new-note'),
                      onPressed:
                          _opening || _loadingMore || _syncing || widget.leaving
                          ? null
                          : () => _edit(),
                      icon: const Icon(Icons.add),
                      label: const Text('新建'),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                TextField(
                  key: const Key('search-notes'),
                  onChanged: (value) => setState(() => _search = value),
                  decoration: InputDecoration(
                    hintText: _real ? '仅搜索已加载记录的标题' : '搜索本次演示的标题和正文',
                    prefixIcon: const Icon(Icons.search),
                  ),
                ),
                const SizedBox(height: 12),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 6,
                    children: [
                      for (var i = 0; i < 3; i++)
                        ChoiceChip(
                          key: Key('filter-$i'),
                          label: Text(['全部', '未完成', '已完成'][i]),
                          selected: _filter == i,
                          onSelected: (_) => setState(() => _filter = i),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        if (filtered.isEmpty)
          const SliverFillRemaining(
            hasScrollBody: false,
            child: Center(child: Text('没有匹配的备忘录')),
          )
        else
          SliverPadding(
            padding: EdgeInsets.fromLTRB(wide ? 24 : 16, 0, wide ? 24 : 16, 24),
            sliver: SliverList.separated(
              itemCount: filtered.length,
              separatorBuilder: (context, index) => const SizedBox(height: 10),
              itemBuilder: (context, index) {
                final note = filtered[index];
                return Card(
                  margin: EdgeInsets.zero,
                  child: InkWell(
                    key: Key('note-${note.id}'),
                    borderRadius: BorderRadius.circular(12),
                    onTap: () => _edit(note),
                    child: Padding(
                      padding: const EdgeInsets.all(18),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Icon(
                                note.completed
                                    ? Icons.check_circle_outline
                                    : Icons.radio_button_unchecked,
                                size: 20,
                              ),
                              const SizedBox(width: 10),
                              Expanded(
                                child: Text(
                                  note.title,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium,
                                ),
                              ),
                              const SizedBox(width: 8),
                              Text(note.completed ? '已完成' : '未完成'),
                            ],
                          ),
                          if (note.content.isNotEmpty) ...[
                            const SizedBox(height: 10),
                            Text(
                              note.content,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                          if (note.isSummary)
                            const Padding(
                              padding: EdgeInsets.only(top: 10),
                              child: Text('点击查看正文与提醒'),
                            ),
                          const SizedBox(height: 12),
                          Wrap(
                            spacing: 18,
                            runSpacing: 6,
                            children: [
                              Text('更新 ${formatLocalTime(note.updatedAt)}'),
                              if (note.reminderAt != null)
                                Text(
                                  '${_real ? '提醒' : '演示提醒'} ${formatLocalTime(note.reminderAt!)}',
                                ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        if (_real)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  if (_hasNext && _notes.length < 1000)
                    OutlinedButton(
                      key: const Key('load-more'),
                      onPressed: _loadingMore || _syncing ? null : _loadMore,
                      child: Text(_loadingMore ? '加载中…' : '加载更多'),
                    ),
                  Text(
                    _notes.length >= 1000 && _hasNext
                        ? '已达本页会话加载上限 1000 条；刷新可重新获取最新记录，完整搜索后续接入。'
                        : _hasNext
                        ? '搜索与筛选仅覆盖已加载记录，不代表全部数据。'
                        : '当前分页已到末尾；前台自动获取新变化。',
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Widget _trashView() => Column(
    children: [
      const Padding(
        padding: EdgeInsets.all(16),
        child: Text('仅保留最近 30 条，不是 30 天。超出后最早记录不可恢复。恢复不恢复旧提醒。'),
      ),
      Expanded(
        child: _trash.isEmpty
            ? Center(child: Text(_real ? '回收站为空' : '演示回收站为空'))
            : ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: _trash.length,
                separatorBuilder: (context, index) =>
                    const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final entry = _trash[index];
                  return Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            entry.note.title,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            entry.note.content,
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 12),
                          Text('删除 ${formatLocalTime(entry.deletedAt)}'),
                          const SizedBox(height: 8),
                          OutlinedButton.icon(
                            key: Key('restore-${entry.id}'),
                            onPressed: _syncing || widget.leaving
                                ? null
                                : () => _restore(entry),
                            icon: const Icon(Icons.restore),
                            label: const Text('恢复'),
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
      ),
    ],
  );
}
