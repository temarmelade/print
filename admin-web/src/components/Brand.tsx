/**
 * Логотип BasmaPrint. Файлы лежат в public/brand/:
 *  • mark-white.png — значок принтера, белый, для тёмно-синей шапки и футера;
 *  • mark-navy.png  — тот же значок тёмно-синим, для светлого фона;
 *  • logo-navy.png  — полный логотип с надписью, для экрана входа.
 * Все три — с прозрачным фоном, вырезаны из исходного logo.jpg.
 */
export function BrandMark({ variant, size }: { variant: "white" | "navy"; size: number }) {
  return (
    <img
      className="brand-mark"
      src={`/brand/mark-${variant}.png`}
      alt=""
      width={Math.round(size * 1.08)}
      height={size}
      draggable={false}
    />
  );
}

export function BrandLogo({ height }: { height: number }) {
  return (
    <img
      className="brand-logo"
      src="/brand/logo-navy.png"
      alt="BasmaPrint"
      height={height}
      width={Math.round(height * 0.925)}
      draggable={false}
    />
  );
}
