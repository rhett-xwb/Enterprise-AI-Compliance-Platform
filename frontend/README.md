# 企业合规平台前端

基于 Vite 和 Vue 3 的 JavaScript 工程，已接入 Vue Router 4 和 Element Plus（中文语言包）。

## 环境与启动

使用 Node.js 20.19+ 或 22.12+ 的受支持版本。本次安装和构建使用 Node.js 24.12.0。

```powershell
cd frontend
npm ci
npm run dev
```

以终端输出的本地地址为准，默认地址为 `http://localhost:5173`。

```powershell
npm run build
npm run preview
```

`build` 输出到 `dist/`；`preview` 用于本地检查构建产物。

## 工程结构

- `src/main.js`：注册路由、Element Plus 和中文语言包。
- `src/App.vue`：公共布局和路由出口。
- `src/router/index.js`：首页、项目说明和 404 路由，使用 Hash 模式。
- `src/views/`：页面组件。
- `src/styles/global.css`：公共样式。
- `vite.config.js`：Vite 和 Vue 插件配置。

通过首页按钮可检查 Element Plus 消息提示，通过菜单可切换路由。当前未接入登录、权限或后端业务接口。

## 官方参考

- [Vite 入门](https://vite.dev/guide/)
- [Vue Router 安装](https://router.vuejs.org/installation.html)
- [Element Plus 快速开始](https://element-plus.org/en-US/guide/quickstart.html)
