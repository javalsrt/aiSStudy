package com.znxsgl.agent;

import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;

import java.util.Map;
import java.util.Set;

/**
 * AI 可调用的原子工具。所有工具必须自行校验参数与数据范围。
 */
public interface AgentTool {

    /** 工具名，如 student.search */
    String name();

    /** 中文操作名，用于结果卡片 */
    String label();

    /** 给模型看的说明（含参数约束） */
    String description();

    /** 允许的角色：ADMIN / TEACHER */
    Set<String> roles();

    /** 是否必须先二次确认 */
    default boolean requiresConfirm() {
        return false;
    }

    /** 参数说明，用于拼 Prompt */
    Map<String, Object> argsSpec();

    /** 执行；参数非法或越权时抛 AgentException */
    ToolResult execute(AgentContext ctx, Map<String, Object> args);

    /** 确认前的预览（删除类工具覆写） */
    default ToolResult preview(AgentContext ctx, Map<String, Object> args) {
        return ToolResult.ok(description());
    }
}
