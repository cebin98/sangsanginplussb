package com.healthcheck;

import java.time.LocalDateTime;

/**
 * 웹사이트 체크 결과를 담는 데이터 클래스
 */
public class CheckResult {

    public enum Status {
        OK,       // 정상
        WARNING,  // 경고 (느린 응답, SSL 만료 임박 등)
        CRITICAL, // 위험 (매우 느린 응답, SSL 곧 만료)
        DOWN      // 사이트 다운
    }

    private final String url;
    private String displayName; // 한글명 (없으면 url 사용)
    private final LocalDateTime checkedAt;
    private Status status;
    private int httpStatusCode;
    private long responseTimeMs;
    private int sslDaysRemaining; // -1 = HTTPS 아님, -2 = 확인 실패
    private String errorMessage;

    public CheckResult(String url) {
        this.url = url;
        this.displayName = url;
        this.checkedAt = LocalDateTime.now();
        this.status = Status.OK;
        this.httpStatusCode = -1;
        this.responseTimeMs = -1;
        this.sslDaysRemaining = -1;
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append("\n");
        sb.append("상태: ").append(getStatusEmoji()).append(" ").append(status).append("\n");

        if (httpStatusCode > 0) {
            sb.append("HTTP 코드: ").append(httpStatusCode).append("\n");
        }
        if (responseTimeMs >= 0) {
            sb.append("응답 시간: ").append(responseTimeMs).append("ms\n");
        }
        if (sslDaysRemaining >= 0) {
            sb.append("SSL 만료: ").append(sslDaysRemaining).append("일 남음\n");
        } else if (sslDaysRemaining == -2) {
            sb.append("SSL: 확인 실패\n");
        }
        if (errorMessage != null) {
            sb.append("오류: ").append(errorMessage).append("\n");
        }
        return sb.toString();
    }

    private String getStatusEmoji() {
        switch (status) {
            case OK:       return "✅";
            case WARNING:  return "⚠️";
            case CRITICAL: return "🔴";
            case DOWN:     return "💀";
            default:       return "❓";
        }
    }

    public boolean isAlertNeeded() {
        return status == Status.WARNING || status == Status.CRITICAL || status == Status.DOWN;
    }

    // Getters & Setters
    public String getUrl() { return url; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getHttpStatusCode() { return httpStatusCode; }
    public void setHttpStatusCode(int httpStatusCode) { this.httpStatusCode = httpStatusCode; }
    public long getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(long responseTimeMs) { this.responseTimeMs = responseTimeMs; }
    public int getSslDaysRemaining() { return sslDaysRemaining; }
    public void setSslDaysRemaining(int sslDaysRemaining) { this.sslDaysRemaining = sslDaysRemaining; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
