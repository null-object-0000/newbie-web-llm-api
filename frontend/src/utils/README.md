# 消息和确认对话框使用说明

## Toast 消息

使用 `message` 对象显示提示消息：

```javascript
import { message } from '../utils/message';

// 成功消息
message.success('操作成功');

// 错误消息
message.error('操作失败');

// 警告消息
message.warning('请注意');

// 信息消息
message.info('提示信息');

// 自定义显示时长（毫秒）
message.success('操作成功', 5000);
```

## 确认对话框

使用 `confirm` 函数显示确认对话框：

```javascript
import { confirm } from '../utils/message';

// 简单用法
const result = await confirm('确定要删除吗？');

// 完整配置
const result = await confirm({
  title: '确认删除',
  message: '确定要删除此项目吗？此操作不可恢复。',
  type: 'danger', // 'warning' | 'danger' | 'info'
  confirmText: '删除',
  cancelText: '取消'
});

if (result) {
  // 用户点击了确定
} else {
  // 用户点击了取消
}
```

## 类型说明

- `type: 'warning'` - 警告（黄色）
- `type: 'danger'` - 危险操作（红色）
- `type: 'info'` - 信息（蓝色）

