-- 管理端 AI 助手操作审计表
-- 执行方式：mysql -u root -p znxsglTest < init-db/ai_agent_log.sql
CREATE TABLE IF NOT EXISTS `ai_agent_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) DEFAULT NULL COMMENT '单次请求 ID',
  `user_id` bigint NOT NULL COMMENT '操作人 user.id',
  `user_role` varchar(16) NOT NULL COMMENT 'ADMIN / TEACHER',
  `session_id` varchar(64) DEFAULT NULL COMMENT '会话 ID',
  `mode` varchar(16) NOT NULL COMMENT 'agent / chat',
  `user_message` text COMMENT '用户原始指令',
  `action_name` varchar(64) DEFAULT NULL COMMENT '执行的工具名',
  `action_args` json DEFAULT NULL COMMENT '工具参数（密码类已脱敏）',
  `result_status` varchar(16) DEFAULT NULL COMMENT 'success / failed / pending_confirm / canceled',
  `result_summary` varchar(500) DEFAULT NULL COMMENT '执行结果摘要',
  `duration_ms` int DEFAULT NULL COMMENT '耗时',
  `error_code` varchar(64) DEFAULT NULL COMMENT '错误码',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_log_user_time` (`user_id`, `created_at`),
  KEY `idx_ai_log_action` (`action_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理端 AI 助手操作审计';
