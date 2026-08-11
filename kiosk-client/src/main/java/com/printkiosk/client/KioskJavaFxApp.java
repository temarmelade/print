package com.printkiosk.client;

import com.printkiosk.client.ui.SpringFxmlLoader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX-Application'у нельзя инжектить зависимости через конструктор
 * (его создаёт сам JavaFX), поэтому контекст Spring мы поднимаем здесь
 * вручную в {@link #init()} и оттуда берём бины.
 *
 * <h2>Портретная дизайн-канва и масштабирование</h2>
 *
 * <p>Интерфейс верстается под фиксированную «дизайн-канву» 900x1600
 * (портретные 9:16), а на реальном экране целиком масштабируется одним
 * преобразованием {@link Scale}. Это даёт три вещи:
 *
 * <ul>
 *   <li>все 480+ пиксельных размеров в CSS остаются как есть — их не нужно
 *       переписывать под каждое разрешение панели;</li>
 *   <li>киоск одинаково выглядит на 1080x1920 (коэффициент 1.2) и на
 *       2560x1440-панели, повёрнутой в портрет (коэффициент 1.6);</li>
 *   <li>пропорции не «плывут»: масштаб по X и Y всегда одинаковый, при
 *       нестандартном соотношении сторон по краям остаются поля
 *       (класс {@code kiosk-letterbox}), а не растянутая картинка.</li>
 * </ul>
 *
 * <p>Канва 900x1600 выбрана под 27-дюймовую панель: её физическая ширина
 * в портрете ~336 мм, то есть один дизайн-пиксель ~0.37 мм. Кнопка высотой
 * 96 px = ~36 мм — заметно выше минимума в 20 мм, который считается нижней
 * границей для сенсорных киосков.
 *
 * <p>Ключ {@code -Dkiosk.ui.fullscreen=false} открывает окно вместо
 * полного экрана (удобно на ноутбуке при разработке — портретный «столбик»
 * просто отрисуется по центру). Ключ {@code -Dkiosk.ui.lock=true} блокирует
 * выход из полноэкранного режима по Esc — включать на реальном киоске.
 */
@Slf4j
public class KioskJavaFxApp extends Application {

    /** Ширина дизайн-канвы в «дизайн-пикселях». Вся вёрстка считается от неё. */
    public static final double DESIGN_W = 900;
    /** Высота дизайн-канвы. 900x1600 = ровно 9:16. */
    public static final double DESIGN_H = 1600;

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        log.info("Bootstrapping Spring context...");
        springContext = new SpringApplicationBuilder(KioskClientApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .headless(false)                       // нам нужен display
                .run();
        log.info("Spring context ready");
    }

    @Override
    public void start(Stage stage) throws Exception {
        SpringFxmlLoader fxmlLoader = springContext.getBean(SpringFxmlLoader.class);
        Parent root = fxmlLoader.load("/fxml/kiosk.fxml");

        // Корень фиксируем в размер канвы. min = pref = max: иначе BorderPane
        // растянулся бы по сцене и масштабирование потеряло бы смысл.
        Region rootRegion = (Region) root;
        rootRegion.setMinSize(DESIGN_W, DESIGN_H);
        rootRegion.setPrefSize(DESIGN_W, DESIGN_H);
        rootRegion.setMaxSize(DESIGN_W, DESIGN_H);

        Scale scale = new Scale(1, 1);
        rootRegion.getTransforms().add(scale);

        // Group нужен как «прозрачная» обёртка: в отличие от Region он
        // сообщает родителю размер УЖЕ отмасштабированного содержимого,
        // поэтому StackPane центрирует канву правильно.
        StackPane canvas = new StackPane(new Group(rootRegion));
        canvas.getStyleClass().add("kiosk-letterbox");

        boolean fullscreen = Boolean.parseBoolean(
                System.getProperty("kiosk.ui.fullscreen", "true"));

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        Scene scene = fullscreen
                ? new Scene(canvas, bounds.getWidth(), bounds.getHeight())
                : new Scene(canvas, DESIGN_W * 0.5, DESIGN_H * 0.5);
        scene.getStylesheets().add(getClass().getResource("/fxml/kiosk.css").toExternalForm());

        // Пересчёт масштаба на каждое изменение размеров сцены: покрывает и
        // старт, и переход в полный экран, и смену разрешения на лету.
        Runnable fit = () -> {
            double s = Math.min(scene.getWidth() / DESIGN_W, scene.getHeight() / DESIGN_H);
            if (s > 0 && Double.isFinite(s)) {
                scale.setX(s);
                scale.setY(s);
            }
        };
        scene.widthProperty().addListener((o, a, b) -> fit.run());
        scene.heightProperty().addListener((o, a, b) -> fit.run());
        fit.run();

        stage.setTitle("PrintKiosk");
        stage.setScene(scene);

        if (fullscreen) {
            stage.setFullScreenExitHint("");
            if (Boolean.parseBoolean(System.getProperty("kiosk.ui.lock", "false"))) {
                // Реальный киоск: Esc не должен выкидывать пользователя в Windows.
                stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
            }
            stage.setFullScreen(true);
        }

        stage.show();
        log.info("UI canvas {}x{}, screen {}x{}, scale {}",
                (int) DESIGN_W, (int) DESIGN_H,
                (int) bounds.getWidth(), (int) bounds.getHeight(),
                String.format("%.3f", scale.getX()));
    }

    @Override
    public void stop() {
        log.info("Closing Spring context...");
        if (springContext != null) {
            springContext.close();
        }
        Platform.exit();
        System.exit(0);
    }
}
