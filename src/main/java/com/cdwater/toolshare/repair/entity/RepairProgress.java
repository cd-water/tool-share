package com.cdwater.toolshare.repair.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维修进度表（进度多次更新，逐条可追溯）
 */
@Data
@TableName("repair_progress")
public class RepairProgress {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报修单 id */
    private Long repairId;

    /** 维修进度：1待配件 2维修中 3维修完成 4维修失败（报废） */
    private Integer status;

    /** 进度说明 */
    private String description;

    /** 操作人 user.id */
    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
