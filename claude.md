# sangsanginplussb - 웹사이트 헬스체커

## 프로젝트 개요

상상인플러스저축은행의 **혁신채널 파트너 연동 상태 및 자사 사이트를 모니터링**하는 Java 헬스체크 데몬.
- 파트너사 인바운드(수신 포트) / 아웃바운드(파트너 API) 연결 상태를 주기적으로 체크
- 이상 감지 시 텔레그램으로 즉시 알림
- 매일 09:00 전체 현황 요약 메시지 전송

---

## 디렉터리 구조

```
sangsanginplussb/
└── website-health-checker/
    ├── pom.xml
    ├── startup.sh          # 앱 시작 (nohup 백그라운드 실행)
    ├── shutdown.sh         # 앱 종료 (graceful → force)
    ├── target/
    │   └── website-health-checker-1.0.0.jar  # 빌드 산출물 (fat JAR)
    └── src/main/
        ├── java/com/healthcheck/
        │   ├── Main.java               # 진입점, 스케줄러 설정
        │   ├── AppConfig.java          # config.properties 로드
        │   ├── WebsiteChecker.java     # HTTP + SSL 체크 로직
        │   ├── TelegramNotifier.java   # 텔레그램 알림 전송
        │   ├── Partner.java            # 파트너 도메인 모델
        │   ├── CheckResult.java        # 체크 결과 (상태 + 응답시간 + SSL)
        │   └── PartnerCheckResult.java # 파트너별 인/아웃 결과 묶음
        └── resources/
            ├── config.properties       # 운영 설정 (토큰, 파트너 URL 등)
            └── logback.xml             # 로그 설정
```

---

## 기술 스택

| 항목 | 내용 |
|---|---|
| 언어 | Java 8 |
| 빌드 | Maven 3 (maven-shade-plugin → fat JAR) |
| HTTP 클라이언트 | OkHttp3 4.12 |
| JSON | Jackson 2.16 |
| 로깅 | SLF4J + Logback |
| 알림 | Telegram Bot API |

---

## 핵심 클래스 설명

### `Main.java`
스케줄러 두 개를 구동:
1. **파트너 체크** (`scheduleAtFixedRate`) — `check.interval.minutes`(기본 1분) 주기. 이상 있을 때만 텔레그램 알림.
2. **일일 요약** — 다음 09:00까지 남은 분을 계산한 뒤 24시간마다 실행. 파트너 전체 + 자사 사이트 체크 후 요약 전송.

### `WebsiteChecker.java`
- `checkHttp(url)` — HTTP 상태 코드 + 응답시간. 파트너 주기 체크에 사용.
- `checkFull(url)` — HTTP + SSL 인증서 만료일. 일일 요약에 사용.
- **인바운드 포트 체크 특이사항**: `unexpected end of stream` 오류는 TCP 연결 성공으로 판단(정상). 파트너 전용 프로토콜이라 HTTP 응답이 없어도 포트가 열려 있으면 정상.
- **DNS 오버라이드**: `config.properties`의 `dns.*` 항목으로 도메인→IP 강제 매핑 (자사 사이트 IP 직접 지정).
- **SSL 검증 생략**: OkHttpClient에 trust-all 설정. SSL 인증서 유효성은 `checkFull()`의 `HttpsURLConnection`에서만 별도 검증.

### `TelegramNotifier.java`
- `notifyIfNeeded(result)` — WARNING/CRITICAL/DOWN 상태일 때만 즉시 알림.
- `sendDailySummary(...)` — 코드블록 형식의 ASCII 표로 전체 현황 전송.
- 한글 포함 문자열 고정폭 패딩: `padKorean()` (한글 1자 = 너비 2 계산).

### `CheckResult.Status` 상태 기준

| 상태 | 조건 |
|---|---|
| `OK` | HTTP 2xx/3xx/403/404, 응답시간 < 3000ms |
| `WARNING` | 응답시간 3000~4999ms, SSL 만료 8~30일 이내 |
| `CRITICAL` | 응답시간 ≥ 5000ms, SSL 만료 7일 이내 |
| `DOWN` | 연결 실패, HTTP 5xx 등 비정상 응답 |

---

## 설정 파일 (`config.properties`)

JAR 내부에 번들됨. 수정 후 반드시 **재빌드 필요**.

### 주요 설정 키

```properties
# 텔레그램
telegram.bot.token=<봇 토큰>
telegram.chat.id=<채팅방 ID>

# 파트너 목록 (쉼표 구분 ID)
partners=kakaobank,finda,...

# 파트너별 URL
partner.<id>.name=<한글명>
partner.<id>.inbound=http://<서버IP>:<포트>
partner.<id>.outbound=https://<파트너도메인>

# 자사 사이트 (SSL 모니터링)
own.sites=https://www.sangsanginplussb.com,...

# DNS 오버라이드
dns.<도메인>=<IP>

# 임계값
check.interval.minutes=1
response.time.warning.ms=3000
response.time.critical.ms=5000
ssl.expiry.warning.days=30
ssl.expiry.critical.days=7
http.connect.timeout.seconds=10
http.read.timeout.seconds=15
```

### 현재 등록된 파트너 (14개)

| ID | 이름 | 인바운드 포트 | 아웃바운드 |
|---|---|---|---|
| kakaobank | 카카오뱅크 | :18071 | loan-partner.kakaobank.io |
| finda | 핀다 | :18072 | pri-openapi.finda.co.kr |
| finnq | 핀크 | :18073 | lng.finnq.com |
| toss | 토스 | :18074 | find-loan-partner-v.toss.im |
| banksalad | 뱅크샐러드 | :18075 | sangsanginplussb.banksalad.com |
| welcomesb | 웰컴저축은행 | :18076 | mydata.welcomebank.co.kr:7791 |
| kakaopay | 카카오페이 | :18077 | pay-gw-loan-hub.kakao.com |
| naverfinancial | 네이버파이낸셜 | :18078 | connect-apis.pay.naver.com |
| kbalda | KB알다 | :18081 | financevpn.alda.ai |
| nh | NH | :18083 | 10.16.15.45:9000 (내부망) |
| qupid | 큐피드 | 없음 | api.qupid.co.kr:8443 |
| nicednr | 나이스디앤알 | 없음 | niceab.nicednr.co.kr |
| infotech | 인포텍 | 없음 | apigw.infotech.co.kr |
| apthefin | apthefin | (비활성화) | (비활성화) |

### 자사 사이트 (SSL 모니터링)
- `www.sangsanginplussb.com` → 218.233.89.196
- `appreal.sangsanginplussb.com` → 218.233.89.196
- `m.sangsanginplussb.com` → 218.233.89.218

---

## 빌드 및 배포

```bash
# 빌드 (website-health-checker 디렉터리에서)
cd website-health-checker
mvn package

# 실행 (target/ 디렉터리에서)
cd target
../startup.sh

# 종료
../shutdown.sh

# 로그 확인
tail -f target/app.log
```

- `startup.sh`: `app.pid` 파일로 중복 실행 방지, `nohup` 백그라운드 실행
- `shutdown.sh`: SIGTERM → 10초 대기 → 그래도 살아있으면 SIGKILL

---

## 파트너 추가/변경 방법

1. `config.properties`에 파트너 ID를 `partners=` 목록에 추가
2. `partner.<id>.name`, `partner.<id>.inbound`, `partner.<id>.outbound` 항목 추가
   - 인바운드만 있거나 아웃바운드만 있는 경우 해당 항목 생략 가능
3. `mvn package` 재빌드
4. 앱 재시작 (`shutdown.sh` → `startup.sh`)

---

## 주의사항

- `config.properties`는 JAR 내부에 번들되므로 **수정 후 반드시 재빌드**
- 인바운드 포트 체크는 TCP 연결 가능 여부만 판단 (HTTP 응답 불필요)
- OkHttpClient SSL 검증이 비활성화되어 있음 — 파트너 연결 가능 여부 체크 목적. 자사 사이트 SSL 인증서 만료 체크는 `HttpsURLConnection`에서 별도 수행
- NH 아웃바운드(`10.16.15.45:9000`)는 내부망 IP — 서버에서만 접근 가능
- 최초 오류가 발생후 해당일에는 정상화되기전까지 텔레그렘메세지 전송은 반복하지 않으며 정상시 메세지를 정상화 메세지를 발송함

