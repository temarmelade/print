package com.printkiosk.client.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;

@Component
@RequiredArgsConstructor
public class SpringFxmlLoader {

    private final ApplicationContext context;

    public <T extends Parent> T load(String fxmlPath) throws IOException {
        URL location = getClass().getResource(fxmlPath);
        if (location == null) {
            throw new IllegalArgumentException("FXML not found on classpath: " + fxmlPath);
        }

        FXMLLoader loader = new FXMLLoader(location);
        loader.setControllerFactory(context::getBean);
        return loader.load();
    }
}
