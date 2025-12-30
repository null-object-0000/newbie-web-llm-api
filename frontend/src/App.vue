<template>
  <div class="app-container" :class="{ 'app-disabled': !playwrightInitialized }">
    <!-- Playwright 初始化状态提示 -->
    <Transition name="init-banner">
      <div v-if="showInitBanner" class="init-status-banner">
        <div class="init-status-content">
          <div class="flex items-center gap-3">
            <div class="init-status-icon">
              <i v-if="playwrightStatus === 'initializing'" data-lucide="loader-2" class="w-5 h-5 animate-spin"></i>
              <i v-else-if="playwrightStatus === 'failed'" data-lucide="alert-circle" class="w-5 h-5"></i>
              <i v-else data-lucide="clock" class="w-5 h-5"></i>
            </div>
            <div class="flex-1">
              <div class="init-status-title">
                <span v-if="playwrightStatus === 'initializing'">正在初始化 Playwright 引擎...</span>
                <span v-else-if="playwrightStatus === 'failed'">Playwright 初始化失败</span>
                <span v-else>等待 Playwright 初始化...</span>
              </div>
              <div v-if="playwrightStatus === 'failed' && playwrightError" class="init-status-error">
                {{ playwrightError }}
              </div>
              <div v-else-if="playwrightStatus === 'initializing'" class="init-status-desc">
                系统正在后台初始化浏览器引擎，请稍候。在此期间，您只能查看页面内容。
              </div>
              <div v-else class="init-status-desc">
                系统正在准备中，请稍候...
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
    
    <Tabs />
    
    <div class="tab-content" :class="{ 'opacity-50 pointer-events-none': !playwrightInitialized }">
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
import {computed, nextTick, onMounted, onUnmounted, provide, ref, watch} from 'vue';
import {useRouter} from 'vue-router';
import Tabs from './components/Tabs.vue';
import Modal from './components/Modal.vue';
import AccountForm from './components/AccountForm.vue';
import ApiKeyForm from './components/ApiKeyForm.vue';
import Toast from './components/Toast.vue';
import ConfirmProvider from './components/ConfirmProvider.vue';
import {setConfirmModalInstance, setToastInstance} from './utils/message';
import {apiService} from './services/api';

const router = useRouter();
const toastRef = ref(null);
const confirmRef = ref(null);

const showAccountModal = ref(false);
const showApiKeyModal = ref(false);
const editingAccount = ref(null);
const editingApiKey = ref(null);

// Playwright 初始化状态
const playwrightStatus = ref('initializing'); // 'initializing' | 'initialized' | 'failed'
const playwrightInitialized = ref(false);
const playwrightError = ref(null);
const showInitBanner = ref(true); // 控制横幅显示，用于平滑过渡
let statusCheckInterval = null;
let hideBannerTimeout = null;

const needsLoginForAccount = computed(() => {
  return (account) => {
    if (!account) return false;
    const playwrightProviders = ['gemini', 'openai', 'deepseek'];
    const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
    return playwrightProviders.includes(account.providerName?.toLowerCase()) && !isVerified;
  };
});

const openAccountModal = (account = null) => {
  if (!playwrightInitialized.value) {
    return;
  }
  editingAccount.value = account;
  showAccountModal.value = true;
};

const openAccountLoginModal = (account) => {
  if (!playwrightInitialized.value) {
    return;
  }
  editingAccount.value = account;
  showAccountModal.value = true;
};

const closeAccountModal = () => {
  showAccountModal.value = false;
  editingAccount.value = null;
};

const openApiKeyModal = (apiKey = null) => {
  if (!playwrightInitialized.value) {
    return;
  }
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

// 检查 Playwright 初始化状态
const checkPlaywrightStatus = async () => {
  try {
    const status = await apiService.getStatus();
    if (status.playwright) {
      const wasInitialized = playwrightInitialized.value;
      playwrightStatus.value = status.playwright.status;
      playwrightInitialized.value = status.playwright.initialized;
      playwrightError.value = status.playwright.error || null;
      
      // 如果已初始化或失败，停止轮询
      if (playwrightStatus.value === 'initialized' || playwrightStatus.value === 'failed') {
        if (statusCheckInterval) {
          clearInterval(statusCheckInterval);
          statusCheckInterval = null;
        }
        
        // 如果从未初始化变为已初始化，延迟隐藏横幅以实现平滑过渡
        if (!wasInitialized && playwrightInitialized.value && playwrightStatus.value === 'initialized') {
          // 延迟 500ms 后开始淡出动画，给用户一个视觉反馈
          if (hideBannerTimeout) {
            clearTimeout(hideBannerTimeout);
          }
          hideBannerTimeout = setTimeout(() => {
            showInitBanner.value = false;
          }, 500);
        }
      }
      
      // 如果初始化失败，保持横幅显示
      if (playwrightStatus.value === 'failed') {
        showInitBanner.value = true;
      }
    }
  } catch (error) {
    console.error('检查 Playwright 状态失败:', error);
    // 即使检查失败也继续轮询
  }
};

// 提供方法给子组件使用
provide('openAccountModal', openAccountModal);
provide('openAccountLoginModal', openAccountLoginModal);
provide('openApiKeyModal', openApiKeyModal);
provide('playwrightInitialized', playwrightInitialized);

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
    
    // 立即检查一次状态
    checkPlaywrightStatus();
    
    // 每 2 秒轮询一次状态，直到初始化完成或失败
    statusCheckInterval = setInterval(() => {
      if (playwrightStatus.value === 'initializing') {
        checkPlaywrightStatus();
      }
    }, 2000);
  });
  
  // 监听状态变化，更新图标
  watch([playwrightStatus, playwrightInitialized], () => {
    nextTick(() => {
      if (window.lucide) {
        window.lucide.createIcons();
      }
    });
  });
});

onUnmounted(() => {
  if (statusCheckInterval) {
    clearInterval(statusCheckInterval);
    statusCheckInterval = null;
  }
  if (hideBannerTimeout) {
    clearTimeout(hideBannerTimeout);
    hideBannerTimeout = null;
  }
});
</script>

<style scoped>
.init-status-banner {
  position: sticky;
  top: 0;
  z-index: 100;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.init-status-content {
  max-width: 80rem; /* max-w-7xl */
  margin-left: auto;
  margin-right: auto;
  padding-left: 2rem; /* px-8 */
  padding-right: 2rem;
  padding-top: 1rem; /* py-4 */
  padding-bottom: 1rem;
}

.init-status-icon {
  flex-shrink: 0;
}

.init-status-title {
  font-weight: 600;
  font-size: 0.95rem;
  margin-bottom: 0.25rem;
}

.init-status-desc {
  font-size: 0.85rem;
  opacity: 0.9;
  line-height: 1.4;
}

.init-status-error {
  font-size: 0.85rem;
  opacity: 0.95;
  margin-top: 0.25rem;
  padding: 0.5rem;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 0.375rem;
  font-family: monospace;
}

.app-disabled {
  position: relative;
}

.app-disabled::after {
  content: '';
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.02);
  pointer-events: none;
  z-index: 50;
}

.tab-content.opacity-50 {
  transition: opacity 0.3s ease;
}

/* 初始化横幅过渡动画 */
.init-banner-enter-active {
  transition: all 0.3s ease-out;
}

.init-banner-leave-active {
  transition: all 0.4s ease-in;
}

.init-banner-enter-from {
  opacity: 0;
  transform: translateY(-100%);
}

.init-banner-leave-to {
  opacity: 0;
  transform: translateY(-100%);
}

.init-banner-enter-to,
.init-banner-leave-from {
  opacity: 1;
  transform: translateY(0);
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.animate-spin {
  animation: spin 1s linear infinite;
}
</style>

