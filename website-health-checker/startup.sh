#!/bin/bash

APP_NAME="website-health-checker"
JAR="website-health-checker-1.0.0.jar"
PID_FILE="app.pid"
LOG_FILE="app.log"

# 이미 실행 중인지 확인
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "[$APP_NAME] 이미 실행 중입니다. (PID: $PID)"
        exit 1
    else
        echo "[$APP_NAME] 이전 PID 파일 제거 (프로세스 없음)"
        rm -f "$PID_FILE"
    fi
fi

# JAR 파일 존재 확인
if [ ! -f "$JAR" ]; then
    echo "[$APP_NAME] JAR 파일을 찾을 수 없습니다: $JAR"
    echo "먼저 mvn package 로 빌드하세요."
    exit 1
fi

# 실행
nohup java -jar "$JAR" >> "$LOG_FILE" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"

echo "[$APP_NAME] 시작 완료 (PID: $PID)"
echo "  로그: tail -f $LOG_FILE"
