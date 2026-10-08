package com.znxsgl.student;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本清理工具：修复 AI 生成题目偶发的「整句重复」问题。
 * 例：凑十时，7和__能组合成10；5的前一个数是__。凑十时，7和__能组合成10；5的前一个数是__。
 */
public final class Texts {

    private Texts() {}

    private static final Pattern SENTENCE = Pattern.compile("[^。；;！!？?]*[。；;！!？?]?");

    /** 去除连续重复的句子/分句（长度 ≥6 才视为有效句，避免误伤短字段） */
    public static String removeDuplicateSentences(@Nullable String text) {
        if (text == null || text.length() < 12) return text;
        List<String> tokens = new ArrayList<>();
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            if (!m.group().isEmpty()) tokens.add(m.group());
        }
        StringBuilder sb = new StringBuilder(text.length());
        String prev = null;
        for (String t : tokens) {
            String key = t.trim();
            if (key.length() >= 6 && key.equals(prev)) continue; // 连续重复 → 丢弃
            sb.append(t);
            prev = key;
        }
        return sb.length() > 0 ? sb.toString() : text;
    }
}
