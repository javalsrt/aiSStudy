-- ============================================================
-- 作息时间表配置化（自动适配单双周）
-- bell_schedule    ：节次 → 起止时间（支持按 年级/周次奇偶 配置多套）
-- grade_week_parity：年级 → 奇数周套用哪套作息（1=单周表 2=双周表）
--
-- 解析优先级（BellTimeService）：
--   (年级,奇偶) > (年级,通用0) > (默认'',奇偶) > (默认'',通用0)
-- 无配置时自动回退到代码内置的旧默认表，不影响现有功能。
-- ============================================================

CREATE TABLE IF NOT EXISTS bell_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  semester VARCHAR(20) NOT NULL DEFAULT '' COMMENT '学期，空=通用',
  grade VARCHAR(20) NOT NULL DEFAULT '' COMMENT '年级（如 2026级），空=默认作息',
  week_parity TINYINT NOT NULL DEFAULT 0 COMMENT '0=通用(不分单双周) 1=单周(奇数周) 2=双周(偶数周)',
  node INT NOT NULL COMMENT '节次，从 1 开始',
  start_time TIME NOT NULL COMMENT '上课时间',
  end_time TIME NOT NULL COMMENT '下课时间',
  UNIQUE KEY uk_bell (semester, grade, week_parity, node)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作息时间表（节次起止时间）';

CREATE TABLE IF NOT EXISTS grade_week_parity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  grade VARCHAR(20) NOT NULL UNIQUE COMMENT '年级，如 2026级',
  odd_week_parity TINYINT NOT NULL DEFAULT 1 COMMENT '奇数周套用的作息：1=单周表 2=双周表',
  remark VARCHAR(100) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级单双周作息映射';

-- ---------- 默认作息（原代码内置：艺术学部/汽车学部，8节制） ----------
INSERT IGNORE INTO bell_schedule (semester, grade, week_parity, node, start_time, end_time) VALUES
('', '', 0, 1, '08:10', '08:50'),
('', '', 0, 2, '09:00', '09:40'),
('', '', 0, 3, '09:50', '10:30'),
('', '', 0, 4, '10:40', '11:20'),
('', '', 0, 5, '15:10', '15:50'),
('', '', 0, 6, '16:00', '16:40'),
('', '', 0, 7, '19:50', '20:10'),
('', '', 0, 8, '20:20', '21:00');

-- ---------- 单周作息（2026级和2024级单周：上午4节+下午3节） ----------
INSERT IGNORE INTO bell_schedule (semester, grade, week_parity, node, start_time, end_time) VALUES
('', '', 1, 1, '08:40', '09:20'),
('', '', 1, 2, '09:30', '10:10'),
('', '', 1, 3, '10:20', '11:00'),
('', '', 1, 4, '11:10', '11:50'),
('', '', 1, 5, '14:20', '15:00'),
('', '', 1, 6, '15:10', '15:50'),
('', '', 1, 7, '16:00', '16:30');

-- ---------- 双周作息（2026级和2024级双周：大课间提前，上午推迟20分钟） ----------
INSERT IGNORE INTO bell_schedule (semester, grade, week_parity, node, start_time, end_time) VALUES
('', '', 2, 1, '09:00', '09:40'),
('', '', 2, 2, '09:50', '10:30'),
('', '', 2, 3, '10:40', '11:20'),
('', '', 2, 4, '11:30', '12:10'),
('', '', 2, 5, '14:20', '15:00'),
('', '', 2, 6, '15:10', '15:50'),
('', '', 2, 7, '16:00', '16:30');

-- ---------- 年级 → 奇数周作息映射（按学校规则：2026级和2024级单周、2025级双周） ----------
INSERT IGNORE INTO grade_week_parity (grade, odd_week_parity, remark) VALUES
('2026级', 1, '2026级：奇数周=单周作息，偶数周=双周作息'),
('2025级', 2, '2025级：奇数周=双周作息，偶数周=单周作息'),
('2024级', 1, '2024级：奇数周=单周作息，偶数周=双周作息');
