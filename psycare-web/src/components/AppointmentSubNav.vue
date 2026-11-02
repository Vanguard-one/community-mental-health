<template>
  <nav class="subnav" aria-label="医生与预约导航">
    <!--
      「我的预约」放在最上面。
      它不是科室分类里的一项，而是「我自己的事」——回访用户打开这一栏，
      十次里有八次是想看自己约上了没有，而不是重新挑人。
      原来的位置在分隔线以下的最底部，视觉上像一条附加项，和实际点击意图相反。
    -->
    <button
      type="button"
      class="subnav-item"
      :class="{ 'is-on': activeKey === MINE_KEY }"
      @click="emit('mine')"
    >
      <svg class="si-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <rect x="3.5" y="5.5" width="17" height="15" rx="3" stroke="currentColor" stroke-width="1.7" />
        <path d="M8 3.5v4M16 3.5v4M3.5 10.5h17" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
      </svg>
      <span class="si-text">我的预约</span>
    </button>

    <div class="subnav-sep" aria-hidden="true" />

    <p class="subnav-title">科室</p>

    <button
      type="button"
      class="subnav-item"
      :class="{ 'is-on': activeKey === '' }"
      @click="emit('select', '')"
    >
      <span class="si-text">全部科室</span>
      <span class="si-count mono">{{ total }}</span>
    </button>

    <button
      v-for="g in departments"
      :key="g.department"
      type="button"
      class="subnav-item"
      :class="{ 'is-on': activeKey === g.department }"
      @click="emit('select', g.department)"
    >
      <span class="si-text">{{ g.department }}</span>
      <span class="si-count mono">{{ g.doctorCount }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import type { DepartmentGroup } from '@/types'

/**
 * 医生咨询 / 我的预约 两页共用的左侧一级菜单。
 *
 * <h3>菜单顺序：我的预约 → 科室</h3>
 * 「我的预约」置顶，科室分类在其下、由一条分隔线隔开。两个区块回答的是两个不同问题
 * ——上面是「我约的事怎么样了」，下面是「我该找谁」。置顶的依据是回访路径：
 * 已经有单子的居民打开这一栏通常是查自己的预约，而挑人是新用户的一次性动作。
 *
 * <h3>为什么抽成组件而不是各页写一份</h3>
 * 两页的一级菜单内容完全一样（我的预约 + 科室列表），只有「当前选中哪一项」不同。
 * 复制一份的话，加一个科室分类就要改两处命名与计数逻辑 —— 而这类「两处都该改」
 * 的地方，实际结果往往只改了一处，然后表现为「这个页面有 7 个科室、那个页面 6 个」。
 *
 * <h3>为什么只发事件、不做路由</h3>
 * 「点科室」在医生咨询页是换 `?dept=`，「点我的预约」是跳 `/appointments`；
 * 而这两个动作在另一个页面里是同一组目的地。把 `router.push` 写进组件意味着
 * 组件知道了两次使用方的路由规划，将来第三个使用方出现时就得改组件。
 */
defineProps<{
  departments: DepartmentGroup[]
  /** 「全部科室」的医生总数 */
  total: number
  /** 当前选中项：'' 全部科室 / 科室名 / 'appointments' */
  activeKey: string
}>()

const emit = defineEmits<{
  (e: 'select', department: string): void
  (e: 'mine'): void
}>()

/** 与 `activeKey` 约定的哨兵值。导出让页面不用手写这个魔法字符串 */
const MINE_KEY = 'appointments'
</script>

<style scoped>
.subnav {
  position: sticky;
  /* 与顶部导航下沿留出呼吸；不贴 0 是因为顶栏是半透明玻璃，贴上去会被看穿 */
  top: 20px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: var(--shadow-card);
}

.subnav-title {
  /* 上方有分隔线（自带 6px 外边距），这里的上外边距只需补一点点，否则读成「悬空」 */
  margin: 2px 10px 6px;
  color: var(--text-3);
  font-size: 11.5px;
  letter-spacing: 0.04em;
}

.subnav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 38px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--radius-ctrl);
  background: transparent;
  color: var(--text-2);
  font-family: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}

.subnav-item:hover {
  background: var(--panel-bg);
  color: var(--text-1);
}

/*
 * 选中态：--primary-soft 底 + --primary-dark 字（约 5.5:1），左缘 3px 主色条标层级。
 * ⚠️ 文字必须是 --primary-dark 而不是 --primary：后者压 --primary-soft 只有 4.44:1，
 *    差一点不到 AA。这条在本项目已经踩过一次，别再改回去。
 */
.subnav-item.is-on {
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-weight: 600;
  box-shadow: inset 3px 0 0 var(--primary);
}

.si-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.si-count {
  flex: none;
  color: var(--text-3);
  font-size: 11.5px;
}

/* 选中项的数字也要跟着变深：--text-3 压在 --primary-soft 上只有约 3.4:1 */
.subnav-item.is-on .si-count {
  color: var(--primary-dark);
  opacity: 0.75;
}

.si-icon {
  flex: none;
  width: 15px;
  height: 15px;
}

.subnav-sep {
  height: 1px;
  margin: 6px 8px;
  /* 不用 --panel-bg：它和悬停态的 item 底色同值，那条线会跟着「消失」 */
  background: var(--card-border);
}

/*
 * 窄屏：sticky 侧栏在一列布局里没有意义（它会盖住内容），
 * 改成横向滚动的 chip 条。媒体查询必须排在基础规则之后（硬约定 #26）。
 */
@media (max-width: 900px) {
  .subnav {
    position: static;
    flex-direction: row;
    gap: 6px;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .subnav-title,
  .subnav-sep {
    display: none;
  }

  .subnav-item {
    flex: none;
    width: auto;
    height: 34px;
    padding: 0 12px;
    /* 横向条里的选中态不再用左缘竖条，改用整块底色 */
    box-shadow: none;
  }
}
</style>
