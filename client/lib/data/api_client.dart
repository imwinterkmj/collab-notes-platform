import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'api_error.dart';
import 'api_transport.dart';

class Account {
  const Account(this.id, this.username);
  final int id;
  final String username;
  @override
  String toString() => 'Account[redacted]';
}

Map<String, dynamic> jsonObject(Object? value) {
  if (value is! Map<String, dynamic>) throw protocolError;
  return value;
}

int positiveId(Object? value) {
  if (value is! int || value <= 0) throw protocolError;
  return value;
}

String jsonString(Object? value) {
  if (value is! String) throw protocolError;
  return value;
}

bool jsonBool(Object? value) {
  if (value is! bool) throw protocolError;
  return value;
}

DateTime utcTime(Object? value) {
  final text = jsonString(value);
  if (!RegExp(r'(Z|[+-]\d\d:\d\d)$').hasMatch(text)) throw protocolError;
  final parsed = DateTime.tryParse(text);
  if (parsed == null) throw protocolError;
  return parsed.toUtc();
}

class ApiClient {
  ApiClient({required this.origin, required this.transport});
  final Uri origin;
  final ApiTransport transport;
  Account? account;
  String? _session;
  bool _sessionSecure = false;
  String? _csrf;
  Future<void> _tail = Future.value();
  int _epoch = 0;
  int _authGeneration = 0;
  bool _closed = false;

  Future<T> _serial<T>(Future<T> Function() work) {
    final epoch = _epoch;
    final result = _tail.then((_) async {
      if (_closed || epoch != _epoch) {
        throw const ApiException('CLIENT_CLOSED', '会话已结束，请重新登录。');
      }
      return work();
    });
    _tail = result.then<void>(
      (_) {},
      onError: (Object error, StackTrace trace) {},
    );
    return result;
  }

  void _forget() {
    _authGeneration++;
    account = null;
    _session = null;
    _csrf = null;
    _sessionSecure = false;
  }

  void _cookies(List<Cookie> cookies) {
    for (final cookie in cookies) {
      if (cookie.name != 'JSESSIONID' ||
          (cookie.domain != null &&
              cookie.domain!.toLowerCase() != origin.host.toLowerCase()) ||
          (cookie.path != null && cookie.path != '/')) {
        continue;
      }
      if (cookie.maxAge == 0 ||
          (cookie.expires != null &&
              !cookie.expires!.isAfter(DateTime.now().toUtc()))) {
        _session = null;
        continue;
      }
      if (!RegExp(r'^[a-zA-Z0-9._~-]{1,256}$').hasMatch(cookie.value)) {
        throw protocolError;
      }
      if (cookie.secure && origin.scheme != 'https') continue;
      _session = cookie.value;
      _sessionSecure = cookie.secure;
    }
  }

  Future<Object?> _raw(
    String method,
    String path, {
    Object? body,
    bool csrf = false,
  }) async {
    final epoch = _epoch;
    final headers = <String, String>{};
    if (_session != null && (!_sessionSecure || origin.scheme == 'https')) {
      headers[HttpHeaders.cookieHeader] = 'JSESSIONID=$_session';
    }
    if (csrf && _csrf != null) headers['X-CSRF-TOKEN'] = _csrf!;
    final response = await transport.send(
      method,
      origin.resolve(path),
      headers: headers,
      body: body == null ? null : jsonEncode(body),
    );
    if (_closed || epoch != _epoch) {
      throw const ApiException('CLIENT_CLOSED', '会话已结束，请重新登录。');
    }
    // 不接受重定向响应的 Cookie，更不会跟随重定向携带凭据。
    if (response.status >= 300 && response.status < 400) {
      throw const ApiException('REDIRECT_BLOCKED', '服务返回重定向，请检查 API 地址与访问限制。');
    }
    _cookies(response.cookies);
    Object? decoded;
    if (response.body.isNotEmpty) {
      try {
        decoded = jsonDecode(response.body);
      } on FormatException {
        throw protocolError;
      }
    }
    if (response.status >= 200 && response.status < 300) return decoded;
    final code = decoded is Map && decoded['code'] is String
        ? decoded['code'] as String
        : 'HTTP_ERROR';
    if (response.status == 401 && code != 'INVALID_CREDENTIALS') {
      _forget();
      throw serverError(401, 'UNAUTHENTICATED');
    }
    // 不显示/记录响应正文、字段值、Cookie、token 或密码。
    final error = serverError(response.status, code);
    if (method != 'GET' && response.status >= 500) {
      throw ApiException(
        error.code,
        '服务异常，写入结果待核对；请先刷新核对，不要直接重复新建。',
        status: error.status,
        uncertain: true,
      );
    }
    throw error;
  }

  Future<void> _fetchCsrf() async {
    final data = jsonObject(await _raw('GET', '/api/auth/csrf'));
    if (data['headerName'] != 'X-CSRF-TOKEN') throw protocolError;
    final token = jsonString(data['token']);
    if (token.isEmpty ||
        token.length > 2048 ||
        token.contains('\r') ||
        token.contains('\n')) {
      throw protocolError;
    }
    _csrf = token;
  }

  Future<Object?> _write(String method, String path, Object? body) async {
    if (_csrf == null) await _fetchCsrf();
    try {
      return await _raw(method, path, body: body, csrf: true);
    } on ApiException catch (error) {
      // 只重试已由安全过滤器拒绝、尚未执行控制器的 CSRF_INVALID。
      // 超时/断网/5xx/401 从不自动重放写入。
      if (error.status != 403 || error.code != 'CSRF_INVALID') rethrow;
      _csrf = null;
      await _fetchCsrf();
      return _raw(method, path, body: body, csrf: true);
    }
  }

  void validateCredentials(String username, String password) {
    if (!RegExp(r'^[a-z0-9_]{3,32}$').hasMatch(username)) {
      throw const ApiException('LOCAL_INPUT', '用户名须为 3～32 位小写字母、数字或下划线。');
    }
    if (password.trim().isEmpty || password.runes.length > 128) {
      throw const ApiException(
        'LOCAL_INPUT',
        '密码须为 1～128 个字符，不能全为空白；生产密码规则仍需强化。',
      );
    }
  }

  Account _account(Object? value) {
    final data = jsonObject(value);
    return Account(positiveId(data['id']), jsonString(data['username']));
  }

  Future<void> register(String username, String password) => _serial(() async {
    validateCredentials(username, password);
    await _write('POST', '/api/users/register', {
      'username': username,
      'password': password,
    });
    // 注册不自动当作登录，复用同一次匿名会话，随后用户明确点击登录。
  });
  Future<Account> login(
    String username,
    String password, {
    int? expectedAccountId,
  }) => _serial(() async {
    validateCredentials(username, password);
    _forget();
    try {
      final user = _account(
        await _write('POST', '/api/auth/login', {
          'username': username,
          'password': password,
        }),
      );
      if (expectedAccountId != null && user.id != expectedAccountId) {
        try {
          await _fetchCsrf();
          await _write('POST', '/api/auth/logout', null);
        } catch (_) {}
        throw const ApiException('ACCOUNT_MISMATCH', '账号不匹配；为保护草稿，不能切换为其他账号。');
      }
      // 登录会轮换 JSESSIONID 并清除旧 token；下次写入必须重取。
      _csrf = null;
      account = user;
      return user;
    } catch (_) {
      _forget();
      rethrow;
    }
  });
  Future<void> logout() => _serial(() async {
    try {
      await _write('POST', '/api/auth/logout', null);
    } finally {
      _forget();
    }
  });
  Future<Account> me() =>
      _serial(() async => _account(await _raw('GET', '/api/auth/me')));

  /// 长轮询单独占一个只读请求，不堵塞普通 API 的串行写入队列。
  /// 使用当前会话快照；不采纳迟到 Cookie，不允许旧 401 清除新登录。
  Future<ChangeSignal> watchChanges(int ownerId, String? cursor) async {
    if (_closed || account?.id != ownerId) {
      throw serverError(401, 'UNAUTHENTICATED');
    }
    final epoch = _epoch;
    final generation = _authGeneration;
    final headers = <String, String>{};
    if (_session != null && (!_sessionSecure || origin.scheme == 'https')) {
      headers[HttpHeaders.cookieHeader] = 'JSESSIONID=$_session';
    }
    final uri = origin
        .resolve('/api/notes/changes')
        .replace(queryParameters: cursor == null ? null : {'cursor': cursor});
    final response = await transport.send('GET', uri, headers: headers);
    if (_closed ||
        epoch != _epoch ||
        generation != _authGeneration ||
        account?.id != ownerId) {
      throw const ApiException('CLIENT_CLOSED', '旧同步连接已结束。');
    }
    if (response.status == 401) {
      _forget();
      throw serverError(401, 'UNAUTHENTICATED');
    }
    if (response.status != 200) {
      throw ApiException(
        'SYNC_UNAVAILABLE',
        '自动同步暂时不可用，可保留草稿或手动刷新。',
        status: response.status,
      );
    }
    Object? body;
    try {
      body = jsonDecode(response.body);
    } on FormatException {
      throw protocolError;
    }
    final data = jsonObject(body);
    final value = jsonString(data['cursor']);
    if (!RegExp(r'^[0-9a-f]{32}-[0-9]{1,20}$').hasMatch(value)) {
      throw protocolError;
    }
    return ChangeSignal(value, jsonBool(data['changed']));
  }

  Future<Object?> request(
    String method,
    String path, {
    Object? body,
    required int ownerId,
  }) => _serial(() async {
    if (account == null) throw serverError(401, 'UNAUTHENTICATED');
    if (account!.id != ownerId) {
      throw const ApiException('ACCOUNT_MISMATCH', '会话账号已改变，请退出当前页面后重新进入。');
    }
    if (!path.startsWith('/api/notes') ||
        path.startsWith('//') ||
        origin.resolve(path).origin != origin.origin) {
      throw protocolError;
    }
    return method == 'GET' ? _raw(method, path) : _write(method, path, body);
  });
  void close() {
    _epoch++;
    _closed = true;
    _forget();
    transport.close();
  }
}

class ChangeSignal {
  const ChangeSignal(this.cursor, this.changed);
  final String cursor;
  final bool changed;
}
