import 'dart:async';

import 'api_client.dart';
import 'api_error.dart';

enum AutoSyncStatus { connecting, online, retrying, paused, loginRequired }

/// 前台单连接、有界退避。游标只在内存；重连/重新登录/恢复前台均重新核对当前数据。
class AutoSyncController {
  AutoSyncController({
    required this.watch,
    required this.onChange,
    required this.onStatus,
    this.minimumGap = const Duration(milliseconds: 250),
    this.initialRetry = const Duration(seconds: 1),
  });
  final Future<ChangeSignal> Function(String? cursor) watch;
  final Future<void> Function() onChange;
  final void Function(AutoSyncStatus status) onStatus;
  final Duration minimumGap;
  final Duration initialRetry;
  Timer? _timer;
  String? _cursor;
  bool _active = false;
  bool _disposed = false;
  bool _inFlight = false;
  int _generation = 0;
  int _failures = 0;
  AutoSyncStatus _status = AutoSyncStatus.paused;

  void _statusChanged(AutoSyncStatus value) {
    if (_disposed || _status == value) return;
    _status = value;
    onStatus(value);
  }

  void start() {
    if (_disposed || _active) return;
    _active = true;
    _generation++;
    _cursor = null;
    _failures = 0;
    _statusChanged(AutoSyncStatus.connecting);
    _schedule(Duration.zero);
  }

  void pause() {
    _active = false;
    _generation++;
    _timer?.cancel();
    _statusChanged(AutoSyncStatus.paused);
  }

  void restart() {
    pause();
    start();
  }

  void _schedule(Duration delay) {
    _timer?.cancel();
    if (!_active || _disposed || _inFlight) return;
    _timer = Timer(delay, _poll);
  }

  Future<void> _poll() async {
    if (!_active || _disposed || _inFlight) return;
    final generation = _generation;
    _inFlight = true;
    var delay = minimumGap;
    bool current() => !_disposed && _active && generation == _generation;
    try {
      final signal = await watch(_cursor);
      if (!current()) return;
      // 重连无游标、或版本改变时才取数据；无变化的心跳不拉列表/正文。
      if (_cursor == null || signal.changed || _cursor != signal.cursor) {
        await onChange();
        if (!current()) return;
      }
      _cursor = signal.cursor;
      _failures = 0;
      _statusChanged(AutoSyncStatus.online);
    } catch (error) {
      if (!current()) return;
      _cursor = null; // 不能把失败的补查当作已经同步。
      if (error is ApiException && error.needsLogin) {
        _active = false;
        _statusChanged(AutoSyncStatus.loginRequired);
      } else {
        _failures = (_failures + 1).clamp(1, 6);
        final millis = (initialRetry.inMilliseconds * (1 << (_failures - 1)))
            .clamp(1, 30000);
        delay = Duration(milliseconds: millis);
        _statusChanged(AutoSyncStatus.retrying);
      }
    } finally {
      _inFlight = false;
      // 暂停再恢复期间旧请求仍在途：等它结束再创建下一条，不叠加连接。
      if (!_disposed && _active) {
        _schedule(generation == _generation ? delay : Duration.zero);
      }
    }
  }

  void dispose() {
    _disposed = true;
    _active = false;
    _generation++;
    _timer?.cancel();
  }
}
