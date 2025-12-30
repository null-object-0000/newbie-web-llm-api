<template>
  <nav class="tabs-nav">
    <div class="max-w-7xl mx-auto px-8 relative" style="z-index: 10;">
      <div class="flex items-center justify-between h-16">
        <div class="flex items-center gap-1 bg-gray-100 dark:bg-base-200 rounded-full p-1 mx-auto">
          <router-link
            v-for="tab in tabs"
            :key="tab.id"
            :to="tab.path"
            :class="[
              'px-6 py-2 rounded-full text-sm font-medium transition-all',
              isActive(tab.path)
                ? 'bg-gray-900 text-white shadow-sm dark:bg-white dark:text-gray-900'
                : 'text-gray-700 hover:text-gray-900 hover:bg-gray-200 dark:text-gray-400 dark:hover:text-base-content dark:hover:bg-base-100'
            ]"
          >
            {{ tab.label }}
          </router-link>
        </div>
      </div>
    </div>
  </nav>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();

const tabs = [
  { id: 'dashboard', path: '/dashboard', label: '仪表盘' },
  { id: 'accounts', path: '/accounts', label: '账号管理' },
  { id: 'api-keys', path: '/api-keys', label: 'API 密钥' }
];

const isActive = (path) => {
  return route.path === path || route.path.startsWith(path + '/');
};
</script>

<style scoped>
.router-link-active {
  text-decoration: none;
}

a {
  text-decoration: none;
  display: inline-block;
}
</style>

