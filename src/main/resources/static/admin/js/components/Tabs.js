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
                { id: 'dashboard', label: '概览' },
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
        <div class="tabs">
            <button 
                v-for="tab in tabs"
                :key="tab.id"
                :class="['tab', { active: activeTab === tab.id }]"
                @click="switchTab(tab.id)"
            >
                {{ tab.label }}
            </button>
        </div>
    `
};

window.Tabs = Tabs;
export default Tabs;

