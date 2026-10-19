<template>
  <div class="auth-wrap">
    <!--
      最外围背景：双通道 WebGL2 马赛克透镜（见 components/MosaicLens.vue）。
      - `pointer-events: none` 不会挡住表单 —— 组件内部是监听 window 的指针事件，
        不依赖 canvas 命中。
      - 颜色不在这里传，走 tokens.css 的 --lens-bg / --lens-c1 / --lens-c2。
    -->
    <div class="auth-lens" aria-hidden="true">
      <MosaicLens />
    </div>

    <!-- 卡片外再套一层「透明玻璃框」：半透明白 + 细亮边 + 内高光 -->
    <div class="auth-frame">
      <div class="auth-card" :class="{ 'is-form': isForm }">
        <!-- 白点漂移场（WebGL 装饰层，含三件套降级；见 components/DotField.vue） -->
        <DotField />

        <!--
          品牌区两份：左份初始可见、右份初始隐藏。
          白板从右滑到左的过程中，左份消散、右份在原位置浮现 ——
          「比例 2:3 = 操作页 40% / 简介页 60%」这件事因此是**结构性**的：
          两页宽度恒定，切换只是左右位置互换，不靠两套不同宽度的样式去凑。
        -->
        <BrandPanel class="brand-left" />
        <BrandPanel class="brand-right" />

        <!-- 操作页：白色面板，宽 40%（2 份），从右侧滑到左侧 -->
        <div class="auth-action">
          <!-- ============ 第一屏：选身份 ============ -->
          <div class="screen" :class="{ 'is-on': !showForm }">
            <h2 class="form-title">欢迎回来</h2>
            <p class="form-sub">请选择你的身份以继续</p>

            <div v-reveal="'grid'" class="role-list">
              <button
                v-for="r in ROLES"
                :key="r.key"
                class="role-item"
                type="button"
                @click="pickRole(r.key)"
              >
                <span class="role-ico" v-html="r.icon" />
                <span class="rtext">
                  <span class="rname">{{ r.title }}</span>
                  <span class="rsub">{{ r.short }}</span>
                </span>
              </button>
            </div>
          </div>

          <!-- ============ 第二屏：登录表单 ============ -->
          <div class="screen" :class="{ 'is-on': showForm }">
            <button class="back-role" type="button" @click="backToRole">← 换个身份</button>

            <h2 class="form-title">{{ roleMeta.title }}</h2>
            <p class="form-sub">{{ roleMeta.sub }}</p>

            <a-form :model="formState" :rules="rules" layout="vertical" @finish="onFinish">
              <a-form-item label="用户名" name="username">
                <a-input
                  v-model:value="formState.username"
                  size="large"
                  placeholder="请输入用户名"
                  autocomplete="username"
                />
              </a-form-item>

              <a-form-item label="密码" name="password">
                <a-input-password
                  v-model:value="formState.password"
                  size="large"
                  placeholder="请输入密码"
                  autocomplete="current-password"
                />
              </a-form-item>

              <a-button
                type="primary"
                html-type="submit"
                size="large"
                block
                :loading="loading"
                class="submit"
              >
                {{ roleMeta.btn }}
              </a-button>
            </a-form>

            <!--
              注册入口只给居民与医生。管理员的账号由系统统一分配 ——
              这不是前端藏按钮那么简单（后端 /auth/register 也只放行这两种角色），
              这里不显示只是不给出「点了会失败」的入口。
            -->
            <template v-if="!isAdminRole">
              <div class="divider">还没有账号？</div>
              <a-button size="large" block @click="router.push('/register')">注 册</a-button>
            </template>
            <p v-else class="admin-note">管理员账号由系统统一分配，如遗忘密码请联系平台运维。</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth'
import BrandPanel from '@/components/BrandPanel.vue'
import DotField from '@/components/DotField.vue'
import MosaicLens from '@/components/MosaicLens.vue'
import type { Rule } from 'ant-design-vue/es/form'

const router = useRouter()
const authStore = useAuthStore()

type RoleKey = 'user' | 'doctor' | 'admin'

const loading = ref(false)
/** 卡片是否处于「已选身份」态。它驱动两件事：白板左移、品牌文字交叉淡化 */
const isForm = ref(false)
/** 两屏内容实际切换的时机 —— 比 isForm 滞后 180ms，见下方 pickRole 的注释 */
const showForm = ref(false)
/** 选中的身份。只影响文案与「有没有注册入口」，**不影响登录后的跳转** —— 见 onFinish */
const picked = ref<RoleKey>('user')

const formState = reactive({ username: '', password: '' })

const rules: Record<string, Rule[]> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/*
 * 三个身份图标：统一「描边墨色」—— 24 栅格、stroke-width 1.8、round 端点与转角，
 * 颜色只走 currentColor（静止墨色 → 悬停主色）。
 *
 * 为什么用 v-html 内联 SVG 而不是 @ant-design/icons-vue：
 * 这一组是**结构性**的图形（人形 / 十字 / 盾牌），要求三者在同一栅格上等比、
 * 同一线宽、同一圆角。antd 图标族各图标的线宽与视觉重心不一致，混用会出现
 * 「一个粗一个细」；而自绘 SVG 能逐条控制到这些参数。它们只在登录页出现一次，
 * 也不构成需要复用组件的规模。
 */
const ROLES: {
  key: RoleKey
  title: string
  /** 身份卡上的短说明（三列并排，每列只有约 114px，长句会折成三行） */
  short: string
  /** 表单屏的标题下说明（整行可用，可以写完整一句话） */
  sub: string
  btn: string
  icon: string
}[] = [
  {
    key: 'user',
    title: '用户登录',
    short: '查看测评与咨询',
    sub: '使用社区发放的账号登录，查看测评与咨询记录',
    btn: '登 录',
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="12" cy="8" r="3.6" />
      <path d="M5.2 19.8c0-3.5 3-5.8 6.8-5.8s6.8 2.3 6.8 5.8" />
    </svg>`,
  },
  {
    key: 'doctor',
    title: '医生登录',
    short: '处理咨询与预警',
    sub: '使用执业医师账号登录，处理居民咨询与风险预警',
    btn: '进入工作台',
    // 十字刻意下移到胸口：肩弧顶点在 y≈12.5、笔画下缘约 13.4，
    // 竖臂从 15.6 起笔 → 留出约 2.2 单位的间隙，不会与肩弧粘连
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="12" cy="6.8" r="3.2" />
      <path d="M5.8 17.4c0-3 2.8-4.9 6.2-4.9s6.2 1.9 6.2 4.9" />
      <path d="M12 15.6v4.4M9.8 17.8h4.4" />
    </svg>`,
  },
  {
    key: 'admin',
    title: '管理员登录',
    short: '用户与内容维护',
    sub: '系统管理账号，管理用户、量表与内容发布',
    btn: '进入管理控制台',
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <path d="M12 3.4 5.8 5.8v5.6c0 3.9 2.6 6.7 6.2 8 3.6-1.3 6.2-4.1 6.2-8V5.8z" />
      <path d="M9.4 11.6l1.9 1.9 3.4-3.7" />
    </svg>`,
  },
]

const roleMeta = computed(() => ROLES.find((r) => r.key === picked.value) ?? ROLES[0])
const isAdminRole = computed(() => picked.value === 'admin')

/*
 * 换屏的时序。
 *
 * 白板滑动是 750ms，但两屏内容**不能在同一帧就换掉** —— 那样表单会「啪」地出现
 * 在还没滑到位的右半边，用户看到的是一次闪切换，而不是一次位移。
 * 做法：立刻进入 is-form（白板开始左移、品牌文字开始交叉淡化），
 * 180ms 后再切屏 —— 那时白板已走出约 1/4 行程，视线跟着它走，
 * 换屏恰好落在运动途中，看不出接缝。
 *
 * 返回时同样滞后（镜像），所以只写一个 180ms 的常量，不写两套时序。
 */
const SWITCH_DELAY = 180
let switchTimer: number | undefined

function scheduleSwitch(next: boolean) {
  if (switchTimer !== undefined) window.clearTimeout(switchTimer)
  switchTimer = window.setTimeout(() => {
    showForm.value = next
    switchTimer = undefined
  }, SWITCH_DELAY)
}

function pickRole(key: RoleKey) {
  picked.value = key
  isForm.value = true
  scheduleSwitch(true)
}

function backToRole() {
  isForm.value = false
  scheduleSwitch(false)
}

onBeforeUnmount(() => {
  if (switchTimer !== undefined) window.clearTimeout(switchTimer)
})

/**
 * 登录。
 *
 * ⚠️ 关键：跳转**按后端返回的真实角色**，不按用户选的身份。
 *    身份选择影响的是文案、按钮文字、有没有注册入口 —— 它是「入口引导」，
 *    不是鉴权声明。若按选项跳转，一个居民账号从「医生登录」进来就会被送进
 *    /admin/alerts，然后被路由守卫弹回去，用户只会看到「登录了但什么都没发生」。
 *    当两者不一致时给一句明确提示，比静默纠正更不容易让人困惑。
 */
async function onFinish() {
  loading.value = true
  try {
    const res = await authStore.login(formState.username, formState.password)
    const target = redirectByRole(res.role)
    if (!roleMatches(res.role)) {
      message.info(`该账号是「${roleName(res.role)}」，已进入对应工作台`)
    } else {
      message.success(`欢迎回来，${res.realName || res.username}`)
    }
    router.push(target)
  } catch {
    // 错误提示已在拦截器处理
  } finally {
    loading.value = false
  }
}

function roleMatches(role: string): boolean {
  if (picked.value === 'admin') return role === 'ADMIN'
  if (picked.value === 'doctor') return role === 'DOCTOR'
  return role === 'RESIDENT'
}

function roleName(role: string): string {
  if (role === 'ADMIN') return '管理员'
  if (role === 'DOCTOR') return '医生'
  return '居民用户'
}

function redirectByRole(role: string): string {
  if (role === 'ADMIN') return '/admin/dashboard'
  if (role === 'DOCTOR') return '/admin/alerts'
  return '/home'
}
</script>

<style scoped>
/* 认证页属「设计稿外」页面，按 tokens.css 自行延展，禁止硬编码色值。
   页面底交由 body 的渐变承担（见 global.css），这里只叠一层**提亮方向**的柔光 ——
   绝不压深：alpha 0.06 就会把 --text-2 从 5.40 压到 4.95，而页面底本来只有 1.32 的台阶。 */
.auth-wrap {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

/*
 * WebGL 背景层。
 *
 * `position: fixed` 而不是 absolute：页面在窄屏上会高于一屏，absolute 会让
 * canvas 随内容拉长（shader 里的比例假设会被破坏），fixed 才是「背景」的语义。
 *
 * `pointer-events: none` 是必须的 —— 它铺满整屏，不关命中的话会把整页的点击吃掉；
 * 而 MosaicLens 内部是监听 window 的 pointermove，不依赖 canvas 命中，所以不影响透镜效果。
 *
 * `z-index: 0` + 内容 `z-index: 1`：背景必须严格在卡片之下。
 */
.auth-lens {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

/*
 * 原来这里有一层白色径向光晕（.auth-wrap::before），用于给平面渐变底「提亮」。
 * 背景换成 WebGL 马赛克后它必须去掉：那两团 rgba(255,255,255,.55/.4) 会变成
 * 盖在画面左上/右上的一块白雾，把最精细的马赛克纹理糊掉。
 */

.auth-frame {
  position: relative;
  /* 必须压在 .auth-lens（z-index: 0）之上 */
  z-index: 1;
  /* 卡宽 1120（= 1120 减两侧 10px 框）：2:3 后操作页只剩 440px，
     再窄表单的输入框会掉到 300px 以下，所以卡整体加宽 */
  width: 1120px;
  max-width: 100%;
  padding: 10px;
  border-radius: 38px;
  background: rgba(255, 255, 255, 0.34);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow:
    0 24px 64px rgba(11, 18, 32, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.auth-card {
  position: relative;
  height: 622px;
  border-radius: 28px;
  overflow: hidden;
  /* 卡片底色 = 简介页的蓝。操作页滑走后露出的就是它 ——
     「移动之前的位置保持蓝色背景」是**结构性保证**，不靠额外动画去补 */
  background: linear-gradient(152deg, var(--primary) 0%, var(--primary-dark) 100%);
  box-shadow: 0 10px 30px rgba(11, 18, 32, 0.08);
  /* 把白点层的 screen 混合锁在卡内，不让它去和外框的玻璃底混合 */
  isolation: isolate;
}

.auth-card::after {
  /* 柔光提亮只落在右下角（文字垂直居中，四角本来留白）；
     alpha 压到 .16 —— 白字在「#1d4fd8 + 16% 白」上仍有 4.79:1，不低于 AA */
  content: '';
  position: absolute;
  right: -60px;
  bottom: -60px;
  width: 380px;
  height: 380px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.16), transparent 68%);
  pointer-events: none;
  /* -1：压到卡片背景之上、白点层之下 —— 绘制序 = 蓝渐变 → 柔光 → 点阵 → 文字 → 白板 */
  z-index: -1;
}

/* ---------- 品牌区两份的定位与交叉淡入淡出 ----------
 * 简介文字**不跟着白板平移**，只有 opacity 与 8px 的纵向位移。
 * 时序：淡出先起步（delay .06s），淡入等位置让开（delay .30s），两者有重叠段。
 * transition 取自「目标状态」的规则 → 正反两趟的时序自动镜像，不必写两套。
 */
.brand-left {
  left: 0;
  opacity: 1;
  transform: none;
  transition:
    opacity 0.55s var(--ease) 0.3s,
    transform 0.55s var(--ease) 0.3s;
}

.brand-right {
  left: 40%;
  opacity: 0;
  transform: translateY(8px);
  transition:
    opacity 0.5s var(--ease) 0.06s,
    transform 0.5s var(--ease) 0.06s;
}

.auth-card.is-form .brand-left {
  opacity: 0;
  transform: translateY(8px);
  transition-delay: 0.06s;
}

.auth-card.is-form .brand-right {
  opacity: 1;
  transform: none;
  transition-delay: 0.3s;
}

/* ---------- 操作页（白板） ----------
 * 位移量：初始左边缘在 60% 处，要落到 0 → 需走 1.5 倍自身宽度（100% = 自身宽）
 * ⚠️ 比例改成 2:3 后这里就是 -150%；改动比例必须同改这一处
 *
 * ⚠️ 定位**不能用 `left: 60%` + `width: 40%`**，两个百分比各自落到布局单位（1/64 px）
 *    后相加可能与卡片实际宽度差半个像素，右边缘就会漏出卡片的蓝底 ——
 *    表现为「选身份」那一屏最右侧一条 1px 蓝色竖线。
 *    换到表单屏后白板在左、右半边本来就该是蓝的，同一条缝自然看不见，
 *    所以这个 bug **只在第一屏出现**，别被「换个屏就好了」骗过去。
 *    正解：用 `right` 锚定右边缘，位置只由一个来源决定，不存在两段百分比相加的误差。
 *
 * ⚠️ 再外扩 1px 是刻意的保险，方向不对称：外层 `.auth-card` 有 `overflow: hidden`，
 *    溢出的一侧会被裁掉 —— 所以「多盖」永远不露馅，「少盖」必然露线。
 *    宽度同步 +1px 保证左边缘仍在 60%（1098−440−1 = 657.8 处，与原来一致），
 *    于是下面的 -150% 无需改动，2:3 比例也不受影响。 */
.auth-action {
  position: absolute;
  top: 0;
  bottom: 0;
  right: -1px;
  width: calc(40% + 1px);
  z-index: 2;
  background: var(--card-bg);
  /* 阴影只朝左：滑动时落在蓝底上，右侧文字区不会被暗晕染脏 */
  box-shadow: -24px 0 44px -28px rgba(11, 18, 32, 0.5);
  transition: transform 0.75s cubic-bezier(0.66, 0, 0.34, 1);
  will-change: transform;
}

.auth-card.is-form .auth-action {
  transform: translateX(-150%);
}

.screen {
  position: absolute;
  inset: 0;
  padding: 40px 36px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  opacity: 0;
  visibility: hidden;
  transform: translateX(16px);
  pointer-events: none;
  transition:
    opacity 0.34s var(--ease),
    transform 0.34s var(--ease),
    visibility 0.34s;
}

.screen.is-on {
  opacity: 1;
  visibility: visible;
  transform: none;
  pointer-events: auto;
}

/* ---------- 表单 ---------- */
.form-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-1);
}

.form-sub {
  margin: 6px 0 22px;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--text-3);
}

.back-role {
  align-self: flex-start;
  margin-bottom: 14px;
  padding: 0;
  border: 0;
  background: none;
  font-size: 13px;
  color: var(--text-3);
  cursor: pointer;
  transition: color var(--dur) var(--ease);
}

.back-role:hover {
  color: var(--primary);
}

.divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0 14px;
  font-size: 13px;
  color: var(--text-3);
}

.divider::before,
.divider::after {
  content: '';
  height: 1px;
  flex: 1;
  background: var(--panel-bg);
}

.admin-note {
  margin-top: 18px;
  padding: 11px 14px;
  background: var(--panel-bg);
  border-radius: var(--radius-ctrl);
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
}

/* antd 表单在这里要「长得像设计稿」：输入框 46px 高、focus 有一圈 3px 主色环。
   ConfigProvider 里 controlHeight 是 38（全站控件基准），登录页是唯一需要
   46px 大控件的地方，所以在页面内局部放大而不是改全局。 */
.auth-action :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.auth-action :deep(.ant-form-item-label > label) {
  font-size: 13.5px;
  color: var(--text-2);
}

.auth-action :deep(.ant-input-affix-wrapper),
.auth-action :deep(.ant-input) {
  height: 46px;
  padding: 0 14px;
  font-size: 15px;
  border-radius: var(--radius-ctrl);
  border-color: var(--ctrl-border);
}

/* 密码框的输入区在 affix-wrapper 里，不能再吃 46px 的 padding，否则文字会溢出 */
.auth-action :deep(.ant-input-affix-wrapper > .ant-input) {
  height: auto;
  padding: 0;
  border: 0;
}

.auth-action :deep(.ant-input-affix-wrapper-focused),
.auth-action :deep(.ant-input:focus) {
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.14);
}

.submit {
  height: 46px;
  font-size: 15px;
  font-weight: 500;
  margin-top: 4px;
}

/* ---------- 身份卡 ----------
 * 四条约束：
 *   ① 卡片无 border、无箭头：靠 --panel-bg 这层 L2 极淡面立住「这是一块可点的卡」；
 *   ② 图标只保留最里面的线性图形，去掉外层的方框面与描边，放大到 34px；
 *   ③ 悬停只改「色」不改「面」：底色恒为 --panel-bg，图标与全部文字一起转主色，
 *      另加一层极淡的主色外光晕表示可点 —— 不加位移、不换底色；
 *   ④ 副标取 --text-2 而不是 --text-3：--text-3 落在 --panel-bg 上只有 4.27
 *      （不到 AA 的 4.5），--text-2 给到 6.94。层级仍低于主标（text-1 + 600 粗）。
 *
 * ⚠️ 蓝字只能用 --primary 原色（--panel-bg 上 4.64 ✓ AA）。
 *    别「淡化主色」做副标：主色 76% 落在 --panel-bg 上只有 3.13，直接不达标。
 *    层级差靠字号 + 字重（15px/600 vs 11.5px/400）承担，不靠减淡颜色。
 * ⚠️ 这里刻意**不加 hover 位移** —— 位移与「只变色」的观感会互相削弱，
 *    而且这三张卡是卡内竖向排布的整块区域，挪动它们会让整屏跟着微抖。
 */
.role-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.role-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  width: 100%;
  /* 纵向内边距刻意不相等：图标上方视觉留白天生比下方文字重，
     上下取同值时整块会显得「往下坠」。22 / 20 是视觉居中。 */
  padding: 22px 10px 20px;
  text-align: center;
  border: 0;
  border-radius: 16px;
  background: var(--panel-bg);
  color: var(--ink);
  font-family: inherit;
  cursor: pointer;
  transition:
    color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.role-item:hover {
  color: var(--primary);
  box-shadow: 0 4px 14px -6px rgba(37, 99, 235, 0.34);
}

.role-ico {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  color: inherit;
}

.role-ico :deep(svg) {
  width: 34px;
  height: 34px;
  display: block;
}

.rtext {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.rname {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  transition: color var(--dur) var(--ease);
}

.rsub {
  font-size: 11.5px;
  line-height: 1.35;
  color: var(--text-2);
  transition: color var(--dur) var(--ease);
}

.role-item:hover .rname,
.role-item:hover .rsub {
  color: var(--primary);
}

/* ---------- 窄屏 ----------
 * 简介区收起，只留操作页（否则两栏挤到 244px 无法阅读）。
 * 简介区没了 → 白点层也一并收掉（DotField 内部会检测 clientWidth 为 0 并跳过着色）。 */
@media (max-width: 1180px) {
  .auth-frame {
    width: 520px;
  }
  .auth-card {
    height: auto;
  }
  .brand-left,
  .brand-right,
  :deep(.dot-field) {
    display: none;
  }
  .auth-action {
    position: static;
    width: 100%;
  }
  .screen {
    position: static;
    display: none;
    padding: 38px 34px;
  }
  .screen.is-on {
    display: flex;
  }
  /* 单栏时没有「另一边」可换，关掉平移（否则面板会被推出视口） */
  .auth-card.is-form .auth-action {
    transform: none;
  }
}
</style>
