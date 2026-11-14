package com.cdwater.toolshare.shared.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>注册两个内置拦截器：分页插件与乐观锁插件。分页插件显式指定 MySQL
 * 方言，保证 count 查询与 limit 语法正确；乐观锁插件用于带 {@code @Version}
 * 字段的实体在更新时自动追加版本条件。</p>
 *
 * <p>插件顺序有意义：分页拦截器应先于其它 SQL 改写插件，避免 count
 * 语句被二次改写。当前仅两个插件，顺序按官方推荐排列。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 构建 MyBatis-Plus 拦截器链。
     *
     * @return 已注册分页与乐观锁插件的拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件：统一处理 Page 参数与 count 查询，方言固定为 MySQL。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 乐观锁插件：更新时自动带上 version 条件，冲突时影响行数为 0。
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
