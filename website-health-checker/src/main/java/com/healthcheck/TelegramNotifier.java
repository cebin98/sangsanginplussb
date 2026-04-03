package com.healthcheck;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 텔레그램 Bot API를 통해 알림 메시지를 전송하는 클래스
 *
 * 사용법:
 *   1. @BotFather 에서 봇 생성 후 토큰 발급
 *   2. 봇과 대화 시작 후 chat id 확인:
 *      https://api.telegram.org/bot<TOKEN>/getUpdates
 */
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final String TELEGRAM_API = "https://api.telegram.org/bot";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String botToken;
    private final String chatId;
    private final OkHttpClient httpClient;

    public TelegramNotifier(AppConfig config) {
        this.botToken = config.getTelegramBotToken();
        this.chatId = config.getTelegramChatId();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 체크 결과를 텔레그램으로 전송 (알림이 필요한 경우에만)
     */
    public void notifyIfNeeded(CheckResult result) {
        if (!result.isAlertNeeded()) {
            return;
        }
        String message = buildAlertMessage(result);
        sendMessage(message);
    }

    /**
     * 체크 요약 리포트를 텔레그램으로 전송
     */
    public void sendReport(java.util.List<CheckResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *웹사이트 헬스체크 리포트*\n");
        sb.append("🕐 ").append(java.time.LocalDateTime.now().format(FORMATTER)).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        long okCount = results.stream().filter(r -> r.getStatus() == CheckResult.Status.OK).count();
        long warnCount = results.stream().filter(r -> r.getStatus() == CheckResult.Status.WARNING).count();
        long critCount = results.stream().filter(r -> r.getStatus() == CheckResult.Status.CRITICAL).count();
        long downCount = results.stream().filter(r -> r.getStatus() == CheckResult.Status.DOWN).count();

        sb.append("✅ 정상: ").append(okCount).append("개  ");
        sb.append("⚠️ 경고: ").append(warnCount).append("개  ");
        sb.append("🔴 위험: ").append(critCount).append("개  ");
        sb.append("💀 다운: ").append(downCount).append("개\n\n");

        for (CheckResult result : results) {
            sb.append(buildResultLine(result)).append("\n");
        }

        sendMessage(sb.toString());
    }

    /**
     * 단일 알림 메시지 빌드
     */
    private String buildAlertMessage(CheckResult result) {
        StringBuilder sb = new StringBuilder();

        switch (result.getStatus()) {
            case DOWN:
                sb.append("🚨 *사이트 다운 감지!*\n");
                break;
            case CRITICAL:
                sb.append("🔴 *위험 상태 감지!*\n");
                break;
            case WARNING:
                sb.append("⚠️ *경고 상태 감지!*\n");
                break;
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🏢 사이트: ").append(result.getDisplayName()).append("\n");
        sb.append("🌐 URL: ").append(result.getUrl()).append("\n");
        sb.append("🕐 시각: ").append(result.getCheckedAt().format(FORMATTER)).append("\n");

        if (result.getHttpStatusCode() > 0) {
            sb.append("📋 HTTP: ").append(result.getHttpStatusCode()).append("\n");
        }
        if (result.getResponseTimeMs() >= 0) {
            sb.append("⏱️ 응답: ").append(result.getResponseTimeMs()).append("ms\n");
        }
        if (result.getSslDaysRemaining() >= 0) {
            sb.append("🔒 SSL: ").append(result.getSslDaysRemaining()).append("일 남음\n");
        } else if (result.getSslDaysRemaining() == -2) {
            sb.append("🔒 SSL: 인증서 오류\n");
        }
        if (result.getErrorMessage() != null) {
            sb.append("❌ 오류: ").append(result.getErrorMessage()).append("\n");
        }

        return sb.toString();
    }

    private String buildResultLine(CheckResult result) {
        StringBuilder sb = new StringBuilder();
        String emoji;
        switch (result.getStatus()) {
            case OK:       emoji = "✅"; break;
            case WARNING:  emoji = "⚠️"; break;
            case CRITICAL: emoji = "🔴"; break;
            case DOWN:     emoji = "💀"; break;
            default:       emoji = "❓"; break;
        }

        sb.append(emoji).append(" ").append(result.getDisplayName());
        if (result.getResponseTimeMs() >= 0) {
            sb.append(" (").append(result.getResponseTimeMs()).append("ms)");
        }
        if (result.getHttpStatusCode() > 0) {
            sb.append(" HTTP ").append(result.getHttpStatusCode());
        }
        if (result.getSslDaysRemaining() >= 0) {
            sb.append(" | SSL ").append(result.getSslDaysRemaining()).append("일");
        }
        if (result.getErrorMessage() != null) {
            sb.append("\n  └ ").append(result.getErrorMessage());
        }
        return sb.toString();
    }

    /**
     * 매일 09:00 SSL 인증서 현황 리포트 전송
     */
    public void sendSslReport(java.util.List<CheckResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *일일 SSL 현황 리포트*\n");
        sb.append("🕐 ").append(java.time.LocalDateTime.now().format(FORMATTER)).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        for (CheckResult result : results) {
            int days = result.getSslDaysRemaining();
            String sslEmoji;
            String sslText;

            if (days < 0) {
                sslEmoji = "❓";
                sslText = "확인 불가";
            } else if (days <= 7) {
                sslEmoji = "🔴";
                sslText = days + "일 남음 (긴급 갱신 필요!)";
            } else if (days <= 30) {
                sslEmoji = "⚠️";
                sslText = days + "일 남음 (갱신 필요)";
            } else {
                sslEmoji = "🔒";
                sslText = days + "일 남음";
            }

            sb.append(sslEmoji).append(" ").append(result.getDisplayName()).append("\n");
            sb.append("   └ SSL ").append(sslText).append("\n");
        }

        sendMessage(sb.toString());
        log.info("SSL 일일 리포트 전송 완료");
    }

    /**
     * 텔레그램 API로 메시지 전송
     */
    public void sendMessage(String text) {
        String url = TELEGRAM_API + botToken + "/sendMessage";

        RequestBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("텔레그램 메시지 전송 성공");
            } else {
                log.error("텔레그램 전송 실패: {} {}", response.code(), response.message());
            }
        } catch (IOException e) {
            log.error("텔레그램 전송 오류: {}", e.getMessage());
        }
    }
}
