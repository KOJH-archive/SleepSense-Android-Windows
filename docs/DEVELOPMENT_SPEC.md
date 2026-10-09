# SleepSense 개발 명세서

버전: 1.2  
대상: Android 스마트폰, Windows 11 x64 노트북  
목표: 야간 미디어 재생으로 인한 불필요한 배터리·CPU 사용 감소

## 1. 제품 정의

SleepSense는 미디어 재생 중 사용자의 활동이 줄어들었을 때 대상 앱을 안전하게 일시정지하는 로컬 애플리케이션이다. 기본 제품은 수면을 진단하지 않으며, 입력·움직임·사용자 설정 시간을 수면의 대리 신호로 표시하지 않는다. “수면 감지”라는 표현은 향후 생체 신호를 검증한 별도 기능에만 사용한다.

제품의 우선순위는 다음과 같다.

1. 잘못된 앱을 멈추지 않는다.
2. 실패하면 추가 자동 명령을 보내지 않고 종료한다.
3. 사용자가 15초 예고와 60초 복구 창에서 개입할 수 있다.
4. 영상·음성·건강 데이터는 외부로 보내지 않는다.
5. 센서가 중단된 것을 사용자의 무활동으로 간주하지 않는다.

## 2. 범위

### 포함

- 사용자가 선택한 재생 세션 확인
- Android 가속도 기반 휴대폰 움직임 감지
- Windows 로그인 세션의 마지막 키보드·마우스 입력 시간 확인
- 무활동 시간 및 최대 재생 시간 정책
- 예고, 일시정지, 결과 확인, 제한된 복구
- 대상 세션·재생 항목 변경 시 보호 종료
- 로컬 설정과 최소 종료 결과 저장

### 제외

- 의학적 수면·수면 단계 판정
- 기본 카메라·마이크·스마트워치 연동
- 서버 분석, 계정, 광고, 원격 측정
- 무조건적인 전역 미디어 토글, 키보드 스페이스 입력, 앱 강제 종료
- 현재 버전의 자동 화면 끄기·시스템 절전

## 3. 공통 논리 구조

```text
사용자 설정
    ↓
재생 세션 선택 ── 대상 식별자 + 재생 항목 세대 + 제어 가능 여부
    ↓
관찰(센서/입력/시간)
    ↓
WATCH → WARNING(15초) → PAUSING
                         ↓ 확인 성공
                      PAUSED_GRACE(60초)
                         ├─ 복구 성공 → COOLDOWN 또는 종료
                         └─ 시간 만료 → FINISHED
```

모든 시간은 단조 증가 시계로 계산한다. 장시간 suspend, 시계 역행, 센서 공백, 이벤트 처리 실패는 관찰 연속성을 무효화한다. 보호 회차는 최대 8시간이며, 앱 재시작 뒤 이전 일시정지를 자동으로 복구하지 않는다.

### 상태 규칙

- `OFF`: 자동 명령을 보내지 않는다.
- `WATCH`: 선택 세션이 계속 재생 중이고 제어 가능해야 한다.
- `WARNING`: 입력 또는 사용자의 취소가 있으면 경고를 취소한다.
- `PAUSING`: 최신 세션과 재생 항목을 다시 확인한 뒤 한 번만 요청한다.
- `PAUSED_GRACE`: 같은 세션·같은 항목·재생 가능 상태일 때만 복구한다.
- `COOLDOWN`: 취소·복구 뒤 15분 동안 자동 개입하지 않는다.
- `FAULT`: 제어 실패·내부 오류 후 추가 명령을 금지한다.

## 4. Android 설계

### 기술 스택

- Kotlin, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21
- compileSdk/targetSdk 36, minSdk 31
- Jetpack Compose 기반 야간 UI
- `SensorManager` 가속도계
- 사용자가 허용한 `NotificationListenerService`에서 `MediaController` 확인
- 감시 회차는 Android 14+ `specialUse` foreground service

### 움직임 계산

1. 가속도계 값을 약 10Hz로 읽는다.
2. 중력 저역 필터를 추정하고 각 축에서 중력을 뺀다.
3. 세 축 변화량의 제곱합 제곱근을 계산한다.
4. 시작 후 30초 동안 중앙값과 MAD를 이용해 개인별 잡음 기준을 계산한다.
5. 기준치는 `median + 6 × MAD`이며 0.15~1.5m/s²로 제한한다.
6. 변화량이 기준을 2초 연속 넘으면 활동 시각을 갱신한다. 기준 아래로 내려가면 연속 시간을 초기화한다.
7. 비정상 값, NaN, 불가능한 가속도, 5초 초과 센서 공백은 무활동 증거로 쓰지 않는다.

이 신호는 휴대폰 자체가 움직였는지를 뜻한다. 사용자가 깨어 있어도 휴대폰을 고정해 두면 무활동 조건에 도달할 수 있다.

### Android 미디어 제어

- 컨트롤러 UUID와 미디어 세션 토큰을 내부 식별자로 보관한다.
- `PlaybackState.PLAYING`, `ACTION_PAUSE`, 대상 세션 일치 여부를 요청 직전에 재검사한다.
- 일시정지 요청 뒤 최대 5초 동안 `PAUSED`를 확인한다.
- 실패하면 같은 세션에 최대 1회 재시도하고, 이후 `FAULT`로 종료한다.
- 제목·URL·알림 본문을 읽거나 저장하지 않는다.
- 복구는 같은 컨트롤러·같은 재생 항목 세대·`ACTION_PLAY`일 때만 허용한다.

### Android 전원·배터리

지속 wake lock, 화면 켜짐 플래그, 정확한 알람, 카메라·마이크 권한을 사용하지 않는다. 제조사 전원 관리로 센서 전달이 중단되면 관찰이 보류될 수 있다. 배터리 최적화 예외는 자동 요청하지 않는다.

## 5. Windows 설계

### 기술 스택

- Python 3.12 x64
- PySide6 6.9.3: 다크 모드 트레이 UI
- PyWinRT 3.2.1: `Windows.Media.Control`
- PyInstaller 6.16.0: 폴더형 portable 배포
- `ctypes`: `GetLastInputInfo` 입력 유휴 시간

### 미디어 세션

`GlobalSystemMediaTransportControlsSessionManager`에서 세션을 열거한다. `SourceAppUserModelId`는 화면 표시용 앱 식별자이며, 제어 시에는 내부 세션 객체·세대·재생 상태를 함께 사용한다.

- 재생 중이며 `is_pause_enabled`인 세션만 선택 목록에 표시한다.
- `try_pause_async()` 뒤 다시 조회해 `PAUSED`를 확인한다.
- 복구는 `try_play_async()`를 같은 세션과 재생 항목 세대에만 호출한다.
- 브라우저의 여러 탭처럼 세션이 모호하면 보호를 거부한다.
- 음소거, 전역 `VK_MEDIA_PLAY_PAUSE`, 스페이스 입력, 프로세스 종료를 사용하지 않는다.

### Windows 입력 유휴

`GetLastInputInfo`는 현재 로그인 세션의 마지막 입력 시각만 제공한다. 키 내용·좌표는 읽지 않는다. DWORD 랩어라운드, 역행, API 실패는 `UNKNOWN`으로 처리하고 무활동으로 계산하지 않는다. 영상 시청 중 입력이 없는 정상 상태도 수면 증거가 아니다.

### UI·트레이

창 닫기는 트레이 숨김으로 처리하고, 보호 종료는 메뉴에서 명시적으로 선택한다. 로그인 자동 시작은 초기 버전에서 사용하지 않는다. 내부 worker 오류는 `FAULT`로 표시하고 미디어 명령을 중지한다.

## 6. 프라이버시와 보안

- 영상·음성·가속도 원본·건강 데이터를 파일로 저장하지 않는다.
- 네트워크 통신과 외부 분석 서버를 사용하지 않는다.
- 콘텐츠 제목, URL, 알림 내용, 입력 내용은 로그에 기록하지 않는다.
- Android 백업·기기 전송에서 설정과 종료 기록을 제외한다.
- 로컬 기록은 최대 7일의 최소 결과만 보관하고 삭제할 수 있게 한다.
- Windows 배포에는 Qt·PyWinRT·PyInstaller·Python 라이선스 고지를 포함한다.

## 7. 검증 계획

### 자동 테스트

정책 테스트는 잘못된 세션, 재생 항목 변경, 제어 권한 상실, 센서 공백, 시간 역행, 명령 확인 실패, 복구 만료, 취소 반복을 모두 모의한다. Windows 미디어 어댑터는 선택 세션만 호출하는지 확인한다.

### 실기기 테스트

Android에서는 Galaxy S26 Ultra를 대상으로 YouTube·Netflix·Spotify, 화면 잠금, Bluetooth 이어폰, 배터리 절전, 8시간 회차를 확인한다. Windows에서는 LG그램에서 브라우저·음악 앱별 세션 노출과 실제 일시정지·복구를 확인한다.

실기기 검증 전에는 호환 앱 목록이나 배터리 절감 수치를 보장하지 않는다. 카메라·마이크 기반 수면 감지는 저조도·개인차·오탐지 평가를 별도 수행한 뒤에만 추가한다.

## 8. 공식 참고

- [Android MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager)
- [Android foreground service 유형](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Windows GlobalSystemMediaTransportControlsSession](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssession)
- [Windows GetLastInputInfo](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getlastinputinfo)
- [PyWinRT](https://pywinrt.readthedocs.io/en/latest/)
- [MediaPipe Face Landmarker Python](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/python) (후속 검토용)
