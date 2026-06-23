package com.printkiosk.client;

import com.printkiosk.client.ui.SpringFxmlLoader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX-Application'у нельзя инжектить зависимости через конструктор
 * (его создаёт сам JavaFX), поэтому контекст Spring мы поднимаем здесь
 * вручную в {@link #init()} и оттуда берём бины.
 */
@Slf4j
public class KioskJavaFxApp extends Application {

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

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/fxml/kiosk.css").toExternalForm());

        stage.setTitle("PrintKiosk");
        stage.setScene(scene);
        stage.show();
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
