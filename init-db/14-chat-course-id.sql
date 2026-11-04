-- ============================================================
-- 聊天室房间键迁移：course_name -> course_id
-- 背景：同名课程可能属于不同教师（库中存在多组同名课程），
--       按课程名做房间键会导致聊天记录、@名单、未读数串号。
-- 影响接口：/api/chat/teacher/unread、/api/chat/by-course/{id} 等
-- 用法：mysql -uroot -p znxsglTest < 14-chat-course-id.sql
-- ============================================================

-- 1. 新增 course_id 列（可空，兼容历史消息）
ALTER TABLE chat_message
  ADD COLUMN course_id BIGINT NULL COMMENT '课程ID（聊天室唯一键）' AFTER id;

-- 2. 索引：按课程查消息 / 未读统计
-- 注意：旧索引 idx_chat_course_created / idx_chat_user_read 建在 course_name 上，名称不能复用
CREATE INDEX idx_chat_courseid_time ON chat_message (course_id, created_at);
CREATE INDEX idx_chat_courseid_user_read ON chat_message (course_id, user_id, is_read);

-- 3. 回填历史消息：仅当课程名唯一对应一门课时回填，避免同名课程串号
UPDATE chat_message cm
JOIN (
  SELECT course_name, MIN(id) AS cid
  FROM course
  GROUP BY course_name
  HAVING COUNT(*) = 1
) c ON c.course_name = cm.course_name
SET cm.course_id = c.cid
WHERE cm.course_id IS NULL;

-- 4. 校验
SELECT COUNT(*) AS total,
       SUM(course_id IS NULL) AS missing_course_id
FROM chat_message;
