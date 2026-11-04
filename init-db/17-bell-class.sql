-- ============================================================
-- 作息时间支持「按班级」配置
-- 优先级：班级专属(class_id) > 年级(grade) > 默认(class_id/grade 均为空)
-- 用法：mysql -uroot -p znxsglTest < 17-bell-class.sql
-- ============================================================

ALTER TABLE bell_schedule
  ADD COLUMN class_id BIGINT NULL COMMENT '班级ID：空=按年级或默认作息' AFTER grade;

CREATE INDEX idx_bell_class_node ON bell_schedule (class_id, node);

-- 原唯一索引 uk_bell(semester, grade, week_parity, node) 不含 class_id，
-- 会导致同年级不同班级的同一节互相冲突；改为普通索引，唯一性由保存逻辑（先删后插）保证
ALTER TABLE bell_schedule DROP INDEX uk_bell;
CREATE INDEX idx_bell_scope ON bell_schedule (semester, class_id, week_parity, node);
