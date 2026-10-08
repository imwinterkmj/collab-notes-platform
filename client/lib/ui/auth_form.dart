import 'package:flutter/material.dart';

import '../data/api_client.dart';
import '../data/api_error.dart';

class AuthForm extends StatefulWidget {
  const AuthForm({
    super.key,
    required this.api,
    required this.onLogin,
    this.onDemo,
    this.onCancel,
    this.expectedAccount,
    this.configurationError,
  });
  final ApiClient? api;
  final ValueChanged<Account> onLogin;
  final VoidCallback? onDemo;
  final VoidCallback? onCancel;
  final Account? expectedAccount;
  final String? configurationError;
  @override
  State<AuthForm> createState() => _AuthFormState();
}

class _AuthFormState extends State<AuthForm> {
  late final TextEditingController _username;
  final _password = TextEditingController();
  bool _register = false;
  bool _busy = false;
  String? _error;
  String? _message;
  @override
  void initState() {
    super.initState();
    _username = TextEditingController(
      text: widget.expectedAccount?.username ?? '',
    );
  }

  @override
  void dispose() {
    _password.clear();
    _password.dispose();
    _username.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy || widget.api == null) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _busy = true;
      _error = null;
      _message = null;
    });
    try {
      final api = widget.api!;
      if (_register) {
        await api.register(_username.text, _password.text);
        if (!mounted) return;
        _password.clear();
        setState(() {
          _register = false;
          _message = '注册成功，请输入密码并点击登录。注册不会自动登录。';
        });
      } else {
        final user = await api.login(
          _username.text,
          _password.text,
          expectedAccountId: widget.expectedAccount?.id,
        );
        if (!mounted) return;
        _password.clear();
        widget.onLogin(user);
      }
    } catch (error) {
      if (mounted) setState(() => _error = operationError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_busy,
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          widget.expectedAccount == null ? '把重要的事记下来' : '重新登录，保留当前草稿',
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 8),
        Text(
          widget.expectedAccount == null
              ? '安卓和 Windows 共用客户端 · 连接真实后端'
              : '只允许原账号重新验证；不会自动重新提交。',
        ),
        const SizedBox(height: 14),
        if (widget.api != null)
          Text(
            '服务：${widget.api!.origin}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        if (widget.expectedAccount == null) ...[
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              ChoiceChip(
                label: const Text('登录'),
                selected: !_register,
                onSelected: _busy
                    ? null
                    : (_) => setState(() {
                        _register = false;
                        _error = null;
                      }),
              ),
              ChoiceChip(
                key: const Key('register-tab'),
                label: const Text('注册'),
                selected: _register,
                onSelected: _busy
                    ? null
                    : (_) => setState(() {
                        _register = true;
                        _error = null;
                      }),
              ),
            ],
          ),
        ],
        const SizedBox(height: 16),
        TextField(
          key: const Key('auth-username'),
          controller: _username,
          enabled: !_busy && widget.expectedAccount == null,
          autocorrect: false,
          textInputAction: TextInputAction.next,
          decoration: const InputDecoration(
            labelText: '用户名',
            helperText: '3～32 位小写字母、数字或下划线',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          key: const Key('auth-password'),
          controller: _password,
          enabled: !_busy,
          obscureText: true,
          enableSuggestions: false,
          autocorrect: false,
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _submit(),
          decoration: const InputDecoration(
            labelText: '密码',
            helperText: '仅在本次输入期间保留，不保存登录密码',
          ),
        ),
        const SizedBox(height: 16),
        if (widget.configurationError != null)
          Text(widget.configurationError!, key: const Key('config-error')),
        if (_error != null)
          Text(
            _error!,
            key: const Key('auth-error'),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_message != null) Text(_message!, key: const Key('auth-message')),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton(
              key: const Key('submit-auth'),
              onPressed: _busy || widget.api == null ? null : _submit,
              child: Text(
                _busy
                    ? '连接中…'
                    : _register
                    ? '注册账号'
                    : '登录',
              ),
            ),
            if (widget.onCancel != null)
              TextButton(
                onPressed: _busy ? null : widget.onCancel,
                child: const Text('取消'),
              ),
            if (widget.onDemo != null)
              TextButton(
                key: const Key('enter-demo'),
                onPressed: _busy ? null : widget.onDemo,
                child: const Text('仅查看离线演示'),
              ),
          ],
        ),
        if (widget.expectedAccount == null) ...[
          const SizedBox(height: 16),
          const Text(
            '数据通过现有后端保存；前台自动同步另一端修改。\n系统通知、锁屏提醒与电脑托盘尚未接入。',
            style: TextStyle(fontSize: 12),
          ),
        ],
      ],
    ),
  );
}
