<template>
<view class="container">
  <view class="header">
    <text class="greeting">{{ greeting }}, {{ realName }}</text>
    <text class="subtitle">今天也要加油学习！</text>
  </view>

  <!-- 课程列表 -->
  <view class="section-title">我的课程</view>
  <view v-if="loading" class="loading">加载中...</view>
  <view v-else-if="courses.length === 0" class="empty">暂无课程</view>
  <view v-else class="course-card" v-for="(c, i) in courses" :key="c.courseId"
        @click="openChat(c)">
    <text class="course-icon">{{ icons[i % icons.length] }}</text>
    <view class="course-info">
      <view class="course-name-row">
        <text class="course-name">{{ c.courseName }}</text>
        <text v-if="getSemesterTag(c.semester)" class="semester-tag" :class="getSemesterTagClass(c.semester)">{{ getSemesterTag(c.semester) }}</text>
        <text v-if="unreadMap[c.courseId] > 0" class="badge">{{ unreadMap[c.courseId] > 99 ? '99+' : unreadMap[c.courseId] }}</text>
      </view>
      <text class="course-detail">{{ c.scheduleInfo || c.teacherName || '暂无排课信息' }}</text>
    </view>
    <text class="arrow">›</text>
  </view>

  <!-- 自定义底部导航栏 -->
  <tab-bar :current="0" />
</view>
</template>

<script>
import { getStudentCourses, getUnreadCount } from '@/utils/api.js'
import { getRealName } from '@/utils/store.js'
import { onChatUpdate, offChatUpdate } from '@/utils/ws.js'

export default {
  data() {
    return {
      realName: '',
      courses: [],
      loading: true,
      unreadMap: {},
      icons: ['📖','💻','📱','🌐','🎨','🔧','✍️','🗣️','🎬','📋','👥','📜','🏛️','🛡️']
    }
  },
  computed: {
    greeting() {
      const h = new Date().getHours()
      return h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'
    }
  },
  onShow() { this.loadData() },
  onHide() { offChatUpdate(this.onWsMsg) },
  onUnload() { offChatUpdate(this.onWsMsg) },
  methods: {
    async loadData() {
      this.realName = getRealName()
      try {
        const [courses, unread] = await Promise.all([
          getStudentCourses(), getUnreadCount()
        ])
        this.courses = courses || []
        this.unreadMap = {}
        // 未读按课程ID索引：同名课程互不干扰
        ;(unread || []).forEach(r => { this.unreadMap[r.courseId] = r.count })
        this.loading = false
      } catch (e) {
        this.loading = false
      }
      onChatUpdate(this.onWsMsg)
    },
    onWsMsg(data) {
      const key = data.courseId != null ? data.courseId : data.courseName
      if (key != null) {
        this.unreadMap[key] = (this.unreadMap[key] || 0) + 1
        this.$forceUpdate()
      }
    },
    getSemesterTag(semester) {
      if (!semester) return ''
      if (semester.includes('暑假') || semester.includes('暑期')) return '暑假班'
      if (semester.includes('寒假')) return '寒假班'
      if (semester.includes('培训')) return '培训班'
      return ''
    },
    getSemesterTagClass(semester) {
      if (!semester) return ''
      if (semester.includes('暑假') || semester.includes('暑期')) return 'tag-summer'
      if (semester.includes('寒假')) return 'tag-winter'
      if (semester.includes('培训')) return 'tag-train'
      return ''
    },
    openChat(c) {
      uni.navigateTo({
        url: '/pages/chat/index?courseName=' + encodeURIComponent(c.courseName) + '&courseId=' + c.courseId
      })
    }
  }
}
</script>

<style scoped>
.header { margin-bottom: 20px; padding-top: 8px; }
.greeting { font-size: 22px; font-weight: 600; color: #1D1D1F; }
.subtitle { display: block; font-size: 14px; color: #86868B; margin-top: 4px; }
.section-title { font-size: 13px; font-weight: 600; color: #86868B; margin-bottom: 8px; }
.loading, .empty { text-align: center; padding: 60px 0; color: #86868B; }

.course-card {
  background: #FFF; border-radius: 14px; padding: 14px 16px;
  margin-bottom: 10px; display: flex; align-items: center;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.course-icon { font-size: 24px; width: 44px; height: 44px; line-height: 44px; text-align: center; background: #F5F5F7; border-radius: 12px; flex-shrink: 0; }
.course-info { flex: 1; margin-left: 12px; }
.course-name-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.course-name { font-size: 15px; font-weight: 600; color: #1D1D1F; }
.semester-tag { font-size: 10px; padding: 2px 6px; border-radius: 4px; }
.tag-summer { color: #FA8C16; background: #FFF7E6; }
.tag-winter { color: #1890FF; background: #E6F7FF; }
.tag-train { color: #722ED1; background: #F9F0FF; }
.badge { font-size: 10px; color: #FFF; background: #FF3B30; border-radius: 10px; padding: 2px 6px; min-width: 16px; text-align: center; }
.course-detail { font-size: 12px; color: #86868B; margin-top: 2px; }
.arrow { font-size: 20px; color: #C7C7CC; margin-left: 8px; }
</style>
