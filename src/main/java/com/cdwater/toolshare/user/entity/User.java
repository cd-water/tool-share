package com.cdwater.toolshare.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表（三级角色同一张表）
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名 */
    private String username;

    /** 密码（BCrypt） */
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 手机号（加密存储，展示脱敏） */
    private String phone;

    /** 身份证号（加密存储，展示脱敏） */
    private String idCard;

    /** 角色：0居民 1社区工作人员 2系统管理员 */
    private Integer role;

    /** 状态：1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
