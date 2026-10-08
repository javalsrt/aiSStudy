-- ============================================================
-- 生产加固迁移：唯一约束、热点查询索引
-- 适用：MySQL 8.x，幂等可重复执行（索引不存在才创建）
-- 执行：mysql -uroot -p123456 znxsglTest < 11-production-hardening.sql
-- ============================================================

-- 9. 异步任务表（AI 出题、课表导入预览等长任务的前置基础设施）
CREATE TABLE IF NOT EXISTS async_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  biz_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  request_json MEDIUMTEXT NULL,
  result_json MEDIUMTEXT NULL,
  error_msg VARCHAR(1000) NULL,
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_async_task_status_created (status, created_at),
  KEY idx_async_task_user_created (created_by, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS add_index_if_missing;
DELIMITER //
CREATE PROCEDURE add_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = p_table
      AND index_name = p_index
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

-- 1. 清理历史重复签到，保证唯一索引可以创建
DELETE t1 FROM check_in_record t1
JOIN check_in_record t2
  ON t1.check_in_id = t2.check_in_id
 AND t1.student_id = t2.student_id
 AND t1.id > t2.id;

-- 2. 签到唯一约束：并发签到原子幂等
CALL add_index_if_missing('check_in_record', 'uk_checkin_student',
     'UNIQUE KEY `uk_checkin_student` (`check_in_id`, `student_id`)');

-- 3. 课表高频查询索引
CALL add_index_if_missing('schedule', 'idx_schedule_user_semester_status',
     'INDEX `idx_schedule_user_semester_status` (`user_id`, `semester`, `status`)');
CALL add_index_if_missing('schedule', 'idx_schedule_course_time',
     'INDEX `idx_schedule_course_time` (`course_id`, `day_of_week`, `start_node`, `semester`)');

-- 4. 聊天未读索引（course_name+created_at 已有 idx_course_time，不重复创建）
CALL add_index_if_missing('chat_message', 'idx_chat_user_read',
     'INDEX `idx_chat_user_read` (`user_id`, `is_read`)');

-- 5. 专注时长/排行索引
CALL add_index_if_missing('focus_session', 'idx_focus_user_finished',
     'INDEX `idx_focus_user_finished` (`user_id`, `finished_at`)');

-- 6. 答题索引（quiz_session 已有 idx_user(user_id, created_at)，不重复创建）
CALL add_index_if_missing('quiz_answer', 'idx_quiz_answer_session_correct',
     'INDEX `idx_quiz_answer_session_correct` (`session_id`, `is_correct`)');

-- 7. exam_submission 已有 uk_exam_user(exam_homework_id, user_id)，无需重复创建。

-- 8. 用户/班级查询索引
CALL add_index_if_missing('user', 'idx_user_class_role',
     'INDEX `idx_user_class_role` (`class_id`, `role`)');

DROP PROCEDURE IF EXISTS add_index_if_missing;