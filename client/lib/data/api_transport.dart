import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'api_error.dart';

class WireResponse {
  const WireResponse(this.status, this.body, {this.cookies = const []});
  final int status;
  final String body;
  final List<Cookie> cookies;
}

abstract interface class ApiTransport {
  Future<WireResponse> send(
    String method,
    Uri uri, {
    required Map<String, String> headers,
    String? body,
  });
  void close();
}

bool isLoopback(Uri uri) =>
    const ['127.0.0.1', 'localhost', '::1'].contains(uri.host.toLowerCase());

Uri validateApiOrigin(String value, {bool allowLocalHttp = false}) {
  final uri = Uri.tryParse(value);
  if (uri == null ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment ||
      (uri.path.isNotEmpty && uri.path != '/') ||
      (uri.scheme != 'https' &&
          !(allowLocalHttp && uri.scheme == 'http' && isLoopback(uri)))) {
    throw const ApiException(
      'INVALID_SERVER',
      '服务地址需要为 HTTPS 根地址；仅电脑本机调试允许回环 HTTP。',
    );
  }
  return uri.replace(path: '');
}

/// 仅安卓/桌面 IO 客户端；隐藏在接口后，后续可替换高层 HTTP 实现。
class IoApiTransport implements ApiTransport {
  IoApiTransport(
    this.origin, {
    String localCertificateBase64 = '',
    this.timeout = const Duration(seconds: 15),
  }) {
    SecurityContext? context;
    if (localCertificateBase64.isNotEmpty) {
      if (origin.scheme != 'https' || !isLoopback(origin)) {
        throw const ApiException('INVALID_SERVER', '本机开发证书只能用于回环 HTTPS 地址。');
      }
      final certificate = base64Decode(localCertificateBase64);
      // keytool 默认导出 DER；Dart Windows TLS 信任入口使用 PEM。
      // 只转换公开证书格式，不跳过证书链、有效期或主机名验证。
      final encoded = base64Encode(certificate);
      final pem =
          certificate.length >= 10 &&
              ascii.decode(certificate.take(10).toList(), allowInvalid: true) ==
                  '-----BEGIN'
          ? certificate
          : utf8.encode(
              '-----BEGIN CERTIFICATE-----\n'
              '${[for (var i = 0; i < encoded.length; i += 64) encoded.substring(i, i + 64 < encoded.length ? i + 64 : encoded.length)].join('\n')}\n'
              '-----END CERTIFICATE-----\n',
            );
      context = SecurityContext(withTrustedRoots: true)
        ..setTrustedCertificatesBytes(pem);
    }
    _http = HttpClient(context: context)
      ..connectionTimeout = timeout
      ..userAgent = 'CollabNotesClient/0.3'
      ..findProxy = null;
    // 不注册 badCertificateCallback；保留证书链、有效期与主机名验证。
  }
  final Uri origin;
  final Duration timeout;
  late final HttpClient _http;
  bool _closed = false;
  static const _limit = 2 * 1024 * 1024;

  @override
  Future<WireResponse> send(
    String method,
    Uri uri, {
    required Map<String, String> headers,
    String? body,
  }) async {
    if (_closed ||
        uri.origin != origin.origin ||
        !uri.path.startsWith('/api/')) {
      throw const ApiException('CLIENT_CLOSED', '当前连接已关闭，请重新进入。');
    }
    HttpClientRequest? request;
    bool expired = false;
    Future<WireResponse> perform() async {
      request = await _http.openUrl(method, uri);
      if (expired || _closed) {
        request!.abort();
        throw const ApiException('NETWORK_ERROR', '当前请求已取消，请刷新核对。');
      }
      request!.followRedirects = false;
      request!.headers.set(HttpHeaders.acceptHeader, 'application/json');
      headers.forEach((key, value) => request!.headers.set(key, value));
      if (body != null) {
        request!.headers.contentType = ContentType.json;
        request!.add(utf8.encode(body));
      }
      final response = await request!.close();
      final bytes = <int>[];
      await for (final chunk in response) {
        if (bytes.length + chunk.length > _limit) {
          throw protocolError;
        }
        bytes.addAll(chunk);
      }
      return WireResponse(
        response.statusCode,
        utf8.decode(bytes),
        cookies: response.cookies,
      );
    }

    try {
      return await perform().timeout(
        timeout,
        onTimeout: () {
          expired = true;
          request?.abort();
          throw TimeoutException('request timed out');
        },
      );
    } on ApiException {
      request?.abort();
      rethrow;
    } on HandshakeException {
      throw const ApiException(
        'TLS_FAILED',
        'HTTPS 证书校验失败，请检查本机证书与客户端版本；不会跳过校验。',
      );
    } on FormatException {
      throw protocolError;
    } catch (_) {
      final write = method != 'GET';
      throw ApiException(
        'NETWORK_ERROR',
        write
            ? '连接中断，结果可能已保存。请先刷新核对，不要直接重复新建；草稿仍保留。'
            : '无法连接服务，请检查后端与 USB 转发，再重试。',
        uncertain: write,
      );
    }
  }

  @override
  void close() {
    _closed = true;
    _http.close(force: true);
  }
}
