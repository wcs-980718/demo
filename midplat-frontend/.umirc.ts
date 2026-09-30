import path from 'node:path';
import { defineConfig } from '@umijs/max';

const deployBase = process.env.DEPLOY_BASE;
const reactDir = path.resolve(__dirname, 'node_modules/react');
const reactDomDir = path.resolve(__dirname, 'node_modules/react-dom');

export default defineConfig({
  hash: true,
  title: '数智大脑',
  favicons: ['/favicon.svg'],
  mountElementId: 'midplat-root',
  headScripts: [
    {
      content: 'try{if(window.__POWERED_BY_QIANKUN__){document.documentElement.classList.add("midplat-in-qiankun")}var t=localStorage.getItem("midplat-theme");var m=t==="dark"||t==="light"?t:"light";document.documentElement.dataset.theme=m;if(!window.__POWERED_BY_QIANKUN__){document.documentElement.style.colorScheme=m}}catch(e){document.documentElement.dataset.theme="light"}',
    },
    {
      // 关闭插件默认的 publicPath 注入后由这里兜底：门户注册的 entry 若缺少
      // 尾部斜杠或部署前缀，qiankun 会把注入的 publicPath 算成站点根，导致
      // 所有异步 chunk 按错误地址请求而 404。入口脚本始终按部署前缀打包，
      // 据此纠正 window.publicPath。
      content: `try{var __mpBase=${JSON.stringify(deployBase || '/')};var __mpInj=window.__INJECTED_PUBLIC_PATH_BY_QIANKUN__||__mpBase;if(window.__POWERED_BY_QIANKUN__&&__mpInj.indexOf(__mpBase)===-1){__mpInj=__mpInj.replace(/\\/+$/,"")+__mpBase}window.publicPath=__mpInj}catch(e){window.publicPath=${JSON.stringify(deployBase || '/')}}`,
    },
  ],
  base: deployBase || '/',
  publicPath: deployBase || '/',
  qiankun: {
    slave: {
      shouldNotModifyDefaultBase: true,
      shouldNotModifyRuntimePublicPath: true,
    },
  },
  routes: [
    {
      path: '/',
      component: '@/layouts/index',
      layout: false,
      routes: [
        { path: '/', redirect: '/home' },
        { path: '/home', component: '@/pages/home/index' },
        { path: '/entry/category/:menuId', component: '@/pages/entry/category' },
        { path: '/entry/scene/:sceneId', component: '@/pages/entry/scene' },
        { path: '/evaluation', component: '@/pages/evaluation/index' },
        { path: '/capabilities', component: '@/pages/capabilities/index' },
        { path: '/capabilities/assets', component: '@/pages/capability-assets/index' },
        { path: '/access', component: '@/pages/access/index' },
        { path: '/agent-platform', redirect: '/agent-hub/overview' },
        { path: '/agent-hub', redirect: '/agent-hub/overview' },
        { path: '/agent-hub/overview', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/agents', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/assets', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/settings', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/releases', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/workflows', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/use', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/runs', component: '@/pages/agent-hub/index' },
        { path: '/agent-hub/audit', component: '@/pages/agent-hub/index' },
        { path: '/models', component: '@/pages/models/index' },
        { path: '/prompts', component: '@/pages/prompts/index' },
        { path: '/settings/menus', component: '@/pages/settings/menus' },
        { path: '*', component: '@/pages/404' },
      ],
    },
  ],
  npmClient: 'npm',
  mfsu: false,
  alias: {
    react: reactDir,
    'react-dom': reactDomDir,
  },
  antd: {},
  esbuildMinifyIIFE: true,
  proxy: {
    '/api': {
      target: process.env.API_TARGET ?? 'http://127.0.0.1:8090',
      // 保留浏览器 Origin，供融合会话的来源校验使用。
      changeOrigin: false,
    },
    // 主应用（malacca 统一门户）接口代理，仅供本地开发直连演示环境；
    // 生产下前端同源调用 /api/base/*，不经此代理。需要登录态时用
    // PORTAL_TOKEN=<micro-service-token JWT> npm run dev 注入会话 cookie。
    '/portal-api': {
      target: process.env.PORTAL_TARGET ?? 'http://portal.example.com:30200',
      changeOrigin: true,
      pathRewrite: { '^/portal-api': '/api' },
      ...(process.env.PORTAL_TOKEN
        ? { headers: { Cookie: `micro-service-token=${process.env.PORTAL_TOKEN}` } }
        : {}),
    },
  },
});
