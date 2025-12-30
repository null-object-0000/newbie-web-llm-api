<template>
  <div class="h-full flex flex-col p-5 gap-4 max-w-7xl mx-auto w-full">
    <!-- 顶部工具栏 -->
    <div class="flex-none flex items-center gap-4">
      <div class="flex-1 max-w-md relative">
        <i data-lucide="search" class="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400"></i>
        <input
          type="text"
          placeholder="搜索账号..."
          class="w-full pl-9 pr-4 py-2 bg-white dark:bg-base-100 text-sm text-gray-900 dark:text-base-content border border-gray-200 dark:border-base-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent placeholder:text-gray-400 dark:placeholder:text-gray-500"
          v-model="searchQuery"
        />
      </div>
      <div class="relative">
        <select
          v-model="selectedProvider"
          class="px-4 py-2 bg-white dark:bg-base-100 text-sm text-gray-900 dark:text-base-content border border-gray-200 dark:border-base-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent appearance-none pr-8 cursor-pointer"
        >
          <option value="">全部提供器</option>
          <option v-for="provider in availableProviders" :key="provider" :value="provider">
            {{ provider }}
          </option>
        </select>
        <i data-lucide="chevron-down" class="absolute right-2 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400 pointer-events-none"></i>
      </div>
      <div class="flex-1"></div>
                <div class="flex items-center gap-2">
                    <button class="btn btn-primary" @click="openAccountModal(null)">
                        <i data-lucide="plus" class="w-3.5 h-3.5"></i>
                        添加账号
                    </button>
                </div>
    </div>

    <!-- 账号列表内容区域 -->
    <div class="flex-1 min-h-0 overflow-y-auto">
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="error" class="error">错误: {{ error }}</div>
      <div v-else-if="!hasAccounts" class="empty-state">
        <div class="flex flex-col items-center justify-center text-center py-16">
          <div class="w-14 h-14 rounded-full bg-gray-50 dark:bg-base-200 flex items-center justify-center mb-4">
            <i data-lucide="inbox" class="w-7 h-7 text-gray-300 dark:text-gray-600"></i>
          </div>
          <p class="text-gray-400 dark:text-gray-500 text-sm font-medium mb-1.5">
            {{ searchQuery || selectedProvider ? '未找到匹配的账号' : '暂无账号' }}
          </p>
          <p v-if="!searchQuery && !selectedProvider" class="text-xs text-gray-400 dark:text-gray-500 max-w-xs">
            点击上方"添加账号"按钮添加第一个账号
          </p>
        </div>
      </div>
      <div v-else class="bg-white dark:bg-base-100 rounded-2xl shadow-sm border border-gray-100 dark:border-base-200 overflow-hidden">
        <div class="table-container">
          <table class="account-table">
            <thead>
              <tr class="table-header-row">
                <th class="table-header">提供器</th>
                <th class="table-header">账号名称</th>
                <th class="table-header">状态</th>
                <th class="table-header">创建时间</th>
                <th class="table-header">最后使用</th>
                <th class="table-header">操作</th>
              </tr>
            </thead>
            <tbody class="table-body">
              <tr v-for="account in filteredAccounts" :key="account.accountId" class="table-row">
                <td class="table-cell">
                  <span class="badge badge-info">{{ account.providerName }}</span>
                </td>
                <td class="table-cell">
                  <div class="flex flex-col gap-1">
                    <span class="font-medium text-sm">{{ account.accountName }}</span>
                    <span v-if="account.nickname" class="text-xs text-gray-500 dark:text-gray-400">
                      <i data-lucide="user" class="w-3 h-3 inline"></i>
                      {{ account.nickname }}
                    </span>
                  </div>
                </td>
                <td class="table-cell">
                  <span :class="getAccountStatusClass(account)" class="text-sm font-medium">
                    {{ getAccountStatus(account) }}
                  </span>
                </td>
                <td class="table-cell">{{ formatTime(account.createdAt) }}</td>
                <td class="table-cell">{{ formatTime(account.lastUsedAt) }}</td>
                <td class="table-cell">
                  <div class="action-buttons-group">
                    <template v-if="needsLogin(account)">
                      <button 
                        class="action-btn action-btn-primary" 
                        @click="openAccountLoginModal(account)"
                        title="开始登录"
                      >
                        <i data-lucide="log-in" class="w-3.5 h-3.5"></i>
                      </button>
                    </template>
                    <button 
                      class="action-btn action-btn-delete" 
                      @click="deleteAccount(account)"
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
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUpdated, inject } from 'vue';
import { apiService } from '../services/api';
import { message, confirm } from '../utils/message';

const openAccountLoginModal = inject('openAccountLoginModal', () => {});
const openAccountModal = inject('openAccountModal', () => {});

const accounts = ref({});
const loading = ref(false);
const error = ref(null);
const searchQuery = ref('');
const selectedProvider = ref('');

const availableProviders = computed(() => {
  return Object.keys(accounts.value).sort();
});

const flatAccountsList = computed(() => {
  const flatList = [];
  Object.keys(accounts.value).forEach(provider => {
    const accountList = accounts.value[provider] || [];
    accountList.forEach(account => {
      flatList.push({
        ...account,
        providerName: provider
      });
    });
  });
  return flatList;
});

const filteredAccounts = computed(() => {
  let filtered = flatAccountsList.value;
  if (selectedProvider.value) {
    filtered = filtered.filter(account => account.providerName === selectedProvider.value);
  }
  if (searchQuery.value) {
    const query = searchQuery.value.toLowerCase();
    filtered = filtered.filter(account => 
      account.accountName?.toLowerCase().includes(query) ||
      account.nickname?.toLowerCase().includes(query) ||
      account.providerName?.toLowerCase().includes(query)
    );
  }
  return filtered;
});

const hasAccounts = computed(() => {
  return filteredAccounts.value.length > 0;
});

const loadAccounts = async () => {
  loading.value = true;
  error.value = null;
  try {
    const data = await apiService.getAccounts();
    accounts.value = data.accounts || {};
  } catch (err) {
    console.error('加载账号列表失败:', err);
    error.value = err.response?.data?.error || err.message;
  } finally {
    loading.value = false;
  }
};

const deleteAccount = async (account) => {
  const accountName = account?.accountName || '此账号';
  const result = await confirm({
    title: '确认删除',
    message: `确定要删除账号 "${accountName}" 吗？\n\n此操作将同时删除该账号的所有 API 密钥！`,
    type: 'danger',
    confirmText: '删除',
    cancelText: '取消'
  });
  
  if (!result) {
    return;
  }
  
  try {
    await apiService.deleteAccount(account.providerName, account.accountId);
    message.success('账号已删除');
    loadAccounts();
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

const needsLogin = (account) => {
  const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
  return apiService.isPlaywrightProvider(account.providerName) && !isVerified;
};

const getAccountStatus = (account) => {
  if (!apiService.isPlaywrightProvider(account.providerName)) {
    return '无需登录';
  }
  const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
  return isVerified ? '已完成登录' : '未完成登录';
};

const getAccountStatusClass = (account) => {
  if (!apiService.isPlaywrightProvider(account.providerName)) {
    return 'text-gray-500 dark:text-gray-400';
  }
  const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
  return isVerified ? 'text-green-600 dark:text-green-400' : 'text-yellow-600 dark:text-yellow-400';
};

onMounted(() => {
  loadAccounts();
  if (window.lucide) {
    window.lucide.createIcons();
  }
  // 监听账号保存事件，刷新列表
  window.addEventListener('account-saved', loadAccounts);
});

onUpdated(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});

defineExpose({
  loadAccounts
});
</script>

