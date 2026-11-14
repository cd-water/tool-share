/**
 * 资金流水 Mapper 文件。
 * <p>继承 MyBatis-Plus BaseMapper，只使用通用 CRUD。
 * 流水按追加方式写入，查询按 rentalId 过滤并在服务层按创建时间升序返回，
 * 用于详情页还原资金过程。
 * <p>调用方：RentalService#recordPayment、RentalService#toDetailVO。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资金流水数据访问层。
 * <p>只提供通用 CRUD；流水按追加方式使用，不提供修改入口。
 */
@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {
}
