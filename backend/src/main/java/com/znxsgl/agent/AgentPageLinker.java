package com.znxsgl.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把回答里提到的管理端页面名自动转成 Markdown 链接，保证前端可以直接点击跳转。
 * 每个页面名只链接第一次出现；已有的 Markdown 链接和代码片段保持原样。
 */
@Component
public class AgentPageLinker {

    /** 需要保护、不参与页面名替换的片段：代码块、行内代码、已有链接 */
    private static final Pattern PROTECTED = Pattern.compile(
            "```[\\s\\S]*?```|`[^`]*`|\\[[^\\]]*\\]\\([^)]*\\)");

    /** 代码片段（去重链接时保持原样） */
    private static final Pattern CODE = Pattern.compile("```[\\s\\S]*?```|`[^`]*`");

    /** Markdown 链接 */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    private static final Map<String, String> ADMIN_PAGES = adminPages();
    private static final Map<String, String> TEACHER_PAGES = teacherPages();

    public String link(String content, boolean admin) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        Map<String, String> pages = admin ? ADMIN_PAGES : TEACHER_PAGES;
        Map<String, String> stash = new LinkedHashMap<>();
        String text = protect(content, stash);

        List<String> names = new ArrayList<>(pages.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names) {
            int index = text.indexOf(name);
            if (index < 0) {
                continue;
            }
            String token = "\u0000" + stash.size() + "\u0000";
            stash.put(token, "[" + name + "](" + pages.get(name) + ")");
            text = text.substring(0, index) + token + text.substring(index + name.length());
        }

        for (Map.Entry<String, String> entry : stash.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return dedupeLinks(text);
    }

    /** 同一站内页面在一条回答里只保留第一个链接，其余还原成纯文本 */
    private String dedupeLinks(String text) {
        Map<String, String> codeStash = new LinkedHashMap<>();
        String masked = protectCode(text, codeStash);

        Matcher matcher = LINK.matcher(masked);
        Set<String> seen = new HashSet<>();
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(2);
            boolean keep = !url.startsWith("/") || seen.add(url);
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(keep ? matcher.group() : matcher.group(1)));
        }
        matcher.appendTail(out);

        String result = out.toString();
        for (Map.Entry<String, String> entry : codeStash.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String protectCode(String content, Map<String, String> stash) {
        Matcher matcher = CODE.matcher(content);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String token = "\u0001" + stash.size() + "\u0001";
            stash.put(token, matcher.group());
            sb.append(content, last, matcher.start()).append(token);
            last = matcher.end();
        }
        sb.append(content.substring(last));
        return sb.toString();
    }

    private String protect(String content, Map<String, String> stash) {
        Matcher matcher = PROTECTED.matcher(content);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String token = "\u0000" + stash.size() + "\u0000";
            stash.put(token, matcher.group());
            sb.append(content, last, matcher.start()).append(token);
            last = matcher.end();
        }
        sb.append(content.substring(last));
        return sb.toString();
    }

    private static Map<String, String> adminPages() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("AI 操作记录", "/ai-records");
        pages.put("操作记录", "/ai-records");
        pages.put("数据总览", "/dashboard");
        pages.put("人员管理", "/admin/staff");
        pages.put("学生管理", "/admin/staff?tab=students");
        pages.put("教师管理", "/admin/staff?tab=teachers");
        pages.put("值班管理", "/admin/staff?tab=duty");
        pages.put("班干部管理", "/admin/staff?tab=cadre");
        pages.put("课程导入", "/admin/course-import");
        pages.put("学期管理", "/admin/semester");
        pages.put("排课管理", "/admin/schedule");
        pages.put("作息设置", "/admin/bell");
        pages.put("校园信息", "/admin/school-info");
        pages.put("请假审批", "/admin/leave");
        pages.put("课程管理", "/courses");
        pages.put("章节管理", "/course-chapters");
        pages.put("学习统计", "/stats");
        pages.put("考试作业", "/exam-homework");
        return pages;
    }

    private static Map<String, String> teacherPages() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("AI 操作记录", "/ai-records");
        pages.put("操作记录", "/ai-records");
        pages.put("数据总览", "/dashboard");
        pages.put("班级管理", "/teacher/class");
        pages.put("请假审批", "/teacher/leave");
        pages.put("课程管理", "/courses");
        pages.put("章节管理", "/course-chapters");
        pages.put("学习统计", "/stats");
        pages.put("考试作业", "/exam-homework");
        pages.put("课程聊天", "/teacher-chat");
        return pages;
    }
}
