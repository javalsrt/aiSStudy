-- ============================================================
-- 聊天室主键迁移：course_name（非唯一）→ course_id
--
-- 背景：不同教师可以创建同名课程（库里存在 4 门「英语」/「数学」/「语文」等，
--       共 18 组同名、涉及 45 门课程）。而聊天室以「课程名」为键，
--       导致以下串号问题：
--         · @ 候选名单 = 所有同名课程班级的并集（英语课曾列出全校 1085 人）
--         · 群聊记录跨教师互相可见（隐私泄漏）
--         · 未读数、排课通知、私发 @ 消息全部串号
--       本迁移为 chat_message 增加 course_id 作为聊天室唯一键，并回填历史数据。
--
-- 适用：MySQL 8.x；幂等（列/索引不存在才创建，回填只处理 course_id IS NULL 的行）
-- 执行：mysql -uroot -p123456 znxsglTest < zz-chat-course-id.sql
--      容器部署：docker exec -i znxsgl-mysql mysql -uroot -p123456 znxsglTest < init-db/zz-chat-course-id.sql
-- ============================================================

-- ---------- 1. 加列 ----------
DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_missing('chat_message', 'course_id',
  'COLUMN `course_id` BIGINT NULL COMMENT ''课程ID（聊天室唯一键；历史数据可能为NULL）'' AFTER `course_name`');

-- ---------- 2. 加索引 ----------
DROP PROCEDURE IF EXISTS add_index_if_missing;
DELIMITER //
CREATE PROCEDURE add_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_index_if_missing('chat_message', 'idx_chat_course_id',
  'INDEX idx_chat_course_id (course_id)');
CALL add_index_if_missing('chat_message', 'idx_chat_course_read',
  'INDEX idx_chat_course_read (course_id, is_read)');

-- ---------- 3. 历史数据回填（三段定位，覆盖全量）----------
-- 3.1 学生侧消息（含发给学生的教师通知/AI 回复）：发送者所在班级 → 课程-班级关联
UPDATE chat_message cm
JOIN user u        ON u.id = cm.user_id AND u.class_id IS NOT NULL
JOIN course c      ON c.course_name = cm.course_name
JOIN course_class cc ON cc.course_id = c.id AND cc.class_id = u.class_id
SET cm.course_id = c.id
WHERE cm.course_id IS NULL;

-- 3.2 教师侧消息：发送者 user → teacher.real_name → 该教师名下的同名课程
UPDATE chat_message cm
JOIN user u     ON u.id = cm.user_id AND u.role = 2
JOIN teacher t  ON t.real_name = u.real_name
JOIN course c   ON c.course_name = cm.course_name AND c.teacher_id = t.id
SET cm.course_id = c.id
WHERE cm.course_id IS NULL;

-- 3.3 兜底：同名课程取最早一门，保证历史消息不因缺少归属而消失
UPDATE chat_message cm
JOIN (SELECT course_name, MIN(id) AS id FROM course GROUP BY course_name) x
  ON x.course_name = cm.course_name
SET cm.course_id = x.id
WHERE cm.course_id IS NULL;

-- ---------- 4. 清理临时存储过程 + 自检 ----------
DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;

SELECT COUNT(*) AS totalMessages,
       SUM(course_id IS NULL) AS unresolvedMessages
FROM chat_message;
