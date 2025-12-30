<template>
  <Teleport to="body">
    <TransitionGroup
      name="toast"
      tag="div"
      class="toast-container"
    >
      <div
        v-for="toast in toasts"
        :key="toast.id"
        :class="['toast', `toast-${toast.type}`]"
      >
        <div class="toast-content">
          <i :data-lucide="getIcon(toast.type)" class="toast-icon"></i>
          <span class="toast-message">{{ toast.message }}</span>
        </div>
        <button class="toast-close" @click="remove(toast.id)">
          <i data-lucide="x" class="w-4 h-4"></i>
        </button>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<script setup>
import {onMounted, onUpdated, ref} from 'vue';

const toasts = ref([]);
let toastId = 0;

const getIcon = (type) => {
  const icons = {
    success: 'check-circle',
    error: 'x-circle',
    warning: 'alert-triangle',
    info: 'info'
  };
  return icons[type] || 'info';
};

const show = (message, type = 'info', duration = 3000) => {
  const id = ++toastId;
  const toast = {
    id,
    message,
    type,
    duration
  };
  
  toasts.value.push(toast);
  
  if (duration > 0) {
    setTimeout(() => {
      remove(id);
    }, duration);
  }
  
  return id;
};

const remove = (id) => {
  const index = toasts.value.findIndex(t => t.id === id);
  if (index > -1) {
    toasts.value.splice(index, 1);
  }
};

const success = (message, duration) => show(message, 'success', duration);
const error = (message, duration) => show(message, 'error', duration);
const warning = (message, duration) => show(message, 'warning', duration);
const info = (message, duration) => show(message, 'info', duration);

// 暴露方法给全局使用
defineExpose({
  show,
  success,
  error,
  warning,
  info,
  remove
});

onMounted(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});

onUpdated(() => {
  if (window.lucide) {
    window.lucide.createIcons();
  }
});
</script>

<style scoped>
.toast-container {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 10000;
  display: flex;
  flex-direction: column;
  gap: 12px;
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 300px;
  max-width: 500px;
  padding: 12px 16px;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  background: white;
  pointer-events: auto;
  animation: slideIn 0.3s ease-out;
}

.dark .toast {
  background: #1d232a;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.toast-content {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.toast-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.toast-message {
  flex: 1;
  font-size: 14px;
  line-height: 1.5;
  color: #111827;
}

.dark .toast-message {
  color: #f9fafb;
}

.toast-close {
  margin-left: 12px;
  padding: 4px;
  border: none;
  background: transparent;
  cursor: pointer;
  color: #6b7280;
  border-radius: 4px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.toast-close:hover {
  background: rgba(0, 0, 0, 0.05);
  color: #111827;
}

.dark .toast-close {
  color: #9ca3af;
}

.dark .toast-close:hover {
  background: rgba(255, 255, 255, 0.05);
  color: #f9fafb;
}

.toast-success {
  border-left: 4px solid #10b981;
}

.toast-success .toast-icon {
  color: #10b981;
}

.toast-error {
  border-left: 4px solid #ef4444;
}

.toast-error .toast-icon {
  color: #ef4444;
}

.toast-warning {
  border-left: 4px solid #f59e0b;
}

.toast-warning .toast-icon {
  color: #f59e0b;
}

.toast-info {
  border-left: 4px solid #3b82f6;
}

.toast-info .toast-icon {
  color: #3b82f6;
}

@keyframes slideIn {
  from {
    transform: translateX(100%);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}

.toast-leave-active {
  transition: all 0.3s ease-in;
}

.toast-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>

