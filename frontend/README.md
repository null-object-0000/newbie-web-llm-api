# 前端项目说明

本项目使用 Vite + Vue 3 + Vue Router 构建前端资源，使用 History 路由模式。

## 开发

```bash
# 安装依赖
npm install

# 启动开发服务器（热重载）
npm run dev
```

开发服务器会在 `http://localhost:3000` 启动，并自动代理 `/admin` API 请求到后端服务器（`http://localhost:8080`）。

## 构建

```bash
# 构建生产版本
npm run build
```

构建后的文件会输出到 `../src/main/resources/static/admin/` 目录，Spring Boot 会自动提供这些静态资源。

## Maven 集成

在 Maven 构建时，`frontend-maven-plugin` 会自动：
1. 安装 Node.js 和 npm
2. 安装前端依赖
3. 构建前端资源

只需运行：
```bash
mvn clean package
```

## 路由说明

项目使用 Vue Router 的 History 模式，路由配置如下：

- `/admin/` 或 `/admin/dashboard` - 仪表盘
- `/admin/accounts` - 账号管理
- `/admin/api-keys` - API 密钥

切换 tab 时会自动更新浏览器地址栏，支持浏览器前进/后退按钮。

## 项目结构

```
frontend/
├── index.html          # 入口 HTML
├── src/
│   ├── main.js        # 主入口文件
│   ├── App.vue        # 根组件
│   ├── router/        # 路由配置
│   │   └── index.js   # 路由定义
│   ├── views/         # 页面视图（路由对应的页面组件）
│   │   ├── Dashboard.vue      # 仪表盘页面
│   │   ├── AccountList.vue    # 账号管理页面
│   │   └── ApiKeyList.vue      # API 密钥页面
│   ├── components/     # 可复用组件
│   │   ├── Tabs.vue            # 标签页组件
│   │   ├── Modal.vue           # 模态框组件
│   │   ├── Toast.vue           # Toast 消息组件
│   │   ├── ConfirmModal.vue    # 确认对话框组件
│   │   ├── ConfirmProvider.vue # 确认对话框提供者
│   │   ├── AccountForm.vue     # 账号表单组件
│   │   ├── ApiKeyForm.vue      # API 密钥表单组件
│   │   └── ApiKeyAccountForm.vue # API 密钥关联账号表单
│   ├── services/      # API 服务
│   ├── utils/         # 工具函数
│   │   └── message.js # 消息和确认服务
│   └── styles/        # 样式文件
├── package.json
├── vite.config.js     # Vite 配置
├── tailwind.config.js # Tailwind CSS 配置
└── postcss.config.js  # PostCSS 配置
```

### 目录说明

- **views/** - 页面级组件，对应路由的页面视图
- **components/** - 可复用的 UI 组件
- **services/** - API 服务层
- **utils/** - 工具函数和全局服务
- **styles/** - 全局样式文件

