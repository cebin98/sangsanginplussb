package com.healthcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
        int intervalMinutes = config.getCheckIntervalMinutes();

        log.info("체크 대상: {} 개 사이트", websites.size());
        log.info("체크 주기: {} 분", intervalMinutes);
        websites.forEach(url -> log.info("  - {}", url));

        // 시작 알림
        notifier.sendMessage("🚀 *웹사이트 헬스체크 시작*\n체크 대상: " + websites.size() + "개 사이트\n주기: " + intervalMinutes + "분");

        // 스케줄러 설정
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        Runnable checkTask = () -> {
            log.info("--- 헬스체크 라운드 시작 ---");
            List<CheckResult> results = new ArrayList<>();

            for (String url : websites) {
                try {
                    CheckResult result = checker.check(url);
                    results.add(result);
                    // 개별 알림이 필요한 경우 즉시 전송
                    notifier.notifyIfNeeded(result);
                } catch (Exception e) {
                    log.error("체크 중 예외 발생 [{}]: {}", url, e.getMessage());
                }
            }

            // 전체 결과 리포트 전송
            notifier.sendReport(results);
            log.info("--- 헬스체크 라운드 완료 ---");
        };

        // 즉시 첫 번째 실행 후 주기적으로 실행
        scheduler.scheduleAtFixedRate(
                checkTask,
                0,
                intervalMinutes,
                TimeUnit.MINUTES
        );

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
}
