-- 27-exam-answer-max-score.sql
-- 历史答卷的 max_score 为空，导致教师无法调分；按题目分值（exam_question.score）回填，可重复执行。
UPDATE exam_submission_answer a
JOIN exam_question q ON q.id = a.question_id
SET a.max_score = q.score
WHERE a.max_score IS NULL AND q.score > 0;
