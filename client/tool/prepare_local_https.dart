// 使用已有 JDK 生成受限本机 HTTPS 联调材料；从 client/ 运行。
// 产物都是构建/本机配置，保存在仓库已忽略的 target/，不记录或打印秘密。
import 'dart:convert';
import 'dart:io';
import 'dart:math';

Future<void> main() async {
  if (!File('../pom.xml').existsSync() || !File('pubspec.yaml').existsSync()) {
    stderr.writeln('请先进入仓库 client/ 目录，避免生成到错误位置。');
    exitCode = 1;
    return;
  }
  final output = Directory('../target/local-https').absolute;
  final key = File('${output.path}/dev-server.p12');
  final cert = File('${output.path}/dev-server.cer');
  final settings = File('${output.path}/server-local.json');
  final defines = File('${output.path}/client-defines.json');
  final cmd = File('${output.path}/server-env.cmd');
  if ([key, cert, settings, defines, cmd].any((file) => file.existsSync())) {
    if ([
      key,
      cert,
      settings,
      defines,
      cmd,
    ].every((file) => file.existsSync())) {
      stdout.writeln('本机 HTTPS 材料已存在，未覆盖或重新生成证书。');
      return;
    }
    stderr.writeln('存在不完整的本机 HTTPS 材料；请先核对，不自动覆盖或删除。');
    exitCode = 1;
    return;
  }
  await output.create(recursive: true);
  final password = base64UrlEncode(
    List.generate(32, (_) => Random.secure().nextInt(256)),
  );
  final keytool = Platform.environment['JAVA_HOME'] == null
      ? 'keytool.exe'
      : '${Platform.environment['JAVA_HOME']}/bin/keytool.exe';
  final environment = {
    ...Platform.environment,
    'CLIENT_DEV_KEYSTORE_PASSWORD': password,
  };
  Future<void> run(List<String> arguments) async {
    final result = await Process.run(
      keytool,
      arguments,
      environment: environment,
    );
    if (result.exitCode != 0) throw StateError('keytool 失败；未输出可能包含本机敏感信息的诊断。');
  }

  try {
    await run([
      '-genkeypair',
      '-alias',
      'collabnotes-dev',
      '-keyalg',
      'RSA',
      '-keysize',
      '3072',
      '-validity',
      '365',
      '-dname',
      'CN=localhost',
      '-ext',
      'SAN=DNS:localhost,IP:127.0.0.1',
      '-storetype',
      'PKCS12',
      '-keystore',
      key.path,
      '-storepass:env',
      'CLIENT_DEV_KEYSTORE_PASSWORD',
    ]);
    await run([
      '-exportcert',
      '-alias',
      'collabnotes-dev',
      '-keystore',
      key.path,
      '-storepass:env',
      'CLIENT_DEV_KEYSTORE_PASSWORD',
      '-file',
      cert.path,
    ]);
    final certificate = base64Encode(await cert.readAsBytes());
    await defines.writeAsString(
      const JsonEncoder.withIndent('  ').convert({
        'API_BASE_URL': 'https://127.0.0.1:8443',
        'DEV_TLS_CERT_BASE64': certificate,
      }),
    );
    await settings.writeAsString(
      jsonEncode({
        'keyStore': key.uri.toString(),
        'password': password,
        'certificateFile': cert.path,
      }),
    );
    await cmd.writeAsString(
      [
        '@echo off',
        'rem 本机专用秘密；禁止提交 Git 或截图分享。',
        'set "SERVER_ADDRESS=127.0.0.1"',
        'set "SERVER_PORT=8443"',
        'set "SERVER_SSL_ENABLED=true"',
        'set "SERVER_SSL_KEY_STORE=${key.uri}"',
        'set "SERVER_SSL_KEY_STORE_TYPE=PKCS12"',
        'set "SERVER_SSL_KEY_ALIAS=collabnotes-dev"',
        'set "SERVER_SSL_KEY_STORE_PASSWORD=$password"',
        'set "SESSION_COOKIE_SECURE=true"',
        '',
      ].join('\r\n'),
    );
    stdout.writeln('本机证书与配置准备完成（仅 target/local-https/）。未安装系统证书、启动服务或开放网络。');
  } catch (_) {
    stderr.writeln('证书准备失败，保留现有产物供核对；未删除或覆盖其他文件。');
    exitCode = 1;
  }
}
