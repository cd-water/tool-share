package com.cdwater.toolshare.module.damage;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.damage.DamageReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * 报修单表的 MyBatis-Plus Mapper。
 * <p>仅使用 BaseMapper 提供的通用增删改查；条件构造与状态控制位于
 * <p>涉及行锁的查询（如 selectByIdForUpdate）位于租赁 Mapper，
 * 本 Mapper 只承担报修单自身的增删改查与分页。
 * {@link DamageService}，不在此处扩展自定义 SQL。
 */
@Mapper
public interface DamageReportMapper extends BaseMapper<DamageReport> {
}
