// API 服务
const API_BASE = window.location.origin;

const apiService = {
    // 统计信息
    async getStats() {
        const response = await axios.get(`${API_BASE}/admin/stats`);
        return response.data;
    },
    
    // 账号管理
    async getAccounts(provider = null) {
        const url = provider 
            ? `${API_BASE}/admin/accounts?provider=${provider}`
            : `${API_BASE}/admin/accounts`;
        const response = await axios.get(url);
        return response.data;
    },
    
    async getAccount(accountId) {
        const response = await axios.get(`${API_BASE}/admin/accounts/${accountId}`);
        return response.data;
    },
    
    async createAccount(account) {
        const response = await axios.post(`${API_BASE}/admin/accounts`, account);
        return response.data;
    },
    
    async deleteAccount(provider, accountId) {
        const response = await axios.delete(`${API_BASE}/admin/accounts/${provider}/${accountId}`);
        return response.data;
    },
    
    // API 密钥管理
    async getApiKeys(accountId = null) {
        const url = accountId
            ? `${API_BASE}/admin/api-keys?accountId=${accountId}`
            : `${API_BASE}/admin/api-keys`;
        const response = await axios.get(url);
        return response.data;
    },
    
    async createApiKey(apiKey) {
        const response = await axios.post(`${API_BASE}/admin/api-keys`, apiKey);
        return response.data;
    },
    
    async updateApiKey(apiKey, updates) {
        const response = await axios.put(`${API_BASE}/admin/api-keys/${apiKey}`, updates);
        return response.data;
    },
    
    async updateApiKeyAccounts(apiKey, providerAccounts) {
        const response = await axios.put(`${API_BASE}/admin/api-keys/${apiKey}/accounts`, { providerAccounts });
        return response.data;
    },
    
    async deleteApiKey(apiKey) {
        const response = await axios.delete(`${API_BASE}/admin/api-keys/${apiKey}`);
        return response.data;
    },
    
    // 登录相关 API
    async startLogin(providerName, accountId) {
        const response = await axios.post(`${API_BASE}/admin/accounts/${providerName}/${accountId}/login/start`);
        return response.data;
    },
    
    async verifyLogin(sessionId) {
        const response = await axios.post(`${API_BASE}/admin/accounts/login/verify`, { sessionId });
        return response.data;
    },
    
    async getLoginSession(sessionId) {
        const response = await axios.get(`${API_BASE}/admin/accounts/login/sessions/${sessionId}`);
        return response.data;
    },
    
    // 检查提供器是否为 Playwright 类型
    isPlaywrightProvider(providerName) {
        const playwrightProviders = ['gemini', 'openai', 'deepseek'];
        return playwrightProviders.includes(providerName?.toLowerCase());
    }
};

// 注册为全局服务
window.apiService = apiService;
export { apiService };

