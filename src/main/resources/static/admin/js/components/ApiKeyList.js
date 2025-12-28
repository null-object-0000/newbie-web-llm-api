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
            error: null,
            searchQuery: '' // 搜索查询
        };
    },
    mounted() {
        this.loadApiKeys();
        // 初始化图标
        if (window.lucide) {
            window.lucide.createIcons();
        }
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
        },
        getStatusText(apiKey) {
            return apiKey.enabled ? '启用' : '禁用';
        },
        getStatusClass(apiKey) {
            return apiKey.enabled 
                ? 'text-green-600 dark:text-green-400' 
                : 'text-gray-500 dark:text-gray-400';
        }
    },
    computed: {
        hasApiKeys() {
            return this.filteredApiKeys.length > 0;
        },
        filteredApiKeys() {
            if (!this.searchQuery) {
                return this.apiKeys;
            }
            const query = this.searchQuery.toLowerCase();
            return this.apiKeys.filter(apiKey => 
                (apiKey.name && apiKey.name.toLowerCase().includes(query)) ||
                (apiKey.apiKey && apiKey.apiKey.toLowerCase().includes(query)) ||
                this.getProviders(apiKey).some(provider => provider.toLowerCase().includes(query))
            );
        }
    },
    template: `
        <div class="h-full flex flex-col p-5 gap-4 max-w-7xl mx-auto w-full">
            <!-- 顶部工具栏：搜索、过滤和操作按钮 -->
            <div class="flex-none flex items-center gap-4">
                <!-- 搜索框 -->
                <div class="flex-1 max-w-md relative">
                    <i data-lucide="search" class="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400"></i>
                    <input
                        type="text"
                        placeholder="搜索 API 密钥..."
                        class="w-full pl-9 pr-4 py-2 bg-white dark:bg-base-100 text-sm text-gray-900 dark:text-base-content border border-gray-200 dark:border-base-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent placeholder:text-gray-400 dark:placeholder:text-gray-500"
                        v-model="searchQuery"
                    />
                </div>

                <div class="flex-1"></div>

                <!-- 操作按钮组 -->
                <div class="flex items-center gap-2">
                    <button class="btn btn-primary" @click="$emit('edit', null)">
                        <i data-lucide="plus" class="w-3.5 h-3.5"></i>
                        创建 API 密钥
                    </button>
                </div>
            </div>

            <!-- API 密钥列表内容区域 -->
            <div class="flex-1 min-h-0 overflow-y-auto">
                <div v-if="loading" class="loading">加载中...</div>
                <div v-else-if="error" class="error">错误: {{ error }}</div>
                <div v-else-if="!hasApiKeys" class="empty-state">暂无 API 密钥</div>
                <div v-else class="bg-white dark:bg-base-100 rounded-2xl shadow-sm border border-gray-100 dark:border-base-200 overflow-hidden">
                    <div class="table-container">
                        <table class="account-table">
                            <thead>
                                <tr class="table-header-row">
                                    <th class="table-header">密钥名称</th>
                                    <th class="table-header">API 密钥</th>
                                    <th class="table-header">关联的提供器</th>
                                    <th class="table-header">状态</th>
                                    <th class="table-header">创建时间</th>
                                    <th class="table-header">最后使用</th>
                                    <th class="table-header">操作</th>
                                </tr>
                            </thead>
                            <tbody class="table-body">
                                <tr v-for="apiKey in filteredApiKeys" :key="apiKey.apiKey" class="table-row">
                                    <td class="table-cell">
                                        <span class="font-medium text-sm">{{ apiKey.name || '-' }}</span>
                                    </td>
                                    <td class="table-cell">
                                        <code class="text-xs bg-gray-100 dark:bg-base-200 px-2 py-1 rounded font-mono">{{ maskApiKey(apiKey.apiKey) }}</code>
                                    </td>
                                    <td class="table-cell">
                                        <div class="flex flex-wrap gap-1">
                                            <span 
                                                v-for="provider in getProviders(apiKey)" 
                                                :key="provider"
                                                class="badge badge-info text-xs"
                                            >
                                                {{ provider }}
                                            </span>
                                            <span v-if="getProviders(apiKey).length === 0" class="text-xs text-gray-400 dark:text-gray-500">无</span>
                                        </div>
                                    </td>
                                    <td class="table-cell">
                                        <span :class="getStatusClass(apiKey)" class="text-sm font-medium">
                                            {{ getStatusText(apiKey) }}
                                        </span>
                                    </td>
                                    <td class="table-cell">{{ formatTime(apiKey.createdAt) }}</td>
                                    <td class="table-cell">{{ formatTime(apiKey.lastUsedAt) }}</td>
                                    <td class="table-cell">
                                        <div class="action-buttons-group">
                                            <button 
                                                class="action-btn action-btn-switch" 
                                                @click="toggleApiKey(apiKey.apiKey, !apiKey.enabled)"
                                                :title="apiKey.enabled ? '禁用' : '启用'"
                                            >
                                                <i :data-lucide="apiKey.enabled ? 'eye-off' : 'eye'" class="w-3.5 h-3.5"></i>
                                            </button>
                                            <button 
                                                class="action-btn action-btn-delete" 
                                                @click="deleteApiKey(apiKey.apiKey)"
                                                title="删除"
                                            >
                                                <i data-lucide="trash-2" class="w-3.5 h-3.5"></i>
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
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

window.ApiKeyList = ApiKeyList;
export default ApiKeyList;

