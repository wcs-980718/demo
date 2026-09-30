import { Fragment, useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Input } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  LayoutGrid,
  List,
  Search,
  Sparkles,
} from 'lucide-react';
import { HomePlanet } from './HomePlanet';
import {
  fetchMasterApps,
  groupMasterApps,
  groupRows,
  masterAppUrl,
  masterPortalRoute,
  type MasterAppGroup,
  type MasterAppItem,
} from './masterApps';
import { publicAsset } from '@/publicAsset';
import { inMasterPortal } from '@/runtimeShell';

/** 首屏主视觉：浅色用原片；深色用最初的深色片，只把光球和金色辉光放慢。 */
function HeroScene({ children }: { children?: ReactNode }) {
  const [theme, setTheme] = useState<'light' | 'dark'>('light');
  useEffect(() => {
    const read = () => setTheme(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
    read();
    const observer = new MutationObserver(read);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, []);
  const dark = theme === 'dark';
  return (
    <div className="home-hero-stage">
      <video
        key={theme}
        autoPlay
        loop
        muted
        playsInline
        poster={publicAsset(dark ? 'media/hero-dark.jpg?v=20260922e' : 'media/hero-light.jpg')}
        src={publicAsset(dark ? 'media/hero-dark.mp4?v=20260922e' : 'media/hero-light.mp4')}
      />
      {children}
    </div>
  );
}

export default function HomePage() {

  // 主应用统一门户的应用目录：同源 /api 直达（qiankun 内即门户会话），
  // dev 走 /portal-api 代理，快照兜底；主应用改菜单配置后这里实时跟随。
  const masterAppsQuery = useQuery({
    queryKey: ['master-apps'],
    queryFn: fetchMasterApps,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const allGroups = useMemo(
    () => groupMasterApps(masterAppsQuery.data ?? []),
    [masterAppsQuery.data],
  );
  // 有数据（含 react-query 缓存命中）直接渲染；仅真实请求期间显示骨架屏
  const deckLoading = masterAppsQuery.isPending;
  const portalAppCount = allGroups.reduce((s, g) => s + g.apps.length, 0);
  const [rail, setRail] = useState('all');
  const [listView, setListView] = useState(false);
  // 分组顺序：「数智大脑」置首（紧跟「全部」），其余保持接口顺序
  const orderedGroups = useMemo(() => {
    const gs = [...allGroups];
    const i = gs.findIndex((g) => g.name === '数智大脑');
    if (i > 0) gs.unshift(...gs.splice(i, 1));
    return gs;
  }, [allGroups]);
  const railItems = [
    { id: 'all', name: '全部', n: portalAppCount },
    ...orderedGroups.map((g) => ({ id: g.name, name: g.name, n: g.apps.length })),
  ];

  // 分组过多溢出时用左右箭头翻动（仅在有可滚动方向时显示对应箭头）
  const railRef = useRef<HTMLDivElement>(null);
  const [railScroll, setRailScroll] = useState({ left: false, right: false });
  useEffect(() => {
    const el = railRef.current;
    if (!el) return;
    const update = () =>
      setRailScroll({
        left: el.scrollLeft > 4,
        right: el.scrollLeft + el.clientWidth < el.scrollWidth - 4,
      });
    update();
    el.addEventListener('scroll', update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      ro.disconnect();
    };
  }, [railItems.length, deckLoading]);
  useEffect(() => {
    const parent = railRef.current;
    const active = parent?.querySelector<HTMLElement>('[aria-selected="true"]');
    if (!parent || !active) return;
    const parentBox = parent.getBoundingClientRect();
    const activeBox = active.getBoundingClientRect();
    if (activeBox.left < parentBox.left) {
      parent.scrollBy({ left: activeBox.left - parentBox.left - 8, behavior: 'smooth' });
    } else if (activeBox.right > parentBox.right) {
      parent.scrollBy({ left: activeBox.right - parentBox.right + 8, behavior: 'smooth' });
    }
  }, [rail, railItems.length, deckLoading]);
  // 分组栏吸顶后才显示毛玻璃底：用栏上方的零高哨兵判断是否已滚出滚动容器顶部
  const railSentinelRef = useRef<HTMLDivElement>(null);
  const [railStuck, setRailStuck] = useState(false);
  useEffect(() => {
    const sentinel = railSentinelRef.current;
    if (!sentinel) return;
    const root = sentinel.closest<HTMLElement>('.app-content');
    const io = new IntersectionObserver(
      ([entry]) => {
        const top = entry.rootBounds?.top ?? 0;
        setRailStuck(!entry.isIntersecting && entry.boundingClientRect.top < top);
      },
      { root },
    );
    io.observe(sentinel);
    return () => io.disconnect();
  }, [deckLoading]);
  const nudgeRail = (dir: number) => {
    const el = railRef.current;
    if (!el) return;
    const tabs = Array.from(el.querySelectorAll<HTMLElement>('button'));
    const viewLeft = el.scrollLeft;
    const viewRight = viewLeft + el.clientWidth;
    if (dir > 0) {
      const next = tabs.find((tab) => tab.offsetLeft + tab.offsetWidth > viewRight + 8);
      el.scrollTo({ left: next ? next.offsetLeft : el.scrollWidth, behavior: 'smooth' });
    } else {
      const prev = [...tabs].reverse().find((tab) => tab.offsetLeft < viewLeft - 8);
      const left = prev ? prev.offsetLeft + prev.offsetWidth - el.clientWidth : 0;
      el.scrollTo({ left: Math.max(0, left), behavior: 'smooth' });
    }
  };
  const SUB_COLORS = ['#8b5cf6', '#0ea5e9', '#f59e0b', '#10b981', '#ec4899', '#6366f1', '#14b8a6'];
  const subColor = (name: string) =>
    SUB_COLORS[[...name].reduce((s, c) => s + c.charCodeAt(0), 0) % SUB_COLORS.length];

  // 命令面板（原型 .pal）：hero 搜索框聚焦/点击或 ⌘K 唤起，↑↓ 选择、↵ 打开、ESC 关闭
  const [palOpen, setPalOpen] = useState(false);
  const [palQ, setPalQ] = useState('');
  const [palIdx, setPalIdx] = useState(0);
  // Enter 用的是渲染期闭包值，快速连续按键时同步 ref 兜底
  const palIdxRef = useRef(0);
  const palInputRef = useRef<HTMLInputElement>(null);
  const movePalIdx = (delta: number) =>
    setPalIdx((i) => {
      const n = (i + delta + palFlat.length) % palFlat.length;
      palIdxRef.current = n;
      return n;
    });
  const openPal = () => {
    setPalQ('');
    setPalIdx(0);
    palIdxRef.current = 0;
    setPalOpen(true);
  };
  const closePal = () => setPalOpen(false);
  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        if (palOpen) closePal();
        else openPal();
      } else if (e.key === 'Escape') {
        setPalOpen(false);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [palOpen]);
  useEffect(() => {
    if (palOpen) palInputRef.current?.focus();
  }, [palOpen]);
  // 面板打开期间锁定背景滚动并暂停 hero 视频：滚动会让压在视频上的
  // 毛玻璃区域整屏重绘，表现为页面闪烁。页面滚动容器是 .app-content 而非 body。
  useEffect(() => {
    if (!palOpen) return;
    const scroller = document.querySelector<HTMLElement>('.app-content');
    const prevOverflow = scroller?.style.overflow ?? '';
    const prevBody = document.body.style.overflow;
    if (scroller) scroller.style.overflow = 'hidden';
    document.body.style.overflow = 'hidden';
    const video = document.querySelector<HTMLVideoElement>('.home-hero-stage video');
    video?.pause();
    return () => {
      if (scroller) scroller.style.overflow = prevOverflow;
      document.body.style.overflow = prevBody;
      video?.play().catch(() => {});
    };
  }, [palOpen]);
  const palGroups = useMemo(() => {
    const kw2 = palQ.trim().toLowerCase();
    const items = (masterAppsQuery.data ?? [])
      .filter(
        (a) =>
          !kw2 ||
          `${a.title ?? ''}${a.name ?? ''}${a.remark ?? ''}${a.projectGroup ?? ''}${a.projectTwoGroup ?? ''}`
            .toLowerCase()
            .includes(kw2),
      );
    return groupMasterApps(items);
  }, [palQ, masterAppsQuery.data]);
  const palFlat = useMemo(() => palGroups.flatMap((g) => g.apps), [palGroups]);
  useEffect(() => {
    if (palOpen) document.querySelector('.pal-it.hl')?.scrollIntoView({ block: 'nearest' });
  }, [palIdx, palOpen]);

  // 主应用应用卡片：title/remark/icon 直接取自接口；门户内点击走主应用路由（同页），独立运行时新开 entry+path
  const openApp = (a: MasterAppItem) => {
    const route = inMasterPortal() ? masterPortalRoute(a) : null;
    if (route) {
      window.location.assign(route);
      return;
    }
    const url = masterAppUrl(a);
    if (url) window.open(url, '_blank', 'noopener,noreferrer');
  };
  const appCardKeyDown = (a: MasterAppItem) => (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      openApp(a);
    }
  };
  const appCard = (a: MasterAppItem) => (
    <article
      key={a.key ?? `${a.name}-${a.title}`}
      className={`app${listView ? ' list' : ''}`}
      role="link"
      tabIndex={0}
      aria-label={`进入${a.title || a.name}`}
      onClick={() => openApp(a)}
      onKeyDown={appCardKeyDown(a)}
    >
      {a.icon ? (
        <img className="lg" src={a.icon} alt="" />
      ) : (
        <span className="lg ic-chip" style={{ ['--c' as string]: '#64748b' }}>
          <LayoutGrid size={18} strokeWidth={1.8} />
        </span>
      )}
      <div className="bd">
        <h4>
          <span>{a.title || a.name}</span>
        </h4>
        <p>{a.remark || a.projectGroup || ''}</p>
      </div>
      <span className="go-hint" aria-hidden="true">
        <ArrowUpRight size={14} strokeWidth={2.2} />
      </span>
    </article>
  );

  const apiGroup = (g: MasterAppGroup) => (
    <Fragment key={g.name}>
      <div className="grp-h grp-main">
        {g.icon ? <img className="grp-ic" src={g.icon} alt="" /> : <i className="bar" />}
        {g.name}
        <span className="n">{g.apps.length}</span>
        {g.desc ? <span className="hint">{g.desc}</span> : null}
      </div>
      {groupRows(g).map((row) => (
        <Fragment key={row.sub ?? '_flat'}>
          {row.sub && (
            <div className="grp-h sub" style={{ ['--c' as string]: subColor(row.sub) }}>
              <i className="bar" />
              {row.sub}
              <span className="n">{row.apps.length}</span>
            </div>
          )}
          {row.apps.map(appCard)}
        </Fragment>
      ))}
    </Fragment>
  );

  return (
    <div className="home-page">
      <section className="home-hero">
        <HeroScene>
          {/* 右上：缩小版单星球，只展示旋转；点击后放大成可拖动、可缩放的应用星球 */}
          <div className="home-hero-planet">
            <HomePlanet apps={allGroups.flatMap((group) => group.apps)} onOpenApp={openApp} />
          </div>

          {/* 左侧：品牌锁合体（eyebrow / 标题 / 英文 / 光带 / 定位语）+ 搜索指令栏 */}
          <div className="home-hero-lockup">
            <span className="home-hero-eyebrow">MEDICAL AI · UNIFIED PORTAL</span>
            <h1 className="home-hero-title">数智大脑</h1>
            <span className="home-hero-en">DIGITAL BRAIN</span>
            <p className="home-hero-lead">
              <b>AI 能力</b>与<b>业务系统</b>的统一入口
              <br />
              按场景找能力，一键进入对应工作台
            </p>
            <form
              className="home-hero-search"
              role="search"
              onSubmit={(event) => {
                event.preventDefault();
                openPal();
              }}
            >
              <label className="sr-only" htmlFor="home-capability-search">
                搜索项目或能力
              </label>
              <Search size={17} strokeWidth={1.9} aria-hidden="true" />
              <Input
                id="home-capability-search"
                placeholder="搜索应用、能力或场景"
                readOnly
                onFocus={openPal}
                onClick={openPal}
                style={{ cursor: 'text' }}
              />
              <kbd>⌘ K</kbd>
              <button type="submit" className="home-hero-search-go" title="搜索" aria-label="搜索">
                <Sparkles size={15} strokeWidth={2} />
              </button>
            </form>
          </div>
        </HeroScene>
      </section>

      {/* 应用目录：数智大脑置首，其余分组实时取自主应用 menus?key=apps */}
      <section className="app-deck" aria-label="应用目录">
        <div className="sec-head">
          <div>
            <span className="en2">APP DIRECTORY</span>
            <h2>应用目录</h2>
            <p>业务系统统一入口，目录跟随门户菜单实时更新</p>
          </div>
          {!deckLoading && portalAppCount > 0 && (
            <div className="sec-stats">
              <div>
                <b>{portalAppCount}</b>
                <span>个应用</span>
              </div>
              <div>
                <b>{orderedGroups.length}</b>
                <span>个分组</span>
              </div>
            </div>
          )}
        </div>
        {!deckLoading && <div ref={railSentinelRef} className="rail-sentinel" aria-hidden="true" />}
        {!deckLoading && (
        <div className={`railwrap${railStuck ? ' is-stuck' : ''}`}>
          <div className={`rail${railScroll.left ? ' can-left' : ''}${railScroll.right ? ' can-right' : ''}`}>
            <button
              type="button"
              className="rail-arrow"
              aria-label="向左切换分组"
              disabled={!railScroll.left}
              onClick={() => nudgeRail(-1)}
            >
              <ChevronLeft size={16} />
            </button>
            <div className="rail-scroll" role="tablist" aria-label="应用分组" ref={railRef}>
              {railItems.map((r) => (
                <button
                  key={r.id}
                  type="button"
                  role="tab"
                  aria-selected={rail === r.id}
                  onClick={() => setRail(r.id)}
                >
                  {r.name}
                  <span className="n">{r.n}</span>
                </button>
              ))}
            </div>
            <button
              type="button"
              className="rail-arrow"
              aria-label="向右切换分组"
              disabled={!railScroll.right}
              onClick={() => nudgeRail(1)}
            >
              <ChevronRight size={16} />
            </button>
          </div>
          <div className="seg" role="group" aria-label="视图切换">
            <button
              type="button"
              aria-pressed={!listView}
              title="宫格视图"
              onClick={() => setListView(false)}
            >
              <LayoutGrid size={16} />
            </button>
            <button
              type="button"
              aria-pressed={listView}
              title="列表视图"
              onClick={() => setListView(true)}
            >
              <List size={16} />
            </button>
          </div>
        </div>
        )}
        <div className={`app-grid${listView ? ' app-list' : ''}`} aria-busy={deckLoading}>
          {deckLoading ? (
            Array.from({ length: 8 }, (_, i) => (
              <div key={i} className="sk-card" aria-hidden="true">
                <span className="sk-lg" />
                <div className="sk-bd">
                  <i />
                  <i />
                </div>
              </div>
            ))
          ) : !allGroups.length ? (
            <div className="home-empty">暂无可用应用目录。</div>
          ) : rail === 'all' ? (
            orderedGroups.map((g) => apiGroup(g))
          ) : (
            orderedGroups.filter((g) => g.name === rail).map((g) => apiGroup(g))
          )}
        </div>
      </section>

      {/* 命令面板：点击 hero 搜索框或 ⌘K 唤起，↑↓ 选择、↵ 打开、ESC 关闭 */}
      {palOpen && (
        <>
          <div className="pal-mask" onClick={closePal} />
          <div className="pal" role="dialog" aria-modal="true" aria-label="搜索">
            <div className="pal-in">
              <Search size={19} strokeWidth={1.9} color="#0a6cff" aria-hidden="true" />
              <input
                ref={palInputRef}
                value={palQ}
                onChange={(e) => {
                  setPalQ(e.target.value);
                  setPalIdx(0);
                  palIdxRef.current = 0;
                }}
                onKeyDown={(e) => {
                  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                    e.preventDefault();
                    if (palFlat.length) movePalIdx(e.key === 'ArrowDown' ? 1 : -1);
                  } else if (e.key === 'Enter') {
                    const it = palFlat[palIdxRef.current] ?? palFlat[0];
                    if (it) {
                      openApp(it);
                      closePal();
                    }
                  } else if (e.key === 'Escape') {
                    closePal();
                  }
                }}
                placeholder="搜索应用、能力或场景…"
              />
              <kbd>ESC</kbd>
            </div>
            <div className="pal-list">
              {palGroups.map((g) => (
                <Fragment key={g.name}>
                  <div className="pal-sec">{g.name}</div>
                  {g.apps.map((a) => {
                    const idx = palFlat.indexOf(a);
                    return (
                      <div
                        key={a.key ?? `${a.name}-${a.title}`}
                        className={`pal-it${idx === palIdx ? ' hl' : ''}`}
                        onMouseEnter={() => {
                          setPalIdx(idx);
                          palIdxRef.current = idx;
                        }}
                        onClick={() => {
                          openApp(a);
                          closePal();
                        }}
                      >
                        {a.icon ? (
                          <img src={a.icon} alt="" />
                        ) : (
                          <span className="ph">
                            <LayoutGrid size={15} />
                          </span>
                        )}
                        <div>
                          <b>{a.title || a.name}</b>
                          <span>{a.remark || a.projectGroup || ''}</span>
                        </div>
                        <em>{g.name}</em>
                      </div>
                    );
                  })}
                </Fragment>
              ))}
              {!palFlat.length && <div className="pal-empty">未找到「{palQ}」相关的应用</div>}
            </div>
            <div className="pal-foot">
              <span>
                <kbd>↑↓</kbd> 选择
              </span>
              <span>
                <kbd>↵</kbd> 打开
              </span>
              <span>
                <kbd>ESC</kbd> 关闭
              </span>
              <span style={{ marginLeft: 'auto' }}>共 {palFlat.length} 个应用</span>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
