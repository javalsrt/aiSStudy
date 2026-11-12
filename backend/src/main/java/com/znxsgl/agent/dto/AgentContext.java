package com.znxsgl.agent.dto;

import lombok.Data;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Set;

/**
 * 当前 AI 操作的调用人上下文。权限范围一律由服务端解析，不信任模型参数。
 */
@Data
public class AgentContext {

    private Long userId;
    private String username;
    private String realName;
    private String role;

    private boolean admin;

    /** teacher.id（仅教师） */
    private Long teacherId;

    /** 任教班级 ID 集合（教师） */
    private Set<Long> taughtClassIds;

    /** 任教班级 id → 名称 */
    private Map<Long, String> classNames;

    /** 是否校级领导 */
    private boolean leader;

    /** 透传给现有 Controller 复用其权限校验 */
    private Authentication authentication;
}
