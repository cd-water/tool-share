/**
 * 工具操作日志 Mapper 文件。
 * <p>继承 MyBatis-Plus BaseMapper，只使用通用 CRUD。
 * 日志按追加方式写入，项目没有提供更新或删除入口；
 * 查询按 toolId 过滤并在服务层按创建时间倒序。
 * <p>调用方：ToolService#logStructured、ToolService#logs。
 */
package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.tool.ToolLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具操作日志数据访问层。
 * <p>只有通用 CRUD，没有更新或删除入口：审计日志按追加方式使用。
 */
@Mapper
public interface ToolLogMapper extends BaseMapper<ToolLog> {
}
