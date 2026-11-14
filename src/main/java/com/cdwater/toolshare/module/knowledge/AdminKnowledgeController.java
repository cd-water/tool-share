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

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "knowledge:manage")
public class AdminKnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping("/knowledge")
    public Result<List<KnowledgeDocVO>> list() {
        return Result.success(knowledgeService.list());
    }

    @PostMapping("/knowledge")
    public Result<KnowledgeDocVO> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "title", required = false) String title) {
        return Result.success(knowledgeService.upload(file, title));
    }

    @DeleteMapping("/knowledge/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return Result.success();
    }
}
