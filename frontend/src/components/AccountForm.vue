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
      
      <!-- DeepSeek 专用登录流程 -->
      <div v-if="isDeepSeekProvider" class="deepseek-login-flow">
        <!-- 登录方式选择 -->
        <div v-if="!selectedLoginMethod" class="login-method-selection">
          <h4 class="mb-4">请选择登录方式</h4>
          <div class="login-methods">
            <button
              type="button"
              class="login-method-btn"
              @click="selectLoginMethod('manual')"
              :disabled="loginStatus === 'logging'"
            >
              <i data-lucide="monitor" class="w-6 h-6"></i>
              <span>手动登录</span>
              <small>在浏览器中手动完成登录</small>
            </button>
            <button
              type="button"
              class="login-method-btn"
              @click="selectLoginMethod('account-password')"
              :disabled="loginStatus === 'logging'"
            >
              <i data-lucide="key" class="w-6 h-6"></i>
              <span>账号+密码登录</span>
              <small>自动填写账号密码</small>
            </button>
            <button
              type="button"
              class="login-method-btn"
              @click="selectLoginMethod('qr-code')"
              :disabled="loginStatus === 'logging'"
            >
              <i data-lucide="qr-code" class="w-6 h-6"></i>
              <span>二维码登录</span>
              <small>微信扫码登录</small>
            </button>
          </div>
        </div>
        
        <!-- 手动登录（原有方式） -->
        <div v-else-if="selectedLoginMethod === 'manual'" class="manual-login">
          <h4 class="mb-4">手动登录</h4>
          <div class="manual-login-info">
            <p>此方式将在浏览器中打开登录页面，您需要手动完成登录操作。</p>
            <p class="text-sm text-gray-500 dark:text-gray-400 mt-2">
              提示：如果浏览器运行模式为"无界面运行"，此方式可能无法使用，请选择其他登录方式。
            </p>
          </div>
          <div class="form-actions">
            <button
              type="button"
              class="btn btn-secondary"
              @click="resetLoginMethod"
              :disabled="loginStatus === 'logging'"
            >
              返回
            </button>
            <button
              type="button"
              class="btn btn-primary"
              @click="startManualLogin"
              :disabled="loginStatus === 'logging' || loginStatus === 'verifying'"
            >
              <i data-lucide="log-in" class="w-4 h-4"></i>
              {{ loginStatus === 'logging' ? '启动中...' : '开始登录' }}
            </button>
            <button
              v-if="loginSessionId && (loginStatus === 'logging' || loginStatus === 'failed')"
              type="button"
              class="btn btn-success"
              @click="verifyLogin"
              :disabled="verifying"
            >
              <i data-lucide="check" class="w-4 h-4"></i>
              {{ verifying ? '验证中...' : '验证登录' }}
            </button>
          </div>
        </div>
        
        <!-- 账号+密码登录表单 -->
        <div v-else-if="selectedLoginMethod === 'account-password'" class="account-password-form">
          <h4 class="mb-4">账号+密码登录</h4>
          <div class="form-group">
            <label>账号 *</label>
            <input
              type="text"
              v-model="loginForm.account"
              placeholder="请输入邮箱或用户名"
              :disabled="loginStatus === 'logging'"
              required
            />
          </div>
          <div class="form-group">
            <label>密码 *</label>
            <input
              type="password"
              v-model="loginForm.password"
              placeholder="请输入密码"
              :disabled="loginStatus === 'logging'"
              required
            />
          </div>
          <div class="form-actions">
            <button
              type="button"
              class="btn btn-secondary"
              @click="resetLoginMethod"
              :disabled="loginStatus === 'logging'"
            >
              返回
            </button>
            <button
              type="button"
              class="btn btn-primary"
              @click="startAccountPasswordLogin"
              :disabled="loginStatus === 'logging' || !loginForm.account || !loginForm.password"
            >
              <i data-lucide="log-in" class="w-4 h-4"></i>
              {{ loginStatus === 'logging' ? '登录中...' : '开始登录' }}
            </button>
          </div>
        </div>
        
        <!-- 二维码登录 -->
        <div v-else-if="selectedLoginMethod === 'qr-code'" class="qr-code-login">
          <h4 class="mb-4">二维码登录</h4>
          <div v-if="qrCodeImage" class="qr-code-container">
            <img :src="qrCodeImage" alt="登录二维码" class="qr-code-image" />
            <p class="qr-code-hint">请使用微信扫描上方二维码完成登录</p>
            <div class="form-actions">
              <button
                type="button"
                class="btn btn-secondary"
                @click="resetLoginMethod"
                :disabled="loginStatus === 'logging'"
              >
                返回
              </button>
              <button
                type="button"
                class="btn btn-success"
                @click="confirmQrCodeScanned"
                :disabled="loginStatus === 'logging' || loginStatus === 'verifying'"
              >
                <i data-lucide="check" class="w-4 h-4"></i>
                {{ loginStatus === 'verifying' ? '验证中...' : '我已扫码' }}
              </button>
            </div>
          </div>
          <div v-else class="qr-code-loading">
            <button
              type="button"
              class="btn btn-secondary"
              @click="resetLoginMethod"
            >
              返回
            </button>
            <button
              type="button"
              class="btn btn-primary"
              @click="startQrCodeLogin"
              :disabled="loginStatus === 'logging'"
            >
              <i data-lucide="qr-code" class="w-4 h-4"></i>
              {{ loginStatus === 'logging' ? '获取中...' : '获取二维码' }}
            </button>
          </div>
        </div>
      </div>
      
      <!-- 其他提供器的登录流程（保持原有逻辑） -->
      <div v-else class="legacy-login-flow">
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
      
      <!-- 登录状态提示 -->
      <div v-if="loginStatus && loginStatus !== 'idle'" class="login-status-message" :class="loginStatus">
        <div v-if="loginStatus === 'logging'" class="status-content">
          <i data-lucide="loader" class="w-5 h-5 animate-spin"></i>
          <p>{{ loginMessage || '正在登录...' }}</p>
        </div>
        <div v-else-if="loginStatus === 'verifying'" class="status-content">
          <i data-lucide="loader" class="w-5 h-5 animate-spin"></i>
          <p>{{ loginMessage || '正在验证登录状态...' }}</p>
        </div>
        <div v-else-if="loginStatus === 'success'" class="status-content success">
          <i data-lucide="check-circle" class="w-5 h-5"></i>
          <p>{{ loginMessage || '登录成功！' }}</p>
        </div>
        <div v-else-if="loginStatus === 'failed'" class="status-content error">
          <i data-lucide="x-circle" class="w-5 h-5"></i>
          <p>{{ loginMessage || '登录失败' }}</p>
        </div>
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
import {computed, onMounted, onUnmounted, onUpdated, ref} from 'vue';
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
  browserHeadless: false // 默认有界面运行
});
const loading = ref(false);
const error = ref(null);
const createdAccount = ref(null);
const loginSessionId = ref(null);
const loginStatus = ref(null);
const loginMessage = ref(null);
const verifying = ref(false);

// DeepSeek 登录相关
const selectedLoginMethod = ref(null); // 'account-password' | 'qr-code' | null
const loginForm = ref({
  account: '',
  password: ''
});
const qrCodeImage = ref(null);
const conversationId = ref(null);
const eventSource = ref(null);

const isPlaywrightProvider = computed(() => {
  return PLAYWRIGHT_PROVIDERS.includes(form.value.provider.toLowerCase());
});

const isDeepSeekProvider = computed(() => {
  return createdAccount.value && createdAccount.value.providerName?.toLowerCase() === 'deepseek';
});

const canStartLogin = computed(() => {
  return createdAccount.value && isPlaywrightProvider.value && 
         !isDeepSeekProvider.value &&
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
      browserHeadless: props.account.browserHeadless !== undefined ? props.account.browserHeadless : false
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
  if (!createdAccount.value) {
    error.value = '账号信息不存在';
    return;
  }
  verifying.value = true;
  loginStatus.value = 'verifying';
  loginMessage.value = '正在验证登录状态...';
  error.value = null;
  try {
    // 使用新的提供器登录验证接口
    const data = await apiService.verifyProviderLogin(
      createdAccount.value.providerName,
      createdAccount.value.accountId
    );
    if (data.success) {
      loginStatus.value = 'success';
      loginMessage.value = `登录验证成功！实际登录账号: ${data.actualAccount || createdAccount.value.accountName}`;
      if (data.actualAccount) {
        createdAccount.value.accountName = data.actualAccount;
      }
      if (data.nickname) {
        createdAccount.value.nickname = data.nickname;
      }
      createdAccount.value.isLoginVerified = true;
      emit('saved', { account: createdAccount.value });
      setTimeout(() => {
        emit('cancel');
      }, 2000);
    } else {
      loginStatus.value = 'failed';
      loginMessage.value = '登录验证失败: ' + (data.message || data.error || '未知错误');
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
  // 清理 SSE 连接
  if (eventSource.value) {
    eventSource.value.close();
    eventSource.value = null;
  }
  emit('cancel');
};

// DeepSeek 登录方法
const selectLoginMethod = (method) => {
  selectedLoginMethod.value = method;
  loginForm.value = { account: '', password: '' };
  qrCodeImage.value = null;
  conversationId.value = null;
  loginStatus.value = null;
  loginMessage.value = null;
  loginSessionId.value = null;
};

const startManualLogin = async () => {
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
    loginStatus.value = 'logging'; // 保持 logging 状态，等待用户手动登录
    loginMessage.value = '浏览器已启动，请在浏览器中完成登录，然后点击"验证登录"按钮';
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
    loginStatus.value = 'failed';
    loginMessage.value = '启动登录失败: ' + (err.response?.data?.error || err.message);
  }
};

const resetLoginMethod = () => {
  selectedLoginMethod.value = null;
  loginForm.value = { account: '', password: '' };
  qrCodeImage.value = null;
  conversationId.value = null;
  loginStatus.value = null;
  loginMessage.value = null;
  if (eventSource.value) {
    eventSource.value.close();
    eventSource.value = null;
  }
};

const startAccountPasswordLogin = async () => {
  if (!createdAccount.value || !loginForm.value.account || !loginForm.value.password) {
    error.value = '请填写账号和密码';
    return;
  }
  
  loginStatus.value = 'logging';
  loginMessage.value = '正在登录...';
  error.value = null;
  
  try {
    const data = await apiService.startAccountPasswordLogin(
      createdAccount.value.providerName,
      createdAccount.value.accountId,
      loginForm.value.account,
      loginForm.value.password
    );
    
    if (data.success) {
      loginSessionId.value = data.sessionId;
      loginStatus.value = 'success';
      loginMessage.value = '登录成功！';
      createdAccount.value.isLoginVerified = true;
      emit('saved', { account: createdAccount.value });
      setTimeout(() => {
        emit('cancel');
      }, 2000);
    } else {
      loginStatus.value = 'failed';
      loginMessage.value = data.message || data.error || '登录失败';
      error.value = data.error || data.message;
    }
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
    loginStatus.value = 'failed';
    loginMessage.value = '登录失败: ' + (err.response?.data?.error || err.message);
  }
};

const startQrCodeLogin = async () => {
  if (!createdAccount.value) {
    error.value = '账号信息不存在';
    return;
  }
  
  loginStatus.value = 'logging';
  loginMessage.value = '正在获取二维码...';
  error.value = null;
  qrCodeImage.value = null;
  
  try {
    const data = await apiService.startQrCodeLogin(
      createdAccount.value.providerName,
      createdAccount.value.accountId
    );
    
    if (data.sessionId) {
      loginSessionId.value = data.sessionId;
    }
    // 优先使用 base64 格式的二维码，如果没有则使用 URL
    if (data.qrCodeBase64) {
      qrCodeImage.value = data.qrCodeBase64;
      loginStatus.value = 'logging';
      loginMessage.value = '请使用微信扫描二维码完成登录';
    } else if (data.qrCodeImageUrl) {
      qrCodeImage.value = data.qrCodeImageUrl;
      loginStatus.value = 'logging';
      loginMessage.value = '请使用微信扫描二维码完成登录';
    } else {
      loginStatus.value = 'failed';
      loginMessage.value = data.error || '获取二维码失败';
    }
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
    loginStatus.value = 'failed';
    loginMessage.value = '获取二维码失败: ' + (err.response?.data?.error || err.message);
  }
};

const confirmQrCodeScanned = async () => {
  if (!createdAccount.value || !loginSessionId.value) {
    error.value = '登录会话不存在';
    return;
  }
  
  loginStatus.value = 'verifying';
  loginMessage.value = '正在验证登录状态...';
  error.value = null;
  
  try {
    const data = await apiService.confirmQrCodeScanned(
      createdAccount.value.providerName,
      createdAccount.value.accountId,
      loginSessionId.value
    );
    
    if (data.success) {
      loginStatus.value = 'success';
      loginMessage.value = '登录成功！';
      createdAccount.value.isLoginVerified = true;
      emit('saved', { account: createdAccount.value });
      setTimeout(() => {
        emit('cancel');
      }, 2000);
    } else {
      loginStatus.value = 'failed';
      loginMessage.value = data.message || data.error || '登录尚未完成';
      error.value = data.error || data.message;
    }
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
    loginStatus.value = 'failed';
    loginMessage.value = '验证失败: ' + (err.response?.data?.error || err.message);
  }
};


onUpdated(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});

// 组件卸载时清理 SSE 连接
onUnmounted(() => {
  if (eventSource.value) {
    eventSource.value.close();
    eventSource.value = null;
  }
});
</script>

<style scoped>
.login-flow {
  margin-top: 1.5rem;
}

.login-info {
  margin-bottom: 1.5rem;
  padding: 1rem;
  background: var(--bg-secondary, #f5f5f5);
  border-radius: 0.5rem;
}

.login-info h3 {
  margin-bottom: 0.5rem;
  font-size: 1.125rem;
  font-weight: 600;
}

.login-info p {
  margin: 0.25rem 0;
  font-size: 0.875rem;
  color: var(--text-secondary, #666);
}

/* DeepSeek 登录样式 */
.deepseek-login-flow {
  margin-top: 1rem;
}

.login-method-selection {
  margin-bottom: 1.5rem;
}

.login-method-selection h4 {
  margin-bottom: 1rem;
  font-size: 1rem;
  font-weight: 600;
}

.login-methods {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
  margin-bottom: 1rem;
}

.login-method-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 1.5rem;
  border: 2px solid var(--border-color, #e0e0e0);
  border-radius: 0.5rem;
  background: var(--bg-primary, #fff);
  cursor: pointer;
  transition: all 0.2s;
  text-align: center;
}

.login-method-btn:hover:not(:disabled) {
  border-color: var(--primary-color, #3b82f6);
  background: var(--bg-hover, #f9fafb);
}

.login-method-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.login-method-btn i {
  color: var(--primary-color, #3b82f6);
}

.login-method-btn span {
  font-weight: 600;
  font-size: 1rem;
}

.login-method-btn small {
  font-size: 0.75rem;
  color: var(--text-secondary, #666);
  font-weight: normal;
}

.account-password-form,
.qr-code-login,
.manual-login {
  margin-top: 1rem;
}

.manual-login-info {
  margin-bottom: 1.5rem;
  padding: 1rem;
  background: var(--bg-secondary, #f5f5f5);
  border-radius: 0.5rem;
}

.manual-login-info p {
  margin: 0.5rem 0;
  line-height: 1.6;
}

.account-password-form h4,
.qr-code-login h4 {
  margin-bottom: 1rem;
  font-size: 1rem;
  font-weight: 600;
}

.qr-code-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 1.5rem;
  background: var(--bg-secondary, #f5f5f5);
  border-radius: 0.5rem;
}

.qr-code-image {
  max-width: 300px;
  max-height: 300px;
  border: 2px solid var(--border-color, #e0e0e0);
  border-radius: 0.5rem;
  padding: 0.5rem;
  background: white;
}

.qr-code-hint {
  text-align: center;
  color: var(--text-secondary, #666);
  font-size: 0.875rem;
}

.qr-code-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 2rem;
}

.login-status-message {
  margin-top: 1rem;
  padding: 1rem;
  border-radius: 0.5rem;
}

.login-status-message.logging,
.login-status-message.verifying {
  background: var(--info-bg, #e0f2fe);
  border: 1px solid var(--info-border, #7dd3fc);
}

.login-status-message.success {
  background: var(--success-bg, #dcfce7);
  border: 1px solid var(--success-border, #86efac);
}

.login-status-message.failed {
  background: var(--error-bg, #fee2e2);
  border: 1px solid var(--error-border, #fca5a5);
}

.status-content {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.status-content i {
  flex-shrink: 0;
}

.status-content.success i {
  color: var(--success-color, #22c55e);
}

.status-content.error i {
  color: var(--error-color, #ef4444);
}

.form-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  margin-top: 1rem;
}

/* 暗色模式支持 */
@media (prefers-color-scheme: dark) {
  .login-info {
    background: var(--bg-secondary, #1f2937);
  }
  
  .login-method-btn {
    background: var(--bg-primary, #111827);
    border-color: var(--border-color, #374151);
  }
  
  .qr-code-container {
    background: var(--bg-secondary, #1f2937);
  }
  
  .qr-code-image {
    background: #374151;
  }
}
</style>

