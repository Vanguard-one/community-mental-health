import { createApp } from 'vue'
import { message } from 'ant-design-vue'
import { createPinia } from 'pinia'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'

// 样式引入顺序 = 层叠优先级：设计 token → 字体 → Ant 重置 → 全局覆盖
import './styles/tokens.css'
import './styles/fonts.css'
import 'ant-design-vue/dist/reset.css'
import './styles/global.css'

import App from './App.vue'
import router from './router'
import { setupReveal } from './directives/reveal'

dayjs.locale('zh-cn')

const app = createApp(App)
// 注意：这里**不要**再写 `app.use(Antd)`。
// 组件由 unplugin-vue-components 在编译期按需注入（见 vite.config.ts），
// 全量注册会把整个 antd（1.4MB+）拉回产物里，按需导入直接失效。
// 少数非组件 API（message / notification / Modal 静态方法）按需 import 即可。
app.use(createPinia())
app.use(router)
// 入场动效引擎（recipe 驱动，见 directives/reveal.ts）。
// 注册在 mount 之前：指令的 mounted 钩子会挂前置态，必须赶在首次绘制前生效。
setupReveal(app)

/**
 * 兜底错误处理。
 *
 * 分工：
 *   - ErrorBoundary（见 App.vue）→ 捕获**渲染/生命周期**异常并就地降级为一块错误卡片；
 *     它 onErrorCaptured 返回 false，所以被它接住的异常不会再走到这里。
 *   - 本处 → 接住边界之外的异常（异步回调、事件处理器、router 钩子等），
 *     这些没有 UI 落点，只能靠 toast + 控制台，否则用户只会看到「点了没反应」。
 *   - 接口错误 → 由 request.ts 拦截器统一提示，不在此重复弹窗。
 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[PsyCare] 未捕获异常:', err, '\n位置:', info)
  message.error('页面出现异常，如无法继续请刷新重试')
}

// 无 catch 的 Promise 拒绝：只记日志。
// 不弹 toast 是因为接口失败已由 request.ts 提示过，重复弹窗会变成两层红色提示。
window.addEventListener('unhandledrejection', (e) => {
  console.error('[PsyCare] 未处理的 Promise 拒绝:', e.reason)
})

app.mount('#app')
