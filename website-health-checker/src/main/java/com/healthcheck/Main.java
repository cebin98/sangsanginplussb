package com.healthcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 웹사이트 헬스체크 애플리케이션 진입점
 *
 * 실행 방법:
 *   1. config.properties에 텔레그램 토큰/채팅ID, 파트너 목록 설정
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

        List<Partner> partners = config.getPartners();
        List<String> ownSites = config.getOwnSites();
        int intervalMinutes = config.getCheckIntervalMinutes();

        log.info("파트너 수: {}개", partners.size());
        log.info("자사 사이트 수: {}개", ownSites.size());
        log.info("체크 주기: {}분", intervalMinutes);

        notifier.sendMessage("🚀 *웹사이트 헬스체크 시작*\n파트너: " + partners.size() + "개\n주기: " + intervalMinutes + "분");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // 1. 파트너 인바운드/아웃바운드 체크 (1분 주기) - 이상 시 즉시 알림
        Runnable checkTask = () -> {
            log.info("--- 파트너 헬스체크 시작 ---");
            for (Partner partner : partners) {
                try {
                    PartnerCheckResult result = new PartnerCheckResult(partner);
                    if (partner.hasInbound()) {
                        result.setInboundResult(checker.checkHttp(partner.getInboundUrl()));
                    }
                    if (partner.hasOutbound()) {
                        result.setOutboundResult(checker.checkHttp(partner.getOutboundUrl()));
                    }
                    notifier.notifyIfNeeded(result);
                } catch (Exception e) {
                    log.error("체크 중 예외 발생 [{}]: {}", partner.getName(), e.getMessage());
                }
            }
            log.info("--- 파트너 헬스체크 완료 ---");
        };

        scheduler.scheduleAtFixedRate(checkTask, 0, intervalMinutes, TimeUnit.MINUTES);

        // 2. 매일 09:00 전체 현황 일일 요약 전송
        Runnable dailySummaryTask = () -> {
            log.info("--- 일일 요약 전송 시작 ---");

            // 파트너 전체 체크
            List<PartnerCheckResult> partnerResults = new ArrayList<PartnerCheckResult>();
            for (Partner partner : partners) {
                try {
                    PartnerCheckResult result = new PartnerCheckResult(partner);
                    if (partner.hasInbound()) {
                        result.setInboundResult(checker.checkHttp(partner.getInboundUrl()));
                    }
                    if (partner.hasOutbound()) {
                        result.setOutboundResult(checker.checkHttp(partner.getOutboundUrl()));
                    }
                    partnerResults.add(result);
                } catch (Exception e) {
                    log.error("일일 요약 파트너 체크 오류 [{}]: {}", partner.getName(), e.getMessage());
                }
            }

            // 자사 사이트 체크 (HTTP + SSL)
            List<CheckResult> ownSiteResults = new ArrayList<CheckResult>();
            for (String url : ownSites) {
                try {
                    ownSiteResults.add(checker.checkFull(url));
                } catch (Exception e) {
                    log.error("일일 요약 자사 사이트 체크 오류 [{}]: {}", url, e.getMessage());
                }
            }

            notifier.sendDailySummary(partnerResults, ownSiteResults);
            log.info("--- 일일 요약 전송 완료 ---");
        };

        // 다음 09:00까지 남은 시간 계산
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextNine = now.toLocalDate().atTime(9, 0);
        if (!now.isBefore(nextNine)) {
            nextNine = nextNine.plusDays(1);
        }
        long initialDelay = ChronoUnit.MINUTES.between(now, nextNine);

        scheduler.scheduleAtFixedRate(dailySummaryTask, initialDelay, 24 * 60, TimeUnit.MINUTES);
        log.info("일일 요약 예약: {}분 후 첫 전송 (매일 09:00)", initialDelay);

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
