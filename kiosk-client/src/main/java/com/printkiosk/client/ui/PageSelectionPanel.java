package com.printkiosk.client.ui;

import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Панель выбора страниц для печати: режим (все / диапазон / одиночные) +
 * динамические строки ввода. Наружу отдаёт итоговый список через getPagesToPrint.
 */
public class PageSelectionPanel {

    public enum Mode {
        ALL("Все страницы"),
        RANGE("Диапазон страниц"),
        SINGLE("Страница");

        private final String title;
        Mode(String t) { this.title = t; }
        @Override public String toString() { return title; }
    }

    private final ComboBox<Mode> modeCombo;
    private final VBox rowsContainer;
    private final Button addRowBtn;

    /** Колбэк «привязать это поле к Numpad» — задаётся контроллером. */
    private final Consumer<TextField> numpadBinder;

    public PageSelectionPanel(ComboBox<Mode> modeCombo, VBox rowsContainer,
                              Button addRowBtn, Consumer<TextField> numpadBinder) {
        this.modeCombo = modeCombo;
        this.rowsContainer = rowsContainer;
        this.addRowBtn = addRowBtn;
        this.numpadBinder = numpadBinder;
        init();
    }

    private void init() {
        modeCombo.setItems(FXCollections.observableArrayList(Mode.values()));
        modeCombo.getSelectionModel().select(Mode.ALL);
        modeCombo.valueProperty().addListener((o, old, mode) -> applyMode(mode));
        applyMode(Mode.ALL);
    }

    /** Переключение режима: показываем нужные блоки и стартовую строку. */
    private void applyMode(Mode mode) {
        rowsContainer.getChildren().clear();
        boolean dynamic = (mode != Mode.ALL);
        addRowBtn.setVisible(dynamic);
        addRowBtn.setManaged(dynamic);

        if (mode == Mode.RANGE) {
            rowsContainer.getChildren().add(rangeRow());
        } else if (mode == Mode.SINGLE) {
            rowsContainer.getChildren().add(singleRow());
        }
        // ALL — строк нет, печатается весь документ.
    }

    /** Обработчик кнопки «+»: добавляет ещё одну строку текущего режима. */
    public void onAddRow() {
        Mode mode = modeCombo.getValue();
        if (mode == Mode.RANGE) {
            rowsContainer.getChildren().add(rangeRow());
        } else if (mode == Mode.SINGLE) {
            rowsContainer.getChildren().add(singleRow());
        }
    }

    /** [  ] — [  ] */
    private HBox rangeRow() {
        TextField from = pageField();
        TextField to   = pageField();
        Label dash = new Label("—");
        dash.getStyleClass().add("page-range-dash");
        HBox row = new HBox(10, from, dash, to);
        row.getStyleClass().add("page-input-row");
        row.getProperties().put("type", "range");
        return row;
    }

    /** [  ] */
    private HBox singleRow() {
        TextField field = pageField();
        HBox row = new HBox(field);
        row.getStyleClass().add("page-input-row");
        row.getProperties().put("type", "single");
        return row;
    }

    /** Квадратное числовое поле, привязанное к Numpad. */
    private TextField pageField() {
        TextField tf = new TextField();
        tf.getStyleClass().add("page-num-field");
        if (numpadBinder != null) numpadBinder.accept(tf);
        return tf;
    }

    /**
     * Итоговый список страниц. Валидирует (цифры, 1..totalPages), убирает
     * дубликаты, сортирует. Для ALL возвращает 1..totalPages.
     */
    public List<Integer> getPagesToPrint(int totalPages) {
        Mode mode = modeCombo.getValue();
        Set<Integer> pages = new LinkedHashSet<>();

        if (mode == Mode.ALL) {
            for (int i = 1; i <= totalPages; i++) pages.add(i);
            return new ArrayList<>(pages);
        }

        for (var node : rowsContainer.getChildren()) {
            if (!(node instanceof HBox row)) continue;
            String type = (String) row.getProperties().get("type");
            List<TextField> fields = row.getChildren().stream()
                    .filter(n -> n instanceof TextField)
                    .map(n -> (TextField) n)
                    .toList();

            if ("range".equals(type) && fields.size() == 2) {
                Integer a = parse(fields.get(0).getText(), totalPages);
                Integer b = parse(fields.get(1).getText(), totalPages);
                if (a != null && b != null) {
                    int lo = Math.min(a, b), hi = Math.max(a, b);
                    for (int i = lo; i <= hi; i++) pages.add(i);
                }
            } else if ("single".equals(type) && fields.size() == 1) {
                Integer p = parse(fields.get(0).getText(), totalPages);
                if (p != null) pages.add(p);
            }
        }

        List<Integer> result = new ArrayList<>(pages);
        result.sort(Integer::compareTo);
        return result;
    }

    /** Парсит и валидирует номер страницы: цифры, 1..totalPages. Иначе null. */
    private Integer parse(String raw, int totalPages) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return (v >= 1 && v <= totalPages) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Mode currentMode() { return modeCombo.getValue(); }
}