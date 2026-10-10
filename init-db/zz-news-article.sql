-- ============================================================
-- 资讯（职教政策）表
--
-- 用途：App 底部「资讯」tab。内容由后端定时抓取官方源（教育部等）自动维护，
--       只存「标题 + 来源 + 官方原文链接」，不转载正文，App 点击直接外链跳转原文，
--       以规避互联网新闻信息服务资质与版权风险。
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行
-- 执行：mysql -uroot -p123456 znxsglTest < zz-news-article.sql
--      容器部署：docker exec -i znxsgl-mysql mysql -uroot -p123456 znxsglTest < init-db/zz-news-article.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `news_article` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(300) NOT NULL COMMENT '标题',
  `source`       VARCHAR(100) NOT NULL COMMENT '来源站点（如 教育部·政策文件）',
  `category`     VARCHAR(50)  NOT NULL DEFAULT '职教政策' COMMENT '分类：职教动态/政策文件/教育要闻',
  `source_url`   VARCHAR(800) NOT NULL COMMENT '官方原文链接（App 外链跳转）',
  `url_md5`      CHAR(32)     NOT NULL COMMENT '链接指纹，用于去重',
  `published_at` DATE         NULL COMMENT '发布日期',
  `is_top`       TINYINT      NOT NULL DEFAULT 0 COMMENT '1置顶 0普通',
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1显示 0隐藏',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_news_url_md5` (`url_md5`),
  KEY `idx_news_list` (`status`, `is_top`, `published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='职教政策资讯（标题+官方外链，不转载正文）';
