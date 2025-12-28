// 使用全局 apiService
const apiService = window.apiService || {
    getStats: async () => {
        const response = await axios.get(`${window.location.origin}/admin/stats`);
        return response.data;
    }
};

const Dashboard = {
    name: 'Dashboard',
    data() {
        return {
            stats: {
                totalAccounts: 0,
                totalApiKeys: 0,
                enabledApiKeys: 0,
                totalProviders: 0,
                accountsByProvider: {},
                providers: []
            },
            loading: false
        };
    },
    mounted() {
        this.loadStats();
    },
    methods: {
        async loadStats() {
            this.loading = true;
            try {
                this.stats = await apiService.getStats();
            } catch (error) {
                console.error('加载统计信息失败:', error);
                alert('加载统计信息失败: ' + (error.response?.data?.error || error.message));
            } finally {
                this.loading = false;
            }
        }
    },
    template: `
        <div class="h-full w-full overflow-y-auto">
            <div class="p-5 space-y-4 max-w-7xl mx-auto">
                <!-- 问候语 -->
                <div class="flex justify-between items-center">
                    <div>
                        <h1 class="text-2xl font-bold text-gray-900 dark:text-base-content">
                            欢迎使用账号管理后台
                        </h1>
                    </div>
                </div>

                <!-- 统计卡片 - 5 columns on medium screens and up -->
                <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
                    <div class="bg-white dark:bg-base-100 rounded-xl p-4 shadow-sm border border-gray-100 dark:border-base-200">
                        <div class="flex items-center justify-between mb-2">
                            <div class="p-1.5 bg-blue-50 dark:bg-blue-900/20 rounded-md">
                                <i data-lucide="users" class="w-4 h-4 text-blue-500 dark:text-blue-400"></i>
                            </div>
                        </div>
                        <div class="text-2xl font-bold text-gray-900 dark:text-base-content mb-0.5">{{ stats.totalAccounts || 0 }}</div>
                        <div class="text-xs text-gray-500 dark:text-gray-400">总账号数</div>
                    </div>

                    <div class="bg-white dark:bg-base-100 rounded-xl p-4 shadow-sm border border-gray-100 dark:border-base-200">
                        <div class="flex items-center justify-between mb-2">
                            <div class="p-1.5 bg-green-50 dark:bg-green-900/20 rounded-md">
                                <i data-lucide="key" class="w-4 h-4 text-green-500 dark:text-green-400"></i>
                            </div>
                        </div>
                        <div class="text-2xl font-bold text-gray-900 dark:text-base-content mb-0.5">{{ stats.totalApiKeys || 0 }}</div>
                        <div class="text-xs text-gray-500 dark:text-gray-400">总 API 密钥数</div>
                    </div>

                    <div class="bg-white dark:bg-base-100 rounded-xl p-4 shadow-sm border border-gray-100 dark:border-base-200">
                        <div class="flex items-center justify-between mb-2">
                            <div class="p-1.5 bg-purple-50 dark:bg-purple-900/20 rounded-md">
                                <i data-lucide="check-circle" class="w-4 h-4 text-purple-500 dark:text-purple-400"></i>
                            </div>
                        </div>
                        <div class="text-2xl font-bold text-gray-900 dark:text-base-content mb-0.5">{{ stats.enabledApiKeys || 0 }}</div>
                        <div class="text-xs text-gray-500 dark:text-gray-400">启用的 API 密钥</div>
                    </div>

                    <div class="bg-white dark:bg-base-100 rounded-xl p-4 shadow-sm border border-gray-100 dark:border-base-200">
                        <div class="flex items-center justify-between mb-2">
                            <div class="p-1.5 bg-cyan-50 dark:bg-cyan-900/20 rounded-md">
                                <i data-lucide="server" class="w-4 h-4 text-cyan-500 dark:text-cyan-400"></i>
                            </div>
                        </div>
                        <div class="text-2xl font-bold text-gray-900 dark:text-base-content mb-0.5">{{ stats.totalProviders || 0 }}</div>
                        <div class="text-xs text-gray-500 dark:text-gray-400">提供器数量</div>
                    </div>

                    <div class="bg-white dark:bg-base-100 rounded-xl p-4 shadow-sm border border-gray-100 dark:border-base-200">
                        <div class="flex items-center justify-between mb-2">
                            <div class="p-1.5 bg-orange-50 dark:bg-orange-900/20 rounded-md">
                                <i data-lucide="info" class="w-4 h-4 text-orange-500 dark:text-orange-400"></i>
                            </div>
                        </div>
                        <div class="text-2xl font-bold text-gray-900 dark:text-base-content mb-0.5">{{ Object.keys(stats.accountsByProvider || {}).length }}</div>
                        <div class="text-xs text-gray-500 dark:text-gray-400">提供器类型</div>
                    </div>
                </div>

                <!-- 各提供器账号统计 -->
                <div v-if="stats.accountsByProvider && Object.keys(stats.accountsByProvider).length > 0" class="bg-white dark:bg-base-100 rounded-xl p-4 shadow-sm border border-gray-100 dark:border-base-200">
                    <h2 class="text-base font-semibold text-gray-900 dark:text-base-content mb-3">各提供器账号统计</h2>
                    <div class="flex flex-wrap gap-3">
                        <div 
                            v-for="(count, provider) in stats.accountsByProvider"
                            :key="provider"
                            class="flex items-center gap-2 px-3 py-1.5 bg-gray-50 dark:bg-base-200 rounded-lg"
                        >
                            <span class="badge badge-info">{{ provider }}</span>
                            <span class="text-sm text-gray-600 dark:text-gray-400">{{ count }} 个账号</span>
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

window.Dashboard = Dashboard;
export default Dashboard;
