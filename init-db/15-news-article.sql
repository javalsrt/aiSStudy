-- ============================================================
-- 职教政策资讯表（App 资讯页 / NewsController / NewsFetchService）
-- 说明：只存标题、来源、分类与官方原文链接，不存正文
-- 用法：mysql -uroot -p znxsglTest < 15-news-article.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `news_article` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`        VARCHAR(300) NOT NULL COMMENT '标题',
  `source`       VARCHAR(200) NULL DEFAULT '' COMMENT '来源站点',
  `category`     VARCHAR(50)  NULL DEFAULT '' COMMENT '分类：职教动态/政策文件/教育要闻',
  `source_url`   VARCHAR(600) NULL DEFAULT '' COMMENT '官方原文链接',
  `url_md5`      CHAR(32)     NOT NULL COMMENT '原文链接 MD5，唯一键用于去重',
  `published_at` DATE         NULL DEFAULT NULL COMMENT '发布日期',
  `is_top`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否置顶：0否 1是',
  `status`       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态：1上架 0下架',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_news_url_md5` (`url_md5`),
  KEY `idx_news_status_time` (`status`, `is_top`, `published_at`),
  KEY `idx_news_category` (`category`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '职教政策资讯';

-- 校验
SELECT COUNT(*) AS total FROM news_article;
