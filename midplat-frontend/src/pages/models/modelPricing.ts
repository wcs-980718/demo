import type { ModelPayload } from '@/api/midplatApi';

export function formatModelPrice(value: number | null | undefined): string {
  return value == null || value === 0 ? '未计价' : `¥${value.toFixed(8)} / 百万 Token`;
}

export function normalizeModelPricing(payload: ModelPayload): ModelPayload {
  return {
    ...payload,
    inputPricePerMillion: payload.inputPricePerMillion ?? 0,
    outputPricePerMillion: payload.outputPricePerMillion ?? 0,
  };
}
