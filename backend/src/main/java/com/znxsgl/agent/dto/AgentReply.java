package com.znxsgl.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理端 AI 助手统一响应。
 */
@Data
public class AgentReply {

    private String sessionId;

    /** 助手自然语言回复 */
    private String content;

    /** 操作结果卡片；纯问答时为空 */
    private AgentActionCard action;

    /** 推荐追问 */
    private List<String> suggestions;

    /** 是否等待用户确认删除 */
    private Boolean needConfirm;
}
