<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="modal" @click.self="handleCancel">
        <div class="modal-content confirm-modal">
          <div class="modal-header">
            <h2>{{ title }}</h2>
          </div>
          <div class="modal-body">
            <div class="confirm-content">
              <i :data-lucide="icon" class="confirm-icon" :class="`confirm-icon-${type}`"></i>
              <p class="confirm-message">{{ message }}</p>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="handleCancel">
              {{ cancelText }}
            </button>
            <button class="btn" :class="`btn-${confirmType}`" @click="handleConfirm">
              {{ confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUpdated } from 'vue';

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  title: {
    type: String,
    default: '确认'
  },
  message: {
    type: String,
    required: true
  },
  type: {
    type: String,
    default: 'warning', // warning, danger, info
    validator: (value) => ['warning', 'danger', 'info'].includes(value)
  },
  confirmText: {
    type: String,
    default: '确定'
  },
  cancelText: {
    type: String,
    default: '取消'
  }
});

const emit = defineEmits(['update:modelValue', 'confirm', 'cancel']);

const visible = ref(props.modelValue);

watch(() => props.modelValue, (newVal) => {
  visible.value = newVal;
});

const confirmType = computed(() => {
  return props.type === 'danger' ? 'danger' : 'primary';
});

const icon = computed(() => {
  const icons = {
    warning: 'alert-triangle',
    danger: 'alert-circle',
    info: 'info'
  };
  return icons[props.type] || 'alert-triangle';
});

const handleConfirm = () => {
  emit('confirm');
  emit('update:modelValue', false);
  visible.value = false;
};

const handleCancel = () => {
  emit('cancel');
  emit('update:modelValue', false);
  visible.value = false;
};

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
.confirm-modal {
  max-width: 400px;
}

.confirm-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 20px 0;
}

.confirm-icon {
  width: 48px;
  height: 48px;
  margin-bottom: 16px;
}

.confirm-icon-warning {
  color: #f59e0b;
}

.confirm-icon-danger {
  color: #ef4444;
}

.confirm-icon-info {
  color: #3b82f6;
}

.confirm-message {
  font-size: 15px;
  line-height: 1.6;
  color: #374151;
  white-space: pre-line;
}

.dark .confirm-message {
  color: #d1d5db;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #e5e7eb;
  margin-top: 16px;
}

.dark .modal-footer {
  border-top-color: #374151;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>

