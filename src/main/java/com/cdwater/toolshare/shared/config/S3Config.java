package com.cdwater.toolshare.shared.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * 对象存储客户端配置。
 *
 * <p>使用 AWS SDK v2 连接 MinIO，并启用路径风格访问（path-style），
 * 因为 MinIO 默认不支持虚拟主机风格的 {@code bucket.host} 访问。区域
 * 固定为 us-east-1，MinIO 不校验区域，但 SDK 要求必须提供。</p>
 *
 * <p>配置拆成两个端点：{@code endpoint} 供后端 SDK 使用，可以是 Docker
 * 内网地址；{@code publicEndpoint} 供浏览器直接访问，必须是宿主机或
 * 公网可达地址。二者分离解决了容器内网与浏览器网络不一致的问题。</p>
 */
@Configuration
public class S3Config {

    /**
     * 绑定 {@code minio.*} 配置项。
     *
     * @return 对象存储配置对象
     */
    @Bean
    @ConfigurationProperties(prefix = "minio")
    public MinioProperties minioProperties() {
        return new MinioProperties();
    }

    /**
     * 构建 S3 客户端。
     *
     * <p>使用静态访问密钥；密钥由环境变量注入，不写入代码仓库。
     * 路径风格访问是 MinIO 兼容的关键配置。</p>
     *
     * @param properties 对象存储配置
     * @return 可复用的 S3 客户端
     */
    @Bean
    public S3Client s3Client(MinioProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                // MinIO 不校验区域，使用 SDK 默认区域即可。
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                // path-style：endpoint/bucket/key，而不是 bucket.endpoint/key。
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * MinIO/S3 连接配置。
     *
     * <p>除端点外还包含桶名与访问密钥。公开端点可以为空，为空时回退到
     * 后端端点，但那种配置只适合浏览器能直接访问内网地址的开发环境。</p>
     */
    @Data
    public static class MinioProperties {
        /** 后端 S3 客户端使用的地址，可以是容器内网地址。 */
        private String endpoint;
        /** 浏览器直接访问对象时使用的地址，必须能从用户浏览器访问。 */
        private String publicEndpoint;
        /** 访问密钥 ID。 */
        private String accessKey;
        /** 访问密钥 Secret。 */
        private String secretKey;
        /** 存储桶名称，不存在时由 StorageService 自动创建。 */
        private String bucket;
    }
}
