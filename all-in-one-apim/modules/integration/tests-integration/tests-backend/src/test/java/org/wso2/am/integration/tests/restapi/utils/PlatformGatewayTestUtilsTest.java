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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Unit tests for pure byte-manipulation helpers in {@link PlatformGatewayTestUtils}.
 */
public class PlatformGatewayTestUtilsTest {

    @Test
    public void testContainsAsciiFindsNeedleInTarLikePayload() {
        byte[] payload = "prefix ustar suffix".getBytes(StandardCharsets.US_ASCII);
        Assert.assertTrue(PlatformGatewayTestUtils.containsAscii(payload, "ustar"));
        Assert.assertFalse(PlatformGatewayTestUtils.containsAscii(payload, "missing"));
    }

    @Test
    public void testGunzipRoundTrip() throws IOException {
        byte[] original = "hello platform gateway tar".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(original);
        byte[] restored = PlatformGatewayTestUtils.gunzip(compressed);
        Assert.assertEquals(restored, original);
    }

    @Test
    public void testUnwrapFetchBatchToTarDetectsPlainTar() throws IOException {
        byte[] tarLike = "000000000000000000000000000ustar000000".getBytes(StandardCharsets.US_ASCII);
        byte[] unwrapped = PlatformGatewayTestUtils.unwrapFetchBatchToTar(tarLike);
        Assert.assertTrue(PlatformGatewayTestUtils.containsAscii(unwrapped, "ustar"));
    }

    @Test
    public void testUnwrapFetchBatchToTarUnwrapsSingleGzipLayer() throws IOException {
        byte[] tarLike = "000000000000000000000000000ustar000000".getBytes(StandardCharsets.US_ASCII);
        byte[] wrapped = gzip(tarLike);
        byte[] unwrapped = PlatformGatewayTestUtils.unwrapFetchBatchToTar(wrapped);
        Assert.assertTrue(PlatformGatewayTestUtils.containsAscii(unwrapped, "ustar"));
    }

    private static byte[] gzip(byte[] input) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(input);
            gzipOut.finish();
            return out.toByteArray();
        }
    }
}
