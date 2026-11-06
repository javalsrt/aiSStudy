-- ============================================================
-- 教师职务与班主任设置
--   teacher.title  已有，表示「职称」（讲师/副教授…），保持不动
--   teacher.duty   新增，表示「职务」：教师 / 班主任 / 教导主任
--   teacher.is_leader 是否校级领导（可审批超 3 天请假）
--   class_info.head_teacher_id 班主任（teacher.id）
-- ============================================================

ALTER TABLE `teacher`
  ADD COLUMN `duty` VARCHAR(30) NOT NULL DEFAULT '教师' COMMENT '职务：教师/班主任/教导主任/副校长' AFTER `title`,
  ADD COLUMN `is_leader` TINYINT NOT NULL DEFAULT 0 COMMENT '是否校级领导（可终审长假）' AFTER `duty`;

ALTER TABLE `class_info`
  ADD COLUMN `head_teacher_id` BIGINT NULL COMMENT '班主任（teacher.id）' AFTER `grade`;

CREATE INDEX `idx_teacher_leader` ON `teacher` (`is_leader`);
CREATE INDEX `idx_class_head_teacher` ON `class_info` (`head_teacher_id`);

-- 李世易：教导主任（领导）
UPDATE `teacher` SET `duty` = '教导主任', `is_leader` = 1 WHERE `real_name` = '李世易';

-- 其余教师默认为「教师」，后续在管理端指定班主任
UPDATE `teacher` SET `duty` = '教师' WHERE `duty` IS NULL OR `duty` = '';

-- 初始化：把各班「第一门课的授课教师」设为班主任（没有则留空，由管理员在管理端指定）
UPDATE `class_info` ci
JOIN (
  SELECT cc.class_id, MIN(c.teacher_id) AS teacher_id
  FROM course_class cc
  JOIN course c ON c.id = cc.course_id AND c.teacher_id IS NOT NULL
  GROUP BY cc.class_id
) t ON t.class_id = ci.id
SET ci.head_teacher_id = t.teacher_id
WHERE ci.head_teacher_id IS NULL;

-- 被指定为班主任的教师，职务更新为「班主任」（领导保持不变）
UPDATE `teacher` t
SET t.duty = '班主任'
WHERE t.is_leader = 0
  AND t.id IN (SELECT head_teacher_id FROM class_info WHERE head_teacher_id IS NOT NULL);
