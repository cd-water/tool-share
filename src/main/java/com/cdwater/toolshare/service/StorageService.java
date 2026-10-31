package com.cdwater.toolshare.service;

import com.cdwater.toolshare.common.BizException;
import com.cdwater.toolshare.common.ResultCode;
import com.cdwater.toolshare.config.S3Config.MinioProperties;
import com.cdwater.toolshare.vo.FileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final S3Client s3Client;
    private final MinioProperties properties;
    private volatile boolean bucketReady;

    public FileVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择文件");
        }
        ensureBucket();
        String original = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "file";
        String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String key = UUID.randomUUID().toString().replace("-", "") + "-" + safeName;
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromInputStream(
                            file.getInputStream(), file.getSize()));
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "文件上传失败，请检查对象存储服务");
        }
        return new FileVO("/api/files/" + key, original);
    }

    public FileContent read(String key) {
        ensureBucket();
        try {
            ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
            String contentType = bytes.response().contentType();
            return new FileContent(bytes.asByteArray(),
                    StringUtils.hasText(contentType) ? contentType : "application/octet-stream");
        } catch (Exception e) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
    }

    public String url(String key) {
        return "/api/files/" + key;
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            String bucket = properties.getBucket();
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException e) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                } else {
                    log.warn("检查 MinIO bucket 失败: {}", e.getMessage());
                    throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "对象存储服务不可用");
                }
            } catch (Exception e) {
                log.warn("检查 MinIO bucket 失败: {}", e.getMessage());
                throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "对象存储服务不可用");
            }
            bucketReady = true;
        }
    }

    public record FileContent(byte[] bytes, String contentType) {
    }
}
