/**
 * 管理端账号 Mapper 文件。
 * <p>继承 MyBatis-Plus BaseMapper，提供 t_admin 的通用 CRUD。
 * 项目没有为管理端账号编写自定义 SQL：
 * 登录按 username 精确查询、角色修改按主键更新，都可由通用方法完成。
 * <p>调用方：AdminService、AdminStpInterface。
 */
package com.cdwater.toolshare.module.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.auth.Admin;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理端账号数据访问层。
 * <p>继承 MyBatis-Plus 的通用 CRUD，无自定义 SQL；
 * 账号查询、角色更新与登录校验都通过通用方法完成。
 */
@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
}
