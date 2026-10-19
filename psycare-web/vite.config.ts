import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    /**
     * Ant Design Vue 组件按需自动导入。
     *
     * 背景：原先是 main.ts 里 `app.use(Antd)` 全量注册。全量注册会让打包器
     * 无法判断哪些组件真的被用到 —— 结果是整个 antd 被塞进一个 1.47MB 的
     * vendor chunk（gzip 451KB），全部进首屏，其中大部分组件本项目从未使用。
     *
     * 换成模板里出现 `<a-xxx>` 才注入对应 import 后，未使用的组件被 tree-shake
     * 掉。对应地，main.ts 里的 `app.use(Antd)` 必须移除，否则它仍然会把
     * 整个库拉回来，插件等于白装。
     *
     * importStyle: false —— antd v4 用 CSS-in-JS，组件自带样式，
     * 不需要再按需引入 less/css 文件（本项目也没装 less）。
     */
    Components({
      resolvers: [AntDesignVueResolver({ importStyle: false })],
      dts: 'src/types/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:18989',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        /**
         * 手动分包。
         *
         * 不拆的话产物是两块巨石：入口 1.29MB（vue + antd + axios + 所有共享代码）
         * + Dashboard 576KB（echarts）。巨石的问题不是总体积，而是**缓存命中率**：
         * 业务代码改一行，1.29MB 的 hash 整个变掉，用户全量重下。
         *
         * 拆法按「变更频率」而非「功能模块」：
         *   vendor-echarts / vendor-antd / vendor-vue → 库，几乎不变
         *   vendor-misc                              → 其余零星依赖
         * 剩下的入口 chunk 就只剩业务代码，改业务只失效那一小块。
         *
         * echarts 单独成块还有第二个作用：它只被路由懒加载的 Dashboard 引用，
         * 拆出来后被标记为异步依赖，居民端首屏不会下载。
         */
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts') || id.includes('zrender') || id.includes('vue-echarts')) {
            return 'vendor-echarts'
          }
          if (id.includes('ant-design-vue') || id.includes('@ant-design')) {
            return 'vendor-antd'
          }
          if (id.includes('vue-router') || id.includes('pinia') || id.includes('@vue')) {
            return 'vendor-vue'
          }
          // vue 本体、axios、dayjs 等
          return 'vendor-misc'
        },
      },
    },
    /**
     * 单块超过 800KB 就告警。
     *
     * 为什么是 800：按需导入后，**首屏必须加载**的最大块是 vendor-antd（752KB / gzip 237KB），
     * 这是本项目实际用到的 antd 组件 + @ant-design/icons-vue 的真实成本，不是浪费。
     * 阈值贴近它，既不为已知合理体积天天报警（报警多了就没人看），
     * 又能在将来某个**页面** chunk 或新的库意外膨胀时第一时间暴露出来。
     *
     * 注意 vendor-echarts（566KB）虽然接近阈值但**不进首屏** ——
     * 它只被路由懒加载的 Dashboard 引用，居民端不会下载。
     */
    chunkSizeWarningLimit: 800,
  },
})
