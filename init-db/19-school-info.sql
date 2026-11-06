-- ============================================================
-- 校园简介 / 规章制度知识库（AI 助手的底层知识来源）
-- 学生提问「宿舍几点熄灯」「请假流程」等校内问题优先检索本表
-- 用法：mysql -uroot -p znxsglTest < 19-school-info.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `school_info` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `category`   VARCHAR(50)  NOT NULL DEFAULT '校园概况' COMMENT '分类：校园概况/规章制度/教学管理/生活服务/奖助政策/就业升学',
  `title`      VARCHAR(200) NOT NULL COMMENT '条目标题，如「学生请假管理办法」',
  `content`    TEXT         NOT NULL COMMENT '正文（纯文本，支持换行）',
  `keywords`   VARCHAR(300) NULL COMMENT '检索关键词，逗号分隔，提高命中率',
  `sort`       INT          NOT NULL DEFAULT 0 COMMENT '排序，越小越前',
  `status`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  `updated_by` VARCHAR(64)  NULL COMMENT '最后修改人',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_school_info_cat` (`category`, `status`, `sort`),
  KEY `idx_school_info_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '校园信息与规章制度知识库';

-- 初始化几条示例（可按学校实际情况修改/删除）
INSERT INTO `school_info` (`category`, `title`, `content`, `keywords`, `sort`)
SELECT * FROM (
  SELECT '校园概况' AS c, '学校简介' AS t,
         '广西南宁市武鸣区第三职业技术学校，创建于1985年，是国家级重点中等职业学校。学校设有信息技术、机电、汽车、艺术设计、财经商贸等学部，开设有计算机应用、机电技术应用、汽车运用与维修、艺术设计与制作等专业。' AS ct,
         '学校,简介,历史,专业,学部' AS k, 1 AS s
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM school_info WHERE title = '学校简介');

INSERT INTO `school_info` (`category`, `title`, `content`, `keywords`, `sort`)
SELECT * FROM (
  SELECT '规章制度' AS c, '学生请假管理办法' AS t,
         '学生请假须提前向班主任提交书面申请，说明事由与起止时间。病假需附校医或医院证明；事假须家长电话确认。请假 1 天以内由班主任审批，1-3 天由学部审批，3 天以上报学生处备案。返校后须及时销假，未办理请假手续缺勤按旷课处理。' AS ct,
         '请假,病假,事假,销假,旷课,审批' AS k, 0 AS s
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM school_info WHERE title = '学生请假管理办法');

INSERT INTO `school_info` (`category`, `title`, `content`, `keywords`, `sort`)
SELECT * FROM (
  SELECT '生活服务' AS c, '作息时间与宿舍管理' AS t,
         '学生宿舍 22:30 熄灯就寝，早上 6:40 起床铃。上课期间不得滞留宿舍；晚归需在宿管处登记。宿舍禁止使用大功率电器（电吹风、电热锅等），违者按校规处理。' AS ct,
         '宿舍,熄灯,作息,晚归,电器,大功率' AS k, 2 AS s
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM school_info WHERE title = '作息时间与宿舍管理');
