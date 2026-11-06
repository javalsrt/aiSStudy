-- ============================================================
-- 教师职务 / 班主任迁移（幂等版）
-- 已执行过 22 也能安全重复运行：列/索引已存在会跳过，不报错
-- 用法：mysql -uroot -p znxsglTest < 22b-teacher-duty-safe.sql
-- ============================================================

SET @db := DATABASE();

-- 1) teacher.duty
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'teacher' AND column_name = 'duty') = 0,
  'ALTER TABLE `teacher` ADD COLUMN `duty` VARCHAR(30) NOT NULL DEFAULT ''教师'' COMMENT ''职务：教师/班主任/教导主任/副校长'' AFTER `title`',
  'SELECT ''duty 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) teacher.is_leader
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'teacher' AND column_name = 'is_leader') = 0,
  'ALTER TABLE `teacher` ADD COLUMN `is_leader` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否校级领导'' AFTER `duty`',
  'SELECT ''is_leader 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) class_info.head_teacher_id
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'class_info' AND column_name = 'head_teacher_id') = 0,
  'ALTER TABLE `class_info` ADD COLUMN `head_teacher_id` BIGINT NULL COMMENT ''班主任（teacher.id）'' AFTER `grade`',
  'SELECT ''head_teacher_id 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) 索引（按索引名判断）
SET @s := IF((SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = @db AND table_name = 'teacher' AND index_name = 'idx_teacher_leader') = 0,
  'CREATE INDEX `idx_teacher_leader` ON `teacher` (`is_leader`)',
  'SELECT ''idx_teacher_leader 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF((SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = @db AND table_name = 'class_info' AND index_name = 'idx_class_head_teacher') = 0,
  'CREATE INDEX `idx_class_head_teacher` ON `class_info` (`head_teacher_id`)',
  'SELECT ''idx_class_head_teacher 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5) 李世易：教导主任 + 领导
UPDATE `teacher` SET `duty` = '教导主任', `is_leader` = 1 WHERE `real_name` = '李世易';

-- 6) 其余教师默认「教师」
UPDATE `teacher` SET `duty` = '教师' WHERE `duty` IS NULL OR `duty` = '';

-- 7) 初始化班主任：取各班第一门课的授课教师（只填空值，不覆盖已设置）
UPDATE `class_info` ci
JOIN (
  SELECT cc.class_id, MIN(c.teacher_id) AS teacher_id
  FROM course_class cc
  JOIN course c ON c.id = cc.course_id AND c.teacher_id IS NOT NULL
  GROUP BY cc.class_id
) t ON t.class_id = ci.id
SET ci.head_teacher_id = t.teacher_id
WHERE ci.head_teacher_id IS NULL;

-- 8) 被指定为班主任的教师，职务更新（领导保持不变，且只更新仍是「教师」的）
UPDATE `teacher`
SET `duty` = '班主任'
WHERE `is_leader` = 0
  AND `duty` = '教师'
  AND `id` IN (SELECT `head_teacher_id` FROM `class_info` WHERE `head_teacher_id` IS NOT NULL);

-- 9) 结果核对
SELECT '检查结果' AS step;
SELECT COUNT(*) AS 教师总数, SUM(is_leader = 1) AS 领导数, SUM(duty = '班主任') AS 班主任数 FROM `teacher`;
SELECT COUNT(*) AS 班级总数, SUM(head_teacher_id IS NOT NULL) AS 已设班主任 FROM `class_info`;
