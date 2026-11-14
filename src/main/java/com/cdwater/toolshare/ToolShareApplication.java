package com.cdwater.toolshare;

import com.cdwater.toolshare.module.auth.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 工具共享平台启动类。
 *
 * <p>{@code @SpringBootApplication} 开启组件扫描与自动配置；
 * {@code @EnableScheduling} 启用定时任务（到期提醒、逾期扫描等）；
 * {@code @EnableConfigurationProperties} 显式注册认证配置，使其可在
 * 启动早期被注入。</p>
 *
 * <p>演示数据、身份证迁移、种子图片等启动逻辑由各
 * {@code ApplicationRunner} 按 {@code @Order} 执行，不在此类中硬编码。</p>
 */
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
@SpringBootApplication
public class ToolShareApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数，交由 Spring Boot 解析
     */
    public static void main(String[] args) {
        // 启动 Spring 容器；初始化失败会直接退出进程。
        SpringApplication.run(ToolShareApplication.class, args);
    }

}
