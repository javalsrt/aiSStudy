package com.znxsgl.controller;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.AgentRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端 AI 操作记录：管理员看全部，教师看自己。
 */
@RestController
@RequestMapping("/api/manage/assistant/records")
@PreAuthorize("hasRole('ADMIN') or hasRole('TEACHER')")
public class AgentRecordController {

    private final AgentRecordService recordService;

    public AgentRecordController(AgentRecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "1") int pageNum,
                                  @RequestParam(defaultValue = "20") int pageSize,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String actionType,
                                  Authentication auth) {
        try {
            return ResponseEntity.ok(recordService.page(auth, pageNum, pageSize, keyword, status, actionType));
        } catch (AgentException e) {
            return error(e);
        }
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id, Authentication auth) {
        try {
            return ResponseEntity.ok(recordService.restore(auth, id));
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
