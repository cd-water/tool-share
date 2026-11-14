/**
 * 工具 Mapper 文件。
 * <p>继承 MyBatis-Plus BaseMapper，提供 t_tool 的通用 CRUD。
 * <h2>自定义 SQL</h2>
 * <p>selectByIdForUpdate：SELECT ... FOR UPDATE，预约创建前锁工具行。
 * 拿到行锁后再查档期冲突并插入租赁单，使同一工具的并发预约串行执行。
 * 必须在事务内调用。
 * <p>其他查询（用户端/管理端分页、可用性冲突）通过 Service 层的 QueryWrapper
 * 与 RentalMapper 完成。
 */
package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.tool.Tool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工具数据访问层。
 * <p>除通用 CRUD 外，只提供预约创建所需的行锁查询。
 */
@Mapper
public interface ToolMapper extends BaseMapper<Tool> {

    // 必须在事务内调用；拿到行锁后再查时间冲突并插入租赁单，
    // 这样同一工具的并发预约会串行执行，避免两个请求同时通过冲突检查。
    /** 预约创建前锁定工具行，串行化同一工具的并发冲突检查与插入。 */
    @Select("SELECT * FROM t_tool WHERE id = #{id} FOR UPDATE")
    Tool selectByIdForUpdate(@Param("id") Long id);
}
