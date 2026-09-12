package com.znxsgl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.znxsgl.entity.BellSchedule;
import com.znxsgl.entity.GradeWeekParity;
import com.znxsgl.entity.Semester;
import com.znxsgl.mapper.BellScheduleMapper;
import com.znxsgl.mapper.GradeWeekParityMapper;
import com.znxsgl.mapper.SemesterMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 作息时间服务：根据 年级 + 日期 自动解析适用的作息表。
 *
 * 自动适配链路：
 *   日期 → 当前学期(semester.start_date) → 周次
 *        → 周次奇偶 → 年级映射(grade_week_parity) → 生效作息(单周表/双周表/通用表)
 *
 * 解析优先级（高→低）：
 *   (年级,奇偶) > (年级,通用0) > (默认'',奇偶) > (默认'',通用0)
 * 数据库无任何配置时返回空表，由调用方降级到各自的代码内置默认值。
 */
@Service
public class BellTimeService {

    public static final int PARITY_GENERIC = 0;
    public static final int PARITY_ODD = 1;   // 单周
    public static final int PARITY_EVEN = 2;  // 双周

    private final BellScheduleMapper bellScheduleMapper;
    private final GradeWeekParityMapper gradeWeekParityMapper;
    private final SemesterMapper semesterMapper;

    /** 小表缓存（60s TTL），避免高频查询 */
    private final Map<String, CachedBells> bellsCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> parityCache = new ConcurrentHashMap<>();
    private volatile long bellsLoadedAt = 0;
    private volatile long paritiesLoadedAt = 0;
    private static final long CACHE_TTL_MS = 60_000;

    public BellTimeService(BellScheduleMapper bellScheduleMapper,
                           GradeWeekParityMapper gradeWeekParityMapper,
                           SemesterMapper semesterMapper) {
        this.bellScheduleMapper = bellScheduleMapper;
        this.gradeWeekParityMapper = gradeWeekParityMapper;
        this.semesterMapper = semesterMapper;
    }

    private record CachedBells(List<BellSchedule> rows) {}

    /** 当前学期 */
    public Semester currentSemester() {
        Semester cur = semesterMapper.selectOne(new LambdaQueryWrapper<Semester>()
                .eq(Semester::getIsCurrent, 1).last("LIMIT 1"));
        if (cur == null) {
            cur = semesterMapper.selectOne(new LambdaQueryWrapper<Semester>()
                    .orderByDesc(Semester::getStartDate).last("LIMIT 1"));
        }
        return cur;
    }

    /** 日期在当前学期中的周次（1-based），无当前学期/早于开学返回 null */
    public Integer weekNumberOf(LocalDate date) {
        Semester sem = currentSemester();
        if (sem == null || sem.getStartDate() == null) return null;
        LocalDate start = sem.getStartDate();
        if (date.isBefore(start)) return null;
        long days = ChronoUnit.DAYS.between(start, date);
        return (int) (days / 7) + 1;
    }

    /** 年级奇数周作息映射（默认 1=单周表）；grade 为空返回默认值 */
    public int oddWeekParityOf(String grade) {
        String g = normalize(grade);
        if (!g.isEmpty()) {
            if (System.currentTimeMillis() - paritiesLoadedAt > CACHE_TTL_MS) {
                parityCache.clear();
                gradeWeekParityMapper.selectList(null)
                        .forEach(m -> parityCache.put(normalize(m.getGrade()), m.getOddWeekParity()));
                paritiesLoadedAt = System.currentTimeMillis();
            }
            Integer v = parityCache.get(g);
            if (v != null) return v;
        }
        return PARITY_ODD;
    }

    /** 指定年级在指定日期生效的作息奇偶（0=未知/无学期配置，调用方按通用表降级） */
    public int effectiveParity(String grade, LocalDate date) {
        Integer week = weekNumberOf(date);
        if (week == null) return PARITY_GENERIC;
        int oddParity = oddWeekParityOf(grade);
        if (week % 2 == 1) return oddParity;
        return oddParity == PARITY_ODD ? PARITY_EVEN : PARITY_ODD;
    }

    /**
     * 解析节点 → 起止时间映射（自动适配）。
     * @param grade  年级（可空=默认作息）
     * @param parity 1=单周 2=双周 0/null=通用表
     */
    public Map<Integer, LocalTime[]> getNodeTimes(String grade, Integer parity) {
        String g = normalize(grade);
        int p = (parity == null) ? PARITY_GENERIC : parity;
        List<BellSchedule> rows = loadBells();
        Map<Integer, Integer> priMap = new LinkedHashMap<>();
        Map<Integer, LocalTime[]> result = new LinkedHashMap<>();
        for (BellSchedule r : rows) {
            String rg = normalize(r.getGrade());
            boolean gradeOk = rg.isEmpty() || rg.equals(g);
            if (!gradeOk) continue;
            int rp = r.getWeekParity() == null ? 0 : r.getWeekParity();
            if (rp != p && rp != PARITY_GENERIC) continue;
            int pri = (rg.isEmpty() ? 0 : 2) + (rp == PARITY_GENERIC ? 0 : 1);
            Integer oldPri = priMap.get(r.getNode());
            if (oldPri == null || pri >= oldPri) {
                priMap.put(r.getNode(), pri);
                result.put(r.getNode(), new LocalTime[]{r.getStartTime(), r.getEndTime()});
            }
        }
        return result;
    }

    /** 某节次段的起止时间：[首节开始, 末节结束]；无配置返回 null（奇偶表缺失时回退通用表） */
    public LocalTime[] nodeRange(String grade, LocalDate date, int startNode, int step) {
        int lastNode = startNode + Math.max(step, 1) - 1;
        int parity = (date == null) ? PARITY_GENERIC : effectiveParity(grade, date);
        LocalTime[] first = null;
        LocalTime[] last = null;
        if (parity != PARITY_GENERIC) {
            Map<Integer, LocalTime[]> times = getNodeTimes(grade, parity);
            first = times.get(startNode);
            last = times.get(lastNode);
        }
        if (first == null || last == null) {
            Map<Integer, LocalTime[]> generic = getNodeTimes(grade, PARITY_GENERIC);
            if (first == null) first = generic.get(startNode);
            if (last == null) last = generic.get(lastNode);
        }
        if (first == null || last == null) return null;
        return new LocalTime[]{first[0], last[1]};
    }

    /** 按周次直接解析作息（调课等面向目标周的场景；weekNumber 为空按通用表） */
    public Map<Integer, LocalTime[]> getNodeTimesByWeek(String grade, Integer weekNumber) {
        int parity;
        if (weekNumber == null) {
            parity = PARITY_GENERIC;
        } else {
            int oddParity = oddWeekParityOf(grade);
            parity = (weekNumber % 2 == 1) ? oddParity : (oddParity == PARITY_ODD ? PARITY_EVEN : PARITY_ODD);
        }
        return getNodeTimes(grade, parity);
    }

    /** 按周次解析某节次段起止（无配置返回 null，回退通用表） */
    public LocalTime[] nodeRangeByWeek(String grade, Integer weekNumber, int startNode, int step) {
        int lastNode = startNode + Math.max(step, 1) - 1;
        int parity;
        if (weekNumber == null) {
            parity = PARITY_GENERIC;
        } else {
            int oddParity = oddWeekParityOf(grade);
            parity = (weekNumber % 2 == 1) ? oddParity : (oddParity == PARITY_ODD ? PARITY_EVEN : PARITY_ODD);
        }
        LocalTime[] first = getNodeTimes(grade, parity).get(startNode);
        LocalTime[] last = getNodeTimes(grade, parity).get(lastNode);
        if (first == null || last == null) {
            Map<Integer, LocalTime[]> generic = getNodeTimes(grade, PARITY_GENERIC);
            if (first == null) first = generic.get(startNode);
            if (last == null) last = generic.get(lastNode);
        }
        if (first == null || last == null) return null;
        return new LocalTime[]{first[0], last[1]};
    }

    /** 某节次开始时间（无配置返回 null） */
    public LocalTime nodeStartTime(String grade, LocalDate date, int node) {
        LocalTime[] r = nodeRange(grade, date, node, 1);
        return r == null ? null : r[0];
    }

    /** 某节次结束时间（无配置返回 null） */
    public LocalTime nodeEndTime(String grade, LocalDate date, int node) {
        LocalTime[] r = nodeRange(grade, date, node, 1);
        return r == null ? null : r[1];
    }

    /** 周次 → 作息奇偶（week 为空按今天解析） */
    public int parityByWeek(String grade, Integer week) {
        if (week == null) return effectiveParity(grade, LocalDate.now());
        int odd = oddWeekParityOf(grade);
        return (week % 2 == 1) ? odd : (odd == PARITY_ODD ? PARITY_EVEN : PARITY_ODD);
    }

    /** 输出某奇偶下的作息列表（按节次升序），奇偶表为空时回退通用表 */
    public List<Map<String, Object>> bellsByParity(String grade, int parity) {
        Map<Integer, LocalTime[]> resolved = getNodeTimes(grade, parity);
        if (resolved.isEmpty()) resolved = getNodeTimes(grade, PARITY_GENERIC);
        final Map<Integer, LocalTime[]> times = resolved;
        List<Map<String, Object>> list = new ArrayList<>();
        times.keySet().stream().sorted().forEach(node -> {
            LocalTime[] t = times.get(node);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", node);
            m.put("startTime", t[0] == null ? null : t[0].toString());
            m.put("endTime", t[1] == null ? null : t[1].toString());
            list.add(m);
        });
        return list;
    }

    /** 今日作息（供 App / 管理端展示），按节次升序；奇偶表为空时回退通用表 */
    public List<Map<String, Object>> todayBells(String grade) {
        return bellsByParity(grade, effectiveParity(grade, LocalDate.now()));
    }

    /** 指定周次作息（周视图；week 为空按今天），单双周自动适配 */
    public List<Map<String, Object>> bellsForWeek(String grade, Integer week) {
        return bellsByParity(grade, parityByWeek(grade, week));
    }

    private String normalize(String grade) {
        return (grade == null) ? "" : grade.trim();
    }

    private List<BellSchedule> loadBells() {
        long now = System.currentTimeMillis();
        if (now - bellsLoadedAt > CACHE_TTL_MS) {
            List<BellSchedule> rows = bellScheduleMapper.selectList(null);
            bellsCache.clear();
            bellsCache.put("all", new CachedBells(rows));
            bellsLoadedAt = now;
        }
        CachedBells c = bellsCache.get("all");
        return c == null ? List.of() : c.rows();
    }
}
