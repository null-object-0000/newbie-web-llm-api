<template>
  <ConfirmModal
    v-if="visible"
    v-model="visible"
    :title="config.title || '确认'"
    :message="config.message || ''"
    :type="config.type || 'warning'"
    :confirm-text="config.confirmText || '确定'"
    :cancel-text="config.cancelText || '取消'"
    @confirm="handleConfirm"
    @cancel="handleCancel"
  />
</template>

<script setup>
import {onMounted, ref} from 'vue';
import ConfirmModal from './ConfirmModal.vue';
import {setConfirmModalInstance} from '../utils/message';

const visible = ref(false);
const config = ref({});
let resolvePromise = null;

const show = (options) => {
  return new Promise((resolve) => {
    // 确保 message 有值
    const message = typeof options === 'string' ? options : (options.message || '确认操作？');
    config.value = {
      title: (typeof options === 'string' ? '确认' : options.title) || '确认',
      message: message,
      type: (typeof options === 'string' ? 'warning' : options.type) || 'warning',
      confirmText: (typeof options === 'string' ? '确定' : options.confirmText) || '确定',
      cancelText: (typeof options === 'string' ? '取消' : options.cancelText) || '取消'
    };
    resolvePromise = resolve;
    visible.value = true;
  });
};

const handleConfirm = () => {
  if (resolvePromise) {
    resolvePromise(true);
    resolvePromise = null;
  }
  visible.value = false;
};

const handleCancel = () => {
  if (resolvePromise) {
    resolvePromise(false);
    resolvePromise = null;
  }
  visible.value = false;
};

onMounted(() => {
  setConfirmModalInstance({ show });
});

defineExpose({
  show
});
</script>

