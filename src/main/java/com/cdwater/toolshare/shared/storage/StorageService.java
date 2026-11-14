package com.cdwater.toolshare.shared.storage;

import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.shared.config.S3Config.MinioProperties;
import com.cdwater.toolshare.shared.storage.FileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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

    /**
     * 存储已通过 {@link ImageUploadProcessor} 校验与重编码的图片。
     * Content-Type 与扩展名都由服务端决定，不接收客户端声明。
     */
    public FileVO store(byte[] bytes, String contentType, String storedName) {
        String key = UUID.randomUUID().toString().replace("-", "") + "-" + storedName;
        try {
            put(key, bytes, contentType);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "文件上传失败，请检查对象存储服务");
        }
        return new FileVO(url(key), storedName);
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

    /** 固定 key 写入（用于种子资源），重复执行会覆盖同名对象 */
    public void put(String key, byte[] bytes, String contentType) {
        ensureBucket();
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    }

    public boolean exists(String key) {
        ensureBucket();
        try {
            s3Client.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
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
