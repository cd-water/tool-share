/**
 * 租赁费用明细 Mapper 文件。
 * <p>继承 MyBatis-Plus BaseMapper，只使用通用 CRUD。
 * 费用明细按追加方式写入，查询按 rentalId 过滤并在服务层按创建时间升序返回。
 * <p>调用方：RentalService#recordFee、RentalService#toDetailVO。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租赁费用明细数据访问层。
 * <p>只提供通用 CRUD；费用明细只增不改，便于还原结算过程。
 */
@Mapper
public interface RentalFeeMapper extends BaseMapper<RentalFee> {
}
