-- ============================================================
-- 作息时间支持「按班级」配置
-- 优先级：班级专属(class_id) > 年级(grade) > 默认(class_id/grade 均为空)
-- 用法：mysql -uroot -p znxsglTest < 17-bell-class.sql
-- ============================================================

ALTER TABLE bell_schedule
  ADD COLUMN class_id BIGINT NULL COMMENT '班级ID：空=按年级或默认作息' AFTER grade;

CREATE INDEX idx_bell_class_node ON bell_schedule (class_id, node);
