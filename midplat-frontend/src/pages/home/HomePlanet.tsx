import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { publicAsset } from '@/publicAsset';
import type { MasterAppItem } from './masterApps';

const GA = Math.PI * (3 - Math.sqrt(5));
const FOV = 1500;

type ShellPoint = { sx: number; sy: number; sz: number };

function shellPoints(count: number): ShellPoint[] {
  if (count <= 0) return [];
  if (count === 1) return [{ sx: 0, sy: 0, sz: 1 }];
  return Array.from({ length: count }, (_, i) => {
    // 偏移半格采样，避免首点落在自转轴北极导致该节点自转时投影不动
    const vy = 1 - ((i + 0.5) / count) * 2;
    const rr = Math.sqrt(Math.max(0, 1 - vy * vy));
    const th = i * GA;
    return { sx: Math.cos(th) * rr, sy: vy, sz: Math.sin(th) * rr };
  });
}

function useThemeDark() {
  const [dark, setDark] = useState(false);
  useEffect(() => {
    const read = () => setDark(document.documentElement.dataset.theme === 'dark');
    read();
    const observer = new MutationObserver(read);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, []);
  return dark;
}

function appLabel(app: MasterAppItem) {
  return app.title || app.name || '应用';
}

/** 把大圆投影点按前后半圈拆成折线段，写入 polyline 池（每圆 4 条，超出截断） */
function writeOrbitSegs(
  pool: Array<SVGPolylineElement | null>,
  segs: Array<{ pts: string[] }>,
) {
  for (let i = 0; i < pool.length; i += 1) {
    const el = pool[i];
    if (!el) continue;
    el.setAttribute('points', segs[i] ? segs[i].pts.join(' ') : '');
  }
}

/** 右上角只展示一颗自转星球，点击后放大成可交互的应用星球。 */
export function HomePlanet({
  apps,
  onOpenApp,
}: {
  apps: MasterAppItem[];
  onOpenApp: (app: MasterAppItem) => void;
}) {
  const dark = useThemeDark();
  const miniRef = useRef<HTMLButtonElement>(null);
  const miniSceneRef = useRef<HTMLSpanElement>(null);
  const miniNodeRefs = useRef<Array<HTMLSpanElement | null>>([]);
  const stageRef = useRef<HTMLDivElement>(null);
  const nodeRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const [open, setOpen] = useState(false);
  const [settled, setSettled] = useState(false);
  const settledRef = useRef(false);
  const [origin, setOrigin] = useState<{ x: number; y: number; size: number } | null>(null);
  settledRef.current = settled;
  const points = useMemo(() => shellPoints(Math.min(apps.length, 96)), [apps.length]);
  const shown = apps.slice(0, points.length);
  const miniCount = Math.min(24, apps.length);
  const miniPoints = useMemo(() => shellPoints(miniCount), [miniCount]);
  const miniApps = apps.slice(0, miniCount);
  const sphere = publicAsset(dark ? 'media/hero-dark.jpg' : 'media/hero-light.jpg');
  const sphereVideo = publicAsset(dark ? 'media/hero-dark.mp4?v=20260922e' : 'media/hero-light.mp4');

  // 天空星尘（微尘/亮星/星芒三档，种子随机保证每次渲染一致）
  const stars = useMemo(() => {
    let seed = 20260922;
    const rnd = () => {
      seed = (seed * 1103515245 + 12345) % 2147483648;
      return seed / 2147483648;
    };
    return Array.from({ length: 118 }, (_, i) => {
      const kind = i % 19 === 5 ? ' big' : i % 4 === 2 ? ' b' : '';
      const size = kind === ' big' ? 2.6 + rnd() * 0.8 : kind === ' b' ? 1.4 + rnd() * 0.8 : 0.5 + rnd() * 0.7;
      return {
        x: rnd() * 100,
        y: rnd() * 100,
        size,
        o: (0.35 + rnd() * 0.5).toFixed(2),
        delay: (rnd() * 4.5).toFixed(2),
        kind,
      };
    });
  }, []);

  // 3 条大圆轨道（赤道 + 两条倾斜），随球体旋转、前后半圈分层遮挡
  const orbitRings = useMemo(() => {
    const cross = (p: ShellPoint, q: ShellPoint): ShellPoint => ({
      sx: p.sy * q.sz - p.sz * q.sy,
      sy: p.sz * q.sx - p.sx * q.sz,
      sz: p.sx * q.sy - p.sy * q.sx,
    });
    const ring = (nx: number, ny: number, nz: number) => {
      const len = Math.hypot(nx, ny, nz) || 1;
      const n = { sx: nx / len, sy: ny / len, sz: nz / len };
      const a = Math.abs(n.sx) < 0.9 ? { sx: 1, sy: 0, sz: 0 } : { sx: 0, sy: 1, sz: 0 };
      const u = cross(a, n);
      const ul = Math.hypot(u.sx, u.sy, u.sz) || 1;
      const un = { sx: u.sx / ul, sy: u.sy / ul, sz: u.sz / ul };
      return { u: un, v: cross(n, un) };
    };
    return [ring(0, 1, 0), ring(0.5, 0.866, 0), ring(0.6, -0.35, 0.72)];
  }, []);
  const orbitBackRefs = useRef<Array<SVGPolylineElement | null>>([]);
  const orbitFrontRefs = useRef<Array<SVGPolylineElement | null>>([]);

  const openPlanet = () => {
    const rect = miniRef.current?.getBoundingClientRect();
    if (rect) {
      // mini 按钮中心相对球心原点的偏移；展开/收拢动画改用 transform 插值（GPU 合成，不触发布局）
      const baseX = window.innerWidth / 2;
      const baseY = window.innerWidth * 0.1226 + window.innerHeight * 0.24;
      setOrigin({
        x: rect.left + rect.width / 2 - baseX,
        y: rect.top + rect.height / 2 - baseY,
        size: rect.width,
      });
    }
    document.body.classList.add('planet-open');
    setSettled(false);
    setOpen(true);
  };

  const closePlanet = () => {
    setSettled(false);
    // 底层页面（粒子/视频/目录）延迟到收拢动画结束再恢复渲染，避免抢 GPU 造成卡顿
    window.setTimeout(() => {
      document.body.classList.remove('planet-open');
      setOpen(false);
    }, 740);
  };

  useEffect(() => {
    if (!open) return;
    const frame = requestAnimationFrame(() => requestAnimationFrame(() => setSettled(true)));
    const scroller = document.querySelector<HTMLElement>('.app-content');
    const prev = scroller?.style.overflow ?? '';
    if (scroller) scroller.style.overflow = 'hidden';
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closePlanet();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener('keydown', onKey);
      document.body.classList.remove('planet-open');
      if (scroller) scroller.style.overflow = prev;
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const stage = stageRef.current;
    if (!stage) return;
    const nodes = nodeRefs.current;
    const pts = points;
    let ay = 0.4;
    let ax = 0.28;
    let zoom = 1;
    let dragging = false;
    let moved = false;
    let hovering = false;
    let lastX = 0;
    let lastY = 0;
    let vAy = 0;
    let vAx = 0;
    let intro = 0;
    let fly = 0;
    let last = performance.now();
    let raf = 0;

    const fit = () => {
      const w = stage.clientWidth || 1200;
      const h = stage.clientHeight || 800;
      // 环绕半径与视频球绑定：球半径 0.54×152/1536·W ≈ 0.0534W，shell ≈ 球半径 × 2.95
      return { w, h, shell: w * 0.1575 };
    };

    const project = (p: ShellPoint, radius: number) => {
      const ca = Math.cos(ay);
      const sa = Math.sin(ay);
      const cb = Math.cos(ax);
      const sb = Math.sin(ax);
      let x = p.sx * ca + p.sz * sa;
      let z = -p.sx * sa + p.sz * ca;
      const y = p.sy * cb - z * sb;
      z = p.sy * sb + z * cb;
      const persp = FOV / (FOV + z * radius * 0.15);
      const sc = persp * zoom;
      return { x: x * radius * sc, y: y * radius * sc, z, persp };
    };

    const frame = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      if (settledRef.current) fly = Math.min(1, fly + dt / 0.62);
      if (fly >= 1) intro = Math.min(1, intro + dt / 0.85);
      // 悬停应用节点时暂停自转，方便点中（v7 交互）
      if (!dragging && !hovering) ay += 0.22 * dt;
      ay += vAy * dt;
      ax += vAx * dt;
      vAy *= 0.9 ** (dt * 60);
      vAx *= 0.9 ** (dt * 60);
      ax = Math.max(-1.05, Math.min(1.05, ax));
      const { shell } = fit();
      const radius = shell * zoom;
      pts.forEach((point, index) => {
        const el = nodes[index];
        if (!el) return;
        const projected = project(point, radius);
        const depth = Math.max(0, Math.min(1, (projected.persp - 0.72) / 0.45));
        const stagger = (index % 24) / 24 * 0.28;
        const k = Math.max(0, Math.min(1, (intro - stagger) / (1 - stagger)));
        const ease = 1 - (1 - k) ** 3;
        const scale = projected.persp * (0.72 + 0.38 * depth) * (0.2 + 0.8 * ease);
        el.style.transform = `translate3d(${projected.x.toFixed(1)}px, ${projected.y.toFixed(1)}px, 0) scale(${scale.toFixed(3)})`;
        el.style.opacity = String((0.2 + 0.8 * depth) * ease);
        el.style.zIndex = projected.z >= 0 ? String(20 + Math.round(depth * 40)) : String(60 + Math.round(depth * 80));
        el.classList.toggle('is-far', depth < 0.42);
      });
      const core = stage.querySelector<HTMLElement>('.planet-core');
      if (core) core.style.transform = `translate(-50%, -50%) scale(${zoom.toFixed(3)})`;
      // 视频球体围绕球心随滚轮缩放（与节点壳同步）
      const bgVideo = stage.querySelector<HTMLVideoElement>('.planet-bg-video');
      if (bgVideo) bgVideo.style.transform = `scale(${zoom.toFixed(3)})`;
      // 3 条大圆轨道：投影后按前后半圈拆段写入 polyline 池
      const orbitR = radius * 1.1;
      orbitRings.forEach((ring, ri) => {
        const segsBack: Array<{ pts: string[] }> = [];
        const segsFront: Array<{ pts: string[] }> = [];
        let cur: { front: boolean; pts: string[] } | null = null;
        for (let k = 0; k <= 72; k += 1) {
          const t = ((k % 72) / 72) * Math.PI * 2;
          const p = {
            sx: ring.u.sx * Math.cos(t) + ring.v.sx * Math.sin(t),
            sy: ring.u.sy * Math.cos(t) + ring.v.sy * Math.sin(t),
            sz: ring.u.sz * Math.cos(t) + ring.v.sz * Math.sin(t),
          };
          const pr = project(p, orbitR);
          const front = pr.z < 0;
          if (!cur || cur.front !== front) {
            cur = { front, pts: [] };
            (front ? segsFront : segsBack).push(cur);
          }
          cur.pts.push(`${pr.x.toFixed(1)},${pr.y.toFixed(1)}`);
        }
        writeOrbitSegs(orbitBackRefs.current.slice(ri * 4, ri * 4 + 4), segsBack);
        writeOrbitSegs(orbitFrontRefs.current.slice(ri * 4, ri * 4 + 4), segsFront);
      });
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);

    const down = (event: PointerEvent) => {
      moved = false;
      stage.dataset.dragged = '0';
      if ((event.target as HTMLElement).closest('.planet-node, .planet-close, .planet-lockup')) return;
      dragging = true;
      moved = false;
      lastX = event.clientX;
      lastY = event.clientY;
      vAy = 0;
      vAx = 0;
      stage.setPointerCapture?.(event.pointerId);
    };
    const move = (event: PointerEvent) => {
      if (!dragging) return;
      const dx = event.clientX - lastX;
      const dy = event.clientY - lastY;
      if (Math.hypot(dx, dy) > 3) moved = true;
      lastX = event.clientX;
      lastY = event.clientY;
      ay += dx * 0.006;
      ax += dy * 0.0045;
      vAy = dx * 0.28;
      vAx = dy * 0.2;
    };
    const up = () => {
      dragging = false;
    };
    const wheel = (event: WheelEvent) => {
      event.preventDefault();
      zoom = Math.max(0.72, Math.min(1.45, zoom * (1 - event.deltaY * 0.001)));
    };
    const inNode = (el: EventTarget | null) =>
      el instanceof Element && !!el.closest('.planet-node');
    const over = (event: PointerEvent) => {
      if (inNode(event.target)) hovering = true;
    };
    const out = (event: PointerEvent) => {
      if (inNode(event.target) && !inNode(event.relatedTarget)) hovering = false;
    };
    stage.addEventListener('pointerdown', down);
    stage.addEventListener('pointermove', move);
    stage.addEventListener('pointerup', up);
    stage.addEventListener('pointercancel', up);
    stage.addEventListener('wheel', wheel, { passive: false });
    stage.addEventListener('pointerover', over);
    stage.addEventListener('pointerout', out);
    stage.dataset.dragged = '0';
    const mark = () => {
      stage.dataset.dragged = moved ? '1' : '0';
    };
    stage.addEventListener('pointerup', mark);
    return () => {
      cancelAnimationFrame(raf);
      stage.removeEventListener('pointerdown', down);
      stage.removeEventListener('pointermove', move);
      stage.removeEventListener('pointerup', up);
      stage.removeEventListener('pointercancel', up);
      stage.removeEventListener('wheel', wheel);
      stage.removeEventListener('pointerover', over);
      stage.removeEventListener('pointerout', out);
      stage.removeEventListener('pointerup', mark);
    };
  }, [open, points]);

  // 缩小版星球的 3D 球面壳环绕（纯展示，慢速自转）
  useEffect(() => {
    const scene = miniSceneRef.current;
    if (!scene) return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    const nodes = miniNodeRefs.current;
    const pts = miniPoints;
    const TILT = 0.16;
    let ay = 0.3;
    let last = performance.now();
    let raf = 0;
    const proj = (p: ShellPoint, r: number) => {
      const ca = Math.cos(ay);
      const sa = Math.sin(ay);
      const cb = Math.cos(TILT);
      const sb = Math.sin(TILT);
      let x = p.sx * ca + p.sz * sa;
      let z = -p.sx * sa + p.sz * ca;
      const y = p.sy * cb - z * sb;
      z = p.sy * sb + z * cb;
      const persp = FOV / (FOV + z * r * 0.15);
      return { x: x * r * persp, y: y * r * persp, z, persp };
    };
    const frame = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      ay += 0.14 * dt;
      const r = (scene.clientWidth || 132) * 0.31;
      const projs = pts.map((p) => proj(p, r));
      const meanX = projs.reduce((s, q) => s + q.x, 0) / projs.length;
      const meanY = projs.reduce((s, q) => s + q.y, 0) / projs.length;
      pts.forEach((p, i) => {
        const el = nodes[i];
        if (!el) return;
        const pr = projs[i];
        const depth = Math.max(0, Math.min(1, (pr.persp - 0.72) / 0.45));
        const scale = pr.persp * (0.9 + 0.26 * depth);
        el.style.transform = `translate(-50%,-50%) translate(${(pr.x - meanX).toFixed(1)}px, ${(pr.y - meanY).toFixed(1)}px) scale(${scale.toFixed(3)})`;
        el.style.opacity = String((0.34 + 0.66 * depth).toFixed(3));
        el.style.zIndex = pr.z >= 0 ? String(20 + Math.round(depth * 20)) : String(50 + Math.round(depth * 30));
      });
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, [miniPoints]);

  const fromMini = open && origin && !settled;

  return (
    <>
      <button
        ref={miniRef}
        type="button"
        className="mini-planet"
        aria-label="打开放大应用星球"
        onClick={openPlanet}
      >
        <span
          className={`mini-planet-scene${dark ? ' is-dark' : ''}`}
          ref={miniSceneRef}
          aria-hidden="true"
        >
          <span className={`mini-planet-globe${dark ? ' is-dark' : ''}`}>
            <svg className="mini-planet-latlon" viewBox="0 0 100 100" aria-hidden="true">
              <g fill="none" stroke="rgba(80,150,255,.9)" strokeWidth="0.7">
                <ellipse cx="50" cy="50" rx="11" ry="47" />
                <ellipse cx="50" cy="50" rx="24" ry="47" />
                <ellipse cx="50" cy="50" rx="37" ry="47" />
                <line x1="50" y1="3" x2="50" y2="97" />
                <ellipse cx="50" cy="17" rx="46" ry="13" />
                <ellipse cx="50" cy="33" rx="50" ry="15" />
                <ellipse cx="50" cy="50" rx="50" ry="6" />
                <ellipse cx="50" cy="67" rx="50" ry="15" />
                <ellipse cx="50" cy="83" rx="46" ry="13" />
              </g>
            </svg>
            <span className="mini-planet-glare" />
          </span>
          {miniApps.map((app, index) => (
            <span
              key={app.key ?? `${app.name}-${index}`}
              className="mini-planet-node"
              ref={(el) => { miniNodeRefs.current[index] = el; }}
            >
              {app.icon ? <img src={app.icon} alt="" /> : <i>{appLabel(app).slice(0, 1)}</i>}
            </span>
          ))}
        </span>
      </button>
      {open &&
        createPortal(
          <div className={`planet-stage${dark ? ' is-dark' : ''}${settled ? ' is-settled' : ''}`} ref={stageRef}>
            <div className="planet-sky" aria-hidden="true">
              <i className="planet-nb nb1" />
              <i className="planet-nb nb2" />
              <i className="planet-nb nb3" />
              <div className="planet-stars">
                {stars.map((s, i) => (
                  <i
                    key={i}
                    className={`planet-star${s.kind}`}
                    style={{
                      left: `${s.x}%`,
                      top: `${s.y}%`,
                      width: `${s.size}px`,
                      height: `${s.size}px`,
                      animationDelay: `${s.delay}s`,
                      ['--o' as string]: s.o,
                    }}
                  />
                ))}
              </div>
            </div>
            <video className="planet-bg-video" autoPlay loop muted playsInline src={sphereVideo} poster={sphere} />
            <div className="planet-bg-veil" />
            <header className="planet-lockup">
              <span>MEDICAL AI · UNIFIED PORTAL</span>
              <h2>数智大脑</h2>
              <i className="planet-lockup-rule" />
              <p><b>{shown.length}</b> 个应用在星球轨道上运行</p>
            </header>
            <button type="button" className="planet-close" onClick={closePlanet} aria-label="关闭应用星球">
              <X size={18} />
            </button>
            <div
              className="planet-scene"
              style={fromMini ? { transform: `translate(${origin!.x.toFixed(1)}px, ${origin!.y.toFixed(1)}px)` } : undefined}
            >
              <svg className="planet-orbits ob" aria-hidden="true">
                {Array.from({ length: 12 }, (_, i) => (
                  <polyline key={i} ref={(el) => { orbitBackRefs.current[i] = el; }} />
                ))}
              </svg>
              <div
                className={`planet-core${dark ? ' is-dark' : ''}`}
                style={fromMini ? { width: origin!.size, height: origin!.size } : undefined}
              >
                <div className="planet-core-glare" />
              </div>
              {shown.map((app, index) => (
                <button
                  key={app.key ?? `${app.name}-${index}`}
                  type="button"
                  className="planet-node"
                  ref={(el) => { nodeRefs.current[index] = el; }}
                  onClick={(event) => {
                    if (stageRef.current?.dataset.dragged === '1') {
                      event.preventDefault();
                      return;
                    }
                    onOpenApp(app);
                  }}
                >
                  {app.icon ? <img src={app.icon} alt="" /> : <i>{appLabel(app).slice(0, 1)}</i>}
                  <em>{appLabel(app)}</em>
                </button>
              ))}
              <svg className="planet-orbits of" aria-hidden="true">
                {Array.from({ length: 12 }, (_, i) => (
                  <polyline key={i} ref={(el) => { orbitFrontRefs.current[i] = el; }} />
                ))}
              </svg>
            </div>
            <p className="planet-tips">拖动旋转星球 · 滚轮缩放 · 点击进入</p>
          </div>,
          document.body,
        )}
    </>
  );
}
