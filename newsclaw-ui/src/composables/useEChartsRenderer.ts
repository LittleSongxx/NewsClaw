import { type Ref, watch, nextTick } from 'vue'
import { useThemeStore } from '@/stores/useThemeStore'

// Lazy-load echarts to keep initial bundle small (~1MB saved)
let echartsModule: typeof import('echarts') | null = null
async function getECharts() {
  if (!echartsModule) {
    echartsModule = await import('echarts')
  }
  return echartsModule
}

/** Top-level keys allowed in ECharts option (security whitelist) */
const ALLOWED_KEYS = new Set([
  'title', 'tooltip', 'legend', 'xAxis', 'yAxis', 'series',
  'grid', 'color', 'dataset', 'graphic', 'radar', 'polar',
  'angleAxis', 'radiusAxis', 'visualMap',
])

const MAX_OPTION_SIZE = 100 * 1024 // 100KB

/**
 * Recursively strip function-like values from an ECharts option object
 * to prevent XSS via ECharts formatter evaluation.
 */
function sanitizeOption(obj: Record<string, any>): void {
  for (const key of Object.keys(obj)) {
    const val = obj[key]
    if (typeof val === 'string' && val.trimStart().startsWith('function')) {
      delete obj[key]
    } else if (typeof val === 'function') {
      delete obj[key]
    } else if (val && typeof val === 'object') {
      if (Array.isArray(val)) {
        val.forEach((item: any) => {
          if (item && typeof item === 'object') sanitizeOption(item)
        })
      } else {
        sanitizeOption(val)
      }
    }
  }
}

function filterTopLevelKeys(option: Record<string, any>): Record<string, any> {
  const filtered: Record<string, any> = {}
  for (const key of Object.keys(option)) {
    if (ALLOWED_KEYS.has(key)) {
      filtered[key] = option[key]
    }
  }
  return filtered
}

/**
 * Composable that observes a container for `.echarts-block` placeholder divs
 * and mounts ECharts instances on them.
 */
export function useEChartsRenderer(containerRef: Ref<HTMLElement | null>) {
  const themeStore = useThemeStore()
  const instanceMap = new WeakMap<HTMLElement, any>() // echarts.ECharts
  const trackedElements: Set<HTMLElement> = new Set()
  const mountingSet = new Set<HTMLElement>() // guard against concurrent mounts
  let observer: MutationObserver | null = null
  let resizeObserver: ResizeObserver | null = null

  async function mountChart(el: HTMLElement) {
    if (instanceMap.has(el) || mountingSet.has(el)) return
    mountingSet.add(el)

    const encoded = el.getAttribute('data-echarts-option')
    if (!encoded) {
      mountingSet.delete(el)
      return
    }

    // Size guard
    if (encoded.length > MAX_OPTION_SIZE) {
      el.textContent = 'Chart option too large'
      mountingSet.delete(el)
      return
    }

    try {
      const raw = decodeURIComponent(encoded)
      let option = JSON.parse(raw)

      // Must be an object with series
      if (!option || typeof option !== 'object' || !option.series) {
        el.textContent = 'Invalid chart option'
        mountingSet.delete(el)
        return
      }

      // Security: filter keys and strip functions
      option = filterTopLevelKeys(option)
      sanitizeOption(option)

      // Ensure the element has explicit dimensions
      if (!el.style.height) {
        el.style.height = '350px'
      }
      if (!el.style.width) {
        el.style.width = '100%'
      }

      const echarts = await getECharts()
      const theme = themeStore.isDark ? 'dark' : undefined
      const chart = echarts.init(el, theme)
      chart.setOption(option)
      instanceMap.set(el, chart)
      trackedElements.add(el)
    } catch (e) {
      console.error('[EChartsRenderer] mount error:', e)
      el.textContent = 'Chart render error'
      el.classList.add('echarts-error')
    } finally {
      mountingSet.delete(el)
    }
  }

  function scanAndMount() {
    const container = containerRef.value
    if (!container) return
    const blocks = container.querySelectorAll('.echarts-block:not(.echarts-error)')
    blocks.forEach((el) => {
      if (!instanceMap.has(el as HTMLElement) && !mountingSet.has(el as HTMLElement)) {
        mountChart(el as HTMLElement)
      }
    })
  }

  function rebuildAll() {
    trackedElements.forEach((el) => {
      const chart = instanceMap.get(el)
      if (chart) {
        chart.dispose()
        instanceMap.delete(el)
      }
    })
    trackedElements.clear()
    scanAndMount()
  }

  function resizeAll() {
    trackedElements.forEach((el) => {
      const chart = instanceMap.get(el)
      if (chart && !chart.isDisposed()) {
        chart.resize()
      }
    })
  }

  function attachObserver(container: HTMLElement) {
    // Clean up previous observers
    observer?.disconnect()
    resizeObserver?.disconnect()

    // MutationObserver to detect new echarts blocks in the DOM
    observer = new MutationObserver(() => {
      // Use nextTick to ensure DOM is settled after Vue updates
      nextTick(() => scanAndMount())
    })
    observer.observe(container, { childList: true, subtree: true })

    // ResizeObserver for container width changes
    resizeObserver = new ResizeObserver(() => {
      resizeAll()
    })
    resizeObserver.observe(container)

    // Initial scan
    scanAndMount()
  }

  function startObserving() {
    const container = containerRef.value
    if (container) {
      attachObserver(container)
    }
  }

  // Watch containerRef — if it's null at mount time, attach when it becomes available
  const stopContainerWatch = watch(
    () => containerRef.value,
    (newContainer) => {
      if (newContainer && !observer) {
        attachObserver(newContainer)
      }
    },
    { immediate: false },
  )

  // Theme reactivity
  const stopThemeWatch = watch(
    () => themeStore.isDark,
    () => {
      rebuildAll()
    },
  )

  function dispose() {
    stopContainerWatch()
    stopThemeWatch()
    observer?.disconnect()
    observer = null
    resizeObserver?.disconnect()
    resizeObserver = null
    trackedElements.forEach((el) => {
      const chart = instanceMap.get(el)
      if (chart && !chart.isDisposed()) {
        chart.dispose()
      }
    })
    trackedElements.clear()
  }

  return { startObserving, dispose, scanAndMount }
}
