import {
  FACE_LAYOUT,
  eyeMotionAt,
  eyePaletteForTheme,
  generateEyeFormation,
  generateTextFormationFromMask,
  HOME_INTRO,
  HOME_TEXT_CHAPTER,
  homeIntroAt,
  type HomeIntroHome,
  particleTitleIdleMotion,
  particleTitleMotionProfile,
  particleTitlePaletteForTheme,
  particleMotionProfile,
} from './particleScene';

/* ============================================================
   CubeField — WebGL2 GPU particle background for 数智医院智能应用中心
   ------------------------------------------------------------
   Ported & adapted from "Da7em" by Da7_Tech (da7tech.com),
   engine LUMEN — a living digital organism.
   https://github.com/Da7-Tech/da7em
   Licensed CC BY 4.0 — https://creativecommons.org/licenses/by/4.0/
   Changes made: DA7EM text formations replaced with a Rubik's-cube
   point cloud; scroll-driven chapters replaced with route-driven
   morph; audio engine, DOM overlay, veil and keyboard navigation
   removed; awake from birth; pointer feed excludes interactive UI.
   Pipeline, physics, palettes and quality controller are original.
   ============================================================ */

export interface CubeFieldHandle {
  setChapter(index: number): void;
  pulse(): void;
  destroy(): void;
  /** 首页挂载后重测标题占位锚点（水平居中、垂直落在两段描述文字中间） */
  refreshTitleAnchor(): void;
  /** 调试探针：回读引擎当前章节与形变进度 */
  debug?(): { chFrom: number; chTo: number; morphT: number; quality: number };
}

const clamp = (v: number, a: number, b: number): number => (v < a ? a : v > b ? b : v);
const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;
const smooth = (t: number): number => t * t * (3 - 2 * t);
const expDamp = (cur: number, target: number, rate: number, dt: number): number =>
  cur + (target - cur) * (1 - Math.exp(-rate * dt));

/* deterministic RNG for stable formations */
function mulberry32(a: number): () => number {
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/* ============================ chapters ============================ */
interface Chapter {
  bg: number[]; fleshA: number[]; fleshB: number[];
  colA: number[]; colB: number[]; glow: number[];
  spring: number; turb: number; push: number; swirl: number; r: number;
  bloom: number; vein: number; pulse: number;
}

const CH: Chapter[] = [
  { bg:[0.012,0.020,0.040], fleshA:[0.06,0.24,0.34], fleshB:[0.015,0.09,0.17],
    colA:[0.20,0.55,1.0], colB:[0.70,0.88,1.0], glow:[0.50,0.72,1.0],
    spring:11.0, turb:0.05, push:3.0, swirl:1.0, r:0.30, bloom:1.0, vein:0.35, pulse:0.15 },
  { bg:[0.030,0.020,0.062], fleshA:[0.17,0.10,0.34], fleshB:[0.05,0.03,0.13],
    colA:[0.66,0.55,1.0], colB:[1.0,0.45,0.80], glow:[0.76,0.62,1.0],
    spring:6.4, turb:0.20, push:2.2, swirl:2.6, r:0.26, bloom:1.05, vein:0.45, pulse:0.15 },
  { bg:[0.010,0.028,0.052], fleshA:[0.04,0.25,0.42], fleshB:[0.01,0.10,0.20],
    colA:[0.18,0.58,1.0], colB:[0.52,0.95,1.0], glow:[0.40,0.82,1.0],
    spring:14.5, turb:0.008, push:1.3, swirl:1.0, r:0.26, bloom:0.68, vein:0.42, pulse:0.16 },
  { bg:[0.052,0.014,0.030], fleshA:[0.34,0.07,0.13], fleshB:[0.14,0.03,0.06],
    colA:[1.0,0.26,0.36], colB:[1.0,0.73,0.36], glow:[1.0,0.46,0.50],
    spring:8.8, turb:0.30, push:4.5, swirl:1.2, r:0.22, bloom:1.25, vein:0.50, pulse:1.0 },
  { bg:[0.010,0.040,0.046], fleshA:[0.05,0.27,0.29], fleshB:[0.02,0.11,0.14],
    colA:[0.25,0.85,0.80], colB:[0.72,0.96,1.0], glow:[0.60,0.95,0.95],
    spring:5.6, turb:0.18, push:1.8, swirl:2.2, r:0.30, bloom:0.95, vein:0.40, pulse:0.15 },
  { bg:[0.052,0.042,0.018], fleshA:[0.32,0.25,0.10], fleshB:[0.12,0.09,0.04],
    colA:[1.0,0.82,0.36], colB:[1.0,0.98,0.92], glow:[1.0,0.90,0.60],
    spring:10.0, turb:0.10, push:5.0, swirl:2.8, r:0.36, bloom:0.95, vein:0.25, pulse:0.2 },
];
const NC = CH.length;

/* ============================ GL bootstrap ============================ */
interface FBO { tex: WebGLTexture; fb: WebGLFramebuffer; w: number; h: number; }
interface Program { p: WebGLProgram; u: Record<string, WebGLUniformLocation | null>; }

export function initCubeField(canvas: HTMLCanvasElement): CubeFieldHandle | null {
  const glContext = canvas.getContext('webgl2', {
    alpha: false, antialias: false, depth: false, stencil: false,
    powerPreference: 'high-performance', preserveDrawingBuffer: false,
  });
  if (!glContext) return null;
  // 捕获收窄后的非空类型：函数声明会提升，TS 不会在其内部保留外部 const 的收窄
  const gl = glContext;

  const extCBF = gl.getExtension('EXT_color_buffer_float');
  const hdrCapable = !!extCBF;
  let HDR = hdrCapable; // tier 0 drops to RGBA8 — half-float linear blending is the priciest thing on mobile GPUs

  function compile(type: number, src: string): WebGLShader {
    const s = gl.createShader(type);
    if (!s) throw new Error('createShader failed');
    gl.shaderSource(s, src.trim());
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      console.error(gl.getShaderInfoLog(s), src.trim().split('\n').map((l, i) => `${i + 1}: ${l}`).join('\n'));
      throw new Error('shader compile failed');
    }
    return s;
  }

  function program(vs: string, fs: string, tfVaryings?: string[]): Program {
    const p = gl.createProgram();
    if (!p) throw new Error('createProgram failed');
    gl.attachShader(p, compile(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
    if (tfVaryings) gl.transformFeedbackVaryings(p, tfVaryings, gl.SEPARATE_ATTRIBS);
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      console.error(gl.getProgramInfoLog(p));
      throw new Error('program link failed');
    }
    const u: Record<string, WebGLUniformLocation | null> = {};
    const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS) as number;
    for (let i = 0; i < n; i++) {
      const info = gl.getActiveUniform(p, i);
      if (!info) continue;
      const name = info.name.replace('[0]', '');
      u[name] = gl.getUniformLocation(p, info.name);
    }
    return { p, u };
  }

  function makeFBO(w: number, h: number, filter: number): FBO {
    const tex = gl.createTexture();
    if (!tex) throw new Error('createTexture failed');
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, HDR ? gl.RGBA16F : gl.RGBA8, w, h, 0, gl.RGBA, HDR ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fb = gl.createFramebuffer();
    if (!fb) throw new Error('createFramebuffer failed');
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return { tex, fb, w, h };
  }

  /* ============================ shaders ============================ */
  const NOISE = `
float hash21(vec2 p){ return fract(sin(dot(p, vec2(127.1,311.7))) * 43758.5453123); }
float vnoise(vec2 p){
  vec2 i = floor(p), f = fract(p);
  vec2 u = f*f*(3.0-2.0*f);
  float a = hash21(i), b = hash21(i+vec2(1.0,0.0)), c = hash21(i+vec2(0.0,1.0)), d = hash21(i+vec2(1.0,1.0));
  return mix(mix(a,b,u.x), mix(c,d,u.x), u.y);
}
float fbm(vec2 p){
  float v = 0.0, a = 0.5;
  mat2 R = mat2(0.8,-0.6,0.6,0.8);
  for(int i=0;i<4;i++){ v += a * vnoise(p); p = R * p * 2.03 + vec2(11.7, 7.3); a *= 0.5; }
  return v;
}`;

  /* --- simulation (transform feedback) --- */
  const SIM_VS = `#version 300 es
precision highp float;
layout(location=0) in vec2 a_pos;
layout(location=1) in vec2 a_vel;
layout(location=2) in float a_seed;
layout(location=3) in vec2 a_homeA;
layout(location=4) in vec2 a_homeB;
layout(location=5) in float a_depth;
layout(location=6) in float a_feature;

uniform float u_dt, u_time, u_morph, u_spring, u_turb, u_push, u_swirl, u_cursorR, u_shock, u_homeScale, u_ghostW, u_settle, u_depthFx, u_motionScale, u_blink, u_jitterScale;
uniform vec2 u_cursor, u_shockPos, u_homeShift, u_ghost, u_eyeGaze;

out vec2 v_pos;
out vec2 v_vel;

${NOISE}

vec2 curl2(vec2 p, float t){
  float e = 0.35;
  float s = 0.9;
  vec2 dr = vec2(t*0.11, t*0.07);
  float t1 = vnoise(p*s + dr + vec2(0.0, e));
  float t2 = vnoise(p*s + dr - vec2(0.0, e));
  float t3 = vnoise(p*s + dr + vec2(e, 0.0));
  float t4 = vnoise(p*s + dr - vec2(e, 0.0));
  return vec2(t1 - t2, -(t3 - t4)) / (2.0*e);
}

void main(){
  float amb = step(0.9, a_seed);
  // staggered morph — particles ripple into new form
  float m = clamp(u_morph * 1.55 - fract(a_seed*7.31) * 0.55, 0.0, 1.0);
  m = m*m*(3.0-2.0*m);
  vec2 home = mix(a_homeA, a_homeB, m);
  // 全息侧脸微透视 + 眼睛注视 + 眨眼（feature>=0 的眼睛粒子参与）
  if (u_depthFx > 0.001) {
    float perspective = 1.0 / (1.0 - a_depth * 0.26);
    vec2 center = vec2(${FACE_LAYOUT.centerX}, ${FACE_LAYOUT.centerY});
    vec2 local = (home - center) * perspective;
    local += u_eyeGaze * step(-0.5, a_feature) * u_depthFx;
    float eyeCy = ${FACE_LAYOUT.centerY} + ${FACE_LAYOUT.eyeY};
    float isEye = step(-0.5, a_feature);
    float eyeLocalY = local.y - (eyeCy - ${FACE_LAYOUT.centerY});
    eyeLocalY = mix(eyeLocalY, eyeLocalY * 0.08, u_blink * isEye);
    local.y = eyeLocalY + (eyeCy - ${FACE_LAYOUT.centerY});
    home = center + local;
  }
  home = home * u_homeScale + u_homeShift; // ambient homes are already wide

  vec2 f = vec2(0.0);
  f += (home - a_pos) * u_spring * mix(1.0, 0.22, amb);

  // organic wander via curl of evolving noise field — must stay WELL below
  // spring force at rest, else the formation smears into fog
  vec2 flow = curl2(a_pos + a_seed*13.7, u_time + a_seed*4.0);
  f += flow * u_turb * mix(1.0, 2.6, amb) * (0.55 + 0.9*fract(a_seed*3.7)) * 2.4 * u_motionScale;

  // cursor: liquid repulsion + swirl
  vec2 d = a_pos - u_cursor;
  float r = length(d) + 1e-5;
  float R = u_cursorR;
  if (u_motionScale > 0.001 && r < R){
    float k = 1.0 - r/R;
    f += (d/r) * k*k * u_push * 26.0;
    f += vec2(-d.y, d.x)/r * k * u_swirl * 9.0;
  }

  // memory ghost attraction
  if (u_motionScale > 0.001 && u_ghostW > 0.001){
    vec2 g = u_ghost - a_pos;
    float rg = length(g) + 1e-5;
    f += (g/rg) * u_ghostW * 34.0 * exp(-rg*3.4);
  }

  // click shockwave
  vec2 sd = a_pos - u_shockPos;
  float sr = length(sd) + 1e-5;
  f += (sd/sr) * u_shock * 200.0 * exp(-sr*4.2) * u_motionScale;

  vec2 vel = a_vel * u_motionScale + f * u_dt;
  vel *= pow(0.885, u_dt * 60.0);                       // damping
  float sp = length(vel);
  if (sp > 3.4) vel = vel / sp * 3.4;                    // speed clamp
  // tiny ambient jitter keeps the body shimmering even at rest
  vel += curl2(a_pos*0.7 - u_time*0.3, u_time*1.7) * 0.012 * (1.0 - amb*0.5) * u_motionScale * u_jitterScale;

  v_pos = a_pos + vel * u_dt;
  v_vel = vel;
  gl_PointSize = 1.0;
  gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
}`;

  const DUMMY_FS = `#version 300 es
precision mediump float;
out vec4 o;
void main(){ o = vec4(0.0); }`;

  /* --- particle rendering into trail buffer --- */
  const PTS_VS = `#version 300 es
precision highp float;
layout(location=0) in vec2 a_pos;
layout(location=1) in vec2 a_vel;
layout(location=2) in float a_seed;
layout(location=5) in float a_depth;
layout(location=6) in float a_feature;

uniform float u_aspect, u_beat, u_px, u_dim, u_settle, u_depthFx, u_textFx, u_scanX, u_scanStrength;
uniform vec3 u_colA, u_colB;
out vec3 v_col;
out float v_a;

void main(){
  float amb = step(0.9, a_seed);
  float sp = clamp(length(a_vel) * 1.6 + fract(a_seed*5.13)*0.18, 0.0, 1.0);
  v_col = mix(u_colA, u_colB, clamp(sp * 1.5, 0.0, 1.0));
  float radial = 1.0 - 0.30 * smoothstep(0.62, 1.15, length(a_pos));
  float depthN = clamp(a_depth / 0.34 + 0.5, 0.0, 1.0);
  float depthLight = mix(1.0, mix(0.52, 1.55, depthN), u_depthFx);
  float scan = exp(-pow((a_pos.x - u_scanX) * 18.0, 2.0)) * u_scanStrength * max(u_textFx, u_depthFx);
  float body = 1.0 - amb;
  float neural = u_depthFx * body;
  float wire = smoothstep(0.68, 0.78, depthN) * (1.0 - smoothstep(0.9, 0.98, depthN));
  float spark = smoothstep(0.58, 0.66, depthN) * (1.0 - smoothstep(0.7, 0.78, depthN)) * step(0.78, fract(a_seed * 7.13));
  float eye = step(2.5, a_feature) * (1.0 - step(5.5, a_feature));
  v_col *= (1.02 + 0.32*sp + 0.46*u_beat) * mix(1.0, 0.24, amb) * radial * u_dim * depthLight;
  v_col = mix(v_col, vec3(0.22, 0.58, 1.0) * u_dim * 1.45, neural * 0.38);
  v_col = mix(v_col, vec3(0.62, 0.9, 1.0) * u_dim * 2.6, neural * wire * 0.9);
  v_col = mix(v_col, vec3(0.82, 0.97, 1.0) * u_dim * 3.1, spark * (0.45 + 0.55 * u_beat));
  v_col = mix(v_col, vec3(0.78, 0.95, 1.0) * u_dim * 2.8, eye * u_depthFx);
  v_col = mix(v_col, vec3(0.48, 0.94, 1.0) * u_dim * 2.2, scan * 0.78);
  v_a = mix(1.0, 0.82, sp) * mix(1.0, 0.52, amb);
  gl_Position = vec4(a_pos.x / u_aspect, a_pos.y, 0.0, 1.0);
  float depthSize = mix(1.0, mix(0.78, 1.18, depthN), u_depthFx);
  float faceSize = 1.0 + neural * wire * 0.22 + u_beat * neural * 0.18 + eye * 0.35;
  gl_PointSize = mix(1.35, 2.05, sp) * mix(1.0, 0.62, amb) * u_px * depthSize * (faceSize + scan * 0.42);
}`;

  const PTS_FS = `#version 300 es
precision mediump float;
in vec3 v_col;
in float v_a;
out vec4 o;
void main(){
  vec2 q = gl_PointCoord - 0.5;
  float d = length(q);
  float fall = smoothstep(0.5, 0.06, d);
  float core = smoothstep(0.16, 0.0, d) * 0.85;
  o = vec4(v_col * (fall + core), fall * v_a);
}`;

  /* --- flat quad (fade pass) --- */
  const FLAT_VS = `#version 300 es
precision mediump float;
out vec2 v_uv;
void main(){
  vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
  v_uv = p * 0.5 + 0.5;
  gl_Position = vec4(p, 0.0, 1.0);
}`;
  const FLAT_FS = `#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 o;
void main(){ o = u_color; }`;

  /* --- the flesh: domain-warped fbm organism skin --- */
  const FLESH_FS = `#version 300 es
precision highp float;
in vec2 v_uv;
uniform float u_time, u_aspect, u_cursorGlow, u_vein, u_beatEnv, u_beatT, u_pulse, u_flash, u_quality;
uniform vec2 u_cursor;
uniform vec3 u_bg, u_tintA, u_tintB, u_glow;
out vec4 o;

${NOISE}

void main(){
  vec2 p = (v_uv * 2.0 - 1.0) * vec2(u_aspect, 1.0);
  float t = u_time * 0.05;
  vec2 q = vec2(fbm(p*1.1 + t), fbm(p*1.1 + vec2(5.2,1.3) - t));
  vec2 r = vec2(fbm(p*1.7 + 2.2*q + vec2(1.7,9.2) + t*0.6), fbm(p*1.7 + 2.2*q + vec2(8.3,2.8) - t*0.4));
  float v = fbm(p*1.9 + 2.4*r);
  float depth = clamp(length(r)*0.75, 0.0, 1.0);

  vec3 col = u_bg * 1.1;
  col += u_tintB * v * v * 0.65;
  col += u_tintA * pow(v, 3.0) * 0.85;

  // capillary veins — ridged noise filaments
  float vein = abs(fbm(r*3.4 + q*1.2 - t*1.4) - 0.5);
  col += u_tintA * smoothstep(0.055, 0.0, vein) * u_vein * (0.35 + 0.4*v);

  // it leans toward your light
  float d = distance(p, u_cursor);
  col += u_glow * exp(-d*d*7.0) * u_cursorGlow * 0.34;
  col += u_glow * exp(-d*30.0) * u_cursorGlow * 0.10;

  // heartbeat ripples
  if (u_pulse > 0.01){
    float ring = sin(d*16.0 - u_beatT*7.5) * exp(-d*1.9) * exp(-u_beatT*1.1);
    col += u_glow * max(ring, 0.0) * u_pulse * u_beatEnv * 0.9;
  }

  col *= 1.0 + u_flash * 1.6;
  o = vec4(col * (u_quality > 0.5 ? 1.0 : 0.92), 1.0);
}`;

  /* --- bloom: bright pass + separable blur --- */
  const BRIGHT_FS = `#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_threshold;
out vec4 o;
void main(){
  vec3 c = texture(u_tex, v_uv).rgb;
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  float k = max(l - u_threshold, 0.0) / max(l, 1e-4);
  o = vec4(c * k, 1.0);
}`;
  const BLUR_FS = `#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_dir;
out vec4 o;
void main(){
  float w[5];
  w[0]=0.227027; w[1]=0.1945946; w[2]=0.1216216; w[3]=0.054054; w[4]=0.016216;
  vec3 c = texture(u_tex, v_uv).rgb * w[0];
  for(int i=1;i<5;i++){
    c += texture(u_tex, v_uv + u_dir * float(i)).rgb * w[i];
    c += texture(u_tex, v_uv - u_dir * float(i)).rgb * w[i];
  }
  o = vec4(c, 1.0);
}`;

  /* --- final composite: flesh + trails + bloom + cursor orb + lens + grain --- */
  const FINAL_FS = `#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_flesh, u_trail, u_bloom;
uniform float u_time, u_aspect, u_beatEnv, u_bloomAmt, u_flash, u_lens, u_orbVis, u_ghostVis, u_wake, u_fleshAmt, u_cheap;
uniform vec2 u_cursor, u_ghost;
uniform vec3 u_glow, u_bg;
out vec4 o;

vec3 aces(vec3 x){
  return clamp((x*(2.51*x + 0.03)) / (x*(2.43*x + 0.59) + 0.14), 0.0, 1.0);
}
float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233))) * 43758.5453); }

void main(){
  vec2 world = (v_uv * 2.0 - 1.0) * vec2(u_aspect, 1.0);
  vec2 d = world - u_cursor;
  float r = length(d);

  // cursor lens — light bends around the attention point
  float lens = exp(-r*r*90.0) * u_lens;
  vec2 suv = v_uv - (d / max(length(vec2(u_aspect,1.0))*0.5, 1e-3)) * lens * -0.016;

  float ca = 0.0; // 色差关闭：密集点云下 CA 会把 R/B 错开、只留 G，整体泛绿；产品背景需要颜色纯净
  vec2 caDir = d * ca;
  vec3 trail;
  if (u_cheap > 0.5){ trail = texture(u_trail, suv).rgb; }
  else {
    trail.r = texture(u_trail, suv + caDir).r;
    trail.g = texture(u_trail, suv).g;
    trail.b = texture(u_trail, suv - caDir).b;
  }

  vec3 flesh = texture(u_flesh, suv).rgb;
  vec3 bloom = texture(u_bloom, suv).rgb;

  vec3 col = mix(u_bg, flesh, u_fleshAmt) + trail * (1.0 + u_beatEnv*0.55 + u_flash*1.8) + bloom * u_bloomAmt;

  // the cursor organism — core + halo + pulse ring
  if (u_orbVis > 0.001){
    float core = exp(-r*r*2600.0) * 1.5;
    float halo = exp(-r*14.0) * 0.16;
    float ringR = 0.055 + u_beatEnv * 0.05;
    float ring = exp(-abs(r - ringR)*90.0) * (0.25 + u_beatEnv*0.9);
    col += u_glow * (core + halo + ring) * u_orbVis;
  }

  // memory ghost — faint double of your attention
  if (u_ghostVis > 0.001){
    vec2 gd = world - u_ghost;
    float gr = length(gd);
    col += u_glow * (exp(-gr*gr*1400.0)*0.9 + exp(-gr*20.0)*0.08) * u_ghostVis;
  }

  // vignette + grain + faint filmic lift
  float vig = 1.0 - 0.42 * smoothstep(0.5, 1.3, length(world) / max(u_aspect, 1.0) * 1.9);
  col *= vig;
  col += (hash(v_uv * (mod(u_time, 100.0)*913.0) ) - 0.5) * 0.030 * (1.0 - u_cheap); // mod: no once-per-second frozen frame; cheap mode: no grain
  col *= mix(0.35, 1.0, u_wake); // asleep behind the veil

  col = aces(col * 1.06);
  col = pow(col, vec3(0.94));
  o = vec4(col, 1.0);
}`;

  /* ============================ GL programs ============================ */
  const pSim = program(SIM_VS, DUMMY_FS, ['v_pos', 'v_vel']);
  const pPts = program(PTS_VS, PTS_FS);
  const pFlat = program(FLAT_VS, FLAT_FS);
  const pFlesh = program(FLAT_VS, FLESH_FS);
  const pBright = program(FLAT_VS, BRIGHT_FS);
  const pBlur = program(FLAT_VS, BLUR_FS);
  const pFinal = program(FLAT_VS, FINAL_FS);

  /* ============================ particles ============================ */
  const MAXP = 16000;
  const AMB_FRAC = 0.15; // 15% 环境星尘点缀
  const QUALITY = [
    { count: 5200, dpr: 0.60, flesh: 0.24, bloomIter: 1 },
    { count: 10500, dpr: 0.85, flesh: 0.50, bloomIter: 1 },
    { count: 16000, dpr: 1.00, flesh: 0.50, bloomIter: 2 },
  ];
  const urlParams = new URLSearchParams(window.location.search);
  const urlQ = urlParams.get('q');
  const freezeQ = urlParams.has('freeze');
  let quality = urlQ !== null ? clamp(parseInt(urlQ, 10) || 0, 0, 2) : (window.matchMedia('(pointer:coarse)').matches ? 0 : (window.devicePixelRatio > 1.6 ? 1 : 2));
  const startQuality = quality;
  let nBody = Math.floor(QUALITY[quality].count * (1 - AMB_FRAC)); // body indices [0, nBody), halo [nBody, count)

  const posA = gl.createBuffer() as WebGLBuffer, posB = gl.createBuffer() as WebGLBuffer;
  const velA = gl.createBuffer() as WebGLBuffer, velB = gl.createBuffer() as WebGLBuffer;
  const seedBuf = gl.createBuffer() as WebGLBuffer;
  const depthBuf = gl.createBuffer() as WebGLBuffer;
  const featureBuf = gl.createBuffer() as WebGLBuffer;
  const titleScatterBuf = gl.createBuffer() as WebGLBuffer;
  const brainHomeBuf = gl.createBuffer() as WebGLBuffer;
  const homeBufs: WebGLBuffer[] = [];
  for (let i = 0; i < NC; i++) homeBufs.push(gl.createBuffer() as WebGLBuffer);

  const seeds = new Float32Array(MAXP);
  function fillSeeds(): void {
    const rng = mulberry32(777);
    for (let i = 0; i < MAXP; i++) {
      const amb = i >= nBody; // halo indices ARE the ambient set — physics and homes agree exactly
      seeds[i] = amb ? 0.9 + rng() * 0.099 : rng() * 0.9;
    }
  }
  fillSeeds();
  function seedPos(i: number, aspect: number): [number, number] {
    const rng = mulberry32(1000 + i);
    return [(rng() * 2 - 1) * aspect, rng() * 2 - 1];
  }
  function seedVel(i: number): [number, number] {
    const rng = mulberry32(5000 + i);
    return [(rng() * 2 - 1) * 0.02, (rng() * 2 - 1) * 0.02];
  }

  const vaoA = gl.createVertexArray() as WebGLVertexArrayObject, vaoB = gl.createVertexArray() as WebGLVertexArrayObject;
  const tfA = gl.createTransformFeedback() as WebGLTransformFeedback, tfB = gl.createTransformFeedback() as WebGLTransformFeedback;

  function attribs(vao: WebGLVertexArrayObject, pos: WebGLBuffer, vel: WebGLBuffer): void {
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, pos);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, vel);
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, seedBuf);
    gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 1, gl.FLOAT, false, 0, 0);
    // homes are re-bound per chapter by setHomeAttribs; start on chapters 0 and 1
    gl.bindBuffer(gl.ARRAY_BUFFER, homeBufs[0]);
    gl.enableVertexAttribArray(3); gl.vertexAttribPointer(3, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, homeBufs[Math.min(1, NC - 1)]);
    gl.enableVertexAttribArray(4); gl.vertexAttribPointer(4, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, depthBuf);
    gl.enableVertexAttribArray(5); gl.vertexAttribPointer(5, 1, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, featureBuf);
    gl.enableVertexAttribArray(6); gl.vertexAttribPointer(6, 1, gl.FLOAT, false, 0, 0);
    gl.bindVertexArray(null);
    gl.bindBuffer(gl.ARRAY_BUFFER, null);
  }
  attribs(vaoA, posA, velA);
  attribs(vaoB, posB, velB);

  function attachTF(tf: WebGLTransformFeedback, pos: WebGLBuffer, vel: WebGLBuffer): void {
    gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, tf);
    gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 0, pos);
    gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 1, vel);
    gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, null);
  }
  attachTF(tfA, posB, velB); // drawing from A writes into B
  attachTF(tfB, posA, velA);

  /* ============================ formations ============================ */
  let aspect = 1.78;
  const formationData: Float32Array[] = []; // Float32Array per chapter
  let titleScatterData = new Float32Array();
  let brainHomeData = new Float32Array();

  function padFormation(pts: number[], targetN: number, rng: () => number, jitter: number): Float32Array {
    // fill exactly targetN points, cycling with jitter when source is smaller
    const out = new Float32Array(targetN * 2);
    const n = pts.length / 2;
    for (let i = 0; i < targetN; i++) {
      const j = i % n;
      out[i * 2] = pts[j * 2] + (rng() * 2 - 1) * jitter;
      out[i * 2 + 1] = pts[j * 2 + 1] + (rng() * 2 - 1) * jitter;
    }
    return out;
  }
  function ambientHomes(targetN: number, rng: () => number, aspect: number): number[] {
    const pts: number[] = [];
    for (let i = 0; i < Math.ceil(targetN * 1.1); i++) {
      // 与主体一致：均匀分布，无分区断层
      const x = (rng() * 2 - 1) * aspect * 0.95;
      const y = (rng() * 2 - 1) * 0.95;
      pts.push(x + (rng() - 0.5) * 0.04, y + (rng() - 0.5) * 0.04);
    }
    return pts;
  }

  /* --- 魔方 (Rubik's cube): 3×3×3 cubies on a surface point cloud,
         isometric projection, seam gaps between cubies. All six faces are
         emitted so the cube reads as a complete solid from any viewing angle;
         wide seams keep the 3×3 grid readable. --- */
  function genCube(n: number, size: number): number[] {
    const rng = mulberry32(606);
    const pts: number[] = [];
    const S = size;
    const cells = 3;
    const cell = (2 * S) / cells;
    const seamW = cell * 0.38; // cubie 间缝隙：立体六面 + 分块清晰
    const ay = 0.62, ax = 0.5; // yaw/pitch：经典三面视角（顶部+右侧可见）
    const cy = Math.cos(ay), sy = Math.sin(ay);
    const cx = Math.cos(ax), sx = Math.sin(ax);
    const inSeam = (t: number): boolean => {
      const s = t + S; // 0..2S
      const k = Math.floor(s / cell);
      const f = s / cell - k;
      const g = seamW / cell;
      return (k > 0 && f < g) || (k < cells - 1 && f > 1 - g);
    };
    // 六个面全部投影：立方体轮廓完整（-x 面在左侧、-y 在下、-z 被遮挡但补全边界）
    const faces: Array<[number, number, number]> = [
      [1, 0, 0], [-1, 0, 0],
      [0, 1, 0], [0, -1, 0],
      [0, 0, 1], [0, 0, -1],
    ];
    let guard = 0;
    while (pts.length < n * 2 && guard++ < n * 20) {
      const face = faces[Math.floor(rng() * faces.length)];
      const u = rng() * 2 * S - S;
      const v = rng() * 2 * S - S;
      if (inSeam(u) || inSeam(v)) continue;
      let x: number, y: number, z: number;
      if (face[0] !== 0) { x = face[0] * S; y = u; z = v; }
      else if (face[1] !== 0) { x = u; y = face[1] * S; z = v; }
      else { x = u; y = v; z = face[2] * S; }
      x += (rng() - 0.5) * 0.004;
      y += (rng() - 0.5) * 0.004;
      z += (rng() - 0.5) * 0.004;
      // rotate around Y then X, orthographic projection
      const x1 = x * cy + z * sy;
      const z1 = -x * sy + z * cy;
      const y1 = y * cx - z1 * sx;
      pts.push(x1, y1);
    }
    return pts;
  }

  /* --- become: cube at the heart + rays + halo (chapter 5) --- */
  function genCubeRays(n: number): number[] {
    const rng = mulberry32(505);
    const pts: number[] = [];
    const cubeN = Math.floor(n * (n < 15000 ? 0.80 : 0.72));
    const cube = genCube(cubeN, 0.38);
    for (let i = 0; i < cube.length; i++) pts.push(cube[i]);
    // radiance — rays kept OUT of the cube belt so the cube owns the centre
    const rayN = n - cubeN - Math.floor(n * 0.05);
    const rays = 26;
    for (let k = 0; k < rays; k++) {
      const a0 = (k / rays) * Math.PI * 2 + 0.12;
      for (let i = 0; i < Math.floor(rayN / rays); i++) {
        const t = Math.pow(rng(), 0.6);
        const r = 0.60 + t * 0.48;
        const a = a0 + (rng() - 0.5) * (0.10 + (1 - t) * 0.22);
        pts.push(Math.cos(a) * r, Math.sin(a) * r);
      }
    }
    // halo
    for (let i = 0; i < Math.floor(n * 0.05); i++) {
      const a = rng() * Math.PI * 2;
      pts.push(Math.cos(a) * 0.98, Math.sin(a) * 0.98);
    }
    return pts;
  }

  function genIris(n: number): number[] {
    const pts: number[] = [];
    const rng = mulberry32(101);
    const push = (x: number, y: number): void => { pts.push(x, y); };
    // sclera ring
    for (let i = 0; i < n * 0.26; i++) { const a = rng() * Math.PI * 2; push(Math.cos(a) * 0.60 + (rng() - 0.5) * 0.02, Math.sin(a) * 0.60 + (rng() - 0.5) * 0.02); }
    // iris — dashed radial fibres
    for (let i = 0; i < n * 0.42; i++) {
      const a = rng() * Math.PI * 2;
      const r = 0.30 + rng() * 0.10;
      push(Math.cos(a) * r + (rng() - 0.5) * 0.012, Math.sin(a) * r + (rng() - 0.5) * 0.012);
    }
    // limbal accent ring
    for (let i = 0; i < n * 0.06; i++) { const a = rng() * Math.PI * 2; push(Math.cos(a) * 0.42, Math.sin(a) * 0.42); }
    // pupil
    for (let i = 0; i < n * 0.14; i++) { const a = rng() * Math.PI * 2, r = Math.sqrt(rng()) * 0.10; push(Math.cos(a) * r, Math.sin(a) * r); }
    // ciliary lashes
    for (let k = 0; k < 9; k++) {
      const a0 = (k / 9) * Math.PI * 2 + 0.3;
      for (let i = 0; i < n * 0.012; i++) {
        const t = rng();
        const r = 0.63 + t * 0.17;
        const a = a0 + (rng() - 0.5) * 0.14;
        push(Math.cos(a) * r, Math.sin(a) * r);
      }
    }
    return pts;
  }

  function genNeuron(n: number): number[] {
    const pts: number[] = [];
    const rng = mulberry32(202);
    // soma
    for (let i = 0; i < n * 0.06; i++) { const a = rng() * Math.PI * 2, r = Math.sqrt(rng()) * 0.075; pts.push(Math.cos(a) * r, Math.sin(a) * r); }
    // dendrites — random-walk with branching, normalized to fit
    interface Branch { x: number; y: number; dx: number; dy: number; steps: number; depth: number; }
    const stack: Branch[] = [];
    for (let k = 0; k < 8; k++) {
      const a = (k / 8) * Math.PI * 2 + rng() * 0.5;
      stack.push({ x: 0, y: 0, dx: Math.cos(a), dy: Math.sin(a), steps: 150, depth: 0 });
    }
    let guard = 0;
    while (stack.length && guard++ < 6000) {
      const b = stack.pop();
      if (!b) break;
      for (let s = 0; s < b.steps; s++) {
        b.x += b.dx * 0.0095; b.y += b.dy * 0.0095;
        const rot = (rng() - 0.5) * 0.34;
        const cos = Math.cos(rot), sin = Math.sin(rot);
        const ndx = b.dx * cos - b.dy * sin, ndy = b.dx * sin + b.dy * cos;
        b.dx = ndx; b.dy = ndy;
        pts.push(b.x + (rng() - 0.5) * 0.010, b.y + (rng() - 0.5) * 0.010);
        if (rng() < 0.055 && b.depth < 3) {
          const rot2 = (rng() < 0.5 ? -1 : 1) * (0.5 + rng() * 0.4);
          const c2 = Math.cos(rot2), s2 = Math.sin(rot2);
          stack.push({ x: b.x, y: b.y, dx: b.dx * c2 - b.dy * s2, dy: b.dx * s2 + b.dy * c2, steps: Math.floor(b.steps * 0.55), depth: b.depth + 1 });
        }
        if (Math.hypot(b.x, b.y) > 0.88) break;
      }
    }
    return pts;
  }

  function genRings(n: number): number[] {
    const pts: number[] = [];
    const rng = mulberry32(303);
    const radii = [0.15, 0.28, 0.41, 0.54, 0.67, 0.80];
    const weights = [1.4, 1.5, 1.6, 1.6, 1.5, 1.4];
    const total = weights.reduce((a, b) => a + b, 0);
    radii.forEach((R, i) => {
      const cnt = Math.floor(n * weights[i] / total);
      for (let k = 0; k < cnt; k++) {
        const a = rng() * Math.PI * 2;
        const r = R + (rng() - 0.5) * 0.014;
        pts.push(Math.cos(a) * r, Math.sin(a) * r);
      }
    });
    return pts;
  }

  function genSpiral(n: number): number[] {
    const pts: number[] = [];
    const rng = mulberry32(404);
    // core
    for (let i = 0; i < n * 0.12; i++) { const a = rng() * Math.PI * 2, r = Math.sqrt(rng()) * 0.11; pts.push(Math.cos(a) * r, Math.sin(a) * r); }
    // two arms
    const armN = Math.floor(n * 0.44);
    for (let arm = 0; arm < 2; arm++) {
      for (let i = 0; i < armN; i++) {
        const t = Math.pow(rng(), 0.72);
        const r = 0.10 + t * 0.78;
        const a = arm * Math.PI + t * 4.7 + (rng() - 0.5) * (0.55 - t * 0.3);
        pts.push(Math.cos(a) * r + (rng() - 0.5) * 0.02, Math.sin(a) * r + (rng() - 0.5) * 0.02);
      }
    }
    return pts;
  }

  /* --- 星尘 (Stardust): 均匀散布的细腻背景粒子。
        全屏均匀分布（不做分区），亮度低、点小，作为柔和背景氛围，
        不形成明显图案、不遮挡内容文字。 --- */
  function genStardust(n: number, aspect: number): number[] {
    const rng = mulberry32(707);
    const pts: number[] = [];
    for (let i = 0; i < n; i++) {
      // 均匀随机分布：覆盖整个画面（含中间），无分区断层
      const x = (rng() * 2 - 1) * aspect * 0.95;
      const y = (rng() * 2 - 1) * 0.95;
      pts.push(x + (rng() - 0.5) * 0.02, y + (rng() - 0.5) * 0.02);
    }
    return pts;
  }

  function genParticleTitle(n: number): { positions: Float32Array; depth: Float32Array } {
    const titleCanvas = document.createElement('canvas');
    titleCanvas.width = 1400;
    titleCanvas.height = 320;
    const context = titleCanvas.getContext('2d', { willReadFrequently: true });
    if (!context) {
      return {
        positions: new Float32Array(genStardust(n, Math.min(aspect, 0.72))),
        depth: new Float32Array(n),
      };
    }

    context.clearRect(0, 0, titleCanvas.width, titleCanvas.height);
    context.fillStyle = '#fff';
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.font = '900 238px "HarmonyOS Sans SC", MiSans, "Source Han Sans SC", "Noto Sans CJK SC", "PingFang SC", "Microsoft YaHei", sans-serif';
    context.fillText('数智大脑', titleCanvas.width / 2, titleCanvas.height / 2 + 5);

    const rgba = context.getImageData(0, 0, titleCanvas.width, titleCanvas.height).data;
    const alpha = new Uint8ClampedArray(titleCanvas.width * titleCanvas.height);
    for (let index = 0; index < alpha.length; index++) alpha[index] = rgba[index * 4 + 3];
    return generateTextFormationFromMask(alpha, titleCanvas.width, titleCanvas.height, n, aspect, 0x5a17d4, titleAnchorY);
  }

  function genTitleScatter(n: number): number[] {
    const random = mulberry32(0x51a77e);
    const points: number[] = [];
    for (let index = 0; index < n; index++) {
      const angle = random() * Math.PI * 2;
      const radius = Math.pow(random(), 0.62);
      const ripple = Math.sin(angle * 4 + radius * 13) * 0.035;
      points.push(
        Math.cos(angle) * radius * 0.72 + (random() - 0.5) * 0.05,
        titleAnchorY + Math.sin(angle) * (radius + ripple) * 0.26 + (random() - 0.5) * 0.035,
      );
    }
    return points;
  }

  // 数智大脑标题成形的垂直锚点（NDC y）。默认 0.495 为历史兜底；
  // 首页存在 .home-particle-title-space 占位时，实测其中心使粒子落在
  // 「N 个平台已上线」与「面向医院管理者…」两段描述的正中。
  let titleAnchorY = 0.495;

  function measureTitleAnchor(): boolean {
    const el = document.querySelector('.home-hero-stage');
    if (!el) return false;
    const rect = el.getBoundingClientRect();
    if (!rect.height) return false;
    const centerPx = rect.top + rect.height / 2;
    const next = clamp(1 - 2 * (centerPx / window.innerHeight), -0.9, 0.9);
    if (Math.abs(next - titleAnchorY) < 0.01) return false;
    titleAnchorY = next;
    return true;
  }

  function buildFormations(): void {
    const count = QUALITY[quality].count;
    const n = nBody = Math.floor(count * (1 - AMB_FRAC));
    const particleTitle = genParticleTitle(n);
    const gens: Array<() => number[]> = [
      () => genStardust(n, aspect),
      () => genIris(n),
      () => Array.from(particleTitle.positions),
      () => genRings(n),
      () => genSpiral(n),
      () => genCubeRays(n),
    ];
    const ambPts = new Float32Array(ambientHomes(count, mulberry32(909), aspect));
    for (let c = 0; c < NC; c++) {
      const rng = mulberry32(1000 + c * 17);
      const body = padFormation(gens[c](), n, rng, c === HOME_TEXT_CHAPTER ? 0.0015 : 0.012);
      const full = new Float32Array(count * 2);
      full.set(body, 0);
      full.set(ambPts.subarray(0, (count - n) * 2), n * 2);
      formationData[c] = full;
      gl.bindBuffer(gl.ARRAY_BUFFER, homeBufs[c]);
      gl.bufferData(gl.ARRAY_BUFFER, full, gl.STATIC_DRAW);
    }
    const scatterBody = new Float32Array(genTitleScatter(n));
    titleScatterData = new Float32Array(count * 2);
    titleScatterData.set(scatterBody, 0);
    titleScatterData.set(ambPts.subarray(0, (count - n) * 2), n * 2);
    gl.bindBuffer(gl.ARRAY_BUFFER, titleScatterBuf);
    gl.bufferData(gl.ARRAY_BUFFER, titleScatterData, gl.STATIC_DRAW);
    const face = generateEyeFormation(n);
    brainHomeData = new Float32Array(count * 2);
    brainHomeData.set(face.positions, 0);
    brainHomeData.set(ambPts.subarray(0, (count - n) * 2), n * 2);
    gl.bindBuffer(gl.ARRAY_BUFFER, brainHomeBuf);
    gl.bufferData(gl.ARRAY_BUFFER, brainHomeData, gl.STATIC_DRAW);
    const depth = new Float32Array(count);
    depth.set(face.depth, 0);
    const depthRng = mulberry32(1212);
    for (let i = n; i < count; i++) depth[i] = (depthRng() * 2 - 1) * 0.34;
    gl.bindBuffer(gl.ARRAY_BUFFER, depthBuf);
    gl.bufferData(gl.ARRAY_BUFFER, depth, gl.STATIC_DRAW);
    const features = new Float32Array(count);
    features.set(face.features, 0);
    for (let i = n; i < count; i++) features[i] = -1;
    gl.bindBuffer(gl.ARRAY_BUFFER, featureBuf);
    gl.bufferData(gl.ARRAY_BUFFER, features, gl.STATIC_DRAW);
    gl.bindBuffer(gl.ARRAY_BUFFER, null);
  }
  buildFormations();

  /* ============================ FBOs ============================ */
  let W = 2, H = 2, dpr = 1;
  let trailFBO: FBO, fleshFBO: FBO, bloomA: FBO, bloomB: FBO;

  function targetSize(): { w: number; h: number } {
    const q = QUALITY[quality];
    dpr = Math.min(window.devicePixelRatio || 1, 2) * q.dpr;
    return {
      w: Math.max(2, Math.round(window.innerWidth * dpr)),
      h: Math.max(2, Math.round(window.innerHeight * dpr)),
    };
  }
  function makeTargets(): void {
    HDR = hdrCapable && quality > 0;
    const t = targetSize();
    W = t.w; H = t.h;
    canvas.width = W; canvas.height = H;
    canvas.style.width = window.innerWidth + 'px';
    canvas.style.height = window.innerHeight + 'px';
    if (trailFBO) { gl.deleteTexture(trailFBO.tex); gl.deleteFramebuffer(trailFBO.fb); }
    if (fleshFBO) { gl.deleteTexture(fleshFBO.tex); gl.deleteFramebuffer(fleshFBO.fb); }
    if (bloomA) { gl.deleteTexture(bloomA.tex); gl.deleteFramebuffer(bloomA.fb); }
    if (bloomB) { gl.deleteTexture(bloomB.tex); gl.deleteFramebuffer(bloomB.fb); }
    trailFBO = makeFBO(W, H, gl.LINEAR);
    const fs = QUALITY[quality].flesh;
    fleshFBO = makeFBO(Math.max(2, Math.round(W * fs)), Math.max(2, Math.round(H * fs)), gl.LINEAR);
    const bw = Math.max(2, Math.round(W * 0.25)), bh = Math.max(2, Math.round(H * 0.25));
    bloomA = makeFBO(bw, bh, gl.LINEAR);
    bloomB = makeFBO(bw, bh, gl.LINEAR);
    gl.bindFramebuffer(gl.FRAMEBUFFER, trailFBO.fb);
    gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }
  function applyQuality(): void { // tier switch: resize targets AND rebuild the swarm at the new density
    makeTargets();
    buildFormations();
    fillSeeds();
    seedParticles(state.homeIntro ? introSeedFormation() : (formationData[state.chTo] ?? formationData[0]));
    if (state.homeIntro) bindIntroHomes(homeIntroAt(state.homeIntroElapsed, RM()));
    else setHomeAttribs(state.chFrom, state.chTo);
  }
  function seedParticles(startFormation = formationData[0]): void {
    const a = aspect;
    const pos = new Float32Array(MAXP * 2), vel = new Float32Array(MAXP * 2);
    for (let i = 0; i < MAXP; i++) {
      const p = seedPos(i, a); const v = seedVel(i);
      if (startFormation && i < nBody) {
        p[0] = startFormation[i * 2];
        p[1] = startFormation[i * 2 + 1];
      }
      pos[i * 2] = p[0]; pos[i * 2 + 1] = p[1];
      vel[i * 2] = v[0]; vel[i * 2 + 1] = v[1];
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, posA); gl.bufferData(gl.ARRAY_BUFFER, pos, gl.DYNAMIC_COPY);
    gl.bindBuffer(gl.ARRAY_BUFFER, posB); gl.bufferData(gl.ARRAY_BUFFER, pos, gl.DYNAMIC_COPY);
    gl.bindBuffer(gl.ARRAY_BUFFER, velA); gl.bufferData(gl.ARRAY_BUFFER, vel, gl.DYNAMIC_COPY);
    gl.bindBuffer(gl.ARRAY_BUFFER, velB); gl.bufferData(gl.ARRAY_BUFFER, vel, gl.DYNAMIC_COPY);
    gl.bindBuffer(gl.ARRAY_BUFFER, seedBuf); gl.bufferData(gl.ARRAY_BUFFER, seeds, gl.STATIC_DRAW);
    gl.bindBuffer(gl.ARRAY_BUFFER, null);
  }

  /* ============================ state ============================ */
  // 背景模式：禁用闲置自走（dream），保证魔方形态稳定呈现
  const DREAM_ENABLED = false;
  const motionMedia = window.matchMedia('(prefers-reduced-motion: reduce)');
  const reducedAtBirth = motionMedia.matches;
  const state = {
    motionFull: !reducedAtBirth,
    time: 0,
    chFrom: 0, chTo: 0, morphT: 1, seam: 0, morph: 0,
    cursor: { x: -9e3, y: -9e3, tx: -9e3, ty: -9e3, vx: 0, vy: 0, speedN: 0, lastMove: 0, seen: false },
    dream: false, dreamT: 0,
    shock: 0, shockPos: [0, 0] as [number, number], flash: 0,
    beatEnv: 0, beatT: 9, nextBeat: 1.2, bpm: 56, // 首次心跳延迟 1.2s：首帧不触发脉冲，避免刷新瞬间闪亮
    turbBoost: 0,
    release: 0,
    mem: { pts: [] as Array<[number, number]>, idx: 0, replay: 0 },
    ghost: [0, 0] as [number, number], ghostW: 0, ghostVis: 0,
    homeShift: [0, 0] as [number, number], homeScale: 1,
    springRamp: 1,
    frameMs: 16, qualityEvents: [] as Array<{ t: number; to: number; fps: number }>,
    homeIntro: false,
    homeIntroElapsed: 0,
    lastIntroBind: '',
    titleScanElapsed: 9,
    clicks: 0,
  };
  const RM = (): boolean => !state.motionFull; // reduced-motion shortcut
  const onReducedMotionChange = (event: MediaQueryListEvent): void => {
    state.motionFull = !event.matches;
    if (event.matches) {
      state.shock = 0;
      state.turbBoost = 0;
      state.release = 0;
      state.ghostW = 0;
      state.ghostVis = 0;
      if (state.chTo === HOME_TEXT_CHAPTER) {
        state.homeIntro = false;
        state.morphT = 1;
        state.chFrom = state.chTo;
        seedParticles(formationData[state.chTo]);
        setHomeAttribs(state.chTo, state.chTo);
      }
    }
  };
  motionMedia.addEventListener('change', onReducedMotionChange);

  /* pointer → world */
  function toWorld(cx: number, cy: number): [number, number] {
    return [((cx / window.innerWidth) * 2 - 1) * aspect, -((cy / window.innerHeight) * 2 - 1)];
  }

  const memPush = (x: number, y: number): void => {
    const m = state.mem, last = m.pts[m.pts.length - 1];
    if (!last || Math.hypot(x - last[0], y - last[1]) > 0.035) {
      m.pts.push([x, y]);
      if (m.pts.length > 700) m.pts.shift();
    }
  };

  /* interactive UI — a tap on these must not feed the swarm */
  const INTERACTIVE_SELECTOR = [
    'button', 'a', 'input', 'textarea', 'select', 'option', 'label',
    '[role="button"]', '[contenteditable]',
    '.mp-ant-btn', '.mp-ant-input', '.mp-ant-select', '.mp-ant-table',
    '.ant-btn', '.ant-input', '.ant-select', '.ant-table',
    '.ant-modal', '.ant-dropdown', '.ant-drawer', '.ant-menu', '.ant-tabs',
    '.entry-card', '.app', '.pal-it',
    '.drag-handle', '.menu-chevron',
  ].join(',');

  let pointerDown = false;
  let downPos: [number, number] | null = null;

  function onMove(cx: number, cy: number): void {
    const w = toWorld(cx, cy);
    if (!state.cursor.seen) {
      // 首次收到真实光标：直接落位到真实坐标，跳过从哨兵值 (-9e3,-9e3)
      // 的缓慢爬升——否则 homeShift 会按巨大偏移把整个形态瞬间拉向左下
      state.cursor.x = w[0]; state.cursor.y = w[1];
    }
    state.cursor.tx = w[0]; state.cursor.ty = w[1];
    state.cursor.lastMove = state.time;
    state.cursor.seen = true; // 收到过真实光标后才允许 homeShift 追踪
    state.dream = false;
    if (!RM()) memPush(w[0], w[1]);
  }

  const onPointerMove = (e: PointerEvent): void => onMove(e.clientX, e.clientY);
  const onPointerDown = (e: PointerEvent): void => {
    pointerDown = true;
    downPos = [e.clientX, e.clientY];
    onMove(e.clientX, e.clientY);
  };
  const onPointerUp = (e: PointerEvent): void => {
    pointerDown = false;
    if (downPos && Math.hypot(e.clientX - downPos[0], e.clientY - downPos[1]) < 12
      && !(e.target instanceof Element && e.target.closest(INTERACTIVE_SELECTOR))) {
      feed(toWorld(e.clientX, e.clientY)); // a tap feeds; a scroll-drag never does
    }
    downPos = null;
  };
  const onPointerCancel = (): void => { pointerDown = false; downPos = null; };
  const onWheel = (e: WheelEvent): void => {
    if (RM()) return;
    state.turbBoost = clamp(state.turbBoost + Math.abs(e.deltaY) * 0.0007, 0, 1.6);
    state.cursor.lastMove = state.time;
  };

  window.addEventListener('pointermove', onPointerMove, { passive: true });
  window.addEventListener('pointerdown', onPointerDown, { passive: true });
  window.addEventListener('pointerup', onPointerUp, { passive: true });
  window.addEventListener('pointercancel', onPointerCancel, { passive: true });
  window.addEventListener('wheel', onWheel, { passive: true });

  function feed(worldPos: [number, number]): void {
    if (RM()) return;
    state.clicks++;
    state.shockPos = worldPos;
    const bch = state.morphT >= 0.5 ? state.chTo : state.chFrom;
    if (bch === 5) {
      if (!RM()) { state.shock = 2.6; state.release = 1; } // transcendence — 保留冲击波，去掉整屏闪亮
    } else {
      state.shock = 1.4;
      if (bch === 3) { state.beatEnv = 1; state.beatT = 0; } // chapter 3 answers the heartbeat
    }
  }

  /* ============================ chapter switching (route-driven) ============================ */
  function pulse(): void {
    if (!RM() && state.chTo === HOME_TEXT_CHAPTER) state.titleScanElapsed = 0;
  }

  function setChapter(i: number): void {
    const target = clamp(i, 0, NC - 1);
    if (target === state.chTo && state.morphT >= 1 && !state.homeIntro) return;
    if (target === HOME_TEXT_CHAPTER) {
      state.chTo = HOME_TEXT_CHAPTER;
      state.titleScanElapsed = 0;
      state.lastIntroBind = '';
      if (RM()) {
        state.homeIntro = false;
        state.homeIntroElapsed = HOME_INTRO.faceGather + HOME_INTRO.faceHold;
        state.chFrom = HOME_TEXT_CHAPTER;
        state.morphT = 1;
        seedParticles(brainHomeData);
        setHomeAttribs(HOME_TEXT_CHAPTER, HOME_TEXT_CHAPTER);
        bindHomeAttribs(brainHomeBuf, brainHomeBuf);
      } else {
        state.homeIntro = true;
        state.homeIntroElapsed = 0;
        state.chFrom = 0;
        state.morphT = 0;
        seedParticles(formationData[0]);
        bindHomeAttribs(homeBufs[0], brainHomeBuf);
      }
      return;
    }
    if (state.homeIntro) {
      const intro = homeIntroAt(state.homeIntroElapsed, RM());
      bindHomeAttribs(introHomeBuf(intro.morphT < 0.5 ? intro.homeA : intro.homeB), homeBufs[target]);
    } else {
      state.chFrom = state.chTo;
      setHomeAttribs(state.chFrom, target);
    }
    state.homeIntro = false;
    state.chTo = target;
    state.morphT = 0;
  }

  /* ============================ resize ============================ */
  function applyAspect(): void {
    const newAspect = window.innerWidth / window.innerHeight;
    const changed = Math.abs(newAspect - aspect) / aspect > 0.12;
    const anchorMoved = measureTitleAnchor();
    aspect = newAspect;
    makeTargets();
    if (changed || anchorMoved) {
      buildFormations();
      seedParticles(state.homeIntro ? introSeedFormation() : (formationData[state.chTo] ?? formationData[0]));
    } // a mere address-bar collapse must not re-scatter the swarm
  }
  let resizeTimer: number | null = null;
  const coarsePointer = window.matchMedia('(pointer:coarse)').matches;
  let lastResizeW = window.innerWidth;
  const onResize = (): void => {
    if (resizeTimer !== null) window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => {
      // iOS toolbar collapse fires resize with a height-only change; the canvas is
      // svh-stable, so reacting would just realloc every FBO mid-scroll — skip it
      if (coarsePointer && window.innerWidth === lastResizeW) return;
      lastResizeW = window.innerWidth;
      applyAspect();
    }, 220);
  };
  window.addEventListener('resize', onResize);

  /* ============================ render helpers ============================ */
  function drawQuad(): void {
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
  function bindTex(unit: number, tex: WebGLTexture, loc: WebGLUniformLocation | null): void {
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.uniform1i(loc, unit);
  }

  function bindHomeAttribs(homeA: WebGLBuffer, homeB: WebGLBuffer): void {
    for (const vao of [vaoA, vaoB]) {
      gl.bindVertexArray(vao);
      gl.bindBuffer(gl.ARRAY_BUFFER, homeA);
      gl.vertexAttribPointer(3, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, homeB);
      gl.vertexAttribPointer(4, 2, gl.FLOAT, false, 0, 0);
    }
    gl.bindVertexArray(null);
    gl.bindBuffer(gl.ARRAY_BUFFER, null);
  }

  function setHomeAttribs(chA: number, chB: number): void {
    bindHomeAttribs(homeBufs[chA], homeBufs[chB]);
  }

  function introHomeBuf(which: HomeIntroHome): WebGLBuffer {
    if (which === 'field') return homeBufs[0];
    return brainHomeBuf; // 'face' 与 'live' 都是人脸缓冲
  }

  function formationForHome(which: HomeIntroHome): Float32Array {
    if (which === 'field') return formationData[0];
    return brainHomeData; // 'face' 与 'live' 都是人脸数据
  }

  function introSeedFormation(): Float32Array {
    const intro = homeIntroAt(state.homeIntroElapsed, RM());
    return formationForHome(intro.morphT < 0.5 ? intro.homeA : intro.homeB);
  }

  function bindIntroHomes(intro: { homeA: HomeIntroHome; homeB: HomeIntroHome }): void {
    const key = `${intro.homeA}:${intro.homeB}`;
    if (key === state.lastIntroBind) return;
    bindHomeAttribs(introHomeBuf(intro.homeA), introHomeBuf(intro.homeB));
    state.lastIntroBind = key;
  }

  /* ============================ main loop ============================ */
  let src = 0; // 0: draw A→B, 1: draw B→A
  let frames = 0, fpsT = 0, fps = 60, adaptT = 0;
  let alive = true;
  let raf = 0;
  let lastT = -1;

  applyAspect();
  seedParticles(); // initial seed — after this, only a real aspect change re-seeds
  setHomeAttribs(0, 0); // 初始 morph=1 → home=homeB，两槽都绑魔方，避免收敛到虹膜
  raf = requestAnimationFrame(frame);

  function frame(now: number): void {
    if (!alive) return;
    raf = requestAnimationFrame(frame);
    if (lastT < 0) { lastT = now; return; }
    const t0 = performance.now();
    let dt = (now - lastT) / 1000;
    lastT = now;
    if (dt > 0.07) dt = 0.07; // bounds a background-tab catapult; substeps keep this real-time down to ~14 fps
    if (dt <= 0) return;
    state.time += dt;
    const motionProfile = particleMotionProfile(RM());

    /* ---- route-driven chapter morph ---- */
    let intro = state.homeIntro ? homeIntroAt(state.homeIntroElapsed, RM()) : null;
    if (state.homeIntro) {
      state.homeIntroElapsed += dt;
      intro = homeIntroAt(state.homeIntroElapsed, RM());
      state.morphT = intro.morphT;
      bindIntroHomes(intro);
      if (intro.phase !== 'gather') state.chFrom = HOME_TEXT_CHAPTER;
      if (intro.phase === 'pulse') state.turbBoost = Math.max(state.turbBoost, 0.35 * intro.pulse);
      // 人脸显化完成 → 常驻（done 时两个 home 槽都固定绑人脸缓冲）
      if (intro.done) {
        state.homeIntro = false;
        state.chFrom = state.chTo;
        setHomeAttribs(state.chTo, state.chTo);
        bindHomeAttribs(brainHomeBuf, brainHomeBuf);
      }
    } else if (state.morphT < 1) {
      state.morphT = Math.min(1, state.morphT + dt / 1.4);
    }
    if (!state.homeIntro && state.morphT >= 1 && state.chFrom !== state.chTo) {
      state.chFrom = state.chTo;
      setHomeAttribs(state.chTo, state.chTo);
    }
    const seam = smooth(state.morphT);
    state.seam = seam;
    state.morph = seam;
    const ch = seam >= 0.5 ? state.chTo : state.chFrom;

    /* ---- palette crossfade ---- */
    const cA = CH[state.chFrom], cB = CH[state.chTo];
    const mixv = (a: number, b: number): number => a + (b - a) * seam;
    const bg = cA.bg.map((v, i) => mixv(v, cB.bg[i]));
    const fleshA = cA.fleshA.map((v, i) => mixv(v, cB.fleshA[i]));
    const fleshB = cA.fleshB.map((v, i) => mixv(v, cB.fleshB[i]));
    const colA = cA.colA.map((v, i) => mixv(v, cB.colA[i]));
    const colB = cA.colB.map((v, i) => mixv(v, cB.colB[i]));
    const glow = cA.glow.map((v, i) => mixv(v, cB.glow[i]));
    const spring = mixv(cA.spring, cB.spring);
    let turb = mixv(cA.turb, cB.turb);
    const push = mixv(cA.push, cB.push);
    const swirl = mixv(cA.swirl, cB.swirl);
    const cursorR = mixv(cA.r, cB.r);
    const bloomAmt = mixv(cA.bloom, cB.bloom);
    const vein = mixv(cA.vein, cB.vein);
    const pulse = mixv(cA.pulse, cB.pulse);
    // 人脸常驻：首页 ch2 的粒子颜色整体用智脑调色板（原文字调色板通道复用）
    const textFx = intro
      ? intro.textFx
      : mixv(
          state.chFrom === HOME_TEXT_CHAPTER ? 1 : 0,
          state.chTo === HOME_TEXT_CHAPTER ? 1 : 0,
        );
    const eyeFx = intro ? intro.brainFx : 0;
    // 神经脉冲：人脸显化期 3 波放电，经心跳 u_beat 通道放大 bloom 发光
    const neuralPulse = intro ? intro.pulse : 0;
    const titleMotionProfile = particleTitleMotionProfile(Math.max(textFx, eyeFx), RM());
    const theme = document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
    const titlePalette = particleTitlePaletteForTheme(theme);
    const facePalette = eyePaletteForTheme(theme);
    const particleColA = colA.map((value, index) => lerp(facePalette.sclera[index], titlePalette.bodyA[index], textFx));
    const particleColB = colB.map((value, index) => lerp(facePalette.pupil[index], titlePalette.bodyB[index], textFx));

    /* ---- turbulence & energy ---- */
    state.turbBoost = expDamp(state.turbBoost, 0, 1.1, dt);
    const cursorSpeedRaw = Math.hypot(state.cursor.vx, state.cursor.vy);
    state.cursor.speedN = expDamp(state.cursor.speedN, clamp(cursorSpeedRaw / 1.4, 0, 1), 4, dt);
    const energy = clamp(state.turbBoost * 0.8 + state.cursor.speedN * 0.5, 0, 1.4);
    if (!RM()) turb = Math.min(turb * (1 + energy * 1.6), 1.3) * titleMotionProfile.jitterScale; else turb = 0;

    // ch5 release (transcendence)
    if (state.release > 0) state.release = Math.max(0, state.release - dt * 0.28);
    const springEff = spring * state.springRamp * (1 - state.release * 0.82)
      * (intro?.phase === 'gather' ? 0.55 : 1); // 聚合期弹簧略软，便于粒子舒展成形
    turb += state.release * 1.1;

    /* ---- cursor / ghost ---- */
    const idle = state.time - state.cursor.lastMove;
    // dream 模式已关闭：作为项目背景，魔方形态必须稳定呈现，幻影光标会持续扰动形态
    if (DREAM_ENABLED && idle > 4.5 && !RM()) {
      state.dream = true; state.dreamT += dt;
    }
    let cx: number, cy: number;
    if (state.dream) {
      const t = state.dreamT;
      cx = Math.sin(t * 0.33 + 1.3) * aspect * 0.5 + Math.sin(t * 0.71) * 0.2;
      cy = Math.sin(t * 0.21 + 4.2) * 0.55 + Math.cos(t * 0.53) * 0.15;
      state.cursor.tx = cx; state.cursor.ty = cy;
    }
    const prevCx = state.cursor.x, prevCy = state.cursor.y;
    state.cursor.x = expDamp(state.cursor.x, state.cursor.tx, RM() ? 22 : 13, dt);
    state.cursor.y = expDamp(state.cursor.y, state.cursor.ty, RM() ? 22 : 13, dt);
    state.cursor.vx = (state.cursor.x - prevCx) / dt;
    state.cursor.vy = (state.cursor.y - prevCy) / dt;

    // ch1: the iris tracks you. other chapters: slight parallax.
    // 仅在收到真实光标后追踪：刷新后光标处于哨兵值 (-9e3,-9e3)，
    // 若直接按它计算会把整个形态平移出视野
    const trackW = (ch === 1 ? 0.16 : ch === HOME_TEXT_CHAPTER ? 0 : 0.035)
      * (state.cursor.seen ? 1 : 0) * motionProfile.simulationScale;
    const shiftScale = ch === 1 ? clamp(1 - seam, 0, 1) : 1;
    const titleIdle = particleTitleIdleMotion(state.time * motionProfile.visualTimeScale, RM());
    const titleLive = !state.homeIntro && ch === HOME_TEXT_CHAPTER ? 1 : 0;
    state.homeShift[0] = expDamp(
      state.homeShift[0],
      state.cursor.x * trackW * shiftScale + titleIdle.swayX * titleLive,
      2.4,
      dt,
    );
    state.homeShift[1] = expDamp(
      state.homeShift[1],
      state.cursor.y * trackW * shiftScale + titleIdle.swayY * titleLive,
      2.4,
      dt,
    );

    // memory ghost — replay when you rest in ch4 (or dreaming elsewhere it fades)
    const wantGhost = (ch === 4 && idle > 1.4 && state.mem.pts.length > 12) ? 1 : 0;
    state.mem.replay = expDamp(state.mem.replay, wantGhost, 1.6, dt);
    if (wantGhost) {
      state.mem.idx = (state.mem.idx + dt * 65) % state.mem.pts.length;
      const p = state.mem.pts[Math.floor(state.mem.idx)];
      state.ghost[0] = expDamp(state.ghost[0], p[0], 10, dt);
      state.ghost[1] = expDamp(state.ghost[1], p[1], 10, dt);
    }
    state.ghostW = state.mem.replay * 1.0;
    state.ghostVis = state.mem.replay * 0.85;

    state.titleScanElapsed += dt;
    const titleMotion = eyeMotionAt({
      pointerX: state.cursor.seen ? clamp(state.cursor.x / Math.max(aspect, 1), -1, 1) : 0,
      pointerY: state.cursor.seen ? clamp(state.cursor.y, -1, 1) : 0,
      scanElapsed: state.titleScanElapsed,
      time: state.time,
      reducedMotion: RM(),
    });

    /* ---- heartbeat ---- */
    state.bpm = expDamp(state.bpm, 56 + clamp(energy * 46, 0, 130), 2, dt);
    const beatInterval = 60 / state.bpm;
    if (state.time >= state.nextBeat) {
      state.nextBeat = state.time + beatInterval;
      state.beatEnv = Math.max(state.beatEnv, 1); state.beatT = 0;
    }
    // 神经脉冲能量经 u_beat 通道叠加：心跳衰减后不覆盖脉冲增强，两者取较大者
    const beatBoost = neuralPulse > 0 ? neuralPulse * 1.35 : 0;
    state.beatEnv = Math.max(state.beatEnv * Math.exp(-dt * 5.2), beatBoost);
    state.beatT += dt;
    state.homeScale = 1
      + state.beatEnv * titleMotionProfile.pulseScale * (0.4 + pulse)
      + titleIdle.breathe * titleLive;
    state.flash *= Math.exp(-dt * 3.4);
    state.shock *= Math.exp(-dt * 2.4);
    state.springRamp = Math.min(1, state.springRamp + dt * 0.9);

    /* ---- GL passes ---- */
    const count = QUALITY[quality].count;

    // 1. simulate (transform feedback) — substepped: below ~45 fps the physics takes
    // several small steps per frame, so motion stays REAL TIME at any frame rate.
    const sub = dt > 0.022 ? Math.min(4, Math.ceil(dt / 0.0167)) : 1;
    const sdt = dt / sub;
    gl.enable(gl.RASTERIZER_DISCARD);
    gl.useProgram(pSim.p);
    const su = pSim.u;
    gl.uniform1f(su.u_morph, state.morph);
    gl.uniform1f(su.u_spring, springEff);
    gl.uniform1f(su.u_turb, turb);
    gl.uniform1f(su.u_push, push * (pointerDown ? 0.4 : 1) * titleMotionProfile.interactionScale);
    gl.uniform1f(su.u_swirl, swirl * titleMotionProfile.interactionScale);
    gl.uniform1f(su.u_cursorR, cursorR);
    gl.uniform1f(su.u_shock, state.shock * titleMotionProfile.interactionScale);
    gl.uniform1f(su.u_homeScale, state.homeScale);
    gl.uniform1f(su.u_ghostW, state.ghostW);
    gl.uniform1f(su.u_settle, 0);
    gl.uniform1f(su.u_depthFx, eyeFx);
    gl.uniform1f(su.u_motionScale, motionProfile.simulationScale);
    gl.uniform1f(su.u_jitterScale, titleMotionProfile.jitterScale);
    // 人脸常驻：眨眼 + 双眼跟随指针（gaze 幅度随显化完成度渐入）
    const gazeOn = eyeFx > 0.6 || (!state.homeIntro && state.chTo === HOME_TEXT_CHAPTER) ? 1 : 0;
    gl.uniform1f(su.u_blink, gazeOn ? titleMotion.blink : 0);
    gl.uniform2f(su.u_cursor, state.cursor.x, state.cursor.y);
    gl.uniform2f(su.u_shockPos, state.shockPos[0], state.shockPos[1]);
    gl.uniform2f(su.u_homeShift, state.homeShift[0], state.homeShift[1]);
    gl.uniform2f(su.u_ghost, state.ghost[0], state.ghost[1]);
    gl.uniform2f(su.u_eyeGaze, gazeOn ? titleMotion.gazeX * eyeFx : 0, gazeOn ? titleMotion.gazeY * eyeFx : 0);
    for (let s = 0; s < sub; s++) {
      gl.uniform1f(su.u_dt, sdt);
      gl.uniform1f(su.u_time, state.time - dt + sdt * (s + 1));
      gl.bindVertexArray(src === 0 ? vaoA : vaoB);
      gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, src === 0 ? tfA : tfB);
      gl.beginTransformFeedback(gl.POINTS);
      gl.drawArrays(gl.POINTS, 0, count);
      gl.endTransformFeedback();
      src = 1 - src; // each substep hands the just-written side to the next
    }
    gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, null);
    gl.disable(gl.RASTERIZER_DISCARD);
    gl.bindVertexArray(null);

    // 2. trail: fade then accumulate points
    gl.bindFramebuffer(gl.FRAMEBUFFER, trailFBO.fb);
    gl.viewport(0, 0, W, H);
    gl.useProgram(pFlat.p);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    const regularFade = quality === 0 ? 0.32 : 0.26;
    const formFx = Math.max(eyeFx, textFx);
    const fadeHi = lerp(0.68, 0.62, textFx);
    const fadeBase = RM() ? 0.46 : lerp(regularFade, fadeHi, formFx);
    gl.uniform4f(pFlat.u.u_color, 0, 0, 0, 1 - Math.pow(1 - fadeBase, dt * 60)); // …scaled so trails persist the same wall-clock time at any frame rate
    drawQuad();
    gl.blendFunc(gl.ONE, gl.ONE);
    gl.useProgram(pPts.p);
    gl.uniform1f(pPts.u.u_aspect, aspect);
    gl.uniform1f(pPts.u.u_beat, RM() ? 0 : state.beatEnv * (0.4 + pulse * 0.6)); // calm = no glow pulse
    gl.uniform1f(pPts.u.u_px, dpr * (quality === 0 ? 1.5 : quality === 1 ? 0.95 : 0.85) * lerp(1, 1.14, textFx));
    gl.uniform1f(
      pPts.u.u_dim,
      lerp(lerp(0.14, 0.32, eyeFx), 0.48 * lerp(1, titlePalette.energy, textFx), textFx),
    );
    gl.uniform1f(pPts.u.u_settle, 0);
    gl.uniform1f(pPts.u.u_depthFx, eyeFx);
    gl.uniform1f(pPts.u.u_textFx, textFx);
    gl.uniform1f(
      pPts.u.u_scanX,
      titleMotion.scanStrength > titleIdle.scanStrength * titleLive ? titleMotion.scanX : titleIdle.scanX,
    );
    gl.uniform1f(pPts.u.u_scanStrength, Math.max(titleMotion.scanStrength, titleIdle.scanStrength * titleLive));
    gl.uniform3f(pPts.u.u_colA, particleColA[0], particleColA[1], particleColA[2]);
    gl.uniform3f(pPts.u.u_colB, particleColB[0], particleColB[1], particleColB[2]);
    gl.bindVertexArray(src === 0 ? vaoA : vaoB); // read what the last substep wrote
    gl.drawArrays(gl.POINTS, 0, count);
    gl.bindVertexArray(null);
    gl.disable(gl.BLEND);
    // src already points at the fresh side — the substep loop flipped it

    // 3. flesh
    gl.bindFramebuffer(gl.FRAMEBUFFER, fleshFBO.fb);
    gl.viewport(0, 0, fleshFBO.w, fleshFBO.h);
    gl.useProgram(pFlesh.p);
    const fu = pFlesh.u;
    gl.uniform1f(fu.u_time, state.time * motionProfile.visualTimeScale);
    gl.uniform1f(fu.u_aspect, aspect);
    gl.uniform1f(fu.u_cursorGlow, (0.35 + state.cursor.speedN * 0.65) * motionProfile.simulationScale);
    gl.uniform1f(fu.u_vein, vein);
    gl.uniform1f(fu.u_beatEnv, state.beatEnv * motionProfile.simulationScale);
    gl.uniform1f(fu.u_beatT, state.beatT * motionProfile.visualTimeScale);
    gl.uniform1f(fu.u_pulse, pulse * motionProfile.simulationScale);
    gl.uniform1f(fu.u_flash, state.flash * motionProfile.simulationScale);
    gl.uniform1f(fu.u_quality, quality);
    gl.uniform2f(fu.u_cursor, state.cursor.x, state.cursor.y);
    gl.uniform3f(fu.u_bg, bg[0], bg[1], bg[2]);
    gl.uniform3f(fu.u_tintA, fleshA[0], fleshA[1], fleshA[2]);
    gl.uniform3f(fu.u_tintB, fleshB[0], fleshB[1], fleshB[2]);
    gl.uniform3f(fu.u_glow, glow[0], glow[1], glow[2]);
    drawQuad();

    // 4. bloom
    gl.bindFramebuffer(gl.FRAMEBUFFER, bloomA.fb);
    gl.viewport(0, 0, bloomA.w, bloomA.h);
    gl.useProgram(pBright.p);
    bindTex(0, trailFBO.tex, pBright.u.u_tex);
    gl.uniform1f(pBright.u.u_threshold, 0.42);
    drawQuad();
    gl.useProgram(pBlur.p);
    const iters = QUALITY[quality].bloomIter;
    for (let i = 0; i < iters; i++) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, bloomB.fb);
      bindTex(0, bloomA.tex, pBlur.u.u_tex);
      gl.uniform2f(pBlur.u.u_dir, 1.4 / bloomA.w, 0);
      drawQuad();
      gl.bindFramebuffer(gl.FRAMEBUFFER, bloomA.fb);
      bindTex(0, bloomB.tex, pBlur.u.u_tex);
      gl.uniform2f(pBlur.u.u_dir, 0, 1.4 / bloomA.h);
      drawQuad();
    }

    // 5. final composite to screen
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    gl.useProgram(pFinal.p);
    const fin = pFinal.u;
    bindTex(0, fleshFBO.tex, fin.u_flesh);
    bindTex(1, trailFBO.tex, fin.u_trail);
    bindTex(2, bloomA.tex, fin.u_bloom);
    gl.uniform1f(fin.u_time, state.time * motionProfile.visualTimeScale);
    gl.uniform1f(fin.u_aspect, aspect);
    gl.uniform1f(fin.u_beatEnv, RM() ? 0 : state.beatEnv * pulse);
    gl.uniform1f(fin.u_bloomAmt, bloomAmt * (0.5 + state.flash * 1.6));
    gl.uniform1f(fin.u_fleshAmt, 0.35); // 肉体层只做淡淡底衬，避免浑浊背景盖住内容
    gl.uniform1f(fin.u_cheap, quality === 0 ? 1 : 0);
    gl.uniform3f(fin.u_bg, bg[0], bg[1], bg[2]);
    gl.uniform1f(fin.u_flash, state.flash);
    gl.uniform1f(fin.u_lens, (0.4 + state.cursor.speedN * 0.6) * motionProfile.simulationScale);
    gl.uniform1f(fin.u_orbVis, RM() ? 0 : 1);
    gl.uniform1f(fin.u_ghostVis, state.ghostVis * motionProfile.simulationScale);
    gl.uniform1f(fin.u_wake, 1);
    gl.uniform2f(fin.u_cursor, state.cursor.x, state.cursor.y);
    gl.uniform2f(fin.u_ghost, state.ghost[0], state.ghost[1]);
    gl.uniform3f(fin.u_glow, glow[0], glow[1], glow[2]);
    drawQuad();

    /* ---- adaptive quality ---- */
    const frameMs = performance.now() - t0;
    state.frameMs = lerp(state.frameMs, frameMs, 0.06);
    frames++; fpsT += dt; adaptT += dt;
    if (fpsT >= 0.5) { fps = frames / fpsT; frames = 0; fpsT = 0; }
    if (adaptT > 2.2 && !freezeQ) {
      adaptT = 0;
      if (fps < 42 && quality > 0) {
        quality--; state.qualityEvents.push({ t: Math.round(state.time), to: quality, fps: Math.round(fps) });
        applyQuality();
      } else if (fps > 56 && quality < startQuality) {
        quality++; state.qualityEvents.push({ t: Math.round(state.time), to: quality, fps: Math.round(fps) });
        applyQuality();
      }
    }
  }

  /* ============================ teardown ============================ */
  function destroy(): void {
    alive = false;
    cancelAnimationFrame(raf);
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerdown', onPointerDown);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerCancel);
    window.removeEventListener('wheel', onWheel);
    window.removeEventListener('resize', onResize);
    motionMedia.removeEventListener('change', onReducedMotionChange);
    if (resizeTimer !== null) window.clearTimeout(resizeTimer);
    const lose = gl.getExtension('WEBGL_lose_context');
    lose?.loseContext();
  }

  function refreshTitleAnchor(): void {
    if (!measureTitleAnchor()) return;
    buildFormations();
    // 开场动画期间不重播（bufferData 已换，后续 morph 自然指向新锚点）；
    // 静止状态下直接落位修正，避免标题停在旧位置。
    if (!state.homeIntro) {
      seedParticles(formationData[state.chTo] ?? formationData[0]);
    }
  }

  return {
    setChapter,
    pulse,
    destroy,
    refreshTitleAnchor,
    debug: () => ({ chFrom: state.chFrom, chTo: state.chTo, morphT: state.morphT, quality }),
  };
}
