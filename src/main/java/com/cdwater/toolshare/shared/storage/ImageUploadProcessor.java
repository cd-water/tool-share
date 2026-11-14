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
 */
@Component
public class ImageUploadProcessor {

    /** 扩展名 -> ImageIO 读出的真实格式 */
    private static final Map<String, String> EXTENSION_FORMATS = Map.of(
            "jpg", "jpeg",
            "jpeg", "jpeg",
            "png", "png");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpeg", "image/jpeg",
            "png", "image/png");

    private final long maxBytes;
    private final long maxPixels;

    public ImageUploadProcessor(
            @Value("${toolshare.upload.max-image-bytes:5242880}") long maxBytes,
            @Value("${toolshare.upload.max-image-pixels:40000000}") long maxPixels) {
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
    }

    public ProcessedImage process(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择图片");
        }
        if (file.getSize() > maxBytes) {
            throw new BizException(ResultCode.BAD_REQUEST, "图片不能超过 5MB");
        }
        String original = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "";
        String extension = StringUtils.getFilenameExtension(original);
        String expectedFormat = extension == null ? null : EXTENSION_FORMATS.get(extension.toLowerCase(Locale.ROOT));
        if (expectedFormat == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "仅支持 JPG、PNG 格式的图片");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ResultCode.BAD_REQUEST, "图片读取失败");
        }
        if (raw.length > maxBytes) {
            throw new BizException(ResultCode.BAD_REQUEST, "图片不能超过 5MB");
        }

        BufferedImage image = decode(raw, expectedFormat);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, expectedFormat, encoded)) {
                throw new BizException(ResultCode.BAD_REQUEST, "图片处理失败");
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.BAD_REQUEST, "图片处理失败");
        }
        String canonicalExtension = "jpeg".equals(expectedFormat) ? "jpg" : expectedFormat;
        String storedName = safeBaseName(original) + "." + canonicalExtension;
        return new ProcessedImage(encoded.toByteArray(), CONTENT_TYPES.get(expectedFormat), storedName);
    }

    /** 同时用 ImageIO 判断真实格式，并限制解码像素数，避免压缩炸弹。 */
    private BufferedImage decode(byte[] raw, String expectedFormat) {
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = input == null
                    ? Collections.emptyIterator() : ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            reader = readers.next();
            String actualFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!expectedFormat.equals(actualFormat)) {
                throw new BizException(ResultCode.BAD_REQUEST, "文件扩展名与实际图片类型不一致");
            }
            reader.setInput(input, true, true);
            long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
            if (pixels > maxPixels) {
                throw new BizException(ResultCode.BAD_REQUEST, "图片分辨率过大");
            }
            BufferedImage image = reader.read(0);
            if (image == null) {
                throw invalidImage();
            }
            return image;
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalidImage();
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private static BizException invalidImage() {
        return new BizException(ResultCode.BAD_REQUEST, "文件不是有效的 JPG/PNG 图片");
    }

    private static String safeBaseName(String original) {
        String name = original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.isBlank()) {
            name = "image";
        }
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    public record ProcessedImage(byte[] bytes, String contentType, String storedName) {
    }
}
