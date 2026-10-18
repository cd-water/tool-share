package com.cdwater.toolshare.rental.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.rental.dto.RentalQueryDTO;
import com.cdwater.toolshare.rental.entity.RentalOrder;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租赁单 Mapper（VO 查询见 resources/mapper/RentalOrderMapper.xml）
 */
@Mapper
public interface RentalOrderMapper extends BaseMapper<RentalOrder> {

    /**
     * 分页查询租赁记录（关联工具、租赁人）
     *
     * @param userId 非空时只查该用户的记录（居民端）
     */
    IPage<RentalOrderVO> selectVoPage(Page<RentalOrderVO> page,
                                      @Param("q") RentalQueryDTO query,
                                      @Param("userId") Long userId);

    /**
     * 查询符合条件的全部租赁记录（导出用，不分页）
     */
    List<RentalOrderVO> selectVoList(@Param("q") RentalQueryDTO query,
                                     @Param("userId") Long userId);

    /**
     * 按 id 查询租赁单详情（关联工具、租赁人）
     */
    RentalOrderVO selectVoById(@Param("id") Long id);
}
