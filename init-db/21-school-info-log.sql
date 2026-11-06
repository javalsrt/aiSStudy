-- ============================================================
-- 校园信息（校规/制度）变更记录
-- 记录谁在什么时候新增/修改/删除/导入了哪条规定
-- 用法：mysql -uroot -p znxsglTest < 21-school-info-log.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `school_info_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `operator_id`   BIGINT       NULL COMMENT '操作人用户ID',
  `operator_name` VARCHAR(64)  NULL COMMENT '操作人姓名',
  `action`        VARCHAR(20)  NOT NULL COMMENT 'CREATE/UPDATE/DELETE/IMPORT/STATUS',
  `item_id`       BIGINT       NULL COMMENT '条目ID',
  `title`         VARCHAR(200) NULL COMMENT '条目标题',
  `category`      VARCHAR(50)  NULL COMMENT '分类',
  `detail`        VARCHAR(300) NULL COMMENT '变更摘要',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_school_log_time` (`created_at`),
  KEY `idx_school_log_item` (`item_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '校园信息变更记录';
