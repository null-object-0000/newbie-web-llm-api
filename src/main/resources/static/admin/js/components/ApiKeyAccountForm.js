// 使用全局 apiService（优先使用全局的 apiService）
const apiService = window.apiService || {
    getAccounts: async () => {
        const response = await axios.get(`${window.location.origin}/admin/accounts`);
        return response.data;
    },
    updateApiKeyAccounts: async (apiKey, providerAccounts) => {
        // 后端期望 UpdateApiKeyAccountsRequest 对象，包含 providerAccounts 字段
        const response = await axios.put(`${window.location.origin}/admin/api-keys/${apiKey}/accounts`, { providerAccounts });
        return response.data;
    }
};

const ApiKeyAccountForm = {
    name: 'ApiKeyAccountForm',
    props: {
        apiKey: {
            type: Object,
            required: true
        }
    },
    emits: ['saved', 'cancel'],
    data() {
        return {
            accounts: {}, // 原始数据：{ provider: [accounts] }
            selectedAccounts: {}, // providerName -> accountId
            loading: false,
            saving: false,
            error: null
        };
    },
    mounted() {
        this.loadAccounts();
        // 加载已关联的账号
        if (this.apiKey.providerAccounts) {
            this.selectedAccounts = { ...this.apiKey.providerAccounts };
        } else if (this.apiKey.accountId && this.apiKey.providerName) {
            // 兼容旧版本
            this.selectedAccounts = { [this.apiKey.providerName]: this.apiKey.accountId };
        }
    },
    methods: {
        async loadAccounts() {
            this.loading = true;
            this.error = null;
            try {
                const data = await apiService.getAccounts();
                this.accounts = data.accounts || {};
            } catch (error) {
                console.error('加载账号列表失败:', error);
                this.error = error.response?.data?.error || error.message;
            } finally {
                this.loading = false;
            }
        },
        toggleAccount(provider, accountId) {
            if (this.selectedAccounts[provider] === accountId) {
                // 取消选择
                delete this.selectedAccounts[provider];
            } else {
                // 选择账号（每个提供器只能选择一个账号）
                this.selectedAccounts[provider] = accountId;
            }
        },
        isAccountSelected(provider, accountId) {
            return this.selectedAccounts[provider] === accountId;
        },
        async submit() {
            this.saving = true;
            this.error = null;
            
            try {
                // 过滤掉空值
                const providerAccounts = {};
                for (const [provider, accountId] of Object.entries(this.selectedAccounts)) {
                    if (accountId) {
                        providerAccounts[provider] = accountId;
                    }
                }
                
                await apiService.updateApiKeyAccounts(this.apiKey.apiKey, providerAccounts);
                // 使用更友好的提示方式
                if (window.showNotification) {
                    window.showNotification('关联账号已更新', 'success');
                } else {
                    alert('关联账号已更新');
                }
                this.$emit('saved');
            } catch (error) {
                const errorMsg = error.response?.data?.error || error.message || '更新失败';
                this.error = errorMsg;
                console.error('更新关联账号失败:', error);
                // 显示错误提示
                if (window.showNotification) {
                    window.showNotification(errorMsg, 'error');
                }
            } finally {
                this.saving = false;
            }
        },
        cancel() {
            this.$emit('cancel');
        },
        getAccountDisplayName(account) {
            if (account.nickname) {
                return `${account.accountName} (${account.nickname})`;
            }
            return account.accountName;
        }
    },
    computed: {
        availableProviders() {
            return Object.keys(this.accounts).sort();
        },
        hasSelectedAccounts() {
            return Object.keys(this.selectedAccounts).length > 0;
        }
    },
    template: `
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
    `,
    updated() {
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }
};

window.ApiKeyAccountForm = ApiKeyAccountForm;
export default ApiKeyAccountForm;

