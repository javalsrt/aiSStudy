-- ============================================================
-- 修正历史学生数据：专业、年级以班级信息为准
-- 适用场景：手动新增学生时专业/年级填错或留空，导致 user 表与 class_info 表不一致
-- ============================================================

UPDATE user u
JOIN class_info ci ON ci.id = u.class_id
SET u.major = ci.major,
    u.grade = ci.grade
WHERE u.role = 1;