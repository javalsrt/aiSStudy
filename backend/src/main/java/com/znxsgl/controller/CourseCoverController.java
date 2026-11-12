package com.znxsgl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.znxsgl.entity.Course;
import com.znxsgl.entity.Teacher;
import com.znxsgl.entity.TeachingTask;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.CourseMapper;
import com.znxsgl.mapper.TeacherMapper;
import com.znxsgl.mapper.TeachingTaskMapper;
import com.znxsgl.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 课程封面图管理。
 *
 * 权限：
 * - 管理员：可上传任意课程的封面
 * - 教师：只能上传自己任教的课程封面
 *
 * 学生端「我的课程」列表使用该封面作为课程图标。
 */
@Slf4j
@RestController
@RequestMapping("/api/course")
public class CourseCoverController {

    /** 封面图片大小上限：2MB */
    private static final long MAX_SIZE = 2L * 1024 * 1024;

    private final CourseMapper courseMapper;
    private final TeacherMapper teacherMapper;
    private final TeachingTaskMapper teachingTaskMapper;
    private final UserMapper userMapper;

    public CourseCoverController(CourseMapper courseMapper,
                                 TeacherMapper teacherMapper,
                                 TeachingTaskMapper teachingTaskMapper,
                                 UserMapper userMapper) {
        this.courseMapper = courseMapper;
        this.teacherMapper = teacherMapper;
        this.teachingTaskMapper = teachingTaskMapper;
        this.userMapper = userMapper;
    }

    @PostMapping("/{courseId}/cover")
    @PreAuthorize("hasRole('ADMIN') or hasRole('TEACHER')")
    public ResponseEntity<?> uploadCover(@PathVariable Long courseId,
                                         @RequestParam("file") MultipartFile file,
                                         Authentication auth) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            return ResponseEntity.status(404).body(Map.of("message", "课程不存在"));
        }

        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin && !teacherOwnsCourse(courseId, course, (Long) auth.getPrincipal())) {
            return ResponseEntity.status(403).body(Map.of("message", "只能上传自己任教课程的封面"));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择要上传的图片"));
        }
        if (file.getSize() > MAX_SIZE) {
            return ResponseEntity.badRequest().body(Map.of("message", "图片大小不能超过 2MB"));
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("message", "仅支持图片文件"));
        }

        try {
            String uploadDir = System.getProperty("user.dir") + "/uploads/course";
            File dir = new File(uploadDir);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建上传目录");
            }

            String filename = "course_" + courseId + "_" + System.currentTimeMillis()
                    + extensionOf(file.getOriginalFilename(), contentType);
            Path filePath = Paths.get(uploadDir, filename);
            Files.write(filePath, file.getBytes());

            String coverUrl = "/uploads/course/" + filename;
            deleteOldCover(course.getCoverUrl());
            course.setCoverUrl(coverUrl);
            courseMapper.updateById(course);

            log.info("课程封面上传成功: courseId={}, coverUrl={}", courseId, coverUrl);
            return ResponseEntity.ok(Map.of("coverUrl", coverUrl));
        } catch (Exception e) {
            log.error("课程封面上传失败: courseId={}", courseId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "上传失败，请稍后重试"));
        }
    }

    /** 管理员直接放行；教师需在 teaching_task 或 course.teacher_id 中拥有该课程 */
    private boolean teacherOwnsCourse(Long courseId, Course course, Long userId) {
        if (userId == null) return false;
        User user = userMapper.selectById(userId);
        if (user == null || user.getRealName() == null) return false;

        Teacher teacher = teacherMapper.selectOne(
                new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, user.getRealName()));
        if (teacher == null) return false;

        if (teacher.getId().equals(course.getTeacherId())) return true;

        Long count = teachingTaskMapper.selectCount(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getTeacherId, teacher.getId())
                        .eq(TeachingTask::getCourseId, courseId));
        return count != null && count > 0;
    }

    /** 删除旧封面文件，避免反复上传产生垃圾文件 */
    private void deleteOldCover(String coverUrl) {
        if (coverUrl == null || !coverUrl.startsWith("/uploads/course/")) return;
        try {
            Path oldFile = Paths.get(System.getProperty("user.dir"), "uploads",
                    coverUrl.substring("/uploads/".length())).normalize();
            Files.deleteIfExists(oldFile);
        } catch (Exception e) {
            log.warn("删除旧课程封面失败: {}", coverUrl, e);
        }
    }

    /** 根据 Content-Type 推断扩展名，避免使用来源不明的原始文件名后缀 */
    private String extensionOf(String originalName, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("png")) return ".png";
        if (type.contains("webp")) return ".webp";
        if (type.contains("gif")) return ".gif";
        if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) {
                String ext = originalName.substring(dot).toLowerCase();
                if (ext.matches("\\.[a-z0-9]{1,5}")) return ext;
            }
        }
        return ".jpg";
    }
}
