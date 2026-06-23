package com.printkiosk.client.service.i18n;

import com.printkiosk.ui.state.Language;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Service
public class LocalizationService {

    private static final String PROPERTIES_PATH_TEMPLATE = "i18n/messages_%s.properties";

    private final Map<Language, Properties> messages = new EnumMap<>(Language.class);

    @PostConstruct
    void init() {
        for (Language lang : Language.values()) {
            loadProperties(lang);
        }
    }

    private void loadProperties(Language lang) {
        String path = PROPERTIES_PATH_TEMPLATE.formatted(lang.name().toLowerCase());

        try {
            ClassPathResource resource = new ClassPathResource(path);

            if (!resource.exists()) {
                log.error("Translation file missing: {}", path);
                messages.put(lang, new Properties());
                return;
            }

            Properties props = new Properties();
            try (InputStream is = resource.getInputStream();
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                props.load(reader);
            }

            messages.put(lang, props);
            log.info("Loaded {} translation keys for {}", props.size(), lang);

        } catch (Exception e) {
            log.error("Failed to load translations for {}", lang, e);
            messages.put(lang, new Properties());
        }
    }

    public String get(String key, Language lang) {
        if (key == null) {
            return "";
        }

        Properties props = messages.get(lang);
        if (props != null) {
            String value = props.getProperty(key);
            if (value != null) {
                return value;
            }
        }

        // Fallback: RU
        Properties ruProps = messages.get(Language.RU);
        if (ruProps != null) {
            String value = ruProps.getProperty(key);
            if (value != null) {
                log.warn("Missing translation key '{}' for {}, fell back to RU", key, lang);
                return value;
            }
        }

        log.warn("Missing translation key '{}' in all languages", key);
        return key;
    }

    public String get(String key, Language lang, Object... args) {
        String template = get(key, lang);
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return java.text.MessageFormat.format(template, args);
        } catch (Exception e) {
            log.warn("Failed to format translation '{}' ({})", key, lang, e);
            return template;
        }
    }
}