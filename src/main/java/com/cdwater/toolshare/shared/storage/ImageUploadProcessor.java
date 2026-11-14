package com.cdwater.toolshare.shared.storage;

import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 图片上传校验与重编码。校验发生在 {@link UploadController}，因此测试里的内存 StorageService 也无法绕过。
 * 只接受 JPG/JPEG/PNG；扩展名必须与真实格式一致，存储的是重新编码后的图片（EXIF、XMP 等元数据全部丢弃）。
 *
 * <p>处理流程：大小检查 → 扩展名白名单 → 读取真实格式 → 校验格式与
 * 扩展名一致 → 限制解码像素数 → 重新编码。重新编码既会丢弃元数据，
 * 也能消除图片中夹带的非图片数据，是上传安全的关键一步。</p>
 *
 * <p>像素数限制用于防御“压缩炸弹”：文件体积很小但解码后占用巨量内存。
 * 限制在真正 decode 之前通过 ImageReader 读取宽高完成，避免先把图片
 * 完整解码进内存再判断。</p>
 *
 * <p>本类不负责存储，只返回处理后的字节、内容类型与安全文件名；
 * 存储 key 与 URL 由 {@link StorageService} 生成。</p>
 *
 * <p>为什么同时限制体积与像素：体积限制只能挡住直接的大文件，攻击者
 * 可以用高压缩比图片让解码后的内存暴涨。像素限制在解码前读取宽高，
 * 因此不会先付出解码成本。</p>
 *
 * <p>为什么必须重编码：原始文件可能携带 EXIF 定位、相机信息或尾随的
 * 非图片数据；重新编码只保留像素内容，既保护隐私，也消除多格式混淆
 * 风险。重编码失败会直接拒绝上传，而不是保存原始字节。</p>
 *
 * <p>所有错误都转换为 400 业务异常，并给出可读提示；不会把 ImageIO
 * 的底层异常直接暴露给前端。成功路径返回的字节与上传字节不同，调用方
 * 不应假设二者相等。</p>
 * <p>重编码后的图片尺寸与原始图片一致，不在此处做缩放。</p>
 */
@Component
public class ImageUploadProcessor {

    /** 扩展名 -> ImageIO 读出的真实格式 */
    private static final Map<String, String> EXTENSION_FORMATS = Map.of(
            "jpg", "jpeg",
            "jpeg", "jpeg",
            "png", "png");

    /** 真实格式 -> 回传给浏览器的 MIME 类型。 */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpeg", "image/jpeg",
            "png", "image/png");

    /** 单文件最大字节数，默认 5MB。 */
    private final long maxBytes;

    /** 解码后最大像素数，默认 4000 万像素，用于防御压缩炸弹。 */
    private final long maxPixels;

    /**
     * 构造函数注入上传限制。
     *
     * @param maxBytes  单文件最大字节数
     * @param maxPixels 解码后最大像素数
     */
    public ImageUploadProcessor(
            @Value("${toolshare.upload.max-image-bytes:5242880}") long maxBytes,
            @Value("${toolshare.upload.max-image-pixels:40000000}") long maxPixels) {
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
    }

    /**
     * 校验并重编码一张上传图片。
     *
     * <p>返回的字节是重新编码后的结果，不再是原始上传内容；文件名也
     * 已清洗为安全 basename。任何不符合要求的输入都会抛出 400 业务异常。</p>
     *
     * <p>校验顺序有意为之：先看大小与扩展名，成本最低；再读字节并再次
     * 校验大小，防止元数据伪造；最后读取真实格式并解码。这样普通非法
     * 请求不会触发昂贵的图片解码。</p>
     *
     * <p>成功返回的 Content-Type 只可能是 image/jpeg 或 image/png，
     * 与存储文件名后缀一一对应，前端无需再次推断类型。</p>
     *
     * @param file 上传文件
     * @return 重编码后的图片、MIME 类型与安全文件名
     * @throws BizException 文件为空、过大、扩展名不支持、格式不符或处理失败
     */
    public ProcessedImage process(MultipartFile file) {
        // MultipartFile 为空或没有内容时直接拒绝，避免后续空指针与无意义解码。
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择图片");
        }
        if (file.getSize() > maxBytes) {
            // 先用 MultipartFile 元数据快速拒绝，避免读取大文件。
            // 提示文案固定为 5MB，与实际配置解耦，避免暴露内部阈值。
            throw new BizException(ResultCode.BAD_REQUEST, "图片不能超过 5MB");
        }
        // 原始文件名只用于推断扩展名，后续会被清洗，不直接进入存储路径。
        String original = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "";
        String extension = StringUtils.getFilenameExtension(original);
        // 扩展名比较统一转小写，兼容 .JPG/.PNG 等大小写变体。
        String expectedFormat = extension == null ? null : EXTENSION_FORMATS.get(extension.toLowerCase(Locale.ROOT));
        if (expectedFormat == null) {
            // 只允许白名单扩展名，SVG/GIF/WebP 等一律拒绝。
            throw new BizException(ResultCode.BAD_REQUEST, "仅支持 JPG、PNG 格式的图片");
        }
        byte[] raw;
        try {
            // 一次性读入内存：图片已经限制在 5MB，且后续解码也需要字节数组。
            raw = file.getBytes();
        } catch (IOException e) {
            // 读取失败通常来自临时文件损坏或请求中断。
            throw new BizException(ResultCode.BAD_REQUEST, "图片读取失败");
        }
        if (raw.length > maxBytes) {
            // 元数据可能被伪造，读取真实字节后再校验一次大小。
            // 两次校验使用同一阈值，避免配置变更时出现不一致。
            throw new BizException(ResultCode.BAD_REQUEST, "图片不能超过 5MB");
        }

        // decode 内部会同时校验真实格式与像素数，返回的图片已通过安全检查。
        BufferedImage image = decode(raw, expectedFormat);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            // 重新编码：丢弃 EXIF/XMP，并确保输出确实是声明的图片格式。
            if (!ImageIO.write(image, expectedFormat, encoded)) {
                throw new BizException(ResultCode.BAD_REQUEST, "图片处理失败");
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.BAD_REQUEST, "图片处理失败");
        }
        // jpeg 统一存储扩展名为 jpg，避免同一格式出现两种后缀。
        String canonicalExtension = "jpeg".equals(expectedFormat) ? "jpg" : expectedFormat;
        // 文件名只保留安全 basename，扩展名由服务端按真实格式决定。
        String storedName = safeBaseName(original) + "." + canonicalExtension;
        // 返回的 contentType 与扩展名来自同一格式分支，不会出现二者不一致。
        return new ProcessedImage(encoded.toByteArray(), CONTENT_TYPES.get(expectedFormat), storedName);
    }

    /** 同时用 ImageIO 判断真实格式，并限制解码像素数，避免压缩炸弹。 */
    /*
     * <p>ImageIO 通过读取文件头识别格式，不依赖扩展名。宽高在
     * {@code reader.read(0)} 之前取得，因此超限图片不会被完整解码。
     * 解码完成后由调用方重编码，原始元数据不会保留。</p>
     *
     * @param raw            原始上传字节
     * @param expectedFormat 扩展名推断出的期望格式
     * @return 解码后的图片
     * @throws BizException 格式不符、像素超限或不是有效图片
     */
    private BufferedImage decode(byte[] raw, String expectedFormat) {
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            // 没有可用 reader 说明不是 ImageIO 支持的图片。
            // input 为 null 时也走同一分支，避免空指针。
            Iterator<ImageReader> readers = input == null
                    ? Collections.emptyIterator() : ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            reader = readers.next();
            // 真实格式必须与扩展名一致，防止改后缀绕过白名单。
            // 例如把 HTML/脚本文件改名为 .png，会在这里被识别并拒绝。
            // 注意 jpeg 的 ImageIO 格式名就是 "jpeg"，与映射表保持一致。
            String actualFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!expectedFormat.equals(actualFormat)) {
                throw new BizException(ResultCode.BAD_REQUEST, "文件扩展名与实际图片类型不一致");
            }
            // 禁止缓存与忽略元数据，减少内存占用并避免读取无关内容。
            reader.setInput(input, true, true);
            // 先读宽高计算像素数，再决定是否真正解码。
            // 宽高相乘使用 long，防止 int 溢出导致绕过限制。
            long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
            if (pixels > maxPixels) {
                throw new BizException(ResultCode.BAD_REQUEST, "图片分辨率过大");
            }
            BufferedImage image = reader.read(0);
            if (image == null) {
                // 部分损坏文件能通过宽高读取，但解码结果为 null。
                throw invalidImage();
            }
            // 返回解码后的像素数据；原始字节不会再被使用。
            return image;
        } catch (BizException e) {
            // 业务异常携带明确提示，直接向上抛。
            // 包括格式不一致、像素超限等可读错误。
            throw e;
        } catch (IOException | RuntimeException e) {
            // 解析类异常统一归一为“不是有效图片”，不泄露底层细节。
            // ImageIO 在不同 JDK 上抛出的异常类型不完全一致，因此统一兜底。
            throw invalidImage();
        } finally {
            // 释放 ImageReader 持有的本地资源。
            // 即使解码抛异常也必须执行，否则可能造成资源泄漏。
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /**
     * 构造统一的“无效图片”业务异常。
     *
     * @return 400 业务异常
     */
    private static BizException invalidImage() {
        return new BizException(ResultCode.BAD_REQUEST, "文件不是有效的 JPG/PNG 图片");
    }

    /**
     * 从原始文件名生成安全的 basename。
     *
     * <p>去掉路径部分、扩展名与非白名单字符，限制长度；结果为空时使用
     * {@code image} 兜底。最终文件名只作为存储 key 的可读后缀，不参与
     * 路径解析，因此不会造成目录穿越。</p>
     *
     * @param original 原始文件名
     * @return 不含路径与扩展名的安全 basename
     */
    private static String safeBaseName(String original) {
        String name = original;
        // 同时处理 Unix 与 Windows 路径分隔符，防止 ../../ 之类的路径穿越。
        // 这里只取最后一段并丢弃目录，最终文件名不参与任何文件系统路径。
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        // 只保留字母、数字、点、下划线与连字符，其余替换为下划线。
        // 中文等非 ASCII 字符会被替换，保证 key 在不同存储后端都安全。
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.isBlank()) {
            name = "image";
        }
        // 限制长度，避免生成超长对象 key。
        // 截断只影响可读性，随机前缀仍保证 key 唯一。
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    /**
     * 处理完成的图片数据。
     *
     * @param bytes       重新编码后的图片字节
     * @param contentType 服务端判定的 MIME 类型
     * @param storedName  清洗后的安全文件名（含规范扩展名）
     */
    public record ProcessedImage(byte[] bytes, String contentType, String storedName) {
    }
}
