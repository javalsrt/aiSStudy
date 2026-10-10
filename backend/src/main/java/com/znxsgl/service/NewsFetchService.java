package com.znxsgl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 职教政策资讯自动抓取。
 * <p>
 * 合规设计：只抓取官方站点的「标题 + 原文链接 + 发布日期」，<b>不抓正文</b>，
 * App 端点击直接外链跳转官方原文，不做全文转载、不做二次分发。
 * <p>
 * 解析兼容两种列表排布（实测教育部站点存在两种）：
 * {@code <li><span>日期</span><a href title>} 与 {@code <li><a href title><span>日期</span>}。
 */
@Service
public class NewsFetchService {

    private static final Logger log = LoggerFactory.getLogger(NewsFetchService.class);

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final Pattern LI = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern A_HREF_FIRST =
            Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*title=\"([^\"]+)\"");
    private static final Pattern A_TITLE_FIRST =
            Pattern.compile("<a[^>]*title=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern META_CHARSET =
            Pattern.compile("<meta[^>]*charset=[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** 职教相关关键词：用于在「全站教育列表」里筛出职教内容 */
    private static final String[] KEYWORDS = {
            "职业", "职教", "技能", "高职", "中职", "技工", "产教", "实训", "实习",
            "就业", "专业目录", "双高", "学徒", "院校", "大赛", "师资", "工匠"
    };

    /** 抓取源配置 */
    private record Source(String name, String url, String category, boolean keywordFilter) {
    }

    private static final Source[] SOURCES = {
            // 职成司：本身就是职教口，无需关键词过滤
            new Source("教育部·职业教育与成人教育司", "http://www.moe.gov.cn/s78/A07/A07_sjhj/", "职教动态", false),
            new Source("教育部·职业教育与成人教育司", "http://www.moe.gov.cn/s78/A07/", "职教动态", false),
            // 全站列表：按职教关键词过滤，避免混入非职教内容
            new Source("教育部·政策文件", "http://www.moe.gov.cn/jyb_xxgk/moe_1777/moe_1778/", "政策文件", true),
            new Source("教育部·要闻", "http://www.moe.gov.cn/jyb_xwfb/s5147/", "教育要闻", true),
            new Source("教育部·工作动态", "http://www.moe.gov.cn/jyb_xwfb/gzdt_gzdt/", "教育要闻", true),
            // 说明：广西教育厅「教育工作动态」栏目多为 IPv6 转码外链（不稳定），暂不接入
    };

    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public NewsFetchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 每 6 小时抓一次；启动 60 秒后先跑一次 */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 60_000L)
    public void scheduledRefresh() {
        refresh();
    }

    /** 应用启动后：表为空时立即抓一次，避免首屏空白 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM news_article", Integer.class);
            if (count != null && count == 0) {
                log.info("[News] 资讯表为空，启动后首次抓取");
                refresh();
            }
        } catch (Exception e) {
            log.warn("[News] 启动预抓取跳过（表可能尚未创建）: {}", e.getMessage());
        }
    }

    /** 抓取全部源并入库，返回新增条数 */
    public int refresh() {
        int inserted = 0;
        for (Source s : SOURCES) {
            try {
                inserted += fetchSource(s);
            } catch (Exception e) {
                log.warn("[News] 抓取失败: {} ({}) - {}", s.name(), s.url(), e.getMessage());
            }
        }
        if (inserted > 0) {
            log.info("[News] 本次新增 {} 条职教政策", inserted);
        }
        return inserted;
    }

    private int fetchSource(Source source) throws Exception {
        String html = fetchHtml(source.url());
        if (html == null || html.isEmpty()) return 0;

        List<Object[]> rows = new ArrayList<>();
        Matcher li = LI.matcher(html);
        while (li.find()) {
            String block = li.group(1);
            if (block.indexOf('<') < 0) continue;

            Matcher a = A_HREF_FIRST.matcher(block);
            String href, title;
            if (a.find()) {
                href = a.group(1);
                title = a.group(2);
            } else {
                Matcher a2 = A_TITLE_FIRST.matcher(block);
                if (!a2.find()) continue;
                title = a2.group(1);
                href = a2.group(2);
            }

            Matcher d = DATE.matcher(block);
            if (!d.find()) continue;                       // 列表项必须带日期，过滤导航类链接
            String date = d.group(1) + "-" + d.group(2) + "-" + d.group(3);

            if (href.startsWith("#") || href.startsWith("javascript")) continue;
            if (!href.contains(".html") && !href.contains(".htm")) continue;

            title = unescape(title).trim();
            if (title.length() < 6) continue;              // 过滤过短的导航文字
            if (source.keywordFilter() && !containsKeyword(title)) continue;

            String url = absoluteUrl(source.url(), href);
            if (url == null) continue;

            rows.add(new Object[]{
                    cut(title, 300), source.name(), source.category(),
                    cut(url, 800), md5(url), date
            });
        }

        int inserted = 0;
        for (Object[] r : rows) {
            try {
                inserted += jdbc.update(
                        "INSERT IGNORE INTO news_article " +
                                "(title, source, category, source_url, url_md5, published_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?)", r);
            } catch (Exception e) {
                log.debug("[News] 单条入库失败: {}", e.getMessage());
            }
        }
        log.info("[News] {} <- 解析 {} 条，入库 {} 条", source.name(), rows.size(), inserted);
        return inserted;
    }

    private String fetchHtml(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200 || resp.body() == null) return null;

        byte[] bytes = resp.body();
        String declared = resp.headers().firstValue("content-type").orElse("");
        Charset charset = charsetOf(bytes, declared);
        return new String(bytes, charset);
    }

    /** 判定页面编码：优先响应头，其次 meta，最后尝试 UTF-8；GB2312/GBK 统一按 GBK 解 */
    private Charset charsetOf(byte[] bytes, String contentType) {
        String name = null;
        Matcher m = Pattern.compile("charset=([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (m.find()) name = m.group(1);
        if (name == null) {
            int head = Math.min(bytes.length, 4096);
            Matcher meta = META_CHARSET.matcher(new String(bytes, 0, head, StandardCharsets.ISO_8859_1));
            if (meta.find()) name = meta.group(1);
        }
        if (name == null) return StandardCharsets.UTF_8;
        try {
            if (name.equalsIgnoreCase("gb2312") || name.equalsIgnoreCase("gb-2312")) return Charset.forName("GBK");
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private String absoluteUrl(String base, String href) {
        try {
            if (href.startsWith("http://") || href.startsWith("https://")) return href;
            return URI.create(base).resolve(href).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean containsKeyword(String title) {
        for (String k : KEYWORDS) {
            if (title.contains(k)) return true;
        }
        return false;
    }

    private String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    private String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
