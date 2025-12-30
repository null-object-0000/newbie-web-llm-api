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
        <button class="btn btn-primary" @click="openApiKeyModal(null)">
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
                <td class="table-cell">
                  <code class="text-xs bg-gray-100 dark:bg-base-200 px-2 py-1 rounded font-mono">{{ maskApiKey(apiKey.apiKey) }}</code>
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
                    >
                      <i data-lucide="link" class="w-3.5 h-3.5"></i>
                    </button>
                    <button 
                      class="action-btn action-btn-switch" 
                      @click="toggleApiKey(apiKey.apiKey, !apiKey.enabled)"
                      :title="apiKey.enabled ? '禁用' : '启用'"
                    >
                      <i :data-lucide="apiKey.enabled ? 'eye-off' : 'eye'" class="w-3.5 h-3.5"></i>
                    </button>
                    <button 
                      class="action-btn action-btn-delete" 
                      @click="deleteApiKey(apiKey.apiKey)"
                      title="删除"
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
import { ref, computed, onMounted, onUpdated, inject } from 'vue';
import { apiService } from '../services/api';
import ApiKeyAccountForm from '../components/ApiKeyAccountForm.vue';
import { message, confirm } from '../utils/message';

const openApiKeyModal = inject('openApiKeyModal', () => {});

const apiKeys = ref([]);
const loading = ref(false);
const error = ref(null);
const searchQuery = ref('');
const showAccountForm = ref(false);
const selectedApiKey = ref(null);

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

const toggleApiKey = async (apiKey, enabled) => {
  try {
    await apiService.updateApiKey(apiKey, { enabled });
    loadApiKeys();
    message.success(enabled ? 'API 密钥已启用' : 'API 密钥已禁用');
  } catch (err) {
    message.error('操作失败: ' + (err.response?.data?.error || err.message));
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
  return apiKey.substring(0, 20) + '...';
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

