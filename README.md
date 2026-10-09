# SleepSense

Android와 Windows에서 야간 동영상·음악 재생을 자동으로 일시정지하는 로컬 우선 프로젝트입니다.

SleepSense는 사용자가 미디어를 재생한 채 잠드는 상황에서 기기의 불필요한 재생과 배터리 사용을 줄이는 것을 목표로 합니다. 현재 버전은 의학적 수면 판정 시스템이 아니라, Android의 기기 움직임·사용자 설정 시간과 Windows의 키보드·마우스 무입력·최대 재생 시간을 이용한 야간 재생 보호 도구입니다.

## 현재 기능

- Android: Kotlin, Jetpack Compose, SensorManager, MediaSession 기반 미디어 제어
- Windows: Python, PySide6, PyWinRT, Windows Global System Media Transport Controls 기반 미디어 제어
- 일시정지 전 15초 예고
- 일시정지 성공 후 60초 이내 동일 세션 되돌리기
- 대상 미디어 세션 또는 재생 항목이 바뀌면 자동 제어 중단
- 카메라·마이크·외부 서버를 사용하지 않는 야간 모드
- 로컬 메모리 중심 처리 및 콘텐츠 제목·URL 비저장

## 시작하기

### Android

`android` 폴더를 Android Studio에서 열고 `android/README.md`의 권한 안내와 빌드 절차를 따릅니다. 개발용 APK는 GitHub Release에 첨부할 수 있으며, 소스 저장소에는 사용자별 서명 키와 `local.properties`를 포함하지 않습니다.

### Windows

Windows용 사용법과 테스트 방법은 [windows/README.md](windows/README.md)를 참조하세요. 배포용 ZIP에는 실행 파일과 필요한 런타임 DLL이 포함됩니다.

## 설계 문서

- [개발 명세서](docs/DEVELOPMENT_SPEC.md): 제품 범위, 기술 스택, 상태기계, 센서·미디어 제어·프라이버시 설계
- [Android 구현 안내](android/README.md)
- [Windows 구현 안내](windows/README.md)
- [Windows 검증 결과](windows/검증_결과.md)

## 중요한 제한

현재 앱은 사용자가 실제로 잠들었다고 확정하지 않습니다. 휴대폰이 고정되어 있거나 PC에서 영상을 조용히 보고 있어도 설정 조건에 도달할 수 있습니다. 모든 앱이 OS 미디어 세션을 제공하는 것은 아니므로 YouTube·Netflix·Spotify를 포함한 앱별 호환성은 기기와 버전에 따라 확인해야 합니다.

카메라 기반 눈 감김, 호흡 분석, 스마트워치 수면 단계, PC 화면 끄기·절전은 후속 연구 범위입니다. 이 기능을 추가할 때도 저조도 환경과 개인정보 보호를 우선 검증합니다.

## 라이선스

아직 라이선스를 확정하지 않은 개발 저장소입니다. 외부 배포 전 라이선스, 모델 파일, Qt 및 PyWinRT 의존성 고지를 확정합니다.
