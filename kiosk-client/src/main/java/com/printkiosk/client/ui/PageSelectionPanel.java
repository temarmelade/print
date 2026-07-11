package com.printkiosk.client.ui;

import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Панель выбора страниц для печати — вертикальный список миниатюр с чекбоксами.
 *
 * <p>Выбор задаётся только чекбоксами: тап по строке переключает её, а
 * «протаскивание» пальцем/мышью по нескольким строкам красит их все в одно
 * состояние (как выделение в «Фото» на iOS). Направление мазка (выделять или
 * снимать) задаётся первой строкой, на которой начали жест.
 *
 * <p>Кнопки «Выбрать все / Снять все» и счётчик «Выбрано: X из Y» — снаружи,
 * управляются методами {@link #selectAll()} / {@link #clearSelection()}.
 *
 * <p>Миниатюры подгружаются асинхронно через {@link ThumbnailProvider}, поэтому
 * на больших документах панель не блокирует UI-поток.
 */
public class PageSelectionPanel {

    /** Провайдер миниатюр: рендер страницы (0-based) в фоне, колбэк с готовым Image. */
    public interface ThumbnailProvider {
        void requestThumbnail(int pageIndex, Consumer<Image> onReady);
    }

    private final VBox  listContainer;
    private final Label countLabel;
    private final ThumbnailProvider thumbnailProvider;
    private final Runnable onSelectionChanged;

    private final List<CheckBox>  checkBoxes = new ArrayList<>();
    private final List<ImageView> thumbViews = new ArrayList<>();
    private int totalPages = 0;

    /** Подавляет пер-чекбоксовые уведомления во время массовых операций. */
    private boolean bulk = false;

    // ── Состояние жеста drag-select ──
    private boolean dragging = false;
    private boolean paintSelected = false;   // целевое состояние «мазка»

    public PageSelectionPanel(VBox listContainer,
                              Label countLabel,
                              ThumbnailProvider thumbnailProvider,
                              Runnable onSelectionChanged) {
        this.listContainer = listContainer;
        this.countLabel = countLabel;
        this.thumbnailProvider = thumbnailProvider;
        this.onSelectionChanged = onSelectionChanged;
        updateCountLabel();
    }

    /**
     * Построить список из {@code totalPages} страниц (все отмечены) и запустить
     * асинхронную загрузку миниатюр. Вызывать на FX-потоке.
     */
    public void setPages(int totalPages) {
        this.totalPages = Math.max(totalPages, 0);
        listContainer.getChildren().clear();
        checkBoxes.clear();
        thumbViews.clear();

        for (int i = 0; i < this.totalPages; i++) {
            listContainer.getChildren().add(buildRow(i));
        }

        setAllChecked(true);   // по умолчанию печатаются все страницы

        if (thumbnailProvider != null) {
            for (int i = 0; i < this.totalPages; i++) {
                final ImageView target = thumbViews.get(i);
                thumbnailProvider.requestThumbnail(i, img -> {
                    if (img != null) target.setImage(img);
                });
            }
        }
    }

    /** Очистить панель (при выходе с экрана / закрытии превью). */
    public void clear() {
        this.totalPages = 0;
        listContainer.getChildren().clear();
        checkBoxes.clear();
        thumbViews.clear();
        updateCountLabel();
        fireChanged();
    }

    /** Кнопка «Выбрать все». */
    public void selectAll() { setAllChecked(true); }

    /** Кнопка «Снять все». */
    public void clearSelection() { setAllChecked(false); }

    /** Итоговый список номеров страниц (1-based), по порядку. */
    public List<Integer> getPagesToPrint() {
        List<Integer> pages = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isSelected()) pages.add(i + 1);
        }
        return pages;
    }

    /** Сколько страниц выбрано сейчас. */
    public int selectedCount() {
        return (int) checkBoxes.stream().filter(CheckBox::isSelected).count();
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутреннее
    // ════════════════════════════════════════════════════════════════

    private HBox buildRow(int pageIndex) {
        CheckBox cb = new CheckBox();
        cb.getStyleClass().add("page-check");
        cb.setSelected(true);
        // Клики/жесты обрабатываем на строке — чекбокс «прозрачен» для мыши.
        cb.setMouseTransparent(true);
        cb.setFocusTraversable(false);
        cb.selectedProperty().addListener((o, was, now) -> {
            if (!bulk) { updateCountLabel(); fireChanged(); }
        });

        StackPane thumbWrap = new StackPane();
        thumbWrap.getStyleClass().add("page-thumb-wrap");
        ImageView iv = new ImageView();
        iv.setFitWidth(44);
        iv.setFitHeight(58);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        iv.setMouseTransparent(true);
        thumbWrap.getChildren().add(iv);

        Label num = new Label(String.valueOf(pageIndex + 1));
        num.getStyleClass().add("page-row-number");
        Label sub = new Label("Страница " + (pageIndex + 1));
        sub.getStyleClass().add("page-row-subtitle");
        VBox texts = new VBox(2, num, sub);
        texts.setAlignment(Pos.CENTER_LEFT);
        texts.setMouseTransparent(true);

        HBox row = new HBox(12, cb, thumbWrap, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("page-row");
        // Прозрачные зоны Region по умолчанию не участвуют в pick'е — без этого
        // клик/hover/drag по пустым частям строки не срабатывал бы.
        row.setPickOnBounds(true);

        // Нажатие: задаём направление «мазка» и переключаем стартовую строку.
        row.setOnMousePressed(e -> {
            paintSelected = !cb.isSelected();
            dragging = true;
            cb.setSelected(paintSelected);
        });
        // Включаем полноценный drag, чтобы события приходили и на соседние строки.
        row.setOnDragDetected(e -> { row.startFullDrag(); e.consume(); });
        // Палец «заезжает» на строку во время жеста — красим её в целевое состояние.
        row.setOnMouseDragEntered(e -> {
            if (dragging && cb.isSelected() != paintSelected) {
                cb.setSelected(paintSelected);
            }
        });
        row.setOnMouseReleased(e -> dragging = false);

        checkBoxes.add(cb);
        thumbViews.add(iv);
        return row;
    }

    private void setAllChecked(boolean checked) {
        bulk = true;
        for (CheckBox cb : checkBoxes) cb.setSelected(checked);
        bulk = false;
        updateCountLabel();
        fireChanged();
    }

    private void fireChanged() {
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    private void updateCountLabel() {
        long sel = checkBoxes.stream().filter(CheckBox::isSelected).count();
        countLabel.setText("Выбрано: " + sel + " из " + totalPages
                + " " + pluralPagesGen(totalPages));
    }

    /** Форма слова «страница» в родительном падеже после «из N». */
    private static String pluralPagesGen(int n) {
        if (n % 10 == 1 && n % 100 != 11) return "страницы";
        return "страниц";
    }
}