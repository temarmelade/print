package com.printkiosk.client.ui;

import javafx.animation.TranslateTransition;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Цифровая экранная клавиатура (Numpad) для полей ввода номеров страниц.
 * Монтируется в корневой контейнер, выезжает снизу при фокусе на поле,
 * прячется по галочке или тапу вне себя. Принимает только цифры.
 */
public class NumericKeyboard extends VBox {

    private TextField target;
    private final TranslateTransition slide;
    private boolean shown = false;

    public NumericKeyboard() {
        getStyleClass().add("numeric-keyboard");
        setVisible(false);
        setManaged(false);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        slide = new TranslateTransition(Duration.millis(160), this);
        build();
    }

    /** Привязать к полю: показывать при фокусе, разрешать только цифры. */
    public void attachTo(TextField field) {
        this.target = field;
        field.setTextFormatter(new TextFormatter<>(c ->
                c.getControlNewText().matches("\\d*") ? c : null));
        field.focusedProperty().addListener((o, was, now) -> { if (now) { setTarget(field); show(); } });

        sceneProperty().addListener((o, oldS, scene) -> {
            if (scene != null) {
                scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::handleOutsideTap);
                scene.addEventFilter(TouchEvent.TOUCH_PRESSED, this::handleOutsideTap);
            }
        });
    }

    /** Переключить целевое поле (при фокусе на другую страницу-инпут). */
    public void setTarget(TextField field) { this.target = field; }

    private void build() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("numpad-grid");
        grid.setHgap(8);
        grid.setVgap(8);
        // 1..9
        for (int i = 1; i <= 9; i++) {
            grid.add(digitKey(String.valueOf(i)), (i - 1) % 3, (i - 1) / 3);
        }
        // нижний ряд: backspace, 0, ✓
        Button back = iconKey("fas-backspace", this::backspace);
        Button zero = digitKey("0");
        Button done = iconKey("fas-check", this::hideKeyboard);
        done.getStyleClass().add("numpad-done");
        grid.add(back, 0, 3);
        grid.add(zero, 1, 3);
        grid.add(done, 2, 3);

        getChildren().add(grid);
    }

    private Button digitKey(String d) {
        Button b = new Button(d);
        b.getStyleClass().add("numpad-key");
        b.setFocusTraversable(false);
        b.setOnMousePressed(e -> { insert(d); e.consume(); });
        return b;
    }

    private Button iconKey(String icon, Runnable action) {
        Button b = new Button();
        b.setGraphic(new FontIcon(icon));
        b.getStyleClass().add("numpad-key");
        b.setFocusTraversable(false);
        b.setOnMousePressed(e -> { action.run(); e.consume(); });
        return b;
    }

    private void insert(String s) {
        if (target == null) return;
        int pos = target.getCaretPosition();
        target.insertText(pos, s);
        target.positionCaret(pos + s.length());
    }

    private void backspace() {
        if (target == null) return;
        int pos = target.getCaretPosition();
        if (pos > 0) { target.deleteText(pos - 1, pos); target.positionCaret(pos - 1); }
    }

    private void handleOutsideTap(javafx.event.Event e) {
        if (!shown) return;
        javafx.scene.Node t = (javafx.scene.Node) e.getTarget();
        for (javafx.scene.Node n = t; n != null; n = n.getParent()) {
            if (n == this) return;
            if (n instanceof TextField) return;   // тап по любому текстовому полю — не прячем
        }
        hideKeyboard();
    }

    private void show() {
        if (shown) return;
        shown = true;
        setVisible(true);
        setManaged(true);
        double h = Math.max(getHeight(), prefHeight(-1));
        slide.stop();
        setTranslateY(h);
        slide.setFromY(h); slide.setToY(0); slide.setOnFinished(null); slide.play();
    }

    public void hideKeyboard() {
        if (!shown) return;
        shown = false;
        double h = Math.max(getHeight(), prefHeight(-1));
        slide.stop();
        slide.setFromY(0); slide.setToY(h);
        slide.setOnFinished(e -> { setVisible(false); setManaged(false); });
        slide.play();
    }
}