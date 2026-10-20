<template>
  <view class="page-stack trends-view">
    <view class="row between"
      ><view
        ><text class="eyebrow">SMALL CHANGES, REAL PROGRESS</text
        ><text class="page-title">看见自己的节奏</text></view
      ><view class="refresh-control" @tap="load"><AppIcon name="refresh" :size="20" /></view
    ></view>
    <view class="range-row"
      ><view class="range-control"
        ><view
          v-for="count in [7, 14]"
          :key="count"
          :class="{ active: days === count }"
          @tap="setDays(count)"
          >近 {{ count }} 天</view
        ></view
      ><text class="range-dates"
        >{{ formatDay(dayOffset(1 - days)) }} — {{ formatDay(today()) }}</text
      ></view
    >
    <StateView
      v-if="!authenticated"
      kind="auth"
      title="登录后继续"
      description="登录后查看后端保存的记录与趋势。"
      action-text="登录或注册"
      @action="navigate('/pages/auth/index')"
    />
    <StateView v-else-if="loading && !trends" kind="loading" title="正在整理你的记录" />
    <StateView
      v-else-if="error"
      kind="error"
      title="趋势暂时无法读取"
      :description="error"
      action-text="重新加载"
      @action="load"
    />
    <template v-if="authenticated && trends">
      <view class="overview-card"
        ><view
          ><text class="overview-kicker">记录，让变化有迹可循</text
          ><text class="overview-title">{{
            totalValidDays
              ? `每一个被记下的日子，
都在帮你认识自己。`
              : '先从今天的一条记录开始。'
          }}</text></view
        ><view class="overview-bottom"
          ><view
            ><text class="overview-number">{{ totalValidDays }}</text
            ><text class="overview-unit"> / {{ days }} 天</text
            ><text class="overview-caption">至少有一类有效记录</text></view
          ><view class="mini-week"
            ><view
              v-for="(day, index) in dayCoverage"
              :key="index"
              :class="{ recorded: day }"
              :style="{ height: (day ? 15 + (index % 4) * 8 : 8) + 'px' }" /></view></view
      ></view>

      <scroll-view scroll-x class="metric-scroll"
        ><view class="metric-tabs"
          ><view
            v-for="item in metricOptions"
            :key="item.code"
            class="metric-tab"
            :class="{ selected: metric === item.code }"
            @tap="selectMetric(item.code)"
            ><AppIcon :name="item.icon" :size="16" /><text>{{ item.label }}</text></view
          ></view
        ></scroll-view
      >
      <view v-if="activeSeries" class="card chart-card"
        ><view class="between row"
          ><view
            ><text class="chart-kicker">{{ activeSeries.definition.label }}</text
            ><view class="metric-heading"
              ><text class="big-value">{{ averageDisplay }}</text
              ><text class="value-unit">{{ metricUnit }}</text></view
            ><text class="chart-subtitle"
              >有记录日期的平均值 · {{ activeSeries.validDays }} /
              {{ activeSeries.totalDays }} 天</text
            ></view
          ><view class="chart-symbol" :class="metricClass"
            ><AppIcon :name="activeMetric.icon" :size="24" /></view
        ></view>
        <view class="bar-chart"
          ><view
            v-for="(point, index) in activeSeries.points"
            :key="point.date"
            class="bar-column"
            :class="{ focused: selectedDate === point.date }"
            @tap="selectedDate = point.date"
            ><view class="bar-container"
              ><view
                v-if="point.value !== null"
                class="chart-bar"
                :class="metricClass"
                :style="{ height: barHeight(point) + '%' }"
                ><view class="bar-cap" /></view
              ><view v-else class="missing-mark">{{
                point.coverageState === 'CONFLICT' ? '!' : '·'
              }}</view></view
            ><text class="bar-label">{{
              days === 7 || index % 2 === 0 ? point.date.slice(8) : ''
            }}</text
            ><view v-if="selectedDate === point.date" class="selection-dot" /></view
        ></view>
        <view class="chart-legend"
          ><view><view class="legend-dot" :class="metricClass" /><text>有效记录</text></view
          ><view><text class="legend-missing">·</text><text>未记录</text></view
          ><text>点选日期查看依据</text></view
        >
        <view v-if="selectedPoint" class="point-detail"
          ><view class="between row"
            ><text>{{ formatDay(selectedPoint.date) }}</text
            ><text class="point-value">{{ pointDisplay(selectedPoint) }}</text></view
          ><text class="point-caption">{{
            selectedPoint.coverageState === 'CONFLICT'
              ? '存在记录冲突，冲突数据已排除，不推算为零。'
              : selectedPoint.value === null
                ? '这一天没有可用记录。空白仅表示缺少数据。'
                : `基于 ${selectedPoint.sampleCount} ${metric === 'TASK_COMPLETION' ? '项任务' : '条有效记录'}`
          }}</text
          ><text
            v-if="metric === 'TASK_COMPLETION' && selectedPoint.denominator"
            class="point-caption"
            >完成 {{ selectedPoint.numerator }} /
            {{ selectedPoint.denominator }} 项，跳过任务仍在分母。</text
          ><view v-if="selectedPoint.sourceRecordIds?.length" class="evidence-links"
            ><text
              v-for="(recordId, index) in selectedPoint.sourceRecordIds.slice(0, 3)"
              :key="recordId"
              class="subtle-link"
              @tap="navigate(`/pages/records/detail?id=${recordId}`)"
              >查看记录依据 {{ index + 1 }} →</text
            ></view
          ></view
        >
      </view>

      <view class="coverage-grid"
        ><view
          v-for="series in trends.series"
          :key="series.definition.code"
          class="coverage-card"
          @tap="selectMetric(series.definition.code)"
          ><view class="between row"
            ><text>{{
              metricOptions.find((item) => item.code === series.definition.code)?.label
            }}</text
            ><text class="coverage-fraction"
              >{{ series.validDays }} / {{ series.totalDays }}</text
            ></view
          ><view class="coverage-track"
            ><view
              :style="{
                width: series.totalDays ? (series.validDays / series.totalDays) * 100 + '%' : '0%',
              }" /></view
          ><text class="coverage-caption">有数据的日期</text></view
        ></view
      >
      <view class="definition-note"
        ><AppIcon name="info" :size="17" /><view
          ><text class="note-heading">每个数字，都有边界</text
          ><text>{{
            activeSeries?.definition.aggregation || '缺失保留空白，避免把未记录误认为没有发生。'
          }}</text
          ><text
            >时区 {{ trends.displayTimeZone }} ·
            {{ trends.stale ? '数据已变化，请刷新' : '按记录冻结的业务日统计' }}</text
          ></view
        ></view
      >
    </template>

    <view class="section-heading"
      ><text>每周回顾</text
      ><text class="subtle-link" @tap="navigate('/pages/reviews/index')"
        >全部回顾 <text class="arrow">→</text></text
      ></view
    >
    <view class="review-teaser" @tap="navigate('/pages/reviews/index')"
      ><view class="review-icon"><AppIcon name="book" :size="25" /></view
      ><view class="review-copy"
        ><text class="review-title">把这一周，轻轻翻一遍</text
        ><text class="review-description"
          >基于已记录的事实，回看节奏与小进步。 每条结论都可以追溯到依据。</text
        ><view class="review-action"
          >进入每周回顾 <AppIcon name="arrow-right" :size="14" /></view></view
    ></view>
    <view class="knowledge-footer" @tap="navigate('/pages/knowledge/index')"
      ><AppIcon name="leaf" :size="18" /><text>想了解记录口径？到知识库看看</text
      ><AppIcon name="arrow-right" :size="15"
    /></view>
  </view>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getTrends, type MetricCode, type MetricPoint, type TrendsView } from '@/api/statistics'
import { session } from '@/stores/session'
import { navigate, ui } from '@/stores/ui'
import { dayOffset, deviceTimeZone, formatDay, today } from '@/utils/time'
const days = ref(7),
  metric = ref<MetricCode>('SLEEP_INTERVAL_MINUTES'),
  trends = ref<TrendsView | null>(null),
  selectedDate = ref(today()),
  loading = ref(false),
  error = ref('')
const authenticated = computed(() => !!session.tokens)
const metricOptions: { code: MetricCode; label: string; icon: string }[] = [
  { code: 'SLEEP_INTERVAL_MINUTES', label: '睡眠', icon: 'moon' },
  { code: 'ACTIVITY_MINUTES', label: '活动', icon: 'activity' },
  { code: 'ENERGY_LEVEL', label: '精力', icon: 'sun' },
  { code: 'TASK_COMPLETION', label: '任务执行', icon: 'check-circle' },
]
const activeMetric = computed(() => metricOptions.find((item) => item.code === metric.value)!)
const metricClass = computed(
  () =>
    ({
      SLEEP_INTERVAL_MINUTES: 'sleep',
      ACTIVITY_MINUTES: 'activity',
      ENERGY_LEVEL: 'energy',
      TASK_COMPLETION: 'task',
    })[metric.value],
)
const activeSeries = computed(() =>
  trends.value?.series.find((item) => item.definition.code === metric.value),
)
const selectedPoint = computed(() =>
  activeSeries.value?.points.find((item) => item.date === selectedDate.value),
)
const metricUnit = computed(() =>
  metric.value === 'SLEEP_INTERVAL_MINUTES'
    ? '小时'
    : metric.value === 'ACTIVITY_MINUTES'
      ? '分钟'
      : metric.value === 'ENERGY_LEVEL'
        ? '/ 5'
        : '%',
)
const averageDisplay = computed(() => {
  const value = activeSeries.value?.average
  if (value === null || value === undefined) return '—'
  return metric.value === 'SLEEP_INTERVAL_MINUTES'
    ? (Number(value) / 60).toFixed(1)
    : metric.value === 'TASK_COMPLETION'
      ? Math.round(Number(value) * 100)
      : Number(value).toFixed(metric.value === 'ENERGY_LEVEL' ? 1 : 0)
})
const dayCoverage = computed(() =>
  Array.from(
    { length: days.value },
    (_, index) =>
      trends.value?.series.some(
        (series) =>
          series.definition.code !== 'TASK_COMPLETION' &&
          series.points[index]?.value !== null &&
          series.points[index]?.value !== undefined,
      ) || false,
  ),
)
const totalValidDays = computed(() => dayCoverage.value.filter(Boolean).length)
function pointDisplay(point: MetricPoint) {
  if (point.value === null) return '未记录'
  const value = Number(point.value)
  return metric.value === 'SLEEP_INTERVAL_MINUTES'
    ? (value / 60).toFixed(1) + ' 小时'
    : metric.value === 'TASK_COMPLETION'
      ? Math.round(value * 100) + '%'
      : value + (metric.value === 'ENERGY_LEVEL' ? ' / 5' : ' 分钟')
}
function barHeight(point: MetricPoint) {
  const max =
    metric.value === 'ENERGY_LEVEL'
      ? 5
      : metric.value === 'TASK_COMPLETION'
        ? 1
        : Math.max(...(activeSeries.value?.points.map((item) => Number(item.value || 0)) || [1])) *
          1.12
  return Math.max(3, (Number(point.value || 0) / max) * 100)
}
function selectMetric(value: MetricCode) {
  metric.value = value
}
function setDays(value: number) {
  if (days.value === value) return
  days.value = value
  void load()
}
async function load() {
  if (!authenticated.value) {
    trends.value = null
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    trends.value = await getTrends(
      dayOffset(1 - days.value),
      today(),
      metricOptions.map((item) => item.code),
      deviceTimeZone(),
    )
    selectedDate.value = today()
  } catch (err) {
    trends.value = null
    error.value = err instanceof Error ? err.message : '读取趋势失败'
  } finally {
    loading.value = false
  }
}
watch(
  () => [session.revision, ui.refreshTick],
  () => {
    void load()
  },
)
onMounted(load)
</script>
<style scoped>
.page-title {
  display: block;
  margin-top: 7px;
}
.refresh-control {
  width: 40px;
  height: 40px;
  border: 1px solid #e2e8de;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
  background: var(--surface);
  color: #6b8164;
}
.range-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.range-control {
  display: flex;
  padding: 4px;
  background: #e9eee4;
  border-radius: 10px;
  font-size: 11px;
  gap: 3px;
}
.range-control > view {
  padding: 7px 11px;
  color: #89977f;
  border-radius: 7px;
}
.range-control > view.active {
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 1px 3px #27472b0d;
}
.range-dates {
  font-size: 9px;
  color: var(--muted);
}
.overview-card {
  background: #e8eee1;
  border: 1px solid #dfe7d6;
  padding: 22px;
  border-radius: 14px;
  position: relative;
}
.overview-kicker {
  font-size: 10px;
  color: #849176;
  letter-spacing: 1px;
}
.overview-title {
  display: block;
  white-space: pre-line;
  font-size: 22px;
  line-height: 1.55;
  font-weight: 600;
  color: var(--ink);
  margin-top: 15px;
}
.overview-bottom {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-top: 24px;
}
.overview-number {
  font-size: 35px;
  line-height: 1.1;
  color: #436d37;
  font-weight: 500;
}
.overview-unit {
  font-size: 11px;
  color: #94a288;
}
.overview-caption {
  display: block;
  font-size: 9px;
  color: #87977b;
  margin-top: 8px;
}
.mini-week {
  display: flex;
  align-items: flex-end;
  gap: 5px;
  max-width: 55%;
  height: 52px;
  padding-bottom: 5px;
}
.mini-week > view {
  width: 7px;
  border-radius: 3px;
  background: #d7e2cc;
}
.mini-week > view.recorded {
  background: #a0b990;
}
.metric-scroll {
  white-space: nowrap;
  width: 100%;
  margin: 4px 0 -2px;
}
.metric-tabs {
  display: flex;
  gap: 9px;
}
.metric-tab {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid #e0e7da;
  background: var(--surface);
  color: #9aa68e;
  padding: 10px 12px;
  font-size: 11px;
  border-radius: 11px;
  flex-shrink: 0;
}
.metric-tab.selected {
  background: #1b6554;
  color: #fff;
  border-color: #1b6554;
}
.chart-card {
  padding: 20px 16px;
}
.chart-kicker {
  font-size: 12px;
  color: var(--muted);
}
.metric-heading {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 11px;
}
.big-value {
  font-size: 36px;
  line-height: 1.15;
  font-weight: 500;
  color: var(--ink);
  letter-spacing: -1px;
}
.value-unit {
  font-size: 11px;
  color: var(--muted);
}
.chart-subtitle {
  display: block;
  font-size: 9px;
  color: var(--muted);
  margin-top: 8px;
}
.chart-symbol {
  width: 45px;
  height: 45px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #efedf5;
  color: #9d8eb4;
}
.chart-symbol.activity {
  background: #e9f0df;
  color: #8b9d5c;
}
.chart-symbol.energy {
  background: #f6efdf;
  color: #bb9b56;
}
.chart-symbol.task {
  background: #e4f0ea;
  color: #649a7c;
}
.bar-chart {
  display: flex;
  justify-content: space-between;
  gap: 7px;
  margin-top: 25px;
  height: 165px;
  position: relative;
  background: repeating-linear-gradient(
    to top,
    transparent,
    transparent 43px,
    #f0f2ec 44px,
    #f0f2ec 45px
  );
  padding: 0 5px;
}
.bar-column {
  flex: 1;
  display: flex;
  align-items: center;
  flex-direction: column;
  min-width: 0;
  position: relative;
}
.bar-container {
  height: 135px;
  width: 100%;
  max-width: 24px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.chart-bar {
  width: 100%;
  background: #c4b9d2;
  border-radius: 5px 5px 2px 2px;
  min-height: 4px;
  position: relative;
  transition: height 0.4s;
}
.chart-bar.activity {
  background: #bacb92;
}
.chart-bar.energy {
  background: #ddc483;
}
.chart-bar.task {
  background: #9cc9ad;
}
.focused .chart-bar {
  background: #827493;
}
.focused .chart-bar.activity {
  background: #7c9956;
}
.focused .chart-bar.energy {
  background: #b99b4f;
}
.focused .chart-bar.task {
  background: #4c9478;
}
.bar-cap {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: #ffffff30;
  border-radius: 5px;
}
.missing-mark {
  font-size: 23px;
  color: #d2d9c9;
  line-height: 12px;
  margin-bottom: 2px;
}
.bar-label {
  font-size: 9px;
  color: var(--muted);
  margin-top: 12px;
}
.focused .bar-label {
  color: var(--ink);
}
.selection-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #6a8a55;
  position: absolute;
  bottom: -2px;
}
.chart-legend {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 9px;
  color: var(--muted);
  margin: 15px 0 18px;
}
.chart-legend > view {
  display: flex;
  align-items: center;
  gap: 4px;
}
.chart-legend > text:last-child {
  margin-left: auto;
}
.legend-dot {
  width: 5px;
  height: 5px;
  background: #c4b9d2;
  border-radius: 50%;
}
.legend-dot.activity {
  background: #bacb92;
}
.legend-dot.energy {
  background: #ddc483;
}
.legend-dot.task {
  background: #9cc9ad;
}
.legend-missing {
  font-size: 18px;
  line-height: 5px;
}
.point-detail {
  background: #f6f8f2;
  border-radius: 10px;
  padding: 13px;
  font-size: 12px;
  color: var(--muted);
}
.point-value {
  color: #58734b;
  font-weight: 600;
}
.point-caption {
  display: block;
  font-size: 10px;
  line-height: 1.8;
  margin-top: 7px;
  color: var(--muted);
}
.coverage-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 11px;
}
.coverage-card {
  background: var(--surface);
  border: 1px solid #e5eadf;
  border-radius: 14px;
  padding: 15px;
  font-size: 11px;
  color: var(--muted);
}
.coverage-fraction {
  color: #8fa37e;
  font-size: 10px;
}
.coverage-track {
  height: 3px;
  border-radius: 3px;
  background: #eff3e9;
  margin-top: 16px;
  overflow: hidden;
}
.coverage-track > view {
  height: 100%;
  background: #a2b98b;
  border-radius: 3px;
}
.coverage-caption {
  display: block;
  font-size: 9px;
  color: var(--muted);
  margin-top: 8px;
}
.definition-note {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  color: var(--muted);
  padding: 2px;
}
.definition-note > view {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 10px;
  line-height: 1.8;
}
.note-heading {
  font-size: 11px;
  color: var(--muted);
}
.review-teaser {
  background: #f1ece0;
  border: 1px solid #e8e1d1;
  border-radius: 14px;
  display: flex;
  gap: 16px;
  padding: 22px 18px;
}
.review-icon {
  width: 44px;
  height: 48px;
  border: 1px solid #d9cda9;
  background: #f9f5ea;
  border-radius: 6px 12px 12px 6px;
  color: #b09a65;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: -3px 0 0 #e7ddc6;
  flex-shrink: 0;
  margin: 4px;
}
.review-title {
  font-size: 16px;
  color: #726344;
  font-weight: 600;
}
.review-description {
  display: block;
  font-size: 11px;
  color: #a39372;
  line-height: 1.8;
  white-space: pre-line;
  margin-top: 9px;
}
.review-action {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10px;
  color: #9d8654;
  margin-top: 17px;
}
.knowledge-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--muted);
  padding: 3px 0;
}
.knowledge-footer > :last-child {
  margin-left: auto;
}
</style>
