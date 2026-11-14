package com.cdwater.toolshare.module.credit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 信用分流水 Mapper。
 *
 * <p>只使用 MyBatis-Plus 提供的基础 CRUD：服务层通过 Lambda 条件构造器
 * 完成按用户分页查询与插入，因此无需额外 SQL。插入时依赖数据库唯一键
 * 处理重复事件，Mapper 本身不做去重。</p>
 */
@Mapper
public interface UserCreditEventMapper extends BaseMapper<UserCreditEvent> {
}
