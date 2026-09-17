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
