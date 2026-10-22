package com.cdwater.toolshare.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * MinIO（S3 兼容）客户端配置。
 * <p>后端 B 的知识库上传直接注入此 {@link S3Client}，不要再自行创建。</p>
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${minio.endpoint}") String endpoint,
                             @Value("${minio.access-key}") String accessKey,
                             @Value("${minio.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                // MinIO 不校验区域，任意合法值即可
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // MinIO 使用 path-style：http://host:port/bucket/key
                .forcePathStyle(true)
                .build();
    }
}
