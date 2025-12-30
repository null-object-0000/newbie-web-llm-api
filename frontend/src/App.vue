<template>
  <div class="app-container">
    <Tabs />
    
    <div class="tab-content">
      <router-view v-slot="{ Component }">
        <component :is="Component" :key="$route.path" />
      </router-view>
    </div>
    
    <Modal 
      v-if="showAccountModal" 
      :title="editingAccount && editingAccount.accountId ? (needsLoginForAccount(editingAccount) ? '账号登录' : '编辑账号') : '创建账号'"
      @close="closeAccountModal"
    >
      <AccountForm 
        :account="editingAccount"
        @saved="handleAccountSaved"
        @cancel="closeAccountModal"
      />
    </Modal>
    
    <Modal 
      v-if="showApiKeyModal" 
      :title="editingApiKey ? '编辑 API 密钥' : '创建 API 密钥'"
      @close="closeApiKeyModal"
    >
      <ApiKeyForm 
        :api-key="editingApiKey"
        @saved="handleApiKeySaved"
        @cancel="closeApiKeyModal"
      />
    </Modal>
    
    <!-- Toast 消息 -->
    <Toast ref="toastRef" />
    
    <!-- Confirm 对话框 -->
    <ConfirmProvider ref="confirmRef" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, provide, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import Tabs from './components/Tabs.vue';
import Modal from './components/Modal.vue';
import AccountForm from './components/AccountForm.vue';
import ApiKeyForm from './components/ApiKeyForm.vue';
import Toast from './components/Toast.vue';
import ConfirmProvider from './components/ConfirmProvider.vue';
import { setToastInstance, setConfirmModalInstance } from './utils/message';

const router = useRouter();
const toastRef = ref(null);
const confirmRef = ref(null);

const showAccountModal = ref(false);
const showApiKeyModal = ref(false);
const editingAccount = ref(null);
const editingApiKey = ref(null);

const needsLoginForAccount = computed(() => {
  return (account) => {
    if (!account) return false;
    const playwrightProviders = ['gemini', 'openai', 'deepseek'];
    const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
    return playwrightProviders.includes(account.providerName?.toLowerCase()) && !isVerified;
  };
});

const openAccountModal = (account = null) => {
  editingAccount.value = account;
  showAccountModal.value = true;
};

const openAccountLoginModal = (account) => {
  editingAccount.value = account;
  showAccountModal.value = true;
};

const closeAccountModal = () => {
  showAccountModal.value = false;
  editingAccount.value = null;
};

const openApiKeyModal = (apiKey = null) => {
  editingApiKey.value = apiKey;
  showApiKeyModal.value = true;
};

const closeApiKeyModal = () => {
  showApiKeyModal.value = false;
  editingApiKey.value = null;
};

const handleAccountSaved = (data) => {
  // 通过事件总线或 provide/inject 通知子组件刷新
  window.dispatchEvent(new CustomEvent('account-saved', { detail: data }));
  const account = data && data.account;
  const isLoginSuccess = account && (account.isLoginVerified === true || account.loginVerified === true);
  if (!isLoginSuccess) {
    closeAccountModal();
  }
};

const handleApiKeySaved = () => {
  closeApiKeyModal();
  window.dispatchEvent(new CustomEvent('api-key-saved'));
};

// 提供方法给子组件使用
provide('openAccountModal', openAccountModal);
provide('openAccountLoginModal', openAccountLoginModal);
provide('openApiKeyModal', openApiKeyModal);

onMounted(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
  // 使用 nextTick 确保组件已挂载
  nextTick(() => {
    // 设置全局消息服务
    if (toastRef.value) {
      setToastInstance(toastRef.value);
    }
    if (confirmRef.value) {
      setConfirmModalInstance(confirmRef.value);
    }
    // 暴露到全局，方便使用
    window.$message = toastRef.value;
    window.$confirm = confirmRef.value;
  });
});
</script>

