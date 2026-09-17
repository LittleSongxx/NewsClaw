/** 本地 file:native-auth 包未随仓库发布时的类型桩，避免 tsc 假红。 */
declare module "@newsclaw/native-auth" {
  export type AuthorizationResult = {
    url?: string;
    state?: string;
    error?: string;
  };

  export const NativeAuth: {
    getRedirectUri: () => Promise<{ uri: string }>;
    getPendingResult: () => Promise<AuthorizationResult>;
    authorize: (opts: {
      url: string;
      redirectUri: string;
      state: string;
    }) => Promise<AuthorizationResult>;
    cancel: () => Promise<void>;
    clearPendingResult: () => Promise<void>;
    addListener: (
      event: string,
      callback: (result: AuthorizationResult) => void,
    ) => Promise<{ remove: () => Promise<void> }>;
  };
}
