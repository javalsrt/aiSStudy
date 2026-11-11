package com.znxsgl.controller;

import com.znxsgl.dto.TeachingTaskSaveRequest;
import com.znxsgl.entity.*;
import com.znxsgl.mapper.*;
import com.znxsgl.service.AutoScheduleJobService;
import com.znxsgl.service.AutoScheduleService;
import com.znxsgl.service.SemesterService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 管理员端 - 排课管理接口
 *
 * 包含：
 *   1. 教室资源管理（CRUD）
 *   2. 教学任务管理（批量导入、列表、删除）
 *   3. 一键自动排课
 *   4. 排课结果查询 + 失败报告
 *
 * 全部接口需要 ADMIN 权限。
 */
@RestController
@RequestMapping("/api/admin/schedule")
@PreAuthorize("hasRole('ADMIN')")
public class AdminScheduleController {

    private final ClassroomMapper classroomMapper;
    private final TeachingTaskMapper teachingTaskMapper;
    private final CourseMapper courseMapper;
    private final TeacherMapper teacherMapper;
    private final ClassInfoMapper classInfoMapper;
    private final AutoScheduleService autoScheduleService;
    private final AutoScheduleJobService autoScheduleJobService;
    private final SemesterService semesterService;

    public AdminScheduleController(ClassroomMapper classroomMapper,
                                    TeachingTaskMapper teachingTaskMapper,
                                    CourseMapper courseMapper,
                                    TeacherMapper teacherMapper,
                                    ClassInfoMapper classInfoMapper,
                                    AutoScheduleService autoScheduleService,
                                    AutoScheduleJobService autoScheduleJobService,
                                    SemesterService semesterService) {
        this.classroomMapper = classroomMapper;
        this.teachingTaskMapper = teachingTaskMapper;
        this.courseMapper = courseMapper;
        this.teacherMapper = teacherMapper;
        this.classInfoMapper = classInfoMapper;
        this.autoScheduleService = autoScheduleService;
        this.autoScheduleJobService = autoScheduleJobService;
        this.semesterService = semesterService;
    }

    // ============================================================
    //  教室资源管理
    // ============================================================

    /** 教室列表；管理端传 includeInactive=true 时同时返回已停用教室 */
    @GetMapping("/classrooms")
    public ResponseEntity<List<Classroom>> listClassrooms(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean includeInactive) {
        LambdaQueryWrapper<Classroom> qw = new LambdaQueryWrapper<Classroom>()
                .orderByAsc(Classroom::getBuilding)
                .orderByAsc(Classroom::getFloor)
                .orderByAsc(Classroom::getName);
        if (!Boolean.TRUE.equals(includeInactive)) {
            qw.eq(Classroom::getIsActive, 1);
        }
        if (type != null && !type.isEmpty()) {
            qw.eq(Classroom::getType, type);
        }
        return ResponseEntity.ok(classroomMapper.selectList(qw));
    }

    /** 新增教室 */
    @PostMapping("/classroom")
    public ResponseEntity<Map<String, Object>> addClassroom(@RequestBody Classroom classroom) {
        classroom.setIsActive(1);
        classroomMapper.insert(classroom);
        return ResponseEntity.ok(Map.of("id", classroom.getId(), "message", "教室添加成功"));
    }

    /** 修改教室 */
    @PutMapping("/classroom/{id}")
    public ResponseEntity<Map<String, String>> updateClassroom(@PathVariable Long id,
                                                                @RequestBody Classroom classroom) {
        classroom.setId(id);
        classroomMapper.updateById(classroom);
        return ResponseEntity.ok(Map.of("message", "教室更新成功"));
    }

    /** 删除教室（物理删除；课表只记录教室名称，不影响历史排课） */
    @DeleteMapping("/classroom/{id}")
    public ResponseEntity<Map<String, String>> deleteClassroom(@PathVariable Long id) {
        classroomMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "教室已删除"));
    }

    // ============================================================
    //  教学任务管理
    // ============================================================

    /** 教学任务列表 */
    @GetMapping("/tasks")
    public ResponseEntity<List<TeachingTask>> listTasks(
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String status) {
        String sem = semester != null ? semester : semesterService.getCurrentSemesterName();
        if (sem == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        LambdaQueryWrapper<TeachingTask> qw = new LambdaQueryWrapper<TeachingTask>()
                .eq(TeachingTask::getSemester, sem)
                .orderByAsc(TeachingTask::getClassId)
                .orderByAsc(TeachingTask::getPriority);
        if (classId != null) qw.eq(TeachingTask::getClassId, classId);
        if (status != null) qw.eq(TeachingTask::getStatus, status);

        return ResponseEntity.ok(teachingTaskMapper.selectList(qw));
    }

    /** 批量导入教学任务 */
    @PostMapping("/tasks/batch")
    public ResponseEntity<Map<String, Object>> batchImportTasks(@RequestBody List<TeachingTaskSaveRequest> tasks) {
        String semester = semesterService.getCurrentSemesterName();
        if (semester == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前学期未配置"));
        }

        int created = 0, updated = 0;
        List<String> errors = new ArrayList<>();

        for (TeachingTaskSaveRequest req : tasks) {
            try {
                // 校验班级存在
                if (classInfoMapper.selectById(req.getClassId()) == null) {
                    errors.add("班级不存在：id=" + req.getClassId());
                    continue;
                }
                // 校验课程存在
                Course course = courseMapper.selectById(req.getCourseId());
                if (course == null) {
                    errors.add("课程不存在：id=" + req.getCourseId());
                    continue;
                }
                // 校验教师存在
                Teacher teacher = null;
                if (req.getTeacherId() != null) {
                    teacher = teacherMapper.selectById(req.getTeacherId());
                    if (teacher == null) {
                        errors.add("教师不存在：id=" + req.getTeacherId());
                        continue;
                    }
                }

                // 查找是否已存在（同一学期同一班级同一课程）
                TeachingTask existing = teachingTaskMapper.selectOne(
                        new LambdaQueryWrapper<TeachingTask>()
                                .eq(TeachingTask::getSemester, semester)
                                .eq(TeachingTask::getClassId, req.getClassId())
                                .eq(TeachingTask::getCourseId, req.getCourseId()));

                TeachingTask task = new TeachingTask();
                task.setSemester(semester);
                task.setClassId(req.getClassId());
                task.setCourseId(req.getCourseId());
                task.setCourseName(course.getCourseName());
                task.setTeacherId(req.getTeacherId());
                task.setTeacherName(teacher != null ? teacher.getRealName() : null);
                task.setWeeklyHours(req.getWeeklyHours() != null ? req.getWeeklyHours() : 2);
                task.setConsecutive(req.getConsecutive() != null ? req.getConsecutive() : 1);
                task.setPreferredRoomType(req.getPreferredRoomType());
                task.setPreferredPeriod(req.getPreferredPeriod() != null ? req.getPreferredPeriod() : "any");
                task.setPriority(req.getPriority() != null ? req.getPriority() : 5);

                if (existing != null) {
                    task.setId(existing.getId());
                    task.setStatus(existing.getStatus());
                    teachingTaskMapper.updateById(task);
                    updated++;
                } else {
                    task.setStatus("pending");
                    teachingTaskMapper.insert(task);
                    created++;
                }
            } catch (Exception e) {
                errors.add("课程" + req.getCourseId() + "导入失败：" + e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
                "created", created,
                "updated", updated,
                "errors", errors,
                "message", String.format("导入完成：新增 %d，更新 %d，失败 %d",
                        created, updated, errors.size())
        ));
    }

    /** 删除单个教学任务 */
    @DeleteMapping("/task/{id}")
    public ResponseEntity<Map<String, String>> deleteTask(@PathVariable Long id) {
        teachingTaskMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "任务已删除"));
    }

    /** 清空当前学期所有教学任务 */
    @DeleteMapping("/tasks/clear")
    public ResponseEntity<Map<String, String>> clearTasks(
            @RequestParam(required = false) String semester) {
        String sem = semester != null ? semester : semesterService.getCurrentSemesterName();
        if (sem == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前学期未配置"));
        }
        int count = teachingTaskMapper.delete(
                new LambdaQueryWrapper<TeachingTask>().eq(TeachingTask::getSemester, sem));
        return ResponseEntity.ok(Map.of("message", "已删除 " + count + " 条教学任务"));
    }

    // ============================================================
    //  一键自动排课
    // ============================================================

    /**
     * 执行自动排课。
     *
     * @param clearExisting true=清空现有非锁定课表全量重排，false=只排未排的任务
     */
    @PostMapping("/auto-generate")
    public ResponseEntity<AutoScheduleService.ScheduleResult> autoGenerate(
            @RequestParam(required = false) String semester,
            @RequestParam(defaultValue = "true") boolean clearExisting) {
        String sem = semester != null ? semester : semesterService.getCurrentSemesterName();
        if (sem == null) {
            AutoScheduleService.ScheduleResult r = new AutoScheduleService.ScheduleResult();
            r.message = "当前学期未配置，请先设置当前学期";
            return ResponseEntity.badRequest().body(r);
        }

        AutoScheduleService.ScheduleResult result = autoScheduleService.autoSchedule(sem, clearExisting);
        return ResponseEntity.ok(result);
    }

    /**
     * 异步执行自动排课：立即返回 jobId，前端轮询进度。
     * 全量排课需要写入数万行课表，同步请求会让页面长时间等待，因此新增该接口。
     */
    @PostMapping("/auto-generate/jobs")
    public ResponseEntity<Map<String, Object>> startAutoGenerateJob(
            @RequestParam(required = false) String semester,
            @RequestParam(defaultValue = "true") boolean clearExisting) {
        String sem = semester != null ? semester : semesterService.getCurrentSemesterName();
        if (sem == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前学期未配置，请先设置当前学期"));
        }

        AutoScheduleJobService.Job job = autoScheduleJobService.start(sem, clearExisting);
        if (job == null) {
            return ResponseEntity.status(409).body(Map.of("error", "已有排课任务正在执行，请等待完成或刷新查看进度"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.jobId);
        body.put("status", job.status);
        body.put("message", "排课任务已开始，进度将自动刷新");
        return ResponseEntity.accepted().body(body);
    }

    /** 查询排课任务进度/结果 */
    @GetMapping("/auto-generate/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getAutoGenerateJob(@PathVariable String jobId) {
        AutoScheduleJobService.Job job = autoScheduleJobService.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of("error", "排课任务不存在或已过期"));
        }
        return ResponseEntity.ok(jobView(job));
    }

    /** 查询当前正在执行的排课任务（页面刷新后可重新接上进度） */
    @GetMapping("/auto-generate/jobs/running")
    public ResponseEntity<Map<String, Object>> getRunningAutoGenerateJob() {
        AutoScheduleJobService.Job job = autoScheduleJobService.running();
        if (job == null) {
            return ResponseEntity.ok(Map.of("status", "idle"));
        }
        return ResponseEntity.ok(jobView(job));
    }

    private Map<String, Object> jobView(AutoScheduleJobService.Job job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.jobId);
        body.put("semester", job.semester);
        body.put("status", job.status);
        body.put("total", job.total);
        body.put("processed", job.processed);
        long end = job.finishedAt != null ? job.finishedAt : System.currentTimeMillis();
        body.put("elapsedMs", end - job.startedAt);
        if (job.result != null) body.put("result", job.result);
        if (job.error != null) body.put("error", job.error);
        return body;
    }

    /**
     * 排课统计（按状态分类）
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) String semester) {
        String sem = semester != null ? semester : semesterService.getCurrentSemesterName();
        if (sem == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前学期未配置"));
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("semester", sem);
        stats.put("total", teachingTaskMapper.selectCount(
                new LambdaQueryWrapper<TeachingTask>().eq(TeachingTask::getSemester, sem)));
        stats.put("scheduled", teachingTaskMapper.selectCount(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getSemester, sem)
                        .eq(TeachingTask::getStatus, "scheduled")));
        stats.put("failed", teachingTaskMapper.selectCount(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getSemester, sem)
                        .eq(TeachingTask::getStatus, "failed")));
        stats.put("pending", teachingTaskMapper.selectCount(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getSemester, sem)
                        .eq(TeachingTask::getStatus, "pending")));
        stats.put("locked", teachingTaskMapper.selectCount(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getSemester, sem)
                        .eq(TeachingTask::getStatus, "locked")));

        return ResponseEntity.ok(stats);
    }

    /**
     * 查询排课失败的任务列表
     */
    @GetMapping("/failures")
    public ResponseEntity<List<TeachingTask>> listFailures(
            @RequestParam(required = false) String semester) {
        String sem = semester != null ? semester : semesterService.getCurrentSemesterName();
        if (sem == null) return ResponseEntity.ok(Collections.emptyList());

        return ResponseEntity.ok(teachingTaskMapper.selectList(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getSemester, sem)
                        .eq(TeachingTask::getStatus, "failed")
                        .orderByAsc(TeachingTask::getClassId)));
    }
}
