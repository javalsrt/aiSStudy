package com.znxsgl.agent.dto;

import lombok.Data;

/**
 * 管理端 AI 助手对话请求。
 */
@Data
public class AgentChatRequest {

    /** 前端生成的会话 ID；为空时后端生成 */
    private String sessionId;

    private String message;
}
