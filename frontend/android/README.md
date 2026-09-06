# Jungle Bell — Android (Capacitor)

기존 React 웹 앱(PWA)을 Capacitor로 포팅한 Android 앱입니다.
모든 앱 기능(페어링, 알림, 세탁, 급식, 출석)은 `frontend`의 웹 코드를 그대로 사용합니다.

## 구조

- `app/src/main/java/com/junglebell/mobile/MainActivity.java` — Capacitor WebView 셸
- `app/src/main/java/com/junglebell/mobile/widget/` — **홈 화면 위젯** (네이티브)
  - `BellWidgetProvider.kt` — 위젯 렌더링 (로컬 캐시 기준, 오프라인 가능)
  - `WidgetSyncWorker.kt` — WorkManager 30분 주기 공개 API 동기화
  - `PublicApiClient.kt` — 공개 API 클라이언트 (인증 불필요)
  - `LaundrySummary.kt` / `MealParser.kt` — 프론트엔드 domain 로직과 동일한 의미
  - `WidgetDataStore.kt` — SharedPreferences JSON 캐시
- `app/src/main/res/layout/bell_widget.xml` — 위젯 레이아웃
- `app/src/main/res/xml/bell_widget_info.xml` — 위젯 메타데이터

## 위젯 데이터 원천 (공개 API만 사용)

| 항목 | endpoint | 비고 |
| --- | --- | --- |
| 세탁 | `GET /api/public/laundry` | 사용 가능 대수 + 최소 남은 시간 |
| 급식 | `GET /api/public/meals` | KST 기준 오늘 중식·석식 |

베이스 URL: `https://jungle-bell.sijun-yang.com/`

- 앱 세션/PC 서버 없이도 동작합니다 (인증 미사용).
- 30분마다 WorkManager가 갱신하고, 앱 실행/위젯 첫 설치 시 즉시 동기화합니다.
- 네트워크가 없으면 마지막 캐시를 표시합니다.

## 빌드

요건: JDK 17, Android SDK (compileSdk 36)

```bash
# frontend/ 에서
npm run android:sync        # web 빌드 + Capacitor sync
# 또는 Android Studio에서 frontend/android 열기 (npm run android:open)

# CLI 빌드
cd android
./gradlew :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

## 웹 자산 갱신

웹 코드가 바뀌면 반드시 다음을 실행해 `android/app/src/main/assets/public` 을 갱신해야 합니다:

```bash
npm run android:sync
```
