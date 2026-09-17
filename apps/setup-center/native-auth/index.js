/** Web / ECS 部署用的空实现：原生账号登录只在移动端存在。 */
const unavailable = async () => {
  throw new Error("native_auth_unavailable");
};

export const NativeAuth = {
  getRedirectUri: unavailable,
  getPendingResult: async () => ({}),
  authorize: unavailable,
  cancel: async () => {},
  clearPendingResult: async () => {},
  addListener: async () => ({ remove: async () => {} }),
};
