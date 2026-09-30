export type PublicAssetRuntime = {
  injectedPublicPath?: string;
  publicPath?: string;
  nodeEnv?: string;
};

export function resolvePublicAsset(file: string, runtime: PublicAssetRuntime = {}) {
  const injected = runtime.injectedPublicPath?.trim();
  const configured = runtime.publicPath?.trim();
  const fallback = (runtime.nodeEnv ?? 'development') === 'production' ? '/midplat-frontend/' : '/';
  const base = `${injected || configured || fallback}`.replace(/\/?$/, '/');
  return `${base}${file.replace(/^\//, '')}`;
}

export function publicAsset(file: string) {
  const runtimeWindow = typeof window === 'undefined'
    ? undefined
    : window as Window & { __INJECTED_PUBLIC_PATH_BY_QIANKUN__?: string };
  return resolvePublicAsset(file, {
    injectedPublicPath: runtimeWindow?.__INJECTED_PUBLIC_PATH_BY_QIANKUN__,
    publicPath: process.env.PUBLIC_PATH,
    nodeEnv: process.env.NODE_ENV,
  });
}
