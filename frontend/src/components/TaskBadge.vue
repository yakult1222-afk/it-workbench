<script setup>
import { computed } from 'vue'

const props = defineProps({
  type: { type: String, default: '' },
  status: { type: String, default: '' }
})

const typeClassMap = {
  审批: 'type-approval',
  硬件问题: 'type-hardware',
  软件问题: 'type-software',
  网络问题: 'type-network'
}
const statusClassMap = {
  完成: 'badge-success',
  未完成: 'badge-warning',
  延期: 'badge-error'
}

const isType = computed(() => !!props.type)
const cls = computed(() =>
  isType.value ? ['badge', typeClassMap[props.type] || ''] : ['badge', statusClassMap[props.status] || '']
)
const text = computed(() => (isType.value ? props.type : props.status))
</script>

<template>
  <span :class="cls"><span v-if="!isType" class="badge-dot-status" />{{ text }}</span>
</template>

<style scoped>
/* 类型徽章：奶油底 + 强调色圆点 */
.type-approval {
  background: var(--surface-card);
}
.type-approval::before {
  content: '';
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  background: var(--accent-teal);
}
.type-hardware::before {
  content: '';
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  background: var(--accent-amber);
}
.type-software::before {
  content: '';
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  background: var(--primary);
}
.type-network::before {
  content: '';
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  background: #7a8cc4;
}
.type-hardware,
.type-software,
.type-network {
  background: var(--surface-card);
}

/* 状态徽章：语义色 */
.badge-dot-status {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  background: currentColor;
  opacity: 0.85;
}
</style>
