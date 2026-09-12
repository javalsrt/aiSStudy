package com.znxsgl.student.network;

import com.znxsgl.student.model.Chapter;
import com.znxsgl.student.model.ChatMsgDto;
import com.znxsgl.student.model.LoginRequest;
import com.znxsgl.student.model.LoginResponse;
import com.znxsgl.student.model.ScheduleItem;
import com.znxsgl.student.model.StudentCourse;

import java.util.List;
import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {

    @POST("/api/auth/login")
    Call<LoginResponse> login(@Body LoginRequest request);

    @GET("/api/schedule/student/courses")
    Call<List<StudentCourse>> getStudentCourses(
            @Header("Authorization") String token);

    @GET("/api/schedule/student/my")
    Call<List<ScheduleItem>> getStudentSchedule(
            @Header("Authorization") String token,
            @Query("week") int week,
            @Query("semester") String semester);

    @GET("/api/schedule/student/semesters")
    Call<List<Map<String, Object>>> getStudentSemesters(
            @Header("Authorization") String token);

    /** 作息表（服务端按 登录用户年级+周次单双周 自动解析），week 为空按今天 */
    @GET("/api/bell/today")
    Call<Map<String, Object>> getBellToday(
            @Header("Authorization") String token,
            @Query("week") Integer week);

    /**
     * 学生：查看通讯录（同班同学 + 教我的老师）
     */
    @GET("/api/schedule/student/contacts")
    Call<Map<String, Object>> getStudentContacts(
            @Header("Authorization") String token);

    // 聊天
    @GET("/api/chat/{courseName}")
    Call<List<ChatMsgDto>> getChatMessages(
            @Header("Authorization") String token,
            @Path("courseName") String courseName);

    @POST("/api/chat/send")
    Call<ChatMsgDto> sendChatMessage(
            @Header("Authorization") String token,
            @Body Map<String, String> body);

    @POST("/api/chat/read")
    Call<Map<String, String>> markAsRead(
            @Header("Authorization") String token,
            @Body Map<String, String> body);

    @GET("/api/chat/unread")
    Call<List<Map<String, Object>>> getUnreadChatCount(
            @Header("Authorization") String token);

    @POST("/api/chat/rag")
    Call<ChatMsgDto> ragChat(
            @Header("Authorization") String token,
            @Body Map<String, String> body);

    @Multipart
    @POST("/api/chat/upload")
    Call<ChatMsgDto> uploadFile(
            @Header("Authorization") String token,
            @Part MultipartBody.Part file,
            @Part("courseName") RequestBody courseName);

    @Multipart
    @POST("/api/chat/upload-file")
    Call<Map<String, String>> uploadChatFile(
            @Header("Authorization") String token,
            @Part MultipartBody.Part file,
            @Part("courseName") RequestBody courseName);

    // 专注模式
    @POST("/api/focus/save")
    Call<Map<String, Object>> saveFocus(
            @Header("Authorization") String token,
            @Body Map<String, Object> body);

    @GET("/api/focus/today")
    Call<Map<String, Object>> getFocusToday(
            @Header("Authorization") String token);

    @GET("/api/focus/total")
    Call<Map<String, Object>> getFocusTotal(
            @Header("Authorization") String token);

    @POST("/api/focus/status")
    Call<Map<String, Object>> updateFocusStatus(
            @Header("Authorization") String token,
            @Body Map<String, String> body);

    @GET("/api/focus/students/{classId}")
    Call<List<Map<String, Object>>> getStudentFocusStatus(
            @Header("Authorization") String token,
            @Path("classId") long classId);

    @GET("/api/focus/last")
    Call<Map<String, Object>> getLastFocus(
            @Header("Authorization") String token);

    // 答题系统
    @POST("/api/quiz/generate")
    Call<Map<String, Object>> generateQuiz(
            @Header("Authorization") String token,
            @Body Map<String, Object> body);

    // 异步出题结果查询（轮询）
    @GET("/api/quiz/generate/result")
    Call<Map<String, Object>> getQuizResult(
            @Header("Authorization") String token,
            @Query("taskId") String taskId);

    @GET("/api/course-chapter/course/{courseId}/chapters")
    Call<List<Chapter>> getCourseChapters(
            @Header("Authorization") String token,
            @Path("courseId") long courseId);

    @POST("/api/chapter-progress/complete")
    Call<ResponseBody> markLessonComplete(
            @Header("Authorization") String token,
            @Body Map<String, Object> body);

    @GET("/api/chapter-progress/completed")
    Call<ResponseBody> getCompletedLessons(
            @Header("Authorization") String token,
            @Query("courseId") long courseId);

    @POST("/api/quiz/evaluate")
    Call<Map<String, Object>> evaluateQuiz(
            @Header("Authorization") String token,
            @Body Map<String, Object> body);

    @POST("/api/quiz/wrong-analysis")
    Call<Map<String, Object>> getWrongAnalysis(
            @Header("Authorization") String token,
            @Body Map<String, String> body);

    @POST("/api/quiz/toggle-bookmark")
    Call<Map<String, Object>> toggleBookmark(
            @Header("Authorization") String token,
            @Body Map<String, String> body);

    @POST("/api/quiz/mark-understood")
    Call<Map<String, Object>> markUnderstood(
            @Header("Authorization") String token,
            @Body Map<String, String> body);

    @GET("/api/quiz/bookmarks")
    Call<List<Map<String, Object>>> getBookmarks(
            @Header("Authorization") String token);

    /** 累计答题统计：完成题目数、正确题目数、正确率 */
    @GET("/api/quiz/stats")
    Call<Map<String, Object>> getQuizStats(
            @Header("Authorization") String token);

    /** 复习加强：获取薄弱点、学习计划及推荐章节 */
    @GET("/api/quiz/review-plan")
    Call<Map<String, Object>> getReviewPlan(
            @Header("Authorization") String token);

    // 考试作业
    @GET("/api/exam-homework/student/{id}")
    Call<Map<String, Object>> getExamHomeworkPaper(
            @Header("Authorization") String token,
            @Path("id") long id);

    @POST("/api/exam-homework/student/{id}/start")
    Call<Map<String, Object>> startExamHomework(
            @Header("Authorization") String token,
            @Path("id") long id);

    @POST("/api/exam-homework/student/{id}/save-progress")
    Call<Map<String, Object>> saveExamHomeworkProgress(
            @Header("Authorization") String token,
            @Path("id") long id,
            @Body Map<String, Object> body);

    @POST("/api/exam-homework/student/{id}/submit")
    Call<Map<String, Object>> submitExamHomework(
            @Header("Authorization") String token,
            @Path("id") long id,
            @Body Map<String, Object> body);

    @GET("/api/exam-homework/student/{id}/result")
    Call<Map<String, Object>> getExamHomeworkResult(
            @Header("Authorization") String token,
            @Path("id") long id);

    @GET("/api/exam-homework/student/course-todos")
    Call<List<Map<String, Object>>> getExamHomeworkTodos(
            @Header("Authorization") String token);
}
