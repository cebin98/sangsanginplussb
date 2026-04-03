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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 텔레그램 Bot API를 통해 알림 메시지를 전송하는 클래스
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
     * 파트너 체크 결과를 텔레그램으로 전송 (이상이 있는 경우에만)
     */
    public void notifyIfNeeded(PartnerCheckResult result) {
        if (!result.isAlertNeeded()) {
            return;
        }
        String message = buildPartnerAlertMessage(result);
        sendMessage(message);
    }

    /**
     * 파트너 인바운드/아웃바운드 알림 메시지 빌드
     *
     * 예시:
     * 🚨 연결 이상 감지!
     * 🕐 2026-04-03 14:32:10
     * ━━━━━━━━━━━━━━━━━━━━
     * 🏢 카카오뱅크
     *
     * 📤 아웃바운드: ❌ 다운
     *    https://loan-partner.kakaobank.io
     *    오류: Connection timeout
     *
     * 📥 인바운드: ✅ 정상 (218ms)
     */
    private String buildPartnerAlertMessage(PartnerCheckResult partnerResult) {
        CheckResult inbound = partnerResult.getInboundResult();
        CheckResult outbound = partnerResult.getOutboundResult();

        // 전체 심각도 결정
        CheckResult.Status worstStatus = CheckResult.Status.OK;
        if (inbound != null && inbound.isAlertNeeded()) {
            worstStatus = worse(worstStatus, inbound.getStatus());
        }
        if (outbound != null && outbound.isAlertNeeded()) {
            worstStatus = worse(worstStatus, outbound.getStatus());
        }

        StringBuilder sb = new StringBuilder();

        switch (worstStatus) {
            case DOWN:
                sb.append("🚨 *연결 이상 감지!*\n");
                break;
            case CRITICAL:
                sb.append("🔴 *위험 상태 감지!*\n");
                break;
            case WARNING:
                sb.append("⚠️ *경고 상태 감지!*\n");
                break;
            default:
                sb.append("ℹ️ *상태 변경*\n");
                break;
        }

        sb.append("🕐 ").append(java.time.LocalDateTime.now().format(FORMATTER)).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🏢 *").append(partnerResult.getPartner().getName()).append("*\n");

        // 아웃바운드
        if (outbound != null) {
            sb.append("\n📤 아웃바운드: ").append(statusLine(outbound)).append("\n");
            sb.append("   ").append(outbound.getUrl()).append("\n");
            if (outbound.getErrorMessage() != null) {
                sb.append("   오류: ").append(outbound.getErrorMessage()).append("\n");
            }
        }

        // 인바운드
        if (inbound != null) {
            sb.append("\n📥 인바운드: ").append(statusLine(inbound)).append("\n");
            sb.append("   ").append(inbound.getUrl()).append("\n");
            if (inbound.getErrorMessage() != null) {
                sb.append("   오류: ").append(inbound.getErrorMessage()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * CheckResult 한 줄 요약 (예: ✅ 정상 (218ms) 또는 ❌ 다운)
     */
    private String statusLine(CheckResult result) {
        switch (result.getStatus()) {
            case OK:
                StringBuilder ok = new StringBuilder("✅ 정상");
                if (result.getResponseTimeMs() >= 0) {
                    ok.append(" (").append(result.getResponseTimeMs()).append("ms)");
                }
                return ok.toString();
            case WARNING:
                return "⚠️ 경고 (" + result.getResponseTimeMs() + "ms)";
            case CRITICAL:
                return "🔴 위험 (" + result.getResponseTimeMs() + "ms)";
            case DOWN:
                return "❌ 다운";
            default:
                return "❓ 알 수 없음";
        }
    }

    /**
     * 두 상태 중 더 심각한 것을 반환
     */
    private CheckResult.Status worse(CheckResult.Status a, CheckResult.Status b) {
        int[] order = new int[CheckResult.Status.values().length];
        order[CheckResult.Status.OK.ordinal()] = 0;
        order[CheckResult.Status.WARNING.ordinal()] = 1;
        order[CheckResult.Status.CRITICAL.ordinal()] = 2;
        order[CheckResult.Status.DOWN.ordinal()] = 3;
        return order[a.ordinal()] >= order[b.ordinal()] ? a : b;
    }

    /**
     * 매일 09:00 SSL 인증서 현황 리포트 전송
     */
    public void sendSslReport(List<CheckResult> results) {
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

            sb.append(sslEmoji).append(" ").append(result.getUrl()).append("\n");
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
