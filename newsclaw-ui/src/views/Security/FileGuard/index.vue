<template>
  <div class="settings-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('security.fileGuard.title') }}</h2>
        <p class="section-desc">{{ t('security.fileGuard.desc') }}</p>
      </div>
    </div>

    <div class="config-card">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('security.fileGuard.enabled') }}</div>
          <div class="setting-hint">{{ t('security.fileGuard.enabledHint') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input type="checkbox" v-model="fileGuardConfig.fileGuardEnabled" @change="saveFileGuardConfig" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>
    </div>

    <div class="paths-section">
      <h3 class="subsection-title">{{ t('security.fileGuard.sensitivePaths') }}</h3>
      <p class="section-desc">{{ t('security.fileGuard.sensitivePathsDesc') }}</p>
      <div class="tag-input tag-input-block">
        <span v-for="path in sensitivePaths" :key="path" class="tag">
          {{ path }}
          <button class="tag-remove" @click="removeSensitivePath(path)">&times;</button>
        </span>
        <input
          v-model="newSensitivePath"
          :placeholder="t('security.fileGuard.pathPlaceholder')"
          @keydown.enter.prevent="addSensitivePath"
          class="tag-input-field"
        />
      </div>
      <button class="btn-primary btn-sm" style="margin-top: 12px" @click="saveFileGuardConfig">
        {{ t('common.save') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { securityApi } from '@/api'

const { t } = useI18n()

const fileGuardConfig = reactive({
  fileGuardEnabled: true,
})
const sensitivePaths = ref<string[]>([])
const newSensitivePath = ref('')

async function loadFileGuardConfig() {
  try {
    const res: any = await securityApi.getFileGuardConfig()
    const data = res.data
    fileGuardConfig.fileGuardEnabled = data.fileGuardEnabled
    sensitivePaths.value = data.sensitivePaths || []
  } catch {
    // ignore
  }
}

async function saveFileGuardConfig() {
  try {
    await securityApi.updateFileGuardConfig({
      fileGuardEnabled: fileGuardConfig.fileGuardEnabled,
      sensitivePathsJson: JSON.stringify(sensitivePaths.value),
    })
  } catch {
    // ignore
  }
}

function addSensitivePath() {
  const val = newSensitivePath.value.trim()
  if (val && !sensitivePaths.value.includes(val)) {
    sensitivePaths.value.push(val)
  }
  newSensitivePath.value = ''
}

function removeSensitivePath(path: string) {
  sensitivePaths.value = sensitivePaths.value.filter(p => p !== path)
}

onMounted(() => {
  loadFileGuardConfig()
})
</script>

<style>
@import '../shared.css';
</style>

<style scoped>
.paths-section { margin-top: 8px; }
.subsection-title { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 12px; }
</style>
