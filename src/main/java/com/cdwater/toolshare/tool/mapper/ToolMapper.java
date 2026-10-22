package com.cdwater.toolshare.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.tool.entity.Tool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工具 Mapper
 */
@Mapper
public interface ToolMapper extends BaseMapper<Tool> {

    /**
     * 查询指定类别下已使用到的最大序号（工具编号格式 SQ-G-XX-YYYY）
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(tool_code, '-', -1) AS UNSIGNED)), 0)
            FROM tool
            WHERE tool_code LIKE CONCAT('SQ-G-', #{categoryCode}, '-%')
            """)
    long selectMaxSeqByCategory(@Param("categoryCode") String categoryCode);
}
