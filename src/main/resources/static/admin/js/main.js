// 主应用入口
// 等待所有组件加载完成后再初始化应用
window.addEventListener('DOMContentLoaded', () => {
    // 延迟一下确保所有模块都加载完成
    setTimeout(() => {
        const { createApp } = Vue;
        
        const App = {
            components: {
                Header: window.Header,
                Tabs: window.Tabs,
                Modal: window.Modal,
                Dashboard: window.Dashboard,
                AccountList: window.AccountList,
                AccountForm: window.AccountForm,
                ApiKeyList: window.ApiKeyList,
                ApiKeyForm: window.ApiKeyForm
            },
            data() {
                return {
                    activeTab: 'dashboard',
                    showAccountModal: false,
                    showApiKeyModal: false,
                    editingAccount: null,
                    editingApiKey: null
                };
            },
            computed: {
                needsLoginForAccount() {
                    return (account) => {
                        if (!account) return false;
                        const playwrightProviders = ['gemini', 'openai', 'deepseek'];
                        return playwrightProviders.includes(account.providerName?.toLowerCase()) && !account.isLoginVerified;
                    };
                }
            },
            methods: {
                switchTab(tab) {
                    this.activeTab = tab;
                },
                openAccountModal(account = null) {
                    this.editingAccount = account;
                    this.showAccountModal = true;
                },
                openAccountLoginModal(account) {
                    // 打开账号登录弹窗（用于已有账号的登录）
                    this.editingAccount = account;
                    this.showAccountModal = true;
                },
                closeAccountModal() {
                    this.showAccountModal = false;
                    this.editingAccount = null;
                },
                openApiKeyModal(apiKey = null) {
                    this.editingApiKey = apiKey;
                    this.showApiKeyModal = true;
                },
                closeApiKeyModal() {
                    this.showApiKeyModal = false;
                    this.editingApiKey = null;
                },
                handleAccountSaved(data) {
                    // 立即刷新账号列表和统计信息
                    if (this.$refs.accountList) {
                        this.$refs.accountList.loadAccounts();
                    }
                    if (this.$refs.dashboard) {
                        this.$refs.dashboard.loadStats();
                    }
                    // 如果不是登录验证成功的情况，立即关闭模态框
                    // 登录验证成功的情况，AccountForm 会自己延迟关闭（显示成功消息）
                    const isLoginSuccess = data && data.account && data.account.isLoginVerified === true;
                    if (!isLoginSuccess) {
                        this.closeAccountModal();
                    }
                },
                handleApiKeySaved() {
                    this.closeApiKeyModal();
                    if (this.$refs.apiKeyList) {
                        this.$refs.apiKeyList.loadApiKeys();
                    }
                    if (this.$refs.dashboard) {
                        this.$refs.dashboard.loadStats();
                    }
                }
            },
            template: `
                <div class="app-container">
                    <Header />
                    <Tabs :active-tab="activeTab" @switch-tab="switchTab" />
                    
                    <div class="tab-content">
                        <Dashboard 
                            v-show="activeTab === 'dashboard'" 
                            ref="dashboard"
                        />
                        <AccountList 
                            v-show="activeTab === 'accounts'" 
                            ref="accountList"
                            @edit="openAccountLoginModal"
                        />
                        <ApiKeyList 
                            v-show="activeTab === 'api-keys'" 
                            ref="apiKeyList"
                            @edit="openApiKeyModal"
                        />
                    </div>
                    
                    <Modal 
                        v-if="showAccountModal" 
                        :title="editingAccount && editingAccount.accountId ? (needsLoginForAccount(editingAccount) ? '账号登录' : '编辑账号') : '创建账号'"
                        @close="closeAccountModal"
                    >
                        <AccountForm 
                            :account="editingAccount"
                            @saved="handleAccountSaved"
                            @cancel="closeAccountModal"
                        />
                    </Modal>
                    
                    <Modal 
                        v-if="showApiKeyModal" 
                        :title="editingApiKey ? '编辑 API 密钥' : '创建 API 密钥'"
                        @close="closeApiKeyModal"
                    >
                        <ApiKeyForm 
                            :api-key="editingApiKey"
                            @saved="handleApiKeySaved"
                            @cancel="closeApiKeyModal"
                        />
                    </Modal>
                </div>
            `
        };
        
        const app = createApp(App);
        app.mount('#app');
        
        // 初始化 Lucide 图标
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }, 100);
});
