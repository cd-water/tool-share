/**
 * C 端用户实体文件。
 * <p>对应 t_user 表，是用户认证、预约与个人资料的根对象。
 * <h2>关键字段</h2>
 * <ul>
 *   <li>phone：登录唯一标识，同时作为租赁联系方式；</li>
 *   <li>password：BCrypt 密文，验证码静默注册的用户为空；</li>
 *   <li>realName/idCard：创建租赁单前必须完善；</li>
 *   <li>idCard：enc: 前缀 AES 密文，历史数据可能是明文；</li>
 *   <li>status：0 禁用、1 启用；</li>
 *   <li>creditScore：信用分，初始 100，由 CreditService 在归还等事件中调整。</li>
 * </ul>
 * <p>用户行在信用分变更时通过 UserMapper#selectByIdForUpdate 加锁。
 */
package com.cdwater.toolshare.module.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * C 端用户实体，对应数据库表 t_user。
 * <p>用户可通过验证码登录静默注册，因此 password 可能为空；
 * 身份证号以 {@code enc:} 前缀的 AES 密文保存，历史数据可能仍是明文，
 * 读取时必须经过 {@code IdCardCipher} 统一脱敏。
 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    // 自增主键，同时作为用户端登录会话的 loginId
    private Long id;

    // 手机号，用户唯一标识；登录与预约联系方式均使用该字段
    private String phone;

    /** BCrypt 密文，静默注册用户为 null */
    private String password;

    // 展示昵称；静默注册时自动生成 user_ 前缀的随机串
    private String nickname;

    // 真实姓名；创建租赁单前必须完善，并会快照到租赁单
    private String realName;

    /** enc: 前缀为 AES 密文，历史数据可能是明文 */
    private String idCard;

    // 头像地址，可为空串
    private String avatar;

    /** 0-禁用 1-启用 */
    private Integer status;

    /** 信用分 0-100，初始 100 */
    private Integer creditScore;

    // 注册时间
    private LocalDateTime createTime;

    // 最近资料更新时间
    private LocalDateTime updateTime;
}
