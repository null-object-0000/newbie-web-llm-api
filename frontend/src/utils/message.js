// 全局消息服务
let toastInstance = null;

export const setToastInstance = (instance) => {
  toastInstance = instance;
};

export const message = {
  success: (msg, duration) => {
    if (toastInstance) {
      toastInstance.success(msg, duration);
    } else {
      console.log('Success:', msg);
    }
  },
  error: (msg, duration) => {
    if (toastInstance) {
      toastInstance.error(msg, duration);
    } else {
      console.error('Error:', msg);
    }
  },
  warning: (msg, duration) => {
    if (toastInstance) {
      toastInstance.warning(msg, duration);
    } else {
      console.warn('Warning:', msg);
    }
  },
  info: (msg, duration) => {
    if (toastInstance) {
      toastInstance.info(msg, duration);
    } else {
      console.info('Info:', msg);
    }
  }
};

// 全局 confirm 服务
let confirmModalInstance = null;

export const setConfirmModalInstance = (instance) => {
  confirmModalInstance = instance;
};

export const confirm = (options) => {
  return new Promise((resolve) => {
    if (!confirmModalInstance || !confirmModalInstance.show) {
      // 如果没有 confirm 组件，回退到浏览器 confirm
      const message = typeof options === 'string' ? options : options.message;
      const result = window.confirm(message || '确认操作？');
      resolve(result);
      return;
    }
    
    const {
      title = '确认',
      message = '确认操作？',
      type = 'warning',
      confirmText = '确定',
      cancelText = '取消'
    } = typeof options === 'string' ? { message: options } : options;
    
    confirmModalInstance.show({
      title,
      message: message || '确认操作？',
      type,
      confirmText,
      cancelText
    }).then((result) => {
      resolve(result);
    });
  });
};

