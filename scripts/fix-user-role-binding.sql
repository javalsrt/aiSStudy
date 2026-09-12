-- ============================================================
-- 修复存量用户缺少 RBAC 角色绑定的问题
--
-- 现象：批量导入/新增的学生和教师登录后访问章节管理等接口提示「无权访问该资源」
-- 原因：创建用户时未写 sys_user_role 关联，登录时 roles/permissions 为空
-- 映射：user.role(1学生 2教师 3管理员) → sys_role.id(3student 2teacher 1admin)
--       即 sys_role_id = 4 - user.role
--
-- 执行后教师需重新登录（旧 token 的权限列表在登录时生成）。
-- ============================================================

-- 为所有未绑定角色的用户按 user.role 补绑角色
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, 4 - u.role
FROM user u
WHERE u.role IN (1, 2, 3)
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role sur WHERE sur.user_id = u.id
  );

-- 验证：查看已绑定情况
SELECT u.id, u.username, u.real_name, u.role AS user_role, sur.role_id AS sys_role_id
FROM user u LEFT JOIN sys_user_role sur ON sur.user_id = u.id
ORDER BY u.role, u.id;
