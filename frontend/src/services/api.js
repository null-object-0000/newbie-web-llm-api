// API 服务
const API_BASE = window.location.origin;

export const apiService = {
  // 统计信息
  async getStats() {
    const response = await axios.get(`${API_BASE}/admin/api/stats`);
    return response.data;
  },
  
  // 账号管理
  async getAccounts(provider = null) {
    const url = provider 
      ? `${API_BASE}/admin/api/accounts?provider=${provider}`
      : `${API_BASE}/admin/api/accounts`;
    const response = await axios.get(url);
    return response.data;
  },
  
  async getAccount(accountId) {
    const response = await axios.get(`${API_BASE}/admin/api/accounts/${accountId}`);
    return response.data;
  },
  
  async createAccount(account) {
    const response = await axios.post(`${API_BASE}/admin/api/accounts`, account);
    return response.data;
  },
  
  async deleteAccount(provider, accountId) {
    const response = await axios.delete(`${API_BASE}/admin/api/accounts/${provider}/${accountId}`);
    return response.data;
  },
  
  // API 密钥管理
  async getApiKeys(accountId = null) {
    const url = accountId
      ? `${API_BASE}/admin/api/api-keys?accountId=${accountId}`
      : `${API_BASE}/admin/api/api-keys`;
    const response = await axios.get(url);
    return response.data;
  },
  
  async createApiKey(apiKey) {
    const response = await axios.post(`${API_BASE}/admin/api/api-keys`, apiKey);
    return response.data;
  },
  
  async updateApiKey(apiKey, updates) {
    const response = await axios.put(`${API_BASE}/admin/api/api-keys/${apiKey}`, updates);
    return response.data;
  },
  
  async updateApiKeyAccounts(apiKey, providerAccounts) {
    const response = await axios.put(`${API_BASE}/admin/api/api-keys/${apiKey}/accounts`, { providerAccounts });
    return response.data;
  },
  
  async deleteApiKey(apiKey) {
    const response = await axios.delete(`${API_BASE}/admin/api/api-keys/${apiKey}`);
    return response.data;
  },
  
  // 登录相关 API
  async startLogin(providerName, accountId) {
    const response = await axios.post(`${API_BASE}/admin/api/accounts/${providerName}/${accountId}/login/start`);
    return response.data;
  },
  
  async verifyLogin(sessionId) {
    const response = await axios.post(`${API_BASE}/admin/api/accounts/login/verify`, { sessionId });
    return response.data;
  },
  
  async getLoginSession(sessionId) {
    const response = await axios.get(`${API_BASE}/admin/api/accounts/login/sessions/${sessionId}`);
    return response.data;
  },
  
  // 检查提供器是否为 Playwright 类型
  isPlaywrightProvider(providerName) {
    const playwrightProviders = ['gemini', 'openai', 'deepseek'];
    return playwrightProviders.includes(providerName?.toLowerCase());
  }
};

// 注册为全局服务（兼容旧代码）
window.apiService = apiService;

