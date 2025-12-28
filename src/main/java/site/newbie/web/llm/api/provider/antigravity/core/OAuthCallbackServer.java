package site.newbie.web.llm.api.provider.antigravity.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * OAuth 回调服务器
 * 监听本地端口接收 Google OAuth 回调
 */
@Slf4j
@Component
public class OAuthCallbackServer {
    
    private HttpServer server;
    private final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<String>> pendingLogins = new java.util.concurrent.ConcurrentHashMap<>();
    private int port;
    private final Object serverLock = new Object();
    
    /**
     * 启动回调服务器（如果尚未启动）
     */
    public int start() throws IOException {
        synchronized (serverLock) {
            if (server != null) {
                // 服务器已在运行，返回当前端口
                return port;
            }
            
            // 尝试绑定可用端口
            for (int p = 8080; p < 9000; p++) {
                try {
                    server = HttpServer.create(new InetSocketAddress("localhost", p), 0);
                    port = p;
                    break;
                } catch (IOException e) {
                    // 端口被占用，尝试下一个
                    continue;
                }
            }
            
            if (server == null) {
                throw new IOException("无法找到可用端口");
            }
            
            server.createContext("/oauth-callback", new OAuthCallbackHandler());
            server.setExecutor(null); // 使用默认执行器
            server.start();
            
            log.info("OAuth 回调服务器已启动，监听端口: {}", port);
            return port;
        }
    }
    
    /**
     * 停止回调服务器
     */
    public void stop() {
        synchronized (serverLock) {
            if (server != null) {
                server.stop(0);
                server = null;
                port = 0;
                pendingLogins.clear();
                log.info("OAuth 回调服务器已停止");
            }
        }
    }
    
    /**
     * 注册一个登录会话并等待授权码（最多等待 5 分钟）
     * @param sessionId 会话 ID，用于区分不同的登录请求
     */
    public String waitForCode(String sessionId) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingLogins.put(sessionId, future);
        
        try {
            return future.get(java.util.concurrent.TimeUnit.MINUTES.toMillis(5), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingLogins.remove(sessionId);
            throw new Exception("等待授权码超时（5分钟）", e);
        } catch (Exception e) {
            pendingLogins.remove(sessionId);
            throw e;
        }
    }
    
    /**
     * 取消等待
     */
    public void cancel(String sessionId) {
        CompletableFuture<String> future = pendingLogins.remove(sessionId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }
    
    /**
     * 获取回调 URI
     */
    public String getRedirectUri() {
        synchronized (serverLock) {
            if (port == 0) {
                throw new IllegalStateException("服务器尚未启动");
            }
            return "http://localhost:" + port + "/oauth-callback";
        }
    }
    
    /**
     * OAuth 回调处理器
     */
    private class OAuthCallbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String requestMethod = exchange.getRequestMethod();
                if (!"GET".equals(requestMethod)) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                
                URI uri = exchange.getRequestURI();
                String query = uri.getQuery();
                
                // 解析授权码
                String code = null;
                String error = null;
                
                if (query != null) {
                    String[] params = query.split("&");
                    for (String param : params) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                            String value = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                            
                            if ("code".equals(key)) {
                                code = value;
                            } else if ("error".equals(key)) {
                                error = value;
                            }
                        }
                    }
                }
                
                // 尝试从所有待处理的登录中找到一个来完成
                // 注意：实际应用中可能需要通过 state 参数来匹配特定的登录会话
                // 这里简化处理，使用第一个可用的 future
                CompletableFuture<String> future = null;
                String sessionId = null;
                for (java.util.Map.Entry<String, CompletableFuture<String>> entry : pendingLogins.entrySet()) {
                    if (!entry.getValue().isDone()) {
                        future = entry.getValue();
                        sessionId = entry.getKey();
                        break;
                    }
                }
                
                if (error != null) {
                    String errorMsg = "OAuth 授权失败: " + error;
                    log.error(errorMsg);
                    sendResponse(exchange, 400, getErrorHtml(errorMsg));
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(new RuntimeException(errorMsg));
                        if (sessionId != null) {
                            pendingLogins.remove(sessionId);
                        }
                    }
                    return;
                }
                
                if (code != null) {
                    log.info("收到 OAuth 授权码");
                    sendResponse(exchange, 200, getSuccessHtml());
                    if (future != null && !future.isDone()) {
                        future.complete(code);
                        if (sessionId != null) {
                            pendingLogins.remove(sessionId);
                        }
                    } else {
                        log.warn("收到授权码但没有待处理的登录会话");
                    }
                } else {
                    String errorMsg = "未能在回调中获取 Authorization Code";
                    log.error(errorMsg);
                    sendResponse(exchange, 400, getErrorHtml(errorMsg));
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(new RuntimeException(errorMsg));
                        if (sessionId != null) {
                            pendingLogins.remove(sessionId);
                        }
                    }
                }
                
            } catch (Exception e) {
                log.error("处理 OAuth 回调时出错", e);
                sendResponse(exchange, 500, getErrorHtml("服务器错误: " + e.getMessage()));
            }
        }
        
        private void sendResponse(HttpExchange exchange, int statusCode, String html) throws IOException {
            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(statusCode, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
        
        private String getSuccessHtml() {
            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>授权成功</title>
                    <style>
                        body {
                            font-family: sans-serif;
                            text-align: center;
                            padding: 50px;
                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                            color: white;
                            min-height: 100vh;
                            margin: 0;
                            display: flex;
                            flex-direction: column;
                            justify-content: center;
                            align-items: center;
                        }
                        h1 { font-size: 2.5em; margin-bottom: 20px; }
                        p { font-size: 1.2em; }
                    </style>
                </head>
                <body>
                    <h1>✅ 授权成功!</h1>
                    <p>您可以关闭此窗口返回应用。</p>
                    <script>setTimeout(function() { window.close(); }, 2000);</script>
                </body>
                </html>
                """;
        }
        
        private String getErrorHtml(String error) {
            return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>授权失败</title>
                    <style>
                        body {
                            font-family: sans-serif;
                            text-align: center;
                            padding: 50px;
                            background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
                            color: white;
                            min-height: 100vh;
                            margin: 0;
                            display: flex;
                            flex-direction: column;
                            justify-content: center;
                            align-items: center;
                        }
                        h1 { font-size: 2.5em; margin-bottom: 20px; }
                        p { font-size: 1.2em; }
                    </style>
                </head>
                <body>
                    <h1>❌ 授权失败</h1>
                    <p>%s</p>
                    <p>请返回应用重试。</p>
                </body>
                </html>
                """, error);
        }
    }
}

