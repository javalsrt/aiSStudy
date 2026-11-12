package com.znxsgl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.znxsgl.agent.dto.AgentChatRequest;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析模型返回的严格 JSON 协议。
 * <p>
 * 容忍 ```json 代码块与前后解释文字，但只取第一个完整 JSON 对象；解析失败绝不执行半截参数。
 */
@Component
public class AgentProtocolParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public ParsedReply parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AgentException("EMPTY_RESPONSE", "AI 没有返回内容");
        }
        String text = raw.trim();
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int firstLineEnd = text.indexOf('\n', fenceStart);
            int fenceEnd = text.lastIndexOf("```");
            if (firstLineEnd > 0 && fenceEnd > firstLineEnd) {
                text = text.substring(firstLineEnd + 1, fenceEnd);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AgentException("PARSE_ERROR", "AI 返回格式不正确");
        }
        text = text.substring(start, end + 1);

        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            throw new AgentException("PARSE_ERROR", "AI 返回的 JSON 无法解析");
        }

        ParsedReply reply = new ParsedReply();
        String type = root.path("type").asText("answer");
        if (!"action".equals(type) && !"clarify".equals(type) && !"answer".equals(type)) {
            type = "answer";
        }
        reply.setType(type);
        reply.setContent(trim(root.path("content").asText(""), 4000));

        JsonNode suggestions = root.path("suggestions");
        List<String> suggestionList = new ArrayList<>();
        if (suggestions.isArray()) {
            for (JsonNode item : suggestions) {
                String s = item.asText("");
                if (!s.isBlank()) suggestionList.add(trim(s, 100));
                if (suggestionList.size() >= 4) break;
            }
        }
        reply.setSuggestions(suggestionList);

        if ("action".equals(type)) {
            String action = root.path("action").asText("");
            if (action.isBlank()) {
                throw new AgentException("PARSE_ERROR", "AI 没有给出可执行动作");
            }
            JsonNode argsNode = root.path("args");
            Map<String, Object> args = new LinkedHashMap<>();
            if (argsNode.isObject()) {
                argsNode.fields().forEachRemaining(entry ->
                        args.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class)));
            }
            reply.setAction(action);
            reply.setArgs(args);
        }
        if (reply.getContent().isBlank() && "action".equals(type)) {
            reply.setContent("好的，我来处理。");
        }
        return reply;
    }

    /** Chat 模式兜底：模型可能返回普通文本而不是 JSON */
    public ParsedReply textReply(String raw) {
        ParsedReply reply = new ParsedReply();
        reply.setType("answer");
        reply.setContent(trim(raw == null ? "" : raw, 4000));
        reply.setSuggestions(List.of());
        return reply;
    }

    public void validate(AgentChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new AgentException("INVALID_ARGS", "请输入问题或指令");
        }
        if (request.getMessage().length() > 500) {
            throw new AgentException("INVALID_ARGS", "指令过长，请控制在 500 字以内");
        }
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        String v = s.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    @Data
    public static class ParsedReply {
        private String type;
        private String content;
        private String action;
        private Map<String, Object> args;
        private List<String> suggestions = List.of();
    }
}
