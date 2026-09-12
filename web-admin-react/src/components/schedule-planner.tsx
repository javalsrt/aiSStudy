import { useState, useEffect, useMemo, useRef } from 'react'
import { Loader2, RotateCcw, ArrowRightLeft } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  getClassSchedule,
  unhideCourse,
  clearClassSchedule,
  type ScheduleRecord,
  type ScheduleSlot,
} from '@/api/courses'
import { getSemesterList } from '@/api/semester'
import { getBellTimes } from '@/api/bell'
import { useDialog } from '@/hooks/use-dialog'
import type { Semester } from '@/types'

// 星期名称（周一至周日，节假日补课可排周日）
const DAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

// 兜底小节列表与时间段（后端无任何作息配置时才使用；正常情况从 /api/bell/today 动态获取）
const FALLBACK_NODE_LIST = Array.from({ length: 8 }, (_, i) => i + 1)

const FALLBACK_NODE_TIMES: Record<number, { start: string; end: string }> = {
  1: { start: '08:10', end: '08:50' },
  2: { start: '09:00', end: '09:40' },
  3: { start: '09:50', end: '10:30' },
  4: { start: '10:40', end: '11:20' },
  5: { start: '15:10', end: '15:50' },
  6: { start: '16:00', end: '16:40' },
  7: { start: '19:50', end: '20:10' },
  8: { start: '20:20', end: '21:00' },
}

// 默认最大周数（未匹配到学期时兜底）
const DEFAULT_WEEK_COUNT = 18

// 单元格 key 生成
const cellKey = (week: number, dayOfWeek: number, node: number) =>
  `${week}-${dayOfWeek}-${node}`

// 解析 weeks JSON 字符串为数组
const parseWeeks = (weeksStr: string | undefined): number[] => {
  if (!weeksStr) return []
  try {
    const parsed = JSON.parse(weeksStr)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

// 根据起止日期计算总周数
function calcWeekCount(startDate?: string, endDate?: string): number {
  if (!startDate || !endDate) return DEFAULT_WEEK_COUNT
  const start = new Date(`${startDate}T00:00:00`)
  const end = new Date(`${endDate}T00:00:00`)
  const diffDays = Math.floor((end.getTime() - start.getTime()) / (24 * 60 * 60 * 1000))
  if (diffDays < 0) return DEFAULT_WEEK_COUNT
  return Math.max(1, Math.ceil((diffDays + 1) / 7))
}

// 计算当前是第几教学周（学期未开始或已结束均返回1）
function getCurrentWeek(startDate?: string, weekCount: number = DEFAULT_WEEK_COUNT): number {
  if (!startDate) return 1
  const start = new Date(`${startDate}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const diffDays = Math.floor((today.getTime() - start.getTime()) / (24 * 60 * 60 * 1000))
  if (diffDays < 0) return 1
  const week = Math.floor(diffDays / 7) + 1
  return week > weekCount ? 1 : Math.max(week, 1)
}

// 已选中的单元格数据
interface SelectedCell {
  week: number
  dayOfWeek: number
  node: number
  classroom: string
  isExisting: boolean
}

// 单元格状态类型
type CellState =
  | { type: 'empty' }
  | { type: 'selected'; cell: SelectedCell }
  | { type: 'self'; record: ScheduleRecord }
  | { type: 'other'; record: ScheduleRecord }

interface SchedulePlannerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  courseName: string
  classId: number | null
  className: string
  courseId: number | null
  scheduled: boolean
  totalCredit: number
  semester?: string
  onSuccess?: () => void
}

export function SchedulePlanner({
  open,
  onOpenChange,
  courseName,
  classId,
  className,
  totalCredit,
  scheduled,
  semester,
  onSuccess,
}: SchedulePlannerProps) {
  const { alert, confirm, DialogComponent } = useDialog()
  // 动态作息（按班级年级+周次从服务端解析；失败回退兜底配置）
  const [nodeList, setNodeList] = useState<number[]>(FALLBACK_NODE_LIST)
  const [nodeTimes, setNodeTimes] =
    useState<Record<number, { start: string; end: string }>>(FALLBACK_NODE_TIMES)
  const [semesterInfo, setSemesterInfo] = useState<Semester | null>(null)
  const [weekCount, setWeekCount] = useState(DEFAULT_WEEK_COUNT)
  const currentWeek = useMemo(
    () => getCurrentWeek(semesterInfo?.startDate, weekCount),
    [semesterInfo, weekCount]
  )
  const [selectedWeek, setSelectedWeek] = useState(1)
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [scheduleRecords, setScheduleRecords] = useState<ScheduleRecord[]>([])
  // 本次新选中的单元格，key = `week-dayOfWeek-node`
  const [selectedByKey, setSelectedByKey] = useState<Record<string, SelectedCell>>({})
  // 被用户取消的已有排课 key 集合
  const [canceledKeys, setCanceledKeys] = useState<Set<string>>(new Set())
  // 被用户取消的已有排课单元格详情，用于精确删除
  const [canceledCells, setCanceledCells] = useState<SelectedCell[]>([])
  const [wasScheduled, setWasScheduled] = useState(false)

  // 从 scheduleRecords 派生出的所有已有单元格（含 step>1 的连续课时展开）。
  // 取消状态单独由 canceledKeys/canceledCells 维护，避免 selectedByKey 与已有记录同步带来的竞态问题。
  const existingByKey = useMemo(() => {
    const map: Record<string, SelectedCell> = {}
    const targetName = (courseName || '').trim()
    for (const record of scheduleRecords) {
      const recordName = (record.course_name || '').trim()
      if (recordName !== targetName) continue
      const recordWeeks = parseWeeks(record.weeks)
      const dayOfWeek = Number(record.day_of_week) || 0
      const startNode = Number(record.start_node) || 0
      const step = Number(record.step) || 1
      for (const week of recordWeeks) {
        for (let i = 0; i < step; i++) {
          const node = startNode + i
          const key = cellKey(week, dayOfWeek, node)
          if (!map[key]) {
            map[key] = {
              week,
              dayOfWeek,
              node,
              classroom: record.classroom || '待定',
              isExisting: true,
            }
          }
        }
      }
    }
    return map
  }, [scheduleRecords, courseName])
  // 教室输入弹窗
  const [classroomPrompt, setClassroomPrompt] = useState<{
    open: boolean
    dayOfWeek: number
    node: number
    value: string
  }>({ open: false, dayOfWeek: 0, node: 0, value: '待定' })

  // 避免 React 严格模式下重复加载同一周数据
  const loadedWeekRef = useRef<number | null>(null)

  // 已排课时：只统计当前周已有的排课记录（每条记录 1 课时），排除已取消的单元格
  const existingCredit = useMemo(() => {
    return Object.values(existingByKey).filter((c) => c.week === selectedWeek && !canceledKeys.has(cellKey(c.week, c.dayOfWeek, c.node))).length
  }, [existingByKey, selectedWeek, canceledKeys])

  // 所有周已排课时总和，排除已取消的单元格
  const totalExistingAllWeeks = useMemo(() => {
    return Object.values(existingByKey).filter((c) => !canceledKeys.has(cellKey(c.week, c.dayOfWeek, c.node))).length
  }, [existingByKey, canceledKeys])

  // 全局已选课时（所有周新选单元格之和）
  const totalSelectedAllWeeks = useMemo(() => {
    return Object.values(selectedByKey).length
  }, [selectedByKey])

  // 全局剩余课时 = 总课时 - 所有周已排课时总和
  const globalRemaining = useMemo(() => {
    return Math.max(totalCredit - totalExistingAllWeeks, 0)
  }, [totalCredit, totalExistingAllWeeks])

  // 本周可排 = 当前周已排 + 全局剩余课时
  // 不再固定显示“每周上限 2”，教师可将所有课时集中到任意一周
  const weeklyCapacity = existingCredit + globalRemaining

  // 剩余课时 = 全局剩余课时 - 全局已选课时（跨周选择时统一扣减，防止总课时超限）
  const remainingCredit = globalRemaining - totalSelectedAllWeeks

  // 跨周移动：统计已从其他周取消、待移动到当前周的时段（仅用于提示条展示）
  const crossWeekCancels = useMemo(() => {
    const groups = new Map<number, number>()
    for (const cell of canceledCells) {
      if (cell.week !== selectedWeek) {
        groups.set(cell.week, (groups.get(cell.week) || 0) + 1)
      }
    }
    return Array.from(groups.entries()).sort((a, b) => a[0] - b[0])
  }, [canceledCells, selectedWeek])

  // [DEBUG] 监听课时统计变化
  useEffect(() => {
    console.log('[SchedulePlanner] credit stats', {
      totalCredit,
      existingCredit,
      totalExistingAllWeeks,
      totalSelectedAllWeeks,
      globalRemaining,
      weeklyCapacity,
      remainingCredit,
      selectedCount: Object.keys(selectedByKey).length,
      existingCount: Object.keys(existingByKey).length,
      canceledCount: canceledKeys.size,
      existingKeys: Object.keys(existingByKey).sort(),
      canceledKeysSorted: Array.from(canceledKeys).sort(),
    })
  }, [totalCredit, existingCredit, totalExistingAllWeeks, totalSelectedAllWeeks, globalRemaining, weeklyCapacity, remainingCredit, selectedByKey, existingByKey, canceledKeys])

  // 弹窗打开时初始化：加载学期信息并一次性加载所有周课表
  useEffect(() => {
    if (!open || !classId) return
    // [DEBUG] 弹窗打开参数
    console.log('[SchedulePlanner] open', { courseName, classId, totalCredit, scheduled, semester })
    setSelectedByKey({})
    setCanceledKeys(new Set())
    setCanceledCells([])
    setScheduleRecords([])
    setSelectedWeek(1)
    setWasScheduled(scheduled)
    loadedWeekRef.current = null

    let stale = false
    const init = async () => {
      const matched = await loadSemesterInfo()
      const week = getCurrentWeek(matched?.startDate, matched?.weekCount || weekCount)
      setSelectedWeek(week)
      setLoading(true)
      try {
        const data = await getClassSchedule(classId, 0, courseName)
        if (stale) {
          console.log('[SchedulePlanner] loadAllSchedule skipped (stale)', { courseName })
          return
        }
        const records = data.schedules || []
        // [DEBUG] 加载课表数据
        console.log('[SchedulePlanner] loadAllSchedule', { classId, courseName, recordsCount: records.length, data })
        if (data.error) {
          await alert({ description: data.error })
          return
        }
        // 课程实际最大周次与学期总周数取较大值，确保既能显示历史排课周次，也能继续向后排课
        const apiMaxWeek = data.courseMaxWeek || data.maxWeek || 0
        if (apiMaxWeek > 0) {
          setWeekCount((prev) => Math.max(prev, apiMaxWeek))
        }
        setScheduleRecords(records)

        // [DEBUG] 加载课表完成：打印本课程记录详情
        const own = records.filter((r) => (r.course_name || '').trim() === (courseName || '').trim())
        console.log('[SchedulePlanner] loadAllSchedule done', {
          classId,
          courseName,
          recordsCount: records.length,
          ownRecords: own.length,
          ownDetails: own.map((r) => ({
            weeks: r.weeks,
            day_of_week: r.day_of_week,
            start_node: r.start_node,
            step: r.step,
            classroom: r.classroom,
          })),
        })
      } catch {
        // 错误已由拦截器处理
      } finally {
        if (!stale) {
          setLoading(false)
        }
      }
    }
    init()
    return () => {
      stale = true
    }
  }, [open, courseName, classId])

  // 根据课程所属学期匹配学期详情，动态确定总周数
  const loadSemesterInfo = async (): Promise<Semester | null> => {
    if (!semester) {
      setSemesterInfo(null)
      setWeekCount(DEFAULT_WEEK_COUNT)
      return null
    }
    try {
      const list = await getSemesterList()
      const matched = list.find((s) => s.name === semester)
      if (matched) {
        setSemesterInfo(matched)
        const count = matched.weekCount || calcWeekCount(matched.startDate, matched.endDate)
        setWeekCount(count)
        return matched
      } else {
        setSemesterInfo(null)
        setWeekCount(DEFAULT_WEEK_COUNT)
        return null
      }
    } catch {
      setSemesterInfo(null)
      setWeekCount(DEFAULT_WEEK_COUNT)
      return null
    }
  }

  // 周次切换时：所有周数据已在打开弹窗时加载，切换只需更新选中周，
  // 保留用户在其他周已选择/已取消的状态，实现跨周移动。
  useEffect(() => {
    if (open && classId) {
      loadedWeekRef.current = selectedWeek
      // [DEBUG] 周次切换
      console.log('[SchedulePlanner] selectedWeek changed', { selectedWeek, selectedByKey, canceledKeys: Array.from(canceledKeys), canceledCells })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedWeek, open, classId])

  // 动态获取作息表（按班级年级 + 选中周的单双周自动解析），失败时保留兜底配置
  useEffect(() => {
    if (!open || !classId) return
    let cancelled = false
    getBellTimes({ classId, week: selectedWeek })
      .then((info) => {
        if (cancelled || !info?.periods?.length) return
        const times: Record<number, { start: string; end: string }> = {}
        for (const p of info.periods) {
          times[p.node] = { start: p.startTime, end: p.endTime }
        }
        setNodeTimes(times)
        setNodeList(info.periods.map((p) => p.node).sort((a, b) => a - b))
      })
      .catch(() => {
        /* 保持兜底作息 */
      })
    return () => {
      cancelled = true
    }
  }, [open, classId, selectedWeek])

  // 获取单元格状态：优先新选 -> 本课程已有记录 -> 其他课程占用 -> 空闲
  const getCellState = (dayOfWeek: number, node: number): CellState => {
    const key = cellKey(selectedWeek, dayOfWeek, node)
    const selected = selectedByKey[key]
    if (selected) {
      return { type: 'selected', cell: selected }
    }
    const existing = existingByKey[key]
    if (existing && !canceledKeys.has(key)) {
      return {
        type: 'self',
        record: {
          id: 0,
          user_id: 0,
          course_id: 0,
          course_name: courseName,
          day_of_week: existing.dayOfWeek,
          start_time: nodeTimes[existing.node]?.start || '',
          end_time: nodeTimes[existing.node]?.end || '',
          start_node: existing.node,
          step: 1,
          classroom: existing.classroom,
          semester: '',
          weeks: String(existing.week),
          status: 1,
        },
      }
    }
    for (const record of scheduleRecords) {
      if (record.course_name === courseName) continue // 本课程已通过 existingByKey 处理
      const recordWeeks = parseWeeks(record.weeks)
      if (!recordWeeks.includes(selectedWeek)) continue
      const startNode = Number(record.start_node) || 0
      const step = Number(record.step) || 1
      if (
        Number(record.day_of_week) === dayOfWeek &&
        node >= startNode &&
        node < startNode + step
      ) {
        return { type: 'other', record }
      }
    }
    return { type: 'empty' }
  }

  // 单元格点击
  const handleCellClick = async (dayOfWeek: number, node: number) => {
    const state = getCellState(dayOfWeek, node)
    // [DEBUG] 单元格点击
    console.log('[SchedulePlanner] cell click', { selectedWeek, dayOfWeek, node, state, selectedByKey, canceledKeys: Array.from(canceledKeys) })

    if (state.type === 'other') {
      await alert({
        title: '课程占用',
        description:
          `课程：${state.record.course_name}\n` +
          `教室：${state.record.classroom || '未设置'}\n` +
          `时间：${DAY_NAMES[state.record.day_of_week - 1]} ` +
          `第${state.record.start_node}节 ~ 第${state.record.start_node + (state.record.step || 1) - 1}节\n` +
          `周次：第${state.record.weeks || selectedWeek}周`,
      })
      return
    }

    if (state.type === 'self' || state.type === 'selected') {
      const k = cellKey(selectedWeek, dayOfWeek, node)
      console.log('[SchedulePlanner] cancel cell', { key: k, stateType: state.type })

      if (state.type === 'self') {
        // 对于已有排课记录（含旧连续课时 step>1），点击任意单元格即取消该记录覆盖的整个时段
        const record = state.record
        const recordDayOfWeek = Number(record.day_of_week) || dayOfWeek
        const startNode = Number(record.start_node) || node
        const step = Number(record.step) || 1
        const cellsToCancel: { dayOfWeek: number; node: number }[] = []
        for (let i = 0; i < step; i++) {
          cellsToCancel.push({ dayOfWeek: recordDayOfWeek, node: startNode + i })
        }

        for (const info of cellsToCancel) {
          const ck = cellKey(selectedWeek, info.dayOfWeek, info.node)
          setCanceledKeys((prevCanceled) => {
            if (prevCanceled.has(ck)) return prevCanceled
            return new Set(prevCanceled).add(ck)
          })
          setCanceledCells((prevCells) => {
            if (prevCells.some((c) => cellKey(c.week, c.dayOfWeek, c.node) === ck)) return prevCells
            const cancelCell: SelectedCell = {
              week: selectedWeek,
              dayOfWeek: info.dayOfWeek,
              node: info.node,
              classroom: record.classroom || '待定',
              isExisting: true,
            }
            const updated = [...prevCells, cancelCell]
            console.log('[SchedulePlanner] canceledCells updated', { key: ck, prevCount: prevCells.length, nextCount: updated.length })
            return updated
          })
        }
      } else {
        // 本次新选的单元格直接移除
        setSelectedByKey((prev) => {
          const next = { ...prev }
          delete next[k]
          return next
        })
      }
      return
    }

    // 空闲：检查全局剩余课时
    if (remainingCredit <= 0) {
      await alert({ description: '课程总课时已满，请先取消其他时段后再选择' })
      return
    }
    setClassroomPrompt({
      open: true,
      dayOfWeek,
      node,
      value: '待定',
    })
  }

  // 确认教室输入
  const confirmClassroom = () => {
    const { dayOfWeek, node, value } = classroomPrompt
    const classroom = (value || '').trim() || '待定'
    const key = cellKey(selectedWeek, dayOfWeek, node)
    // 安全兜底：如果目标位置在 scheduleRecords 中仍有本课程的已有记录（未被取消），
    // 自动将其加入 canceledCells，避免后端产生重复排课记录。
    const matchedRecord = scheduleRecords.find((r) => {
      if (r.course_name !== courseName) return false
      const weeks = parseWeeks(r.weeks)
      if (!weeks.includes(selectedWeek)) return false
      const startNode = Number(r.start_node) || 0
      const step = Number(r.step) || 1
      return Number(r.day_of_week) === dayOfWeek && node >= startNode && node < startNode + step
    })
    if (matchedRecord && !canceledKeys.has(key)) {
      setCanceledKeys((prev) => new Set(prev).add(key))
      setCanceledCells((prev) => {
        if (prev.some((c) => cellKey(c.week, c.dayOfWeek, c.node) === key)) return prev
        return [...prev, {
          week: selectedWeek,
          dayOfWeek,
          node,
          classroom: matchedRecord.classroom || '待定',
          isExisting: true,
        }]
      })
    }
    const newCell = { week: selectedWeek, dayOfWeek, node, classroom, isExisting: false }
    setSelectedByKey((prev) => {
      const next = { ...prev, [key]: newCell }
      // [DEBUG] 确认教室
      console.log('[SchedulePlanner] confirmClassroom', { key, newCell, next, canceledCellsCount: canceledCells.length })
      return next
    })
    setClassroomPrompt({ open: false, dayOfWeek: 0, node: 0, value: '待定' })
  }

  // 将当前周选中的单元格转换为 slots（所有课按单节处理，不再合并连续小节）
  // 注意：只包含用户新选择的单元格（isExisting=false），已有记录不应重复提交
  const buildSlotsForCurrentWeek = (): ScheduleSlot[] => {
    const cells = Object.values(selectedByKey).filter((c) => c.week === selectedWeek && !c.isExisting)
    return cells.map((cell) => makeSlot(cell, selectedWeek))
  }

  // 生成一个单节 slot，weeks 必须是 JSON 数组格式
  const makeSlot = (cell: SelectedCell, week: number): ScheduleSlot => {
    return {
      week,
      dayOfWeek: cell.dayOfWeek,
      startNode: cell.node,
      step: 1,
      startTime: nodeTimes[cell.node]?.start || '',
      endTime: nodeTimes[cell.node]?.end || '',
      credit: 1,
      classroom: cell.classroom,
      semester: semester || '',
      weeks: `[${week}]`,
    }
  }

  // 确认排课
  // 生成本次提交专用的幂等键（每次点击确定都重新生成，避免同一弹窗内先删后增被误判为重复请求）
  const makeRequestId = () =>
    `${Date.now()}-${Math.random().toString(36).slice(2, 10)}-${courseName}-${classId}`

  const handleConfirm = async () => {
    const slots = buildSlotsForCurrentWeek()
    // 收集本次涉及的所有取消周次，用于确认提示
    const affectedWeeks = Array.from(new Set(canceledCells.map((c) => c.week)))
      .sort((a, b) => a - b)
      .join('、')
    // [DEBUG] 提交排课
    console.log('[SchedulePlanner] handleConfirm', { selectedWeek, slots, canceledCells, selectedByKey, affectedWeeks })

    if (slots.length === 0) {
      // 用户取消了部分已有单元格，但未新增任何时段：精确删除被取消的单元格
      if (canceledCells.length > 0) {
        const weekText = affectedWeeks || String(selectedWeek)
        const confirmed = await confirm({
          title: '取消排课',
          description: `确认取消「${courseName}」在「${className}」第 ${weekText} 周选中的 ${canceledCells.length} 个排课时段？`,
          confirmText: '确认取消',
          cancelText: '取消',
          variant: 'danger',
        })
        if (!confirmed) return
        setSubmitting(true)
        try {
          const resp = await unhideCourse({
            courseName,
            classId: classId!,
            requestId: makeRequestId(),
            slots: [],
            clearCells: canceledCells.map((c) => ({
              week: c.week,
              dayOfWeek: c.dayOfWeek,
              startNode: c.node,
            })),
          })
          // [DEBUG] 后端响应
          console.log('[SchedulePlanner] cancel-only response', resp)
          await alert({ description: '已取消选中时段的排课记录' })
          onOpenChange(false)
          setTimeout(() => onSuccess?.(), 100)
        } catch (err: any) {
          // [DEBUG] 请求异常
          console.error('[SchedulePlanner] cancel-only error', err)
          const msg = err?.response?.data?.message || err?.message || '取消失败，请稍后重试'
          await alert({ title: '取消失败', description: msg })
        } finally {
          setSubmitting(false)
        }
        return
      }

      if (wasScheduled) {
        const confirmed = await confirm({
          title: '清空排课',
          description: `当前未选择任何排课时段，确认清空「${courseName}」在「${className}」第 ${selectedWeek} 周的排课？\n（不会影响其他周次）`,
          confirmText: '确认清空',
          cancelText: '取消',
          variant: 'danger',
        })
        if (!confirmed) return
        setSubmitting(true)
        try {
          await clearClassSchedule({ courseName, classId: classId!, week: selectedWeek })
          await alert({ description: `已清空第 ${selectedWeek} 周排课记录` })
          onOpenChange(false)
          setTimeout(() => onSuccess?.(), 100)
        } catch (err: any) {
          console.error('[SchedulePlanner] clear-week error', err)
          const msg = err?.response?.data?.message || err?.message || '清空失败，请稍后重试'
          await alert({ title: '清空失败', description: msg })
        } finally {
          setSubmitting(false)
        }
      } else {
        await alert({ description: '请先选择排课时段' })
      }
      return
    }

    // 每次确定都生成新的 requestId，防止同一弹窗内“先只取消、再选目标周提交”被幂等拦截
    const requestId = makeRequestId()
    const payload = {
      courseName,
      slots,
      classId: classId!,
      requestId,
      // 跨周移动：把其他周取消的单元格一并提交，由后端在同一事务中先删后增
      clearCells: canceledCells.map((c) => ({
        week: c.week,
        dayOfWeek: c.dayOfWeek,
        startNode: c.node,
      })),
    }
    // [DEBUG] 最终请求体，便于和后端日志/测试脚本对比
    console.log('[SchedulePlanner] unhide payload', JSON.stringify(payload, null, 2))
    setSubmitting(true)
    try {
      const resp = await unhideCourse(payload)
      // [DEBUG] 后端响应
      console.log('[SchedulePlanner] unhide response', resp)
      await alert({ description: '排课成功' })
      onOpenChange(false)
      setTimeout(() => onSuccess?.(), 100)
    } catch (err: any) {
      // [DEBUG] 请求异常
      console.error('[SchedulePlanner] unhide error', err)
      const msg = err?.response?.data?.message || err?.message || '排课失败，请稍后重试'
      await alert({ title: '排课失败', description: msg })
    } finally {
      setSubmitting(false)
    }
  }

  // 单元格样式
  const getCellClass = (state: CellState) => {
    const base = 'border border-neutral-200 text-center align-middle p-0.5 text-xs h-[48px] cursor-pointer transition-colors'
    if (state.type === 'selected') return `${base} bg-green-500 text-white font-semibold`
    if (state.type === 'self') return `${base} bg-slate-500 text-white`
    if (state.type === 'other') return `${base} bg-indigo-100 text-indigo-700 cursor-default`
    return `${base} bg-white hover:bg-primary-50`
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[1100px] w-[95vw] overflow-hidden p-4 sm:p-6">
        {DialogComponent}
        <DialogHeader>
          <DialogTitle>排课：{courseName} - {className}</DialogTitle>
        </DialogHeader>

        {/* 顶部信息栏 */}
        <div className="flex flex-wrap gap-4 mb-4 p-3 bg-primary-50 rounded-lg">
          <div className="flex flex-col gap-1">
            <span className="text-xs text-neutral-500">本周可排</span>
            <span
              className="text-xl font-semibold text-primary-600"
              title={`本周可排 = 当前周已排 ${existingCredit} + 全局剩余课时 ${globalRemaining}`}
            >
              {weeklyCapacity}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-neutral-500">当前周已排</span>
            <span className="text-xl font-semibold text-primary-600" title="当前周次已排课时数">{existingCredit}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-neutral-500">全局已选</span>
            <span className="text-xl font-semibold text-primary-600" title="所有周次本次新选择的课时数">{totalSelectedAllWeeks}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-neutral-500">剩余课时</span>
            <span
              className={`text-xl font-semibold ${
                remainingCredit < 0 ? 'text-amber-600' : 'text-primary-600'
              }`}
              title={remainingCredit < 0 ? '课程总课时已满，请取消其他时段后再选择' : ''}
            >
              {remainingCredit < 0 ? `已超 ${Math.abs(remainingCredit)}` : remainingCredit}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-neutral-500">当前选中周</span>
            <span className="text-xl font-semibold text-primary-600">第 {selectedWeek} 周</span>
          </div>
        </div>

        {/* 跨周移动提示 */}
        {crossWeekCancels.length > 0 && (
          <div className="mb-3.5 p-3 bg-amber-50 border border-amber-200 rounded-lg flex items-start gap-2.5">
            <ArrowRightLeft className="w-4 h-4 text-amber-600 mt-0.5 flex-shrink-0" />
            <div className="text-sm text-amber-800">
              <span className="font-medium">跨周移动中：</span>
              已从第 {crossWeekCancels.map(([w, c]) => `${w} 周（${c} 个时段）`).join('、')} 取消排课，
              可在当前第 {selectedWeek} 周选择目标时段，提交后自动完成移动。
            </div>
          </div>
        )}

        {/* 周次选择器 */}
        <div className="flex items-center gap-3 mb-3.5">
          <span className="text-sm text-neutral-500 whitespace-nowrap">选择周次</span>
          <div className="flex gap-1.5 overflow-x-auto pb-1">
            {Array.from({ length: weekCount }, (_, i) => i + 1).map((w) => (
              <button
                key={w}
                data-week={w}
                onClick={() => setSelectedWeek(w)}
                className={`flex-shrink-0 w-9 h-8 border rounded text-sm cursor-pointer transition-all relative ${
                  selectedWeek === w
                    ? 'bg-primary-550 border-primary-550 text-white'
                    : 'bg-white border-neutral-200 hover:border-primary-400 hover:text-primary-600'
                }`}
              >
                {w}
                {currentWeek === w && (
                  <span
                    title={`当前教学周（第 ${currentWeek} 周）`}
                    className="absolute -top-1 -right-1 w-2 h-2 bg-blue-500 rounded-full border-[1.5px] border-white"
                  />
                )}
              </button>
            ))}
          </div>
        </div>

        {/* 图例与操作 */}
        <div className="flex items-center justify-between mb-3">
          <div className="flex gap-4 text-xs text-neutral-500 flex-wrap">
            {totalSelectedAllWeeks > 0 && (
              <span className="flex items-center gap-1.5">
                <span className="inline-block w-3 h-3 rounded-sm bg-green-500" />
                本次选择
              </span>
            )}
            <span className="flex items-center gap-1.5">
              <span className="inline-block w-3 h-3 rounded-sm bg-slate-500" />
              已排记录
            </span>
            <span className="flex items-center gap-1.5">
              <span className="inline-block w-3 h-3 rounded-sm bg-indigo-100 border border-indigo-200" />
              其他课程
            </span>
            <span className="flex items-center gap-1.5">
              <span className="inline-block w-3 h-3 rounded-sm bg-white border border-neutral-200 flex items-center justify-center text-[10px] text-neutral-300">+</span>
              空闲
            </span>
          </div>
          <Button
            variant="outline"
            size="sm"
            className="h-8 text-xs gap-1"
            onClick={() => {
              // 重置本周：仅清除当前周的已选/已取消状态，其他周状态保留
              setSelectedByKey((prev) => {
                const next: Record<string, SelectedCell> = {}
                for (const [key, cell] of Object.entries(prev)) {
                  if (cell.week !== selectedWeek) next[key] = cell
                }
                return next
              })
              setCanceledKeys((prev) => {
                const next = new Set(prev)
                for (const key of next) {
                  if (key.startsWith(`${selectedWeek}-`)) next.delete(key)
                }
                return next
              })
              setCanceledCells((prev) => prev.filter((c) => c.week !== selectedWeek))
            }}
          >
            <RotateCcw className="w-3.5 h-3.5" />
            重置本周
          </Button>
        </div>

        {/* 课表主体 */}
        <div className="overflow-x-auto relative">
          {loading && (
            <div className="absolute inset-0 bg-white/60 flex items-center justify-center z-10">
              <Loader2 className="w-6 h-6 animate-spin text-primary-550" />
            </div>
          )}
          <table className="w-full min-w-[680px] border-collapse table-fixed">
            <thead>
              <tr>
                <th className="border border-neutral-200 bg-neutral-50 font-semibold p-1 text-xs w-[84px]">
                  小节/时间
                </th>
                {DAY_NAMES.map((day, idx) => (
                  <th
                    key={day}
                    className="border border-neutral-200 bg-neutral-50 font-semibold p-1 text-xs"
                  >
                    {day}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {nodeList.map((node) => (
                <tr key={node}>
                  <td className="border border-neutral-200 bg-neutral-50 p-1 text-center align-middle">
                    <div className="text-xs font-semibold">第{node}节</div>
                    <div className="text-[11px] text-neutral-500 mt-0.5">
                      {nodeTimes[node]?.start}-{nodeTimes[node]?.end}
                    </div>
                  </td>
                  {Array.from({ length: 7 }, (_, i) => i + 1).map((day) => {
                    const state = getCellState(day, node)
                    return (
                      <td
                        key={day}
                        className={getCellClass(state)}
                        onClick={() => handleCellClick(day, node)}
                      >
                        <div className="flex flex-col items-center justify-center gap-0.5">
                          {state.type === 'other' && (
                            <>
                              <div className="font-semibold leading-tight">
                                {state.record.course_name}
                              </div>
                              {state.record.classroom && (
                                <div className="text-[11px] text-indigo-600/80">
                                  {state.record.classroom}
                                </div>
                              )}
                            </>
                          )}
                          {state.type === 'self' && (
                            <>
                              <div className="font-semibold leading-tight">{courseName}</div>
                              {state.record.classroom && (
                                <div className="text-[11px] opacity-90">
                                  {state.record.classroom}
                                </div>
                              )}
                            </>
                          )}
                          {state.type === 'selected' && (
                            <>
                              <div className="font-semibold">已选</div>
                              {state.cell.classroom && (
                                <div className="text-[11px] opacity-90">
                                  {state.cell.classroom}
                                </div>
                              )}
                            </>
                          )}
                          {state.type === 'empty' && (
                            <span className="text-neutral-300 text-sm">+</span>
                          )}
                        </div>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleConfirm} disabled={submitting}>
            {submitting ? (
              <>
                <Loader2 className="w-4 h-4 mr-1 animate-spin" />
                提交中...
              </>
            ) : (
              '确定'
            )}
          </Button>
        </DialogFooter>
      </DialogContent>

      {/* 教室输入弹窗 */}
      <Dialog open={classroomPrompt.open} onOpenChange={(o) => !o && setClassroomPrompt({ ...classroomPrompt, open: false })}>
        <DialogContent className="max-w-[400px]">
          <DialogHeader>
            <DialogTitle>设置教室</DialogTitle>
          </DialogHeader>
          <div className="space-y-2 py-2">
            <Label htmlFor="classroom-input">教室名</Label>
            <Input
              id="classroom-input"
              value={classroomPrompt.value}
              onChange={(e) => setClassroomPrompt({ ...classroomPrompt, value: e.target.value })}
              placeholder="请输入教室名"
              onKeyDown={(e) => {
                if (e.key === 'Enter') confirmClassroom()
              }}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setClassroomPrompt({ ...classroomPrompt, open: false })}>
              取消
            </Button>
            <Button onClick={confirmClassroom}>确认</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Dialog>
  )
}
