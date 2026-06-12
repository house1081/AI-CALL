-- 演示数据：线路 + 示例商户 + 客户（外呼任务可跑通）
USE `ai-call`;

UPDATE `risk_config` SET `call_start_time` = '08:00', `call_end_time` = '22:00', `call_interval` = 10 WHERE `id` = 1;

-- 仅当库中无任何线路时插入演示线路；已有真实线路配置请勿执行本段
INSERT INTO `line` (`sip_account`, `sip_password`, `sip_address`, `cost_price`, `status`, `daily_call_limit`)
SELECT 'demo_line', 'demo_pass', '192.168.60.28:5060', 0.0600, 1, 1000
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `line` LIMIT 1);

INSERT INTO `tenant` (`username`, `password`, `contact_name`, `contact_phone`, `balance`, `pending_deduct`,
  `price_type`, `sell_price`, `status`, `daily_call_limit`)
SELECT 'demo', '0192023a7bbd73250516f069df18b500', '演示商户', '13800000001', 500.00, 0, 1, 0.1500, 1, 5000
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tenant` WHERE `username` = 'demo');

SET @tid = (SELECT `id` FROM `tenant` WHERE `username` = 'demo' LIMIT 1);

INSERT INTO `customer_group` (`group_name`, `tenant_id`)
SELECT '默认分组', @tid FROM DUAL
WHERE @tid IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `customer_group` WHERE `tenant_id` = @tid AND `group_name` = '默认分组');

SET @gid = (SELECT `id` FROM `customer_group` WHERE `tenant_id` = @tid AND `group_name` = '默认分组' LIMIT 1);

INSERT INTO `customer` (`phone`, `name`, `province`, `group_id`, `group_name`, `tenant_id`)
SELECT * FROM (
  SELECT '13800001001' AS phone, '张三' AS name, '广东' AS province, @gid AS group_id, '默认分组' AS group_name, @tid AS tenant_id
  UNION SELECT '13800001002', '李四', '北京', @gid, '默认分组', @tid
  UNION SELECT '13800001003', '王五', '上海', @gid, '默认分组', @tid
  UNION SELECT '13800001004', '赵六', '浙江', @gid, '默认分组', @tid
  UNION SELECT '13800001005', '钱七', '江苏', @gid, '默认分组', @tid
) t
WHERE @tid IS NOT NULL AND @gid IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `customer` c WHERE c.`tenant_id` = @tid AND c.`phone` = t.phone);
