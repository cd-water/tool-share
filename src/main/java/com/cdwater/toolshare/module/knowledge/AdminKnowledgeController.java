package com.cdwater.toolshare.module.knowledge;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.knowledge.KnowledgeService;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 管理端知识库控制器。
 * <p>提供文档列表、上传与删除三类接口，挂载在 {@code /api/admin} 下，
 * 并要求 {@code knowledge:manage} 权限。上传接口同时完成解析与一次索引
 * <p>上传返回的视图对象包含索引状态：indexed=false 表示正文已保存但
 * <p>接口边界：
 * <ul>
 *   <li>列表：返回元数据与正文预览，不返回完整正文；</li>
 *   <li>上传：解析并落库，随后同步尝试一次索引；</li>
 *   <li>删除：先清理向量，再删除数据库记录。</li>
 * </ul>
 * <p>上传与删除都可能访问外部向量库，但失败语义不同：上传失败只标记
 * 未索引，删除失败则保留文档，避免检索与数据库不一致。
 * 向量索引失败，系统会自动重试，管理员无需重复上传。
 * 尝试；删除接口会先清理向量再删文档。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "knowledge:manage")
public class AdminKnowledgeController {

    /** 知识库文档服务。 */
    private final KnowledgeService knowledgeService;

    /**
     * 查询全部知识库文档。
     * <p>返回标题、文件名、正文预览与索引状态，按创建时间倒序。
     *
     * @return 文档视图列表
     */
    @GetMapping("/knowledge")
    public Result<List<KnowledgeDocVO>> list() {
        return Result.success(knowledgeService.list());
    }

    /**
     * 上传一份知识库文档。
     * <p>支持 txt/md/csv 以及 Tika 可解析的 PDF、DOCX 等格式；标题可选，
     * 为空时使用文件名。解析或索引失败会通过业务异常返回明确原因。
     *
     * @param file  上传文件
     * @param title 可选标题
     * @return 新建文档的视图对象
     */
    @PostMapping("/knowledge")
    public Result<KnowledgeDocVO> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "title", required = false) String title) {
        return Result.success(knowledgeService.upload(file, title));
    }

    /**
     * 删除知识库文档并清理其向量索引。
     * <p>向量清理失败时文档不会被删除，避免检索与数据库不一致。
     *
     * @param id 文档主键
     * @return 空结果
     */
    @DeleteMapping("/knowledge/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return Result.success();
    }
}
