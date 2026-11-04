-- ============================================================
-- 学生聊天已读状态表
-- 背景：chat_message.is_read 是「整行共享」的单值字段，
--       教师群发消息只有一行（user_id=教师），无法表达"哪些学生读过"，
--       导致学生端永远统计不到未读、也不会有红点。
-- 方案：按 (user_id, course_id) 记录最后已读时间，未读 = 该时间之后的消息数。
-- 用法：mysql -uroot -p znxsglTest < 16-chat-read-state.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `chat_read_state` (
  `user_id`      BIGINT   NOT NULL COMMENT '学生用户ID',
  `course_id`    BIGINT   NOT NULL COMMENT '课程ID（聊天室）',
  -- 用消息ID做已读水位线：created_at 只有秒级精度，同一秒内的新消息会被误判为已读
  `last_read_id` BIGINT   NOT NULL DEFAULT 0 COMMENT '已读到的最新消息ID（0=未建立基线）',
  `last_read_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后已读时间',
  PRIMARY KEY (`user_id`, `course_id`),
  KEY `idx_read_course` (`course_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '学生课程聊天已读状态';

-- 已存在旧表时补列并回填为「当前最新消息」，避免升级后历史消息全部变成未读
-- ALTER TABLE chat_read_state ADD COLUMN last_read_id BIGINT NOT NULL DEFAULT 0 AFTER course_id;
-- UPDATE chat_read_state rs SET last_read_id =
--   (SELECT COALESCE(MAX(cm.id), 0) FROM chat_message cm WHERE cm.course_id = rs.course_id);
