const Tabs = {
    name: 'Tabs',
    props: {
        activeTab: {
            type: String,
            required: true
        }
    },
    emits: ['switch-tab'],
    data() {
        return {
            tabs: [
                { id: 'dashboard', label: '仪表盘' },
                { id: 'accounts', label: '账号管理' },
                { id: 'api-keys', label: 'API 密钥' }
            ]
        };
    },
    methods: {
        switchTab(tabId) {
            this.$emit('switch-tab', tabId);
        }
    },
    template: `
        <nav class="tabs-nav">
            <div class="max-w-7xl mx-auto px-8 relative" style="z-index: 10;">
                <div class="flex items-center justify-between h-16">
                    <!-- 药丸形状的导航标签 - 居中 -->
                    <div class="flex items-center gap-1 bg-gray-100 dark:bg-base-200 rounded-full p-1 mx-auto">
                        <button 
                            v-for="tab in tabs"
                            :key="tab.id"
                            :class="[
                                'px-6 py-2 rounded-full text-sm font-medium transition-all',
                                activeTab === tab.id
                                    ? 'bg-gray-900 text-white shadow-sm dark:bg-white dark:text-gray-900'
                                    : 'text-gray-700 hover:text-gray-900 hover:bg-gray-200 dark:text-gray-400 dark:hover:text-base-content dark:hover:bg-base-100'
                            ]"
                            @click="switchTab(tab.id)"
                        >
                            {{ tab.label }}
                        </button>
                    </div>
                </div>
            </div>
        </nav>
    `
};

window.Tabs = Tabs;
export default Tabs;

