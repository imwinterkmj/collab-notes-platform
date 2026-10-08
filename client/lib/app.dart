import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'data/api_client.dart';
import 'data/api_transport.dart';
import 'data/demo_notes_repository.dart';
import 'data/http_notes_repository.dart';
import 'data/notes_repository.dart';
import 'ui/auth_form.dart';
import 'ui/notebook_page.dart';

ApiClient createConfiguredApi() {
  const url = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'https://127.0.0.1:8443',
  );
  const certificate = String.fromEnvironment('DEV_TLS_CERT_BASE64');
  final origin = validateApiOrigin(
    url,
    allowLocalHttp: !Platform.isAndroid && !Platform.isIOS,
  );
  return ApiClient(
    origin: origin,
    transport: IoApiTransport(origin, localCertificateBase64: certificate),
  );
}

class NotesApp extends StatefulWidget {
  const NotesApp({super.key, this.apiFactory, this.enableAutoSync = true});
  final ApiClient Function()? apiFactory;
  final bool enableAutoSync;
  @override
  State<NotesApp> createState() => _NotesAppState();
}

class _NotesAppState extends State<NotesApp> {
  final _navigator = GlobalKey<NavigatorState>();
  ApiClient? _api;
  String? _configurationError;
  String? _exitMessage;
  NotesRepository? _repository;
  Account? _account;
  bool _leaving = false;
  @override
  void initState() {
    super.initState();
    _setupApi();
  }

  void _setupApi() {
    _configurationError = null;
    try {
      _api = (widget.apiFactory ?? createConfiguredApi)();
    } catch (_) {
      _api = null;
      _configurationError = '连接配置无效，请使用匹配本机 HTTPS 证书的构建；不会跳过证书校验。';
    }
  }

  @override
  void dispose() {
    _api?.close();
    super.dispose();
  }

  void _login(Account user) => setState(() {
    _account = user;
    _repository = HttpNotesRepository(_api!, user.id);
    _exitMessage = null;
  });
  void _demo() {
    _api?.close();
    setState(() {
      _account = null;
      _repository = DemoNotesRepository();
      _exitMessage = null;
    });
  }

  Future<bool> _reauthenticate() async {
    final user = _account;
    if (user == null || _api == null) return false;
    return await showDialog<bool>(
          context: _navigator.currentContext!,
          barrierDismissible: false,
          builder: (context) => Dialog(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: AuthForm(
                  api: _api,
                  expectedAccount: user,
                  onLogin: (_) => Navigator.pop(context, true),
                  onCancel: () => Navigator.pop(context, false),
                ),
              ),
            ),
          ),
        ) ??
        false;
  }

  Future<void> _exit() async {
    if (_leaving) return;
    setState(() => _leaving = true);
    String? message;
    if (_account != null) {
      try {
        await _api!.logout();
      } catch (_) {
        message = '本机凭据已清除；服务端退出未确认，旧会话会按原超时规则失效。';
      }
    }
    _api?.close();
    if (!mounted) return;
    setState(() {
      _repository = null;
      _account = null;
      _exitMessage = message;
      _leaving = false;
      _setupApi();
    });
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '备忘录 · 联调',
    navigatorKey: _navigator,
    debugShowCheckedModeBanner: false,
    locale: const Locale('zh', 'CN'),
    supportedLocales: const [Locale('zh', 'CN')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: ThemeData(
      useMaterial3: true,
      fontFamily: 'Microsoft YaHei',
      fontFamilyFallback: const ['Noto Sans CJK SC', 'sans-serif'],
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff245a48)),
      scaffoldBackgroundColor: const Color(0xfff4f6f2),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
        filled: true,
        fillColor: Colors.white,
      ),
    ),
    home: _repository == null
        ? Scaffold(
            body: SafeArea(
              child: Center(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 540),
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (_exitMessage != null)
                              Padding(
                                padding: const EdgeInsets.only(bottom: 16),
                                child: Text(_exitMessage!),
                              ),
                            AuthForm(
                              api: _api,
                              configurationError: _configurationError,
                              onLogin: _login,
                              onDemo: _demo,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          )
        : NotebookPage(
            key: ValueKey(
              _account == null ? 'demo' : 'account-${_account!.id}',
            ),
            repository: _repository!,
            accountName: _account?.username,
            onExit: () {
              _exit();
            },
            onReauthenticate: _account == null ? null : _reauthenticate,
            leaving: _leaving,
            enableAutoSync: widget.enableAutoSync,
          ),
  );
}
