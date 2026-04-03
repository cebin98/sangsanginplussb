package com.healthcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 웹사이트 헬스체크 애플리케이션 진입점
 *
 * 실행 방법:
 *   1. config.properties에 텔레그램 토큰/채팅ID, 웹사이트 목록 설정
 *   2. mvn package 빌드
 *   3. java -jar target/website-health-checker-1.0.0.jar
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("=== 웹사이트 헬스체크 시작 ===");

        AppConfig config;
        try {
            config = new AppConfig();
        } catch (Exception e) {
            log.error("설정 로드 실패: {}", e.getMessage());
            System.exit(1);
            return;
        }

        WebsiteChecker checker = new WebsiteChecker(config);
        TelegramNotifier notifier = new TelegramNotifier(config);

        List<String> websites = config.getWebsites();
        final Map<String, String> siteNames = config.getSiteNameMap();
        int intervalMinutes = config.getCheckIntervalMinutes();

        log.info("체크 대상: {} 개 사이트", websites.size());
        log.info("체크 주기: {} 분", intervalMinutes);
        websites.forEach(url -> log.info("  - {}", url));

        // 시작 알림
        notifier.sendMessage("🚀 *웹사이트 헬스체크 시작*\n체크 대상: " + websites.size() + "개 사이트\n주기: " + intervalMinutes + "분");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // 1. HTTP 상태/응답시간 체크 (1분 주기, SSL 제외)
        Runnable checkTask = () -> {
            log.info("--- HTTP 헬스체크 시작 ---");
            for (String url : websites) {
                try {
                    CheckResult result = checker.checkHttp(url);
                    result.setDisplayName(resolveDisplayName(url, siteNames));
                    notifier.notifyIfNeeded(result);
                } catch (Exception e) {
                    log.error("체크 중 예외 발생 [{}]: {}", url, e.getMessage());
                }
            }
            log.info("--- HTTP 헬스체크 완료 ---");
        };

        scheduler.scheduleAtFixedRate(checkTask, 0, intervalMinutes, TimeUnit.MINUTES);

        // 2. 매일 09:00 SSL 현황 리포트 (sangsanginplussb.com 도메인만)
        Runnable sslReportTask = () -> {
            log.info("--- SSL 일일 리포트 전송 ---");
            List<CheckResult> results = new ArrayList<CheckResult>();
            for (String url : websites) {
                if (!url.contains("sangsanginplussb.com")) continue;
                try {
                    CheckResult result = checker.checkFull(url);
                    result.setDisplayName(resolveDisplayName(url, siteNames));
                    results.add(result);
                } catch (Exception e) {
                    log.error("SSL 리포트 체크 오류 [{}]: {}", url, e.getMessage());
                }
            }
            notifier.sendSslReport(results);
        };

        // 다음 09:00까지 남은 시간 계산
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextNine = now.toLocalDate().atTime(9, 0);
        if (!now.isBefore(nextNine)) {
            nextNine = nextNine.plusDays(1);
        }
        long initialDelay = ChronoUnit.MINUTES.between(now, nextNine);

        scheduler.scheduleAtFixedRate(sslReportTask, initialDelay, 24 * 60, TimeUnit.MINUTES);
        log.info("SSL 일일 리포트 예약: {} 분 후 첫 전송 (매일 09:00)", initialDelay);

        // 종료 훅
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("=== 헬스체크 종료 ===");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));

        log.info("헬스체커 실행 중... (종료: Ctrl+C)");
    }

    /**
     * URL에서 호스트를 추출하여 한글명 조회
     */
    private static String resolveDisplayName(String url, Map<String, String> siteNames) {
        try {
            String host = new URL(url).getHost();
            if (siteNames.containsKey(host)) {
                return siteNames.get(host);
            }
        } catch (Exception e) {
            // 파싱 실패 시 URL 그대로 사용
        }
        return url;
    }
}
