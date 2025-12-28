// 使用全局 apiService
const apiService = window.apiService || {
    getAccounts: async () => {
        const response = await axios.get(`${window.location.origin}/admin/accounts`);
        return response.data;
    },
    createApiKey: async (apiKey) => {
        const response = await axios.post(`${window.location.origin}/admin/api-keys`, apiKey);
        return response.data;
    }
};

const ApiKeyForm = {
    name: 'ApiKeyForm',
    props: {
        apiKey: {
            type: Object,
            default: null
        }
    },
    emits: ['saved', 'cancel'],
    data() {
        return {
            accounts: {},
            selectedAccounts: {}, // providerName -> accountId
            form: {
                name: '',
                description: ''
            },
            loading: false,
            error: null,
            createdApiKey: null
        };
    },
    mounted() {
        this.loadAccounts();
        if (this.apiKey) {
            this.form = {
                name: this.apiKey.name || '',
                description: this.apiKey.description || ''
            };
            // 如果编辑已有 API key，加载已关联的账号
            if (this.apiKey.providerAccounts) {
                this.selectedAccounts = { ...this.apiKey.providerAccounts };
            } else if (this.apiKey.accountId && this.apiKey.providerName) {
                // 兼容旧版本
                this.selectedAccounts = { [this.apiKey.providerName]: this.apiKey.accountId };
            }
        }
    },
    methods: {
        async loadAccounts() {
            try {
                const data = await apiService.getAccounts();
                this.accounts = data.accounts || {};
            } catch (error) {
                console.error('加载账号列表失败:', error);
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
            if (Object.keys(this.selectedAccounts).length === 0) {
                this.error = '请至少选择一个提供器的账号';
                return;
            }
            
            this.loading = true;
            this.error = null;
            
            try {
                const requestData = {
                    providerAccounts: this.selectedAccounts,
                    name: this.form.name,
                    description: this.form.description
                };
                
                const data = await apiService.createApiKey(requestData);
                this.createdApiKey = data.apiKey;
                alert('API 密钥创建成功！\n\nAPI 密钥: ' + data.apiKey + '\n\n请妥善保存，此密钥仅显示一次！');
                this.$emit('saved', data);
            } catch (error) {
                this.error = error.response?.data?.error || error.message;
            } finally {
                this.loading = false;
            }
        },
        cancel() {
            this.$emit('cancel');
        }
    },
    template: `
        <form @submit.prevent="submit">
            <div v-if="error" class="error-message">{{ error }}</div>
            
            <div class="form-group">
                <label>关联账号 *</label>
                <p class="text-sm text-gray-500 dark:text-gray-400 mb-2">
                    为每个提供器选择一个账号。如果某个提供器不选择账号，该 API key 将不支持该提供器的所有模型。
                </p>
                <div class="provider-accounts-selection">
                    <div v-for="(accountList, provider) in accounts" :key="provider" class="provider-account-group">
                        <div class="provider-account-header">
                            <span class="badge badge-info">{{ provider }}</span>
                            <span class="text-sm text-gray-500 dark:text-gray-400">
                                (已选择: {{ selectedAccounts[provider] ? '是' : '否' }})
                            </span>
                        </div>
                        <div class="account-options">
                            <label 
                                v-for="account in accountList" 
                                :key="account.accountId"
                                class="account-option"
                                :class="{ selected: isAccountSelected(provider, account.accountId) }"
                            >
                                <input 
                                    type="radio"
                                    :name="'provider-' + provider"
                                    :value="account.accountId"
                                    :checked="isAccountSelected(provider, account.accountId)"
                                    @change="toggleAccount(provider, account.accountId)"
                                />
                                    <div class="account-info">
                                        <div class="account-name">{{ account.accountName }}</div>
                                    </div>
                            </label>
                            <label 
                                class="account-option"
                                :class="{ selected: !selectedAccounts[provider] }"
                            >
                                <input 
                                    type="radio"
                                    :name="'provider-' + provider"
                                    value=""
                                    :checked="!selectedAccounts[provider]"
                                    @change="delete selectedAccounts[provider]"
                                />
                                <div class="account-info">
                                    <div class="account-name text-gray-400">不选择（不支持此提供器）</div>
                                </div>
                            </label>
                        </div>
                    </div>
                </div>
            </div>
            
            <div class="form-group">
                <label>密钥名称</label>
                <input 
                    type="text" 
                    v-model="form.name" 
                    placeholder="例如: 生产环境密钥"
                />
            </div>
            
            <div class="form-group">
                <label>描述</label>
                <textarea 
                    v-model="form.description" 
                    rows="3" 
                    placeholder="密钥用途描述"
                ></textarea>
            </div>
            
            <div v-if="createdApiKey" class="api-key-display">
                <strong>API 密钥:</strong>
                <code>{{ createdApiKey }}</code>
            </div>
            
            <div class="form-actions">
                <button type="button" class="btn btn-secondary" @click="cancel" :disabled="loading">
                    取消
                </button>
                <button type="submit" class="btn btn-primary" :disabled="loading">
                    {{ loading ? '创建中...' : '创建' }}
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

window.ApiKeyForm = ApiKeyForm;
export default ApiKeyForm;
