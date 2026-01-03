<template>
  <div class="api-key-account-form">
    <div v-if="error" class="error-message mb-4">{{ error }}</div>
    
    <div v-if="loading" class="loading">加载账号列表中...</div>
    <div v-else>
      <div class="mb-4">
        <p class="text-sm text-gray-600 dark:text-gray-400 mb-4">
          为每个提供器选择一个账号。如果某个提供器不选择账号，该 API 密钥将不支持该提供器的所有模型。
        </p>
      </div>
      
      <div v-if="availableProviders.length === 0" class="empty-state">
        <div class="flex flex-col items-center justify-center text-center py-8">
          <i data-lucide="inbox" class="w-12 h-12 text-gray-300 dark:text-gray-600 mb-4"></i>
          <p class="text-gray-400 dark:text-gray-500 text-sm font-medium mb-1.5">
            暂无可用账号
          </p>
          <p class="text-xs text-gray-400 dark:text-gray-500 max-w-xs">
            请先在"账号"页面添加账号，然后再进行关联
          </p>
        </div>
      </div>
      
      <div v-else class="provider-accounts-grid">
        <div 
          v-for="provider in availableProviders" 
          :key="provider" 
          class="provider-card"
        >
          <div class="provider-card-header">
            <span class="badge badge-info">{{ provider }}</span>
            <span class="text-xs text-gray-500 dark:text-gray-400 ml-2">
              {{ accounts[provider]?.length || 0 }} 个账号
            </span>
          </div>
          
          <div class="account-list">
            <div 
              v-if="!accounts[provider] || accounts[provider].length === 0"
              class="empty-account-list"
            >
              <i data-lucide="inbox" class="w-5 h-5 text-gray-300 dark:text-gray-600"></i>
              <span class="text-xs text-gray-400 dark:text-gray-500">暂无账号</span>
            </div>
            <div 
              v-else
              v-for="account in accounts[provider]" 
              :key="account.accountId"
              class="account-item"
              :class="{ selected: isAccountSelected(provider, account.accountId) }"
              @click="toggleAccount(provider, account.accountId)"
            >
              <div class="account-item-checkbox">
                <input 
                  type="radio"
                  :name="'provider-' + provider"
                  :value="account.accountId"
                  :checked="isAccountSelected(provider, account.accountId)"
                  @change="toggleAccount(provider, account.accountId)"
                />
              </div>
              <div class="account-item-info">
                <div class="account-item-name">{{ getAccountDisplayName(account) }}</div>
                <div class="account-item-meta">
                  <span v-if="account.nickname" class="account-item-nickname">
                    <i data-lucide="user" class="w-3 h-3"></i>
                    {{ account.nickname }}
                  </span>
                </div>
              </div>
              <div class="account-item-indicator">
                <i v-if="isAccountSelected(provider, account.accountId)" data-lucide="check-circle" class="w-5 h-5 text-blue-500"></i>
              </div>
            </div>
            
            <!-- 不选择选项 -->
            <div 
              class="account-item"
              :class="{ selected: !selectedAccounts[provider] }"
              @click="delete selectedAccounts[provider]"
            >
              <div class="account-item-checkbox">
                <input 
                  type="radio"
                  :name="'provider-' + provider"
                  value=""
                  :checked="!selectedAccounts[provider]"
                  @change="delete selectedAccounts[provider]"
                />
              </div>
              <div class="account-item-info">
                <div class="account-item-name text-gray-400 dark:text-gray-500">
                  不选择（不支持此提供器）
                </div>
              </div>
              <div class="account-item-indicator">
                <i v-if="!selectedAccounts[provider]" data-lucide="check-circle" class="w-5 h-5 text-gray-400"></i>
              </div>
            </div>
          </div>
        </div>
      </div>
      
      <div class="form-actions mt-6">
        <button type="button" class="btn btn-secondary" @click="cancel" :disabled="saving">
          取消
        </button>
        <button type="button" class="btn btn-primary" @click="submit" :disabled="saving">
          {{ saving ? '保存中...' : '保存关联' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, onUpdated, ref} from 'vue';
import {apiService} from '../services/api';
import {message} from '../utils/message';

const props = defineProps({
  apiKey: {
    type: Object,
    required: true
  }
});

const emit = defineEmits(['saved', 'cancel']);

const accounts = ref({});
const providers = ref({});
const selectedAccounts = ref({});
const loading = ref(false);
const saving = ref(false);
const error = ref(null);

const availableProviders = computed(() => {
  // 优先使用 providers 列表（即使没有账号也能看到所有 providers）
  const providerNames = Object.keys(providers.value).length > 0 
    ? Object.keys(providers.value).sort()
    : Object.keys(accounts.value).sort();
  return providerNames;
});

const hasSelectedAccounts = computed(() => {
  return Object.keys(selectedAccounts.value).length > 0;
});

onMounted(() => {
  loadProviders();
  loadAccounts();
  if (props.apiKey.providerAccounts) {
    selectedAccounts.value = { ...props.apiKey.providerAccounts };
  } else if (props.apiKey.accountId && props.apiKey.providerName) {
    selectedAccounts.value = { [props.apiKey.providerName]: props.apiKey.accountId };
  }
});

const loadProviders = async () => {
  try {
    const data = await apiService.getProviders();
    providers.value = data || {};
  } catch (err) {
    console.error('加载提供器列表失败:', err);
    // 不显示错误，因为这不是关键功能
  }
};

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

const toggleAccount = (provider, accountId) => {
  if (selectedAccounts.value[provider] === accountId) {
    delete selectedAccounts.value[provider];
  } else {
    selectedAccounts.value[provider] = accountId;
  }
};

const isAccountSelected = (provider, accountId) => {
  return selectedAccounts.value[provider] === accountId;
};

const submit = async () => {
  saving.value = true;
  error.value = null;
  try {
    const providerAccounts = {};
    for (const [provider, accountId] of Object.entries(selectedAccounts.value)) {
      if (accountId) {
        providerAccounts[provider] = accountId;
      }
    }
    await apiService.updateApiKeyAccounts(props.apiKey.apiKey, providerAccounts);
    message.success('关联账号已更新');
    emit('saved');
  } catch (err) {
    const errorMsg = err.response?.data?.error || err.message || '更新失败';
    error.value = errorMsg;
    console.error('更新关联账号失败:', err);
  } finally {
    saving.value = false;
  }
};

const cancel = () => {
  emit('cancel');
};

const getAccountDisplayName = (account) => {
  if (account.nickname) {
    return `${account.accountName} (${account.nickname})`;
  }
  return account.accountName;
};

onUpdated(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});
</script>

