<template>
  <span class="typing-cursor" :class="{ blinking: !typing }"></span>
</template>

<script setup lang="ts">
interface Props {
  /** 是否正在打字 */
  typing?: boolean
  /** 光标颜色 */
  color?: string
}

withDefaults(defineProps<Props>(), {
  typing: false,
  color: 'currentColor',
})
</script>

<style scoped>
.typing-cursor {
  display: inline-block;
  width: 2px;
  height: 1.2em;
  background-color: v-bind(color);
  margin-left: 2px;
  vertical-align: middle;
  animation: blink 1s step-end infinite;
}

.typing-cursor.blinking {
  animation: blink 1s step-end infinite;
}

.typing-cursor:not(.blinking) {
  animation: none;
  opacity: 1;
}

@keyframes blink {
  0%, 50% {
    opacity: 1;
  }
  51%, 100% {
    opacity: 0;
  }
}
</style>
