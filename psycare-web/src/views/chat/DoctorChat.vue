<template>
  <div class="chat-page">
    <section class="chat-card">
      <!--
        标题栏：整页只有这一处说明「我在和谁说话」。
        医生资料（科室 / 评分 / 擅长）跟着会话接口一起回来，不额外请求 ——
        openSession 返回的 peer* 字段就是为这个标题栏准备的。

        视觉统一改造：标题栏本身是一层 L1 白面（显式写 --card-bg，与 .chat-card 同色），
        固定在卡片顶部、下方只有消息区滚动 —— 结构是「上部固定 + 下部滚动」，注释见 style。

        动效：这是整页唯一的页面框架区块，走 'header' recipe（90ms 递进）。
        ⚠️ 只给**标题栏**挂动效，消息区一律不挂 —— 消息是流式追加的，
          给气泡加入场动画会让「新消息到达」看起来像整块重排。
        ⚠️ 返回 / 「我的消息」两个元素自身带 transform / transition，
          用 data-anim="chrome"（纯淡入、位移恒等）而不是 rise，避免动画 cancel 时
          与元素自身的过渡叠加出一次多余的回弹。
      -->
      <header v-reveal="'header'" class="ct-head">
        <button type="button" class="ct-back" data-anim="chrome" aria-label="返回" @click="goBack">
          <span aria-hidden="true">←</span>
        </button>

        <RoleAvatar class="ct-avatar" role="doctor" data-anim="pop" />

        <div class="ct-ident" data-anim="rise">
          <p class="ct-name">
            <span class="ct-name-text">{{ peerName || '医生' }}</span>
            <span v-if="peerTitle" class="ct-title">{{ peerTitle }}</span>
          </p>
          <p class="ct-meta">
            <span v-if="peerDepartment">{{ peerDepartment }}</span>
            <template v-if="peerDepartment && peerRating !== null">
              <span class="ct-sep" aria-hidden="true">·</span>
            </template>
            <span v-if="peerRating !== null" class="ct-rating">
              ★ {{ peerRating.toFixed(1) }}
            </span>
            <template v-if="expertiseText">
              <span class="ct-sep" aria-hidden="true">·</span>
              <span class="ct-expertise">擅长 {{ expertiseText }}</span>
            </template>
          </p>
        </div>

        <router-link class="ct-link" data-anim="chrome" to="/messages">我的消息</router-link>
      </header>

      <!--
        安全提示：常驻一行，不做折叠也不做成弹窗。
        心理健康场景下「紧急情况别等回复」这句话的价值高于它占的那一行高度 ——
        而它不是危机判定（不做任何自动识别），只是一句静态声明，不会误伤正常对话。
      -->
      <p class="ct-notice">
        <span class="ct-notice-dot" aria-hidden="true"></span>
        医生会在看到消息后回复。如遇紧急情况，请直接拨打 <b>12356</b>（心理援助热线）或
        <b>120</b>。
      </p>

      <ChatPanel
        v-if="sessionId !== null"
        :session-id="sessionId"
        :peer-name="peerName"
        :can-send="canSend"
      />

      <!-- 打开失败 / 参数非法：给出口而不是白屏，用户需要知道下一步点哪里 -->
      <div v-else class="ct-state">
        <template v-if="loading">
          <p class="ct-state-title">正在连接…</p>
        </template>
        <template v-else>
          <p class="ct-state-title">{{ errorTitle }}</p>
          <p class="ct-state-desc">{{ errorDesc }}</p>
          <button type="button" class="ct-retry" @click="init">重试</button>
          <p class="ct-state-alt">
            或前往 <router-link to="/doctors">医生咨询</router-link> 重新选择
          </p>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatPanel from '@/components/ChatPanel.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import { openSession } from '@/api/chat'

/**
 * 居民端专有聊天页（`/messages/:doctorId`）。
 *
 * <h3>为什么从「抽屉」改成独立页面</h3>
 *
 * 在此之前，居民端有三个入口能开聊：医生咨询页、AI 推荐结果页、我的消息页。
 * 三处各自实现了一遍「遮罩 + 抽屉 + ChatPanel」，于是同一件事有三套 DOM、
 * 三套样式、三处 header 信息不一致；抽屉宽度 420px 也让一段正常长度的对话
 * 频繁换行。独立页面把三处收敛成一处，同时给了聊天足够的空间。
 *
 * <h3>为什么路由参数是 doctorId 而不是 sessionId</h3>
 *
 * 从「医生咨询」页进来时**会话还不存在**（会话在点「咨询」那一刻才建），
 * 所以 URL 里只可能带 doctorId。`POST /chat/sessions` 本身是幂等的
 * （已有进行中的会话就复用），进页面调一次即可，不会重复建会话。
 * 反过来说，如果路由用 sessionId，就必须在跳转前先请求一次 —— 多一次往返，
 * 还要处理「查到的会话已过期」的竞态。
 *
 * <h3>与「我的消息」的分工</h3>
 *
 * `/messages` 是会话列表（找得到人），本页是具体对话（说得上话）。
 * 聊过之后医生会自动出现在列表里 —— 判定条件是「该会话下至少 1 条消息」，
 * 落在后端 SQL，本页不需要做任何回写。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(true)
const sessionId = ref<number | null>(null)

/* ---- 对端信息（由 openSession 的 peer* 字段填充） ---- */
const peerName = ref('')
const peerTitle = ref('')
const peerDepartment = ref('')
const peerRating = ref<number | null>(null)
const peerExpertise = ref('')

const sessionStatus = ref('OPEN')

const errorTitle = ref('无法打开这个对话')
const errorDesc = ref('')

/** 会话已结束（CLOSED）就不能再发 —— 后端会返回 400，前端先挡住 */
const canSend = computed(() => sessionStatus.value === 'OPEN')

/**
 * 专长只取前 3 个：标题栏是单行省略号截断的，
 * 把十几个标签拼进去只会让后面的被截掉，不如自己收口。
 */
const expertiseText = computed(() => {
  const tags = (peerExpertise.value || '')
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
  if (!tags.length) return ''
  return tags.length > 3 ? `${tags.slice(0, 3).join('、')}等` : tags.join('、')
})

/**
 * 路由参数 → 医生 id。
 *
 * `<router-link to="/messages/abc">` 或手改地址会得到 NaN，
 * 在这里挡掉再发请求 —— 让后端返回 400 再弹一条「系统异常」是最差的体验。
 */
const doctorId = computed<number | null>(() => {
  const n = Number(route.params.doctorId)
  return Number.isInteger(n) && n > 0 ? n : null
})

async function init() {
  sessionId.value = null
  loading.value = true

  const id = doctorId.value
  if (id === null) {
    errorTitle.value = '链接无效'
    errorDesc.value = '这个地址里没有有效的医生编号。'
    loading.value = false
    return
  }

  try {
    const session = await openSession(id)
    sessionId.value = session.id
    sessionStatus.value = session.status
    peerName.value = session.peerName
    peerTitle.value = session.peerDesc ?? ''
    peerDepartment.value = session.peerDepartment ?? ''
    peerRating.value = session.peerRating ?? null
    peerExpertise.value = session.peerExpertise ?? ''
  } catch {
    // 具体错误（404 医生不存在 / 403 非医生对象）已由 request.ts 的拦截器弹出，
    // 这里只负责给一个「接下来能点哪里」的兜底视图。
    errorTitle.value = '无法打开这个对话'
    errorDesc.value = '这位医生可能已停止接诊或尚未通过资质审核。'
  } finally {
    loading.value = false
  }
}

/**
 * 返回上一页；没有站内上一页（直接打开链接或刷新过）则回消息中心。
 *
 * <p>不能简单地 `router.back()`：刷新后 history 里还有别的站点记录时，
 * 会把用户带出 PsyCare；也不能固定 `push('/messages')`，那会让
 * 「从推荐页进来 → 点返回」变得反直觉。
 */
function goBack() {
  const back = (window.history.state as { back?: string } | null)?.back
  if (back) router.back()
  else router.push('/messages')
}

// 同一路由换参数（如从消息中心切到另一位医生）时不会重建组件，必须手动重新初始化
watch(
  () => route.params.doctorId,
  (next, prev) => {
    if (next !== prev) init()
  },
)

onMounted(init)
</script>

<style scoped>
/*
 * 高度 = 整个可视区。DefaultLayout 的顶栏已移入侧栏（改成左侧导航），
 * 内容区不再有 64px 的顶栏占位，所以用 100vh —— 整页铺满且不产生页面滚动条，
 * 滚动只发生在消息区内部，输入框永远贴底可见。
 */
.chat-page {
  height: 100vh;
  padding: 20px var(--content-pad) 24px;
  box-sizing: border-box;
  display: flex;
  justify-content: center;
}

/*
 * 「不小不大」：宽度上限 940px。
 * 更宽会让一句话横跨半屏、眼睛来回扫；更窄则一段正常长度的话要折三行。
 * 940px 下气泡（74%）约 690px，一行能放下 40 来个汉字。
 */
.chat-card {
  width: 100%;
  max-width: 940px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

/* ---------- 标题栏 ---------- */
/*
 * 标题栏 = 一层 L1 白面（显式写 --card-bg，不靠外层 .chat-card 的底色兜底）。
 * flex: none 让它固定在顶部 —— 页面结构是「上部固定（标题栏 + 安全提示）+ 下部消息区滚动」，
 * 滚动只发生在 ChatPanel 内部，输入区永远贴底可见（该逻辑不属于本页，勿在此处改动）。
 */
.ct-head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 20px;
  background: var(--card-bg);
  border-bottom: 1px solid var(--card-border);
}

.ct-back {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  /* 可交互控件用 --ctrl-border（比 --card-border 深一档，与 PageHeader 的返回键一致） */
  border: 1px solid var(--ctrl-border);
  border-radius: 50%;
  background: transparent;
  color: var(--text-2);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  transition:
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.ct-back:hover {
  border-color: var(--primary);
  color: var(--primary);
  transform: translateX(-1px);
}

/* 头像直径走 CSS 变量而不是 :size 内联 —— 窄屏媒体查询要能把它改小，
   而内联样式的优先级高于任何样式表规则，传 :size 就改不动了。 */
.ct-avatar {
  --ra-size: 52px;
  flex: none;
}

.ct-ident {
  flex: 1;
  min-width: 0;
}

.ct-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  font-family: var(--font-serif);
  font-size: 16.5px;
  font-weight: 600;
  color: var(--text-1);
}

.ct-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 职称徽标：--primary-soft 底 + --primary-dark 文字（5.71:1）。
   ⚠️ 不要写回 --primary：它压在 --primary-soft 上只有 4.44:1，差一点不到 AA 的 4.5。 */
.ct-title {
  flex: none;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-family: var(--font-sans);
  font-size: 11.5px;
  font-weight: 400;
}

/* 单行省略：窄屏时「擅长 …等」被截断，而不是把标题栏撑成两行。
   这一行落在 L1 白面上，--text-3 的 4.8:1 达标。 */
.ct-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ct-rating {
  flex: none;
  color: var(--text-2);
  font-weight: 600;
}

/* 「·」分隔符是文字，走文字三档里最轻的一档（原来是 --card-border ——
   那是描边色，压在白面上约 1.2:1，等于没画）。 */
.ct-sep {
  flex: none;
  color: var(--text-3);
}

.ct-expertise {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ct-link {
  flex: none;
  padding: 6px 14px;
  /* 可交互控件：与 .ct-back 同一档描边 */
  border: 1px solid var(--ctrl-border);
  border-radius: var(--radius-pill);
  color: var(--text-2);
  font-size: 12.5px;
  text-decoration: none;
  white-space: nowrap;
  transition:
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.ct-link:hover {
  border-color: var(--primary);
  color: var(--primary);
}

/* ---------- 安全提示 ---------- */
/*
 * 卡内浅色分区（L2）：只用底色区分，不叠阴影（面分级规则 ②）。
 * 正文走 --text-2 —— 这一条压在 --risk-b-soft 浅底上，不是白面，--text-3 会掉到 4.5 以下。
 */
.ct-notice {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 8px 20px;
  background: var(--risk-b-soft);
  border-bottom: 1px solid var(--risk-b-border);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-2);
}

/* 热线的强调：--risk-b-ink（同族深一档）。
   ⚠️ 原来是 --risk-c 实色 —— 实色风险色当文字用是错的：--risk-c 压在 --risk-b-soft 上
     只有 2.6 上下，远不到 AA。soft 底上的文字必须用同族 --risk-*-ink（--risk-b-ink 是 4.87:1）。 */
.ct-notice b {
  color: var(--risk-b-ink);
}

.ct-notice-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--risk-b);
}

/* ---------- 错误 / 加载态 ---------- */
.ct-state {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 24px;
  text-align: center;
}

.ct-state-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.ct-state-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-3);
}

/* 主按钮：主色实底 + --on-primary 白字（5.17:1）+ 控件圆角。
   圆角由 pill 改为 --radius-ctrl：按钮属于控件档，与全站其它主按钮（通知页 / 历史页）一致。 */
.ct-retry {
  margin-top: 6px;
  padding: 8px 22px;
  border: 1px solid var(--primary);
  border-radius: var(--radius-ctrl);
  background: var(--primary);
  color: var(--on-primary);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: var(--shadow-primary);
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.ct-retry:hover {
  background: var(--primary-dark);
  border-color: var(--primary-dark);
}

.ct-state-alt {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}

.ct-state-alt a {
  color: var(--primary);
  text-decoration: none;
}

/* ChatPanel 契约是「填满父容器」，这里把高度让给它 */
.chat-card > :deep(.chat-panel) {
  flex: 1;
  min-height: 0;
}

/* ---------- 窄屏 ---------- */
@media (max-width: 720px) {
  .chat-page {
    height: 100vh;
    padding: 12px 12px 16px;
  }

  .ct-head {
    gap: 10px;
    padding: 12px 14px;
  }

  .ct-avatar {
    --ra-size: 46px;
  }

  .ct-notice {
    padding: 8px 14px;
  }

  /* 标题栏放不下三件事，把「我的消息」收进返回按钮的语义里 */
  .ct-link {
    display: none;
  }
}
</style>
