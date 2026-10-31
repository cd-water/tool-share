package com.cdwater.toolshare.module.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_knowledge_doc")
public class KnowledgeDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String fileName;

    private String content;

    private Boolean indexed;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
