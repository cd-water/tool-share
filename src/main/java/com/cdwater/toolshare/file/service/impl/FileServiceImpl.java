package com.cdwater.toolshare.file.service.impl;

import cn.hutool.core.util.StrUtil;
import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.file.service.FileService;
import com.cdwater.toolshare.file.vo.FileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用文件业务实现：校验图片 → 上传 MinIO → 返回可访问 URL。
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    private static final long MAX_SIZE = 5L * 1024 * 1024;

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 允许的图片扩展名（不接收 SVG，避免脚本注入） */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    private final S3Client s3Client;
    private final String bucket;
    private final String endpoint;

    /** 桶初始化只需成功一次 */
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public FileServiceImpl(S3Client s3Client,
                           @Value("${minio.bucket}") String bucket,
                           @Value("${minio.endpoint}") String endpoint) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.endpoint = StrUtil.removeSuffix(endpoint, "/");
    }

    @Override
    public FileVO upload(MultipartFile file) {
        validate(file);
        String extension = extensionOf(file.getOriginalFilename());
        String objectKey = "images/%s/%s.%s".formatted(
                LocalDate.now().format(DATE_DIR),
                UUID.randomUUID().toString().replace("-", ""),
                extension);

        ensureBucket();
        try (InputStream in = file.getInputStream()) {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(StrUtil.blankToDefault(file.getContentType(), "application/octet-stream"))
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException | SdkException e) {
            log.error("文件上传 MinIO 失败，bucket={}, key={}", bucket, objectKey, e);
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "文件上传失败");
        }

        FileVO vo = new FileVO();
        // path-style 公开读 URL：http://endpoint/bucket/key
        vo.setUrl("%s/%s/%s".formatted(endpoint, bucket, objectKey));
        return vo;
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "图片大小不能超过 5MB");
        }
        if (!ALLOWED_EXTENSIONS.contains(extensionOf(file.getOriginalFilename()))) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(),
                    "仅支持 jpg/jpeg/png/gif/webp/bmp 格式的图片");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "仅支持图片文件");
        }
    }

    private String extensionOf(String filename) {
        if (StrUtil.isBlank(filename) || !filename.contains(".")) {
            return "";
        }
        return StrUtil.subSuf(filename, filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 首次上传时确保桶存在，并设置公开读策略（对象 URL 可直接访问）。
     */
    private void ensureBucket() {
        if (bucketReady.get()) {
            return;
        }
        synchronized (this) {
            if (bucketReady.get()) {
                return;
            }
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    log.error("检查 MinIO 桶失败，bucket={}", bucket, e);
                    throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "文件存储不可用");
                }
                try {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                } catch (SdkException ex) {
                    log.error("创建 MinIO 桶失败，bucket={}", bucket, ex);
                    throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "文件存储不可用");
                }
            }
            applyPublicReadPolicy();
            bucketReady.set(true);
        }
    }

    private void applyPublicReadPolicy() {
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": ["*"]},
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucket);
        try {
            s3Client.putBucketPolicy(PutBucketPolicyRequest.builder()
                    .bucket(bucket)
                    .policy(policy)
                    .build());
        } catch (SdkException e) {
            // 策略设置失败不影响上传，但生成的 URL 可能无法匿名访问
            log.warn("设置 MinIO 桶公开读策略失败，bucket={}", bucket, e);
        }
    }
}
