-- V2 · 初始化演示数据（seed）
-- 有密码账号的密码明文均为 Aa123456（BCrypt 加密存储）
-- 演示图片统一使用 GitHub 头像

-- 居民1：有密码，可用 手机号 + Aa123456 登录；已完善实名资料
INSERT INTO `t_user` (`id`, `phone`, `password`, `nickname`, `real_name`, `id_card`, `avatar`)
VALUES (1, '13800000001', '$2a$10$GIo5IeU8h/hoUoXFt6sFOu91qMy.0QrjdS6zO0jcC9znCQ.5cIvpC',
        'user_mjafo8', '王建国', '310101199001011234', 'https://github.com/cd-water.png');

-- 居民2：静默注册形态，无密码（仅验证码登录）
INSERT INTO `t_user` (`id`, `phone`, `nickname`, `avatar`)
VALUES (2, '13800000002', 'user_214neo', 'https://github.com/cd-water.png');

-- 管理员：账号 admin，密码 Aa123456
INSERT INTO `t_admin` (`id`, `username`, `password`)
VALUES (1, 'admin', '$2a$10$GIo5IeU8h/hoUoXFt6sFOu91qMy.0QrjdS6zO0jcC9znCQ.5cIvpC');

-- 演示工具
INSERT INTO `t_tool` (`id`, `code`, `name`, `model`, `category`, `damage_level`, `status`, `deposit`, `storage_area`, `usage_guide`, `image_url`, `image_urls`, `purchase_date`, `supplier`, `created_by`)
VALUES (1, 'SQ-G-WX-0001', '手电钻', '博世 GSB550（550W，夹头最大 13mm）', 'WX', 'LIGHT', 'AVAILABLE', 200.00, '社区服务站 2 号工具柜', '<p>1. 操作前检查电源线与夹头；</p><p>2. 钻孔时垂直对准目标位置，缓慢按下开关；</p><p>3. 使用后断电并清理碎屑。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2024-03-15', '鼎力机电设备有限公司', 1),
       (2, 'SQ-G-WX-0002', '家用工具箱', '史丹利 65 件套', 'WX', 'NEW', 'AVAILABLE', 100.00, '社区服务站 1 号工具柜', '<p>包含螺丝刀、扳手、锤子等常用工具，使用后请按原位归放。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2024-05-20', '史丹利工具专卖店', 1),
       (3, 'SQ-G-YY-0001', '园艺修枝剪', '嘉丁拿 8857', 'YY', 'LIGHT', 'AVAILABLE', 50.00, '社区服务站 3 号工具柜', '<p>仅用于修剪树枝与绿篱，使用后擦拭刀片并涂防锈油。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2024-04-08', '绿友园林用品', 1),
       (4, 'SQ-G-YY-0002', '手推式割草机', '本田 GX35', 'YY', 'MEDIUM', 'AVAILABLE', 300.00, '社区服务站车库 A 区', '<p>使用前检查机油与刀片，禁止在湿滑草地作业。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2023-09-12', '绿友园林用品', 1),
       (5, 'SQ-G-QJ-0001', '高压清洗机', '凯驰 K3', 'QJ', 'LIGHT', 'AVAILABLE', 250.00, '社区服务站 4 号工具柜', '<p>接通水源后排气，使用完毕排空水管并擦干机身。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2024-06-01', '凯驰清洁设备', 1),
       (6, 'SQ-G-QJ-0002', '吸尘器', '戴森 V8', 'QJ', 'NEW', 'AVAILABLE', 180.00, '社区服务站 4 号工具柜', '<p>使用前确认滤网干燥，尘盒满后及时清理。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2025-01-18', '戴森官方旗舰店', 1),
       (7, 'SQ-G-WX-0003', '电锤', '博世 GBH 2-26', 'WX', 'MEDIUM', 'AVAILABLE', 350.00, '社区服务站 2 号工具柜', '<p>佩戴护目镜与防尘口罩，钻孔前确认墙体管线位置。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2023-11-30', '鼎力机电设备有限公司', 1),
       (8, 'SQ-G-QT-0001', '折叠梯', '奥鹏 5 步梯', 'QT', 'NEW', 'AVAILABLE', 80.00, '社区服务站门厅', '<p>展开后确认卡扣锁定，禁止站在最高两阶作业。</p>', 'https://github.com/cd-water.png', 'https://github.com/cd-water.png', '2025-02-14', '奥鹏家居', 1);

-- 演示知识库（indexed=0，应用启动时自动补建向量索引）
INSERT INTO `t_knowledge_doc` (`title`, `file_name`, `content`, `indexed`)
VALUES ('社区工具租赁规则', '租赁规则.txt', '社区共享工具租赁规则：居民需完善真实姓名与身份证号后方可预约。预约时需选择开始与结束时间，系统自动校验时段是否冲突。押金按工具价值收取，支付后预约生效。取件前 24 小时外可取消预约，押金原路退还；24 小时内取消需联系社区工作人员。租赁逾期按每小时 5 元收取逾期费，最高不超过押金。归还时由工作人员验收，如有严重损坏将从押金中扣除赔偿。', 0),
       ('手电钻安全操作说明', '手电钻安全操作.txt', '手电钻使用前请检查电源线是否破损、夹头是否夹紧钻头。钻孔时保持身体稳定，垂直对准目标位置，缓慢按下开关，避免用力过猛。禁止用于金属以外的坚硬材质，如石材。使用后关闭开关、拔掉电源并清理钻头碎屑。若发现电机异响、接口发黑或无法转动，请立即停止使用并提交损坏报修。', 0),
       ('园艺工具使用与保养', '园艺工具保养.txt', '园艺修枝剪仅用于修剪树枝与绿篱，使用后应擦拭刀片并涂抹防锈油。割草机使用前检查机油与刀片，禁止在湿滑草地作业。长时间不使用时请存放在干燥工具柜内。损坏报修时请说明损坏部位并上传照片，工作人员会在核验后安排维修。', 0);
