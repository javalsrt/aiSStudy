package com.znxsgl.agent.dto;

import lombok.Data;

/**
 * 删除等高风险操作的确认请求。
 */
@Data
public class AgentConfirmRequest {

    private String sessionId;
    private String token;
    private Boolean approved;
}
