package com.znxsgl.controller;

import com.znxsgl.dto.*;
import com.znxsgl.service.CourseChapterService;
import com.znxsgl.service.QuestionGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 课程章节与资源管理接口
 *
 * 权限说明：
 * - 管理员：可管理所有课程的章节和资源
 * - 教师：可管理自己所授课程的章节和资源
 * - 学生：可查看自己已选课程的章节和资源
 */
@RestController
@RequestMapping("/api/course-chapter")
public class CourseChapterController {

    private final CourseChapterService courseChapterService;
    private final QuestionGenerationService questionGenerationService;

    public CourseChapterController(CourseChapterService courseChapterService,
                                   QuestionGenerationService questionGenerationService) {
        this.courseChapterService = courseChapterService;
        this.questionGenerationService = questionGenerationService;
    }

    /**
     * 查询某课程下的章节列表（含课时）
     */
    @GetMapping("/course/{courseId}/chapters")
    @PreAuthorize("hasAuthority('chapter:view')")
    public ResponseEntity<List<ChapterDTO>> listChapters(@PathVariable Long courseId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return ResponseEntity.ok(courseChapterService.listChaptersByCourse(courseId, userId, isAdmin));
    }

    /**
     * 查询单个章节详情（含课时）
     */
    @GetMapping("/chapters/{chapterId}")
    @PreAuthorize("hasAuthority('chapter:view')")
    public ResponseEntity<?> getChapterDetail(@PathVariable Long chapterId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        ChapterDTO dto = courseChapterService.getChapterDetail(chapterId, userId, isAdmin);
        if (dto == null) {
            return ResponseEntity.status(404).body(Map.of("error", "章节不存在或无权限"));
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * 保存章节（新增/更新）
     */
    @PostMapping("/chapters")
    @PreAuthorize("hasAuthority('chapter:create') or hasAuthority('chapter:edit:self') or hasAuthority('chapter:edit:all')")
    public ResponseEntity<?> saveChapter(@Valid @RequestBody ChapterSaveRequest request, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        courseChapterService.saveChapter(request, userId, isAdmin);
        return ResponseEntity.ok(Map.of("message", "保存成功"));
    }

    /**
     * 删除章节
     */
    @DeleteMapping("/chapters/{chapterId}")
    @PreAuthorize("hasAuthority('chapter:delete')")
    public ResponseEntity<?> deleteChapter(@PathVariable Long chapterId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        courseChapterService.deleteChapter(chapterId, userId, isAdmin);
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    /**
     * 保存课时/资源（新增/更新）
     */
    @PostMapping("/lessons")
    @PreAuthorize("hasAuthority('resource:create') or hasAuthority('resource:edit:self') or hasAuthority('resource:edit:all')")
    public ResponseEntity<?> saveLesson(@Valid @RequestBody LessonSaveRequest request, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        courseChapterService.saveLesson(request, userId, isAdmin);
        return ResponseEntity.ok(Map.of("message", "保存成功"));
    }

    /**
     * 删除课时/资源
     */
    @DeleteMapping("/lessons/{lessonId}")
    @PreAuthorize("hasAuthority('resource:delete')")
    public ResponseEntity<?> deleteLesson(@PathVariable Long lessonId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        courseChapterService.deleteLesson(lessonId, userId, isAdmin);
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    /**
     * AI 一键生成整课程章节（章节+课时+内容）
     * 可选请求体：{ "chapterCount": 6, "difficulty": "入门|基础|进阶", "notes": "生成范围/内容备注" }
     */
    @PostMapping("/generate/{courseId}")
    @PreAuthorize("hasAuthority('chapter:create') or hasAuthority('chapter:edit:self') or hasAuthority('chapter:edit:all')")
    public ResponseEntity<?> generateChapters(@PathVariable Long courseId,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        Integer chapterCount = null;
        String difficulty = null;
        String notes = null;
        if (body != null) {
            if (body.get("chapterCount") instanceof Number n) {
                chapterCount = n.intValue();
            }
            if (body.get("difficulty") != null) {
                difficulty = body.get("difficulty").toString();
            }
            if (body.get("notes") != null) {
                notes = body.get("notes").toString();
            }
        }
        try {
            Map<String, Object> result = courseChapterService.generateCourseChapters(
                    courseId, userId, isAdmin, chapterCount, difficulty, notes);
            // 章节生成成功后，后台预生成题库
            questionGenerationService.prewarmCourseAsync(courseId);
            return ResponseEntity.ok(Map.of(
                    "message", "生成成功",
                    "data", result
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "生成失败：" + e.getMessage()));
        }
    }

    /**
     * 从 Excel 导入课程章节和课时
     */
    @PostMapping("/import-excel")
    @PreAuthorize("hasAuthority('chapter:import')")
    public ResponseEntity<?> importExcel(@RequestParam("file") MultipartFile file,
                                         @RequestParam("courseId") Long courseId,
                                         Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "文件为空"));
        }

        try (InputStream is = file.getInputStream()) {
            ChapterImportResultDTO result = courseChapterService.importFromExcel(courseId, is, userId, isAdmin);
            boolean allSuccess = result.getFailCount() == 0;
            // 导入成功后，后台预生成题库
            questionGenerationService.prewarmCourseAsync(courseId);
            return ResponseEntity.ok(Map.of(
                    "success", allSuccess,
                    "message", allSuccess ? "导入成功" : "导入失败：存在数据校验错误",
                    "data", result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "服务器内部错误：" + e.getMessage()));
        }
    }

    /**
     * 从 Word 导入课程章节和课时
     */
    @PostMapping("/import-word")
    @PreAuthorize("hasAuthority('chapter:import')")
    public ResponseEntity<?> importWord(@RequestParam("file") MultipartFile file,
                                        @RequestParam("courseId") Long courseId,
                                        Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "文件为空"));
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!filename.endsWith(".docx")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "仅支持 .docx 格式"));
        }

        try (InputStream is = file.getInputStream()) {
            ChapterImportResultDTO result = courseChapterService.importFromWord(courseId, is, userId, isAdmin);
            boolean allSuccess = result.getFailCount() == 0;
            // 导入成功后，后台预生成题库
            questionGenerationService.prewarmCourseAsync(courseId);
            return ResponseEntity.ok(Map.of(
                    "success", allSuccess,
                    "message", allSuccess ? "导入成功" : "导入失败：存在数据校验错误",
                    "data", result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "服务器内部错误：" + e.getMessage()));
        }
    }
}
