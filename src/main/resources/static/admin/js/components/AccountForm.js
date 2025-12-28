// 使用全局 apiService
const apiService = window.apiService || {
    createAccount: async (account) => {
        const response = await axios.post(`${window.location.origin}/admin/accounts`, account);
        return response.data;
    },
    startLogin: async (providerName, accountId) => {
        const response = await axios.post(`${window.location.origin}/admin/accounts/${providerName}/${accountId}/login/start`);
        return response.data;
    },
    verifyLogin: async (sessionId) => {
        const response = await axios.post(`${window.location.origin}/admin/accounts/login/verify`, { sessionId });
        return response.data;
    }
};

// Playwright 类提供器列表
const PLAYWRIGHT_PROVIDERS = ['gemini', 'openai', 'deepseek'];

const AccountForm = {
    name: 'AccountForm',
    props: {
        account: {
            type: Object,
            default: null
        }
    },
    emits: ['saved', 'cancel'],
    data() {
        return {
            form: {
                provider: '',
                accountName: ''
            },
            loading: false,
            error: null,
            createdAccount: null,
            loginSessionId: null,
            loginStatus: null, // 'idle', 'logging', 'verifying', 'success', 'failed'
            loginMessage: null,
            verifying: false
        };
    },
    mounted() {
        if (this.account) {
            this.form = {
                provider: this.account.providerName || '',
                accountName: this.account.accountName || ''
            };
            // 如果是已有账号且是 Playwright 提供器，直接设置为已创建状态，显示登录流程
            if (this.isPlaywrightProvider && this.account.accountId) {
                this.createdAccount = this.account;
                this.loginStatus = 'idle';
                this.loginMessage = '请点击"开始登录"按钮启动浏览器登录';
            }
        }
    },
    computed: {
        isPlaywrightProvider() {
            return PLAYWRIGHT_PROVIDERS.includes(this.form.provider.toLowerCase());
        },
        canStartLogin() {
            return this.createdAccount && this.isPlaywrightProvider && 
                   this.loginStatus !== 'logging' && this.loginStatus !== 'verifying';
        },
        canVerifyLogin() {
            return this.loginSessionId && 
                   (this.loginStatus === 'logging' || this.loginStatus === 'failed');
        }
    },
    methods: {
        async submit() {
            if (!this.form.provider || !this.form.accountName) {
                this.error = '请填写所有必填字段';
                return;
            }
            
            this.loading = true;
            this.error = null;
            
            try {
                const data = await apiService.createAccount(this.form);
                this.createdAccount = data.account;
                
                // 如果是 Playwright 类提供器，提示用户开始登录
                if (this.isPlaywrightProvider) {
                    this.loginStatus = 'idle';
                    this.loginMessage = '账号已创建，请点击"开始登录"按钮启动浏览器登录';
                } else {
                    alert('账号创建成功！');
                    this.$emit('saved', data);
                }
            } catch (error) {
                this.error = error.response?.data?.error || error.message;
            } finally {
                this.loading = false;
            }
        },
        async startLogin() {
            if (!this.createdAccount) {
                this.error = '请先创建账号';
                return;
            }
            
            this.loginStatus = 'logging';
            this.loginMessage = '正在启动浏览器...';
            this.error = null;
            
            try {
                const data = await apiService.startLogin(
                    this.createdAccount.providerName,
                    this.createdAccount.accountId
                );
                this.loginSessionId = data.sessionId;
                this.loginMessage = '浏览器已启动，请在浏览器中完成登录，然后点击"验证登录"按钮';
            } catch (error) {
                this.error = error.response?.data?.error || error.message;
                this.loginStatus = 'failed';
                this.loginMessage = '启动登录失败: ' + (error.response?.data?.error || error.message);
            }
        },
        async verifyLogin() {
            if (!this.loginSessionId) {
                this.error = '登录会话不存在';
                return;
            }
            
            this.verifying = true;
            this.loginStatus = 'verifying';
            this.loginMessage = '正在验证登录状态...';
            this.error = null;
            
            try {
                const data = await apiService.verifyLogin(this.loginSessionId);
                
                if (data.success) {
                    this.loginStatus = 'success';
                    this.loginMessage = `登录验证成功！实际登录账号: ${data.actualAccount || this.createdAccount.accountName}`;
                    
                    // 更新账号信息
                    if (data.actualAccount) {
                        this.createdAccount.accountName = data.actualAccount;
                    }
                    
                    // 标记为已登录验证
                    this.createdAccount.isLoginVerified = true;
                    
                    // 立即触发 saved 事件，让父组件刷新账号列表
                    this.$emit('saved', { account: this.createdAccount });
                    
                    // 延迟后关闭表单（给用户看到成功消息的时间）
                    setTimeout(() => {
                        this.$emit('cancel');
                    }, 2000);
                } else {
                    this.loginStatus = 'failed';
                    this.loginMessage = '登录验证失败: ' + (data.message || '未知错误');
                }
            } catch (error) {
                this.error = error.response?.data?.error || error.message;
                this.loginStatus = 'failed';
                this.loginMessage = '验证登录失败: ' + (error.response?.data?.error || error.message);
            } finally {
                this.verifying = false;
            }
        },
        cancel() {
            this.$emit('cancel');
        }
    },
    template: `
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
    `,
    updated() {
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }
};

window.AccountForm = AccountForm;
export default AccountForm;
