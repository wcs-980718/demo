export type MidplatApiRuntime = {
  locationHref?: string;
  explicitApiBaseURL?: string;
  injectedPublicPath?: string;
};

type MidplatWindow = Window & {
  __MIDPLAT_API_BASE_URL__?: string;
  __INJECTED_PUBLIC_PATH_BY_QIANKUN__?: string;
};

function normalizeBaseURL(baseURL: string) {
  return baseURL.replace(/\/+$/, '');
}

export function resolveMidplatApiBaseURL(runtime: MidplatApiRuntime = {}) {
  const explicitApiBaseURL = runtime.explicitApiBaseURL?.trim();
  if (explicitApiBaseURL) {
    return normalizeBaseURL(explicitApiBaseURL);
  }

  const injectedPublicPath = runtime.injectedPublicPath?.trim();
  if (!injectedPublicPath) {
    return '/api';
  }

  try {
    const publicPathURL = new URL(injectedPublicPath, runtime.locationHref ?? 'http://localhost/');
    return normalizeBaseURL(new URL('/api', publicPathURL).toString());
  } catch {
    return '/api';
  }
}

export function getMidplatApiBaseURL() {
  if (typeof window === 'undefined') {
    return '/api';
  }
  const runtimeWindow = window as MidplatWindow;
  return resolveMidplatApiBaseURL({
    locationHref: runtimeWindow.location.href,
    explicitApiBaseURL: runtimeWindow.__MIDPLAT_API_BASE_URL__,
    injectedPublicPath: runtimeWindow.__INJECTED_PUBLIC_PATH_BY_QIANKUN__,
  });
}
