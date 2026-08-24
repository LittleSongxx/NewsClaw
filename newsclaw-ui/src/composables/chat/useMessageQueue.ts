/**
 * 消息队列管理 Composable
 *
 * 参考 claude-code-haha 的 messageQueueManager 设计思想：
 * - 当 AI 正在运行时，用户的新输入不被拒绝，而是进入队列
 * - 当前 turn 结束（完成/中断/停止）后自动发送队列中的下一条消息
 * - 支持查看、取消队列中的消息
 *
 * 支持多条排队消息，按序消费（与后端 ConcurrentLinkedQueue 对齐）。
 */
import { ref, computed } from 'vue'
import type { QueuedMessage, MessageContentPart } from '@/types'

export interface UseMessageQueueReturn {
  /** 当前排队的消息列表 */
  queuedMessages: import('vue').Ref<QueuedMessage[]>
  /** 当前排队的第一条消息（向后兼容） */
  queuedMessage: import('vue').ComputedRef<QueuedMessage | null>
  /** 是否有排队消息 */
  hasQueued: import('vue').ComputedRef<boolean>
  /** 排队消息数量 */
  queueSize: import('vue').ComputedRef<number>
  /** 入队一条消息 */
  enqueue: (content: string, contentParts?: MessageContentPart[], conversationId?: string) => void
  /** 消费队列头（取出并移除） */
  dequeue: () => QueuedMessage | null
  /** 取消指定位置的排队消息（默认最后一条） */
  cancel: (index?: number) => void
  /** 标记队列头消息为 sending */
  markSending: () => void
  /** 清空队列 */
  clear: () => void
}

export function useMessageQueue(): UseMessageQueueReturn {
  const queuedMessages = ref<QueuedMessage[]>([])

  const queuedMessage = computed(() => {
    const active = queuedMessages.value.filter(m => m.status !== 'cancelled')
    return active.length > 0 ? active[0] : null
  })

  const hasQueued = computed(() => queuedMessages.value.some(m => m.status !== 'cancelled'))

  const queueSize = computed(() => queuedMessages.value.filter(m => m.status !== 'cancelled').length)

  const enqueue = (content: string, contentParts?: MessageContentPart[], conversationId?: string) => {
    queuedMessages.value = [
      ...queuedMessages.value,
      {
        content,
        enqueuedAt: Date.now(),
        status: 'queued',
        contentParts,
        conversationId,
      },
    ]
  }

  const dequeue = (): QueuedMessage | null => {
    const idx = queuedMessages.value.findIndex(m => m.status !== 'cancelled')
    if (idx === -1) return null
    const msg = queuedMessages.value[idx]
    queuedMessages.value = queuedMessages.value.filter((_, i) => i !== idx)
    return msg
  }

  const cancel = (index?: number) => {
    const activeIndices = queuedMessages.value
      .map((m, i) => m.status !== 'cancelled' ? i : -1)
      .filter(i => i >= 0)

    if (activeIndices.length === 0) return

    // 默认取消最后一条活跃消息
    const targetIdx = index !== undefined ? index : activeIndices[activeIndices.length - 1]
    if (targetIdx < 0 || targetIdx >= queuedMessages.value.length) return

    const updated = [...queuedMessages.value]
    updated[targetIdx] = { ...updated[targetIdx], status: 'cancelled' }
    queuedMessages.value = updated

    // 延迟清除已取消的消息以允许 UI 过渡
    setTimeout(() => {
      queuedMessages.value = queuedMessages.value.filter(m => m.status !== 'cancelled')
    }, 300)
  }

  const markSending = () => {
    const idx = queuedMessages.value.findIndex(m => m.status === 'queued')
    if (idx === -1) return
    const updated = [...queuedMessages.value]
    updated[idx] = { ...updated[idx], status: 'sending' }
    queuedMessages.value = updated
  }

  const clear = () => {
    queuedMessages.value = []
  }

  return {
    queuedMessages,
    queuedMessage,
    hasQueued,
    queueSize,
    enqueue,
    dequeue,
    cancel,
    markSending,
    clear,
  }
}

export default useMessageQueue
