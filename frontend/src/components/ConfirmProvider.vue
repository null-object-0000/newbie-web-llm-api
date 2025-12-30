<template>
  <ConfirmModal
    v-model="visible"
    :title="config.title"
    :message="config.message"
    :type="config.type"
    :confirm-text="config.confirmText"
    :cancel-text="config.cancelText"
    @confirm="handleConfirm"
    @cancel="handleCancel"
  />
</template>

<script setup>
import { ref, onMounted } from 'vue';
import ConfirmModal from './ConfirmModal.vue';
import { setConfirmModalInstance } from '../utils/message';

const visible = ref(false);
const config = ref({});
let resolvePromise = null;

const show = (options) => {
  return new Promise((resolve) => {
    config.value = {
      title: options.title || '确认',
      message: options.message,
      type: options.type || 'warning',
      confirmText: options.confirmText || '确定',
      cancelText: options.cancelText || '取消'
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

