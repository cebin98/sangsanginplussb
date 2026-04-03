package com.healthcheck;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
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
        sendMessage(buildPartnerAlertMessage(result));
    }

    /**
     * 매일 09:00 전체 일일 요약 전송
     * - 파트너 인바운드/아웃바운드 HTTP 상태 표
     * - 자사 사이트 HTTP 상태 + SSL 만료일 표
     */
    public void sendDailySummary(List<PartnerCheckResult> partnerResults, List<CheckResult> ownSiteResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *일일 헬스체크 요약*\n");
        sb.append("🕐 ").append(LocalDateTime.now().format(FORMATTER)).append("\n\n");

        // 파트너 표
        sb.append("```\n");
        sb.append("혁신채널명       IN    OUT\n");
        sb.append("---------------+-----+-----\n");
        int okCount = 0, warnCount = 0, downCount = 0;
        for (PartnerCheckResult pr : partnerResults) {
            String name = padKorean(pr.getPartner().getName(), 15);
            String inStr  = pr.getInboundResult()  != null ? tableStatus(pr.getInboundResult())  : " -- ";
            String outStr = pr.getOutboundResult() != null ? tableStatus(pr.getOutboundResult()) : " -- ";
            sb.append(name).append("| ").append(inStr).append(" | ").append(outStr).append("\n");

            // 집계
            if (pr.getInboundResult() != null) {
                switch (pr.getInboundResult().getStatus()) {
                    case OK: okCount++; break;
                    case WARNING: warnCount++; break;
                    default: downCount++; break;
                }
            }
            if (pr.getOutboundResult() != null) {
                switch (pr.getOutboundResult().getStatus()) {
                    case OK: okCount++; break;
                    case WARNING: warnCount++; break;
                    default: downCount++; break;
                }
            }
        }
        sb.append("```\n\n");

        // 자사 사이트 표
        sb.append("```\n");
        sb.append("자사 사이트                        상태  SSL\n");
        sb.append("----------------------------------+----+------\n");
        for (CheckResult r : ownSiteResults) {
            String shortName = padRight(shortenSiteName(r.getUrl()), 34);
            String status = tableStatus(r);
            String ssl = r.getSslDaysRemaining() >= 0 ? r.getSslDaysRemaining() + "일" : " - ";
            sb.append(shortName).append("| ").append(status).append(" | ").append(ssl).append("\n");

            switch (r.getStatus()) {
                case OK: okCount++; break;
                case WARNING: warnCount++; break;
                default: downCount++; break;
            }
        }
        sb.append("```\n");

        // 집계 한 줄
        sb.append("✅ 정상 ").append(okCount)
          .append("  ⚠️ 경고 ").append(warnCount)
          .append("  ❌ 이상 ").append(downCount);

        sendMessage(sb.toString());
        log.info("일일 요약 전송 완료");
    }

    // ──────────────────────────────────────────────────
    // private helpers
    // ──────────────────────────────────────────────────

    private String buildPartnerAlertMessage(PartnerCheckResult partnerResult) {
        CheckResult inbound = partnerResult.getInboundResult();
        CheckResult outbound = partnerResult.getOutboundResult();

        CheckResult.Status worstStatus = CheckResult.Status.OK;
        if (inbound != null && inbound.isAlertNeeded())   worstStatus = worse(worstStatus, inbound.getStatus());
        if (outbound != null && outbound.isAlertNeeded()) worstStatus = worse(worstStatus, outbound.getStatus());

        StringBuilder sb = new StringBuilder();
        switch (worstStatus) {
            case DOWN:     sb.append("🚨 *연결 이상 감지!*\n"); break;
            case CRITICAL: sb.append("🔴 *위험 상태 감지!*\n"); break;
            default:       sb.append("⚠️ *경고 상태 감지!*\n"); break;
        }

        sb.append("🕐 ").append(LocalDateTime.now().format(FORMATTER)).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🏢 *").append(partnerResult.getPartner().getName()).append("*\n");

        if (outbound != null) {
            sb.append("\n📤 아웃바운드: ").append(statusLine(outbound)).append("\n");
            sb.append("   ").append(outbound.getUrl()).append("\n");
            if (outbound.getErrorMessage() != null) {
                sb.append("   오류: ").append(outbound.getErrorMessage()).append("\n");
            }
        }
        if (inbound != null) {
            sb.append("\n📥 인바운드: ").append(statusLine(inbound)).append("\n");
            sb.append("   ").append(inbound.getUrl()).append("\n");
            if (inbound.getErrorMessage() != null) {
                sb.append("   오류: ").append(inbound.getErrorMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 표 안 상태 셀 (4자 고정폭, 코드블록용) */
    private String tableStatus(CheckResult result) {
        switch (result.getStatus()) {
            case OK:       return " OK ";
            case WARNING:  return "WRN ";
            case CRITICAL: return "CRT ";
            case DOWN:     return "ERR ";
            default:       return " ?? ";
        }
    }

    private String statusLine(CheckResult result) {
        switch (result.getStatus()) {
            case OK:
                String ok = "✅ 정상";
                if (result.getResponseTimeMs() >= 0) ok += " (" + result.getResponseTimeMs() + "ms)";
                return ok;
            case WARNING:  return "⚠️ 경고 (" + result.getResponseTimeMs() + "ms)";
            case CRITICAL: return "🔴 위험 (" + result.getResponseTimeMs() + "ms)";
            case DOWN:     return "❌ 다운";
            default:       return "❓ 알 수 없음";
        }
    }

    private CheckResult.Status worse(CheckResult.Status a, CheckResult.Status b) {
        int[] order = new int[CheckResult.Status.values().length];
        order[CheckResult.Status.OK.ordinal()]       = 0;
        order[CheckResult.Status.WARNING.ordinal()]  = 1;
        order[CheckResult.Status.CRITICAL.ordinal()] = 2;
        order[CheckResult.Status.DOWN.ordinal()]     = 3;
        return order[a.ordinal()] >= order[b.ordinal()] ? a : b;
    }

    /** 자사 사이트 URL에서 호스트명 추출 */
    private String shortenSiteName(String url) {
        try {
            return url.replaceFirst("https?://", "").split("/")[0];
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 한글 포함 문자열을 고정폭으로 패딩
     * 한글 1자 = 화면폭 2, ASCII 1자 = 화면폭 1
     */
    private String padKorean(String s, int targetWidth) {
        int width = 0;
        for (char c : s.toCharArray()) {
            width += (c >= 0xAC00 && c <= 0xD7A3) ? 2 : 1;
        }
        int pad = targetWidth - width;
        if (pad <= 0) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) sb.append(' ');
        return sb.toString();
    }

    private String padRight(String s, int len) {
        if (s.length() >= len) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
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
