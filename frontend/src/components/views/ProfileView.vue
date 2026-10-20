<template>
  <view class="page-stack profile-page">
    <view class="row between"
      ><view><text class="eyebrow">MY SPACE</text><text class="page-title">我的</text></view
      ><button class="icon-button" aria-label="设置" @click="navigate('/pages/profile/settings')">
        <AppIcon name="settings" :size="23" /></button
    ></view>
    <view class="profile-hero" @click="openProfile">
      <view class="avatar"><AppIcon name="leaf" :size="32" color="#fff" /></view>
      <view class="hero-text"
        ><text class="profile-name">{{ session.me?.profile.nickname || '你好，欢迎来到知衡' }}</text
        ><text class="hero-caption">{{
          session.me?.accountMasked || '登录后，开始记录属于你的节律'
        }}</text></view
      >
      <AppIcon name="arrow-right" :size="20" color="#fff" />
      <view class="hero-ring" />
    </view>
    <view v-if="!session.tokens" class="card"
      ><text class="label">你的生活安排，值得被好好保存</text
      ><text class="muted text-block">登录或创建账号，连接自己的记录、目标与计划。</text
      ><AppButton block @click="navigate('/pages/auth/index')">登录 / 注册</AppButton></view
    >
    <view class="two-col">
      <view class="mini-card" @click="navigate('/pages/profile/goals')"
        ><view class="mini-icon"><AppIcon name="flag" :size="23" /></view
        ><text class="mini-title">我的目标</text><text class="muted">从小改变开始</text></view
      >
      <view class="mini-card" @click="navigate('/pages/profile/availability')"
        ><view class="mini-icon amber"><AppIcon name="clock" :size="23" color="#aa7848" /></view
        ><text class="mini-title">时间偏好</text><text class="muted">为生活留些余地</text></view
      >
    </view>
    <view class="card menu-card">
      <view v-for="item in menus" :key="item.path" class="menu-row" @click="navigate(item.path)"
        ><view :class="['menu-icon', item.tone]"><AppIcon :name="item.icon" :size="20" /></view
        ><view class="menu-copy"
          ><text class="menu-title">{{ item.title }}</text
          ><text class="muted">{{ item.subtitle }}</text></view
        ><AppIcon name="arrow-right" :size="17" color="#9aa89e"
      /></view>
    </view>
    <view class="privacy-note"
      ><AppIcon name="shield" :size="18" /><text
        >每项授权由你决定，个人数据可预览、导出与清除。</text
      ></view
    >
    <text class="version-note">知衡 ZHIHENG · v0.1.0 和生活，保持温柔的平衡。</text>
  </view>
</template>
<script setup lang="ts">
import { session } from '@/stores/session'
import { navigate } from '@/stores/ui'
const menus = [
  {
    icon: 'user',
    title: '个人档案与偏好',
    subtitle: '你的称呼、节奏与阅读习惯',
    path: '/pages/profile/edit',
    tone: 'green',
  },
  {
    icon: 'bell',
    title: '消息与提醒',
    subtitle: '查看通知，安排一声恰好的提醒',
    path: '/pages/profile/notifications',
    tone: 'sand',
  },
  {
    icon: 'shield',
    title: '用途授权',
    subtitle: '分别管理 AI、通知与外部数据授权',
    path: '/pages/profile/consents',
    tone: 'green',
  },
  {
    icon: 'lock',
    title: '隐私与数据',
    subtitle: '数据导出、范围删除与账号注销',
    path: '/pages/profile/privacy',
    tone: 'blue',
  },
  {
    icon: 'help',
    title: '反馈与建议',
    subtitle: '一起让知衡变得更贴近生活',
    path: '/pages/profile/feedback',
    tone: 'sand',
  },
]
function openProfile() {
  navigate(!session.tokens ? '/pages/auth/index' : '/pages/profile/edit')
}
</script>
<style scoped>
.profile-page {
  padding-top: 8px;
}
.icon-button {
  width: 42px;
  height: 42px;
  padding: 0;
  margin: 0;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
}
.profile-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 27px 21px;
  min-height: 98px;
  border-radius: 14px;
  background: linear-gradient(115deg, #1b6554, #3d8167);
  color: white;
}
.avatar {
  width: 62px;
  height: 62px;
  background: #ffffff20;
  border: 1px solid #ffffff32;
  border-radius: 23px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
}
.hero-text {
  flex: 1;
  z-index: 1;
}
.profile-name {
  display: block;
  font-size: 21px;
  font-weight: 600;
}
.hero-caption {
  display: block;
  font-size: 11px;
  color: #d5e8dc;
  margin-top: 10px;
  line-height: 1.6;
}
.hero-ring {
  position: absolute;
  width: 170px;
  height: 170px;
  border: 25px solid #ffffff09;
  border-radius: 50%;
  right: -55px;
  bottom: -90px;
}
.mini-card {
  background: #fff;
  border-radius: 14px;
  padding: 19px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mini-icon {
  width: 44px;
  height: 44px;
  background: #eaf3ed;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 5px;
}
.mini-icon.amber {
  background: #f8f0e4;
}
.mini-title {
  font-size: 16px;
  font-weight: 600;
}
.mini-card .muted {
  font-size: 11px;
}
.menu-card {
  padding: 2px 18px;
}
.menu-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 19px 0;
  border-bottom: 1px solid #f0f3ef;
}
.menu-row:last-child {
  border-bottom: 0;
}
.menu-icon {
  width: 37px;
  height: 37px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #edf3ee;
}
.sand {
  background: #f7f0e6;
}
.blue {
  background: #edf1f5;
}
.menu-copy {
  flex: 1;
}
.menu-title {
  display: block;
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 7px;
}
.menu-copy .muted {
  font-size: 11px;
}
.privacy-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 11px;
  line-height: 1.8;
  color: #788d7c;
  padding: 2px 10px;
}
.version-note {
  white-space: pre-line;
  color: #a2aba3;
  font-size: 10px;
  line-height: 2;
  text-align: center;
  padding-bottom: 20px;
}
.text-block {
  display: block;
  margin: 12px 0 18px;
}
</style>
