-- ============================================================
-- 作息变更记录：谁在什么时候改了哪些班级的作息、同步了多少条课表
-- 用法：mysql -uroot -p znxsglTest < 18-bell-change-log.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `bell_change_log` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `operator_id`      BIGINT       NULL COMMENT '操作人用户ID',
  `operator_name`    VARCHAR(64)  NULL COMMENT '操作人姓名',
  `scope_type`       VARCHAR(16)  NOT NULL DEFAULT 'CLASS' COMMENT 'DEFAULT=默认作息 CLASS=班级',
  `class_names`      VARCHAR(1000) NULL COMMENT '涉及的班级名称（逗号分隔，最多前若干个）',
  `class_count`      INT          NOT NULL DEFAULT 0 COMMENT '涉及班级数（默认作息记 0）',
  `node_count`       INT          NOT NULL DEFAULT 0 COMMENT '保存的节次数量',
  `synced_schedules` INT          NOT NULL DEFAULT 0 COMMENT '同步的课表记录数',
  `semester`         VARCHAR(20)  NULL COMMENT '学期',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_bell_log_time` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '作息变更记录';
