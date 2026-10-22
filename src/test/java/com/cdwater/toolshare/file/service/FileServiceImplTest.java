package com.cdwater.toolshare.file.service;

import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.file.service.impl.FileServiceImpl;
import com.cdwater.toolshare.file.vo.FileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件上传业务单元测试（Mock MultipartFile 与 S3Client）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceImplTest {

    private static final String ENDPOINT = "http://localhost:9000";
    private static final String BUCKET = "tool-share";
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    @Mock
    private S3Client s3Client;

    @Mock
    private MultipartFile file;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        // 末尾斜杠应被去掉
        fileService = new FileServiceImpl(s3Client, BUCKET, ENDPOINT + "/");
    }

    @Test
    void upload_rejectsNullFile() {
        BizException ex = assertThrows(BizException.class, () -> fileService.upload(null));

        assertEquals("C400", ex.getCode());
        assertEquals("文件不能为空", ex.getMessage());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void upload_rejectsEmptyFile() {
        when(file.isEmpty()).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("C400", ex.getCode());
        assertEquals("文件不能为空", ex.getMessage());
    }

    @Test
    void upload_rejectsFileLargerThan5MB() {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(MAX_SIZE + 1);

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("C400", ex.getCode());
        assertEquals("图片大小不能超过 5MB", ex.getMessage());
    }

    @Test
    void upload_rejectsUnsupportedExtension() {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("evil.svg");

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("C400", ex.getCode());
        assertTrue(ex.getMessage().contains("jpg"));
    }

    @Test
    void upload_rejectsFilenameWithoutExtension() {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("no-extension");

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("C400", ex.getCode());
    }

    @Test
    void upload_rejectsNonImageContentType() {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("note.png");
        when(file.getContentType()).thenReturn("text/plain");

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("C400", ex.getCode());
        assertEquals("仅支持图片文件", ex.getMessage());
    }

    @Test
    void upload_putsObjectAndReturnsPublicUrl() throws Exception {
        givenValidFile("photo.JPG", "image/jpeg");

        FileVO vo = fileService.upload(file);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest request = captor.getValue();
        assertEquals(BUCKET, request.bucket());
        assertTrue(request.key().matches("images/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.jpg"),
                "对象 key 应为日期目录 + UUID + 小写扩展名: " + request.key());
        assertEquals("image/jpeg", request.contentType());

        assertTrue(vo.getUrl().startsWith(ENDPOINT + "/" + BUCKET + "/images/"));
        assertTrue(vo.getUrl().endsWith(".jpg"));
    }

    @Test
    void upload_createsBucketWhenMissing() throws Exception {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());
        givenValidFile("photo.png", "image/png");

        fileService.upload(file);

        verify(s3Client).createBucket(any(CreateBucketRequest.class));
        verify(s3Client).putBucketPolicy(any(PutBucketPolicyRequest.class));
    }

    @Test
    void upload_failsWhenBucketCheckReturnsServerError() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("photo.png");
        when(file.getContentType()).thenReturn("image/png");

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("S500", ex.getCode());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void upload_failsWhenBucketCreationFails() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());
        when(s3Client.createBucket(any(CreateBucketRequest.class)))
                .thenThrow(SdkException.builder().message("create failed").build());
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("photo.png");
        when(file.getContentType()).thenReturn("image/png");

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("S500", ex.getCode());
    }

    @Test
    void upload_failsWhenPutObjectFails() throws Exception {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("put failed").build());
        givenValidFile("photo.png", "image/png");

        BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));

        assertEquals("S500", ex.getCode());
    }

    private void givenValidFile(String filename, String contentType) throws Exception {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("image-bytes".getBytes()));
    }
}
