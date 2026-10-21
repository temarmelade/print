/**
 * На телефоне таблицы показываются карточками: каждая строка — блок,
 * каждая ячейка — пара «подпись колонки — значение».
 *
 * Подпись берём из заголовка (<th>) той же колонки и кладём в
 * data-label ячейки; CSS (pages.css, table.stack) выводит её через
 * ::before. Делаем это наблюдателем за DOM, чтобы не размечать вручную
 * каждую таблицу в каждой странице: колонки там бывают условными
 * (выручку видит только владелец), и ручная разметка быстро разъедется.
 */

const SELECTOR = "table.tx-table, .pricing-table table, .modal table";

function labelTable(table: HTMLTableElement) {
  const headRow = table.tHead?.rows[0];
  if (!headRow) return;

  // Учитываем colspan у заголовков, чтобы индексы совпали с ячейками.
  const labels: string[] = [];
  for (const th of Array.from(headRow.cells)) {
    const text = (th.textContent ?? "").trim();
    for (let i = 0; i < (th.colSpan || 1); i++) labels.push(text);
  }

  table.classList.add("stack");

  for (const body of Array.from(table.tBodies)) {
    for (const row of Array.from(body.rows)) {
      let col = 0;
      for (const cell of Array.from(row.cells)) {
        const label = labels[col] ?? "";
        if (cell.getAttribute("data-label") !== label) {
          cell.setAttribute("data-label", label);
        }
        col += cell.colSpan || 1;
      }
    }
  }
}

function labelAll(root: ParentNode) {
  root.querySelectorAll<HTMLTableElement>(SELECTOR).forEach(labelTable);
}

export function installStackTables(): void {
  labelAll(document);

  let scheduled = false;
  const observer = new MutationObserver(() => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      labelAll(document);
    });
  });
  // Атрибуты не наблюдаем: иначе наши же data-label запускали бы цикл.
  observer.observe(document.body, { childList: true, subtree: true, characterData: true });
}
