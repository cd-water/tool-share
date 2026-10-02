-- =============================================
-- 测试种子数据
-- 约定：密码统一 Aa123456（BCrypt 哈希）；
--       id 显式指定并用 setval 重置序列，可重复执行（TRUNCATE 幂等）；
--       时间相对 CURRENT_TIMESTAMP，不随时间腐烂；
--       phone / id_card 为明文占位（线上由 TypeHandler 加密）
-- =============================================
TRUNCATE sys_user, sys_config, tool_category, tool_info, tool_image, tool_status_log,
    rental_order, pay_record, repair_order, repair_progress, repair_image, msg_record, kb_document,
    kb_chunk RESTART IDENTITY CASCADE;

-- ============ 用户（admin/staff 居民密码均为 Aa123456） ============
INSERT INTO sys_user (id, username, password, real_name, phone, id_card, role, status)
VALUES (1, 'admin', '$2a$10$SwwMs7T7E2rWloWEZP9ABeANabe992RETeA4.0HOfr2AqszhwSkiu', '王建国', '13800000001',
        '310101198501010011', 'ADMIN', 'ACTIVE'),
       (2, 'zhangwei', '$2a$10$SwwMs7T7E2rWloWEZP9ABeANabe992RETeA4.0HOfr2AqszhwSkiu', '张伟', '13800000002',
        '310101198803052233', 'STAFF', 'ACTIVE'),
       (3, 'lina', '$2a$10$SwwMs7T7E2rWloWEZP9ABeANabe992RETeA4.0HOfr2AqszhwSkiu', '李娜', '13800000003',
        '310101199002124455', 'STAFF', 'ACTIVE'),
       (4, 'chenxm', '$2a$10$SwwMs7T7E2rWloWEZP9ABeANabe992RETeA4.0HOfr2AqszhwSkiu', '陈晓明', '13800000004',
        '310101199509156677', 'RESIDENT', 'ACTIVE'),
       (5, 'liufang', '$2a$10$SwwMs7T7E2rWloWEZP9ABeANabe992RETeA4.0HOfr2AqszhwSkiu', '刘芳', '13800000005',
        '310101199211288899', 'RESIDENT', 'ACTIVE'),
       (6, 'zhaolei', '$2a$10$SwwMs7T7E2rWloWEZP9ABeANabe992RETeA4.0HOfr2AqszhwSkiu', '赵磊', '13800000006',
        '310101199707101010', 'RESIDENT', 'ACTIVE');

INSERT INTO sys_config (id, config_key, config_value, remark)
VALUES (1, 'default_remind_channel', 'IN_APP', '默认提醒方式：IN_APP / SMS'),
       (2, 'overdue_fee_per_day', '5.00', '逾期费率（元/天）');

-- ============ 工具 ============
INSERT INTO tool_category (id, code, name)
VALUES (1, 'REPAIR', '维修'),
       (2, 'GARDEN', '园艺'),
       (3, 'CLEAN', '清洁'),
       (4, 'ELECTRIC', '电动');

INSERT INTO tool_info (id, tool_code, name, model, category_id, description, damage_level, deposit_amount, storage_area,
                       purchase_date, supplier, creator_id, audit_status, status, rental_count, last_repair_time)
VALUES (1, 'SQ-G-REPAIR-0001', '电钻', '博世 GSB 600', 1, '家用冲击电钻，含 15 支钻头套装。', 'NONE', 100.00, 'A区-1号架',
        DATE '2024-03-15', '鼎力机电设备有限公司 021-66012345', 2, 'APPROVED', 'AVAILABLE', 12, NULL),
       (2, 'SQ-G-REPAIR-0002', '圆锯', '牧田 4100NH', 1, '木工圆锯，配有护罩与备用锯片。', 'LIGHT', 150.00, 'A区-2号架',
        DATE '2024-05-20', '鼎力机电设备有限公司 021-66012345', 2, 'APPROVED', 'AVAILABLE', 8,
        CURRENT_TIMESTAMP - INTERVAL '20 days'),
       (3, 'SQ-G-REPAIR-0003', '热风枪', '得伟 D26411', 1, '双档温控热风枪，贴膜/除漆通用。', 'SEVERE', 120.00,
        'A区-3号架', DATE '2023-11-08', '恒达五金工具批发部 021-56784321', 3, 'APPROVED', 'REPAIRING', 15, NULL),
       (4, 'SQ-G-GARDEN-0001', '割草机', '本田 HRU196', 2, '手推式汽油割草机，适用于中型草坪。', 'NONE', 200.00,
        'B区-1号架', DATE '2024-04-02', '绿洲园林机械有限公司 021-58761234', 3, 'APPROVED', 'RESERVED', 6, NULL),
       (5, 'SQ-G-GARDEN-0002', '修枝剪', '嘉丁拿 8809', 2, '省力修枝剪，剪径 25mm。', 'NONE', 30.00, 'B区-2号架',
        DATE '2023-09-12', '绿洲园林机械有限公司 021-58761234', 3, 'APPROVED', 'AVAILABLE', 20, NULL),
       (6, 'SQ-G-GARDEN-0003', '自动洒水器', '嘉丁拿 1971', 2, '旋转洒水器，覆盖半径 15m。', 'LIGHT', 40.00, 'B区-3号架',
        DATE '2023-09-12', '绿洲园林机械有限公司 021-58761234', 2, 'APPROVED', 'AVAILABLE', 9,
        CURRENT_TIMESTAMP - INTERVAL '35 days'),
       (7, 'SQ-G-CLEAN-0001', '高压清洗机', '凯驰 K2', 3, '家用高压清洗机，含 3 种喷头。', 'NONE', 180.00, 'C区-1号架',
        DATE '2024-01-18', '洁美清洁设备有限公司 021-54409876', 2, 'APPROVED', 'IN_USE', 11, NULL),
       (8, 'SQ-G-CLEAN-0002', '地毯清洗机', '必胜 86T3', 3, '喷抽一体地毯清洗机。', 'MEDIUM', 220.00, 'C区-2号架',
        DATE '2024-01-18', '洁美清洁设备有限公司 021-54409876', 3, 'APPROVED', 'AVAILABLE', 4,
        CURRENT_TIMESTAMP - INTERVAL '10 days'),
       (9, 'SQ-G-CLEAN-0003', '工业吸尘器', '德尔玛 DX700', 3, '干湿两用工业吸尘器，30L 容量。', 'NONE', 90.00,
        'C区-3号架', DATE '2023-06-25', '洁美清洁设备有限公司 021-54409876', 3, 'APPROVED', 'IN_USE', 7, NULL),
       (10, 'SQ-G-ELECTRIC-0001', '角磨机', '东成 S1M-FF-100A', 4, '多功能角磨机，含切割/打磨片。', 'SEVERE', 130.00,
        'D区-1号架', DATE '2023-03-30', '恒达五金工具批发部 021-56784321', 2, 'APPROVED', 'DISCARDED', 25,
        CURRENT_TIMESTAMP - INTERVAL '3 days');

INSERT INTO tool_image (id, tool_id, url, sort)
VALUES (1, 1, 'http://localhost:10000/tool-share/tool/SQ-G-REPAIR-0001/main.jpg', 0),
       (2, 1, 'http://localhost:10000/tool-share/tool/SQ-G-REPAIR-0001/detail.jpg', 1),
       (3, 2, 'http://localhost:10000/tool-share/tool/SQ-G-REPAIR-0002/main.jpg', 0),
       (4, 3, 'http://localhost:10000/tool-share/tool/SQ-G-REPAIR-0003/main.jpg', 0),
       (5, 4, 'http://localhost:10000/tool-share/tool/SQ-G-GARDEN-0001/main.jpg', 0),
       (6, 5, 'http://localhost:10000/tool-share/tool/SQ-G-GARDEN-0002/main.jpg', 0),
       (7, 6, 'http://localhost:10000/tool-share/tool/SQ-G-GARDEN-0003/main.jpg', 0),
       (8, 7, 'http://localhost:10000/tool-share/tool/SQ-G-CLEAN-0001/main.jpg', 0),
       (9, 8, 'http://localhost:10000/tool-share/tool/SQ-G-CLEAN-0002/main.jpg', 0),
       (10, 9, 'http://localhost:10000/tool-share/tool/SQ-G-CLEAN-0003/main.jpg', 0),
       (11, 10, 'http://localhost:10000/tool-share/tool/SQ-G-ELECTRIC-0001/main.jpg', 0);

INSERT INTO tool_status_log (id, tool_id, old_status, new_status, reason, operator_id)
VALUES (1, 10, 'AVAILABLE', 'DISCARDED', '归还验收电机烧毁，经管理员确认报废', 1),
       (2, 3, 'AVAILABLE', 'REPAIRING', '居民报修出风口变形，工作人员核验属实', 2),
       (3, 4, 'AVAILABLE', 'RESERVED', '预约成功，锁定时段', 3),
       (4, 7, 'RESERVED', 'IN_USE', '取件出库', 2),
       (5, 2, 'NONE', 'LIGHT', '归还验收发现护罩轻微磨损，重估损坏程度', 2);

-- ============ 租赁（覆盖全部状态） ============
INSERT INTO rental_order (id, order_code, tool_id, user_id, pickup_person, start_time, end_time, deposit_amount,
                          deposit_status, status, pickup_time, return_time, accept_result, acceptor_id, overdue_fee,
                          compensation_fee, cancel_reason)
VALUES (1, 'SQ-YG-20261004-0001', 4, 4, '陈晓明', CURRENT_TIMESTAMP + INTERVAL '2 days',
        CURRENT_TIMESTAMP + INTERVAL '5 days', 200.00, 'PAID', 'RESERVED', NULL, NULL, NULL, NULL, 0, 0, NULL),
       (2, 'SQ-YG-20260927-0002', 1, 5, '刘芳', CURRENT_TIMESTAMP - INTERVAL '5 days',
        CURRENT_TIMESTAMP - INTERVAL '2 days', 100.00, 'PAID', 'RETURNED',
        CURRENT_TIMESTAMP - INTERVAL '5 days' + INTERVAL '1 hour',
        CURRENT_TIMESTAMP - INTERVAL '2 days' + INTERVAL '2 hours', 'NORMAL', 2, 0, 0, NULL),
       (3, 'SQ-YG-20260924-0003', 3, 4, '陈晓明', CURRENT_TIMESTAMP - INTERVAL '8 days',
        CURRENT_TIMESTAMP - INTERVAL '5 days', 120.00, 'PAID', 'RETURNED', CURRENT_TIMESTAMP - INTERVAL '8 days',
        CURRENT_TIMESTAMP - INTERVAL '5 days', 'MINOR_DAMAGED', 2, 0, 0, NULL),
       (4, 'SQ-YG-20261001-0004', 7, 6, '赵磊', CURRENT_TIMESTAMP - INTERVAL '1 days',
        CURRENT_TIMESTAMP + INTERVAL '2 days', 180.00, 'PAID', 'PICKED_UP', CURRENT_TIMESTAMP - INTERVAL '1 days', NULL,
        NULL, NULL, 0, 0, NULL),
       (5, 'SQ-YG-20260922-0005', 2, 6, '赵磊', CURRENT_TIMESTAMP - INTERVAL '10 days',
        CURRENT_TIMESTAMP - INTERVAL '7 days', 150.00, 'PAID', 'RETURNED', CURRENT_TIMESTAMP - INTERVAL '10 days',
        CURRENT_TIMESTAMP - INTERVAL '4 days', 'NORMAL', 3, 15.00, 0, NULL),
       (6, 'SQ-YG-20261003-0006', 5, 5, '刘芳', CURRENT_TIMESTAMP + INTERVAL '1 days',
        CURRENT_TIMESTAMP + INTERVAL '3 days', 30.00, 'UNPAID', 'PENDING_PAYMENT', NULL, NULL, NULL, NULL, 0, 0, NULL),
       (7, 'SQ-YG-20260926-0007', 9, 4, '陈晓明', CURRENT_TIMESTAMP - INTERVAL '6 days',
        CURRENT_TIMESTAMP - INTERVAL '4 days', 90.00, 'REFUNDED', 'CANCELLED', NULL, NULL, NULL, NULL, 0, 0,
        '行程变动，取件前 24 小时以上取消'),
       (9, 'SQ-YG-20260929-0009', 9, 6, '赵磊', CURRENT_TIMESTAMP - INTERVAL '3 days',
        CURRENT_TIMESTAMP - INTERVAL '1 days', 90.00, 'PAID', 'OVERDUE', CURRENT_TIMESTAMP - INTERVAL '3 days', NULL,
        NULL, NULL, 0, 0, NULL),
       (8, 'SQ-YG-20260918-0008', 10, 6, '赵磊', CURRENT_TIMESTAMP - INTERVAL '12 days',
        CURRENT_TIMESTAMP - INTERVAL '10 days', 130.00, 'PAID', 'RETURNED', CURRENT_TIMESTAMP - INTERVAL '12 days',
        CURRENT_TIMESTAMP - INTERVAL '10 days', 'SEVERE_DAMAGED', 3, 0, 30.00, NULL);

INSERT INTO pay_record (id, pay_code, biz_type, biz_id, amount, status, pay_time)
VALUES (1, 'SQ-PAY-20261003-0001', 'DEPOSIT', 1, 200.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '1 days'),
       (2, 'SQ-PAY-20260927-0002', 'DEPOSIT', 2, 100.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '6 days'),
       (3, 'SQ-PAY-20260924-0003', 'DEPOSIT', 3, 120.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '9 days'),
       (4, 'SQ-PAY-20261001-0004', 'DEPOSIT', 4, 180.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '2 days'),
       (5, 'SQ-PAY-20260922-0005', 'DEPOSIT', 5, 150.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '11 days'),
       (6, 'SQ-PAY-20260926-0007', 'DEPOSIT', 7, 90.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '7 days'),
       (7, 'SQ-PAY-20260918-0008', 'DEPOSIT', 8, 130.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '13 days'),
       (8, 'SQ-PAY-20260926-0009', 'REFUND', 7, 90.00, 'SUCCESS',
        CURRENT_TIMESTAMP - INTERVAL '7 days' + INTERVAL '30 minutes'),
       (9, 'SQ-PAY-20260920-0010', 'REFUND', 8, 100.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '10 days'),
       (10, 'SQ-PAY-20261001-0011', 'REPAIR_FEE', 1, 60.00, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '1 days');

-- ============ 报修 ============
INSERT INTO repair_order (id, repair_code, tool_id, order_id, reporter_id, damage_part, description, status, handler_id,
                          estimated_duration, repair_fee, repair_description, accept_result, reject_reason)
VALUES (1, 'SQ-BX-2026-0001', 3, 3, 4, '出风口', '热风枪出风口变形，开机有异响，无法正常使用', 'REPAIRING', 2,
        '3 个工作日', 60.00, '已更换出风口部件，待观察测试', NULL, NULL),
       (2, 'SQ-BX-2026-0002', 10, 8, 6, '电机与夹头', '角磨机夹头卡死，电机完全无法转动', 'ACCEPTED', 3,
        '2 个工作日', 80.00, '更换夹头后电机仍烧毁，经管理员确认报废', 'FAIL', NULL),
       (3, 'SQ-BX-2026-0003', 6, NULL, 5, '接口处', '洒水器接口处疑似漏水', 'REJECTED', 3,
        NULL, NULL, NULL, NULL, '现场核验为密封圈正常收紧现象，非损坏'),
       (4, 'SQ-BX-2026-0004', 8, NULL, 4, '滚刷轴承', '地毯清洗机滚刷转动有明显异响', 'ACCEPTED', 2,
        '1 个工作日', 45.00, '更换滚刷轴承，运转正常', 'PASS', NULL);

INSERT INTO repair_image (id, repair_id, url, sort)
VALUES (1, 1, 'http://localhost:10000/tool-share/repair/SQ-BX-2026-0001/outlet.jpg', 0),
       (2, 1, 'http://localhost:10000/tool-share/repair/SQ-BX-2026-0001/noise.mp4', 1),
       (3, 3, 'http://localhost:10000/tool-share/repair/SQ-BX-2026-0003/joint.jpg', 0),
       (4, 4, 'http://localhost:10000/tool-share/repair/SQ-BX-2026-0004/roller.jpg', 0);

INSERT INTO repair_progress (id, repair_id, content, operator_id)
VALUES (1, 1, '已接单，待现场核验', 2),
       (2, 1, '核验确认损坏，已联系维修点', 2),
       (3, 1, '维修中，已更换出风口部件', 2),
       (4, 2, '维修完成，更换夹头后电机仍烧毁，建议报废', 3),
       (5, 4, '维修完成，更换滚刷轴承，验收通过重新上架', 2);

-- ============ 消息提醒 ============
INSERT INTO msg_record (id, user_id, type, title, content, channel, send_status, send_time, biz_code)
VALUES (1, 4, 'PICKUP', '取件提醒',
        '您预约的割草机（SQ-G-GARDEN-0001）将于 2 天后可取件，请携带预约单号 SQ-YG-20261004-0001 取件', 'IN_APP', 'SENT',
        CURRENT_TIMESTAMP - INTERVAL '2 hours', 'SQ-YG-20261004-0001'),
       (2, 6, 'DUE_SOON', '归还提醒', '您租赁的高压清洗机（SQ-G-CLEAN-0001）将于 2 天后到期，请按时归还', 'IN_APP', 'SENT',
        CURRENT_TIMESTAMP - INTERVAL '1 days', 'SQ-YG-20261001-0004'),
       (3, 6, 'OVERDUE', '逾期提醒', '您租赁的圆锯（SQ-G-REPAIR-0002）已逾期 3 天，逾期费按 5 元/天计算，请尽快归还',
        'IN_APP', 'SENT', CURRENT_TIMESTAMP - INTERVAL '5 days', 'SQ-YG-20260922-0005'),
       (4, 4, 'REPAIR_DONE', '维修完成', '您报修的地毯清洗机（SQ-G-CLEAN-0002）已维修完成并重新上架，可正常预约', 'IN_APP',
        'SENT', CURRENT_TIMESTAMP - INTERVAL '9 days', 'SQ-BX-2026-0004');

-- ============ RAG 知识库（kb_chunk 向量需真实 embedding API 生成，不造数据） ============
INSERT INTO kb_document (id, doc_name, uploader_id, doc_type, tool_id, file_url, status, chunk_count)
VALUES (1, '电钻安全操作手册.pdf', 1, 'MANUAL', 1, 'http://localhost:10000/tool-share/kb/drill-manual.pdf', 'READY',
        12),
       (2, '社区工具租赁规则.docx', 1, 'RULE', NULL, 'http://localhost:10000/tool-share/kb/rental-rules.docx',
        'PENDING', 0);

-- ============ 重置序列（显式 id 插入后必须执行，否则应用插入会主键冲突） ============
SELECT setval(pg_get_serial_sequence('sys_user', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_user));
SELECT setval(pg_get_serial_sequence('sys_config', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_config));
SELECT setval(pg_get_serial_sequence('tool_category', 'id'), (SELECT COALESCE(MAX(id), 1) FROM tool_category));
SELECT setval(pg_get_serial_sequence('tool_info', 'id'), (SELECT COALESCE(MAX(id), 1) FROM tool_info));
SELECT setval(pg_get_serial_sequence('tool_image', 'id'), (SELECT COALESCE(MAX(id), 1) FROM tool_image));
SELECT setval(pg_get_serial_sequence('tool_status_log', 'id'), (SELECT COALESCE(MAX(id), 1) FROM tool_status_log));
SELECT setval(pg_get_serial_sequence('rental_order', 'id'), (SELECT COALESCE(MAX(id), 1) FROM rental_order));
SELECT setval(pg_get_serial_sequence('pay_record', 'id'), (SELECT COALESCE(MAX(id), 1) FROM pay_record));
SELECT setval(pg_get_serial_sequence('repair_order', 'id'), (SELECT COALESCE(MAX(id), 1) FROM repair_order));
SELECT setval(pg_get_serial_sequence('repair_progress', 'id'), (SELECT COALESCE(MAX(id), 1) FROM repair_progress));
SELECT setval(pg_get_serial_sequence('repair_image', 'id'), (SELECT COALESCE(MAX(id), 1) FROM repair_image));
SELECT setval(pg_get_serial_sequence('msg_record', 'id'), (SELECT COALESCE(MAX(id), 1) FROM msg_record));
SELECT setval(pg_get_serial_sequence('kb_document', 'id'), (SELECT COALESCE(MAX(id), 1) FROM kb_document));
