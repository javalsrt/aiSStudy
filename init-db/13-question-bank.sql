-- ============================================================
-- 题库表：持久化 AI 生成结果，支持预生成和 Redis 共享缓存
-- ============================================================

CREATE TABLE IF NOT EXISTS question_bank (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cache_key VARCHAR(64) NOT NULL COMMENT '课程+难度+章节等参数指纹',
  course_id BIGINT NULL,
  subject VARCHAR(100) NULL COMMENT '科目/课程名',
  difficulty INT NULL COMMENT '难度档位 1-3',
  question_json MEDIUMTEXT NOT NULL COMMENT '题目列表 JSON',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qbank_cache_key (cache_key),
  KEY idx_qbank_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI出题题库缓存';