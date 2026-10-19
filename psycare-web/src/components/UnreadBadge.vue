<template>
  <!--
    数量为 0 时渲染成空（v-if 而不是 CSS 隐藏）：
    屏幕阅读器与自动化测试都会读到隐藏元素，而「0 条未读」对用户没有任何信息量。
  -->
  <span
    v-if="count > 0"
    class="unread"
    :class="`is-${mode}`"
    :title="tip"
  >
    <template v-if="mode !== 'dot'">{{ count > 99 ? '99+' : count }}</template>
  </span>
</template>

<script setup lang="ts">
/**
 * 未读标记 —— 医生端与居民端**共用同一个组件**。
 *
 * <h3>为什么必须共用一个</h3>
 *
 * 需求是「用户端红点与医生端完全一致」。靠"两边都照着写一遍"做不到这件事：
 * 颜色、尺寸、圆角、字数上限（99+）任何一处改了，另一处不会跟着变，
 * 而这类差异不会报错，只会一直挂在界面上。共用一个组件后，
 * 「一致」不再靠人记着，而是由代码结构保证。
 *
 * <h3>两种形态</h3>
 *
 * - `pill`：数字胶囊，琥珀底，用于**带文字的导航项**（医生端旧侧栏的会话项等）。
 *   样式沿用医生端既有的 `.side-badge`（18px 高、`--risk-b` 底、等宽字体）。
 * - `rail`：数字胶囊，红底 + 白色外环，用于**64px 窄图标栏**上的图标角标。
 *   颜色与 pill 不同是刻意的，不是两套实现打架：pill 旁边写着菜单名，用户知道
 *   那个数字属于谁，所以要中性；rail 只有一个 20px 图标，角标必须自己抢到注意力，
 *   红底 + 白环是这类「图标角标」的通行形态（邮件 / IM 客户端一致如此）。
 * - `dot`：无数字的小圆点，压在会话列表头像右上角。
 *   沿用医生端 `MessageList` 既有的 `.dot`（9px、`--risk-d`、带卡片色描边）。
 *
 * 三种形态共用一个组件，是为了让「居民端 / 医生端 / 管理端红点一致」由**代码结构**
 * 保证，而不是靠三处照着写一遍。
 *
 * <h3>只渲染数字，不负责取数</h3>
 *
 * 数据一律来自 `stores/inbox.ts`（服务端 `/chat/unread`）。
 * 本组件不请求、不计算 —— 一旦它自己会取数，两端就又有了各自的口径。
 */
withDefaults(
  defineProps<{
    /** 未读数：会话数（导航）或消息数（列表行）。为 0 时不渲染 */
    count: number
    /** pill = 带文字导航的数字胶囊；rail = 窄图标栏角标；dot = 列表小圆点 */
    mode?: 'pill' | 'rail' | 'dot'
    /** 悬浮说明，如「3 位居民发来新消息」 */
    tip?: string
  }>(),
  { mode: 'pill', tip: '' },
)
</script>

<style scoped>
/* 两个形态共有的定位约束：都不参与 flex 伸缩，否则窄容器里会被压扁 */
.unread {
  flex: none;
}

/* ---------- pill：导航角标 ---------- */
.is-pill {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
  color: #fff;
  background: var(--risk-b);
  box-sizing: border-box;
}

/* ---------- rail：窄图标栏角标 ---------- */
/*
 * 定位写在组件内（要求父元素 position: relative），调用方只管给一个 relative 容器 ——
 * 「压在图标右上角」这个约定只有一份。box-sizing: content-box 让 1.5px 的白色外环
 * 长在 17px 之外，不会把数字压扁。
 */
.is-rail {
  position: absolute;
  top: 3px;
  right: 3px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: var(--radius-pill);
  font-family: var(--font-mono);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 17px;
  text-align: center;
  color: #fff;
  background: var(--risk-d);
  /* 白色外环：窄栏底色是半透明白玻璃，红点直接压上去边界会糊，
     加一圈浅环才能在任何底色上「立」起来 */
  border: 1.5px solid rgba(255, 255, 255, 0.9);
  box-sizing: content-box;
}

/* ---------- dot：列表行红点 ---------- */
.is-dot {
  /*
   * 自定位：要求父元素 position: relative。
   * 定位写在组件内而不是每个调用方各写一遍 —— 调用方只需要给一个 relative 容器，
   * 「压在右上角 2px」这个约定只有一份。
   */
  position: absolute;
  top: -2px;
  right: -2px;
  width: 9px;
  height: 9px;
  border-radius: var(--radius-pill);
  background: var(--risk-d);
  border: 2px solid var(--card-bg);
  box-sizing: content-box;
}
</style>
