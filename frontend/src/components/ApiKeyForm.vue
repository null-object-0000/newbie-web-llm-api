<template>
  <form @submit.prevent="submit">
    <div v-if="error" class="error-message">{{ error }}</div>
    
    <div class="form-group">
      <label>密钥名称</label>
      <input 
        type="text" 
        v-model="form.name" 
        placeholder="例如: 生产环境密钥"
      />
    </div>
    
    <div class="form-group">
      <label>描述</label>
      <textarea 
        v-model="form.description" 
        rows="3" 
        placeholder="密钥用途描述"
      ></textarea>
    </div>
    
    <div class="form-group">
      <div class="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-3 text-sm text-blue-800 dark:text-blue-200">
        <i data-lucide="info" class="w-4 h-4 inline mr-1"></i>
        <strong>提示：</strong>创建后可以在 API 密钥列表中关联账号。
      </div>
    </div>
    
    <div v-if="createdApiKey" class="api-key-display">
      <strong>API 密钥:</strong>
      <code>{{ createdApiKey }}</code>
    </div>
    
    <div class="form-actions">
      <button type="button" class="btn btn-secondary" @click="cancel" :disabled="loading">
        取消
      </button>
      <button type="submit" class="btn btn-primary" :disabled="loading">
        {{ loading ? '创建中...' : '创建' }}
      </button>
    </div>
  </form>
</template>

<script setup>
import {onMounted, onUpdated, ref} from 'vue';
import {apiService} from '../services/api';
import {message} from '../utils/message';

const props = defineProps({
  apiKey: {
    type: Object,
    default: null
  }
});

const emit = defineEmits(['saved', 'cancel']);

const form = ref({
  name: '',
  description: ''
});
const loading = ref(false);
const error = ref(null);
const createdApiKey = ref(null);

onMounted(() => {
  if (props.apiKey) {
    form.value = {
      name: props.apiKey.name || '',
      description: props.apiKey.description || ''
    };
  }
});

const submit = async () => {
  loading.value = true;
  error.value = null;
  try {
    const requestData = {
      name: form.value.name,
      description: form.value.description
    };
    const data = await apiService.createApiKey(requestData);
    createdApiKey.value = data.apiKey;
    message.success(
      `API 密钥创建成功！\n\nAPI 密钥: ${data.apiKey}\n\n请妥善保存，此密钥仅显示一次！\n\n提示：您可以在 API 密钥列表中关联账号。`,
      8000
    );
    emit('saved', data);
  } catch (err) {
    error.value = err.response?.data?.error || err.message;
  } finally {
    loading.value = false;
  }
};

const cancel = () => {
  emit('cancel');
};

onUpdated(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});
</script>

