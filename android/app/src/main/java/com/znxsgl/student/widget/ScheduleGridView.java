package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 课表内容区自绘网格。
 *
 * 相比「每节一行」的 LinearLayout，这里可以按天把同一门课在连续节次
 * （同一上午 / 下午，且中间没有午休、晚休等分隔行）合并成一张纵向卡片。
 */
public class ScheduleGridView extends View {

    /** 一行数据：正常节次或休息分隔行 */
    public static final class Row {
        public final int slotIndex; // 休息行为 -1
        public final String start;
        public final String end;
        public final String breakText;

        private Row(int slotIndex, String start, String end, String breakText) {
            this.slotIndex = slotIndex;
            this.start = start;
            this.end = end;
            this.breakText = breakText;
        }

        public static Row slot(int slotIndex, String start, String end) {
            return new Row(slotIndex, start, end, null);
        }

        public static Row breakRow(String text) {
            return new Row(-1, null, null, text);
        }

        public boolean isBreak() {
            return slotIndex < 0;
        }
    }

    /** 一个格子的课程信息，name + room 相同才允许合并 */
    public static final class CourseInfo {
        public final String name;
        public final String room;

        public CourseInfo(String name, String room) {
            this.name = name;
            this.room = room;
        }

        boolean sameAs(CourseInfo other) {
            return other != null
                    && TextUtils.equals(name, other.name)
                    && TextUtils.equals(room, other.room);
        }
    }

    private final float density;
    private final float scaledDensity;

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint roomPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timeEndPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint breakPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Paint cardFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint breakLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final Path path = new Path();

    private List<Row> rows = new ArrayList<>();
    private CourseInfo[][] courses = new CourseInfo[0][7];
    private int todayDayOfWeek = -1;
    private boolean todayInWeek;

    public ScheduleGridView(Context context) {
        this(context, null);
    }

    public ScheduleGridView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScheduleGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        namePaint.setTextSize(sp(11));
        namePaint.setColor(0xFF1D1D1F);

        roomPaint.setTextSize(sp(10));
        roomPaint.setColor(0xFF8E8E93);
        roomPaint.setTextAlign(Paint.Align.CENTER);

        timePaint.setTextSize(sp(10));
        timePaint.setColor(0xFF1D1D1F);
        timePaint.setTextAlign(Paint.Align.CENTER);

        timeEndPaint.setTextSize(sp(10));
        timeEndPaint.setColor(0xFF8E8E93);
        timeEndPaint.setTextAlign(Paint.Align.CENTER);

        breakPaint.setTextSize(sp(11));
        breakPaint.setColor(0xFF8E8E93);
        breakPaint.setTextAlign(Paint.Align.CENTER);

        cardFillPaint.setStyle(Paint.Style.FILL);
        cardFillPaint.setColor(0xFFFFFFFF);

        todayStrokePaint.setStyle(Paint.Style.STROKE);
        todayStrokePaint.setStrokeWidth(dp(2));
        todayStrokePaint.setColor(0xFFDCE9FF);

        timeFillPaint.setStyle(Paint.Style.FILL);
        timeFillPaint.setColor(0xFFF5F5F7);

        hairlinePaint.setStyle(Paint.Style.FILL);
        hairlinePaint.setColor(0xFFF0F0F3);

        breakLinePaint.setStyle(Paint.Style.FILL);
        breakLinePaint.setColor(0xFFD9D9DE);
    }

    /**
     * @param rows           节次 / 休息行的顺序
     * @param courses        [节次序号][周几 0-6] 的课程
     * @param todayDayOfWeek 今天周几（1-7），未知传 -1
     * @param todayInWeek    今天是否在当前显示的教学周内
     */
    public void setData(List<Row> rows, CourseInfo[][] courses,
                        int todayDayOfWeek, boolean todayInWeek) {
        this.rows = rows == null ? new ArrayList<>() : rows;
        this.courses = courses == null ? new CourseInfo[0][7] : courses;
        this.todayDayOfWeek = todayDayOfWeek;
        this.todayInWeek = todayInWeek;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (rows.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;

        int slotCount = 0;
        int breakCount = 0;
        for (Row row : rows) {
            if (row.isBreak()) breakCount++;
            else slotCount++;
        }
        if (slotCount == 0) return;

        float breakHeight = dp(26);
        float slotHeight = Math.max(dp(24),
                (getHeight() - breakCount * breakHeight) / slotCount);

        int rowCount = rows.size();
        float[] top = new float[rowCount];
        float[] bottom = new float[rowCount];
        int[] slotRowIndex = new int[slotCount];

        float y = 0;
        int slotRank = 0;
        for (int i = 0; i < rowCount; i++) {
            top[i] = y;
            float h = rows.get(i).isBreak() ? breakHeight : slotHeight;
            bottom[i] = y + h;
            if (!rows.get(i).isBreak()) {
                slotRowIndex[slotRank] = i;
                slotRank++;
            }
            y += h;
        }

        // 行分隔线：先画线，课程卡片随后覆盖在线上
        for (int i = 0; i < rowCount; i++) {
            canvas.drawRect(0, bottom[i] - dp(0.5f), getWidth(),
                    bottom[i] + dp(0.5f), hairlinePaint);
        }

        // 休息行
        for (int i = 0; i < rowCount; i++) {
            Row row = rows.get(i);
            if (row.isBreak()) drawBreakRow(canvas, top[i], bottom[i], row.breakText);
        }

        float timeWidth = dp(44);
        float contentLeft = dp(2);
        float contentRight = getWidth() - dp(2);
        float dayLeft = contentLeft + timeWidth;
        float dayWidth = (contentRight - dayLeft) / 7f;

        // 节次时间列
        for (int s = 0; s < slotCount; s++) {
            int rowIndex = slotRowIndex[s];
            rect.set(contentLeft, top[rowIndex] + dp(2),
                    contentLeft + timeWidth, bottom[rowIndex] - dp(2));
            if (rect.width() <= 0 || rect.height() <= 0) continue;
            G2Drawable.buildPath(rect, dp(10), path);
            canvas.drawPath(path, timeFillPaint);
            Row row = rows.get(rowIndex);
            drawTimeText(canvas, rect, row.start, row.end);
        }

        // 课程格子：同一门课连续节次合并为一张卡片
        for (int day = 0; day < 7; day++) {
            int s = 0;
            while (s < slotCount) {
                CourseInfo info = s < courses.length ? courses[s][day] : null;
                if (info == null) {
                    s++;
                    continue;
                }
                int end = s;
                while (end + 1 < slotCount
                        && end + 1 < courses.length
                        && info.sameAs(courses[end + 1][day])
                        && slotRowIndex[end + 1] == slotRowIndex[end] + 1) {
                    end++;
                }
                int startRow = slotRowIndex[s];
                int endRow = slotRowIndex[end];
                boolean isToday = todayInWeek && (day + 1) == todayDayOfWeek;
                drawCourseCard(canvas,
                        dayLeft + day * dayWidth,
                        top[startRow],
                        dayLeft + (day + 1) * dayWidth,
                        bottom[endRow],
                        info, isToday);
                s = end + 1;
            }
        }
    }

    private void drawTimeText(Canvas canvas, RectF r, String start, String end) {
        Paint.FontMetrics fm = timePaint.getFontMetrics();
        float lineHeight = fm.descent - fm.ascent;
        float total = lineHeight * 2 + dp(1);
        float baseline = r.centerY() - total / 2f - fm.ascent;
        canvas.drawText(start == null ? "" : start, r.centerX(), baseline, timePaint);
        canvas.drawText(end == null ? "" : end, r.centerX(),
                baseline + lineHeight + dp(1), timeEndPaint);
    }

    private void drawCourseCard(Canvas canvas, float left, float top, float right,
                                float bottom, CourseInfo info, boolean isToday) {
        rect.set(left + dp(2), top + dp(2), right - dp(2), bottom - dp(2));
        if (rect.width() <= 0 || rect.height() <= 0 || info == null) return;

        G2Drawable.buildPath(rect, dp(10), path);
        canvas.drawPath(path, cardFillPaint);
        if (isToday) canvas.drawPath(path, todayStrokePaint);

        canvas.save();
        canvas.clipPath(path);
        float maxWidth = Math.max(dp(10), rect.width() - dp(8));

        String name = info.name == null ? "" : info.name;
        StaticLayout nameLayout = StaticLayout.Builder
                .obtain(name, 0, name.length(), namePaint, (int) maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build();

        String room = info.room == null ? "" : info.room;
        boolean hasRoom = !room.isEmpty();
        float roomHeight = hasRoom ? (roomPaint.descent() - roomPaint.ascent()) : 0;
        float gap = hasRoom ? dp(2) : 0;
        float totalHeight = nameLayout.getHeight() + gap + roomHeight;
        float textTop = rect.centerY() - totalHeight / 2f;

        canvas.save();
        canvas.translate(rect.left + dp(4), textTop);
        nameLayout.draw(canvas);
        canvas.restore();

        if (hasRoom) {
            CharSequence roomText = TextUtils.ellipsize(room, roomPaint, maxWidth,
                    TextUtils.TruncateAt.END);
            float baseline = textTop + nameLayout.getHeight() + gap - roomPaint.ascent();
            canvas.drawText(roomText, 0, roomText.length(), rect.centerX(), baseline, roomPaint);
        }
        canvas.restore();
    }

    private void drawBreakRow(Canvas canvas, float top, float bottom, String text) {
        float centerY = (top + bottom) / 2f;
        float timeWidth = dp(44);
        float centerX = timeWidth + (getWidth() - timeWidth) / 2f;
        String label = text == null ? "" : text;
        float textWidth = breakPaint.measureText(label);
        float gap = dp(8);

        canvas.drawRect(dp(2), centerY - dp(0.5f),
                Math.max(dp(2), centerX - textWidth / 2f - gap),
                centerY + dp(0.5f), breakLinePaint);
        canvas.drawRect(Math.min(getWidth() - dp(2), centerX + textWidth / 2f + gap),
                centerY - dp(0.5f), getWidth() - dp(2),
                centerY + dp(0.5f), breakLinePaint);

        float baseline = centerY - (breakPaint.ascent() + breakPaint.descent()) / 2f;
        canvas.drawText(label, centerX, baseline, breakPaint);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * scaledDensity;
    }
}
