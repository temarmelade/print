package com.printkiosk.client.ui;

import com.printkiosk.client.ui.state.Language;
import javafx.animation.TranslateTransition;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Кастомная экранная клавиатура в стиле киоска. Монтируется в корневой
 * контейнер (rootStack), выезжает снизу при фокусе на привязанном поле и
 * прячется по кнопке-галочке. Раскладка переключается по языку из стейта.
 *
 * Допускаются только безопасные для имени файла символы: буквы, цифры,
 * пробел, дефис, подчёркивание. Запрещённые (/ \ : * ? " < > | .) отсутствуют
 * на клавиатуре И режутся TextFormatter'ом (на случай аппаратного ввода).
 */
public class VirtualKeyboard extends VBox {

    private static final String SAFE_REGEX = "[\\p{L}\\d _-]*";

    private static final String[][] LAYOUT_LAT = {
            {"q","w","e","r","t","y","u","i","o","p"},
            {"a","s","d","f","g","h","j","k","l"},
            {"z","x","c","v","b","n","m"}
    };
    private static final String[][] LAYOUT_CYR = {
            {"й","ц","у","к","е","н","г","ш","щ","з","х"},
            {"ф","ы","в","а","п","р","о","л","д","ж","э"},
            {"я","ч","с","м","и","т","ь","б","ю"}
    };

    private TextField target;
    private final TranslateTransition slide;
    private boolean shown = false;

    /** Регистр ввода. false — строчные, true — заглавные. */
    private boolean capsOn = false;
    /** Кнопки-буквы (для смены регистра подписей при переключении Caps). */
    private final java.util.List<Button> letterButtons = new java.util.ArrayList<>();
    private FontIcon capsIcon;

    public VirtualKeyboard() {
        getStyleClass().add("virtual-keyboard");
        setVisible(false);
        setManaged(false);
        // Сжимаем по содержимому: иначе VBox растянется на всю ширину rootStack
        // и серый фон уйдёт во весь экран. USE_PREF_SIZE = размер по кнопкам.
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        slide = new TranslateTransition(Duration.millis(180), this);
    }

    /**
     * Привязать клавиатуру к полю: показывать при фокусе, фильтровать ввод.
     * Вызывай при инициализации экрана ScanStart.
     */
    public void attachTo(TextField field, Language lang) {
        this.target = field;
        rebuild(lang);

        field.setTextFormatter(new TextFormatter<>(c ->
                c.getControlNewText().matches(SAFE_REGEX) ? c : null));

        field.focusedProperty().addListener((obs, was, now) -> {
            if (now) show();
        });

        // Скрытие при касании в любой точке ВНЕ клавиатуры и вне самого поля.
        // Ставим фильтр на сцену, когда она станет доступна.
        sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene != null) {
                scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::handleOutsideTap);
                scene.addEventFilter(TouchEvent.TOUCH_PRESSED, this::handleOutsideTap);
            }
        });
    }

    /** Если тап пришёлся не по клавиатуре и не по полю — прячем клавиатуру. */
    private void handleOutsideTap(javafx.event.Event e) {
        if (!shown) return;
        javafx.scene.Node t = (javafx.scene.Node) e.getTarget();
        // Проверяем, находится ли цель внутри клавиатуры или это само поле.
        for (javafx.scene.Node n = t; n != null; n = n.getParent()) {
            if (n == this || n == target) return;   // тап по клавиатуре/полю — не прячем
        }
        hideKeyboard();
    }

    /** Перестроить раскладку под язык (вызывать при смене языка). */
    public void rebuild(Language lang) {
        getChildren().clear();
        letterButtons.clear();
        String[][] layout = (lang == Language.RU) ? LAYOUT_CYR : LAYOUT_LAT;
        for (int i = 0; i < layout.length; i++) {
            HBox r = new HBox(6);
            r.getStyleClass().add("vk-row");
            // Перед последним рядом букв — кнопка CapsLock (слева от «я»/«z»).
            if (i == layout.length - 1) {
                r.getChildren().add(capsKey());
            }
            for (String key : layout[i]) r.getChildren().add(letterKey(key));
            getChildren().add(r);
        }
        getChildren().add(bottomRow());
        applyCapsToLabels();
    }

    // ── клавиши ────────────────────────────────────────────────────

    private Button letterKey(String baseChar) {
        Button b = new Button(baseChar);
        b.getStyleClass().add("vk-key");
        b.setFocusTraversable(false);
        // baseChar храним строчным; при вставке/подписи применяем регистр.
        b.getProperties().put("base", baseChar);
        b.setOnMousePressed(e -> {
            insert(capsOn ? baseChar.toUpperCase() : baseChar);
            e.consume();
        });
        letterButtons.add(b);
        return b;
    }

    /** Кнопка переключения регистра (CapsLock). */
    private Button capsKey() {
        Button b = new Button();
        capsIcon = new FontIcon("fas-arrow-up");
        b.setGraphic(capsIcon);
        b.getStyleClass().addAll("vk-key", "vk-key-caps");
        b.setFocusTraversable(false);
        b.setOnMousePressed(e -> {
            capsOn = !capsOn;
            applyCapsToLabels();
            e.consume();
        });
        return b;
    }

    /** Обновляет подписи букв и подсветку Caps под текущий регистр. */
    private void applyCapsToLabels() {
        for (Button b : letterButtons) {
            String base = (String) b.getProperties().get("base");
            b.setText(capsOn ? base.toUpperCase() : base);
        }
        if (capsIcon != null) {
            capsIcon.getStyleClass().remove("caps-active");
            if (capsOn) capsIcon.getStyleClass().add("caps-active");
        }
    }

    private HBox bottomRow() {
        HBox row = new HBox(6);
        row.getStyleClass().add("vk-row");

        Button space = new Button("пробел");
        space.getStyleClass().addAll("vk-key", "vk-key-wide");
        space.setFocusTraversable(false);
        space.setOnMousePressed(e -> { insert(" "); e.consume(); });

        Button backspace = new Button();
        backspace.setGraphic(new FontIcon("fas-backspace"));
        backspace.getStyleClass().add("vk-key");
        backspace.setFocusTraversable(false);
        backspace.setOnMousePressed(e -> { backspace(); e.consume(); });

        Button done = new Button();
        done.setGraphic(new FontIcon("fas-check"));
        done.getStyleClass().addAll("vk-key", "vk-key-done");
        done.setFocusTraversable(false);
        done.setOnMousePressed(e -> { hideKeyboard(); e.consume(); });

        row.getChildren().addAll(backspace, space, done);
        return row;
    }

    // ── редактирование поля ────────────────────────────────────────

    private void insert(String s) {
        if (target == null) return;
        int pos = target.getCaretPosition();
        target.insertText(pos, s);
        target.positionCaret(pos + s.length());   // каретка вперёд, без сброса в начало
    }

    private void backspace() {
        if (target == null) return;
        int pos = target.getCaretPosition();
        if (pos > 0) {
            target.deleteText(pos - 1, pos);
            target.positionCaret(pos - 1);
        }
    }

    // ── показ / скрытие ────────────────────────────────────────────

    private void show() {
        if (shown) return;
        shown = true;
        setVisible(true);
        setManaged(true);
        double h = Math.max(getHeight(), prefHeight(-1));
        slide.stop();
        setTranslateY(h);
        slide.setFromY(h);
        slide.setToY(0);
        slide.setOnFinished(null);
        slide.play();
    }

    /** Спрятать клавиатуру (по кнопке-галочке), сохранив текст в поле. */
    public void hideKeyboard() {
        if (!shown) return;
        shown = false;
        double h = Math.max(getHeight(), prefHeight(-1));
        slide.stop();
        slide.setFromY(0);
        slide.setToY(h);
        slide.setOnFinished(e -> {
            setVisible(false);
            setManaged(false);
        });
        slide.play();
    }

    public boolean isShown() { return shown; }
}