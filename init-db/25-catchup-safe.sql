-- ============================================================
-- 补列 / 补表：幂等脚本，可重复执行
-- 适用：早期部署的库缺少后续迭代新增的字段与表
-- 用法：
--   mysql -uroot -p --default-character-set=utf8mb4 znxsglTest < 25-catchup-safe.sql
--   docker exec -i znxsgl-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 \
--     znxsglTest < init-db/25-catchup-safe.sql
-- ============================================================

SET @db := DATABASE();

-- ------------------------------------------------------------
-- 1) 请假审批：审批人身份留痕（缺这两列时点击「通过」会 500）
-- ------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'leave_request'
                AND column_name = 'teacher_reviewer_role') = 0,
  'ALTER TABLE `leave_request` ADD COLUMN `teacher_reviewer_role` VARCHAR(30) NULL COMMENT ''初审身份：班主任/教导主任/管理员'' AFTER `teacher_comment`',
  'SELECT ''leave_request.teacher_reviewer_role 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'leave_request'
                AND column_name = 'leader_reviewer_role') = 0,
  'ALTER TABLE `leave_request` ADD COLUMN `leader_reviewer_role` VARCHAR(30) NULL COMMENT ''终审身份：教导主任/副校长/管理员'' AFTER `leader_comment`',
  'SELECT ''leave_request.leader_reviewer_role 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2) 教师职务 / 领导（迁移 22）
-- ------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'teacher' AND column_name = 'duty') = 0,
  'ALTER TABLE `teacher` ADD COLUMN `duty` VARCHAR(30) NOT NULL DEFAULT ''教师'' COMMENT ''职务：教师/班主任/教导主任/副校长''',
  'SELECT ''teacher.duty 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'teacher' AND column_name = 'is_leader') = 0,
  'ALTER TABLE `teacher` ADD COLUMN `is_leader` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否校级领导''',
  'SELECT ''teacher.is_leader 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'class_info' AND column_name = 'head_teacher_id') = 0,
  'ALTER TABLE `class_info` ADD COLUMN `head_teacher_id` BIGINT NULL COMMENT ''班主任（teacher.id）''',
  'SELECT ''class_info.head_teacher_id 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3) 学生班干部职务（迁移 23）
-- ------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = @db AND table_name = 'user' AND column_name = 'position') = 0,
  'ALTER TABLE `user` ADD COLUMN `position` VARCHAR(30) NULL COMMENT ''班级职务（班长/学习委员等）''',
  'SELECT ''user.position 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF((SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = @db AND table_name = 'user' AND index_name = 'idx_user_class_position') = 0,
  'CREATE INDEX `idx_user_class_position` ON `user` (`class_id`, `position`)',
  'SELECT ''idx_user_class_position 已存在，跳过''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4) 调整记录表（迁移 24 / 25）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `class_head_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `operator_id`   BIGINT       NULL,
  `operator_name` VARCHAR(64)  NULL,
  `class_id`      BIGINT       NOT NULL,
  `class_name`    VARCHAR(100) NULL,
  `action`        VARCHAR(16)  NOT NULL COMMENT 'SET=设置 CLEAR=取消',
  `old_teacher`   VARCHAR(64)  NULL COMMENT '原班主任',
  `new_teacher`   VARCHAR(64)  NULL COMMENT '新班主任',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_head_log_time` (`created_at`),
  KEY `idx_head_log_class` (`class_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '班主任调整记录';

CREATE TABLE IF NOT EXISTS `student_position_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `operator_id`   BIGINT       NULL,
  `operator_name` VARCHAR(64)  NULL,
  `student_id`    BIGINT       NOT NULL,
  `student_name`  VARCHAR(64)  NULL,
  `class_id`      BIGINT       NULL,
  `class_name`    VARCHAR(100) NULL,
  `old_position`  VARCHAR(30)  NULL,
  `new_position`  VARCHAR(30)  NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pos_log_time` (`created_at`),
  KEY `idx_pos_log_student` (`student_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '学生班干部调整记录';

-- ------------------------------------------------------------
-- 5) 结果核对
-- ------------------------------------------------------------
SELECT '检查结果' AS step;
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'leave_request'
       AND column_name IN ('teacher_reviewer_role','leader_reviewer_role')) AS 请假审批身份列应有2个,
  (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'user' AND column_name = 'position') AS 学生职务列,
  (SELECT COUNT(*) FROM class_info WHERE head_teacher_id IS NOT NULL) AS 已设班主任班级数,
  (SELECT COUNT(*) FROM teacher WHERE is_leader = 1) AS 校级领导人数;
