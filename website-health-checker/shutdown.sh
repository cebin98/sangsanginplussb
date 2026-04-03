#!/bin/bash

APP_NAME="website-health-checker"
PID_FILE="app.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "[$APP_NAME] PID 파일이 없습니다. 실행 중이 아닌 것 같습니다."
    exit 1
fi

PID=$(cat "$PID_FILE")

if ! kill -0 "$PID" 2>/dev/null; then
    echo "[$APP_NAME] 프로세스가 이미 종료되어 있습니다. (PID: $PID)"
    rm -f "$PID_FILE"
    exit 0
fi

echo "[$APP_NAME] 종료 중... (PID: $PID)"
kill "$PID"

# 최대 10초 대기
for i in $(seq 1 10); do
    if ! kill -0 "$PID" 2>/dev/null; then
        break
    fi
    sleep 1
done

# 아직 살아있으면 강제 종료
if kill -0 "$PID" 2>/dev/null; then
    echo "[$APP_NAME] 강제 종료합니다."
    kill -9 "$PID"
fi

rm -f "$PID_FILE"
echo "[$APP_NAME] 종료 완료"
