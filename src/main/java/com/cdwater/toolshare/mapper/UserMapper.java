package com.cdwater.toolshare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
