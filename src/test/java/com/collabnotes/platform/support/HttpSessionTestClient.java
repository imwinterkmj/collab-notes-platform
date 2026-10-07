package com.collabnotes.platform.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;

/** 真实 HTTP 客户端：Cookie 仅在内存中保存，每个实例模拟独立设备。 */
public final class HttpSessionTestClient {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final ObjectMapper mapper;

    public HttpSessionTestClient(int port, ObjectMapper mapper) {
        this.baseUrl = "http://localhost:" + port;
        this.mapper = mapper;
    }

    public ResponseEntity<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    public ResponseEntity<String> post(String path, Object payload) throws Exception {
        return write("POST", path, payload);
    }

    public ResponseEntity<String> put(String path, Object payload) throws Exception {
        return write("PUT", path, payload);
    }

    public ResponseEntity<String> patch(String path, Object payload) throws Exception {
        return write("PATCH", path, payload);
    }
    public ResponseEntity<String> delete(String path) throws Exception { return write("DELETE", path, null); }

    private ResponseEntity<String> write(String method, String path, Object payload) throws Exception {
        var csrf = mapper.readTree(get("/api/auth/csrf").getBody());
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))));
    }

    public String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .findFirst().orElseThrow().getValue();
    }

    public ResponseEntity<String> replaySession(String id) throws Exception {
        // 无 CookieManager 的新客户端，仅重放保存的旧会话编号。
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/me"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", "JSESSIONID=" + id).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }

    private ResponseEntity<String> send(HttpRequest.Builder request) throws Exception {
        var response = client.send(request.timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        var result = ResponseEntity.status(response.statusCode());
        response.headers().map().forEach((name, values) -> result.header(name, values.toArray(String[]::new)));
        return result.body(response.body());
    }
}
