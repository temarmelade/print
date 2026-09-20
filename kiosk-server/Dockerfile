# ─────────────────────────────────────────────────────────────────
#  Сборка kiosk-server. Двухэтапная: в финальный образ попадает
#  только jar и JRE, без Maven и исходников (~200 МБ вместо ~800).
#
#  Собирать из КОРНЯ репозитория, а не из kiosk-server:
#      docker build -f kiosk-server/Dockerfile -t kiosk-server .
#  Модуль зависит от kiosk-shared, и контекст сборки должен их видеть.
# ─────────────────────────────────────────────────────────────────

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Сначала только pom-файлы: слой с зависимостями кешируется и не
# пересобирается, пока не менялись сами зависимости.
COPY pom.xml .
COPY kiosk-shared/pom.xml kiosk-shared/
COPY kiosk-server/pom.xml kiosk-server/
COPY kiosk-client/pom.xml kiosk-client/
RUN mvn -q -pl kiosk-shared,kiosk-server -am dependency:go-offline -DskipTests

COPY kiosk-shared/src kiosk-shared/src
COPY kiosk-server/src kiosk-server/src
# kiosk-client не собираем: это десктопное приложение с JavaFX,
# серверу оно не нужно и тянуло бы лишние 100+ МБ зависимостей.
RUN mvn -q -pl kiosk-shared,kiosk-server -am package -DskipTests

# ─────────────────────────────────────────────────────────────────

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Не root: пробой в приложении не должен давать права на весь контейнер.
# curl нужен для HEALTHCHECK: в jre-jammy его нет, и проверка молча
# падала бы, оставляя контейнер навсегда в статусе unhealthy.
# ffmpeg перегоняет загруженные GIF в H.264: GIF почти не сжат, а его
# декодер в JavaFX работает на потоке отрисовки и даёт рывки на киоске.
#
# LibreOffice конвертирует присланные DOC/DOCX в PDF. Без него загрузка
# любого Word-файла падала. Ставим только Writer и без графической части
# (-nogui): X11, GTK и Java для конвертации не нужны.
# Шрифты — метрически совместимые замены офисных: без них LibreOffice
# подставляет что попало, документ перевёрстывается, и число страниц
# (а с ним и цена) расходится с тем, что человек видел в Word:
#   fonts-liberation2       — Times New Roman, Arial, Courier New
#   fonts-crosextra-carlito — Calibri (шрифт Word по умолчанию)
#   fonts-crosextra-caladea — Cambria
#   fonts-dejavu-core       — запасной, с полной кириллицей (ң, ө, ү)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ffmpeg \
        libreoffice-writer-nogui \
        fonts-liberation2 fonts-crosextra-carlito fonts-crosextra-caladea fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/* \
 && soffice --headless --version \
 && groupadd -r kiosk && useradd -r -g kiosk kiosk \
 && mkdir -p /var/lib/kiosk/uploads /var/log/kiosk \
 && chown -R kiosk:kiosk /var/lib/kiosk /var/log/kiosk

COPY --from=build /build/kiosk-server/target/*.jar app.jar

USER kiosk
EXPOSE 8080

# Контейнер считается живым, только когда Spring реально поднялся.
# start-period даёт время на миграции Flyway при первом запуске.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# UTC внутри контейнера: время в БД и логах не должно зависеть от
# часового пояса хоста.
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
