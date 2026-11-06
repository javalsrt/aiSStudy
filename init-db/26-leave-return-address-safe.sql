-- ============================================================
-- 返校打卡：记录定位地址（幂等，可重复执行）
-- 用法：
--   docker exec -i znxsgl-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 \
--     znxsglTest < init-db/26-leave-return-address-safe.sql
-- ============================================================

SET @db := DATABASE();

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'leave_request'
                AND column_name = 'return_address') = 0,
  'ALTER TABLE `leave_request` ADD COLUMN `return_address` VARCHAR(200) NULL COMMENT ''打卡定位地址（App 反查）'' AFTER `return_lng`',
  'SELECT ''leave_request.return_address 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '检查结果' AS step;
SELECT COUNT(*) AS 地址列应有1个 FROM information_schema.columns
WHERE table_schema = @db AND table_name = 'leave_request' AND column_name = 'return_address';
