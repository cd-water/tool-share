package com.cdwater.toolshare.shared.storage;

import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.shared.config.S3Config.MinioProperties;
import com.cdwater.toolshare.shared.storage.FileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 对象存储访问服务（兼容 MinIO / S3 协议）。
 *
 * <p>负责三件事：写入文件、读取文件、生成浏览器可访问的 URL。写入入口
 * 只接受已经过 {@link ImageUploadProcessor} 校验与重编码的字节，Content-Type
 * 与存储文件名都由服务端决定，客户端声明一律忽略。</p>
 *
 * <p>启动后首次访问存储时会懒初始化桶：不存在则创建，随后尝试设置
 * 整桶匿名只读策略。公开读设置成功时返回对象直链；设置失败时自动回退
 * 到 {@code /api/files/**} 后端代理，保证功能可用而不是直接失败。</p>
 *
 * <p>并发控制：桶初始化使用双重检查锁，并用 volatile 标记完成状态，
 * 避免每个请求都执行 HeadBucket。单个实例内只会初始化一次；多实例
 * 部署时各实例独立初始化，操作本身是幂等的。</p>
 *
 * <p>安全边界：读取失败统一转换为 404，不向调用方泄露存储内部错误；
 * 上传失败转换为 500，并提示检查对象存储服务。种子资源使用固定 key
 * 写入，因此重复启动会覆盖同名对象而不是产生副本。</p>
 *
 * <p>配置契约：{@code minio.endpoint} 供后端 SDK 使用，可以是容器内网
 * 地址；{@code minio.public-endpoint} 供浏览器使用，必须是用户可达的
 * 地址。两者分离是为了适配 Docker 网络与宿主机端口映射并存的部署方式。</p>
 *
 * <p>失败降级：公开读策略设置失败不会阻止上传，只会让 URL 退回后端
 * 代理；桶创建失败则视为存储不可用，因为后续读写都无法进行。该策略
 * 让开发环境在 MinIO 权限受限时仍能完成基本功能验证。</p>
 *
 * <p>不在本类做图片校验：调用方必须传入已重编码的字节。这样存储层
 * 不依赖 ImageIO，也便于测试时替换为内存实现而不改变安全边界。</p>
 *
 * <p>对象 key 规则：用户上传使用随机 UUID 前缀，种子资源使用固定的
 * {@code seed/} 前缀。前者不可预测，后者便于人工定位；两类 key 都不会
 * 包含用户原始路径或可执行扩展名。</p>
 *
 * <p>读放大：公开读可用时浏览器直接访问对象存储，后端不承担流量；
 * 代理模式下所有图片流量经过后端，部署时应优先启用公开读。</p>
 *
 * <p>可观测性：桶初始化、公开读设置与上传失败都有日志。日志不打印
 * 访问密钥，只打印桶名与错误消息。</p>
 *
 * <p>容量与清理：本服务不实现对象删除与生命周期策略，历史图片会随
 * 业务记录长期保留。若需要清理孤儿对象，应由独立运维任务扫描数据库
 * 引用后进行，避免误删仍在使用的图片。</p>
 *
 * <p>版本兼容：SDK 使用 AWS S3 协议，因此同一套代码可连接 AWS S3、
 * MinIO 或其它兼容实现；差异集中在 endpoint 与 path-style 配置。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    /** 底层 S3 客户端，已配置路径风格访问以兼容 MinIO。 */
    private final S3Client s3Client;

    /** MinIO/S3 连接配置，包含桶名、访问密钥与公开访问地址。 */
    private final MinioProperties properties;

    /** 桶是否已完成初始化；volatile 保证双重检查锁的可见性。 */
    private volatile boolean bucketReady;
    /** 桶公开读策略是否设置成功；失败时 URL 回退到后端代理。 */
    private volatile boolean publicReadReady;

    /**
     * 存储已通过 {@link ImageUploadProcessor} 校验与重编码的图片。
     * Content-Type 与扩展名都由服务端决定，不接收客户端声明。
     *
     * <p>每次写入生成随机 key，前缀是去掉连字符的 UUID，后缀是服务端
     * 清洗后的文件名。随机前缀避免同名文件互相覆盖，也让 key 不可预测。</p>
     *
     * <p>该方法不校验字节内容，调用方必须保证已经过图片处理器。返回的
     * URL 取决于桶公开读状态，因此同一份文件在不同部署环境下可能是
     * 直链或代理地址，但其指向的内容一致。</p>
     *
     * <p>写入失败不会留下半成品：S3 PutObject 是原子操作，失败时对象
     * 要么不存在，要么是完整内容。因此无需额外清理逻辑。</p>
     *
     * @param bytes       已重编码的图片字节
     * @param contentType 服务端判定的 MIME 类型
     * @param storedName  服务端生成的安全文件名
     * @return 包含访问地址与文件名的上传结果
     * @throws BizException 存储写入失败时抛出 500
     */
    public FileVO store(byte[] bytes, String contentType, String storedName) {
        // 写入前确保桶已初始化，上传与读取使用同一初始化路径。
        // UUID 去掉连字符可缩短 key，同时保留足够的随机性。
        String key = UUID.randomUUID().toString().replace("-", "") + "-" + storedName;
        try {
            put(key, bytes, contentType);
        } catch (BizException e) {
            // 业务异常原样抛出，保留具体错误码与消息。
            throw e;
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "文件上传失败，请检查对象存储服务");
        }
        // URL 选择逻辑集中在 url()，此处不关心直链还是代理。
        return new FileVO(url(key), storedName);
    }

    /**
     * 按 key 读取对象字节。
     *
     * <p>返回实际 Content-Type；缺失时兜底为 application/octet-stream。
     * 任何读取异常都被归一为 404，避免把存储内部细节暴露给调用方。</p>
     *
     * <p>读取会把整个对象载入内存，仅适合当前图片规模。若未来支持大文件，
     * 应改为流式响应，否则可能造成内存压力。</p>
     *
     * @param key 对象 key，可包含分层斜杠
     * @return 文件字节与实际内容类型
     * @throws BizException 对象不存在或读取失败时抛出 404
     */
    public FileContent read(String key) {
        // 读取前确保桶已初始化，避免首次访问时代理接口报错。
        ensureBucket();
        try {
            ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
            String contentType = bytes.response().contentType();
            return new FileContent(bytes.asByteArray(),
                    StringUtils.hasText(contentType) ? contentType : "application/octet-stream");
        } catch (Exception e) {
            // 不区分 NoSuchKey 与网络错误，统一按文件不存在处理。
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
    }

    /**
     * 返回当前配置下最合适的文件访问地址。
     *
     * <p>公开读可用时返回对象直链，减少一次后端代理；否则返回代理地址。
     * 调用方不应缓存这个选择结果，因为桶策略状态可能在运行期变化。</p>
     *
     * @param key 对象 key
     * @return 可直接放入 img src 的 URL
     */
    public String url(String key) {
        // 公开读状态在运行期不会频繁变化，这里直接读取 volatile 标志。
        return publicReadReady ? publicUrl(key) : legacyUrl(key);
    }

    /** 浏览器可访问的对象直链；不依赖桶策略，供测试与策略成功后使用。 */
    public String publicUrl(String key) {
        // 对 key 做路径编码，保留斜杠但转义空格等特殊字符。
        // 编码后仍可直接放入 img src，浏览器会按 UTF-8 解码。
        // 该方法不检查对象是否存在，只负责拼 URL。
        return directPrefix() + UriUtils.encodePath(key, StandardCharsets.UTF_8);
    }

    /** 直链前缀；桶公开读未启用时返回 null，调用方应暂停旧数据规范化。 */
    public String publicUrlPrefix() {
        // 返回 null 是明确的“不可用”信号，调用方据此跳过旧地址迁移。
        return publicReadReady ? directPrefix() : null;
    }

    /**
     * 拼接“公开地址 + 桶名 + 斜杠”的直链前缀。
     *
     * @return 形如 {@code https://host/bucket/} 的前缀，末尾保证有斜杠
     */
    private String directPrefix() {
        // 桶名直接拼接；配置层已保证 bucket 非空且不含非法字符。
        return publicEndpoint() + "/" + properties.getBucket() + "/";
    }

    /**
     * 后端代理地址。
     *
     * <p>保留原始 key 的斜杠层级，由 {@link PublicFileController} 负责
     * 从 URI 中还原；该地址不依赖桶公开读策略。</p>
     *
     * @param key 对象 key
     * @return 以 {@code /api/files/} 开头的代理地址
     */
    private String legacyUrl(String key) {
        // 代理路径与 PublicFileController 的映射前缀必须保持一致。
        return "/api/files/" + key;
    }

    /**
     * 选择浏览器可访问的公开端点。
     *
     * <p>优先使用 publicEndpoint（例如宿主机映射地址），未配置时退回
     * endpoint（可能只是容器内网地址）。返回前去掉末尾斜杠，避免拼接
     * 出现双斜杠。</p>
     *
     * @return 无末尾斜杠的端点地址
     */
    private String publicEndpoint() {
        String endpoint = StringUtils.hasText(properties.getPublicEndpoint())
                ? properties.getPublicEndpoint() : properties.getEndpoint();
        // 配置可能带末尾斜杠，这里统一去掉，拼出的 URL 只有单个斜杠。
        // endpoint 本身不应为空，否则构造 S3 客户端时就会失败。
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    /** 固定 key 写入（用于种子资源），重复执行会覆盖同名对象 */
    public void put(String key, byte[] bytes, String contentType) {
        ensureBucket();
        // 固定 key 写入是幂等的；种子初始化依赖这一特性反复执行而不产生副本。
        // 与 store() 不同，这里不生成随机前缀，key 由调用方完全控制。
        // 因此只能用于可信的种子资源，不能直接暴露给用户上传。
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    }

    /**
     * 判断对象是否存在。
     *
     * <p>使用 HeadObject 而不是下载对象，开销更小。404 视为“不存在”，
     * 其它 S3 错误继续向上抛出，避免把存储故障误判为文件缺失。</p>
     *
     * <p>不同 S3 兼容实现对“对象不存在”的异常类型并不统一：MinIO 可能
     * 抛出 NoSuchKeyException，也可能返回通用 S3Exception + 404，因此
     * 两种情况都要覆盖。权限错误（403）会继续抛出，提示配置问题。</p>
     *
     * @param key 对象 key
     * @return 存在返回 true；404 返回 false
     */
    public boolean exists(String key) {
        ensureBucket();
        try {
            s3Client.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            // 明确的 NoSuchKey 直接判定为不存在。
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                // 部分 S3 兼容实现用通用 S3Exception + 404 表达缺失。
                return false;
            }
            throw e;
        }
    }

    /**
     * 懒初始化桶及公开读策略。
     *
     * <p>双重检查锁：已初始化时直接返回；未初始化时只有一个线程进入
     * 同步块，其余线程等待后复用结果。检查 bucket 不存在则创建，
     * 其它错误视为存储不可用并抛出业务异常。</p>
     */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            // 进入同步块后再次检查，防止重复初始化。
            if (bucketReady) {
                return;
            }
            String bucket = properties.getBucket();
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException e) {
                // 桶不存在是预期情况：自动创建，降低部署门槛。
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    // 兼容把“桶不存在”返回为通用 404 的实现。
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                } else {
                    log.warn("检查 MinIO bucket 失败: {}", e.getMessage());
                    throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "对象存储服务不可用");
                }
            } catch (Exception e) {
                // 网络或认证错误：不标记 bucketReady，下次请求会重试。
                log.warn("检查 MinIO bucket 失败: {}", e.getMessage());
                throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "对象存储服务不可用");
            }
            // 公开读失败不会阻断初始化，只影响 URL 形式。
            // 因此无论策略设置成功与否，都标记桶已就绪，避免反复创建桶。
            ensurePublicRead(bucket);
            bucketReady = true;
        }
    }

    /** 整桶匿名只读，供浏览器直链访问种子图片与用户上传图片。 */
    private void ensurePublicRead(String bucket) {
        // 策略只授予 GetObject，不授予写入或列举权限，降低暴露面。
        // Principal 为 * 表示匿名可读；对象 key 带随机前缀，降低被遍历风险。
        // 该策略是整桶级别的，新增对象无需重复授权。
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": {"AWS": ["*"]},
                    "Action": ["s3:GetObject"],
                    "Resource": ["arn:aws:s3:::%s/*"]
                  }]
                }
                """.formatted(bucket);
        try {
            s3Client.putBucketPolicy(PutBucketPolicyRequest.builder()
                    .bucket(bucket)
                    .policy(policy)
                    .build());
            publicReadReady = true;
            log.info("对象存储桶已启用公开读: {}", bucket);
        } catch (Exception e) {
            // 设置失败时降级为后端代理，不影响上传与读取功能。
            // 常见原因是 MinIO 策略权限受限或网关不支持 PutBucketPolicy。
            // 此时 publicReadReady 保持 false，后续所有 URL 都走代理。
            publicReadReady = false;
            log.error("对象存储桶公开读设置失败，文件 URL 回退到 /api/files/: {}", e.getMessage());
        }
    }

    /**
     * 读取到的文件内容。
     *
     * @param bytes       文件字节
     * @param contentType 实际 MIME 类型，缺失时已兜底为二进制流
     */
    public record FileContent(byte[] bytes, String contentType) {
    }
}
