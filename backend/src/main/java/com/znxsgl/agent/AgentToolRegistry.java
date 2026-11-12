package com.znxsgl.agent;

import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：白名单 + 角色校验。
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            tools.put(tool.name(), tool);
        }
    }

    public List<AgentTool> toolsFor(String role) {
        List<AgentTool> result = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            if (tool.roles().contains(role)) {
                result.add(tool);
            }
        }
        return result;
    }

    public AgentTool requireAllowed(String name, AgentContext ctx) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new AgentException("UNKNOWN_TOOL", "暂不支持该操作：" + name);
        }
        if (!tool.roles().contains(ctx.getRole())) {
            throw new AgentException("FORBIDDEN", "当前角色无权执行该操作", 403);
        }
        return tool;
    }

    public ToolResult execute(AgentContext ctx, String name, Map<String, Object> args) {
        AgentTool tool = requireAllowed(name, ctx);
        return tool.execute(ctx, args == null ? Map.of() : args);
    }
}
