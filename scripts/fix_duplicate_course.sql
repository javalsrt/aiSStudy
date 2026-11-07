-- =====================================================================
-- 重复课程诊断与合并脚本（在「服务器数据库」上执行）
--
-- 现象：同一班级出现两门同名课程（如「数学」分别挂不同教师），
--       学生端课程列表/课表会显示成重复课程。
-- 根因：历史导入逻辑按「课程名+教师」建课，AI 两次识别出不同教师名时，
--       会给同一个班生成第二门同名课程。（代码已修复，此脚本用于清理存量脏数据）
--
-- 使用前提：
--   1. 先在服务器库确认诊断结果（第 1 步只读，安全）；
--   2. 执行第 2 步前务必备份数据库（mysqldump）。
-- =====================================================================


-- =====================================================================
-- 第 1 步：诊断（只读，不修改数据）
-- =====================================================================

-- 1.1 找出「同一班级 + 同课程名 + 多门课程 id」的数据
SELECT cc.class_id,
       ci.class_name,
       c.course_name,
       COUNT(DISTINCT c.id)          AS course_cnt,
       GROUP_CONCAT(DISTINCT c.id ORDER BY c.id)               AS course_ids,
       GROUP_CONCAT(DISTINCT t.real_name ORDER BY t.real_name) AS teachers
FROM course c
JOIN course_class cc ON cc.course_id = c.id
LEFT JOIN class_info ci ON ci.id = cc.class_id
LEFT JOIN teacher t ON t.id = c.teacher_id
GROUP BY cc.class_id, ci.class_name, c.course_name
HAVING course_cnt > 1
ORDER BY ci.class_name, c.course_name;

-- 1.2 对诊断出的重复课程，查看各自在该班的排课记录数（建议保留记录多的那门）
--   （把 '数学' 和 班级ID 换成 1.1 里查出的值）
-- SELECT c.id AS course_id, t.real_name AS teacher,
--        COUNT(s.id) AS schedule_rows
-- FROM course c
-- JOIN course_class cc ON cc.course_id = c.id AND cc.class_id = <班级ID>
-- LEFT JOIN teacher t ON t.id = c.teacher_id
-- LEFT JOIN schedule s ON s.course_id = c.id
--        AND s.user_id IN (SELECT id FROM user WHERE class_id = <班级ID>)
-- WHERE c.course_name = '数学'
-- GROUP BY c.id, t.real_name;

-- 1.3 查看待删除课程在关键表的引用量（确认合并影响范围）
--   （把 321 换成要删除的重复课程 id）
-- SELECT 'schedule' AS tbl, COUNT(*) FROM schedule WHERE course_id = 321
-- UNION ALL SELECT 'chat_message', COUNT(*) FROM chat_message WHERE course_id = 321
-- UNION ALL SELECT 'course_chapter', COUNT(*) FROM course_chapter WHERE course_id = 321
-- UNION ALL SELECT 'quiz_session', COUNT(*) FROM quiz_session WHERE course_id = 321
-- UNION ALL SELECT 'exam_homework', COUNT(*) FROM exam_homework WHERE course_id = 321;


-- =====================================================================
-- 第 2 步：合并（对每一对重复课程执行一次 CALL，先备份再执行！）
-- =====================================================================

DROP PROCEDURE IF EXISTS merge_course_dup;
DELIMITER //
CREATE PROCEDURE merge_course_dup(IN keep_id BIGINT, IN dup_id BIGINT)
BEGIN
    -- 防御：keep 与 dup 不同且都存在
    IF keep_id = dup_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'keep_id 与 dup_id 相同，无需合并';
    END IF;

    -- ---- 2.1 直接改挂 course_id 的表 ----
    UPDATE schedule               SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE teaching_task          SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE question_bank          SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE quiz_session           SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE exam_homework          SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE checkin_task           SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE learning_plan          SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE chapter_read_progress  SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE document               SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE chat_message           SET course_id = keep_id WHERE course_id = dup_id;
    UPDATE course_chapter         SET course_id = keep_id WHERE course_id = dup_id;

    -- ---- 2.2 带 (user_id, course_id) 唯一键的表：借助临时表避免列名依赖与唯一键冲突 ----
    CREATE TEMPORARY TABLE tmp_dup_chat_read AS
        SELECT * FROM chat_read_state WHERE course_id = dup_id;
    UPDATE tmp_dup_chat_read SET course_id = keep_id;
    INSERT IGNORE INTO chat_read_state SELECT * FROM tmp_dup_chat_read;
    DROP TEMPORARY TABLE tmp_dup_chat_read;
    DELETE FROM chat_read_state WHERE course_id = dup_id;

    CREATE TEMPORARY TABLE tmp_dup_difficulty AS
        SELECT * FROM user_course_difficulty WHERE course_id = dup_id;
    UPDATE tmp_dup_difficulty SET course_id = keep_id;
    INSERT IGNORE INTO user_course_difficulty SELECT * FROM tmp_dup_difficulty;
    DROP TEMPORARY TABLE tmp_dup_difficulty;
    DELETE FROM user_course_difficulty WHERE course_id = dup_id;

    -- ---- 2.3 课程-班级关联：先补绑保留课程，再删除重复关联 ----
    INSERT IGNORE INTO course_class (course_id, class_id, semester)
        SELECT keep_id, class_id, semester FROM course_class WHERE course_id = dup_id;
    DELETE FROM course_class WHERE course_id = dup_id;

    -- ---- 2.4 重复课程已无任何班级使用时，删除课程记录 ----
    DELETE FROM course
    WHERE id = dup_id
      AND NOT EXISTS (SELECT 1 FROM course_class cc WHERE cc.course_id = dup_id);

    SELECT CONCAT('合并完成：保留课程 ', keep_id, '，已处理重复课程 ', dup_id) AS result;
END //
DELIMITER ;

-- 示例：把重复的「数学」合并到保留课程
--   1) 先跑第 1.2 步确认哪门课排课记录多 → 假设保留 359，删除 321
--   2) CALL merge_course_dup(359, 321);
--   3) 合并完成后复查第 1.1 步，确认不再出现重复行
