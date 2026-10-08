class ApiException implements Exception {
  const ApiException(
    this.code,
    this.message, {
    this.status,
    this.uncertain = false,
  });
  final String code;
  final String message;
  final int? status;
  final bool uncertain;
  bool get needsLogin => code == 'UNAUTHENTICATED';
  @override
  String toString() => 'ApiException[$code]';
}

const protocolError = ApiException('INVALID_RESPONSE', '服务响应格式异常，请刷新核对或重试。');

ApiException serverError(int status, String code) =>
    ApiException(code, switch (code) {
      'INVALID_CREDENTIALS' => '用户名或密码错误',
      'UNAUTHENTICATED' => '登录已失效，请重新登录；未保存草稿仍保留。',
      'USERNAME_TAKEN' || 'USERNAME_EXISTS' => '这个用户名已被使用',
      'CSRF_INVALID' => '会话校验失败，请重新登录后再试。',
      'INVALID_TIME' => '提醒时间需要在未来一年内；草稿仍保留。',
      'NOTE_COMPLETED' => '请先恢复为未完成，再设置提醒',
      'NOTE_NOT_FOUND' => '记录不存在或不可访问，请刷新列表',
      'REMINDER_NOT_FOUND' => '提醒不存在或不可访问',
      'INVALID_INPUT' || 'INVALID_REQUEST' => '请检查输入格式、长度和提醒时间；草稿仍保留。',
      'FORBIDDEN' => '没有访问权限',
      _ => status >= 500 ? '服务暂时不可用，请稍后刷新核对。' : '请求未成功，请刷新核对后重试。',
    }, status: status);

String operationError(Object error) =>
    error is ApiException ? error.message : '操作失败，请重试。草稿仍保留。';
