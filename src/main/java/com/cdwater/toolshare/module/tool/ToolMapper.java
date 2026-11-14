package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.tool.Tool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ToolMapper extends BaseMapper<Tool> {

    /** 预约创建前锁定工具行，串行化同一工具的并发冲突检查与插入。 */
    @Select("SELECT * FROM t_tool WHERE id = #{id} FOR UPDATE")
    Tool selectByIdForUpdate(@Param("id") Long id);
}
