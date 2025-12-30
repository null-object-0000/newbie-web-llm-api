import { createApp } from 'vue';
import axios from 'axios';
import { createIcons, icons } from 'lucide';
import App from './App.vue';
import router from './router';
import './styles/main.css';

// 全局配置 axios
window.axios = axios;

// 全局配置 lucide
window.lucide = {
  createIcons: (options) => createIcons({ icons, ...options })
};

// 全局消息服务（会在 App.vue 中设置实例）
window.$message = null;
window.$confirm = null;

// 创建 Vue 应用
const app = createApp(App);
app.use(router);
app.mount('#app');

// 初始化 Lucide 图标
if (window.lucide && window.lucide.createIcons) {
  window.lucide.createIcons();
}

