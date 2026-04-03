package com.healthcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * config.properties를 로드하여 애플리케이션 설정을 관리하는 클래스
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private final Properties props = new Properties();

    public AppConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (in == null) {
                throw new RuntimeException("config.properties 파일을 찾을 수 없습니다.");
            }
            props.load(in);
            log.info("설정 파일 로드 완료");
        } catch (IOException e) {
            throw new RuntimeException("설정 파일 로드 실패: " + e.getMessage(), e);
        }
    }

    public String getTelegramBotToken() {
        return getRequired("telegram.bot.token");
    }

    public String getTelegramChatId() {
        return getRequired("telegram.chat.id");
    }

    public List<String> getWebsites() {
        String raw = getRequired("websites");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public int getCheckIntervalMinutes() {
        return Integer.parseInt(props.getProperty("check.interval.minutes", "5"));
    }

    public long getResponseTimeWarningMs() {
        return Long.parseLong(props.getProperty("response.time.warning.ms", "3000"));
    }

    public long getResponseTimeCriticalMs() {
        return Long.parseLong(props.getProperty("response.time.critical.ms", "5000"));
    }

    public int getSslExpiryWarningDays() {
        return Integer.parseInt(props.getProperty("ssl.expiry.warning.days", "30"));
    }

    public int getSslExpiryCriticalDays() {
        return Integer.parseInt(props.getProperty("ssl.expiry.critical.days", "7"));
    }

    public int getHttpConnectTimeoutSeconds() {
        return Integer.parseInt(props.getProperty("http.connect.timeout.seconds", "10"));
    }

    public int getHttpReadTimeoutSeconds() {
        return Integer.parseInt(props.getProperty("http.read.timeout.seconds", "15"));
    }

    private String getRequired(String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException("필수 설정값 누락: " + key);
        }
        return value.trim();
    }
}
