package com.cdwater.toolshare.module.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.auth.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 信用分变更前锁定用户行，串行化同一用户的并发加减分。 */
    @Select("SELECT * FROM t_user WHERE id = #{id} FOR UPDATE")
    User selectByIdForUpdate(@Param("id") Long id);
}
