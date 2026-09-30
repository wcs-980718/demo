import type { ReactNode } from 'react';
import { configureMidplatAntd, MidplatConfigProvider } from '@/antdConfig';

configureMidplatAntd();

export function rootContainer(container: ReactNode) {
  return <MidplatConfigProvider>{container}</MidplatConfigProvider>;
}
