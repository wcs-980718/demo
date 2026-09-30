import type { ExceptionReportFilters } from '@/api/midplatApi';

export type FixedExceptionPlatform = {
  id: string;
  name: string;
};

export const EXCEPTION_METHOD_OPTIONS = [
  'GET',
  'HEAD',
  'POST',
  'PUT',
  'PATCH',
  'DELETE',
  'OPTIONS',
  'TRACE',
  'CONNECT',
].map((method) => ({ value: method, label: method }));

export function isExceptionRowActivationKey(key: string) {
  return key === 'Enter' || key === ' ';
}

export function exceptionHistoryPresentation(platform?: FixedExceptionPlatform) {
  if (!platform) {
    return {
      title: '异常总览',
      description: '各接入项目调用接口报错时汇总到这里，点开可看请求参数与请求体。',
      showProjectFilter: true,
      showProjectColumn: true,
    };
  }
  return {
    title: `${platform.name} · 异常历史`,
    description: `仅展示${platform.name}上报的接口调用异常，点开可查看完整错误上下文。`,
    showProjectFilter: false,
    showProjectColumn: false,
  };
}

export function normalizeExceptionFilters(
  draft: ExceptionReportFilters,
  fixedPlatformId?: string,
): ExceptionReportFilters {
  const platformId = clean(fixedPlatformId) ?? clean(draft.platformId);
  const method = clean(draft.method)?.toUpperCase();
  const keyword = clean(draft.keyword);
  const occurredFrom = clean(draft.occurredFrom);
  const occurredTo = clean(draft.occurredTo);

  return {
    ...(platformId ? { platformId } : {}),
    ...(method ? { method } : {}),
    ...(draft.status !== undefined && draft.status !== null ? { status: draft.status } : {}),
    ...(keyword ? { keyword } : {}),
    ...(occurredFrom ? { occurredFrom } : {}),
    ...(occurredTo ? { occurredTo } : {}),
  };
}

function clean(value: string | undefined) {
  const normalized = value?.trim();
  return normalized || undefined;
}
