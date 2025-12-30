<template>
  <form @submit.prevent="submit">
    <div v-if="error" class="error-message">{{ error }}</div>
    
    <div v-if="!createdAccount">
      <div class="form-group">
        <label>提供器 *</label>
        <select v-model="form.provider" required :disabled="loading">
          <option value="">请选择</option>
          <option value="gemini">Gemini (Playwright)</option>
          <option value="openai">OpenAI (Playwright)</option>
          <option value="deepseek">DeepSeek (Playwright)</option>
          <option value="antigravity">Antigravity (逆向 API)</option>
        </select>
      </div>
      
      <div class="form-group">
        <label>账号名称 *</label>
        <input 
          type="text" 
          v-model="form.accountName" 
          required 
          placeholder="例如: user@example.com"
          :disabled="loading"
        />
        <small class="text-gray-500 dark:text-gray-400">
          {{ isPlaywrightProvider ? '请输入账号邮箱或用户名，登录后将自动验证' : '请输入账号标识' }}
        </small>
      </div>
      
      <div v-if="isPlaywrightProvider" class="form-group">
        <label>浏览器运行模式</label>
        <select v-model="form.browserHeadless" :disabled="loading" class="w-full">
          <option :value="null">使用全局配置</option>
          <option :value="false">有界面运行（Headed）</option>
          <option :value="true">无界面运行（Headless）</option>
        </select>
        <small class="text-gray-500 dark:text-gray-400">
          选择该账号对应的 Chrome 浏览器运行模式。有界面模式适合需要手动操作或调试的场景，无界面模式适合自动化运行。
        </small>
      </div>
      
      <div class="form-actions">
        <button type="button" class="btn btn-secondary" @click="cancel" :disabled="loading">
          取消
        </button>
        <button type="submit" class="btn btn-primary" :disabled="loading">
          {{ loading ? '创建中...' : '创建账号' }}
        </button>
      </div>
    </div>
    
    <!-- 登录流程 -->
    <div v-else-if="isPlaywrightProvider" class="login-flow">
      <div class="login-info">
        <h3>账号创建成功</h3>
        <p><strong>提供器:</strong> {{ createdAccount.providerName }}</p>
        <p><strong>账号名称:</strong> {{ createdAccount.accountName }}</p>
      </div>
      
      <div class="login-status" :class="loginStatus">
        <div v-if="loginStatus === 'idle'" class="status-message">
          <i data-lucide="info" class="w-5 h-5"></i>
          <p>{{ loginMessage || '请点击"开始登录"按钮启动浏览器' }}</p>
        </div>
        <div v-else-if="loginStatus === 'logging'" class="status-message">
          <i data-lucide="loader" class="w-5 h-5 animate-spin"></i>
          <p>{{ loginMessage }}</p>
        </div>
        <div v-else-if="loginStatus === 'verifying'" class="status-message">
          <i data-lucide="loader" class="w-5 h-5 animate-spin"></i>
          <p>{{ loginMessage }}</p>
        </div>
        <div v-else-if="loginStatus === 'success'" class="status-message success">
          <i data-lucide="check-circle" class="w-5 h-5"></i>
          <p>{{ loginMessage }}</p>
        </div>
        <div v-else-if="loginStatus === 'failed'" class="status-message error">
          <i data-lucide="x-circle" class="w-5 h-5"></i>
          <p>{{ loginMessage }}</p>
        </div>
      </div>
      
      <div class="login-actions">
        <button 
          v-if="canStartLogin"
          type="button" 
          class="btn btn-primary" 
          @click="startLogin"
        >
          <i data-lucide="log-in" class="w-4 h-4"></i>
          开始登录
        </button>
        <button 
          v-if="canVerifyLogin"
          type="button" 
          class="btn btn-success" 
          @click="verifyLogin"
          :disabled="verifying"
        >
          <i data-lucide="check" class="w-4 h-4"></i>
          {{ verifying ? '验证中...' : '验证登录' }}
        </button>
        <button 
          v-if="loginStatus === 'success'"
          type="button" 
          class="btn btn-secondary" 
          @click="cancel"
        >
          关闭
        </button>
      </div>
    </div>
    
    <!-- 非 Playwright 提供器，直接完成 -->
    <div v-else class="success-message">
      <i data-lucide="check-circle" class="w-5 h-5"></i>
      <p>账号创建成功！</p>
      <button type="button" class="btn btn-primary" @click="$emit('saved', { account: createdAccount })">
        完成
      </button>
    </div>
  </form>
</template>

<script setup>
import {computed, onMounted, onUpdated, ref} from 'vue';
import {apiService} from '../services/api';
import {message} from '../utils/message';

const props = defineProps({
  account: {
    type: Object,
    default: null
  }
});

const emit = defineEmits(['saved', 'cancel']);

const PLAYWRIGHT_PROVIDERS = ['gemini', 'openai', 'deepseek'];

const form = ref({
  provider: '',
  accountName: '',
  browserHeadless: null // null 表示使用全局配置
});
const loading = ref(false);
const error = ref(null);
const createdAccount = ref(null);
const loginSessionId = ref(null);
const loginStatus = ref(null);
const loginMessage = ref(null);
const verifying = ref(false);

const isPlaywrightProvider = computed(() => {
  return PLAYWRIGHT_PROVIDERS.includes(form.value.provider.toLowerCase());
});

const canStartLogin = computed(() => {
  return createdAccount.value && isPlaywrightProvider.value && 
         loginStatus.value !== 'logging' && loginStatus.value !== 'verifying';
});

const canVerifyLogin = computed(() => {
  return loginSessionId.value && 
         (loginStatus.value === 'logging' || loginStatus.value === 'failed');
});

onMounted(() => {
  if (props.account) {
    form.value = {
      provider: props.account.providerName || '',
      accountName: props.account.accountName || '',
      browserHeadless: props.account.browserHeadless !== undefined ? props.account.browserHeadless : null
    };
    if (isPlaywrightProvider.value && props.account.accountId) {
      createdAccount.value = props.account;
      loginStatus.value = 'idle';
      loginMessage.value = '请点击"开始登录"按钮启动浏览器登录';
    }
  }
});

const submit = async () => {
  if (!form.value.provider || !form.value.accountName) {
    error.value = '请填写所有必填字段';
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const data = await apiService.createAccount(form.value);
    createdAccount.value = data.account;
    if (isPlaywrightProvider.value) {
      loginStatus.value = 'idle';
      loginMessage.value = '账号已创建，请点击"开始登录"按钮启动浏览器登录';
    } else {
      message.success('账号创建成功！');
      emit('saved', data);
    }
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
  } finally {
    loading.value = false;
  }
};

const startLogin = async () => {
  if (!createdAccount.value) {
    error.value = '请先创建账号';
    return;
  }
  loginStatus.value = 'logging';
  loginMessage.value = '正在启动浏览器...';
  error.value = null;
  try {
    const data = await apiService.startLogin(
      createdAccount.value.providerName,
      createdAccount.value.accountId
    );
    loginSessionId.value = data.sessionId;
    loginMessage.value = '浏览器已启动，请在浏览器中完成登录，然后点击"验证登录"按钮';
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
    loginStatus.value = 'failed';
    loginMessage.value = '启动登录失败: ' + (err.response?.data?.error || err.message);
  }
};

const verifyLogin = async () => {
  if (!loginSessionId.value) {
    error.value = '登录会话不存在';
    return;
  }
  verifying.value = true;
  loginStatus.value = 'verifying';
  loginMessage.value = '正在验证登录状态...';
  error.value = null;
  try {
    const data = await apiService.verifyLogin(loginSessionId.value);
    if (data.success) {
      loginStatus.value = 'success';
      loginMessage.value = `登录验证成功！实际登录账号: ${data.actualAccount || createdAccount.value.accountName}`;
      if (data.actualAccount) {
        createdAccount.value.accountName = data.actualAccount;
      }
      createdAccount.value.isLoginVerified = true;
      emit('saved', { account: createdAccount.value });
      setTimeout(() => {
        emit('cancel');
      }, 2000);
    } else {
      loginStatus.value = 'failed';
      loginMessage.value = '登录验证失败: ' + (data.message || '未知错误');
    }
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
    loginStatus.value = 'failed';
    loginMessage.value = '验证登录失败: ' + (err.response?.data?.error || err.message);
  } finally {
    verifying.value = false;
  }
};

const cancel = () => {
  emit('cancel');
};

onUpdated(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});
</script>

