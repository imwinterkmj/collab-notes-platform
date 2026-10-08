import 'dart:async';
import 'dart:convert';

import 'package:collab_notes_client/data/api_client.dart';
import 'package:collab_notes_client/data/api_transport.dart';

class RecordedRequest {
  RecordedRequest(this.method, this.uri, this.headers, this.body);
  final String method;
  final Uri uri;
  final Map<String, String> headers;
  final String? body;
  Map<String, dynamic> get json => jsonDecode(body!) as Map<String, dynamic>;
}

class FakeTransport implements ApiTransport {
  FakeTransport(this.respond);
  final FutureOr<WireResponse> Function(RecordedRequest) respond;
  final List<RecordedRequest> calls = [];
  bool closed = false;
  @override
  Future<WireResponse> send(
    String method,
    Uri uri, {
    required Map<String, String> headers,
    String? body,
  }) async {
    final request = RecordedRequest(
      method,
      uri,
      Map.unmodifiable(headers),
      body,
    );
    calls.add(request);
    return respond(request);
  }

  @override
  void close() => closed = true;
}

WireResponse reply(Object? body, {int status = 200}) =>
    WireResponse(status, jsonEncode(body));
WireResponse csrfReply([String token = 'fixture-token']) =>
    reply({'headerName': 'X-CSRF-TOKEN', 'token': token});
ApiClient fakeApi(FakeTransport transport, {bool loggedIn = false}) {
  final api = ApiClient(
    origin: Uri.parse('https://localhost:8443'),
    transport: transport,
  );
  if (loggedIn) api.account = const Account(5, 'fixture_user');
  return api;
}

Map<String, Object?> noteJson({
  int id = 7,
  String title = '已保存',
  String? content = '完整正文',
  bool completed = false,
}) => {
  'id': id,
  'title': title,
  'content': ?content,
  'completed': completed,
  'createdAt': '2030-01-01T10:00:00Z',
  'updatedAt': '2030-01-01T10:01:00Z',
};
Map<String, Object?> reminderJson({
  String status = 'SCHEDULED',
  int noteId = 7,
}) => {
  'id': 1,
  'noteId': noteId,
  'generation': 2,
  'status': status,
  'dueAt': '2030-01-01T11:00:00Z',
};
Map<String, Object?> pageJson(
  List<Map<String, Object?>> items, {
  int page = 0,
  int size = 20,
  bool hasNext = false,
}) => {'items': items, 'page': page, 'size': size, 'hasNext': hasNext};
