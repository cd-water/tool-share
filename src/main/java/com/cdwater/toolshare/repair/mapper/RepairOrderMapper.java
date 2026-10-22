package com.cdwater.toolshare.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.repair.entity.RepairOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 报修单 Mapper
 */
@Mapper
public interface RepairOrderMapper extends BaseMapper<RepairOrder> {

    /**
     * 报修单分页：工具编号模糊匹配需要联 tool 表（仅做存在性过滤，不读取 tool 包数据）
     */
    @Select("""
            <script>
            SELECT ro.*
            FROM repair_order ro
            WHERE 1 = 1
            <if test="toolCode != null and toolCode != ''">
                AND EXISTS (
                    SELECT 1 FROM `tool` t
                    WHERE t.id = ro.tool_id
                      AND t.tool_code LIKE CONCAT('%', #{toolCode}, '%')
                )
            </if>
            <if test="repairStatus != null">
                AND ro.repair_status = #{repairStatus}
            </if>
            <if test="reporterId != null">
                AND ro.reporter_id = #{reporterId}
            </if>
            ORDER BY ro.create_time DESC, ro.id DESC
            </script>
            """)
    IPage<RepairOrder> selectRepairPage(Page<RepairOrder> page,
                                        @Param("toolCode") String toolCode,
                                        @Param("repairStatus") Integer repairStatus,
                                        @Param("reporterId") Long reporterId);
}
