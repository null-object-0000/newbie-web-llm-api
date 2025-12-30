import {createRouter, createWebHistory} from 'vue-router';
import Dashboard from '../views/Dashboard.vue';
import AccountList from '../views/AccountList.vue';
import ApiKeyList from '../views/ApiKeyList.vue';

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: Dashboard,
    meta: { title: '仪表盘' }
  },
  {
    path: '/accounts',
    name: 'accounts',
    component: AccountList,
    meta: { title: '账号管理' }
  },
  {
    path: '/api-keys',
    name: 'api-keys',
    component: ApiKeyList,
    meta: { title: 'API 密钥' }
  }
];

const router = createRouter({
  history: createWebHistory('/admin/'),
  routes
});

export default router;

