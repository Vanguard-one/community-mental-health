<template>
  <div
    class="scale-card"
    :class="{ disabled }"
    role="button"
    tabindex="0"
    @click="onClick"
    @keyup.enter="onClick"
  >
    <div class="body">
      <h3 class="name font-serif">{{ name }}</h3>
      <span v-if="dimension" class="tag">{{ dimension }}</span>
      <p v-if="description" class="desc">{{ description }}</p>
    </div>

    <div class="foot">
      <slot name="footer">
        <span class="action">开始测评 →</span>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
/*
 * 量表卡片（Uiverse / Javierrocadev 的卡片格式改写）。
 *
 * <h3>只保留三样内容</h3>
 * 表名 / 维度标签 / 简要描述 + 一个「开始测评」按钮条。
 * 按用户要求去掉了：量表编码（PHQ9）、题数、满分。
 * 对应地，`code` / `itemCount` / `maxScore` 三个 prop **一并删除** ——
 * 留着不用的话，父组件传进来的值会作为 fallthrough attribute 落到根元素上，
 * 在 DOM 里生成 `itemcount="9"` 这种无意义属性。
 */
const props = withDefaults(
  defineProps<{
    name: string
    /** 适用维度 / 分类，如「抑郁」「焦虑」 */
    dimension?: string
    description?: string
    disabled?: boolean
  }>(),
  {
    dimension: '',
    description: '',
    disabled: false,
  },
)

const emit = defineEmits<{ (e: 'select'): void }>()

function onClick() {
  if (props.disabled) return
  emit('select')
}
</script>

<style scoped>
/*
 * 卡片本体。
 *
 * <h3>底色为什么是「白 + 主色 8%」而不是样例的半透明紫</h3>
 * 样例 `rgba(105,13,197,0.103)` 是压在**深色页面**上的——透出来的仍是暗底，
 * 所以近白色文字（aliceblue）能读。本项目的页面底是浅蓝渐变
 * （#c6d9f5 → #d3e1f8），同样的半透明色透出来还是浅色，卡片会整块糊进背景 ——
 * 而项目有一条明确的规则：贴在页面底上的「面」只能是白卡或主色。
 *
 * 所以改成「白卡为主、掺 8% 主色」：
 *   ① 对页面两端仍有 **1.30 / 1.20** 的分离度（白卡基准是 1.32），卡片立得住；
 *   ② 肉眼看是一张浅蓝卡，而不是纯白；
 *   ③ 文字全部达标：--text-1 16.5、--text-2 6.8、--primary-dark 5.9。
 */
.scale-card {
  position: relative;
  /* 裁掉溢出卡片的装饰圆环；顺带让底部按钮条的直角被卡片圆角带圆 */
  overflow: hidden;
  display: flex;
  flex-direction: column;
  height: 100%;
  /* 与样例 250×200 的比例感对齐：内容自然高度约 176，撑到 200 才不显扁 */
  min-height: 200px;
  background: color-mix(in srgb, var(--primary) 8%, var(--card-bg));
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  transition: box-shadow var(--dur) var(--ease);
}

.scale-card:not(.disabled):hover {
  box-shadow: var(--shadow-hover);
}

.scale-card.disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

/*
 * 装饰圆环 —— 这套卡片格式的招牌交互。
 * 静止：偏在左上、模糊 0.5rem，像一团还没对焦的光；
 * 悬停：放大到 140、移到上方中央、模糊归零 —— 焦点对上的那一下。
 *
 * ⚠️ 用 border 画环而不是实心圆：样例就是 35px 的粗边框，
 *    实心圆在浅底上会变成一块压死文字的色斑。
 * ⚠️ 颜色必须自己给（样例的 rgba(255,255,255,.102) 在白卡上等于不存在）。
 */
.scale-card::before {
  content: '';
  position: absolute;
  /*
   * 位置与尺寸是按「露出来的弧长」调的，不是随手取的百分比：
   *   top 百分比是相对**卡片高度**算的（约 200px）。
   *   静止：116px 的环，top -34% ≈ -68px → 露在卡内约 48px（原先只有 20px，
   *         显得整团光被切在卡片外面、只剩一条边）。
   *   悬停：152px，top -26% ≈ -52px → 露在卡内约 100px，是一道完整的上方弧。
   * ⚠️ 两个尺寸要**同时**改（宽度 + 高度），只改一个会把圆压成椭圆。
   */
  top: -34%;
  left: -20%;
  width: 116px;
  height: 116px;
  border-radius: 50%;
  border: 35px solid color-mix(in srgb, var(--primary) 18%, transparent);
  filter: blur(0.5rem);
  /* 显式列出四个属性，不写 `all` —— `all` 会连带过渡掉无关属性，改成别的属性时很难查 */
  transition:
    width 0.8s ease,
    height 0.8s ease,
    top 0.8s ease,
    left 0.8s ease,
    filter 0.8s ease;
  /* 纯装饰，不能吃点击 */
  pointer-events: none;
}

.scale-card:not(.disabled):hover::before {
  width: 152px;
  height: 152px;
  top: -26%;
  left: 50%;
  filter: blur(0);
}

/*
 * 内容层必须显式抬到圆环之上。
 * `::before` 是**定位元素**，会盖在静态流内容上面 —— 不写 z-index 的话，
 * 那圈模糊的光会糊在表名上（样例没写是因为它的环足够淡、且压在深色底上不明显）。
 */
.body {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 16px 18px;
}

.name {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-1);
  margin: 0;
  line-height: 1.35;
}

/*
 * 维度标签。改成**描边式**而不是实底：
 *  - 实底 --primary-soft 落在已经带蓝调的卡片底上，pill 的边界几乎看不出来；
 *  - 实底 + --primary 文字只有 4.44（不达 AA），本项目一律改用 --primary-dark。
 * 描边式两头都解决：描边 4.7、文字 5.9，在白卡与浅蓝卡上都立得住。
 */
.tag {
  font-size: 12px;
  line-height: 1;
  padding: 4px 10px;
  border: 1px solid var(--primary);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--primary-dark);
  white-space: nowrap;
}

.desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-2);
  /* 最多三行：四张卡的描述长度不一，不截断会让同一行的卡片高度对不齐 */
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/*
 * 底部按钮条（样例的 .icons 位置）。
 * 这里是**整条可点**，不是独立按钮 —— 卡片整体就是入口，
 * 再套一层 button 会出现「点外圈能进、点按钮也能进」的两套语义。
 *
 * 半透明白底而不是实心主色：样例的按钮条也是近白的半透明，
 * 主色留给上面那圈光和标签，卡片才不会变成一块蓝板。
 */
.foot {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  background: color-mix(in srgb, var(--card-bg) 72%, transparent);
}

.action {
  font-size: 13px;
  font-weight: 600;
  /* 落在半透明白条上，主色 4.9 达标 */
  color: var(--primary);
}
</style>
