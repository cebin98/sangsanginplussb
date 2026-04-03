package com.healthcheck;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 웹사이트 HTTP 상태, 응답 시간, SSL 인증서를 체크하는 클래스
 */
public class WebsiteChecker {

    private static final Logger log = LoggerFactory.getLogger(WebsiteChecker.class);

    private final OkHttpClient httpClient;
    private final long responseTimeWarningMs;
    private final long responseTimeCriticalMs;
    private final int sslWarningDays;
    private final int sslCriticalDays;

    public WebsiteChecker(AppConfig config) {
        this.responseTimeWarningMs = config.getResponseTimeWarningMs();
        this.responseTimeCriticalMs = config.getResponseTimeCriticalMs();
        this.sslWarningDays = config.getSslExpiryWarningDays();
        this.sslCriticalDays = config.getSslExpiryCriticalDays();

        final Map<String, String> dnsOverride = config.getDnsOverrideMap();

        // IP 기반 HTTPS 접속 시 SSL 인증서 검증 생략용 TrustManager
        X509TrustManager trustAllManager = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };

        SSLContext trustAllSslContext;
        try {
            trustAllSslContext = SSLContext.getInstance("TLS");
            trustAllSslContext.init(null, new TrustManager[]{trustAllManager}, null);
        } catch (Exception e) {
            throw new RuntimeException("SSL 컨텍스트 초기화 실패", e);
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getHttpConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getHttpReadTimeoutSeconds(), TimeUnit.SECONDS)
                .followRedirects(true)
                .sslSocketFactory(trustAllSslContext.getSocketFactory(), trustAllManager)
                .hostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        // 파트너 연결 가능 여부 체크용 - 호스트명 검증 생략
                        // SSL 인증서 유효성은 checkFull()의 HttpsURLConnection에서 별도 검증
                        return true;
                    }
                })
                .dns(new Dns() {
                    @Override
                    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                        if (dnsOverride.containsKey(hostname)) {
                            String ip = dnsOverride.get(hostname);
                            log.debug("DNS 오버라이드: {} -> {}", hostname, ip);
                            return Collections.singletonList(InetAddress.getByName(ip));
                        }
                        return Dns.SYSTEM.lookup(hostname);
                    }
                })
                .build();
    }

    /**
     * HTTP 상태 + 응답시간만 체크 (5분 주기용, SSL 제외)
     */
    public CheckResult checkHttp(String url) {
        CheckResult result = new CheckResult(url);
        log.info("HTTP 체크: {}", url);

        checkHttp(url, result);
        evaluateStatus(result);

        log.info("HTTP 체크 완료: {} -> {} ({}ms, HTTP {})",
                url, result.getStatus(), result.getResponseTimeMs(), result.getHttpStatusCode());

        return result;
    }

    /**
     * HTTP + SSL 종합 체크 (일일 09:00 리포트용)
     */
    public CheckResult checkFull(String url) {
        CheckResult result = new CheckResult(url);
        log.info("전체 체크: {}", url);

        checkHttp(url, result);

        if (url.toLowerCase().startsWith("https://")) {
            checkSsl(url, result);
        }

        evaluateStatus(result);

        log.info("전체 체크 완료: {} -> {} ({}ms, HTTP {}, SSL {}일)",
                url, result.getStatus(), result.getResponseTimeMs(),
                result.getHttpStatusCode(), result.getSslDaysRemaining());

        return result;
    }

    /**
     * HTTP 요청으로 상태 코드와 응답 시간 측정
     */
    private void checkHttp(String url, CheckResult result) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        long startTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long responseTime = System.currentTimeMillis() - startTime;
            result.setHttpStatusCode(response.code());
            result.setResponseTimeMs(responseTime);

            // 2xx, 3xx, 403/404(API Gateway 정상응답)는 연결 성공으로 처리
            boolean isNormal = response.isSuccessful()
                    || response.code() == 301
                    || response.code() == 302
                    || response.code() == 403
                    || response.code() == 404;
            if (!isNormal) {
                result.setStatus(CheckResult.Status.DOWN);
                result.setErrorMessage("HTTP 오류: " + response.code() + " " + response.message());
            }
        } catch (IOException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            result.setResponseTimeMs(responseTime);
            result.setStatus(CheckResult.Status.DOWN);
            result.setErrorMessage("연결 실패: " + e.getMessage());
            log.warn("HTTP 체크 실패 [{}]: {}", url, e.getMessage());
        }
    }

    /**
     * SSL 인증서 만료일 확인
     */
    private void checkSsl(String url, CheckResult result) {
        try {
            URL targetUrl = new URL(url);
            HttpsURLConnection conn = (HttpsURLConnection) targetUrl.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.connect();

            Certificate[] certs = conn.getServerCertificates();
            conn.disconnect();

            if (certs.length == 0) {
                result.setSslDaysRemaining(-2);
                return;
            }

            X509Certificate cert = (X509Certificate) certs[0];
            Date expiryDate = cert.getNotAfter();
            LocalDate expiry = expiryDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), expiry);

            result.setSslDaysRemaining((int) daysRemaining);
            log.debug("SSL 인증서 만료: {} -> {}일 남음", url, daysRemaining);

        } catch (SSLHandshakeException e) {
            result.setSslDaysRemaining(-2);
            if (result.getStatus() == CheckResult.Status.OK) {
                result.setStatus(CheckResult.Status.CRITICAL);
            }
            result.setErrorMessage("SSL 인증서 오류: " + e.getMessage());
            log.warn("SSL 체크 실패 [{}]: {}", url, e.getMessage());
        } catch (Exception e) {
            result.setSslDaysRemaining(-2);
            log.warn("SSL 체크 오류 [{}]: {}", url, e.getMessage());
        }
    }

    /**
     * 체크 결과를 종합하여 최종 상태 결정
     */
    private void evaluateStatus(CheckResult result) {
        // 이미 DOWN으로 설정된 경우 유지
        if (result.getStatus() == CheckResult.Status.DOWN) {
            return;
        }

        CheckResult.Status worstStatus = CheckResult.Status.OK;

        // 응답 시간 평가
        if (result.getResponseTimeMs() >= responseTimeCriticalMs) {
            worstStatus = CheckResult.Status.CRITICAL;
        } else if (result.getResponseTimeMs() >= responseTimeWarningMs) {
            worstStatus = worse(worstStatus, CheckResult.Status.WARNING);
        }

        // SSL 만료 평가
        int sslDays = result.getSslDaysRemaining();
        if (sslDays >= 0) {
            if (sslDays <= sslCriticalDays) {
                worstStatus = worse(worstStatus, CheckResult.Status.CRITICAL);
            } else if (sslDays <= sslWarningDays) {
                worstStatus = worse(worstStatus, CheckResult.Status.WARNING);
            }
        }

        result.setStatus(worstStatus);
    }

    private CheckResult.Status worse(CheckResult.Status a, CheckResult.Status b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
