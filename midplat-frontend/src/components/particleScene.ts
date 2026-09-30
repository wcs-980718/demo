export const HOME_TEXT_CHAPTER = 2;
/** @deprecated 保留旧名称，避免外部调试脚本失效。 */
export const HOME_EYE_CHAPTER = HOME_TEXT_CHAPTER;

export function particleChapterForPath(pathname: string): number {
  return 0;
}

export interface EyeFormation {
  positions: Float32Array;
  depth: Float32Array;
  features: Float32Array;
}

export const EYE_FEATURE = {
  UPPER_LID: 0,
  LOWER_LID: 1,
  SCLERA: 2,
  IRIS: 3,
  PUPIL: 4,
  HIGHLIGHT: 5,
  BROW: 6,
  BRIDGE: 7,
} as const;

export const EYE_LAYOUT = {
  centerX: 0.42,
  centerY: 0.54,
  lidHalfWidth: 0.198,
  upperLidHalfHeight: 0.078,
  lowerLidHalfHeight: 0.058,
  lidOuterLift: 0.02,
  scleraHalfWidth: 0.168,
  scleraHalfHeight: 0.05,
  browHalfWidth: 0.176,
  browLift: 0.128,
  irisMinRadius: 0.042,
  irisSpread: 0.024,
  irisDepthRadius: 0.07,
  irisYScale: 0.9,
  irisGazeX: 0.014,
  pupilRadius: 0.008,
  pupilDepthRadius: 0.014,
  highlightOffsetX: -0.012,
  highlightOffsetY: 0.016,
  highlightRadius: 0.0045,
  bridgeOuter: 0.185,
  bridgeSpan: 0.125,
} as const;

function seededRandom(seed: number): () => number {
  let value = seed | 0;
  return () => {
    value = (value + 0x6d2b79f5) | 0;
    let mixed = Math.imul(value ^ (value >>> 15), 1 | value);
    mixed = (mixed + Math.imul(mixed ^ (mixed >>> 7), 61 | mixed)) ^ mixed;
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296;
  };
}

export interface TextFormation {
  positions: Float32Array;
  depth: Float32Array;
}

export function generateTextFormationFromMask(
  mask: ArrayLike<number>,
  width: number,
  height: number,
  count: number,
  aspect: number,
  seed = 0x5a17d4,
  /** 成形垂直锚点（NDC y）。默认 0.495 为历史值；首页应传入标题占位区实测中心。 */
  anchorY = 0.495,
): TextFormation {
  const safeWidth = Math.max(0, Math.floor(width));
  const safeHeight = Math.max(0, Math.floor(height));
  const safeCount = Math.max(0, Math.floor(count));
  const positions = new Float32Array(safeCount * 2);
  const depth = new Float32Array(safeCount);
  const candidates: Array<[number, number]> = [];
  let minX = safeWidth;
  let maxX = -1;
  let minY = safeHeight;
  let maxY = -1;

  for (let y = 0; y < safeHeight; y++) {
    for (let x = 0; x < safeWidth; x++) {
      if ((mask[y * safeWidth + x] ?? 0) < 120) continue;
      candidates.push([x, y]);
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
  }

  if (!candidates.length || safeCount === 0) return { positions, depth };

  const random = seededRandom(seed);
  const pixelWidth = Math.max(1, maxX - minX + 1);
  const pixelHeight = Math.max(1, maxY - minY + 1);
  const worldWidth = Math.min(1.16, Math.max(0.5, Math.abs(aspect) * 1.84));
  const worldHeight = Math.min(0.24, worldWidth * pixelHeight / pixelWidth);
  const scaleX = worldWidth / pixelWidth;
  const scaleY = worldHeight / pixelHeight;
  const centerX = (minX + maxX) * 0.5;
  const centerY = (minY + maxY) * 0.5;

  for (let i = 0; i < safeCount; i++) {
    const point = candidates[Math.floor(random() * candidates.length)];
    positions[i * 2] = (point[0] - centerX + random() - 0.5) * scaleX;
    positions[i * 2 + 1] = anchorY + (centerY - point[1] + random() - 0.5) * scaleY;
    depth[i] = (random() * 2 - 1) * 0.06;
  }

  return { positions, depth };
}

export const HOME_INTRO = {
  faceGather: 2.0,
  faceHold: 2.8,
} as const;

export function particleTextGatherAt(elapsed: number, reducedMotion: boolean): number {
  if (reducedMotion) return 1;
  const progress = Math.max(0, Math.min(1, elapsed / (HOME_INTRO.faceGather + HOME_INTRO.faceHold)));
  return 1 - Math.pow(1 - progress, 3);
}

function gatherEase(elapsed: number, duration: number): number {
  const progress = Math.max(0, Math.min(1, elapsed / duration));
  return 1 - Math.pow(1 - progress, 3);
}

export type HomeIntroPhase = 'gather' | 'face' | 'pulse' | 'live';
export type HomeIntroHome = 'field' | 'face' | 'live';

export interface HomeIntroView {
  phase: HomeIntroPhase;
  morphT: number;
  brainFx: number;
  textFx: number;
  pulse: number;
  homeA: HomeIntroHome;
  homeB: HomeIntroHome;
  done: boolean;
}

const DONE_INTRO: HomeIntroView = {
  phase: 'live',
  morphT: 1,
  brainFx: 1,
  textFx: 1,
  pulse: 0,
  homeA: 'face',
  homeB: 'face',
  done: true,
};

function smoothstep(t: number): number {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
}

/** 人脸显化期的 3 波神经脉冲：波前短暂点亮（bloom 能量由引擎 u_beat 通道放大）。 */
function neuralPulseAt(progress: number): number {
  const waves = [0.18, 0.46, 0.74];
  const width = 0.17;
  let pulse = 0;
  for (const center of waves) {
    const d = Math.abs(progress - center);
    if (d < width) {
      pulse = Math.max(pulse, Math.sin((d / width) * Math.PI * -0.5 + Math.PI * 0.5) ** 1.4);
    }
  }
  return Math.max(0, Math.min(1, pulse));
}

export function homeIntroAt(elapsed: number, reducedMotion: boolean): HomeIntroView {
  if (reducedMotion) return { ...DONE_INTRO };
  const t = Number.isFinite(elapsed) ? Math.max(0, elapsed) : 0;
  const faceFormed = HOME_INTRO.faceGather;
  const liveStart = faceFormed + HOME_INTRO.faceHold;

  if (t < faceFormed) {
    const morphT = gatherEase(t, HOME_INTRO.faceGather);
    return {
      phase: 'gather',
      morphT,
      brainFx: morphT,
      textFx: 0,
      pulse: 0,
      homeA: 'field',
      homeB: 'face',
      done: false,
    };
  }
  if (t < liveStart) {
    const progress = (t - faceFormed) / HOME_INTRO.faceHold;
    const pulse = neuralPulseAt(progress);
    return {
      phase: pulse > 0.02 ? 'pulse' : 'face',
      morphT: 1,
      brainFx: 1,
      textFx: 0,
      pulse,
      homeA: 'face',
      homeB: 'face',
      done: false,
    };
  }
  // 常驻：人脸长驻首页，眨眼/视线/扫光由 eyeMotionAt 与 titleLive 通道驱动
  return { ...DONE_INTRO };
}

export interface ParticleTitleMotionProfile {
  jitterScale: number;
  pulseScale: number;
  interactionScale: number;
}

export function particleTitleMotionProfile(
  titleBlend: number,
  reducedMotion: boolean,
): ParticleTitleMotionProfile {
  if (reducedMotion) return { jitterScale: 0, pulseScale: 0, interactionScale: 0 };
  const lock = Math.max(0, Math.min(1, titleBlend));
  return {
    jitterScale: 0.36 + 0.64 * (1 - lock),
    pulseScale: 0.018 + 0.027 * (1 - lock),
    interactionScale: 0.48 + 0.52 * (1 - lock),
  };
}

export interface ParticleTitleIdleMotion {
  swayX: number;
  swayY: number;
  breathe: number;
  scanX: number;
  scanStrength: number;
}

export function particleTitleIdleMotion(time: number, reducedMotion: boolean): ParticleTitleIdleMotion {
  if (reducedMotion) {
    return { swayX: 0, swayY: 0, breathe: 0, scanX: 0, scanStrength: 0 };
  }
  const t = Number.isFinite(time) ? time : 0;
  const scanPeriod = 7.6;
  const scanDuration = 1.45;
  const phase = ((t % scanPeriod) + scanPeriod) % scanPeriod;
  const scanActive = phase < scanDuration;
  const scanProgress = scanActive ? phase / scanDuration : 0;
  return {
    swayX: Math.sin(t * 0.41) * 0.018,
    swayY: Math.sin(t * 0.27 + 1.15) * 0.01,
    breathe: Math.sin(t * 0.72) * 0.008,
    scanX: scanActive ? -0.7 + scanProgress * 1.4 : 0,
    scanStrength: scanActive ? Math.sin(scanProgress * Math.PI) * 0.55 : 0,
  };
}

export interface ParticleTitlePalette {
  bodyA: [number, number, number];
  bodyB: [number, number, number];
  energy: number;
}

export function particleTitlePaletteForTheme(theme: 'light' | 'dark'): ParticleTitlePalette {
  return theme === 'light'
    ? {
        // 浅色主题会对整张 WebGL 画布反相；提高原始亮度后，屏幕上的粒子会呈现更深的蓝青色。
        bodyA: [0.52, 0.84, 1],
        bodyB: [0.72, 0.97, 1],
        energy: 1.18,
      }
    : {
        bodyA: [0.18, 0.58, 1],
        bodyB: [0.52, 0.95, 1],
        energy: 1,
      };
}

export const FACE_LAYOUT = {
  centerX: 0,
  centerY: 0.46,
  headRadius: 0.34,
  rx: 0.30,
  ry: 0.38,
  rz: 0.20,
  eyeX: 0.108,
  eyeY: 0.14,
  neuralNodes: 14,
} as const;

function sampleHubFace(u: number, v: number) {
  const L = FACE_LAYOUT;
  const chin = 1 - 0.22 * Math.pow(Math.max(0, -v), 1.35);
  const brow = 1 + 0.05 * Math.exp(-((v - 0.18) ** 2) / 0.05);
  const x = L.rx * Math.sin(u) * chin * brow;
  const y = L.ry * v;
  const cap = Math.sqrt(Math.max(0.04, 1 - v * v * 0.78));
  let z = L.rz * Math.cos(u * 0.9) * cap;
  const noseGate = v > -0.36 && v < 0.3 ? 1 : 0;
  z += noseGate * Math.exp(-(u * u) / 0.05) * (0.05 + 0.07 * Math.exp(-((v - 0.02) ** 2) / 0.075));
  const socket = (side: number) => Math.exp(-((u - side) ** 2) / 0.026 - ((v - 0.16) ** 2) / 0.016);
  z -= 0.038 * (socket(0.4) + socket(-0.4));
  z -= 0.012 * Math.exp(-(u * u) / 0.11) * Math.exp(-((v + 0.36) ** 2) / 0.012);
  return { x: L.centerX + x, y: L.centerY + y, z: z * 0.52 };
}

export function generateEyeFormation(count: number, seed = 0x0e1e5): EyeFormation {
  const safeCount = Math.max(0, Math.floor(count));
  const positions = new Float32Array(safeCount * 2);
  const depth = new Float32Array(safeCount);
  const features = new Float32Array(safeCount);
  const random = seededRandom(seed);
  const F = EYE_FEATURE;
  const L = FACE_LAYOUT;
  const jitter = (scale: number) => (random() - 0.5) * scale;
  const landmarks = [
    [0, 0.72], [-0.42, 0.46], [0.42, 0.46], [-0.28, 0.3], [0.28, 0.3],
    [-0.4, 0.16], [0.4, 0.16], [0, 0.18], [0, 0.02],
    [-0.34, -0.12], [0.34, -0.12], [-0.22, -0.36], [0.22, -0.36], [0, -0.78],
  ].map(([u, v]) => sampleHubFace(u, v));
  const links: Array<[number, number]> = [
    [0, 3], [0, 4], [3, 5], [4, 6], [5, 7], [6, 7], [7, 8], [8, 11], [8, 12],
    [11, 13], [12, 13], [5, 9], [6, 10], [9, 11], [10, 12], [1, 5], [2, 6],
  ];

  for (let i = 0; i < safeCount; i++) {
    const layer = (i + 0.5) / Math.max(1, safeCount);
    // 显式 number：FACE_LAYOUT 是 as const，字面量类型会让后续赋值报错
    let x: number = L.centerX;
    let y: number = L.centerY;
    let z: number = 0.08;
    let feature = -1;

    if (layer < 0.3) {
      const v = -0.9 + (Math.floor(random() * 15) / 14) * 1.8;
      const u = (random() * 2 - 1) * 1.08;
      const p = sampleHubFace(u, v);
      x = p.x + jitter(0.002);
      y = p.y + jitter(0.002);
      z = 0.1 + p.z * 0.15;
    } else if (layer < 0.46) {
      const u = (Math.floor(random() * 9) / 4 - 1) * 0.98;
      const v = (random() * 2 - 1) * 0.92;
      const p = sampleHubFace(u, v);
      x = p.x + jitter(0.002);
      y = p.y + jitter(0.002);
      z = 0.1 + p.z * 0.15;
    } else if (layer < 0.64) {
      const kind = random();
      let u = 0;
      let v = 0;
      if (kind < 0.22) {
        const side = random() < 0.5 ? -1 : 1;
        u = side * (0.22 + random() * 0.48);
        v = 0.28 + 0.04 * Math.sin((Math.abs(u) - 0.22) * 6);
      } else if (kind < 0.42) {
        u = jitter(0.04);
        v = 0.18 - random() * 0.34;
      } else if (kind < 0.62) {
        u = (random() * 2 - 1) * 0.28;
        v = -0.32 + 0.02 * Math.cos(u * 10);
      } else if (kind < 0.78) {
        u = (random() * 2 - 1) * 0.26;
        v = -0.39 - 0.016 * Math.cos(u * 8);
      } else {
        u = (random() * 2 - 1) * 0.92;
        v = -0.76 - 0.1 * Math.cos(u * 1.2);
      }
      const p = sampleHubFace(u, v);
      x = p.x + jitter(0.0018);
      y = p.y + jitter(0.0018);
      z = 0.12 + p.z * 0.12;
    } else if (layer < 0.76) {
      const side = random() < 0.5 ? -1 : 1;
      const ex = L.centerX + side * L.eyeX;
      const ey = L.centerY + L.eyeY;
      const pick = random();
      const angle = random() * Math.PI * 2;
      if (pick < 0.62) {
        const ring = random() < 0.55;
        const radius = ring ? 0.026 + jitter(0.0015) : 0.01 + Math.pow(random(), 0.45) * 0.016;
        x = ex + Math.cos(angle) * radius;
        y = ey + Math.sin(angle) * radius * 0.88;
        z = 0.2;
        feature = F.IRIS;
      } else if (pick < 0.86) {
        const radius = Math.sqrt(random()) * 0.007;
        x = ex + Math.cos(angle) * radius;
        y = ey + Math.sin(angle) * radius;
        z = 0.24;
        feature = F.PUPIL;
      } else {
        x = ex - 0.007 + Math.cos(angle) * 0.003;
        y = ey + 0.008 + Math.sin(angle) * 0.003;
        z = 0.26;
        feature = F.HIGHLIGHT;
      }
    } else if (layer < 0.88) {
      if (random() < 0.32) {
        const node = landmarks[Math.floor(random() * landmarks.length)];
        x = node.x + jitter(0.004);
        y = node.y + jitter(0.004);
        z = 0.13 + node.z * 0.1;
      } else {
        const [a, b] = links[Math.floor(random() * links.length)];
        const t = random();
        x = landmarks[a].x + (landmarks[b].x - landmarks[a].x) * t + jitter(0.002);
        y = landmarks[a].y + (landmarks[b].y - landmarks[a].y) * t + jitter(0.002);
        z = 0.11;
      }
    } else if (layer < 0.96) {
      const x0 = L.centerX - L.rx * 1.18;
      const x1 = L.centerX + L.rx * 1.18;
      const y0 = L.centerY - L.ry * 1.02;
      const y1 = L.centerY + L.ry * 1.02;
      const arm = 0.046;
      const corners: Array<[number, number, number, number]> = [
        [x0, y1, 1, -1], [x1, y1, -1, -1], [x0, y0, 1, 1], [x1, y0, -1, 1],
      ];
      const [cx, cy, dx, dy] = corners[Math.floor(random() * 4)];
      if (random() < 0.5) {
        x = cx + dx * random() * arm;
        y = cy;
      } else {
        x = cx;
        y = cy + dy * random() * arm;
      }
      z = 0.18;
    } else {
      const p = sampleHubFace((random() * 2 - 1) * 0.85, (random() * 2 - 1) * 0.82);
      x = p.x;
      y = p.y;
      z = 0.02 + p.z * 0.08;
    }

    positions[i * 2] = x;
    positions[i * 2 + 1] = y;
    depth[i] = z;
    features[i] = feature;
  }
  return { positions, depth, features };
}

export interface BrainFormation {
  positions: Float32Array;
  depth: Float32Array;
}

export const BRAIN_LAYOUT = {
  centerY: 0.535,
  hemiX: 0.2,
  rx: 0.28,
  ry: 0.335,
  fissure: 0.034,
  gyrusCount: 10,
} as const;

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}

function brainNy(y: number): number {
  return (y - BRAIN_LAYOUT.centerY) / BRAIN_LAYOUT.ry;
}

function brainEgg(ny: number): number {
  return ny < 0 ? 1.06 : 0.96;
}

function hemisphereOuter(ny: number): number {
  if (Math.abs(ny) >= 1) return BRAIN_LAYOUT.fissure;
  return BRAIN_LAYOUT.hemiX + BRAIN_LAYOUT.rx * brainEgg(ny) * Math.sqrt(Math.max(0, 1 - ny * ny));
}

function fissureAt(y: number): number {
  return BRAIN_LAYOUT.fissure + 0.007 * Math.sin(brainNy(y) * Math.PI);
}

function inCerebrum(x: number, y: number): boolean {
  const side = x < 0 ? -1 : 1;
  const ny = brainNy(y);
  if (Math.abs(ny) > 0.98) return false;
  const egg = brainEgg(ny);
  const nx = (x - side * BRAIN_LAYOUT.hemiX) / (BRAIN_LAYOUT.rx * egg);
  if (nx * nx + ny * ny > 1) return false;
  return Math.abs(x) >= fissureAt(y);
}

function gyrusOffset(t: number, fold: number): number {
  return 0.055 * Math.sin(t * Math.PI * (6.4 + fold * 0.38) + fold * 0.95)
    + 0.02 * Math.sin(t * Math.PI * 13 + fold * 1.7);
}

function cerebrumPoint(
  side: number,
  ny: number,
  v: number,
  jitterX: number,
  jitterY: number,
): { x: number; y: number } {
  const y = BRAIN_LAYOUT.centerY + ny * BRAIN_LAYOUT.ry + jitterY;
  const inner = fissureAt(y);
  const outer = hemisphereOuter(ny);
  const x = side * (inner + clamp01(v) * (outer - inner)) + jitterX;
  return { x, y };
}

function sampleGyrus(random: () => number, side: number, fold: number): { x: number; y: number } {
  const t = random();
  const ny = -0.9 + t * 1.8;
  const v = clamp01((fold + 0.5) / BRAIN_LAYOUT.gyrusCount + gyrusOffset(t, fold));
  return cerebrumPoint(side, ny, v, (random() - 0.5) * 0.018, (random() - 0.5) * 0.01);
}

function sampleTransverse(random: () => number, side: number): { x: number; y: number } {
  const which = Math.floor(random() * 3);
  const t = random();
  const ny = [0.26, 0.0, -0.28][which] + (t - 0.5) * 0.12;
  return cerebrumPoint(side, ny, t, (random() - 0.5) * 0.012, (random() - 0.5) * 0.016);
}

function sampleOutline(random: () => number, side: number): { x: number; y: number } {
  const theta = (random() * 1.72 - 0.86) * Math.PI;
  const ny = Math.sin(theta);
  const egg = brainEgg(ny);
  const scallop = 1
    + 0.08 * Math.sin(theta * 11 + side * 0.55)
    + 0.045 * Math.sin(theta * 19 + 0.4);
  const pointingIn = Math.cos(theta) * side < 0.12;
  if (pointingIn && Math.abs(ny) < 0.78) {
    const y = BRAIN_LAYOUT.centerY + ny * BRAIN_LAYOUT.ry * 0.94;
    return { x: side * (fissureAt(y) + random() * 0.01), y };
  }
  return {
    x: side * BRAIN_LAYOUT.hemiX + Math.cos(theta) * BRAIN_LAYOUT.rx * egg * scallop,
    y: BRAIN_LAYOUT.centerY + Math.sin(theta) * BRAIN_LAYOUT.ry * scallop,
  };
}

function nearGyrus(x: number, y: number): boolean {
  const side = x < 0 ? -1 : 1;
  const ny = brainNy(y);
  const inner = fissureAt(y);
  const outer = hemisphereOuter(ny);
  const span = Math.max(0.001, outer - inner);
  const v = (side * x - inner) / span;
  const t = (ny + 0.9) / 1.8;
  for (let fold = 0; fold < BRAIN_LAYOUT.gyrusCount; fold++) {
    if (Math.abs(v - ((fold + 0.5) / BRAIN_LAYOUT.gyrusCount + gyrusOffset(t, fold))) < 0.045) {
      return true;
    }
  }
  return false;
}

function sampleUntil(
  random: () => number,
  accept: (x: number, y: number) => boolean,
  generate: () => { x: number; y: number },
): { x: number; y: number } {
  for (let attempt = 0; attempt < 56; attempt++) {
    const point = generate();
    if (accept(point.x, point.y)) return point;
  }
  return generate();
}

export function generateBrainFormation(count: number, seed = 0x0b4a17): BrainFormation {
  const safeCount = Math.max(0, Math.floor(count));
  const positions = new Float32Array(safeCount * 2);
  const depth = new Float32Array(safeCount);
  const random = seededRandom(seed);

  for (let i = 0; i < safeCount; i++) {
    const layer = (i + 0.5) / Math.max(1, safeCount);
    const side = random() < 0.5 ? -1 : 1;
    let point: { x: number; y: number } = { x: 0, y: BRAIN_LAYOUT.centerY };
    let z = 0;

    if (layer < 0.52) {
      const fold = Math.floor(random() * BRAIN_LAYOUT.gyrusCount);
      point = sampleUntil(random, inCerebrum, () => sampleGyrus(random, side, fold));
      z = 0.055 + (random() - 0.5) * 0.03;
    } else if (layer < 0.66) {
      point = sampleUntil(random, inCerebrum, () => sampleTransverse(random, side));
      z = 0.02 + (random() - 0.5) * 0.03;
    } else if (layer < 0.9) {
      point = sampleUntil(random, inCerebrum, () => sampleOutline(random, side));
      z = -0.02 + (random() - 0.5) * 0.04;
    } else {
      point = sampleUntil(
        random,
        (x, y) => inCerebrum(x, y) && nearGyrus(x, y),
        () => ({
          x: (random() * 2 - 1) * 0.56,
          y: BRAIN_LAYOUT.centerY + (random() * 2 - 1) * BRAIN_LAYOUT.ry,
        }),
      );
      z = 0.03 + (random() - 0.5) * 0.03;
    }

    positions[i * 2] = point.x;
    positions[i * 2 + 1] = point.y;
    depth[i] = z;
  }

  return { positions, depth };
}

export interface EyeMotionInput {
  pointerX: number;
  pointerY: number;
  scanElapsed: number;
  time?: number;
  reducedMotion: boolean;
}

export interface EyeMotion {
  gazeX: number;
  gazeY: number;
  scanX: number;
  scanStrength: number;
  blink: number;
}

export interface ParticleMotionProfile {
  simulationScale: number;
  visualTimeScale: number;
  allowPulse: boolean;
}

export interface EyeLayerPalette {
  sclera: [number, number, number];
  pupil: [number, number, number];
  highlight: [number, number, number];
  scleraEnergy: number;
  pupilEnergy: number;
  highlightEnergy: number;
}

export function eyePaletteForTheme(theme: 'light' | 'dark'): EyeLayerPalette {
  return theme === 'light'
    ? {
        sclera: [0.035, 0.09, 0.16],
        pupil: [0.96, 0.985, 1],
        highlight: [0.012, 0.035, 0.07],
        scleraEnergy: 1.7,
        pupilEnergy: 1.35,
        highlightEnergy: 1.6,
      }
    : {
        sclera: [0.46, 0.66, 0.82],
        pupil: [0.004, 0.012, 0.03],
        highlight: [0.58, 0.82, 1],
        scleraEnergy: 1.35,
        pupilEnergy: 1.2,
        highlightEnergy: 1.7,
      };
}

export function particleMotionProfile(reducedMotion: boolean): ParticleMotionProfile {
  return reducedMotion
    ? { simulationScale: 0, visualTimeScale: 0, allowPulse: false }
    : { simulationScale: 1, visualTimeScale: 1, allowPulse: true };
}

export function eyeMotionAt(input: EyeMotionInput): EyeMotion {
  if (input.reducedMotion) {
    return { gazeX: 0, gazeY: 0, scanX: 0, scanStrength: 0, blink: 0 };
  }
  const pointerX = Math.max(-1, Math.min(1, input.pointerX));
  const pointerY = Math.max(-1, Math.min(1, input.pointerY));
  const scanDuration = 1.1;
  const scanActive = input.scanElapsed >= 0 && input.scanElapsed < scanDuration;
  const scanProgress = scanActive ? input.scanElapsed / scanDuration : 0;
  const blinkCycle = 5.8;
  const blinkDuration = 0.22;
  const time = input.time ?? 0;
  const blinkPhase = ((time - 3.6) % blinkCycle + blinkCycle) % blinkCycle;
  const blink = blinkPhase < blinkDuration
    ? Math.sin((blinkPhase / blinkDuration) * Math.PI)
    : 0;
  // 人脸常驻时跟随指针的轻幅度视线（双眼整体平移，非眼球转动）
  return {
    gazeX: pointerX * 0.03,
    gazeY: pointerY * 0.018,
    scanX: scanActive ? -0.7 + scanProgress * 1.4 : 0,
    scanStrength: scanActive ? Math.sin(scanProgress * Math.PI) : 0,
    blink,
  };
}
