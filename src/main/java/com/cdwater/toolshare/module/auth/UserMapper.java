/**
 * 用户 Mapper 文件。
 * <p>继承 MyBatis-Plus BaseMapper，提供 t_user 的通用 CRUD。
 * <h2>自定义 SQL</h2>
 * <p>selectByIdForUpdate：SELECT ... FOR UPDATE，用于信用分变更前锁定用户行。
 * 必须在事务内调用，锁在事务结束时释放；作用是串行化同一用户的并发加减分，
 * 避免读-改-写丢失更新。
 * <p>调用方：UserService、CreditService 等。
 */
package com.cdwater.toolshare.module.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.auth.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户数据访问层。
 * <p>除通用 CRUD 外，只额外提供信用分变更所需的行锁查询。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    // 必须在事务内调用，锁在事务提交或回滚时释放。
    // 这里用 SELECT ... FOR UPDATE 而非乐观锁：信用分是读-改-写，
    // 需要保证同一用户的并发加减分不丢失。
    /** 信用分变更前锁定用户行，串行化同一用户的并发加减分。 */
    @Select("SELECT * FROM t_user WHERE id = #{id} FOR UPDATE")
    User selectByIdForUpdate(@Param("id") Long id);
}
