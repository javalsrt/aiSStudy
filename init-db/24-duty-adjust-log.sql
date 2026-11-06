-- ============================================================
-- 班主任 / 学生班干部 调整记录
-- ============================================================

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
