// 使用全局 apiService
const apiService = window.apiService || {
    getAccounts: async (provider) => {
        const url = provider 
            ? `${window.location.origin}/admin/accounts?provider=${provider}`
            : `${window.location.origin}/admin/accounts`;
        const response = await axios.get(url);
        return response.data;
    },
    deleteAccount: async (provider, accountId) => {
        const response = await axios.delete(`${window.location.origin}/admin/accounts/${provider}/${accountId}`);
        return response.data;
    },
    startLogin: async (providerName, accountId) => {
        const response = await axios.post(`${window.location.origin}/admin/accounts/${providerName}/${accountId}/login/start`);
        return response.data;
    },
    verifyLogin: async (sessionId) => {
        const response = await axios.post(`${window.location.origin}/admin/accounts/login/verify`, { sessionId });
        return response.data;
    },
    isPlaywrightProvider: (providerName) => {
        const playwrightProviders = ['gemini', 'openai', 'deepseek'];
        return playwrightProviders.includes(providerName?.toLowerCase());
    }
};

const AccountList = {
    name: 'AccountList',
    emits: ['edit'],
    data() {
        return {
            accounts: {},
            loading: false,
            error: null,
            expandedProviders: new Set(), // 展开的提供器
            searchQuery: '' // 搜索查询
        };
    },
    mounted() {
        this.loadAccounts();
        // 初始化图标
        if (window.lucide) {
            window.lucide.createIcons();
        }
    },
    methods: {
        async loadAccounts() {
            this.loading = true;
            this.error = null;
            try {
                const data = await apiService.getAccounts();
                this.accounts = data.accounts || {};
                // 默认展开所有提供器
                Object.keys(this.accounts).forEach(provider => {
                    this.expandedProviders.add(provider);
                });
            } catch (error) {
                console.error('加载账号列表失败:', error);
                this.error = error.response?.data?.error || error.message;
            } finally {
                this.loading = false;
            }
        },
        async deleteAccount(provider, accountId) {
            const account = this.accounts[provider]?.find(acc => acc.accountId === accountId);
            const accountName = account?.accountName || '此账号';
            if (!confirm(`确定要删除账号 "${accountName}" 吗？\n\n此操作将同时删除该账号的所有 API 密钥！`)) {
                return;
            }
            
            try {
                await apiService.deleteAccount(provider, accountId);
                alert('账号已删除');
                this.loadAccounts();
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
        toggleProvider(provider) {
            if (this.expandedProviders.has(provider)) {
                this.expandedProviders.delete(provider);
            } else {
                this.expandedProviders.add(provider);
            }
        },
        needsLogin(provider, account) {
            // 只有 Playwright 类提供器且未完成登录验证的账号需要登录
            // 兼容两种字段名：isLoginVerified 和 loginVerified
            const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
            return apiService.isPlaywrightProvider(provider) && !isVerified;
        },
        getAccountStatus(provider, account) {
            // 获取账号状态文本
            if (!apiService.isPlaywrightProvider(provider)) {
                return '无需登录';
            }
            // 兼容两种字段名：isLoginVerified 和 loginVerified
            const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
            return isVerified ? '已完成登录' : '未完成登录';
        },
        getAccountStatusClass(provider, account) {
            // 获取账号状态的样式类
            if (!apiService.isPlaywrightProvider(provider)) {
                return 'text-gray-500 dark:text-gray-400';
            }
            // 兼容两种字段名：isLoginVerified 和 loginVerified
            const isVerified = account.isLoginVerified !== undefined ? account.isLoginVerified : account.loginVerified;
            return isVerified ? 'text-green-600 dark:text-green-400' : 'text-yellow-600 dark:text-yellow-400';
        }
    },
    computed: {
        hasAccounts() {
            return Object.keys(this.accounts).length > 0;
        },
        totalAccounts() {
            return Object.values(this.accounts).reduce((sum, list) => sum + list.length, 0);
        },
        filteredAccounts() {
            if (!this.searchQuery) {
                return this.accounts;
            }
            const query = this.searchQuery.toLowerCase();
            const filtered = {};
            Object.keys(this.accounts).forEach(provider => {
                const filteredList = this.accounts[provider].filter(account => 
                    account.accountName?.toLowerCase().includes(query) ||
                    account.nickname?.toLowerCase().includes(query)
                );
                if (filteredList.length > 0) {
                    filtered[provider] = filteredList;
                }
            });
            return filtered;
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
                        placeholder="搜索账号..."
                        class="w-full pl-9 pr-4 py-2 bg-white dark:bg-base-100 text-sm text-gray-900 dark:text-base-content border border-gray-200 dark:border-base-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent placeholder:text-gray-400 dark:placeholder:text-gray-500"
                        v-model="searchQuery"
                    />
                </div>

                <div class="flex-1"></div>

                <!-- 操作按钮组 -->
                <div class="flex items-center gap-2">
                    <button class="btn btn-primary" @click="$emit('edit', null)">
                        <i data-lucide="plus" class="w-3.5 h-3.5"></i>
                        添加账号
                    </button>
                </div>
            </div>

            <!-- 账号列表内容区域 -->
            <div class="flex-1 min-h-0 overflow-y-auto">
                <div v-if="loading" class="loading">加载中...</div>
                <div v-else-if="error" class="error">错误: {{ error }}</div>
                <div v-else-if="!hasAccounts" class="empty-state">暂无账号</div>
                <div v-else class="bg-white dark:bg-base-100 rounded-2xl shadow-sm border border-gray-100 dark:border-base-200 overflow-hidden">
                    <!-- 按提供器分组显示 -->
                    <div v-for="(accountList, provider) in filteredAccounts" :key="provider" class="provider-group">
                        <div class="provider-header" @click="toggleProvider(provider)">
                            <div class="flex items-center gap-2">
                                <i :data-lucide="expandedProviders.has(provider) ? 'chevron-down' : 'chevron-right'" class="w-4 h-4"></i>
                                <span class="badge badge-info">{{ provider }}</span>
                                <span class="text-sm text-gray-500 dark:text-gray-400">({{ accountList.length }} 个账号)</span>
                            </div>
                        </div>
                        <div v-if="expandedProviders.has(provider)" class="provider-content">
                            <div class="table-container">
                                <table class="account-table">
                                    <thead>
                                        <tr class="table-header-row">
                                            <th class="table-header">账号名称</th>
                                            <th class="table-header">状态</th>
                                            <th class="table-header">创建时间</th>
                                            <th class="table-header">最后使用</th>
                                            <th class="table-header">操作</th>
                                        </tr>
                                    </thead>
                                    <tbody class="table-body">
                                        <tr v-for="account in accountList" :key="account.accountId" class="table-row">
                                            <td class="table-cell">
                                                <div class="flex flex-col gap-1">
                                                    <span class="font-medium text-sm">{{ account.accountName }}</span>
                                                    <span v-if="account.nickname" class="text-xs text-gray-500 dark:text-gray-400">
                                                        <i data-lucide="user" class="w-3 h-3 inline"></i>
                                                        {{ account.nickname }}
                                                    </span>
                                                </div>
                                            </td>
                                            <td class="table-cell">
                                                <span :class="getAccountStatusClass(provider, account)" class="text-sm font-medium">
                                                    {{ getAccountStatus(provider, account) }}
                                                </span>
                                            </td>
                                            <td class="table-cell">{{ formatTime(account.createdAt) }}</td>
                                            <td class="table-cell">{{ formatTime(account.lastUsedAt) }}</td>
                                            <td class="table-cell">
                                                <div class="action-buttons-group">
                                                    <!-- 登录流程按钮 -->
                                                    <template v-if="needsLogin(provider, account)">
                                                        <button 
                                                            class="action-btn action-btn-primary" 
                                                            @click="$emit('edit', account)"
                                                            title="开始登录"
                                                        >
                                                            <i data-lucide="log-in" class="w-3.5 h-3.5"></i>
                                                        </button>
                                                    </template>
                                                    <button 
                                                        class="action-btn action-btn-delete" 
                                                        @click="deleteAccount(provider, account.accountId)"
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
            </div>
        </div>
    `,
    updated() {
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }
};

window.AccountList = AccountList;
export default AccountList;
