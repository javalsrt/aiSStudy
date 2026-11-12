package com.znxsgl.agent;

import com.znxsgl.agent.dto.AgentActionCard;
import com.znxsgl.agent.dto.AgentChatRequest;
import com.znxsgl.agent.dto.AgentConfirmRequest;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.AgentReply;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端 AI 助手主编排。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final LlmService llmService;
    private final AgentScopeService scopeService;
    private final AgentSessionStore sessionStore;
    private final AgentPromptBuilder promptBuilder;
    private final AgentProtocolParser protocolParser;
    private final AgentToolRegistry toolRegistry;
    private final AgentAuditService auditService;

    public AgentService(LlmService llmService, AgentScopeService scopeService,
                        AgentSessionStore sessionStore, AgentPromptBuilder promptBuilder,
                        AgentProtocolParser protocolParser, AgentToolRegistry toolRegistry,
                        AgentAuditService auditService) {
        this.llmService = llmService;
        this.scopeService = scopeService;
        this.sessionStore = sessionStore;
        this.promptBuilder = promptBuilder;
        this.protocolParser = protocolParser;
        this.toolRegistry = toolRegistry;
        this.auditService = auditService;
    }

    public AgentReply chat(Authentication auth, AgentChatRequest request) {
        protocolParser.validate(request);
        AgentContext ctx = scopeService.buildContext(auth);
        String mode = "chat".equalsIgnoreCase(request.getMode()) ? "chat" : "agent";
        String requestId = UUID.randomUUID().toString().replace("-", "");
        AgentSessionStore.Session session = sessionStore.getOrCreate(ctx.getUserId(), request.getSessionId());
        long start = System.currentTimeMillis();

        synchronized (session.getLock()) {
            if (session.getPending() != null) {
                if (System.currentTimeMillis() - session.getPending().getCreatedAt() > AgentSessionStore.PENDING_TTL_MS) {
                    session.setPending(null);
                } else {
                    AgentReply reply = new AgentReply();
                    reply.setSessionId(session.getSessionId());
                    reply.setContent("还有一个待确认的操作，请先在卡片上点「确认执行」或「取消」。");
                    reply.setSuggestions(List.of("确认执行", "取消"));
                    reply.setNeedConfirm(true);
                    return reply;
                }
            }

            String message = request.getMessage().trim();
            sessionStore.addMessage(session, "user", message);

            long llmStart = System.currentTimeMillis();
            String raw;
            try {
                if ("chat".equals(mode)) {
                    List<Map<String, String>> messages = buildMessages(
                            promptBuilder.buildChatSystem(ctx), session);
                    raw = llmService.chatMessages(ctx.getUserId(), messages, 3000, false);
                } else {
                    List<AgentTool> tools = toolRegistry.toolsFor(ctx.getRole());
                    List<Map<String, String>> messages = buildMessages(
                            promptBuilder.buildAgentSystem(ctx, tools, session), session);
                    raw = llmService.chatMessages(ctx.getUserId(), messages, 4096, true);
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                String friendly = msg.contains("频繁") || msg.contains("繁忙")
                        ? "你操作得有点快，稍等几秒再试。"
                        : "AI 服务暂时不可用，请稍后再试。";
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), mode,
                        message, null, null, "failed", friendly,
                        System.currentTimeMillis() - start, requestId, "LLM_ERROR");
                return simpleReply(session, friendly);
            }

            if (raw == null || raw.isBlank()) {
                String friendly = "AI 服务暂时不可用，请稍后再试。";
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), mode,
                        message, null, null, "failed", friendly,
                        System.currentTimeMillis() - start, requestId, "LLM_EMPTY");
                return simpleReply(session, friendly);
            }

            AgentReply reply;
            if ("chat".equals(mode)) {
                AgentProtocolParser.ParsedReply parsed = protocolParser.textReply(raw);
                reply = replyFromParsed(session, parsed);
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), mode,
                        message, null, null, "success", reply.getContent(),
                        System.currentTimeMillis() - start, requestId, null);
            } else {
                reply = handleAgentRaw(ctx, session, message, raw, requestId, start);
            }

            sessionStore.addMessage(session, "assistant",
                    reply.getContent() + (reply.getAction() == null ? "" : " [" + reply.getAction().getStatus() + "]"));
            reply.setSessionId(session.getSessionId());
            log.info("AI 助手[{}] {} 用时 {}ms", mode, ctx.getRole(), System.currentTimeMillis() - llmStart);
            return reply;
        }
    }

    private AgentReply handleAgentRaw(AgentContext ctx, AgentSessionStore.Session session,
                                      String userMessage, String raw, String requestId, long start) {
        AgentProtocolParser.ParsedReply parsed;
        try {
            parsed = protocolParser.parse(raw);
        } catch (AgentException firstError) {
            // 解析失败重试一次：明确要求纯 JSON，不执行任何半解析结果
            try {
                List<Map<String, String>> retryMessages = buildMessages(
                        promptBuilder.buildAgentSystem(ctx, toolRegistry.toolsFor(ctx.getRole()), session), session);
                retryMessages.add(Map.of("role", "user",
                        "content", "上一次输出不是合法 JSON。请只输出一个 JSON 对象，不要 Markdown 代码块。"));
                String retry = llmService.chatMessages(ctx.getUserId(), retryMessages, 4096, true);
                parsed = protocolParser.parse(retry);
            } catch (Exception retryError) {
                String content = "我没太理解你的意思，可以换个说法，或直接告诉我要查/改哪个班级、哪位学生。";
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                        userMessage, null, null, "failed", content,
                        System.currentTimeMillis() - start, requestId, "PARSE_ERROR");
                return simpleReplyWithSuggestions(session, content,
                        List.of("查一下我教的班级", "现在有多少学生？"));
            }
        }

        if (!"action".equals(parsed.getType())) {
            AgentReply reply = replyFromParsed(session, parsed);
            auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                    userMessage, null, null, "success", reply.getContent(),
                    System.currentTimeMillis() - start, requestId, null);
            return reply;
        }

        String toolName = parsed.getAction();
        Map<String, Object> args = parsed.getArgs() == null ? Map.of() : parsed.getArgs();
        AgentTool tool;
        try {
            tool = toolRegistry.requireAllowed(toolName, ctx);
        } catch (AgentException e) {
            AgentReply reply = failedActionReply(session, toolName, toolName, e.getMessage());
            auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                    userMessage, toolName, args, "failed", e.getMessage(),
                    System.currentTimeMillis() - start, requestId, e.getCode());
            return reply;
        }

        if (tool.requiresConfirm()) {
            try {
                ToolResult preview = tool.preview(ctx, args);
                if (!preview.isSuccess()) {
                    return failedActionReply(session, tool.name(), tool.label(), preview.getSummary());
                }
                String token = UUID.randomUUID().toString().replace("-", "");
                AgentSessionStore.PendingAction pending = new AgentSessionStore.PendingAction();
                pending.setName(tool.name());
                pending.setLabel(tool.label());
                pending.setArgs(args);
                pending.setToken(token);
                pending.setSummary(preview.getSummary());
                pending.setFields(preview.toFields());
                pending.setLink(preview.getLink());
                pending.setCreatedAt(System.currentTimeMillis());
                session.setPending(pending);

                AgentActionCard card = new AgentActionCard();
                card.setName(tool.name());
                card.setLabel(tool.label());
                card.setStatus("pending_confirm");
                card.setSummary(preview.getSummary());
                card.setFields(preview.toFields());
                card.setLink(preview.getLink());
                card.setConfirmToken(token);
                card.setExpiresIn((int) (AgentSessionStore.PENDING_TTL_MS / 1000));

                AgentReply reply = new AgentReply();
                reply.setSessionId(session.getSessionId());
                reply.setContent(parsed.getContent() + "\n\n此操作需要你确认后才会执行。");
                reply.setAction(card);
                reply.setNeedConfirm(true);
                reply.setSuggestions(List.of("确认执行", "取消"));
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                        userMessage, toolName, args, "pending_confirm", preview.getSummary(),
                        System.currentTimeMillis() - start, requestId, null);
                return reply;
            } catch (AgentException e) {
                AgentReply reply = failedActionReply(session, tool.name(), tool.label(), e.getMessage());
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                        userMessage, toolName, args, "failed", e.getMessage(),
                        System.currentTimeMillis() - start, requestId, e.getCode());
                return reply;
            }
        }

        try {
            ToolResult result = toolRegistry.execute(ctx, toolName, args);
            AgentReply reply = actionReply(session, parsed, tool, result);
            remember(session, toolName, result);
            auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                    userMessage, toolName, args, result.isSuccess() ? "success" : "failed",
                    result.getSummary(), System.currentTimeMillis() - start, requestId, result.getErrorCode());
            return reply;
        } catch (AgentException e) {
            AgentReply reply = failedActionReply(session, tool.name(), tool.label(), e.getMessage());
            auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                    userMessage, toolName, args, "failed", e.getMessage(),
                    System.currentTimeMillis() - start, requestId, e.getCode());
            return reply;
        } catch (Exception e) {
            log.warn("AI 工具执行失败 {}: {}", toolName, e.getMessage());
            String msg = "执行失败：" + (e.getMessage() == null ? "系统异常，请稍后重试" : e.getMessage());
            AgentReply reply = failedActionReply(session, tool.name(), tool.label(), msg);
            auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                    userMessage, toolName, args, "failed", msg,
                    System.currentTimeMillis() - start, requestId, "EXECUTE_ERROR");
            return reply;
        }
    }

    public AgentReply confirm(Authentication auth, AgentConfirmRequest request) {
        AgentContext ctx = scopeService.buildContext(auth);
        AgentSessionStore.Session session = sessionStore.getOrCreate(ctx.getUserId(), request.getSessionId());
        long start = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().replace("-", "");

        synchronized (session.getLock()) {
            boolean approved = Boolean.TRUE.equals(request.getApproved());
            AgentSessionStore.PendingAction pending = sessionStore.consumePending(
                    session, request.getToken(), approved);
            if (pending == null) {
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                        "取消确认", null, null, "canceled", "用户取消操作",
                        System.currentTimeMillis() - start, requestId, null);
                return simpleReply(session, "已取消，本次没有修改任何数据。");
            }
            try {
                AgentTool tool = toolRegistry.requireAllowed(pending.getName(), ctx);
                ToolResult result = toolRegistry.execute(ctx, pending.getName(), pending.getArgs());
                AgentActionCard card = toCard(tool, result);
                AgentReply reply = new AgentReply();
                reply.setSessionId(session.getSessionId());
                reply.setContent(result.isSuccess() ? result.getSummary() : "操作失败：" + result.getSummary());
                reply.setAction(card);
                reply.setSuggestions(List.of("查看相关页面"));
                sessionStore.addMessage(session, "assistant", reply.getContent());
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                        "确认执行", pending.getName(), pending.getArgs(),
                        result.isSuccess() ? "success" : "failed", result.getSummary(),
                        System.currentTimeMillis() - start, requestId, result.getErrorCode());
                return reply;
            } catch (Exception e) {
                String msg = "操作失败：" + (e.getMessage() == null ? "系统异常" : e.getMessage());
                auditService.log(ctx.getUserId(), ctx.getRole(), session.getSessionId(), "agent",
                        "确认执行", pending.getName(), pending.getArgs(), "failed", msg,
                        System.currentTimeMillis() - start, requestId, "CONFIRM_ERROR");
                return simpleReply(session, msg);
            }
        }
    }

    public Map<String, Object> meta(Authentication auth) {
        AgentContext ctx = scopeService.buildContext(auth);
        boolean admin = ctx.isAdmin();
        List<Map<String, Object>> capabilities = new ArrayList<>();
        capabilities.add(cap("student", "学生管理", "查询、新增、修改、删除学生"));
        capabilities.add(cap("class", "班级管理", admin ? "班级查询、新增、修改" : "查看自己任教的班级"));
        capabilities.add(cap("course", "课程与课表", admin ? "课程维护、课表查询" : "自己的课程与班级课表"));
        capabilities.add(cap("exam", "考试作业", "查询、上下架、删除"));
        capabilities.add(cap("leave", "请假审批", "查询待办、通过或驳回"));
        if (admin) {
            capabilities.add(cap("teacher", "教师管理", "查询、新增、修改、删除教师"));
            capabilities.add(cap("school", "校园信息", "校园信息查询与维护"));
            capabilities.add(cap("stats", "数据统计", "全校学生、教师、班级概览"));
        } else {
            capabilities.add(cap("stats", "班级统计", "任教班级的学生与课程概览"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("greeting", "你好，我是智学 AI 助手");
        result.put("subtitle", admin
                ? "可以直接告诉我：查学生、改信息、看课表、审批请假"
                : "可以帮你查任教班级、管理学生、看课表、审批请假");
        result.put("role", ctx.getRole().toLowerCase());
        result.put("capabilities", capabilities);

        if (admin) {
            result.put("agentSuggestions", List.of(
                    "现在有多少学生和教师？",
                    "查一下24级计算机应用1班的学生",
                    "给24级计算机应用1班新增学生 2026999 张三",
                    "列出待审批的请假",
                    "查一下计算机应用1班的课表"));
            result.put("chatSuggestions", List.of(
                    "怎么批量导入学生？",
                    "怎么发布考试或作业？",
                    "怎么给教师排课？",
                    "怎么修改校园信息？"));
        } else {
            result.put("agentSuggestions", List.of(
                    "我教哪些班级？",
                    "查一下我班级的学生",
                    "给机电263班新增学生 2026999 张三",
                    "列出我班级待审批的请假",
                    "查一下我班级的课表"));
            result.put("chatSuggestions", List.of(
                    "怎么导入学生名单？",
                    "怎么审批学生请假？",
                    "怎么发布考试作业？",
                    "怎么查看班级学习统计？"));
        }
        return result;
    }

    private Map<String, Object> cap(String key, String title, String desc) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("title", title);
        item.put("desc", desc);
        return item;
    }

    private List<Map<String, String>> buildMessages(String system, AgentSessionStore.Session session) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        List<Map<String, String>> history = session.getMessages();
        int start = Math.max(0, history.size() - 9);
        for (int i = start; i < history.size(); i++) {
            messages.add(history.get(i));
        }
        return messages;
    }

    private AgentReply replyFromParsed(AgentSessionStore.Session session,
                                       AgentProtocolParser.ParsedReply parsed) {
        AgentReply reply = new AgentReply();
        reply.setSessionId(session.getSessionId());
        reply.setContent(parsed.getContent());
        reply.setSuggestions(parsed.getSuggestions());
        return reply;
    }

    private AgentReply actionReply(AgentSessionStore.Session session,
                                   AgentProtocolParser.ParsedReply parsed,
                                   AgentTool tool, ToolResult result) {
        AgentReply reply = new AgentReply();
        reply.setSessionId(session.getSessionId());
        String content = parsed == null ? "" : parsed.getContent();
        if (result.getSummary() != null && !result.getSummary().isBlank()) {
            content = content.isBlank() ? result.getSummary() : content + "\n\n" + result.getSummary();
        }
        reply.setContent(content);
        reply.setAction(toCard(tool, result));
        reply.setSuggestions(parsed == null ? List.of() : parsed.getSuggestions());
        return reply;
    }

    private AgentActionCard toCard(AgentTool tool, ToolResult result) {
        AgentActionCard card = new AgentActionCard();
        card.setName(tool.name());
        card.setLabel(tool.label());
        card.setStatus(result.isSuccess() ? "success" : "failed");
        card.setSummary(result.getSummary());
        card.setFields(result.toFields());
        if (result.getItems() != null) {
            card.setItems(result.getItems().size() > 10
                    ? result.getItems().subList(0, 10) : result.getItems());
        }
        card.setLink(result.getLink());
        return card;
    }

    private AgentReply failedActionReply(AgentSessionStore.Session session,
                                         String name, String label, String message) {
        AgentActionCard card = new AgentActionCard();
        card.setName(name);
        card.setLabel(label);
        card.setStatus("failed");
        card.setSummary(message);
        AgentReply reply = new AgentReply();
        reply.setSessionId(session.getSessionId());
        reply.setContent(message);
        reply.setAction(card);
        reply.setSuggestions(List.of("换个说法试试"));
        return reply;
    }

    private AgentReply simpleReply(AgentSessionStore.Session session, String content) {
        AgentReply reply = new AgentReply();
        reply.setSessionId(session.getSessionId());
        reply.setContent(content);
        return reply;
    }

    private AgentReply simpleReplyWithSuggestions(AgentSessionStore.Session session,
                                                  String content, List<String> suggestions) {
        AgentReply reply = simpleReply(session, content);
        reply.setSuggestions(suggestions);
        return reply;
    }

    private void remember(AgentSessionStore.Session session, String toolName, ToolResult result) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("tool", toolName);
        if (result.getData() != null && !result.getData().isEmpty()) {
            memory.putAll(result.getData());
        }
        if (result.getItems() != null && !result.getItems().isEmpty()) {
            memory.put("items", result.getItems().stream().limit(5).toList());
        }
        session.setLastEntity(memory);
    }
}
