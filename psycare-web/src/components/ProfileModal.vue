<template>
  <!--
    个人资料面板（三端共用）。

    <h3>为什么抽成一个组件，而不是在 SideRail 里内联</h3>
    SideRail 只有 64px 宽，是导航骨架；资料编辑是一整块表单（头像 + 四字段 + 改密码）。
    内联进去会让一个「只负责导航」的组件同时承担表单状态机、校验、上传、
    二次确认四件事 —— 之后任何一处想复用（比如管理端点自己头像进来），
    都得把 SideRail 一起拖过去。

    <h3>为什么弹窗宽度 520 而不是更宽</h3>
    表单是单列纵向排布，字段只有 3 + 3 个，520 是「一行文字不会被挤到换行、
    又不会在宽屏上显得空荡」的折中。这与 AiChat 的 468（三段说明文字）
    是两个不同用途的宽度，不是随手填的。
  -->
  <a-modal
    v-model:open="open"
    :footer="null"
    :width="520"
    centered
    title="个人资料"
    :mask-closable="!saving && !pwdSaving"
  >
    <div v-if="loading" class="pf-loading">
      <a-spin />
    </div>

    <div v-else class="pf">
      <!-- ========== 身份条：头像 + 姓名 + 角色 ========== -->
      <div class="pf-id">
        <!--
          头像本身就是「更换」入口 —— 额外放一个按钮会让这块区域出现两个
          指向同一动作的控件，用户会犹豫点哪个。
          用 button 而不是 div：键盘可达、有焦点环，且语义就是「按一下就做事」。
        -->
        <button
          type="button"
          class="pf-ava"
          :disabled="uploading"
          :aria-label="draft.avatarUrl ? '更换头像' : '上传头像'"
          @click="pickFile"
        >
          <RoleAvatar :role="avatarRole" :size="64" :src="draft.avatarUrl" />
          <span class="pf-ava-mask">{{ uploading ? '上传中' : '更换' }}</span>
        </button>

        <div class="pf-id-text">
          <p class="pf-name">{{ draft.realName || '未设置姓名' }}</p>
          <div class="pf-id-meta">
            <!-- 角色标签只读：账号类型不归自己改（那是管理员职能） -->
            <span class="pf-role">{{ profile?.roleLabel || roleLabelFallback }}</span>
            <span class="pf-account mono">@{{ draft.username || '—' }}</span>
          </div>
        </div>

        <button
          v-if="draft.avatarUrl"
          type="button"
          class="pf-remove"
          :disabled="uploading"
          @click="removeAvatar"
        >
          移除头像
        </button>

        <!-- 原生 input + 自定义上传，不引 a-upload（同 BannerManage 的理由：
             a-upload 会带进进度条/拖拽一整批依赖，vendor-antd 有 800 kB 预算） -->
        <input
          ref="fileRef"
          class="pf-file"
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp"
          @change="onPickFile"
        />
      </div>

      <p class="pf-hint">支持 jpg / png / gif / webp，不超过 5 MB。建议用正方形图片，非正方形会按中心裁切。</p>

      <!-- ========== 基本资料 ========== -->
      <section class="pf-section">
        <h3 class="pf-title">基本资料</h3>

        <a-form ref="basicRef" :model="draft" :rules="basicRules" layout="vertical">
          <a-form-item label="登录账号" name="username">
            <a-input
              v-model:value="draft.username"
              :maxlength="32"
              placeholder="字母开头，4-32 位，可含数字与下划线"
            />
          </a-form-item>

          <a-form-item label="姓名" name="realName">
            <a-input v-model:value="draft.realName" :maxlength="20" placeholder="2-20 个字符" />
          </a-form-item>

          <a-form-item label="手机号" name="phone">
            <!-- 选填：居民注册时就不采集手机号，强制必填会把存量用户挡在门外 -->
            <a-input v-model:value="draft.phone" :maxlength="11" placeholder="选填" />
          </a-form-item>
        </a-form>

        <div class="pf-actions">
          <a-button type="primary" :loading="saving" :disabled="uploading" @click="onSaveProfile">
            保存资料
          </a-button>
        </div>
      </section>

      <div class="pf-divider" />

      <!-- ========== 修改密码 ========== -->
      <section class="pf-section">
        <h3 class="pf-title">修改密码</h3>

        <a-form ref="pwdRef" :model="pwd" :rules="pwdRules" layout="vertical">
          <a-form-item label="当前密码" name="oldPassword">
            <a-input-password
              v-model:value="pwd.oldPassword"
              autocomplete="current-password"
              placeholder="请输入当前使用的密码"
            />
          </a-form-item>

          <a-form-item label="新密码" name="newPassword">
            <a-input-password
              v-model:value="pwd.newPassword"
              autocomplete="new-password"
              placeholder="6-32 位"
            />
          </a-form-item>

          <a-form-item label="确认新密码" name="confirmPassword">
            <a-input-password
              v-model:value="pwd.confirmPassword"
              autocomplete="new-password"
              placeholder="再输入一次新密码"
            />
          </a-form-item>
        </a-form>

        <div class="pf-actions">
          <a-button :loading="pwdSaving" @click="onChangePassword">更新密码</a-button>
        </div>

        <p class="pf-hint">
          修改成功后需要用新密码重新登录。
        </p>
      </section>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import type { FormInstance, Rule } from 'ant-design-vue/es/form'
import RoleAvatar from '@/components/RoleAvatar.vue'
import {
  changeMyPassword,
  getMyProfile,
  updateMyProfile,
  uploadAvatar,
} from '@/api/profile'
import { useAuthStore } from '@/stores/auth'
import type { ProfileVO } from '@/types'

const props = defineProps<{
  /** 受控开合。用法：`<ProfileModal v-model:open="profileOpen" />` */
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
}>()

const open = computed({
  get: () => props.open,
  set: (v: boolean) => emit('update:open', v),
})

const router = useRouter()
const authStore = useAuthStore()

/* ==================== 状态 ==================== */

/** 首屏拉取中。**不要用 authStore 里的缓存先渲染** —— 那是登录那一刻的快照，
    换了设备改了资料之后它就是过期的，闪一下旧值再变成新值比空载更让人困惑。 */
const loading = ref(false)
const saving = ref(false)
const pwdSaving = ref(false)
const uploading = ref(false)

const profile = ref<ProfileVO | null>(null)

/**
 * 表单草稿。
 *
 * 为什么不直接 v-model 到 profile：取消（点遮罩/右上角 ×）应当丢弃修改。
 * 直接绑 profile 的话，用户改了三个字段然后关掉弹窗，再打开看到的是
 * 未保存的脏值 —— 而界面上没有任何「未保存」的标记。
 */
const draft = reactive({
  username: '',
  realName: '',
  phone: '',
  avatarUrl: '',
})

const pwd = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const basicRef = ref<FormInstance>()
const pwdRef = ref<FormInstance>()
const fileRef = ref<HTMLInputElement | null>(null)

/** 头像用的角色。后端 role 是 RESIDENT/DOCTOR/ADMIN，RoleAvatar 只认小写四值 */
const avatarRole = computed(() => {
  const r = (profile.value?.role || authStore.role || '').toLowerCase()
  return r === 'doctor' || r === 'admin' ? (r as 'doctor' | 'admin') : 'user'
})

const roleLabelFallback = computed(() => {
  const r = (profile.value?.role || authStore.role || '').toUpperCase()
  if (r === 'DOCTOR') return '医生'
  if (r === 'ADMIN') return '管理员'
  return '居民用户'
})

/* ==================== 校验规则 ==================== */

/**
 * 前端规则与后端注解**刻意重复**。这不是冗余：
 * 前端负责「即时、不用等一次往返就能指出哪里错了」，
 * 后端负责「无论请求从哪来都必须成立」。删掉任何一边都会出问题 ——
 * 只有后端则每次都要点保存才知道错在哪；只有前端则绕过前端就失效。
 * 两边的正则以 ProfileUpdateRequest 为准，改一处必须改另一处。
 */
const basicRules: Record<string, Rule[]> = {
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]{3,31}$/,
      message: '账号需以字母开头，4-32 位，只能包含字母、数字和下划线',
      trigger: 'blur',
    },
  ],
  realName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { min: 2, max: 20, message: '姓名长度需在 2-20 个字符之间', trigger: 'blur' },
  ],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
}

const pwdRules: Record<string, Rule[]> = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '新密码长度需在 6-32 位之间', trigger: 'blur' },
    {
      // 新旧相同的校验放在前端是为了少一次往返；后端同样有一道
      // （见 ProfileService#changePassword），因为「密码没变」在服务端看来
      // 不是格式问题而是业务规则。
      validator: (_rule: Rule, value: string) => {
        if (value && value === pwd.oldPassword) {
          return Promise.reject(new Error('新密码不能与当前密码相同'))
        }
        return Promise.resolve()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次新密码', trigger: 'blur' },
    {
      validator: (_rule: Rule, value: string) => {
        if (value && value !== pwd.newPassword) {
          return Promise.reject(new Error('两次输入的新密码不一致'))
        }
        return Promise.resolve()
      },
      trigger: 'blur',
    },
  ],
}

/* ==================== 打开时载入 ==================== */

/**
 * 载入。
 *
 * ⚠️ watch 必须写在这里（`loading` / `draft` / `basicRef` 等 const 之后）。
 * 它定义时就会执行一次 getter，若提前到声明之前会命中暂时性死区，
 * 报 `Cannot access 'xxx' before initialization` → setup 崩溃 → 整页白屏，
 * 而 vue-tsc 与 vite build 都查不出来（二者都不执行 setup）。
 * 见《硬约定》#129。
 */
watch(
  () => props.open,
  (visible) => {
    if (!visible) return
    resetForms()
    void loadProfile()
  },
)

async function loadProfile() {
  loading.value = true
  try {
    const data = await getMyProfile()
    profile.value = data
    draft.username = data.username
    draft.realName = data.realName || ''
    draft.phone = data.phone || ''
    draft.avatarUrl = data.avatarUrl || ''
  } catch {
    // 错误提示已由 request.ts 拦截器统一弹出，这里不再重复 toast；
    // 关掉弹窗避免用户对着一个空表单操作。
    open.value = false
  } finally {
    loading.value = false
  }
}

/** 每次打开都清空校验态与密码输入 —— 密码绝不该在关闭后还留在内存/输入框里 */
function resetForms() {
  basicRef.value?.clearValidate()
  pwdRef.value?.clearValidate()
  pwd.oldPassword = ''
  pwd.newPassword = ''
  pwd.confirmPassword = ''
}

/* ==================== 头像 ==================== */

function pickFile() {
  fileRef.value?.click()
}

async function onPickFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 立刻清空 input.value：同一个文件选第二次时 change 不会触发，
  // 表现为「第一次能传、第二次点了没反应」。
  input.value = ''
  if (!file) return

  // 前端先挡 5 MB：后端也挡，但一次 5 MB 的无效上传要走完整个 multipart
  // 才被告知超限，弱网下要等十几秒。这里是纯体验优化，不是安全边界。
  if (file.size > 5 * 1024 * 1024) {
    message.error('图片不能超过 5 MB')
    return
  }

  uploading.value = true
  try {
    const res = await uploadAvatar(file)
    // 只更新草稿，**不落库** —— 用户点「保存资料」才真正生效。
    // 这样误选一张图后可以直接关掉弹窗，旧头像不受影响。
    draft.avatarUrl = res.url
  } catch {
    // toast 已由拦截器提示
  } finally {
    uploading.value = false
  }
}

function removeAvatar() {
  // 置空串而不是 null：draft 是 reactive 对象，空串让 `:src` 与 `v-if`
  // 的判断都成立（后端收到空串会归一为 NULL）。
  draft.avatarUrl = ''
}

/* ==================== 保存资料 ==================== */

async function onSaveProfile() {
  try {
    await basicRef.value?.validate()
  } catch {
    // 校验失败：a-form 已在字段下方给出红色说明，无需再弹 toast
    return
  }

  // 二次确认：账号是登录凭据，改名的影响面比「改个昵称」大。
  Modal.confirm({
    title: '确认保存资料？',
    content:
      draft.username !== profile.value?.username
        ? `登录账号将改为「${draft.username}」，之后请用新账号登录。修改立即生效。`
        : '修改将立即生效。',
    okText: '确认保存',
    cancelText: '再想想',
    // 必须显式抬高层级：确认框与 a-modal 的默认 zIndex 都是 1000，
    // 同值时后插入者在上——但弹窗内部若有 transform 形成的层叠上下文，
    // 确认框可能被压在弹窗下面，表现为「点了保存什么都没发生」。
    zIndex: 1100,
    onOk: () => doSaveProfile(),
  })
}

async function doSaveProfile() {
  saving.value = true
  try {
    const saved = await updateMyProfile({
      username: draft.username.trim(),
      realName: draft.realName.trim(),
      phone: draft.phone.trim(),
      avatarUrl: draft.avatarUrl,
    })
    profile.value = saved
    // 同步到全局状态（内存 + localStorage 两处成对更新，见 auth store 注释），
    // 否则侧栏头像与姓名要等下次刷新才变。
    authStore.applyProfile({
      username: saved.username,
      realName: saved.realName,
      avatarUrl: saved.avatarUrl,
    })
    message.success('资料已保存')
    open.value = false
  } catch {
    // 后端错误（账号被占用、头像地址不合法等）已由拦截器 toast，
    // 这里保持弹窗打开，让用户就地改。
  } finally {
    saving.value = false
  }
}

/* ==================== 修改密码 ==================== */

async function onChangePassword() {
  try {
    await pwdRef.value?.validate()
  } catch {
    return
  }

  Modal.confirm({
    title: '确认修改密码？',
    content: '修改成功后当前登录会退出，需要你用新密码重新登录。请确认已记住新密码。',
    okText: '确认修改',
    cancelText: '取消',
    zIndex: 1100,
    onOk: () => doChangePassword(),
  })
}

async function doChangePassword() {
  pwdSaving.value = true
  try {
    await changeMyPassword({
      oldPassword: pwd.oldPassword,
      newPassword: pwd.newPassword,
    })
    message.success('密码已修改，请用新密码重新登录')
    open.value = false
    /*
     * 强制登出。
     *
     * 为什么必须登出而不是留在原地：本项目的 token 与密码无关
     * （改密不会让已签发的 token 失效，见 ProfileService 的说明），
     * 若不登出，用户会继续用旧会话，进而产生「改完密码了但好像没生效」
     * 的错觉；而真正需要验证「新密码可用」的时刻也没了。
     */
    await authStore.logout()
    router.push('/login')
  } catch {
    // 「当前密码不正确」「新密码不能与当前密码相同」等由拦截器提示
  } finally {
    pwdSaving.value = false
  }
}
</script>

<style scoped>
.pf-loading {
  display: flex;
  justify-content: center;
  padding: 48px 0;
}

.pf {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* ==================== 身份条 ==================== */

.pf-id {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 4px 0 0;
}

/*
 * 头像按钮。
 *
 * 外层不写 border-radius 与 size —— 尺寸由 RoleAvatar 的 `:size="64"` 内联决定。
 * 这里只做「相对定位的容器」，用来挂遮罩层。
 * 不加 border/background，避免在圆形头像外侧多出一圈方形轮廓。
 */
.pf-ava {
  position: relative;
  flex: none;
  padding: 0;
  border: 0;
  background: none;
  border-radius: 50%;
  cursor: pointer;
  /* 刻意**不写** overflow: hidden —— 它会连带裁掉 RoleAvatar 外圈的
     同色辉光（那圈 shadow 是头像与白卡之间的分隔信号，裁掉后头像会「贴」在卡上）。
     遮罩自己带 border-radius: 50%，不需要父级裁剪。 */
  /* 消除默认行高在圆形按钮下方多出的几像素 */
  line-height: 0;
}

/*
 * 悬停遮罩：不是装饰，是「这块能点」的唯一提示。
 * 头像本身没有任何控件特征（没有按钮样式、没有文字），
 * 不提示的话用户不会想到点它。
 *
 * 用 --ink 半透明而不是纯黑：#0b1220 的 62% 叠在照片上仍然能看清人像，
 * 纯黑会变成一块洞。
 */
.pf-ava-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: color-mix(in srgb, var(--ink) 62%, transparent);
  color: #fff;
  font-size: 12px;
  line-height: 1;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}

.pf-ava:hover .pf-ava-mask,
.pf-ava:focus-visible .pf-ava-mask {
  opacity: 1;
}

/* 键盘可达：a-button 的焦点环样式不作用于原生 button，必须自己补 */
.pf-ava:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.pf-ava:disabled {
  cursor: progress;
}

.pf-id-text {
  flex: 1;
  min-width: 0;
}

.pf-name {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
  line-height: 1.3;
}

.pf-id-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

/*
 * 角色标签：描边式（不是填充式）。
 * 填充式要用 --primary-soft 打底，而 modal 的底是 --card-bg 白 ——
 * 浅蓝贴白只有 1.04:1，标签会「化」进背景。描边 + --primary-dark 文字是
 * 唯一在两种底上都成立的做法（同 ScaleCard 标签的处理）。
 */
.pf-role {
  padding: 1px 8px;
  border: 1px solid var(--ctrl-border);
  border-radius: var(--radius-pill);
  font-size: 12px;
  color: var(--primary-dark);
  white-space: nowrap;
}

.pf-account {
  font-size: 12px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 「移除头像」。
 *
 * 用 --risk-d-ink 而不是 --risk-d：后者在白底上白字只有 4.21:1 不达 AA
 * （见《硬约定》#124）。这里虽然是纯文字不是实心按钮，但同理——
 * --risk-d 落白面只有 3.94，看不清。
 */
.pf-remove {
  flex: none;
  padding: 0;
  border: 0;
  background: none;
  font-size: 12.5px;
  color: var(--risk-d-ink);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.pf-remove:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.pf-file {
  display: none;
}

.pf-hint {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  /* --text-3 只能落白面；modal 内容是白底，可用。但这里是长句，
     用 --text-2 比 --text-3 更易读，不必贴着 4.5 的下限。 */
  color: var(--text-2);
}

/* ==================== 分区 ==================== */

.pf-section {
  margin-top: 18px;
}

.pf-title {
  margin: 0 0 12px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

/*
 * 分区之间的分隔线。
 *
 * 用 1px 的 --ctrl-border 而不是留白：两块表单各自有独立的提交按钮，
 * 不加线时「保存资料」与下面「当前密码」看起来像同一个表单里的字段。
 */
.pf-divider {
  height: 1px;
  margin: 20px 0 0;
  background: var(--ctrl-border);
}

.pf-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}

/* ==================== 表单密度 ==================== */

/*
 * 三字段 + 三密码字段塞进一个弹窗，antd 默认的 24px 字段间距会让整体过高。
 * 收到 16px：仍然明显分组，又不至于要滚动。
 */
.pf :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.pf :deep(.ant-form-item-label) {
  padding-bottom: 4px;
}

.pf :deep(.ant-form-item-label > label) {
  font-size: 13px;
  color: var(--text-2);
}

/*
 * 减动效：只去掉遮罩的淡入过渡，保留「显隐」本身。
 * 淡入不属于会引起前庭不适的位移/缩放类动效，这里保留它、只需确保
 * transition 里没有 transform。
 */
@media (prefers-reduced-motion: reduce) {
  .pf-ava-mask {
    transition: none;
  }
}
</style>
