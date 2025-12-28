// 使用全局 apiService
const apiService = window.apiService || {
    getApiKeys: async (accountId) => {
        const url = accountId
            ? `${window.location.origin}/admin/api-keys?accountId=${accountId}`
            : `${window.location.origin}/admin/api-keys`;
        const response = await axios.get(url);
        return response.data;
    },
    updateApiKey: async (apiKey, updates) => {
        const response = await axios.put(`${window.location.origin}/admin/api-keys/${apiKey}`, updates);
        return response.data;
    },
    deleteApiKey: async (apiKey) => {
        const response = await axios.delete(`${window.location.origin}/admin/api-keys/${apiKey}`);
        return response.data;
    }
};

const ApiKeyList = {
    name: 'ApiKeyList',
    emits: ['edit'],
    data() {
        return {
            apiKeys: [],
            loading: false,
            error: null
        };
    },
    mounted() {
        this.loadApiKeys();
    },
    methods: {
        async loadApiKeys() {
            this.loading = true;
            this.error = null;
            try {
                const data = await apiService.getApiKeys();
                this.apiKeys = data.apiKeys || [];
            } catch (error) {
                console.error('加载 API 密钥列表失败:', error);
                this.error = error.response?.data?.error || error.message;
            } finally {
                this.loading = false;
            }
        },
        async toggleApiKey(apiKey, enabled) {
            try {
                await apiService.updateApiKey(apiKey, { enabled });
                this.loadApiKeys();
            } catch (error) {
                alert('操作失败: ' + (error.response?.data?.error || error.message));
            }
        },
        async deleteApiKey(apiKey) {
            if (!confirm('确定要删除此 API 密钥吗？')) {
                return;
            }
            
            try {
                await apiService.deleteApiKey(apiKey);
                alert('API 密钥已删除');
                this.loadApiKeys();
            } catch (error) {
                alert('删除失败: ' + (error.response?.data?.error || error.message));
            }
        },
        formatTime(timestamp) {
            if (!timestamp || timestamp === 0) {
                return '从未使用';
            }
            const date = new Date(timestamp);
            return date.toLocaleString('zh-CN');
        },
        maskApiKey(apiKey) {
            if (!apiKey) return '';
            return apiKey.substring(0, 20) + '...';
        },
        getProviders(apiKey) {
            if (apiKey.providerAccounts && Object.keys(apiKey.providerAccounts).length > 0) {
                return Object.keys(apiKey.providerAccounts);
            }
            // 兼容旧版本
            if (apiKey.providerName) {
                return [apiKey.providerName];
            }
            return [];
        }
    },
    template: `
        <div class="api-key-list">
            <div class="card">
                <div class="card-header">
                    <h2>API 密钥列表</h2>
                    <button class="btn btn-primary" @click="$emit('edit', null)">
                        <i data-lucide="key" class="w-4 h-4"></i>
                        创建 API 密钥
                    </button>
                </div>
                
                <div v-if="loading" class="loading">加载中...</div>
                <div v-else-if="error" class="error">错误: {{ error }}</div>
                <div v-else-if="apiKeys.length === 0" class="empty-state">暂无 API 密钥</div>
                <div v-else class="table-container">
                    <table>
                        <thead>
                            <tr>
                                <th>密钥名称</th>
                                <th>API 密钥</th>
                                <th>关联的提供器</th>
                                <th>状态</th>
                                <th>创建时间</th>
                                <th>最后使用</th>
                                <th>操作</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr v-for="apiKey in apiKeys" :key="apiKey.apiKey">
                                <td>{{ apiKey.name || '-' }}</td>
                                <td><code>{{ maskApiKey(apiKey.apiKey) }}</code></td>
                                <td>
                                    <div class="flex flex-wrap gap-1">
                                        <span 
                                            v-for="provider in getProviders(apiKey)" 
                                            :key="provider"
                                            class="badge badge-info"
                                        >
                                            {{ provider }}
                                        </span>
                                        <span v-if="getProviders(apiKey).length === 0" class="text-gray-400 text-sm">无</span>
                                    </div>
                                </td>
                                <td>
                                    <span :class="['badge', apiKey.enabled ? 'badge-success' : 'badge-danger']">
                                        {{ apiKey.enabled ? '启用' : '禁用' }}
                                    </span>
                                </td>
                                <td>{{ formatTime(apiKey.createdAt) }}</td>
                                <td>{{ formatTime(apiKey.lastUsedAt) }}</td>
                                <td>
                                    <button 
                                        class="btn btn-secondary" 
                                        @click="toggleApiKey(apiKey.apiKey, !apiKey.enabled)"
                                    >
                                        <i :data-lucide="apiKey.enabled ? 'eye-off' : 'eye'" class="w-4 h-4"></i>
                                        {{ apiKey.enabled ? '禁用' : '启用' }}
                                    </button>
                                    <button 
                                        class="btn btn-danger" 
                                        @click="deleteApiKey(apiKey.apiKey)"
                                    >
                                        <i data-lucide="trash-2" class="w-4 h-4"></i>
                                        删除
                                    </button>
                                </td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    `
};

window.ApiKeyList = ApiKeyList;
export default ApiKeyList;

// 初始化图标
if (window.lucide) {
    window.lucide.createIcons();
}

