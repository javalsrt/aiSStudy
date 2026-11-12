package com.znxsgl.agent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 前端渲染的操作结果卡片。
 */
@Data
public class AgentActionCard {

    /** 工具名，如 student.delete */
    private String name;

    /** 中文操作名，如 删除学生 */
    private String label;

    /** success / failed / pending_confirm */
    private String status;

    private String summary;

    /** 关键字段（确认卡片展示） */
    private List<Map<String, String>> fields;

    /** 列表结果（最多 10 条） */
    private List<Map<String, Object>> items;

    /** 操作后跳转的管理端页面 */
    private String link;

    /** 删除确认令牌 */
    private String confirmToken;

    /** 确认有效期（秒） */
    private Integer expiresIn;
}
