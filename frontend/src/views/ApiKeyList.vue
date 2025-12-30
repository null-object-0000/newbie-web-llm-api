<template>
  <div class="h-full flex flex-col p-5 gap-4 max-w-7xl mx-auto w-full">
    <!-- 顶部工具栏 -->
    <div class="flex-none flex items-center gap-4">
      <div class="flex-1 max-w-md relative">
        <i data-lucide="search" class="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400"></i>
        <input
          type="text"
          placeholder="搜索 API 密钥..."
          class="w-full pl-9 pr-4 py-2 bg-white dark:bg-base-100 text-sm text-gray-900 dark:text-base-content border border-gray-200 dark:border-base-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent placeholder:text-gray-400 dark:placeholder:text-gray-500"
          v-model="searchQuery"
        />
      </div>
      <div class="flex-1"></div>
      <div class="flex items-center gap-2">
        <button 
          class="btn btn-primary" 
          @click="openApiKeyModal(null)"
          :disabled="!playwrightInitialized"
        >
          <i data-lucide="plus" class="w-3.5 h-3.5"></i>
          创建 API 密钥
        </button>
      </div>
    </div>

    <!-- API 密钥列表内容区域 -->
    <div class="flex-1 min-h-0 overflow-y-auto">
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="error" class="error">错误: {{ error }}</div>
      <div v-else-if="!hasApiKeys" class="empty-state">暂无 API 密钥</div>
      <div v-else class="bg-white dark:bg-base-100 rounded-2xl shadow-sm border border-gray-100 dark:border-base-200 overflow-hidden">
        <div class="table-container">
          <table class="account-table">
            <thead>
              <tr class="table-header-row">
                <th class="table-header">密钥名称</th>
                <th class="table-header">API 密钥</th>
                <th class="table-header">关联的提供器</th>
                <th class="table-header">状态</th>
                <th class="table-header">创建时间</th>
                <th class="table-header">最后使用</th>
                <th class="table-header">操作</th>
              </tr>
            </thead>
            <tbody class="table-body">
              <tr v-for="apiKey in filteredApiKeys" :key="apiKey.apiKey" class="table-row">
                <td class="table-cell">
                  <span class="font-medium text-sm">{{ apiKey.name || '-' }}</span>
                </td>
                <td class="table-cell" style="min-width: 320px; width: 380px;">
                  <div class="flex items-start gap-2">
                    <code 
                      class="text-xs bg-gray-100 dark:bg-base-200 px-2 py-1 rounded font-mono inline-block" 
                      style="word-break: break-all; overflow-wrap: anywhere; white-space: normal; max-width: none; overflow: visible; text-overflow: clip; width: 260px; min-width: 260px;"
                    >
                      {{ isApiKeyVisible(apiKey.apiKey) ? apiKey.apiKey : maskApiKey(apiKey.apiKey) }}
                    </code>
                    <button 
                      class="p-1 hover:bg-gray-200 dark:hover:bg-gray-700 rounded transition-colors flex-shrink-0"
                      @click="toggleApiKeyVisibility(apiKey.apiKey)"
                      :title="isApiKeyVisible(apiKey.apiKey) ? '隐藏' : '查看'"
                    >
                      <i :data-lucide="isApiKeyVisible(apiKey.apiKey) ? 'eye-off' : 'eye'" class="w-4 h-4 text-gray-600 dark:text-gray-400"></i>
                    </button>
                  </div>
                </td>
                <td class="table-cell">
                  <div class="flex flex-wrap gap-1">
                    <span 
                      v-for="provider in getProviders(apiKey)" 
                      :key="provider"
                      class="badge badge-info text-xs"
                    >
                      {{ provider }}
                    </span>
                    <span v-if="getProviders(apiKey).length === 0" class="text-xs text-gray-400 dark:text-gray-500">无</span>
                  </div>
                </td>
                <td class="table-cell">
                  <span :class="getStatusClass(apiKey)" class="text-sm font-medium">
                    {{ getStatusText(apiKey) }}
                  </span>
                </td>
                <td class="table-cell">{{ formatTime(apiKey.createdAt) }}</td>
                <td class="table-cell">{{ formatTime(apiKey.lastUsedAt) }}</td>
                <td class="table-cell">
                  <div class="action-buttons-group">
                    <button 
                      class="action-btn action-btn-primary" 
                      @click="openAccountForm(apiKey)"
                      title="关联账号"
                      :disabled="!playwrightInitialized"
                    >
                      <i data-lucide="link" class="w-3.5 h-3.5"></i>
                    </button>
                    <button 
                      class="action-btn action-btn-primary" 
                      @click="copyApiKey(apiKey.apiKey)"
                      title="复制 API 密钥"
                      :disabled="!playwrightInitialized"
                    >
                      <i data-lucide="copy" class="w-3.5 h-3.5"></i>
                    </button>
                    <button 
                      class="action-btn action-btn-switch" 
                      @click="toggleApiKeyEnabled(apiKey)"
                      :title="apiKey.enabled ? '禁用' : '启用'"
                      :disabled="!playwrightInitialized"
                    >
                      <i :data-lucide="apiKey.enabled ? 'toggle-right' : 'toggle-left'" class="w-3.5 h-3.5"></i>
                    </button>
                    <button 
                      class="action-btn action-btn-delete" 
                      @click="deleteApiKey(apiKey.apiKey)"
                      title="删除"
                      :disabled="!playwrightInitialized"
                    >
                      <i data-lucide="trash-2" class="w-3.5 h-3.5"></i>
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
    
    <!-- 关联账号模态框 -->
    <div v-if="showAccountForm" class="modal" @click.self="closeAccountForm">
      <div class="modal-content" style="max-width: 900px; width: 95%;">
        <div class="modal-header">
          <h2>关联账号 - {{ selectedApiKey?.name || selectedApiKey?.apiKey }}</h2>
          <button class="close" @click="closeAccountForm">
            <i data-lucide="x" class="w-5 h-5"></i>
          </button>
        </div>
        <div class="modal-body">
          <ApiKeyAccountForm 
            v-if="selectedApiKey"
            :apiKey="selectedApiKey"
            @saved="onAccountsSaved"
            @cancel="closeAccountForm"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed, inject, onMounted, onUpdated, ref} from 'vue';
import {apiService} from '../services/api';
import ApiKeyAccountForm from '../components/ApiKeyAccountForm.vue';
import {confirm, message} from '../utils/message';

const openApiKeyModal = inject('openApiKeyModal', () => {});
const playwrightInitialized = inject('playwrightInitialized', ref(true));

const apiKeys = ref([]);
const loading = ref(false);
const error = ref(null);
const searchQuery = ref('');
const showAccountForm = ref(false);
const selectedApiKey = ref(null);
// 跟踪哪些 API 密钥是显示完整内容的
const visibleApiKeys = ref(new Set());

const hasApiKeys = computed(() => {
  return filteredApiKeys.value.length > 0;
});

const filteredApiKeys = computed(() => {
  if (!searchQuery.value) {
    return apiKeys.value;
  }
  const query = searchQuery.value.toLowerCase();
  return apiKeys.value.filter(apiKey => 
    (apiKey.name && apiKey.name.toLowerCase().includes(query)) ||
    (apiKey.apiKey && apiKey.apiKey.toLowerCase().includes(query)) ||
    getProviders(apiKey).some(provider => provider.toLowerCase().includes(query))
  );
});

const loadApiKeys = async () => {
  loading.value = true;
  error.value = null;
  try {
    const data = await apiService.getApiKeys();
    apiKeys.value = data.apiKeys || [];
  } catch (err) {
    console.error('加载 API 密钥列表失败:', err);
    error.value = err.response?.data?.error || err.message;
  } finally {
    loading.value = false;
  }
};

const toggleApiKeyVisibility = (apiKey) => {
  if (visibleApiKeys.value.has(apiKey)) {
    visibleApiKeys.value.delete(apiKey);
  } else {
    visibleApiKeys.value.add(apiKey);
  }
};

const isApiKeyVisible = (apiKey) => {
  return visibleApiKeys.value.has(apiKey);
};

const copyApiKey = async (apiKey) => {
  try {
    await navigator.clipboard.writeText(apiKey);
    message.success('API 密钥已复制到剪贴板');
  } catch (err) {
    // 降级方案：使用传统方法
    const textArea = document.createElement('textarea');
    textArea.value = apiKey;
    textArea.style.position = 'fixed';
    textArea.style.opacity = '0';
    document.body.appendChild(textArea);
    textArea.select();
    try {
      document.execCommand('copy');
      message.success('API 密钥已复制到剪贴板');
    } catch (e) {
      message.error('复制失败，请手动复制');
    }
    document.body.removeChild(textArea);
  }
};

const toggleApiKeyEnabled = async (apiKey) => {
  const action = apiKey.enabled ? '禁用' : '启用';
  const confirmed = await confirm({
    title: `${action} API 密钥`,
    message: `确定要${action} API 密钥 "${apiKey.name || apiKey.apiKey.substring(0, 20)}..." 吗？`,
    type: apiKey.enabled ? 'warning' : 'info'
  });
  
  if (!confirmed) {
    return;
  }
  
  try {
    await apiService.updateApiKey(apiKey.apiKey, { enabled: !apiKey.enabled });
    await loadApiKeys();
    message.success(`API 密钥已${action}`);
  } catch (err) {
    message.error(`${action}失败: ` + (err.response?.data?.error || err.message));
  }
};

const deleteApiKey = async (apiKey) => {
  const result = await confirm({
    title: '确认删除',
    message: '确定要删除此 API 密钥吗？',
    type: 'danger',
    confirmText: '删除',
    cancelText: '取消'
  });
  
  if (!result) {
    return;
  }
  
  try {
    await apiService.deleteApiKey(apiKey);
    message.success('API 密钥已删除');
    loadApiKeys();
  } catch (err) {
    message.error('删除失败: ' + (err.response?.data?.error || err.message));
  }
};

const formatTime = (timestamp) => {
  if (!timestamp || timestamp === 0) {
    return '从未使用';
  }
  const date = new Date(timestamp);
  return date.toLocaleString('zh-CN');
};

const maskApiKey = (apiKey) => {
  if (!apiKey) return '';
  if (apiKey.length <= 8) {
    // 如果密钥很短，全部用星号代替
    return '*'.repeat(apiKey.length);
  }
  // 显示前 4 个字符和后 4 个字符，中间用星号代替
  const prefix = apiKey.substring(0, 4);
  const suffix = apiKey.substring(apiKey.length - 4);
  const maskedLength = apiKey.length - 8;
  return prefix + '*'.repeat(Math.min(maskedLength, 12)) + suffix;
};

const getProviders = (apiKey) => {
  if (apiKey.providerAccounts && Object.keys(apiKey.providerAccounts).length > 0) {
    return Object.keys(apiKey.providerAccounts);
  }
  if (apiKey.providerName) {
    return [apiKey.providerName];
  }
  return [];
};

const getStatusText = (apiKey) => {
  return apiKey.enabled ? '启用' : '禁用';
};

const getStatusClass = (apiKey) => {
  return apiKey.enabled 
    ? 'text-green-600 dark:text-green-400' 
    : 'text-gray-500 dark:text-gray-400';
};

const openAccountForm = (apiKey) => {
  selectedApiKey.value = apiKey;
  showAccountForm.value = true;
};

const closeAccountForm = () => {
  showAccountForm.value = false;
  selectedApiKey.value = null;
};

const onAccountsSaved = () => {
  closeAccountForm();
  loadApiKeys();
};

onMounted(() => {
  loadApiKeys();
  if (window.lucide) {
    window.lucide.createIcons();
  }
  // 监听 API 密钥保存事件，刷新列表
  window.addEventListener('api-key-saved', loadApiKeys);
});

onUpdated(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});

defineExpose({
  loadApiKeys
});
</script>

