package com.znxsgl.controller;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.AgentService;
import com.znxsgl.agent.dto.AgentChatRequest;
import com.znxsgl.agent.dto.AgentConfirmRequest;
import com.znxsgl.agent.dto.AgentReply;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端 AI 助手：管理员与教师共用。
 */
@RestController
@RequestMapping("/api/manage/assistant")
@PreAuthorize("hasRole('ADMIN') or hasRole('TEACHER')")
public class ManageAssistantController {

    private final AgentService agentService;

    public ManageAssistantController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/meta")
    public ResponseEntity<?> meta(Authentication auth) {
        try {
            return ResponseEntity.ok(agentService.meta(auth));
        } catch (AgentException e) {
            return error(e);
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AgentChatRequest request, Authentication auth) {
        try {
            AgentReply reply = agentService.chat(auth, request);
            return ResponseEntity.ok(reply);
        } catch (AgentException e) {
            return error(e);
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody AgentConfirmRequest request, Authentication auth) {
        try {
            return ResponseEntity.ok(agentService.confirm(auth, request));
        } catch (AgentException e) {
            return error(e);
        }
    }

    private ResponseEntity<Map<String, Object>> error(AgentException e) {
        return ResponseEntity.status(e.getHttpStatus()).body(Map.of(
                "error", e.getMessage(),
                "code", e.getCode()));
    }
}
