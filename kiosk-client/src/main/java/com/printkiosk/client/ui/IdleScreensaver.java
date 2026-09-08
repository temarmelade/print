package com.printkiosk.client.ui;

import com.printkiosk.shared.api.AdMediaType;
import com.printkiosk.shared.api.dto.AdCreativeDto;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class IdleScreensaver extends StackPane {

    private final ImageView imageView = new ImageView();
    private final MediaView mediaView = new MediaView();

    private List<AdCreativeDto> playlist = List.of();
    private int index = 0;

    private MediaPlayer currentPlayer;
    private PauseTransition imageTimer;

    private static final int DEFAULT_IMAGE_SECONDS = 8;

    private final com.printkiosk.client.service.AdMediaCache mediaCache;

    public IdleScreensaver(com.printkiosk.client.service.AdMediaCache mediaCache) {
        this.mediaCache = mediaCache;

        getStyleClass().add("idle-screensaver");
        imageView.setPreserveRatio(true);
        // smooth=false осознанно. У анимированных GIF фильтрация применяется
        // К КАЖДОМУ кадру при растягивании на весь экран, и на слабом мини-ПК
        // это главный источник рывков. Для статичных картинок разница в
        // качестве на такой диагонали незаметна.
        imageView.setSmooth(false);
        // Кешируем отрисованный узел: анимация GIF всё равно обновляет
        // содержимое, но масштабирование пересчитывается реже.
        imageView.setCache(true);
        imageView.setCacheHint(javafx.scene.CacheHint.SPEED);
        mediaView.setPreserveRatio(true);

        imageView.fitWidthProperty().bind(widthProperty());
        imageView.fitHeightProperty().bind(heightProperty());
        mediaView.fitWidthProperty().bind(widthProperty());
        mediaView.fitHeightProperty().bind(heightProperty());

        getChildren().addAll(imageView, mediaView);
        setVisible(false);
        setManaged(false);
    }

    public void start(List<AdCreativeDto> items) {
        this.playlist = (items != null) ? items : List.of();
        this.index = 0;
        if (playlist.isEmpty()) {
            log.debug("Screensaver start skipped: empty playlist");
            return;
        }
        setVisible(true);
        setManaged(true);
        playCurrent();
    }

    public void stop() {
        disposeCurrent();
        setVisible(false);
        setManaged(false);
    }

    public boolean hasContent() {
        return !playlist.isEmpty();
    }

    private void playCurrent() {
        disposeCurrent();
        if (playlist.isEmpty()) return;

        AdCreativeDto ad = playlist.get(index);

        // Только локальный файл. Сетевой адрес плееру не отдаём: JavaFX
        // качает сам, без заголовка X-Kiosk-Key, и получает 403 —
        // именно так на экране появлялся чёрный фон.
        String url = mediaCache.localUri(ad);
        if (url == null) {
            log.warn("Ролик {} ещё не закеширован — пропускаем", ad.id());
            next();
            return;
        }

        if (ad.mediaType() == AdMediaType.VIDEO) {
            playVideo(url);
        } else {
            playImage(ad, url);
        }
    }

    private void playImage(AdCreativeDto ad, String url) {
        imageView.setVisible(true);
        mediaView.setVisible(false);

        // false = грузим синхронно: файл лежит на диске, ждать нечего,
        // а при фоновой загрузке слайд мог смениться раньше отрисовки.
        imageView.setImage(new Image(url, false));

        int seconds = (ad.durationSec() != null && ad.durationSec() > 0)
                ? ad.durationSec()
                : DEFAULT_IMAGE_SECONDS;

        imageTimer = new PauseTransition(Duration.seconds(seconds));
        imageTimer.setOnFinished(e -> next());
        imageTimer.play();
    }

    private void playVideo(String url) {
        imageView.setVisible(false);
        mediaView.setVisible(true);

        try {
            Media media = new Media(url);
            currentPlayer = new MediaPlayer(media);
            currentPlayer.setOnEndOfMedia(this::next);
            currentPlayer.setOnError(() -> {
                log.warn("Screensaver video error for {}: {}", url,
                        currentPlayer.getError() != null ? currentPlayer.getError().getMessage() : "unknown");
                next();
            });
            mediaView.setMediaPlayer(currentPlayer);
            currentPlayer.play();
        } catch (Exception e) {
            log.warn("Failed to play video {}: {}", url, e.getMessage());
            next();
        }
    }

    private void next() {
        if (playlist.isEmpty()) return;
        index = (index + 1) % playlist.size();
        Platform.runLater(this::playCurrent);
    }

    private void disposeCurrent() {
        if (imageTimer != null) {
            imageTimer.stop();
            imageTimer = null;
        }
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer.dispose();
            currentPlayer = null;
        }
    }

}
