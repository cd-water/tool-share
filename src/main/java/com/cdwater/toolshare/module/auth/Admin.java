/**
 * 管理端账号实体文件。
 * <p>对应 t_admin 表，只被管理端认证与权限模块使用。
 * 与 C 端 User 分表、分登录体系：管理端会话在 StpKit.ADMIN，用户会话在 StpKit.USER。
 * <p>关键约束：
 * <ul>
 *   <li>password 只存 BCrypt 密文，任何接口都不得返回；</li>
 *   <li>role 只允许 STAFF / SUPER_ADMIN，由 AdminRole 枚举约束；</li>
 *   <li>status 为 0 或 null 时禁止登录与鉴权；</li>
 *   <li>角色权限不落库缓存，AdminStpInterface 每次鉴权按当前 role 计算。</li>
 * </ul>
 * <p>调用方：AdminService（登录/账号管理）、AdminStpInterface（权限加载）。
 */
package com.cdwater.toolshare.module.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端账号实体，对应数据库表 t_admin。
 * <p>管理端账号与 C 端用户分表、分登录体系：管理端 token 归属于 {@code StpKit.ADMIN}，
 * 用户 token 归属于 {@code StpKit.USER}，两者互不通用。
 * <p>password 只保存 BCrypt 密文；role 决定可访问的管理端接口，
 * 具体权限由 {@link AdminStpInterface} 在每次鉴权时按数据库当前角色计算。
 */
@Data
@TableName("t_admin")
public class Admin {

    @TableId(type = IdType.AUTO)
    // 自增主键，同时作为管理端登录会话的 loginId
    private Long id;

    // 登录名，全局唯一；仅用于管理端登录，不与用户手机号混用
    private String username;

    /** BCrypt 密文 */
    private String password;

    /** STAFF / SUPER_ADMIN */
    private String role;

    /** 0-禁用 1-启用 */
    private Integer status;

    // 记录创建时间，由数据库默认值/持久层填充，不参与业务校验
    private LocalDateTime createTime;

    // 最近更新时间，角色变更等写操作会刷新
    private LocalDateTime updateTime;
}
