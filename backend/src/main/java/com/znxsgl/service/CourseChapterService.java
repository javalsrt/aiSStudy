package com.znxsgl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.znxsgl.dto.*;
import com.znxsgl.entity.Course;
import com.znxsgl.entity.CourseChapter;
import com.znxsgl.entity.CourseLesson;
import com.znxsgl.entity.DocumentVector;
import com.znxsgl.entity.Teacher;
import com.znxsgl.entity.TeachingTask;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.CourseChapterMapper;
import com.znxsgl.mapper.CourseLessonMapper;
import com.znxsgl.mapper.CourseMapper;
import com.znxsgl.mapper.DocumentVectorMapper;
import com.znxsgl.mapper.TeacherMapper;
import com.znxsgl.mapper.TeachingTaskMapper;
import com.znxsgl.mapper.UserMapper;
import com.znxsgl.util.WordChapterParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 课程章节与资源服务
 * 支持课程-章节-课时三级结构管理
 */
@Service
public class CourseChapterService {

    private final CourseChapterMapper chapterMapper;
    private final CourseLessonMapper lessonMapper;
    private final CourseMapper courseMapper;
    private final UserMapper userMapper;
    private final TeacherMapper teacherMapper;
    private final TeachingTaskMapper teachingTaskMapper;
    private final JdbcTemplate jdbc;
    private final LlmService llmService;
    private final EmbeddingService embeddingService;
    private final DocumentVectorMapper docMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CourseChapterService(CourseChapterMapper chapterMapper,
                                CourseLessonMapper lessonMapper,
                                CourseMapper courseMapper,
                                UserMapper userMapper,
                                TeacherMapper teacherMapper,
                                TeachingTaskMapper teachingTaskMapper,
                                JdbcTemplate jdbc,
                                LlmService llmService,
                                EmbeddingService embeddingService,
                                DocumentVectorMapper docMapper) {
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.courseMapper = courseMapper;
        this.userMapper = userMapper;
        this.teacherMapper = teacherMapper;
        this.teachingTaskMapper = teachingTaskMapper;
        this.jdbc = jdbc;
        this.llmService = llmService;
        this.embeddingService = embeddingService;
        this.docMapper = docMapper;
    }

    /**
     * 查询课程的章节列表（含课时）
     *
     * @param courseId 课程ID
     * @param userId   当前用户ID
     * @param isAdmin  是否管理员
     */
    public List<ChapterDTO> listChaptersByCourse(Long courseId, Long userId, boolean isAdmin) {
        if (!canAccessCourse(courseId, userId, isAdmin)) {
            return Collections.emptyList();
        }

        User currentUser = userMapper.selectById(userId);
        boolean isStudent = !isAdmin && currentUser != null && currentUser.getRole() != null && currentUser.getRole() == 1;

        LambdaQueryWrapper<CourseChapter> chapterQw = new LambdaQueryWrapper<CourseChapter>()
                .eq(CourseChapter::getCourseId, courseId)
                .orderByAsc(CourseChapter::getSortOrder)
                .orderByAsc(CourseChapter::getChapterNo);
        // 学生只展示启用章节；管理员/教师需要管理全部章节
        if (isStudent) {
            chapterQw.eq(CourseChapter::getStatus, 1);
        }
        List<CourseChapter> chapters = chapterMapper.selectList(chapterQw);

        if (CollectionUtils.isEmpty(chapters)) {
            return Collections.emptyList();
        }

        List<Long> chapterIds = chapters.stream().map(CourseChapter::getId).collect(Collectors.toList());
        LambdaQueryWrapper<CourseLesson> lessonQw = new LambdaQueryWrapper<CourseLesson>()
                .in(CourseLesson::getChapterId, chapterIds)
                .orderByAsc(CourseLesson::getSortOrder)
                .orderByAsc(CourseLesson::getLessonNo);
        // 学生只展示启用课时
        if (isStudent) {
            lessonQw.eq(CourseLesson::getStatus, 1);
        }
        List<CourseLesson> allLessons = lessonMapper.selectList(lessonQw);

        return chapters.stream().map(chapter -> {
            ChapterDTO dto = new ChapterDTO();
            dto.setId(chapter.getId());
            dto.setCourseId(chapter.getCourseId());
            dto.setChapterNo(chapter.getChapterNo());
            dto.setChapterName(chapter.getChapterName());
            dto.setDescription(chapter.getDescription());
            dto.setSortOrder(chapter.getSortOrder());
            dto.setStatus(chapter.getStatus());

            Course course = courseMapper.selectById(chapter.getCourseId());
            if (course != null) {
                dto.setCourseName(course.getCourseName());
            }

            List<LessonDTO> lessons = allLessons.stream()
                    .filter(l -> l.getChapterId().equals(chapter.getId()))
                    .map(this::convertLesson)
                    .collect(Collectors.toList());
            dto.setLessons(lessons);

            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 查询单个章节详情（含课时）
     */
    public ChapterDTO getChapterDetail(Long chapterId, Long userId, boolean isAdmin) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || chapter.getStatus() == 0) {
            return null;
        }
        if (!canAccessCourse(chapter.getCourseId(), userId, isAdmin)) {
            return null;
        }
        return buildChapterDTO(chapter);
    }

    /**
     * 保存章节（新增/更新）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveChapter(ChapterSaveRequest request, Long userId, boolean isAdmin) {
        if (!canAccessCourse(request.getCourseId(), userId, isAdmin)) {
            throw new RuntimeException("无权限操作该课程的章节");
        }
        Course course = courseMapper.selectById(request.getCourseId());
        if (course == null) {
            throw new RuntimeException("课程不存在");
        }
        if (request.getChapterName() == null || request.getChapterName().trim().isEmpty()) {
            throw new RuntimeException("章节名称不能为空");
        }
        if (request.getChapterName().length() > 200) {
            throw new RuntimeException("章节名称不能超过 200 个字符");
        }

        CourseChapter chapter = new CourseChapter();
        chapter.setId(request.getId());
        chapter.setCourseId(request.getCourseId());
        chapter.setChapterNo(request.getChapterNo());
        chapter.setChapterName(request.getChapterName());
        chapter.setDescription(request.getDescription());
        chapter.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : request.getChapterNo());
        chapter.setStatus(request.getStatus() != null ? request.getStatus() : 1);

        if (chapter.getId() == null) {
            chapterMapper.insert(chapter);
        } else {
            CourseChapter exist = chapterMapper.selectById(chapter.getId());
            if (exist == null) {
                throw new RuntimeException("章节不存在");
            }
            if (!exist.getCourseId().equals(chapter.getCourseId())) {
                throw new RuntimeException("不允许修改章节所属课程");
            }
            chapterMapper.updateById(chapter);
        }
    }

    /**
     * 删除章节（逻辑删除，同时删除下属课时）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteChapter(Long chapterId, Long userId, boolean isAdmin) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new RuntimeException("章节不存在");
        }
        if (!canAccessCourse(chapter.getCourseId(), userId, isAdmin)) {
            throw new RuntimeException("无权限删除该章节");
        }
        chapterMapper.deleteById(chapterId);
        // 同步逻辑删除下属课时，保持数据一致性（MyBatis-Plus 会根据 @TableLogic 自动转为 UPDATE deleted=1）
        lessonMapper.delete(new LambdaQueryWrapper<CourseLesson>().eq(CourseLesson::getChapterId, chapterId));
    }

    /**
     * 保存课时（新增/更新）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveLesson(LessonSaveRequest request, Long userId, boolean isAdmin) {
        CourseChapter chapter = chapterMapper.selectById(request.getChapterId());
        if (chapter == null || chapter.getStatus() == 0) {
            throw new RuntimeException("章节不存在或已禁用");
        }
        if (!canAccessCourse(chapter.getCourseId(), userId, isAdmin)) {
            throw new RuntimeException("无权限操作该课时");
        }

        CourseLesson lesson = new CourseLesson();
        lesson.setId(request.getId());
        lesson.setChapterId(request.getChapterId());
        lesson.setLessonNo(request.getLessonNo());
        lesson.setLessonName(request.getLessonName());
        lesson.setResourceType(request.getResourceType());
        lesson.setResourceUrl(request.getResourceUrl());
        lesson.setDuration(request.getDuration());
        lesson.setContent(request.getContent());
        lesson.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : request.getLessonNo());
        lesson.setStatus(request.getStatus() != null ? request.getStatus() : 1);

        if (lesson.getId() == null) {
            lessonMapper.insert(lesson);
        } else {
            CourseLesson exist = lessonMapper.selectById(lesson.getId());
            if (exist == null) {
                throw new RuntimeException("课时不存在");
            }
            if (!exist.getChapterId().equals(lesson.getChapterId())) {
                throw new RuntimeException("不允许修改课时所属章节");
            }
            lessonMapper.updateById(lesson);
        }

        // content 有就向量化；没有就 AI 生成通用内容后保存 + 向量化（异步，失败不阻断主流程）
        if (StringUtils.hasText(lesson.getContent())) {
            vectorizeLessonAsync(chapter.getCourseId(), lesson.getChapterId(), lesson.getId(), lesson.getLessonName(), lesson.getContent(), false);
        } else if ("document".equalsIgnoreCase(lesson.getResourceType())) {
            Course course = courseMapper.selectById(chapter.getCourseId());
            String courseName = course != null ? course.getCourseName() : "";
            String chapterName = chapter.getChapterName();
            generateContentAndSaveAsync(chapter.getCourseId(), courseName, lesson.getChapterId(), lesson.getId(),
                    chapterName, lesson.getLessonName());
        }
    }

    /**
     * AI 生成通用课时内容，异步：生成 → update lesson.content → 向量化
     * 内容格式：Markdown 正文，约 300-500 字，分层结构化、段落间留空行
     */
    private void generateContentAndSaveAsync(Long courseId, String courseName, Long chapterId, Long lessonId,
                                              String chapterName, String lessonName) {
        new Thread(() -> {
            try {
                String systemPrompt = "你是资深大学教师，擅长编写结构化教学内容。只输出正文，不要标题和解释文字。";
                String userPrompt = String.format(
                        "请为《%s》课程的第「%s」章节中的「%s」课时编写通用教学内容。\n" +
                                "要求：\n" +
                                "1）内容分层结构化：使用 Markdown 二级标题（##）划分 2-3 个核心知识点小节；\n" +
                                "2）每个知识点先讲概念，再举 1 个简短例子，例子必须换行单独成段；\n" +
                                "3）所有英文函数名、变量名、类名、命令行、代码片段都必须用 Markdown 行内代码（`code`）或 fenced code block（```）包裹，禁止裸写英文函数名；例如 `np.sin`、`np.exp`、`print()`、`arr[arr > 5]`；\n" +
                                "4）较长或较复杂的代码示例必须用 fenced code block（```python ... ```）独占一行或多行，代码块内部除原始代码自带的换行外，严禁自动换行；单行代码必须完整显示在一行内；\n" +
                                "5）列举多个函数、特性、步骤时，必须使用 Markdown 列表（- 或 1.）展示，每个列表项独占一行，列表项中的英文函数名仍用 `code` 包裹；\n" +
                                "6）段落之间必须留空行，禁止大段纯文本堆砌，保持视觉呼吸感；\n" +
                                "7）关键术语使用加粗（**term**），整体 300-500 字，不要加一级标题。\n" +
                                "8）数学公式必须使用 LaTeX 语法，且无论行内还是独立公式一律用 $$...$$ 包裹（如 $$a_n^{(1/n)} \\le \\limsup a_n$$、$$\\sum_{n=1}^{\\infty} a_n$$），分数用 \\frac{}{}、上下标用 ^ _、极限用 \\lim_{n \\to \\infty}；禁止用 a_n^(1/n)、limsup a_n <= limsup a_n 这类纯文本形式表达公式，禁止只用单个 $ 包裹公式。\n" +
                                "9）涉及参数规格、对比选型时用 Markdown 表格展示；涉及流程、结构、控制逻辑时用 ```mermaid 代码块（flowchart TD）描述；注意事项用引用块（> ⚠️ **注意**：内容）单独成段。\n" +
                                "注意：JSON 字符串中的英文双引号必须正确转义，内容中尽量避免直接使用英文双引号，可用中文「」代替。",
                        courseName, chapterName, lessonName);
                String content = llmService.chat(systemPrompt, userPrompt);
                if (content == null || content.trim().isEmpty()) {
                    return;
                }
                content = content.trim();
                // 更新 lesson 表 content
                CourseLesson upd = new CourseLesson();
                upd.setId(lessonId);
                upd.setContent(content);
                lessonMapper.updateById(upd);
                vectorizeLessonAsync(courseId, chapterId, lessonId, lessonName, content, true);
            } catch (Exception e) {
                System.out.println("=== AI 生成课时内容失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 异步把课时内容向量化写入 document_vector（按课时分块，不拆 500 字）
     */
    private void vectorizeLessonAsync(Long courseId, Long chapterId, Long lessonId, String lessonName, String content, boolean isGen) {
        new Thread(() -> {
            try {
                String courseName = jdbc.queryForObject(
                        "SELECT course_name FROM course WHERE id = ?", String.class, courseId);
                // 先删除该课时的旧向量
                jdbc.update("DELETE FROM document_vector WHERE chapter_id = ? AND doc_name = ?",
                        chapterId, lessonName);
                float[] embedding = embeddingService.embed(content);
                DocumentVector dv = new DocumentVector();
                dv.setCourseName(courseName);
                dv.setChapterId(chapterId);
                dv.setDocName(lessonName);
                dv.setContentChunk(content);
                if (embedding != null) {
                    dv.setEmbedding(objectMapper.writeValueAsString(embedding));
                }
                dv.setCreatedAt(LocalDateTime.now());
                docMapper.insert(dv);
            } catch (Exception e) {
                System.out.println("=== 课时向量化失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 删除课时（逻辑删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteLesson(Long lessonId, Long userId, boolean isAdmin) {
        CourseLesson lesson = lessonMapper.selectById(lessonId);
        if (lesson == null) {
            throw new RuntimeException("课时不存在");
        }
        CourseChapter chapter = chapterMapper.selectById(lesson.getChapterId());
        if (chapter == null) {
            throw new RuntimeException("章节不存在");
        }
        if (!canAccessCourse(chapter.getCourseId(), userId, isAdmin)) {
            throw new RuntimeException("无权限删除该课时");
        }
        // 清理该课时的向量数据
        try {
            jdbc.update("DELETE FROM document_vector WHERE chapter_id = ? AND doc_name = ?",
                    lesson.getChapterId(), lesson.getLessonName());
        } catch (Exception ignored) {}
        lessonMapper.deleteById(lessonId);
    }

    /**
     * 判断用户是否有权访问某课程数据
     * - 管理员：全部
     * - 教师：自己教授的课程
     * - 学生：所在班级已关联课程（兼容个人 schedule 旧数据）
     */
    private boolean canAccessCourse(Long courseId, Long userId, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }

        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            return false;
        }

        // 教师：优先按教学任务表判断该教师是否实际教授该课程；无记录时回退到 course.teacher_id
        if (user.getRole() != null && user.getRole() == 2) {
            Teacher teacher = teacherMapper.selectOne(
                    new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, user.getRealName()));
            if (teacher == null) {
                return false;
            }
            Long teacherId = teacher.getId();
            Long courseIdVal = course.getId();
            Long taskCount = teachingTaskMapper.selectCount(
                    new LambdaQueryWrapper<TeachingTask>()
                            .eq(TeachingTask::getTeacherId, teacherId)
                            .eq(TeachingTask::getCourseId, courseIdVal));
            if (taskCount != null && taskCount > 0) {
                return true;
            }
            // 教学任务表无数据时回退到 course.teacher_id（兼容旧数据）
            return teacherId.equals(course.getTeacherId());
        }

        // 学生：通过 schedule 表判断是否选了该课程
        if (user.getRole() != null && user.getRole() == 1) {
            return studentHasCourse(userId, courseId);
        }

        return false;
    }

    private boolean studentHasCourse(Long userId, Long courseId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getClassId() == null) {
            // 没有班级信息时，兼容旧的个人 schedule 判断
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule WHERE user_id = ? AND course_id = ? LIMIT 1",
                    Integer.class, userId, courseId);
            return count != null && count > 0;
        }

        Long classId = user.getClassId();
        // 1) 班级已关联课程：新学生即使没有个人 schedule 记录，也能访问章节
        Integer classCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_class WHERE class_id = ? AND course_id = ? LIMIT 1",
                Integer.class, classId, courseId);
        if (classCount != null && classCount > 0) {
            return true;
        }
        // 2) 兼容旧数据：本班任一学生的 schedule 关联过该课程
        Integer scheduleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE u.class_id = ? AND s.course_id = ? LIMIT 1",
                Integer.class, classId, courseId);
        return scheduleCount != null && scheduleCount > 0;
    }

    private ChapterDTO buildChapterDTO(CourseChapter chapter) {
        ChapterDTO dto = new ChapterDTO();
        dto.setId(chapter.getId());
        dto.setCourseId(chapter.getCourseId());
        dto.setChapterNo(chapter.getChapterNo());
        dto.setChapterName(chapter.getChapterName());
        dto.setDescription(chapter.getDescription());
        dto.setSortOrder(chapter.getSortOrder());
        dto.setStatus(chapter.getStatus());

        Course course = courseMapper.selectById(chapter.getCourseId());
        if (course != null) {
            dto.setCourseName(course.getCourseName());
        }

        List<LessonDTO> lessons = lessonMapper.selectList(
                new LambdaQueryWrapper<CourseLesson>()
                        .eq(CourseLesson::getChapterId, chapter.getId())
                        .eq(CourseLesson::getStatus, 1)
                        .orderByAsc(CourseLesson::getSortOrder)
                        .orderByAsc(CourseLesson::getLessonNo))
                .stream().map(this::convertLesson).collect(Collectors.toList());
        dto.setLessons(lessons);

        return dto;
    }

    private LessonDTO convertLesson(CourseLesson lesson) {
        LessonDTO dto = new LessonDTO();
        dto.setId(lesson.getId());
        dto.setChapterId(lesson.getChapterId());
        dto.setLessonNo(lesson.getLessonNo());
        dto.setLessonName(lesson.getLessonName());
        dto.setResourceType(lesson.getResourceType());
        dto.setResourceUrl(lesson.getResourceUrl());
        dto.setDuration(lesson.getDuration());
        dto.setContent(lesson.getContent());
        dto.setSortOrder(lesson.getSortOrder());
        dto.setStatus(lesson.getStatus());
        return dto;
    }

    /**
     * 从 Excel 导入课程章节和课时数据
     *
     * @param courseId   课程ID
     * @param excelInput Excel 文件输入流
     * @param userId     当前用户ID
     * @param isAdmin    是否管理员
     * @return 导入结果统计
     */
    @Transactional(rollbackFor = Exception.class)
    public ChapterImportResultDTO importFromExcel(Long courseId, InputStream excelInput,
                                                  Long userId, boolean isAdmin) {
        checkCourseAccess(courseId, userId, isAdmin);
        ChapterImportResultDTO result = new ChapterImportResultDTO();
        List<ChapterImportRowDTO> rows = parseExcel(excelInput, result);
        return saveImportRows(courseId, rows, result);
    }

    /**
     * 从 Word 导入课程章节和课时数据
     *
     * @param courseId  课程ID
     * @param wordInput Word 文件输入流
     * @param userId    当前用户ID
     * @param isAdmin   是否管理员
     * @return 导入结果统计
     */
    @Transactional(rollbackFor = Exception.class)
    public ChapterImportResultDTO importFromWord(Long courseId, InputStream wordInput,
                                                 Long userId, boolean isAdmin) {
        checkCourseAccess(courseId, userId, isAdmin);
        ChapterImportResultDTO result = new ChapterImportResultDTO();
        WordChapterParser parser = new WordChapterParser();
        List<ChapterImportRowDTO> rows = parser.parse(wordInput);
        if (!parser.getFailures().isEmpty()) {
            result.getFailures().addAll(parser.getFailures());
            result.setFailCount(result.getFailures().size());
            return result;
        }
        return saveImportRows(courseId, rows, result);
    }

    private void checkCourseAccess(Long courseId, Long userId, boolean isAdmin) {
        if (!canAccessCourse(courseId, userId, isAdmin)) {
            throw new RuntimeException("无权限操作该课程");
        }
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new RuntimeException("课程不存在");
        }
    }

    private ChapterImportResultDTO saveImportRows(Long courseId, List<ChapterImportRowDTO> rows,
                                                  ChapterImportResultDTO result) {
        if (!result.getFailures().isEmpty()) {
            result.setFailCount(result.getFailures().size());
            return result;
        }
        if (rows.isEmpty()) {
            return result;
        }
        if (rows.size() > 1000) {
            throw new RuntimeException("单次导入最多支持1000行数据");
        }

        Map<Integer, List<ChapterImportRowDTO>> chapterGroup = rows.stream()
                .collect(Collectors.groupingBy(ChapterImportRowDTO::getChapterNo));

        List<CourseChapter> existChapters = chapterMapper.selectList(
                new LambdaQueryWrapper<CourseChapter>()
                        .eq(CourseChapter::getCourseId, courseId)
                        .in(CourseChapter::getChapterNo, chapterGroup.keySet()));
        Map<Integer, CourseChapter> chapterNoMap = existChapters.stream()
                .collect(Collectors.toMap(CourseChapter::getChapterNo, c -> c));

        for (Map.Entry<Integer, List<ChapterImportRowDTO>> entry : chapterGroup.entrySet()) {
            Integer chapterNo = entry.getKey();
            List<ChapterImportRowDTO> chapterRows = entry.getValue();

            CourseChapter chapter = chapterNoMap.get(chapterNo);
            boolean isNewChapter = chapter == null;
            if (isNewChapter) {
                chapter = new CourseChapter();
                chapter.setCourseId(courseId);
                chapter.setChapterNo(chapterNo);
                chapter.setStatus(1);
            }
            chapter.setChapterName(chapterRows.get(0).getChapterName());
            chapter.setDescription(chapterRows.get(0).getDescription());
            chapter.setSortOrder(chapterNo);

            if (isNewChapter) {
                chapterMapper.insert(chapter);
                result.setChapterCount(result.getChapterCount() + 1);
            } else {
                chapterMapper.updateById(chapter);
            }

            List<Integer> lessonNos = chapterRows.stream()
                    .map(ChapterImportRowDTO::getLessonNo)
                    .collect(Collectors.toList());
            List<CourseLesson> existLessons = lessonMapper.selectList(
                    new LambdaQueryWrapper<CourseLesson>()
                            .eq(CourseLesson::getChapterId, chapter.getId())
                            .in(CourseLesson::getLessonNo, lessonNos));
            Map<Integer, CourseLesson> lessonNoMap = existLessons.stream()
                    .collect(Collectors.toMap(CourseLesson::getLessonNo, l -> l));

            for (ChapterImportRowDTO row : chapterRows) {
                CourseLesson lesson = lessonNoMap.get(row.getLessonNo());
                boolean isNewLesson = lesson == null;
                if (isNewLesson) {
                    lesson = new CourseLesson();
                    lesson.setChapterId(chapter.getId());
                    lesson.setLessonNo(row.getLessonNo());
                    lesson.setStatus(1);
                }
                lesson.setLessonName(row.getLessonName());
                lesson.setResourceType(row.getResourceType());
                lesson.setResourceUrl(row.getResourceUrl());
                lesson.setDuration(row.getDuration());
                lesson.setContent(row.getContent());
                lesson.setSortOrder(row.getLessonNo());

                if (isNewLesson) {
                    lessonMapper.insert(lesson);
                } else {
                    lessonMapper.updateById(lesson);
                }

                // 导入后 content 为空且是 document 类型，AI 生成通用内容 + 向量化
                if (!StringUtils.hasText(lesson.getContent()) && "document".equalsIgnoreCase(lesson.getResourceType())) {
                    Course course = courseMapper.selectById(courseId);
                    String courseName = course != null ? course.getCourseName() : "";
                    generateContentAndSaveAsync(courseId, courseName, chapter.getId(), lesson.getId(),
                            chapter.getChapterName(), lesson.getLessonName());
                } else if (StringUtils.hasText(lesson.getContent())) {
                    vectorizeLessonAsync(courseId, chapter.getId(), lesson.getId(), lesson.getLessonName(),
                            lesson.getContent(), false);
                }
            }
            result.setLessonCount(result.getLessonCount() + chapterRows.size());
        }

        return result;
    }

    /**
     * 解析 Excel 文件，收集校验错误到结果中
     */
    private List<ChapterImportRowDTO> parseExcel(InputStream input, ChapterImportResultDTO result) {
        List<ChapterImportRowDTO> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                ChapterImportRowDTO dto = new ChapterImportRowDTO();
                dto.setChapterNo(getIntCellValue(row.getCell(0)));
                dto.setChapterName(getStringCellValue(row.getCell(1)));
                dto.setDescription(getStringCellValue(row.getCell(2)));
                dto.setLessonNo(getIntCellValue(row.getCell(3)));
                dto.setLessonName(getStringCellValue(row.getCell(4)));
                dto.setResourceType(getStringCellValue(row.getCell(5)));
                dto.setResourceUrl(getStringCellValue(row.getCell(6)));
                dto.setDuration(getIntCellValue(row.getCell(7)));
                dto.setContent(getStringCellValue(row.getCell(8)));

                if (isEmptyRow(dto)) {
                    continue;
                }

                if (dto.getChapterNo() == null) {
                    result.getFailures().add(new ImportFailureDTO(i + 1, "章节序号不能为空"));
                    continue;
                }
                if (dto.getChapterNo() <= 0) {
                    result.getFailures().add(new ImportFailureDTO(i + 1, "章节序号必须大于0"));
                    continue;
                }
                if (!StringUtils.hasText(dto.getChapterName())) {
                    result.getFailures().add(new ImportFailureDTO(i + 1, "章节名称不能为空"));
                    continue;
                }
                if (dto.getLessonNo() == null) {
                    result.getFailures().add(new ImportFailureDTO(i + 1, "课时序号不能为空"));
                    continue;
                }
                if (dto.getLessonNo() <= 0) {
                    result.getFailures().add(new ImportFailureDTO(i + 1, "课时序号必须大于0"));
                    continue;
                }
                if (!StringUtils.hasText(dto.getLessonName())) {
                    result.getFailures().add(new ImportFailureDTO(i + 1, "课时名称不能为空"));
                    continue;
                }
                if (!StringUtils.hasText(dto.getResourceType())) {
                    dto.setResourceType("video");
                }
                String rt = dto.getResourceType().trim().toLowerCase();
                if (!Arrays.asList("video", "document", "quiz", "link").contains(rt)) {
                    result.getFailures().add(new ImportFailureDTO(i + 1, "资源类型不合法：" + dto.getResourceType()));
                    continue;
                }
                dto.setResourceType(rt);

                rows.add(dto);
            }
        } catch (Exception e) {
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
        return rows;
    }

    private String getStringCellValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        switch (cell.getCellType()) {
            case STRING:
                String v = cell.getStringCellValue();
                return v == null ? null : v.trim();
            case NUMERIC:
                return String.valueOf((int) cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return null;
        }
    }

    private Integer getIntCellValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return (int) cell.getNumericCellValue();
        }
        String v = getStringCellValue(cell);
        if (!StringUtils.hasText(v)) {
            return null;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isEmptyRow(ChapterImportRowDTO dto) {
        return dto.getChapterNo() == null
                && !StringUtils.hasText(dto.getChapterName())
                && dto.getLessonNo() == null
                && !StringUtils.hasText(dto.getLessonName());
    }

    /**
     * AI 一键生成整课程章节+课时+内容
     * 同步调用：规划章节结构（一次AI）→ 批量写入 → 每个课时异步向量化
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateCourseChapters(Long courseId, Long userId, boolean isAdmin,
                                                      Integer chapterCount, String difficulty, String notes) {
        if (!canAccessCourse(courseId, userId, isAdmin)) {
            throw new RuntimeException("无权限操作该课程");
        }
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new RuntimeException("课程不存在");
        }
        // 参数校验：章节数 1-20，难度限三档，备注最长 500 字
        if (chapterCount != null && (chapterCount < 1 || chapterCount > 20)) {
            throw new RuntimeException("章节数量需在 1-20 之间");
        }
        if (StringUtils.hasText(difficulty)) {
            if (!"入门".equals(difficulty) && !"基础".equals(difficulty) && !"进阶".equals(difficulty)) {
                throw new RuntimeException("难度仅支持：入门 / 基础 / 进阶");
            }
        } else {
            difficulty = null;
        }
        if (notes != null) {
            notes = notes.trim();
            if (notes.length() > 500) {
                throw new RuntimeException("备注内容不能超过 500 字");
            }
            if (notes.isEmpty()) notes = null;
        }
        String courseName = course.getCourseName();

        // 1. AI 规划章节结构 + 生成课时内容（一次调用返回全部 JSON）
        String plan = generatePlan(courseName, chapterCount, difficulty, notes);
        if (plan == null || plan.trim().isEmpty()) {
            throw new RuntimeException("AI 生成失败，无返回内容");
        }
        plan = stripJsonMarkers(plan);
        if (plan == null || plan.trim().isEmpty()) {
            System.out.println("=== AI 生成章节解析失败: courseName=" + courseName + ", 未找到合法 JSON 结构");
            throw new RuntimeException("AI 返回内容中未找到合法 JSON 结构（模型可能只输出了思考文字），请重试");
        }

        // 2. 解析 JSON 为 chapters 列表（先标准解析，失败再单引号容错转换后重试）
        List<Map<String, Object>> chapters;
        try {
            chapters = parseChapterJson(plan);
        } catch (Exception e) {
            System.out.println("=== AI 生成章节解析失败: courseName=" + courseName + ", error=" + e.getMessage());
            throw new RuntimeException("AI 返回格式解析失败：" + e.getMessage());
        }
        if (chapters == null || chapters.isEmpty()) {
            throw new RuntimeException("AI 未返回任何章节");
        }
        System.out.println("=== AI 生成章节解析成功: chapters=" + chapters.size());
        for (int i = 0; i < chapters.size(); i++) {
            Map<String, Object> ch = chapters.get(i);
            Object less = ch.getOrDefault("lessons", ch.get("items"));
            int lessSize = less instanceof List ? ((List<?>) less).size() : 0;
            System.out.println("=== AI 生成章节结构: idx=" + i + ", chapterNo=" + ch.get("chapterNo")
                    + ", chapterName=" + ch.get("chapterName") + ", lessons=" + lessSize);
        }

        // 3. 已存在章节则跳过（课程下已有章节且非空，提示先清空）
        Long existCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_chapter WHERE course_id = ? AND deleted = 0",
                Long.class, courseId);
        System.out.println("=== AI 生成章节已存在检查: courseId=" + courseId + ", existCount=" + existCount);
        if (existCount != null && existCount > 0) {
            throw new RuntimeException("该课程已存在 " + existCount + " 个章节，请先删除后再生成");
        }

        // 4. 按章节序遍历，逐章写入
        int writtenChapters = 0;
        int lessonCount = 0;
        int lessonNoCursor = 0;
        for (int ci = 0; ci < chapters.size(); ci++) {
            Map<String, Object> ch = chapters.get(ci);
            Number chNoNum = (Number) ch.getOrDefault("chapterNo", ch.get("no"));
            if (chNoNum == null) chNoNum = ci + 1;
            int chapterNo = chNoNum.intValue();
            String chapterName = (String) ch.getOrDefault("chapterName", ch.get("name"));
            if (!StringUtils.hasText(chapterName)) chapterName = "第" + chapterNo + "章";
            String description = (String) ch.getOrDefault("description", "");

            CourseChapter chapter = new CourseChapter();
            chapter.setCourseId(courseId);
            chapter.setChapterNo(chapterNo);
            chapter.setChapterName(chapterName.trim());
            chapter.setDescription(description != null ? description.trim() : "");
            chapter.setSortOrder(chapterNo);
            chapter.setStatus(1);
            chapterMapper.insert(chapter);
            writtenChapters++;

            // 5. 写入课时
            Object lessListObj = ch.getOrDefault("lessons", ch.get("items"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lessList = lessListObj instanceof List ? (List<Map<String, Object>>) lessListObj : java.util.Collections.emptyList();
            for (int li = 0; li < lessList.size(); li++) {
                Map<String, Object> ls = lessList.get(li);
                Number lsNoNum = (Number) ls.getOrDefault("lessonNo", ls.get("no"));
                int lessonNo = lsNoNum != null ? lsNoNum.intValue() : li + 1;
                String lessonName = (String) ls.getOrDefault("lessonName", ls.get("name"));
                if (!StringUtils.hasText(lessonName)) lessonName = "第" + lessonNo + "节";
                String content = (String) ls.getOrDefault("content", "");
                if (content == null) content = "";
                content = content.trim();

                CourseLesson lesson = new CourseLesson();
                lesson.setChapterId(chapter.getId());
                lesson.setLessonNo(lessonNo);
                lesson.setLessonName(lessonName.trim());
                lesson.setResourceType("document");
                lesson.setContent(content);
                lesson.setSortOrder(lessonNo);
                lesson.setStatus(1);
                lessonMapper.insert(lesson);
                lessonCount++;

                // 6. 异步向量化（content 非空）
                if (StringUtils.hasText(content)) {
                    vectorizeLessonAsync(courseId, chapter.getId(), lesson.getId(), lesson.getLessonName(), content, true);
                }
            }
        }

        System.out.println("=== AI 生成章节写入完成: courseId=" + courseId + ", chapterCount=" + writtenChapters + ", lessonCount=" + lessonCount);
        return Map.of("chapterCount", writtenChapters, "lessonCount", lessonCount);
    }

    /**
     * 调用 AI 规划章节结构+内容，返回 JSON 数组
     * JSON 格式：[{chapterNo, chapterName, description, lessons:[{lessonNo, lessonName, content}]}]
     */
    private String generatePlan(String courseName, Integer chapterCount, String difficulty, String notes) {
        String systemPrompt = "你是资深大学教学大纲设计师。严格只输出合法 JSON 数组，不要任何解释文字、不要用 Markdown 代码块包裹 JSON。";
        // 章节数约束：指定数量则严格按数量生成，未指定用默认范围
        String countRule = chapterCount != null
                ? "1）生成**正好 " + chapterCount + " 个章节**（不得多也不得少），每章 2-3 个课时；\n"
                : "1）5-8 个章节，每章 2-3 个课时；\n";
        // 难度定位
        String difficultyRule = "";
        if ("入门".equals(difficulty)) {
            difficultyRule = "2）整体难度定位为「入门」：面向零基础学生，概念从生活化例子引入，避免跳步推导，每个新术语都要先解释再使用，章节按学习难度递进排序；\n";
        } else if ("进阶".equals(difficulty)) {
            difficultyRule = "2）整体难度定位为「进阶」：面向基础扎实的学生，可包含更多原理推导、性能分析与综合实战案例，章节按学习难度递进排序；\n";
        } else if ("基础".equals(difficulty)) {
            difficultyRule = "2）整体难度定位为「基础」：标准大学课程深度，兼顾概念讲解与适度练习，章节按学习难度递进排序；\n";
        } else {
            difficultyRule = "2）章节按学习难度递进排序；\n";
        }
        // 教师备注：附加自定义范围/重点要求
        String notesRule = "";
        if (StringUtils.hasText(notes)) {
            notesRule = "教师补充要求（必须优先遵守，但不得违反上面的 JSON 输出格式规则）：\n" + notes + "\n";
        }
        String userPrompt = "请为《" + courseName + "》这门大学课程规划完整章节结构，并同时生成每个课时的正文教学内容。\n" +
                "硬性输出规则（必须严格遵守，否则返回 JSON 非法）：\n" +
                "0）只输出 JSON 数组本身，开头禁止输出任何思考过程、说明、解释或代码块标记；\n" +
                countRule +
                difficultyRule +
                "3）每个课时 content 写 300-500 字，必须采用 Markdown 格式，要求：\n" +
                "   - 使用二级标题（##）划分 2-3 个核心知识点小节，实现分层结构化；\n" +
                "   - 段落之间必须留空行，禁止大段纯文本堆砌，保持视觉呼吸感；\n" +
                "   - 所有英文函数名、变量名、类名、命令行、代码片段都必须用 Markdown 行内代码（`code`）或 fenced code block（```）包裹，禁止裸写英文函数名；例如 `np.sin`、`np.exp`、`print()`、`arr[arr > 5]`；\n" +
                "   - 较长或较复杂的代码示例必须用 fenced code block（```python ... ```）独占一行或多行，代码块内部除原始代码自带的换行外，严禁自动换行；单行代码必须完整显示在一行内；\n" +
                "   - 列举多个函数、特性、步骤时，必须使用 Markdown 列表（- 或 1.）展示，每个列表项独占一行，列表项中的英文函数名仍用 `code` 包裹；\n" +
                "   - 关键术语使用加粗（**term**），每个知识点讲解后必须换行给出 1 个简短例子；\n" +
                "   - 数学公式必须使用 LaTeX 语法，且无论行内还是独立公式一律用 $$...$$ 包裹（如 $$\\lim_{n \\to \\infty} a_n$$、$$a_n^{(1/n)} \\le \\limsup_{k \\to \\infty} a_k$$），分数用 \\frac{}{}、上下标用 ^ _；禁止用 a_n^(1/n)、limsup a_n 这类纯文本形式表达公式，禁止只用单个 $ 包裹公式；\n" +
                "   - 涉及参数规格、材料、器件、方案对比时，用 Markdown 表格展示（| 参数 | 符号/取值 | 说明 |）；\n" +
                "   - 涉及工艺流程、系统结构、控制逻辑、信号流向时，用 ```mermaid 代码块（flowchart TD 或 graph LR 语法）描述，每个章节至少包含 1 个 mermaid 图，节点文字用简洁中文；\n" +
                "   - 注意事项、易错点、安全要求用引用块单独成段（格式：> ⚠️ **注意**：内容）；\n" +
                "4）JSON 语法硬性要求：所有键名（如 chapterNo、chapterName、lessonNo、lessonName、content）和所有字符串值**必须且只能**使用英文双引号（\"）包裹，**绝对禁止使用单引号（'）**作为 JSON 分隔符；单引号只能作为普通字符出现在 content 代码片段内部（如 print('hi')）；\n" +
                "5）content 中绝对禁止出现未转义的英文双引号（\"）；如需引号请用中文「」或确保 JSON 转义；禁止出现未转义的反斜杠（\\）；\n" +
                "6）content 里如果需要引用代码变量名如 JAVA_HOME，写成 `JAVA_HOME` 或纯文字 JAVA_HOME，不要加英文引号包裹；\n" +
                "7）只输出合法 JSON 数组，格式示例：\n" +
                "[{\"chapterNo\":1,\"chapterName\":\"章节名\",\"description\":\"简述\",\"lessons\":[{\"lessonNo\":1,\"lessonName\":\"课时名\",\"content\":\"## 知识点一\\n\\n概念说明...\\n\\n例子：...\\n\\n```python\\nprint('hello')\\n```\"}]}]\n" +
                notesRule;
        System.out.println("=== AI 生成章节请求: courseName=" + courseName);
        // 章节+课时正文一次性生成内容量大（上万 token），需用更大的输出预算，避免模型先输出思考文字后被截断
        String raw = llmService.chat(systemPrompt, userPrompt, 24000);
        System.out.println("=== AI 生成章节原始响应长度=" + (raw == null ? 0 : raw.length()));
        if (raw != null && raw.length() > 200) {
            System.out.println("=== AI 生成章节原始响应前200字=" + raw.substring(0, 200));
        } else if (raw != null) {
            System.out.println("=== AI 生成章节原始响应=" + raw);
        }
        if (raw == null) return null;
        // 不要在提取 JSON 前硬截断：模型可能先输出大段思考文字，提前截断会把末尾的 JSON 内容切掉。
        // 长度限制（60000）推迟到 stripJsonMarkers 提取出干净的 JSON 主体之后再执行。
        return raw;
    }

    /**
     * 容错清洗：把 JSON 字符串值内部残留的未转义英文 " 替换为「」。
     * 仅允许在标准解析失败后的兜底路径调用，且输入必须是已剥去推理文字/代码块包裹的 JSON 主体。
     *
     * 通过转义感知的状态机保证：
     *  - 键名与结构引号原样保留，绝不误改；
     *  - 已转义的 \" 不会被破坏（避免产生非法的 \「 转义）；
     *  - 只有位于字符串值内部、且后面不是结构符（: , } ] 换行或行尾）的 " 才会被替换。
     */
    private String sanitizeJsonQuotes(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        boolean inString = false;   // 是否处于 JSON 字符串内部
        boolean escaped = false;    // 当前字符被反斜杠转义
        boolean afterColon = false; // 刚遇到冒号，下一个字符串是值（非键）
        int valueDepth = 0;         // 值内替换引号的「/」交替计数
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    sb.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    sb.append(c);
                    escaped = true;
                } else if (c == '"') {
                    // 判断是字符串真正的结束引号，还是值内容内部残留的引号
                    int j = i + 1;
                    while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                    char next = j < s.length() ? s.charAt(j) : '\0';
                    boolean isClose = (next == ':' || next == ',' || next == '}' || next == ']'
                            || next == '\n' || next == '\r' || j == s.length());
                    if (isClose) {
                        inString = false;
                        sb.append(c);
                        if (next == ':') {
                            afterColon = true;
                        } else if (next == ',' || next == '}' || next == ']') {
                            afterColon = false;
                        }
                    } else {
                        // 字符串值内部残留的未转义 "，替换为「/」交替
                        sb.append(valueDepth % 2 == 0 ? '「' : '」');
                        valueDepth++;
                    }
                } else if (c == '\n') {
                    // 字符串值内出现真实换行（非法 JSON），转义为 \n
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                    sb.append(c);
                } else if (c == ':') {
                    afterColon = true;
                    sb.append(c);
                } else if (c == ',' || c == '{' || c == '}' || c == '[' || c == ']') {
                    afterColon = false;
                    sb.append(c);
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 剥去 AI 可能输出的 ```json ... ``` 包裹及前置思考/说明文字，并补齐截断的 JSON 尾部。
     * 返回的字符串以 [ 或 { 开头，是真正的 JSON 主体。
     *
     * @return 干净的 JSON 主体；如果完全找不到 JSON 结构（模型只输出了纯思考文字）返回 null
     */
    private String stripJsonMarkers(String s) {
        if (s == null) return null;
        s = stripCodeFence(s);
        int start = findJsonStart(s);
        if (start < 0) {
            // 完全找不到 JSON 结构，返回 null 让上层给出明确错误
            return null;
        }
        char open = s.charAt(start);
        char close = (open == '[') ? ']' : '}';
        int end = s.lastIndexOf(close);
        String body;
        if (end < start) {
            // 只有起始、没有闭合（被截断），从起始位置起尝试补齐
            body = completeTruncatedJson(s.substring(start));
        } else {
            body = completeTruncatedJson(s.substring(start, end + 1));
        }
        // 模型可能只输出了单个 JSON 对象而非数组，包一层数组以满足章节列表结构
        if (open == '{') {
            body = "[" + body + "]";
        }
        // 此时已是干净的 JSON 主体，再做单次生成的长度限制
        if (body.length() > 60000) body = body.substring(0, 60000);
        return body;
    }

    /**
     * 剥离 Markdown 代码块围栏（```json / ``` / ~~~），只处理“整体包裹”的情况。
     */
    private String stripCodeFence(String s) {
        String t = s.trim();
        String fence = null;
        for (String f : new String[]{"```json", "```", "~~~json", "~~~"}) {
            if (t.startsWith(f)) { fence = f; break; }
        }
        if (fence == null) return s;
        int nl = t.indexOf('\n');
        if (nl < 0) {
            // 只有一个围栏标记、没有换行，去掉它
            String inner = t.substring(fence.length()).trim();
            return inner.isEmpty() ? s : inner;
        }
        String inner = t.substring(nl + 1).trim();
        // 去掉结尾围栏行
        int cut = -1;
        for (String f : new String[]{"```", "~~~"}) {
            int idx = inner.lastIndexOf(f);
            if (idx > cut) cut = idx;
        }
        if (cut >= 0) {
            inner = inner.substring(0, cut).trim();
        }
        return inner;
    }

    /**
     * 找到真正 JSON 主体的起始位置（[ 或 {）。
     *
     * 模型经常在 JSON 之前输出大段“复读提示词 + 思考”的文字，这些文字里也可能出现独立的
     * [ 或 {（例如复述 prompt 中的格式示例）。因此这里不能只取“第一个 [ 后跟 {”，而是：
     *  1) 收集所有“疑似 JSON 起始”的候选位置（数组 [ 后允许跟 { [ ] " 或数字；对象 { 后允许跟 " 或 }）；
     *  2) 从最靠后的候选开始向前验证：能一直走到字符串末尾、括号深度归零、且深度归零后仅剩空白字符的
     *     候选，才认定为真正的 JSON 起始——模型总是在思考文字之后才输出 JSON，真正的 JSON 必然是最靠后的
     *     可闭合结构；
     *  3) 若没有候选能完美闭合（说明 JSON 被截断），退回使用最靠前的候选，交由 completeTruncatedJson 补齐。
     *
     * @return 起始下标；找不到任何候选时返回 -1
     */
    private int findJsonStart(String s) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '[' && c != '{') continue;
            int j = i + 1;
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
            char n = j < s.length() ? s.charAt(j) : '\0';
            if (c == '[') {
                if (n == '{' || n == '[' || n == ']' || n == '"' || Character.isDigit(n)) {
                    candidates.add(i);
                }
            } else {
                if (n == '"' || n == '}') {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) return -1;
        // 从最靠后往前，找能完美闭合到末尾的候选
        for (int idx = candidates.size() - 1; idx >= 0; idx--) {
            if (isCleanJsonToEnd(s, candidates.get(idx))) {
                return candidates.get(idx);
            }
        }
        // 都没有完美闭合（可能是截断），退回最靠前的候选作为最佳猜测
        return candidates.get(0);
    }

    /**
     * 从 start 开始做一次括号平衡扫描，判断它是否是响应末尾的完整 JSON 主体：
     *   - 括号深度全程不为负；
     *   - 深度最终归零；
     *   - 深度归零之后只剩空白字符（说明这个结构就是响应末尾的 JSON，而非思考文字里的示例片段）。
     * 扫描对字符串与转义敏感，因此 content 代码块内部的 [ { 不会被误判为 JSON 起始。
     */
    private boolean isCleanJsonToEnd(String s, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"': inStr = true; break;
                case '{': depth++; break;
                case '}': depth--; if (depth < 0) return false; break;
                case '[': depth++; break;
                case ']': depth--; if (depth < 0) return false; break;
                default:
                    if (depth == 0 && !Character.isWhitespace(c)) {
                        // 深度已归零但后面还有非空白字符 -> 这是思考文字里的片段，不是末尾的 JSON
                        return false;
                    }
            }
        }
        return depth == 0 && !inStr;
    }

    /**
     * 补齐 JSON 字符串末尾缺失的 } 和 ]，按计数法闭合。
     * 处理策略：遇到数组/对象内部字符串截断时，先闭合最近的字符串，再逐层补括号。
     */
    private String completeTruncatedJson(String s) {
        if (s == null) return null;
        int objDepth = 0;
        int arrDepth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"': inStr = true; break;
                case '{': objDepth++; break;
                case '}': if (objDepth > 0) objDepth--; break;
                case '[': arrDepth++; break;
                case ']': if (arrDepth > 0) arrDepth--; break;
            }
        }
        StringBuilder sb = new StringBuilder(s);
        // 若还在字符串内，先闭合字符串
        if (inStr) sb.append('"');
        // 逐层补对象/数组括号
        while (objDepth-- > 0) sb.append('}');
        while (arrDepth-- > 0) sb.append(']');
        return sb.toString();
    }

    /**
     * 解析 AI 返回的章节 JSON 数组。
     * 标准 Jackson 解析失败时，先尝试把 Python 风格的单引号 JSON 转换为标准双引号 JSON，再解析。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseChapterJson(String plan) {
        try {
            return objectMapper.readValue(plan,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            // 容错：模型可能输出了单引号包裹的键/值（Python dict 风格）
            String repaired = repairSingleQuoteJson(plan);
            String cleaned = sanitizeJsonQuotes(repaired);
            System.out.println("=== AI 生成章节单引号容错: 转换后长度=" + cleaned.length());
            try {
                return objectMapper.readValue(cleaned,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            } catch (Exception e2) {
                throw new RuntimeException("单引号容错转换后仍解析失败：" + e2.getMessage(), e2);
            }
        }
    }

    /**
     * 把 Python 风格的单引号 JSON 转换为标准双引号 JSON。
     * 状态机：
     *  - 已处于合法双引号字符串内时，原样保留（content 代码块内的单引号不会被误伤）；
     *  - 双引号字符串之外遇到的单引号，视为 JSON 字符串分隔符，转换为双引号；
     *  - 转换后的字符串内部，单引号若后面紧跟 : , } ] 或行尾才视为闭合分隔符；
     *    否则（如 print('hi') 中的引号）保留为普通字符。
     */
    private String repairSingleQuoteJson(String s) {
        if (s == null) return s;
        StringBuilder sb = new StringBuilder(s.length() + 64);
        boolean inDouble = false; // 处于合法双引号字符串内
        boolean inSingle = false; // 处于待转换的单引号字符串内
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (inDouble) {
                sb.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }

            if (inSingle) {
                if (c == '\\') {
                    // 单引号字符串内的转义：\' 或 \" -> \"（因为外层改为双引号）
                    if (i + 1 < s.length()) {
                        char n = s.charAt(i + 1);
                        if (n == '\'' || n == '"') {
                            sb.append('\\').append('"');
                        } else {
                            sb.append('\\').append(n);
                        }
                        i++;
                    } else {
                        sb.append(c);
                    }
                    continue;
                }
                if (c == '\'') {
                    // 向后看，判断是否字符串结束（后面是结构符或行尾）
                    int j = i + 1;
                    while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                    char next = j < s.length() ? s.charAt(j) : '\0';
                    boolean isEnd = (next == ':' || next == ',' || next == '}' || next == ']'
                            || next == '\n' || next == '\r' || j == s.length());
                    if (isEnd) {
                        inSingle = false;
                        sb.append('"');
                    } else {
                        // 普通单引号字符（如 print('hi')），原样保留
                        sb.append('\'');
                    }
                } else {
                    sb.append(c);
                }
                continue;
            }

            // 双引号字符串之外
            if (c == '"') {
                inDouble = true;
                sb.append(c);
            } else if (c == '\'') {
                inSingle = true;
                sb.append('"');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
