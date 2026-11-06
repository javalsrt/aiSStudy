-- ============================================================
-- 学生请假 / 临时外出申请
-- 审批规则：
--   请假 ≤3 天：班主任审批
--   请假 >3 天：班主任初审 → 学生处（上级）终审
--   临时外出（当天往返）：班主任审批
-- 返校要求：定位 + 拍照打卡
-- ============================================================

CREATE TABLE IF NOT EXISTS `leave_request` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT       NOT NULL COMMENT '申请人',
  `class_id`        BIGINT       NULL COMMENT '班级',
  `teacher_id`      BIGINT       NULL COMMENT '班主任用户ID',
  `type`            VARCHAR(16)  NOT NULL DEFAULT 'LEAVE' COMMENT 'LEAVE=请假 OUTING=临时外出',
  `reason`          VARCHAR(500) NOT NULL COMMENT '事由',
  `destination`     VARCHAR(200) NULL COMMENT '去向',
  `start_time`      DATETIME     NOT NULL COMMENT '离校时间',
  `end_time`        DATETIME     NOT NULL COMMENT '预计回校时间',
  `days`            DECIMAL(4,1) NOT NULL DEFAULT 1.0 COMMENT '请假天数',
  `parent_notified` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已告知家长',
  `parent_phone`    VARCHAR(20)  NULL COMMENT '家长手机号',
  `sms_sent`        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已发送短信',
  `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING_TEACHER'
                    COMMENT 'PENDING_TEACHER/PENDING_LEADER/APPROVED/REJECTED/CANCELED/RETURNED',
  `teacher_id_op`   BIGINT       NULL COMMENT '审批班主任',
  `teacher_comment` VARCHAR(300) NULL,
  `teacher_at`      DATETIME     NULL,
  `leader_id_op`    BIGINT       NULL COMMENT '上级审批人',
  `leader_comment`  VARCHAR(300) NULL,
  `leader_at`       DATETIME     NULL,
  `return_photo`    VARCHAR(300) NULL COMMENT '返校打卡照片',
  `return_lat`      DECIMAL(10,6) NULL,
  `return_lng`      DECIMAL(10,6) NULL,
  `return_at`       DATETIME     NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_leave_user` (`user_id`, `created_at`),
  KEY `idx_leave_class` (`class_id`, `status`),
  KEY `idx_leave_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '请假与临时外出申请';
