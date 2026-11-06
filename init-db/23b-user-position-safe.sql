-- ============================================================
-- 学生班干部职务迁移（幂等版）
-- 已执行过 23 也能安全重复运行
-- ============================================================

SET @db := DATABASE();

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'user' AND column_name = 'position') = 0,
  'ALTER TABLE `user` ADD COLUMN `position` VARCHAR(30) NULL COMMENT ''班级职务（班长/学习委员等）'' AFTER `grade`',
  'SELECT ''user.position 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF((SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = @db AND table_name = 'user' AND index_name = 'idx_user_class_position') = 0,
  'CREATE INDEX `idx_user_class_position` ON `user` (`class_id`, `position`)',
  'SELECT ''idx_user_class_position 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '检查结果' AS step;
SELECT COUNT(*) AS 学生总数, SUM(position IS NOT NULL AND position <> '') AS 已设职务 FROM `user` WHERE role = 1;
