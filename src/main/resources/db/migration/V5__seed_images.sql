-- 演示种子数据统一使用同一张测试图片（GitHub 头像）
UPDATE `t_tool`
SET `image_url`  = 'https://github.com/cd-water.png',
    `image_urls` = 'https://github.com/cd-water.png'
WHERE `id` BETWEEN 1 AND 8;

UPDATE `t_user`
SET `avatar` = 'https://github.com/cd-water.png'
WHERE `phone` IN ('13800000001', '13800000002');
