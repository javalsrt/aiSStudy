import { useEffect, useState } from 'react'
import { Select } from '@/components/ui/select'
import {
  Users,
  Clock,
  Award,
  Activity,
  ArrowLeft,
  ArrowRight,
  ChevronDown,
} from 'lucide-react'
import {
  ResponsiveContainer,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  BarChart,
  Bar,
  Cell,
} from 'recharts'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { MetricCard } from '@/components/ui/metric-card'
import { Pagination } from '@/components/ui/pagination'
import { SegmentedControl } from '@/components/ui/segmented-control'
import { Skeleton } from '@/components/ui/skeleton'
import { useRole } from '@/hooks/use-role'
import { useThemeStore } from '@/store/theme'
import { getTeacherActivity, getTeacherStats, getTeacherTrend } from '@/api/dashboard'
import {
  ACTIVITY_LEVEL_COLORS,
  DEMO_CHANGES,
  TREND_PERIODS,
  buildTrendSeries,
  type ActivityPoint,
  type TrendPeriod,
} from '@/lib/dashboard-insights'
import type { ActivityItem, TeacherStats, TrendItem } from '@/types'

/**
 * 数据总览配色：统一使用主题蓝（blue-400 / blue-500），
 * 涨跌标签正数用蓝色，负数保留语义红。
 */
const CHART_BLUE = '#60a5fa'
const CHART_BLUE_ACTIVE = '#3b82f6'

/** 秒 → 学习时长文案：满 1 小时显示小时，否则显示分钟，避免出现“0 小时” */
const formatStudyTime = (seconds: number) => {
  const minutes = Math.floor(seconds / 60)
  return minutes >= 60 ? `${Math.floor(minutes / 60)} 小时` : `${minutes} 分钟`
}

export function DashboardPage() {
  const { isAdmin } = useRole()
  const isDark = useThemeStore((state) => state.theme === 'dark')
  // 图表辅助色跟随主题（Recharts 需要具体色值，无法直接吃 CSS 变量）
  const chartGrid = isDark ? '#2e2e2e' : '#ebebeb'
  const chartTick = isDark ? '#737373' : '#a3a3a3'
  const chartTip = isDark
    ? { border: '1px solid #2e2e2e', background: '#1a1a1a' }
    : { border: '1px solid #ebebeb', background: '#ffffff' }
  const chartCursor = isDark ? '#242424' : '#f7f7f7'
  // 热力图色阶：0 级在暗底上改用深灰，避免整片发亮
  const activityColors = isDark
    ? ['#2e2e2e', '#1e3a8a', '#1d4ed8', '#3b82f6', '#93c5fd']
    : ACTIVITY_LEVEL_COLORS
  const [stats, setStats] = useState<TeacherStats | null>(null)
  const [trend, setTrend] = useState<TrendItem[]>([])
  const [activity, setActivity] = useState<ActivityItem[]>([])
  const [period, setPeriod] = useState<TrendPeriod>('7d')
  const [studentPage, setStudentPage] = useState(1)
  // 优秀学生面板：按累计/今日时长排序，每页 4 人
  const [topMetric, setTopMetric] = useState<'total' | 'today'>('total')
  const [topPage, setTopPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    const fetchData = async () => {
      setLoading(true)
      setError('')
      try {
        const [statsData, trendData, activityData] = await Promise.all([
          getTeacherStats(),
          getTeacherTrend(),
          // 接口尚未部署时不影响主数据加载，面板显示空态
          getTeacherActivity().catch(() => [] as ActivityItem[]),
        ])
        setStats(statsData)
        setTrend(trendData || [])
        setActivity(activityData || [])
      } catch (err: any) {
        setError(err.response?.data?.message || err.response?.data?.error || '数据加载失败')
      } finally {
        setLoading(false)
      }
    }
    fetchData()
  }, [])

  if (loading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-[26px] w-28" />
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Skeleton className="h-[268px] rounded-3xl" />
          <Skeleton className="h-[268px] rounded-3xl lg:col-span-2" />
        </div>
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <Skeleton className="h-[300px] rounded-3xl" />
          <Skeleton className="h-[300px] rounded-3xl" />
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => (
            <Skeleton key={index} className="h-[132px] rounded-3xl" />
          ))}
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center min-h-[400px]">
        <div className="text-danger">{error}</div>
      </div>
    )
  }

  const cards = isAdmin
    ? [
        { label: '学生总数', value: stats?.totalStudents ?? 0, icon: Users, change: DEMO_CHANGES.students },
        { label: '今日在线', value: stats?.onlineToday ?? 0, icon: Activity, change: DEMO_CHANGES.online },
        { label: '平均专注时长', value: `${stats?.avgFocusMinutes ?? 0} 分钟`, icon: Clock, change: DEMO_CHANGES.focus },
        { label: '答题正确率', value: `${stats?.quizAccuracy ?? 0}%`, icon: Award, change: DEMO_CHANGES.accuracy },
      ]
    : [
        { label: '授课学生', value: stats?.totalStudents ?? 0, icon: Users, change: DEMO_CHANGES.students },
        { label: '今日在线', value: stats?.onlineToday ?? 0, icon: Activity, change: DEMO_CHANGES.online },
        { label: '平均专注时长', value: `${stats?.avgFocusMinutes ?? 0} 分钟`, icon: Clock, change: DEMO_CHANGES.focus },
        { label: '答题正确率', value: `${stats?.quizAccuracy ?? 0}%`, icon: Award, change: DEMO_CHANGES.accuracy },
      ]

  const classRankingData = stats?.classFocusRanking || []
  const trendSeries = buildTrendSeries(trend, period)
  const tickInterval = period === '7d' ? 0 : period === '30d' ? 4 : 2
  // 面板头部的大数字：日均学习时长 / 班级平均时长（与 BoardUI 模板的指标标题结构一致）
  const avgDailyMinutes = trendSeries.length
    ? Math.round(trendSeries.reduce((sum, item) => sum + item.minutes, 0) / trendSeries.length)
    : 0
  const classAvgMinutes = classRankingData.length
    ? Math.round(
        classRankingData.reduce((sum, item) => sum + item.avgMinutes, 0) / classRankingData.length
      )
    : 0
  // 学生表分页：模板表格每页 10 条，避免一次性渲染上千行
  const studentPageSize = 10
  const studentTotal = stats?.students?.length ?? 0
  const studentTotalPages = Math.max(1, Math.ceil(studentTotal / studentPageSize))
  const currentStudentPage = Math.min(studentPage, studentTotalPages)
  const studentRows = (stats?.students ?? []).slice(
    (currentStudentPage - 1) * studentPageSize,
    currentStudentPage * studentPageSize
  )
  // 优秀学生：按学习时长降序，4 人一页，对应模板的 Recent hires 面板
  const topStudents = [...(stats?.students ?? [])].sort((a, b) =>
    topMetric === 'total' ? b.totalSeconds - a.totalSeconds : b.todaySeconds - a.todaySeconds
  )
  const topPageSize = 4
  const topTotalPages = Math.max(1, Math.ceil(topStudents.length / topPageSize))
  const currentTopPage = Math.min(topPage, topTotalPages)
  const topRows = topStudents.slice(
    (currentTopPage - 1) * topPageSize,
    currentTopPage * topPageSize
  )
  // 在线活跃度：后端返回今年逐日活跃学生（真实学习行为去重），按星期对齐成 7 行热力图
  const activityPeak = activity.reduce((max, item) => Math.max(max, item.activeUsers), 0)
  const activitySeries: ActivityPoint[] = activity.map((item) => {
    const [year, month, day] = item.date.split('-')
    const date = new Date(`${year}-${month}-${day}T00:00:00`)
    return {
      date: `${Number(month)}/${Number(day)}`,
      weekday: date.getDay(),
      count: item.activeUsers,
      level:
        item.activeUsers <= 0
          ? 0
          : Math.min(
              4,
              Math.max(1, Math.ceil((item.activeUsers / Math.max(1, activityPeak)) * 4))
            ),
    }
  })
  const activityPad = activitySeries[0]?.weekday ?? 0
  const activityCols = Math.ceil((activityPad + activitySeries.length) / 7)
  const activityTotal = activitySeries.reduce((sum, item) => sum + item.count, 0)
  const activityAvg = activitySeries.length
    ? (activityTotal / activitySeries.length).toFixed(1)
    : '0.0'
  const activityDays = activitySeries.filter((item) => item.count > 0).length
  const activityCells: Array<ActivityPoint | null> = [
    ...Array<null>(activityPad).fill(null),
    ...activitySeries,
  ]
  const activityMonths = activitySeries.reduce<Array<{ label: string; col: number }>>(
    (acc, item, index) => {
      const [month, day] = item.date.split('/')
      if (day === '1') {
        const col = Math.floor((activityPad + index) / 7)
        if (!acc.some((entry) => entry.col === col)) acc.push({ label: `${month}月`, col })
      }
      return acc
    },
    []
  )
  const panelClass = 'rounded-3xl bg-surface-primary shadow-none hover:shadow-none'

  return (
    /* 仅数据总览：页面底色由 AppLayout 提供，这里只负责内容纵向节奏 */
    <div>
      <div className="mx-auto max-w-[1440px] space-y-3">
      <div className="px-1">
        {/* 模板页面标题为 20/26/500，而非大号展示标题 */}
        <h1 className="text-[20px] font-medium leading-[26px] text-ink-primary">
          {isAdmin ? '数据总览' : '教学总览'}
        </h1>
      </div>

        {/* 第一行：优秀学生 + 学习趋势（对应模板的 Recent hires + Earned so far） */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card className={`${panelClass} flex flex-col justify-between p-2`}>
            {/* 面板头：标签 + 总人数 + 排序方式 */}
            <div className="flex items-start justify-between px-2 pt-2">
              <div>
                <div className="text-body-medium text-ink-secondary">优秀学生</div>
                <div className="mt-0.5 text-[24px] font-medium leading-[34px] text-ink-primary tnum">
                  {topStudents.length}
                </div>
              </div>
              <label className="relative inline-flex items-center">
                <Select
                  aria-label="优秀学生排序方式"
                  value={topMetric}
                  onChange={(e) => {
                    setTopMetric(e.target.value as 'total' | 'today')
                    setTopPage(1)
                  }}
                  className="cursor-pointer appearance-none bg-transparent pr-5 text-body-medium text-ink-primary outline-hidden"
                >
                  <option value="total">累计时长</option>
                  <option value="today">今日时长</option>
                </Select>
                <ChevronDown className="pointer-events-none absolute right-0 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-primary" />
              </label>
            </div>

            {/* 2×2 学生卡：头像 + 姓名 + 时长 + 班级标签 */}
            <div className="mt-2 grid grid-cols-2 gap-2">
              {topRows.map((s) => (
                <div key={s.id} className="min-w-0 rounded-lg bg-neutral-100 p-2.5">
                  <div className="flex items-center gap-2">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-neutral-500 to-neutral-700 text-sm font-medium text-white">
                      {s.realName?.slice(0, 1) || '学'}
                    </div>
                    <div className="min-w-0">
                      <div className="truncate text-sm font-medium text-ink-primary">
                        {s.realName || '未命名'}
                      </div>
                      <div className="truncate text-sm text-ink-secondary tnum">
                        {topMetric === 'total'
                          ? `累计 ${formatStudyTime(s.totalSeconds)}`
                          : `今日 ${formatStudyTime(s.todaySeconds)}`}
                      </div>
                    </div>
                  </div>
                  <div className="mt-2 truncate rounded-md bg-surface-primary px-2 py-1 text-center text-body-2-regular text-ink-secondary">
                    {s.className || '未分班'}
                  </div>
                </div>
              ))}
              {topRows.length === 0 && (
                <div className="col-span-2 flex h-[190px] items-center justify-center text-body-2-regular text-ink-tertiary">
                  暂无学生数据
                </div>
              )}
            </div>

            {/* 翻页：模板底部两个白色按钮 */}
            <div className="mt-2 grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setTopPage((p) => Math.max(1, p - 1))}
                disabled={currentTopPage <= 1}
                className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg bg-neutral-100 text-body-2-medium text-ink-primary transition-colors hover:bg-neutral-150 disabled:pointer-events-none disabled:opacity-40"
              >
                <ArrowLeft className="h-4 w-4" />
                上一页
              </button>
              <button
                type="button"
                onClick={() => setTopPage((p) => Math.min(topTotalPages, p + 1))}
                disabled={currentTopPage >= topTotalPages}
                className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg bg-neutral-100 text-body-2-medium text-ink-primary transition-colors hover:bg-neutral-150 disabled:pointer-events-none disabled:opacity-40"
              >
                下一页
                <ArrowRight className="h-4 w-4" />
              </button>
            </div>
          </Card>

          <Card className={`${panelClass} lg:col-span-2`}>
            <CardHeader className="flex-row items-start justify-between space-y-0 p-4 pb-0">
              <div className="min-w-0">
                <div className="text-body-medium text-ink-secondary">日均学习时长</div>
                <div className="mt-0.5 text-[24px] font-medium leading-[34px] text-ink-primary tnum">
                  {avgDailyMinutes}
                  <span className="ml-1 text-body-medium text-ink-secondary">分钟</span>
                </div>
              </div>
              {/* 区间切换：周 / 月 / 学期（模板面板右上角分段控件） */}
              <SegmentedControl
                size="sm"
                ariaLabel="趋势区间"
                value={period}
                onChange={setPeriod}
                options={TREND_PERIODS.map((item) => ({ value: item.value, label: item.label }))}
              />
            </CardHeader>
            <CardContent className="p-4 pt-4">
              <ResponsiveContainer width="100%" height={220}>
                <AreaChart data={trendSeries} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                  <defs>
                    <linearGradient id="colorMinutes" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor={CHART_BLUE} stopOpacity={0.28} />
                      <stop offset="95%" stopColor={CHART_BLUE} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="4 8" stroke={chartGrid} vertical={false} />
                  <XAxis
                    dataKey="date"
                    tick={{ fontSize: 12, fill: chartTick }}
                    axisLine={false}
                    tickLine={false}
                    interval={tickInterval}
                    minTickGap={16}
                    padding={{ left: 4, right: 8 }}
                  />
                  <YAxis tick={{ fontSize: 12, fill: chartTick }} axisLine={false} tickLine={false} width={32} />
                  <Tooltip
                    cursor={{ stroke: chartGrid }}
                    contentStyle={{
                      borderRadius: '10px',
                      ...chartTip,
                      boxShadow: '0 1px 1px rgba(0,0,0,0.04), 0 4px 4px rgba(0,0,0,0.02)',
                      fontSize: '12px',
                    }}
                  />
                  <Area
                    type="monotone"
                    dataKey="minutes"
                    stroke={CHART_BLUE_ACTIVE}
                    strokeWidth={2}
                    fill="url(#colorMinutes)"
                    name="学习时长(分钟)"
                    isAnimationActive={false}
                  />
                </AreaChart>
              </ResponsiveContainer>
            </CardContent>
          </Card>
        </div>

        {/* 第二行：班级排行 + 在线活跃度（对应模板的 Revenue + Contributions） */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <Card className={panelClass}>
            <CardHeader className="flex-row items-start justify-between space-y-0 p-4 pb-0">
              <div>
                <div className="text-body-medium text-ink-secondary">班级平均学习时长</div>
                <div className="mt-0.5 text-[24px] font-medium leading-[34px] text-ink-primary tnum">
                  {classAvgMinutes}
                  <span className="ml-1 text-body-medium text-ink-secondary">分钟</span>
                </div>
              </div>
              <span className="pt-1 text-body-medium text-ink-tertiary tnum">
                {classRankingData.length} 个班级
              </span>
            </CardHeader>
            <CardContent className="p-4 pt-4">
              {classRankingData.length > 0 ? (
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart
                    data={classRankingData}
                    layout="vertical"
                    margin={{ top: 8, right: 16, left: 0, bottom: 0 }}
                  >
                    <CartesianGrid strokeDasharray="4 8" stroke={chartGrid} horizontal={false} />
                    <XAxis type="number" tick={{ fontSize: 12, fill: chartTick }} axisLine={false} tickLine={false} unit="分" />
                    <YAxis
                      type="category"
                      dataKey="name"
                      tick={{ fontSize: 12, fill: chartTick }}
                      axisLine={false}
                      tickLine={false}
                      width={120}
                    />
                    <Tooltip
                      cursor={{ fill: chartCursor }}
                      formatter={(value: number, _name: string, props: any) => [
                        `${value} 分钟（${props.payload.studentCount}人）`,
                        '人均时长',
                      ]}
                      contentStyle={{
                        borderRadius: '10px',
                        ...chartTip,
                        boxShadow: '0 1px 1px rgba(0,0,0,0.04), 0 4px 4px rgba(0,0,0,0.02)',
                        fontSize: '12px',
                      }}
                    />
                    <Bar dataKey="avgMinutes" name="人均时长" radius={[0, 6, 6, 0]} maxBarSize={24} fill={CHART_BLUE} isAnimationActive={false}>
                      {classRankingData.map((_item: any, i: number) => (
                        <Cell key={i} fillOpacity={0.45 + (0.55 * (classRankingData.length - i)) / classRankingData.length} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <div className="flex h-[220px] items-center justify-center text-ink-tertiary">
                  暂无班级数据
                </div>
              )}
            </CardContent>
          </Card>

          {/* 在线活跃度：真实学习行为（专注/刷题/章节/考试/聊天）去重统计 */}
          <Card className={panelClass}>
            <div className="p-4">
              <div className="text-body-medium text-ink-secondary">今年的学习活跃</div>
              <div className="mt-0.5 text-[24px] font-medium leading-[34px] text-ink-primary tnum">
                {activityTotal.toLocaleString()}
                <span className="ml-1 text-body-medium text-ink-secondary">人次</span>
              </div>

              {/* 四个白色指标块（对应模板的 Token / 时长 / 连续天数） */}
              <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
                {[
                  { label: '今日在线', value: stats?.onlineToday ?? 0 },
                  { label: '日均在线', value: activityAvg },
                  { label: '峰值在线', value: activityPeak },
                  { label: '活跃天数', value: activityDays },
                ].map((item) => (
                  <div key={item.label} className="rounded-lg bg-neutral-100 p-2">
                    <div className="text-sm font-medium text-ink-primary tnum">{item.value}</div>
                    <div className="mt-0.5 truncate text-caption-1-regular text-ink-tertiary">
                      {item.label}
                    </div>
                  </div>
                ))}
              </div>

              <div className="mt-4 flex items-center justify-between">
                <span className="text-body-medium text-ink-secondary">每日活跃</span>
                <span className="text-caption-1-regular text-ink-tertiary">学习行为</span>
              </div>

              {activitySeries.length > 0 ? (
                <>
                  {/* 7 行 × N 列热力图，色阶使用主题蓝 */}
                  <div
                    className="mt-2 grid gap-[3px]"
                    style={{
                      gridAutoFlow: 'column',
                      gridTemplateRows: 'repeat(7, minmax(0, 1fr))',
                      gridAutoColumns: 'minmax(0, 1fr)',
                    }}
                  >
                    {activityCells.map((point, i) => (
                      <span
                        key={i}
                        className="aspect-square w-full rounded-[2px]"
                        style={{
                          backgroundColor: point
                            ? activityColors[point.level]
                            : 'transparent',
                        }}
                        title={point ? `${point.date}：${point.count} 人活跃` : undefined}
                      />
                    ))}
                  </div>
                  <div
                    className="mt-1.5 grid gap-[3px]"
                    style={{ gridTemplateColumns: `repeat(${activityCols}, minmax(0, 1fr))` }}
                  >
                    {activityMonths.map((item) => (
                      <span
                        key={`${item.label}-${item.col}`}
                        className="whitespace-nowrap text-caption-2-medium text-ink-tertiary"
                        style={{ gridColumnStart: item.col + 1 }}
                      >
                        {item.label}
                      </span>
                    ))}
                  </div>
                </>
              ) : (
                <div className="mt-2 flex h-[96px] items-center justify-center rounded-lg border border-dashed border-line-separator text-body-2-regular text-ink-tertiary">
                  暂无活跃数据
                </div>
              )}
            </div>
          </Card>
        </div>

        {/* KPI 行：白色统计卡（图标左上、数值与涨跌标签同行） */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {cards.map((card, i) => (
            <MetricCard
              key={i}
              label={card.label}
              value={card.value}
              icon={card.icon}
              change={card.change}
            />
          ))}
        </div>
        {/* 必要说明：演示数据来源，接入真实历史统计后删除 */}
        <p className="px-1 text-caption-1-regular text-ink-tertiary">
          涨跌标签与月/学期曲线为演示数据，待后端提供历史统计后替换。
        </p>

        {/* 学生表：灰底上的白色卡片，表头 14px 三级文字 */}
        <Card className="rounded-3xl border border-line-separator bg-surface-primary shadow-none hover:shadow-none">
          <CardHeader className="flex-row items-center justify-between space-y-0 px-5 pb-3 pt-4">
            <span className="text-body-medium text-ink-tertiary">学生学习状态</span>
            <span className="text-body-medium text-ink-primary tnum">
              共 {stats?.students?.length ?? 0} 人
            </span>
          </CardHeader>
          <CardContent className="p-0">
            {stats?.students && stats.students.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-line-separator">
                      <th className="px-5 py-3 text-left text-body-medium font-medium text-ink-tertiary">学生</th>
                      <th className="px-5 py-3 text-left text-body-medium font-medium text-ink-tertiary">学号</th>
                      <th className="px-5 py-3 text-left text-body-medium font-medium text-ink-tertiary">班级</th>
                      <th className="px-5 py-3 text-left text-body-medium font-medium text-ink-tertiary">今日时长</th>
                      <th className="px-5 py-3 text-left text-body-medium font-medium text-ink-tertiary">累计时长</th>
                      <th className="px-5 py-3 text-left text-body-medium font-medium text-ink-tertiary">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {studentRows.map((s) => (
                      <tr key={s.id} className="border-b border-line-separator transition-colors last:border-0 hover:bg-neutral-50">
                        <td className="px-5 py-4 text-sm font-medium text-ink-primary">{s.realName}</td>
                        <td className="px-5 py-4 text-sm text-ink-secondary tnum">{s.studentNo}</td>
                        <td className="px-5 py-4 text-sm text-ink-secondary">{s.className}</td>
                        <td className="px-5 py-4 text-sm text-ink-primary tnum">
                          {Math.floor(s.todaySeconds / 60)} 分钟
                        </td>
                        <td className="px-5 py-4 text-sm text-ink-primary tnum">
                          {Math.floor(s.totalSeconds / 3600)} 小时
                        </td>
                        <td className="px-5 py-4">
                          {/* 状态胶囊使用主题蓝 / 中性色 */}
                          {s.online ? (
                            <span className="inline-flex items-center rounded-md bg-blue-100 px-2 py-0.5 text-sm font-medium text-blue-700">
                              在线
                            </span>
                          ) : (
                            <span className="inline-flex items-center rounded-md bg-neutral-200 px-2 py-0.5 text-sm font-medium text-ink-secondary">
                              离线
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="flex h-[200px] items-center justify-center text-ink-tertiary">
                暂无学生数据
              </div>
            )}
            {studentTotal > 0 && (
              <div className="flex flex-col gap-3 border-t border-line-separator px-5 py-3 sm:flex-row sm:items-center sm:justify-between">
                <span className="text-sm text-ink-tertiary tnum">
                  显示 {(currentStudentPage - 1) * studentPageSize + 1}–
                  {Math.min(currentStudentPage * studentPageSize, studentTotal)}，共 {studentTotal} 人
                </span>
                <Pagination
                  page={currentStudentPage}
                  totalPages={studentTotalPages}
                  onChange={setStudentPage}
                />
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
