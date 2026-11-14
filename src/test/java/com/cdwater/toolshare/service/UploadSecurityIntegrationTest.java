package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.shared.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传安全：图片白名单、真实类型校验、重编码、5MB 上限与公开读取响应头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class UploadSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String NOSNIFF = "nosniff";

    @LocalServerPort
    int port;

    @Autowired
    StorageService storageService;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();
    String userToken;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        JsonNode node = post("/api/user/login/by-pwd", null,
                Map.of("phone", "13800000001", "password", "Aa123456"));
        assertEquals("A200", node.get("code").asString(), node.toString());
        userToken = node.get("data").get("tokenValue").asString();
    }

    @Test
    void valid_png_and_jpg_are_reencoded_and_served_with_safe_headers() throws IOException {
        for (String format : new String[]{"png", "jpg"}) {
            byte[] source = image(format, 32, 24);
            ResponseEntity<String> uploaded = upload(source, "照片." + format, mediaType(format));
            assertEquals(HttpStatus.OK, uploaded.getStatusCode(), uploaded.getBody());
            JsonNode node = om.readTree(uploaded.getBody());
            assertEquals("A200", node.get("code").asString(), node.toString());
            String url = node.get("data").get("url").asString();
            assertTrue(url.endsWith(".png") || url.endsWith(".jpg"), url);

            ResponseEntity<byte[]> read = client.get().uri(url).retrieve().toEntity(byte[].class);
            assertEquals(HttpStatus.OK, read.getStatusCode());
            assertEquals(NOSNIFF, read.getHeaders().getFirst("X-Content-Type-Options"));
            assertEquals(mediaType(format).toString(), read.getHeaders().getContentType().toString());
            // 存储的是服务端重新编码后的有效图片
            assertNotNull(ImageIO.read(new ByteArrayInputStream(read.getBody())));
        }
    }

    @Test
    void html_svg_and_fake_extension_are_rejected() throws IOException {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertRejected(upload(html, "attack.html", MediaType.TEXT_HTML));
        assertRejected(upload(html, "attack.png", MediaType.IMAGE_PNG));

        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\">"
                + "<script>alert(1)</script></svg>").getBytes(StandardCharsets.UTF_8);
        assertRejected(upload(svg, "attack.svg", MediaType.parseMediaType("image/svg+xml")));

        // 扩展名与真实类型不一致
        assertRejected(upload(image("png", 8, 8), "fake.jpg", MediaType.IMAGE_JPEG));
    }

    @Test
    void file_larger_than_5mb_is_rejected() {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        assertRejected(upload(oversized, "big.png", MediaType.IMAGE_PNG));
    }

    @Test
    void legacy_unsafe_object_is_not_served_as_executable_html() {
        String key = "seed/legacy-" + System.nanoTime() + ".html";
        storageService.put(key, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "text/html");

        ResponseEntity<byte[]> read = client.get().uri("/api/files/" + key).retrieve().toEntity(byte[].class);
        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertEquals(NOSNIFF, read.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM.toString(), read.getHeaders().getContentType().toString());
    }

    private void assertRejected(ResponseEntity<String> response) {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), response.getBody());
    }

    private ResponseEntity<String> upload(byte[] bytes, String filename, MediaType contentType) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes)).filename(filename).contentType(contentType);
        return client.post().uri("/api/user/upload")
                .header("Authorization", "Bearer " + userToken)
                .body(body.build())
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(String.class);
    }

    private static MediaType mediaType(String format) {
        return "png".equals(format) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        int type = "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out));
        return out.toByteArray();
    }

    private JsonNode post(String url, String token, Object body) {
        RestClient.RequestBodySpec spec = client.post().uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        String raw = spec.body(body).retrieve().onStatus(status -> true, (request, response) -> {
        }).body(String.class);
        return om.readTree(raw == null ? "{}" : raw);
    }
}
