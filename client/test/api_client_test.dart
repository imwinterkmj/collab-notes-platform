import 'dart:async';
import 'dart:io';

import 'package:collab_notes_client/data/api_error.dart';
import 'package:collab_notes_client/data/api_client.dart';
import 'package:collab_notes_client/data/api_transport.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_transport.dart';

void main() {
  test('登录轮换 Cookie 后重新获取 CSRF，所有写入携带新值', () async {
    var tokens = 0;
    final wire = FakeTransport((r) {
      if (r.uri.path.endsWith('csrf')) {
        tokens++;
        return WireResponse(
          200,
          '{"headerName":"X-CSRF-TOKEN","token":"token-$tokens"}',
          cookies: tokens == 1
              ? [Cookie('JSESSIONID', 'anonymous')..secure = true]
              : [],
        );
      }
      if (r.uri.path.endsWith('login')) {
        expect(r.headers['cookie'], 'JSESSIONID=anonymous');
        expect(r.headers['X-CSRF-TOKEN'], 'token-1');
        return WireResponse(
          200,
          '{"id":5,"username":"fixture_user"}',
          cookies: [Cookie('JSESSIONID', 'rotated')..secure = true],
        );
      }
      expect(r.headers['cookie'], 'JSESSIONID=rotated');
      expect(r.headers['X-CSRF-TOKEN'], 'token-2');
      return reply({});
    });
    final api = fakeApi(wire);
    await api.login('fixture_user', 'fixture-password');
    await api.request('POST', '/api/notes/save', ownerId: 5, body: {});
    expect(tokens, 2);
  });
  test('注册不自动登录，错误密码保留明确错误码', () async {
    final wire = FakeTransport(
      (r) => r.uri.path.endsWith('csrf')
          ? csrfReply()
          : r.uri.path.endsWith('register')
          ? reply({'id': 5}, status: 201)
          : reply({'code': 'INVALID_CREDENTIALS'}, status: 401),
    );
    final api = fakeApi(wire);
    await api.register('fixture_user', 'x');
    expect(api.account, isNull);
    await expectLater(
      api.login('fixture_user', 'wrong'),
      throwsA(
        isA<ApiException>().having(
          (e) => e.code,
          'code',
          'INVALID_CREDENTIALS',
        ),
      ),
    );
  });
  test('CSRF_INVALID 只安全重试一次，连续失败不死循环', () async {
    for (final alwaysFail in [false, true]) {
      var writes = 0;
      final wire = FakeTransport(
        (r) => r.method == 'GET'
            ? csrfReply()
            : ++writes == 1 || alwaysFail
            ? reply({'code': 'CSRF_INVALID'}, status: 403)
            : reply({}),
      );
      final api = fakeApi(wire, loggedIn: true);
      if (alwaysFail) {
        await expectLater(
          api.request('POST', '/api/notes/save', ownerId: 5),
          throwsA(isA<ApiException>()),
        );
      } else {
        await api.request('POST', '/api/notes/save', ownerId: 5);
      }
      expect(writes, 2);
      expect(wire.calls.where((r) => r.uri.path.endsWith('csrf')).length, 2);
    }
  });
  test('断网和 5xx 从不自动重放写入', () async {
    for (final serverFailure in [false, true]) {
      final wire = FakeTransport((r) {
        if (r.method == 'GET') return csrfReply();
        if (serverFailure) {
          return reply({'code': 'INTERNAL_ERROR'}, status: 500);
        }
        throw const ApiException(
          'NETWORK_ERROR',
          'fixture failure',
          uncertain: true,
        );
      });
      await expectLater(
        fakeApi(
          wire,
          loggedIn: true,
        ).request('POST', '/api/notes/save', ownerId: 5),
        throwsA(
          isA<ApiException>().having((e) => e.uncertain, 'uncertain', true),
        ),
      );
      expect(wire.calls.where((r) => r.method == 'POST').length, 1);
    }
  });
  test('会话 401 清理凭据，后续本人请求不发出', () async {
    final wire = FakeTransport(
      (r) => reply({'code': 'UNAUTHENTICATED'}, status: 401),
    );
    final api = fakeApi(wire, loggedIn: true);
    await expectLater(
      api.request('GET', '/api/notes', ownerId: 5),
      throwsA(isA<ApiException>()),
    );
    expect(api.account, isNull);
    await expectLater(
      api.request('POST', '/api/notes/save', ownerId: 5),
      throwsA(isA<ApiException>()),
    );
    expect(wire.calls.length, 1);
  });
  test('旧仓库不能将请求发给新账号', () async {
    final wire = FakeTransport((r) => reply({}));
    await expectLater(
      fakeApi(wire, loggedIn: true).request('GET', '/api/notes', ownerId: 8),
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'ACCOUNT_MISMATCH'),
      ),
    );
    expect(wire.calls, isEmpty);
  });
  test('退出失败也清除本机凭据', () async {
    final wire = FakeTransport(
      (r) => r.method == 'GET'
          ? csrfReply()
          : reply({'code': 'INTERNAL_ERROR'}, status: 500),
    );
    final api = fakeApi(wire, loggedIn: true);
    await expectLater(api.logout(), throwsA(isA<ApiException>()));
    expect(api.account, isNull);
  });
  test('重定向被阻止，不跟随或显示响应原文', () async {
    final wire = FakeTransport(
      (r) => const WireResponse(302, 'private response'),
    );
    await expectLater(
      fakeApi(wire, loggedIn: true).request('GET', '/api/notes', ownerId: 5),
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'REDIRECT_BLOCKED'),
      ),
    );
    expect(wire.calls.length, 1);
  });
  test('服务器错误原文不进入客户端错误', () async {
    final wire = FakeTransport(
      (r) => reply({
        'code': 'INTERNAL_ERROR',
        'message': 'private SQL and token',
      }, status: 500),
    );
    await expectLater(
      fakeApi(wire, loggedIn: true).request('GET', '/api/notes', ownerId: 5),
      throwsA(
        isA<ApiException>().having(
          (e) => e.message.contains('private'),
          'private leak',
          false,
        ),
      ),
    );
  });
  test('关闭连接后迟到响应不能恢复旧凭据', () async {
    final pending = Completer<WireResponse>();
    final wire = FakeTransport((r) => pending.future);
    final api = fakeApi(wire, loggedIn: true);
    final result = api.request('GET', '/api/notes', ownerId: 5);
    await Future<void>.delayed(Duration.zero);
    api.close();
    pending.complete(reply({}));
    await expectLater(
      result,
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'CLIENT_CLOSED'),
      ),
    );
    expect(api.account, isNull);
    expect(wire.closed, isTrue);
  });
  test('输入边界本地拒绝，合法密码不裁剪', () async {
    final wire = FakeTransport(
      (r) => r.uri.path.endsWith('csrf')
          ? csrfReply()
          : reply({'id': 5, 'username': 'fixture_user'}),
    );
    final api = fakeApi(wire);
    await expectLater(api.login('ABC', 'x'), throwsA(isA<ApiException>()));
    await expectLater(
      api.login('fixture_user', ' '),
      throwsA(isA<ApiException>()),
    );
    expect(wire.calls, isEmpty);
    await api.login('fixture_user', '  x  ');
    expect(wire.calls.last.json['password'], '  x  ');
  });
  test('HTTPS 地址拒绝明文、URL 凭据、路径和查询注入', () {
    for (final url in [
      'http://192.168.1.2:8080',
      'http://127.0.0.1:8080',
      'https://user:pass@localhost',
      'https://localhost/path',
      'https://localhost/?token=x',
      'https://localhost/#secret',
    ]) {
      expect(() => validateApiOrigin(url), throwsA(isA<ApiException>()));
    }
    expect(validateApiOrigin('https://127.0.0.1:8443').scheme, 'https');
    expect(
      validateApiOrigin('http://127.0.0.1:8080', allowLocalHttp: true).scheme,
      'http',
    );
  });
  test('草稿重新登录拒绝其他账号，并清理其临时会话', () async {
    final wire = FakeTransport((r) {
      if (r.uri.path.endsWith('csrf')) return csrfReply();
      if (r.uri.path.endsWith('login')) {
        return reply({'id': 8, 'username': 'fixture_user'});
      }
      return const WireResponse(204, '');
    });
    final api = fakeApi(wire);
    await expectLater(
      api.login('fixture_user', 'x', expectedAccountId: 5),
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'ACCOUNT_MISMATCH'),
      ),
    );
    expect(api.account, isNull);
    expect(wire.calls.where((r) => r.uri.path.endsWith('logout')).length, 1);
    final before = wire.calls.length;
    await expectLater(
      api.request('GET', '/api/notes', ownerId: 5),
      throwsA(isA<ApiException>()),
    );
    expect(wire.calls.length, before);
  });
  test('Cookie 只接受本服务根路径，Secure 不流入 HTTP，过期即移除', () async {
    for (final scheme in ['https', 'http']) {
      final wire = FakeTransport((r) {
        if (r.uri.path.endsWith('csrf')) {
          return WireResponse(
            200,
            '{"headerName":"X-CSRF-TOKEN","token":"fixture-token"}',
            cookies: [
              Cookie('JSESSIONID', 'foreign')..domain = 'other.example',
              Cookie('JSESSIONID', 'wrongpath')..path = '/another',
              Cookie('OTHER', 'ignored'),
              Cookie('JSESSIONID', 'secure')..secure = true,
            ],
          );
        }
        if (r.uri.path.endsWith('login')) {
          expect(
            r.headers['cookie'],
            scheme == 'https' ? 'JSESSIONID=secure' : null,
          );
          return WireResponse(
            200,
            '{"id":5,"username":"fixture_user"}',
            cookies: [Cookie('JSESSIONID', 'expired')..maxAge = 0],
          );
        }
        expect(r.headers['cookie'], isNull);
        return reply({'id': 5, 'username': 'fixture_user'});
      });
      final api = ApiClient(
        origin: Uri.parse('$scheme://127.0.0.1:8443'),
        transport: wire,
      );
      await api.login('fixture_user', 'x');
      await api.me();
      api.close();
    }
  });
}
