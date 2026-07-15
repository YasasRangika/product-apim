/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.am.integration.tests.restapi.utils;

import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.testng.Assert;
import org.wso2.am.integration.clients.admin.ApiException;
import org.wso2.am.integration.clients.admin.ApiResponse;
import org.wso2.am.integration.clients.admin.api.dto.GatewayListDTO;
import org.wso2.am.integration.clients.admin.api.dto.PlatformGatewayResponseDTO;
import org.wso2.am.integration.test.impl.RestAPIAdminImpl;
import org.wso2.am.integration.test.utils.http.HTTPSClientUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Shared helpers for platform (Universal) gateway integration tests.
 */
public final class PlatformGatewayTestUtils {

    public static final String INTERNAL_DATA_V1 = "https://localhost:9943/internal/data/v1";
    public static final String WS_GATEWAY_CONNECT_PATH = "/ws/gateways/connect";
    /** Matches {@code GatewayConnectEndpoint.CLOSE_UNAUTHORIZED} in carbon-apimgt. */
    public static final int WS_CLOSE_UNAUTHORIZED = 4401;

    private PlatformGatewayTestUtils() {
    }

    public static WebSocketClient newInternalDataWebSocketClient() {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setTrustAll(true);
        return new WebSocketClient(sslContextFactory);
    }

    public static Boolean readPlatformGatewayIsActive(RestAPIAdminImpl restAPIAdmin, String gatewayId)
            throws ApiException {

        ApiResponse<GatewayListDTO> res = restAPIAdmin.getPlatformGateways();
        if (res.getStatusCode() != HttpStatus.SC_OK || res.getData() == null || res.getData().getList() == null) {
            return null;
        }
        for (PlatformGatewayResponseDTO g : res.getData().getList()) {
            if (gatewayId.equals(g.getId())) {
                return g.isIsActive();
            }
        }
        return null;
    }

    public static void awaitPlatformGatewayIsActive(RestAPIAdminImpl restAPIAdmin, String gatewayId,
                                                    boolean expectActive, long timeoutMs, long pollIntervalMs)
            throws Exception {

        long deadline = System.currentTimeMillis() + timeoutMs;
        Boolean last = null;
        while (System.currentTimeMillis() < deadline) {
            last = readPlatformGatewayIsActive(restAPIAdmin, gatewayId);
            if (last != null && last.booleanValue() == expectActive) {
                return;
            }
            Thread.sleep(pollIntervalMs);
        }
        Assert.fail("Timed out waiting for platform gateway " + gatewayId + " active=" + expectActive
                + " (last isActive=" + last + ")");
    }

    public static void assertEnvironmentNotFound(RestAPIAdminImpl restAPIAdmin, String gatewayId, String message)
            throws ApiException {

        try {
            restAPIAdmin.getEnvironment(gatewayId);
            Assert.fail(message);
        } catch (ApiException e) {
            Assert.assertEquals(e.getCode(), HttpStatus.SC_NOT_FOUND, message);
        }
    }

    /**
     * POST /deployments/fetch-batch returns {@code application/x-tar+gzip}. Reading that body as UTF-8 text
     * corrupts the archive and hides the ASCII {@code ustar} magic.
     */
    public static byte[] postFetchBatchRaw(String registrationToken, String deploymentId) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("api-key", registrationToken);
        headers.put("Content-Type", "application/json");
        String payload = "{\"deploymentIds\":[\"" + deploymentId + "\"]}";

        try (CloseableHttpClient httpClient = HTTPSClientUtils.getHttpsClient()) {
            HttpPost post = new HttpPost(INTERNAL_DATA_V1 + "/deployments/fetch-batch");
            for (Map.Entry<String, String> head : headers.entrySet()) {
                post.addHeader(head.getKey(), head.getValue());
            }
            post.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int code = response.getStatusLine().getStatusCode();
                byte[] body = response.getEntity() == null ? new byte[0]
                        : EntityUtils.toByteArray(response.getEntity());
                Assert.assertEquals(code, HttpStatus.SC_OK,
                        "fetch-batch should succeed for a listed deployment id; bytes=" + body.length);
                return body;
            }
        }
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gin = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gin.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static boolean containsAscii(byte[] data, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Unwraps fetch-batch payload to TAR bytes. Server may return tar.gz already (and CXF may also
     * Content-Encoding gzip when the client advertises Accept-Encoding).
     */
    public static byte[] unwrapFetchBatchToTar(byte[] responseBytes) throws IOException {
        byte[] current = responseBytes;
        for (int i = 0; i < 2; i++) {
            if (containsAscii(current, "ustar")) {
                return current;
            }
            try {
                current = gunzip(current);
            } catch (IOException e) {
                break;
            }
        }
        return current;
    }

    public static String tarPayloadAsSearchableText(byte[] tarBytes) {
        return new String(tarBytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * Connects to the internal gateway WebSocket with an invalid api-key and asserts the server rejects
     * the handshake with close code {@link #WS_CLOSE_UNAUTHORIZED}.
     */
    public static void assertWebSocketConnectRejectedForInvalidApiKey(String invalidApiKey, long timeoutMs)
            throws Exception {

        WebSocketClient client = newInternalDataWebSocketClient();
        UnauthorizedConnectWebSocket socket = new UnauthorizedConnectWebSocket();
        client.start();
        try {
            URI wsUri = new URI("wss://localhost:9943/internal/data/v1" + WS_GATEWAY_CONNECT_PATH);
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setHeader("api-key", invalidApiKey);
            Future<Session> future = client.connect(socket, wsUri, request);
            Session session;
            try {
                session = future.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Assert.assertTrue(isUnauthorizedWebSocketFailure(e),
                        "Invalid api-key should fail WebSocket connect with unauthorized signal; cause="
                                + rootCauseMessage(e));
                return;
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (session.isOpen() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100L);
            }
            Assert.assertFalse(session.isOpen(), "Invalid api-key must close the WebSocket session");
            Assert.assertEquals(socket.getCloseCode(), WS_CLOSE_UNAUTHORIZED,
                    "Invalid api-key should close WebSocket with 4401 Unauthorized");
        } finally {
            client.stop();
        }
    }

    private static boolean isUnauthorizedWebSocketFailure(ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("unauthorized") || lower.contains("401")
                || message.contains(String.valueOf(WS_CLOSE_UNAUTHORIZED));
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    @WebSocket
    public static class UnauthorizedConnectWebSocket {

        private volatile int closeCode = -1;

        @OnWebSocketConnect
        public void onConnect(Session ignored) {
            // Server may accept upgrade then close in @OnOpen with 4401.
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String ignored) {
            this.closeCode = statusCode;
        }

        public int getCloseCode() {
            return closeCode;
        }
    }
}
