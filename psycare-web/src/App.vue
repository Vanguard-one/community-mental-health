<template>
  <a-config-provider :theme="theme" :locale="zhCN">
    <!--
      页面级错误边界：捕获路由组件子树的渲染/生命周期异常，避免整站白屏。

      ⚠️ key 必须用 `route.path`，**绝不能用 `route.fullPath`**。
         错误边界内部把异常存在 ref 里，遇到新页面必须重建实例，否则错误态会一直粘住 ——
         但「换了一个新页面」的判据是**路径变了**，不是「URL 变了」。
         `fullPath` 含 query，于是 `?dept=` / `?type=` 这类**同页筛选参数**一变，
         整棵 `router-view` 子树就被销毁重建，后果是页面内数据全部重来：
         骨架屏闪一次、数据重新请求、入场动画重播、**未保存的表单被清空**。
         症状是「点一下科室筛选就刷新一次」，看着像页面自己发了请求，
         实际是组件被重新挂载了 —— 排查时容易一路怀疑到页面里，而根因在这行 key。
         用 `path` 作 key：跨页导航照旧重置错误态；同页换参数则原地响应，
         query 由各页自己的 `computed` / `watch` 承接（如 `DoctorConsult` 的 `currentDept`）。
    -->
    <ErrorBoundary :key="route.path">
      <router-view />
    </ErrorBoundary>
  </a-config-provider>
</template>

<script setup lang="ts">
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { useRoute } from 'vue-router'
import ErrorBoundary from '@/components/ErrorBoundary.vue'

const route = useRoute()

/**
 * 全局主题：科技白
 * 依据《开发文档.md》3.6 —— 所有色值必须与 src/styles/tokens.css 保持一致。
 * Ant Design Vue 无法直接读 CSS 变量做色值计算，故此处按 token 表逐项映射；
 * 改色时两处同步（tokens.css 是唯一事实来源）。
 *
 * 对应关系（改 tokens.css 时请逐项核对本文件）：
 *   colorPrimary           ← --primary
 *   colorText              ← --text-1
 *   colorTextSecondary     ← --text-2
 *   colorTextTertiary      ← --text-3
 *   colorBorder            ← --ctrl-border（控件描边，输入框 / 选择器 / default 按钮），
 *                            **不等于** --card-border：这个更浅会隐形，
 *                            故取值比 --card-border 再深一档（#c3d0e0，白底约 1.45:1）
 *   colorBorderSecondary   ← 分隔线（表格行线、Card 边框）
 *   colorBgContainer       ← --card-bg
 *
 * ⚠️ 圆角三级必须与 tokens.css 一一对应（改版后为 控件 10 / 内卡 16 / 面板 28）：
 *    borderRadius    ← --radius-ctrl（控件）
 *    borderRadiusLG  ← --radius-lg（页面级面板）
 *    borderRadiusSM  ← 8px（antd 自己的小档，如 Tag / Badge，token 表里没有对应项）
 *    antd 组件读的是这里的值而不是 CSS 变量，两处不同步会出现「按钮 10px、输入框 12px」
 *    这种同排控件圆角不一致的情况。
 */
const theme = {
  token: {
    colorPrimary: '#2563EB',
    colorLink: '#2563EB',
    colorText: '#0B1220',
    colorTextSecondary: '#47536A',
    colorTextTertiary: '#64748B',
    colorBorder: '#C3D0E0',
    colorBorderSecondary: '#DFE7F0',
    colorBgContainer: '#FFFFFF',
    // 关键：透明，让 body 的页面底透出来，否则会被 Ant 的灰底盖掉
    colorBgLayout: 'transparent',
    colorBgElevated: '#FFFFFF',
    borderRadius: 10,
    borderRadiusLG: 28,
    borderRadiusSM: 8,
    fontFamily: 'var(--font-sans)',
    fontSize: 15,
    controlHeight: 38,
  },
  components: {
    /*
     * Menu：现在只剩**下拉菜单**在用（侧栏用户卡的「退出登录」）。
     * 三端的常驻导航已换成自绘的 64px 窄图标栏（components/SideRail.vue），
     * 不再走 a-menu —— 所以这里的选中态取值只影响下拉，不再影响导航。
     *
     * 注意 token 名是 color* 前缀：itemSelectedBg / itemSelectedColor / itemHoverBg
     * 这些名字并不存在（antd 静默忽略，不会报错也不会生效，等于白写）。
     */
    Menu: {
      colorItemBgSelected: '#E7EEFE',
      colorItemTextSelected: '#2563EB',
      colorItemBgHover: '#EEF3FA',
      colorItemTextSelectedHorizontal: '#2563EB',
    },
    Card: {
      // Card 的 ComponentToken 在 ant-design-vue v4 里是空的，
      // paddingLG 命中的是 AliasToken，属于合法用法
      paddingLG: 20,
    },
    Table: {
      // Table 的 ComponentToken 同样是空的，没有 headerBg / borderColor，
      // 只能落到 AliasToken 上：表头底色是 colorFillAlter，描边是 colorBorderSecondary
      colorFillAlter: '#EEF3FA',
      colorBorderSecondary: '#DFE7F0',
    },
  },
}
</script>
