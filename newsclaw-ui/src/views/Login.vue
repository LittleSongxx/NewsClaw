<template>
  <div class="login-page">
    <div class="login-center">
      <div class="login-logo">
        <img src="/logo/newsclaw-mark.svg" alt="NewsClaw" class="logo-image" />
        <h1 class="logo-title">News<span class="logo-title-highlight">Claw</span></h1>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <div class="input-wrap">
          <input
            v-model="form.username"
            type="text"
            class="form-input"
            :placeholder="t('login.placeholders.username')"
            :aria-label="t('login.fields.username')"
            autocomplete="username"
            required
          />
        </div>

        <div class="input-wrap">
          <input
            v-model="form.password"
            :type="showPassword ? 'text' : 'password'"
            class="form-input form-input--has-eye"
            :placeholder="t('login.placeholders.password')"
            :aria-label="t('login.fields.password')"
            autocomplete="current-password"
            required
          />
          <button type="button" class="eye-btn" @click="showPassword = !showPassword">
            <svg v-if="!showPassword" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
              <circle cx="12" cy="12" r="3"/>
            </svg>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
              <line x1="1" y1="1" x2="23" y2="23"/>
            </svg>
          </button>
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button type="submit" class="login-btn" :disabled="loading">
          <span v-if="!loading">{{ t('login.signIn') }}</span>
          <span v-else class="loading-dots">
            <span></span><span></span><span></span>
          </span>
        </button>
      </form>

      <!-- SSO providers (rendered only when the backend reports enabled providers) -->
      <template v-if="ssoProviders.length > 0">
        <div class="sso-divider">或使用</div>
        <div class="sso-buttons">
          <button
            v-for="p in ssoProviders"
            :key="p.id"
            type="button"
            class="sso-btn"
            :disabled="loading"
            @click="handleSsoLogin(p.id)"
          >
            {{ p.displayName }} 登录
          </button>
        </div>
      </template>

      <!-- SSO bind dialog (link-only mode: user must bind to an existing account) -->
      <div v-if="bindDialog.visible" class="bind-dialog">
        <div class="bind-dialog-content">
          <h3 class="bind-title">首次使用 {{ bindDialog.provider }} 登录</h3>
          <p class="bind-desc">请绑定你的 NewsClaw 账号</p>
          <input v-model="bindDialog.username" type="text" class="form-input" placeholder="NewsClaw 用户名" autocomplete="username" />
          <input v-model="bindDialog.password" type="password" class="form-input" placeholder="NewsClaw 密码" autocomplete="current-password" />
          <div v-if="bindDialog.error" class="error-msg">{{ bindDialog.error }}</div>
          <button class="login-btn" :disabled="loading" @click="handleBind">绑定</button>
          <button class="bind-cancel" @click="cancelBind">取消</button>
        </div>
      </div>

    </div>
  </div>
  <ChangePasswordDialog
    v-model:visible="forcePasswordChange"
    forced
    @changed="finishForcedLogin"
  />
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { authApi, ssoApi } from '@/api/index'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { useSystemSettingsStore } from '@/stores/useSystemSettingsStore'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'

interface SsoProvider { id: string; displayName: string }

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const systemSettingsStore = useSystemSettingsStore()
const loading = ref(false)
const showPassword = ref(false)
const errorMsg = ref('')
const forcePasswordChange = ref(false)
const form = reactive({ username: '', password: '' })

const ssoProviders = ref<SsoProvider[]>([])

const bindDialog = reactive({
  visible: false,
  bindToken: '',
  provider: '',
  username: '',
  password: '',
  error: '',
})

// Load enabled SSO providers on mount so the button only shows when configured.
onMounted(async () => {
  try {
    const res: any = await ssoApi.providers()
    ssoProviders.value = (res.data || res) as SsoProvider[]
  } catch {
    // SSO not enabled or unreachable — password login still works.
  }

  // Detect SSO callback: /login?sso=callback&code=xxx&provider=feishu
  const query = route.query
  if (query.sso === 'callback' && query.code && query.provider) {
    await handleSsoCallback(String(query.provider), String(query.code), String(query.state || ''))
  }
})

/** Shared login-success flow: write localStorage + navigate. */
async function applyLogin(data: { token: string; id: string | number; username: string; role: string; mustChangePassword?: boolean }) {
  localStorage.setItem('token', data.token)
  localStorage.setItem('userId', String(data.id || '1'))
  localStorage.setItem('username', data.username)
  localStorage.setItem('role', data.role || 'user')
  if (data.mustChangePassword) {
    forcePasswordChange.value = true
    return
  }
  await enterApplication()
}

async function enterApplication() {
  systemSettingsStore.load()
  try {
    await workspaceStore.fetchWorkspaces()
  } catch {
    /* default-deny is fine; router guard will still steer */
  }
  const target = workspaceStore.can('view:dashboard') ? '/dashboard' : '/chat'
  router.push(target)
}

async function finishForcedLogin() {
  forcePasswordChange.value = false
  await enterApplication()
}

async function handleLogin() {
  if (!form.username || !form.password) return
  loading.value = true
  errorMsg.value = ''
  try {
    const res: any = await authApi.login(form)
    const data = res.data || res
    await applyLogin(data)
  } catch (e: any) {
    errorMsg.value = typeof e === 'string' ? e : t('login.failed')
  } finally {
    loading.value = false
  }
}

/** Redirect to the IdP authorization page. */
async function handleSsoLogin(providerId: string) {
  loading.value = true
  errorMsg.value = ''
  try {
    const res: any = await ssoApi.authorize(providerId)
    const data = res.data || res
    if (data.authorizeUrl) {
      window.location.href = data.authorizeUrl
    }
  } catch (e: any) {
    errorMsg.value = typeof e === 'string' ? e : 'SSO 授权失败'
    loading.value = false
  }
}

/** Handle the OAuth2 callback (code → JWT). */
async function handleSsoCallback(provider: string, code: string, state: string) {
  loading.value = true
  errorMsg.value = ''
  try {
    const res: any = await ssoApi.callback(provider, code, state)
    const data = res.data || res

    // link-only mode: backend returns { bindRequired: true, bindToken, provider, displayName }
    if (data.bindRequired) {
      bindDialog.visible = true
      bindDialog.bindToken = data.bindToken || ''
      bindDialog.provider = data.provider || provider
      bindDialog.error = ''
      return
    }

    // Success: loginResponse carries the JWT
    await applyLogin(data.loginResponse)
    // Clean the query params so a refresh doesn't replay the callback.
    router.replace({ path: '/login' })
  } catch (e: any) {
    errorMsg.value = typeof e === 'string' ? e : 'SSO 登录失败'
  } finally {
    loading.value = false
  }
}

/** Submit the bind form (link-only mode). */
async function handleBind() {
  if (!bindDialog.username || !bindDialog.password) return
  loading.value = true
  bindDialog.error = ''
  try {
    const res: any = await ssoApi.bind(bindDialog.bindToken, bindDialog.username, bindDialog.password)
    const data = res.data || res
    bindDialog.visible = false
    await applyLogin(data)
  } catch (e: any) {
    bindDialog.error = typeof e === 'string' ? e : '绑定失败，请检查用户名和密码'
  } finally {
    loading.value = false
  }
}

function cancelBind() {
  bindDialog.visible = false
  bindDialog.bindToken = ''
  bindDialog.username = ''
  bindDialog.password = ''
  bindDialog.error = ''
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--mc-bg);
  padding: 24px;
}

:root.dark .login-page,
html.dark .login-page {
  background: var(--mc-bg);
}

.login-center {
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 32px;
  animation: fadeUp 0.35s ease-out both;
}

/* Logo */
.login-logo {
  text-align: center;
  padding-bottom: 4px;
}

.logo-image {
  display: block;
  margin: 0 auto 14px;
  width: 72px;
  height: 72px;
  object-fit: contain;
  filter: drop-shadow(0 8px 16px rgba(15, 118, 110, 0.16));
}

.logo-title {
  font-size: 32px;
  font-weight: 800;
  color: var(--mc-text-primary);
  margin: 0;
  letter-spacing: 0;
}

.logo-title-highlight {
  color: var(--mc-primary);
}

/* Form */
.login-form {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.form-input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  font-size: 15px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.form-input--has-eye {
  padding-right: 44px;
}

.form-input:focus {
  border-color: var(--mc-primary);
  background: var(--mc-bg-elevated);
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.13);
}

.eye-btn {
  position: absolute;
  right: 12px;
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}

.eye-btn:hover {
  color: var(--mc-primary);
}

/* Error */
.error-msg {
  padding: 10px 14px;
  background: var(--mc-danger-bg);
  border: 1px solid var(--mc-danger);
  border-radius: 6px;
  font-size: 13px;
  color: var(--mc-danger);
}

/* Button */
.login-btn {
  width: 100%;
  padding: 12px;
  background: var(--mc-primary);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
  margin-top: 4px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  background: var(--mc-primary-hover);
  box-shadow: 0 8px 18px rgba(15, 118, 110, 0.20);
}

.login-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

/* SSO buttons */
.sso-divider {
  text-align: center;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin: 4px 0;
  position: relative;
}
.sso-divider::before,
.sso-divider::after {
  content: '';
  display: inline-block;
  width: 30%;
  height: 1px;
  background: var(--mc-border);
  vertical-align: middle;
  margin: 0 8px;
}
.sso-buttons {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}
.sso-btn {
  width: 100%;
  padding: 11px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  height: 44px;
}
.sso-btn:hover:not(:disabled) {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}
.sso-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

/* Bind dialog */
.bind-dialog {
  width: 100%;
}
.bind-dialog-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bind-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
}
.bind-desc {
  font-size: 13px;
  color: var(--mc-text-tertiary);
  margin: 0;
}
.bind-cancel {
  width: 100%;
  padding: 8px;
  background: none;
  color: var(--mc-text-tertiary);
  border: none;
  font-size: 13px;
  cursor: pointer;
}

/* Loading */
.loading-dots {
  display: flex;
  gap: 5px;
  align-items: center;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  background: white;
  border-radius: 50%;
  animation: bounce 1.2s infinite;
}

.loading-dots span:nth-child(2) { animation-delay: 0.2s; }
.loading-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-5px); }
}

/* Hint */
.login-hint {
  text-align: center;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin: 0;
  opacity: 0.7;
}

.login-hint :deep(code) {
  background: var(--mc-inline-code-bg);
  padding: 1px 6px;
  border-radius: 4px;
  color: var(--mc-inline-code-color);
  font-size: 12px;
}

/* Entrance animation */
@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* Mobile */
@media (max-width: 480px) {
  .login-page {
    padding: 16px;
  }
}
</style>
