// 使用全局 apiService
const apiService = window.apiService || {
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
        if (this.apiKey) {
            this.form = {
                name: this.apiKey.name || '',
                description: this.apiKey.description || ''
            };
        }
    },
    methods: {
        async submit() {
            this.loading = true;
            this.error = null;
            
            try {
                const requestData = {
                    name: this.form.name,
                    description: this.form.description
                };
                
                const data = await apiService.createApiKey(requestData);
                this.createdApiKey = data.apiKey;
                alert('API 密钥创建成功！\n\nAPI 密钥: ' + data.apiKey + '\n\n请妥善保存，此密钥仅显示一次！\n\n提示：您可以在 API 密钥列表中关联账号。');
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
            
            <div class="form-group">
                <div class="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-3 text-sm text-blue-800 dark:text-blue-200">
                    <i data-lucide="info" class="w-4 h-4 inline mr-1"></i>
                    <strong>提示：</strong>创建后可以在 API 密钥列表中关联账号。
                </div>
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
