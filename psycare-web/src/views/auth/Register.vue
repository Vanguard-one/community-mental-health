<template>
  <!--
    注册页与登录页共用同一套外壳：透明玻璃框 → 卡片 → 左侧蓝底品牌柱 + 右侧白面表单。
    这样两页在「配色 / 圆角 / 阴影 / 玻璃质感 / 白点漂移场」上逐项同源，
    用户从登录跳到注册不会有「换了个网站」的感觉。
    左侧品牌柱上的白点漂移场复用 components/DotField.vue —— 与登录页是同一个实现。
  -->
  <div class="auth-wrap">
    <div class="auth-frame reg-frame">
      <div class="reg-card">
        <aside class="reg-brand">
          <DotField />
          <div class="reg-brand-inner">
            <div class="brand-logo">
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

            <h2 class="reg-brand-title">
              {{ isDoctor ? '加入我们，' : '从这里开始，' }}<br />
              {{ isDoctor ? '一起接住每一次求助' : '认真对待自己的情绪' }}
            </h2>
            <p class="reg-brand-desc">{{ subtitle }}</p>

            <ul class="reg-feats">
              <li><span class="ok">✓</span>{{ isDoctor ? '按科室接诊，全程可追溯' : '3 分钟完成一次专业自评' }}</li>
              <li><span class="ok">✓</span>{{ isDoctor ? '资质审核通过后即可接诊' : '结果仅你与接诊医生可见' }}</li>
              <li><span class="ok">✓</span>AI 助手 7×24 小时在线</li>
            </ul>
          </div>
        </aside>

        <div class="reg-form">
          <!-- 提交成功后的结果态：替代表单，避免用户回到表单反复提交 -->
          <div v-if="done" class="done-block">
        <span class="done-chip" :class="doneOk ? 'ok' : 'pending'">
          {{ doneOk ? '注册成功' : '待审核' }}
        </span>
        <h2 class="done-title">{{ doneTitle }}</h2>
        <p class="done-sub">{{ doneSubTitle }}</p>
        <a-button type="primary" size="large" block @click="goLogin">返回登录</a-button>
      </div>

      <template v-else>
        <div class="auth-header">
          <h1 class="auth-title">{{ isDoctor ? '申请医生账号' : '创建居民账号' }}</h1>
          <p class="auth-subtitle">{{ subtitle }}</p>
        </div>

        <a-radio-group
          v-model:value="userType"
          button-style="solid"
          size="large"
          class="type-switch"
        >
          <a-radio-button value="RESIDENT">居民注册</a-radio-button>
          <a-radio-button value="DOCTOR">医生注册</a-radio-button>
        </a-radio-group>

        <a-alert
          v-if="isDoctor"
          class="audit-alert"
          type="info"
          show-icon
          message="医生账号需资质审核"
          description="提交后由管理员核对执业资质，审核通过前无法登录。"
        />

        <a-form
          ref="formRef"
          :model="formState"
          :rules="rules"
          layout="vertical"
          @finish="onFinish"
        >
          <a-form-item label="用户名" name="username">
            <a-input
              v-model:value="formState.username"
              size="large"
              placeholder="4-32 位字母、数字或下划线"
              :prefix="h(UserOutlined)"
            />
          </a-form-item>

          <a-form-item label="真实姓名" name="realName">
            <a-input
              v-model:value="formState.realName"
              size="large"
              :placeholder="isDoctor ? '请填写与执业证书一致的姓名' : '您的姓名'"
            />
          </a-form-item>

          <a-form-item label="手机号" name="phone">
            <a-input
              v-model:value="formState.phone"
              size="large"
              placeholder="用于联系（列表脱敏展示）"
            />
          </a-form-item>

          <!-- 密码两名表单共用（在差异字段之外） -->
          <a-form-item label="密码" name="password">
            <a-input-password
              v-model:value="formState.password"
              size="large"
              placeholder="6-32 位"
              :prefix="h(LockOutlined)"
            />
          </a-form-item>

          <a-form-item label="确认密码" name="confirmPassword">
            <a-input-password
              v-model:value="formState.confirmPassword"
              size="large"
              placeholder="请再次输入密码"
              :prefix="h(LockOutlined)"
            />
          </a-form-item>

          <!-- ===== 居民差异字段 ===== -->
          <template v-if="!isDoctor">
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="性别" name="gender">
                  <a-select
                    v-model:value="formState.gender"
                    size="large"
                    placeholder="选填"
                    :options="GENDER_OPTIONS"
                    allow-clear
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="出生年份" name="birthYear">
                  <a-input
                    v-model:value="formState.birthYear"
                    size="large"
                    placeholder="选填，如 1990"
                    :maxlength="4"
                  />
                </a-form-item>
              </a-col>
            </a-row>

            <a-form-item label="职业" name="occupation">
              <a-input
                v-model:value="formState.occupation"
                size="large"
                placeholder="选填，如：教师、退休、企业职员"
                :maxlength="64"
              />
            </a-form-item>

            <a-form-item label="紧急联系人" name="emergencyContact">
              <a-input
                v-model:value="formState.emergencyContact"
                size="large"
                placeholder="选填，格式：姓名 关系 电话"
                :maxlength="64"
              />
              <p class="field-hint">
                危机干预时用于紧急联络，仅授权医生可见。
              </p>
            </a-form-item>
          </template>

          <!-- ===== 医生差异字段 ===== -->
          <template v-else>
            <a-form-item label="执业证书编号" name="licenseNo">
              <a-input
                v-model:value="formState.licenseNo"
                size="large"
                placeholder="资质审核凭据，必填"
                :maxlength="64"
              />
            </a-form-item>

            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="科室" name="department">
                  <a-input
                    v-model:value="formState.department"
                    size="large"
                    placeholder="选填"
                    :maxlength="32"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="职称" name="title">
                  <a-select
                    v-model:value="formState.title"
                    size="large"
                    placeholder="选填"
                    :options="TITLE_OPTIONS"
                    allow-clear
                  />
                </a-form-item>
              </a-col>
            </a-row>

            <a-form-item label="专长标签" name="expertiseTags">
              <a-select
                v-model:value="formState.expertiseTags"
                mode="tags"
                size="large"
                placeholder="选填，回车添加，如：焦虑、抑郁、青少年"
                :options="EXPERTISE_OPTIONS"
                :token-separators="[',']"
              />
              <p class="field-hint">
                用于推荐引擎按患者问题匹配医生，填写越准推荐越准。
              </p>
            </a-form-item>
          </template>

          <a-form-item>
            <a-button
              type="primary"
              html-type="submit"
              size="large"
              block
              :loading="loading"
            >
              {{ isDoctor ? '提交审核' : '注 册' }}
            </a-button>
          </a-form-item>
        </a-form>

        <div class="auth-footer">
          已有账号？
          <a @click="goLogin">返回登录</a>
        </div>
        </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, h, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import DotField from '@/components/DotField.vue'
import { useAuthStore } from '@/stores/auth'
import type { RegisterResponse, UserType } from '@/types'
import type { FormInstance, Rule } from 'ant-design-vue/es/form'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const currentYear = new Date().getFullYear()

const GENDER_OPTIONS = [
  { value: 'M', label: '男' },
  { value: 'F', label: '女' },
  { value: 'U', label: '不愿透露' },
]

const TITLE_OPTIONS = [
  { value: '主任医师', label: '主任医师' },
  { value: '副主任医师', label: '副主任医师' },
  { value: '主治医师', label: '主治医师' },
  { value: '住院医师', label: '住院医师' },
  { value: '心理治疗师', label: '心理治疗师' },
  { value: '心理咨询师', label: '心理咨询师' },
]

const EXPERTISE_OPTIONS = [
  '焦虑',
  '抑郁',
  '睡眠障碍',
  '青少年心理',
  '婚姻家庭',
  '危机干预',
].map((v) => ({ value: v, label: v }))

/** 从 ?type= 初始化；非法值一律按居民处理，避免深链伪造出未知表单 */
function parseType(raw: unknown): UserType {
  return raw === 'DOCTOR' ? 'DOCTOR' : 'RESIDENT'
}

const userType = ref<UserType>(parseType(route.query.type))
const isDoctor = computed(() => userType.value === 'DOCTOR')

const loading = ref(false)
const done = ref<RegisterResponse | null>(null)
const formRef = ref<FormInstance>()

const formState = reactive({
  username: '',
  realName: '',
  phone: '',
  password: '',
  confirmPassword: '',
  // 居民
  gender: '' as '' | 'M' | 'F' | 'U',
  // 存字符串而非数字：用 a-input 而非 a-input-number，省掉 InputNumber 组件（见 vite.config 包体约定）
  birthYear: '',
  occupation: '',
  emergencyContact: '',
  // 医生
  licenseNo: '',
  department: '',
  title: '',
  expertiseTags: [] as string[],
})

const subtitle = computed(() =>
  // 注意 .value：script 内不会像模板那样自动解包 ref，漏了会让条件恒真
  isDoctor.value
    ? '提交执业资质，管理员审核通过后即可接诊'
    : '注册后即可开始心理测评与 AI 咨询',
)

const rules = computed<Record<string, Rule[]>>(() => {
  const base: Record<string, Rule[]> = {
    // 与后端 @Pattern 完全一致（4-32 位），旧版前端写成 3 位会被后端打回
    username: [
      { required: true, message: '请输入用户名', trigger: 'blur' },
      {
        pattern: /^[a-zA-Z0-9_]{4,32}$/,
        message: '4-32 位字母、数字或下划线',
        trigger: 'blur',
      },
    ],
    realName: [
      { required: true, message: '请输入真实姓名', trigger: 'blur' },
      { max: 32, message: '姓名过长', trigger: 'blur' },
    ],
    phone: [
      { required: true, message: '请输入手机号', trigger: 'blur' },
      { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
    ],
    password: [
      { required: true, message: '请输入密码', trigger: 'blur' },
      { min: 6, message: '密码至少 6 位', trigger: 'blur' },
      { max: 32, message: '密码最多 32 位', trigger: 'blur' },
    ],
    confirmPassword: [
      { required: true, message: '请再次输入密码', trigger: 'blur' },
      {
        validator: (_rule, value: string) => {
          if (value !== formState.password) {
            return Promise.reject('两次输入的密码不一致')
          }
          return Promise.resolve()
        },
        trigger: 'blur',
      },
    ],
    // 仅居民表单渲染该字段，留空合法（后端可选）
    birthYear: [
      {
        validator: (_rule, value: string) => {
          if (!value) return Promise.resolve()
          const n = Number(value)
          if (!/^\d{4}$/.test(value) || n < 1900 || n > currentYear) {
            return Promise.reject(`请输入 1900 - ${currentYear} 之间的年份`)
          }
          return Promise.resolve()
        },
        trigger: 'blur',
      },
    ],
  }

  // 仅医生渲染该字段；antd 只校验已注册（已渲染）的字段，故条件加规则是安全的
  if (isDoctor.value) {
    base.licenseNo = [
      { required: true, message: '请输入执业证书编号', trigger: 'blur' },
      { max: 64, message: '执业证书编号过长', trigger: 'blur' },
    ]
  }

  return base
})

/** 切换身份时同步 URL 并清掉上一套表单的校验残留 */
watch(userType, (t) => {
  done.value = null
  formRef.value?.clearValidate()
  if (route.query.type !== t) {
    router.replace({ path: '/register', query: { type: t } })
  }
})

async function onFinish() {
  loading.value = true
  try {
    if (isDoctor.value) {
      done.value = await authStore.registerDoctor({
        username: formState.username,
        password: formState.password,
        confirmPassword: formState.confirmPassword,
        realName: formState.realName,
        phone: formState.phone,
        licenseNo: formState.licenseNo,
        department: formState.department || undefined,
        title: formState.title || undefined,
        // 后端收逗号分隔字符串，空数组传 undefined 而非空串，避免存进无意义的 ''
        expertiseTags: formState.expertiseTags.join(',') || undefined,
      })
    } else {
      done.value = await authStore.registerResident({
        username: formState.username,
        password: formState.password,
        confirmPassword: formState.confirmPassword,
        realName: formState.realName,
        phone: formState.phone,
        gender: formState.gender,
        birthYear: formState.birthYear ? Number(formState.birthYear) : null,
        occupation: formState.occupation || undefined,
        emergencyContact: formState.emergencyContact || undefined,
      })
    }
  } catch {
    // 错误提示已在响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

/** 居民注册即可登录（成功）；医生注册只是「提交了申请」（待审核），语义不同 */
const doneOk = computed(() => done.value?.userType !== 'DOCTOR')

const doneTitle = computed(() =>
  done.value?.userType === 'DOCTOR' ? '注册申请已提交' : '注册成功',
)

const doneSubTitle = computed(() => {
  if (done.value?.userType === 'DOCTOR') {
    return (
      done.value.tip ||
      '资质资料已提交，需管理员审核通过后方可登录，请耐心等待。'
    )
  }
  return '账号已创建，现在就可以登录开始心理测评。'
})

function goLogin() {
  router.push('/login')
}
</script>

<style scoped>
/* 认证页属「设计稿外」页面，按 tokens.css 自行延展，禁止硬编码色值。
   外壳与登录页**逐项同源**：玻璃框 → 卡片 → 蓝底品牌柱 + 白面表单。
   页面底交由 body 的渐变承担（见 global.css），这里只叠一层「提亮方向」的柔光 ——
   绝不压深：alpha 0.06 就会把 --text-2 从 5.40 压到 4.95。 */
.auth-wrap {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.auth-wrap::before {
  content: '';
  position: fixed;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(880px 520px at 10% -12%, rgba(255, 255, 255, 0.55), transparent 62%),
    radial-gradient(720px 460px at 104% 6%, rgba(255, 255, 255, 0.4), transparent 64%);
}

.auth-frame {
  position: relative;
  width: 1060px;
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

.reg-card {
  display: flex;
  height: 720px;
  border-radius: 28px;
  overflow: hidden;
  background: var(--card-bg);
  box-shadow: 0 10px 30px rgba(11, 18, 32, 0.08);
}

/* ---- 左：蓝底品牌柱 ---- */
.reg-brand {
  position: relative;
  width: 34%;
  flex: none;
  overflow: hidden;
  display: flex;
  align-items: center;
  padding: 42px 34px;
  /* 与登录页卡片底色同一支渐变 */
  background: linear-gradient(152deg, var(--primary) 0%, var(--primary-dark) 100%);
  color: #fff;
  /* 把白点层的 screen 混合锁在柱内，不让它去和右侧白面混合 */
  isolation: isolate;
}

.reg-brand-inner {
  position: relative;
  z-index: 1;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 34px;
}

.mark {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.18);
}

.name {
  font-family: var(--font-serif);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.reg-brand-title {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.5;
  color: #fff;
  margin-bottom: 14px;
}

.reg-brand-desc {
  font-size: 13.5px;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.82);
  margin-bottom: 28px;
}

.reg-feats {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.94);
}

.reg-feats li {
  display: flex;
  align-items: center;
  gap: 9px;
}

.reg-feats .ok {
  width: 19px;
  height: 19px;
  flex: none;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.22);
  font-size: 11px;
}

/* ---- 右：白面表单 ---- */
.reg-form {
  flex: 1;
  min-width: 0;
  padding: 32px 36px 28px;
  overflow-y: auto;
}

.auth-header {
  margin-bottom: 18px;
}

.auth-title {
  font-size: 23px;
  font-weight: 700;
  color: var(--text-1);
}

.auth-subtitle {
  margin-top: 6px;
  color: var(--text-3);
  font-size: 13px;
  line-height: 1.6;
}

.type-switch {
  display: flex;
  width: 100%;
  margin-bottom: 16px;
}

/* 两个身份按钮等宽铺满，与下方表单同宽 */
.type-switch :deep(.ant-radio-button-wrapper) {
  flex: 1;
  text-align: center;
}

.audit-alert {
  margin-bottom: 16px;
}

/* 表单控件与登录页同高 46px；这里字段多，用 42px 折中，
   既比全站 38px 的基准大、又不至于把表单拉得太长 */
.reg-form :deep(.ant-input-affix-wrapper),
.reg-form :deep(.ant-input),
.reg-form :deep(.ant-select-selector) {
  min-height: 42px;
}

.reg-form :deep(.ant-select-single) {
  height: 42px;
}

.reg-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.reg-form :deep(.ant-form-item-label > label) {
  font-size: 13.5px;
  color: var(--text-2);
}

.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-3);
}

.auth-footer {
  margin-top: 8px;
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
}

.auth-footer a {
  color: var(--primary);
  cursor: pointer;
}

/* ---- 提交结果态 ---- */
.done-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  height: 100%;
  padding: 12px 8px;
}

.done-chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  font-size: 12px;
  font-weight: 600;
}

/* ⚠️ 文字色用深一档的 ink 系列：实色 --risk-a 落在 --risk-a-soft 上只有 2.49:1（不达 AA） */
.done-chip.ok {
  background: var(--risk-a-soft);
  color: var(--risk-a-ink);
}

.done-chip.pending {
  background: var(--risk-b-soft);
  color: var(--risk-b-ink);
}

.done-title {
  margin: 14px 0 8px;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-1);
}

.done-sub {
  margin: 0 0 22px;
  max-width: 380px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-2);
}

/* ---- 窄屏：品牌柱收起，只留表单（否则 34% 的柱子会把表单挤到 320px 以下） ---- */
@media (max-width: 960px) {
  .auth-frame {
    width: 560px;
  }
  .reg-card {
    height: auto;
    max-height: 88vh;
  }
  .reg-brand {
    display: none;
  }
  .reg-form {
    padding: 28px 26px 24px;
  }
}
</style>
