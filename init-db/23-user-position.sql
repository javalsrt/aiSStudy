-- ============================================================
-- 学生班级职务（班干部）
-- 例：班长、副班长、学习委员、纪律委员、生活委员、文娱委员、心理委员、团支书
-- 管理员可给任意用户设置；教师只能给本班学生设置班干部职务
-- ============================================================

ALTER TABLE `user`
  ADD COLUMN `position` VARCHAR(30) NULL COMMENT '班级职务（班长/学习委员等）' AFTER `grade`;

CREATE INDEX `idx_user_class_position` ON `user` (`class_id`, `position`);
