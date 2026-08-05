import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { HEALTH_TONE, HEALTH_LABEL, type Kiosk } from "../lib/kiosksApi.ts";
import "./map.css";

/** Бишкек — центр по умолчанию, когда ни у одного киоска нет координат. */
const FALLBACK_CENTER: [number, number] = [42.8746, 74.5698];

const TONE_COLOR: Record<string, string> = {
  ok: "#22c55e",
  warn: "#f59e0b",
  down: "#ef4444",
  idle: "#6b7280",
};

/**
 * Карта сети киосков. Цвет точки повторяет статус из карточки, чтобы
 * взгляд не переучивался при переходе между списком и картой.
 *
 * <p>Leaflet живёт вне React: рисуем через ref в useEffect, иначе React
 * будет пересоздавать DOM под картой и она развалится.
 */
export function KioskMap({
  kiosks, onSelect,
}: {
  kiosks: Kiosk[];
  onSelect?: (id: string) => void;
}) {
  const nodeRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);

  // Инициализация — ровно один раз.
  useEffect(() => {
    if (!nodeRef.current || mapRef.current) return;

    const map = L.map(nodeRef.current, { attributionControl: true })
      .setView(FALLBACK_CENTER, 12);

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "© OpenStreetMap",
    }).addTo(map);

    mapRef.current = map;
    layerRef.current = L.layerGroup().addTo(map);

    return () => {
      map.remove();
      mapRef.current = null;
      layerRef.current = null;
    };
  }, []);

  // Перерисовка маркеров при обновлении данных (раз в 30 секунд).
  useEffect(() => {
    const map = mapRef.current;
    const layer = layerRef.current;
    if (!map || !layer) return;

    layer.clearLayers();

    const located = kiosks.filter(
      (k) => typeof k.latitude === "number" && typeof k.longitude === "number",
    );

    located.forEach((k) => {
      const tone = HEALTH_TONE[k.health];
      const marker = L.circleMarker([k.latitude as number, k.longitude as number], {
        radius: 10,
        color: "#0b0e14",
        weight: 2,
        fillColor: TONE_COLOR[tone] ?? TONE_COLOR.idle,
        fillOpacity: 0.9,
      });

      marker.bindPopup(
        `<b>${escapeHtml(k.name)}</b><br>` +
        `${escapeHtml(k.location ?? k.id)}<br>` +
        `<span style="color:${TONE_COLOR[tone]}">${HEALTH_LABEL[k.health]}</span><br>` +
        `<small>${escapeHtml(k.healthReason)}</small>`,
      );

      if (onSelect) marker.on("click", () => onSelect(k.id));
      marker.addTo(layer);
    });

    // Подгоняем масштаб под точки — иначе при одном киоске в другом городе
    // карта осталась бы смотреть на Бишкек.
    if (located.length === 1) {
      map.setView([located[0].latitude as number, located[0].longitude as number], 15);
    } else if (located.length > 1) {
      map.fitBounds(
        L.latLngBounds(located.map((k) => [k.latitude as number, k.longitude as number])),
        { padding: [40, 40], maxZoom: 16 },
      );
    }
  }, [kiosks, onSelect]);

  const withoutCoords = kiosks.filter(
    (k) => typeof k.latitude !== "number" || typeof k.longitude !== "number",
  );

  return (
    <div className="map-wrap card">
      <div ref={nodeRef} className="map-canvas" />
      {withoutCoords.length > 0 && (
        <div className="map-hint">
          Без координат на карте не видно: {withoutCoords.map((k) => k.name).join(", ")}.
          Укажите широту и долготу в настройках киоска.
        </div>
      )}
    </div>
  );
}

/** Popup собирается строкой, поэтому имя и адрес экранируем вручную. */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
