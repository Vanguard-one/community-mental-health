<template>
  <!--
    登录页品牌区（简介页）。

    <h3>为什么是个组件</h3>
    卡片切换时这块内容**同时存在于左右两处**：左份初始可见、右份初始隐藏，
    白板从右滑到左的过程中，左份消散、右份在原位置浮现（时序见 Login.vue 的注释）。
    两份内容必须**逐字一致** —— 用 v-for 复制 DOM 而不是把标签写两遍，
    这样「改一处两边都变」由结构保证，也省得两套 data-anim 属性需要人工同步。

    <h3>动效</h3>
    每份各自带 `v-reveal="'brand'"`，走 hero 档节奏（160ms/step）：
    标识 → 标题逐行升起 → 下划线从左描出 → 副标 → 三条特性 → 勾图标弹入。
    两份一起播 —— 白板滑到哪一边，那边的文字都已经是就位状态。

    <h3>悬停</h3>
    只动 transform / 背景色，两者都在合成层，不触发 layout 与重绘扩散。
    ⚠️ 这里的 transform 能生效，是因为入场动画播完后会被 cancel() 并清掉 fill；
       若动画用 fill:forwards 一直挂着，这些 hover 会被整条压掉。
  -->
  <div v-reveal="'brand'" class="brand-text">
    <div class="brand-logo" data-anim="chrome">
      <span class="mark">
        <svg viewBox="0 0 24 24" width="19" height="19" aria-hidden="true">
          <path
            d="M2 12h4l2.5-6 3 12 3-9 2 3h5.5"
            fill="none"
            stroke="#fff"
            stroke-width="2.1"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </span>
      <span class="name">PsyCare</span>
    </div>

    <h2 class="brand-title">
      <span class="bt-line" data-anim="line">让每一次倾诉</span>
      <span class="bt-line" data-anim="line">
        都被<span class="bt-em">听见<i class="bt-under" data-anim="draw" /></span>
      </span>
    </h2>

    <p class="brand-desc" data-anim="line">
      社区心理健康评测与咨询系统 —— 3 分钟完成一次专业自评，结果仅你与接诊医生可见。
    </p>

    <ul class="brand-feats">
      <li data-anim="right">
        <span class="ok" data-anim="pop">✓</span>
        专业量表：抑郁 / 焦虑 / 躯体 / 睡眠
      </li>
      <li data-anim="right">
        <span class="ok" data-anim="pop">✓</span>
        AI 助手 7×24 小时在线陪伴
      </li>
      <li data-anim="right">
        <span class="ok" data-anim="pop">✓</span>
        真人医生按科室接诊，全程可追溯
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
// 纯展示组件：内容写死在这里，两份副本（左/右）共用同一份模板
</script>

<style scoped>
.brand-text {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 60%;
  padding: 46px 42px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  color: #fff;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 42px;
}

.mark {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.18);
  transition:
    transform 0.34s var(--ease-prod),
    background-color 0.34s var(--ease-prod);
}

.brand-logo:hover .mark {
  transform: rotate(-6deg) scale(1.07);
  background-color: rgba(255, 255, 255, 0.3);
}

.name {
  font-family: var(--font-serif);
  font-size: 20px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.5px;
}

.brand-title {
  font-size: 30px;
  font-weight: 600;
  color: #fff;
  line-height: 1.4;
  margin-bottom: 14px;
}

/* 标题拆成两行后必须显式成块，否则两个 span 会挤在一行 */
.bt-line {
  display: block;
}

/*
 * 「听见」二字的 hairline 下划光带：入场时从左向右描出。
 * ⚠️ 用真实元素 <i> 而不是 ::after —— 伪元素无法被 WAAPI 直接驱动，
 *    也没法参与统一的复位 / 取消，只有真实元素才能和别的动效共用同一套收尾逻辑。
 */
.bt-em {
  position: relative;
}

.bt-under {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 2px;
  height: 1px;
  background: rgba(255, 255, 255, 0.6);
  transform-origin: left center;
}

.brand-desc {
  font-size: 14px;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.82);
  margin-bottom: 34px;
}

.brand-feats {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.94);
}

.brand-feats li {
  display: flex;
  align-items: center;
  gap: 10px;
  transition: transform 0.3s var(--ease-prod);
}

.brand-feats li:hover {
  transform: translateX(6px);
}

.brand-feats .ok {
  width: 20px;
  height: 20px;
  flex: none;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.22);
  font-size: 11px;
  transition:
    transform 0.3s var(--ease-prod),
    background-color 0.3s var(--ease-prod);
}

.brand-feats li:hover .ok {
  transform: scale(1.1);
  background-color: rgba(255, 255, 255, 0.42);
}
</style>
