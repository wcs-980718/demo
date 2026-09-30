import { useEffect, useRef } from 'react';
import { initCubeField, type CubeFieldHandle } from './cubeFieldEngine';

/**
 * 全局粒子背景（LUMEN 引擎移植，魔方形态）
 * - 挂载时初始化 WebGL2 引擎，卸载时完整销毁
 * - chapter 变化时路由驱动粒子 morph
 */
export function CubeField({ chapter }: { chapter: number }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const handleRef = useRef<CubeFieldHandle | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const handle = initCubeField(canvas);
    handleRef.current = handle;
    (window as unknown as { __CF__?: CubeFieldHandle | null }).__CF__ = handle;
    return () => {
      handle?.destroy();
      handleRef.current = null;
      (window as unknown as { __CF__?: CubeFieldHandle | null }).__CF__ = null;
    };
  }, []);

  useEffect(() => {
    handleRef.current?.setChapter(chapter);
    // 首页标题占位区在路由渲染后才可测量；延时重测一次锚点，
    // 让「数智大脑」粒子成形落在两段描述文字的正中（水平居中）。
    const timer = window.setTimeout(() => handleRef.current?.refreshTitleAnchor(), 150);
    return () => window.clearTimeout(timer);
  }, [chapter]);

  // WebGL2 不可用时 initCubeField 返回 null，渲染空背景（保持浅色主题）
  return (
    <div className="cube-field" aria-hidden="true">
      <canvas ref={canvasRef} />
    </div>
  );
}