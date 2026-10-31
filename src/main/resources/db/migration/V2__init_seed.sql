-- 初始化测试数据
-- 有密码账号的密码明文均为 Aa123456（BCrypt 加密存储）

-- 用户1：有密码，可用 手机号 + Aa123456 登录
INSERT INTO `t_user` (`id`, `phone`, `password`, `nickname`)
VALUES (1, '13800000001', '$2a$10$GIo5IeU8h/hoUoXFt6sFOu91qMy.0QrjdS6zO0jcC9znCQ.5cIvpC', 'user_mjafo8');

-- 用户2：静默注册形态，无密码（登录需先走设置密码流程）
INSERT INTO `t_user` (`id`, `phone`, `nickname`)
VALUES (2, '13800000002', 'user_214neo');

-- 管理员：账号 admin，密码 Aa123456
INSERT INTO `t_admin` (`id`, `username`, `password`)
VALUES (1, 'admin', '$2a$10$GIo5IeU8h/hoUoXFt6sFOu91qMy.0QrjdS6zO0jcC9znCQ.5cIvpC');
