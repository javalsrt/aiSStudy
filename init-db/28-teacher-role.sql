-- 教师身份/职称/岗位角色关联表（支持一人多身份，例如「高级教师 + 教务处主任 + 班主任」）
-- 执行方式：mysql -u<user> -p <database> < init-db/28-teacher-role.sql
CREATE TABLE IF NOT EXISTS `teacher_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL COMMENT '教师ID（teacher.id）',
  `role_code` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '身份编码，见 TeacherRoleCatalog',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teacher_role` (`teacher_id`,`role_code`),
  KEY `idx_teacher_role_code` (`role_code`),
  CONSTRAINT `fk_teacher_role_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教师身份/职务/职称关联';

-- 兼容旧数据：把已有的职称/职务/领导标记映射成身份（可选执行，重复执行安全）
INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'title_principal_senior' FROM `teacher`
WHERE `title` LIKE '%正高%' OR `title` LIKE '%教授%';

INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'title_senior' FROM `teacher`
WHERE `title` LIKE '%高级%' OR `title` LIKE '%副教授%';

INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'title_first' FROM `teacher`
WHERE `title` LIKE '%一级%' OR `title` LIKE '%讲师%';

INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'title_second' FROM `teacher`
WHERE `title` LIKE '%二级%' OR `title` LIKE '%助教%';

INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'title_third' FROM `teacher`
WHERE `title` LIKE '%三级%';

INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'position_head_teacher' FROM `teacher`
WHERE `duty` = '班主任';

INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'middle_academic_director' FROM `teacher`
WHERE `duty` = '教导主任';

INSERT IGNORE INTO `teacher_role` (`teacher_id`, `role_code`)
SELECT `id`, 'leader_vice_principal' FROM `teacher`
WHERE `duty` = '副校长' OR (`is_leader` = 1 AND `duty` NOT IN ('班主任', '教导主任'));
