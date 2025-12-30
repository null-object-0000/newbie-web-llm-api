import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';

export default defineConfig({
  plugins: [vue()],
  base: '/admin/',
  build: {
    outDir: '../src/main/resources/static/admin',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html')
      }
    }
  },
  server: {
    port: 3174,
    proxy: {
      // 只代理 API 请求到后端
      '/admin/api': {
        target: 'http://localhost:24753',
        changeOrigin: true
      }
    }
  }
});

