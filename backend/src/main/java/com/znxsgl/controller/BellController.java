package com.znxsgl.controller;

import com.znxsgl.entity.Semester;
import com.znxsgl.service.BellTimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作息时间接口：App / 管理端获取自动适配后的作息表。
 *
 * 自动适配链路（服务端完成，客户端零硬编码）：
 *   登录用户(或 classId/grade 参数) → 年级
 *   → week(周次，缺省=今天) → 周次奇偶 → 年级映射(grade_week_parity)
 *   → 生效作息(单周表/双周表/通用表)
 */
@RestController
@RequestMapping("/api/bell")
public class BellController {

    private final BellTimeService bellTimeService;
    private final JdbcTemplate jdbc;

    public BellController(BellTimeService bellTimeService, JdbcTemplate jdbc) {
        this.bellTimeService = bellTimeService;
        this.jdbc = jdbc;
    }

    /**
     * 作息表。
     * @param grade   年级（如 2026级），可选；缺省时按 classId 或登录用户所属班级自动解析
     * @param classId 班级ID，可选；用于反查年级（管理端排课场景）
     * @param week    周次，可选；传入则按该周单双周解析（周视图），缺省按今天
     */
    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today(Principal principal,
            @RequestParam(value = "grade", required = false) String grade,
            @RequestParam(value = "classId", required = false) Long classId,
            @RequestParam(value = "week", required = false) Integer week) {

        LocalDate today = LocalDate.now();

        // 年级解析优先级：grade 参数 > classId 反查 > 登录用户所属班级反查 > 默认作息
        if (grade == null || grade.isBlank()) {
            if (classId != null) {
                grade = resolveGradeByClass(classId);
            } else if (principal instanceof org.springframework.security.core.Authentication auth
                    && auth.getPrincipal() instanceof Long userId) {
                grade = resolveGradeByUser(userId);
            }
        }
        final String g = grade;

        Integer weekNumber = (week != null && week > 0) ? week : bellTimeService.weekNumberOf(today);
        int parity = bellTimeService.parityByWeek(g, (week != null && week > 0) ? week : null);
        List<Map<String, Object>> periods = (week != null && week > 0)
                ? bellTimeService.bellsForWeek(g, week)
                : bellTimeService.todayBells(g);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", today.toString());
        data.put("grade", g);
        data.put("weekNumber", weekNumber);
        data.put("parity", parity);                          // 1=单周 2=双周 0=未知(按通用表)
        data.put("parityLabel", parity == 1 ? "单周" : parity == 2 ? "双周" : "通用");
        Semester sem = bellTimeService.currentSemester();
        data.put("semester", sem == null ? null : sem.getName());
        data.put("periods", periods);
        return ResponseEntity.ok(data);
    }

    /** 通过班级反查年级 */
    private String resolveGradeByClass(Long classId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT grade FROM class_info WHERE id = ? AND grade IS NOT NULL", String.class, classId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 通过登录用户（学生）反查年级 */
    private String resolveGradeByUser(Long userId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT ci.grade FROM user u JOIN class_info ci ON ci.id = u.class_id " +
                    "WHERE u.id = ? AND ci.grade IS NOT NULL", String.class, userId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }
}
