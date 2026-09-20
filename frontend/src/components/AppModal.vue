<script setup>
defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  width: { type: String, default: '560px' }
})
const emit = defineEmits(['close'])
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-mask" @click.self="emit('close')">
        <div class="modal-panel" :style="{ maxWidth: width }" role="dialog" aria-modal="true">
          <div class="modal-header">
            <h3 class="title-md">{{ title }}</h3>
            <button class="modal-close" aria-label="关闭" @click="emit('close')">×</button>
          </div>
          <div class="modal-body">
            <slot />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(20, 20, 19, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-lg);
  z-index: 200;
}
.modal-panel {
  width: 100%;
  background: var(--canvas);
  border-radius: var(--r-lg);
  border: 1px solid var(--hairline);
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 30px rgba(20, 20, 19, 0.12);
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-lg) var(--sp-xl) var(--sp-md);
  border-bottom: 1px solid var(--hairline-soft);
}
.modal-close {
  border: none;
  background: transparent;
  font-size: 22px;
  line-height: 1;
  color: var(--muted);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--r-sm);
}
.modal-close:hover {
  background: var(--surface-card);
  color: var(--ink);
}
.modal-body {
  padding: var(--sp-xl);
  overflow-y: auto;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.18s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
