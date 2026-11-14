package com.cdwater.toolshare.shared.storage;

import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口，用户端与管理端共用同一套校验与存储逻辑。
 *
 * <p>两个入口路径不同，是为了让 Sa-Token 按端分别鉴权：{@code /api/user/upload}
 * 校验用户登录态，{@code /api/admin/upload} 校验管理端登录态。进入控制器后
 * 都调用同一个私有方法，避免两端出现不同的安全策略。</p>
 *
 * <p>真正的格式校验、重编码与大小限制在 {@link ImageUploadProcessor} 中完成，
 * 即使测试替换了内存版 StorageService，也无法绕过这些检查。</p>
 */
@RestController
@RequiredArgsConstructor
public class UploadController {

    /** 图片校验与重编码组件，负责拒绝非法文件并丢弃元数据。 */
    private final ImageUploadProcessor imageUploadProcessor;

    /** 对象存储服务，负责生成 key、写入文件并返回访问地址。 */
    private final StorageService storageService;

    /**
     * 用户端上传单张图片。
     *
     * @param file 上传文件，必须为 JPG/JPEG/PNG
     * @return 文件访问地址与服务端文件名
     */
    @PostMapping("/api/user/upload")
    public Result<FileVO> uploadUser(@RequestParam("file") MultipartFile file) {
        // 用户端与管理端共用实现，差异只体现在外层鉴权路径。
        return Result.success(upload(file));
    }

    /**
     * 管理端上传单张图片。
     *
     * @param file 上传文件，必须为 JPG/JPEG/PNG
     * @return 文件访问地址与服务端文件名
     */
    @PostMapping("/api/admin/upload")
    public Result<FileVO> uploadAdmin(@RequestParam("file") MultipartFile file) {
        return Result.success(upload(file));
    }

    /**
     * 统一的图片处理流程：先校验并重编码，再写入对象存储。
     *
     * <p>顺序不可颠倒：只有通过校验的字节才会进入存储，避免把原始
     * 上传内容直接写入桶中。存储 key 由服务端随机生成，不信任原始文件名。</p>
     *
     * @param file 上传文件
     * @return 文件访问地址与服务端文件名
     */
    private FileVO upload(MultipartFile file) {
        ImageUploadProcessor.ProcessedImage image = imageUploadProcessor.process(file);
        return storageService.store(image.bytes(), image.contentType(), image.storedName());
    }
}
