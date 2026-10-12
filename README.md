# QuantuMobileLauncher

<p align="center"><img src="docs/screenshots/icon.png" width="360" alt="앱 아이콘" /></p>

안드로이드용 **마인크래프트 자바 에디션** 런처입니다. PojavLauncher / MojoLauncher 와 같은 방식으로,
안드로이드용으로 빌드된 OpenJDK 를 앱 프로세스 안에서 직접 실행해 게임을 구동합니다.

<p align="center">
  <img src="docs/screenshots/home.png" width="300" alt="홈 화면" />
  <img src="docs/screenshots/instances.png" width="300" alt="인스턴스 화면" />
  <img src="docs/screenshots/mods.png" width="300" alt="모드 화면" />
</p>

## 기능

| 기능 | 상태 |
| --- | --- |
| 마이크로소프트 로그인 (Xbox Live → XSTS → Minecraft 서비스, 토큰 자동 갱신) | ✅ |
| 오프라인 계정 (바닐라 서버와 같은 UUID 생성) | ✅ |
| 모장 버전 목록 / 릴리스·스냅샷 | ✅ |
| 클라이언트 jar, 라이브러리, 에셋 병렬 다운로드 + SHA-1 검증 + 재시도 | ✅ |
| 1.6 이전 버전의 legacy/virtual 에셋 | ✅ |
| `inheritsFrom` 프로필 병합 (Fabric, 직접 추가한 Forge/Quilt 프로필 등) | ✅ |
| Fabric 원클릭 설치 (공식 meta API) | ✅ |
| 인스턴스 (버전별 분리된 게임 폴더, 메모리/JVM 인자 개별 설정) | ✅ |
| 실행 인자 생성 (신규 `arguments` 포맷 + 구버전 `minecraftArguments`, rule 평가) | ✅ |
| 필요한 Java 버전 자동 선택 (8 / 17 / 21) | ✅ |
| Java 런타임 가져오기 (`.tar.xz` / `.tar.gz`) | ✅ |
| 앱 안에서 Java 런타임 다운로드 (카탈로그 선택 또는 링크 입력, SHA-256 검증, 진행률) | ✅ |
| 필요한 Java 가 없을 때 오류 창에서 바로 "Java N 받기" | ✅ |
| JNI 브릿지: `JLI_Launch` 로 JVM 실행, stdout/stderr → 로그 파일 | ✅ |
| 터치 컨트롤 (이동 패드, 점프/웅크리기/달리기, 공격/사용, 단축키, 핫바 스크롤) | ✅ |
| 화면 드래그로 시점 조작, 탭 = 사용, 길게 누르기 = 부수기, 메뉴에서는 마우스 포인터 | ✅ |
| 물리 키보드 / 마우스 (포인터 캡처), 소프트 키보드로 채팅 입력 | ✅ |
| 해상도 배율, 렌더러 선택 (GL4ES / Zink / 사용자 지정) | ✅ |
| 한국어 / 영어 UI, 다크 테마 | ✅ |
| 홈에서 계정·인스턴스 바로 전환, 스킨 아바타 표시 | ✅ |
| 처음 실행 체크리스트 (빠진 설정으로 바로 이동) | ✅ |
| 검색·필터 가능한 버전 선택 (정식/스냅샷/알파·베타/설치됨) | ✅ |
| **Modrinth 연동**: 모드·리소스팩·셰이더 검색/설치/삭제/업데이트, 선택한 인스턴스의 버전·로더에 맞는 것만 표시, 필수 의존성(Fabric API 등) 자동 설치, SHA-1 검증 | ✅ |
| 인스턴스 메뉴 (바로 플레이, 이름 변경, 메모리, JVM 인자), 최근 플레이 순 정렬 | ✅ |
| 로그 색상 강조 (오류/경고), 로그 복사·공유 | ✅ |

## 게임을 실제로 실행하려면 필요한 것

모장이 배포하는 LWJGL 과 자바 런타임은 데스크톱(x86/glibc)용이라 안드로이드에서 그대로 동작하지 않습니다.
Pojav 계열 런처들도 아래 세 가지를 따로 빌드해서 함께 배포합니다. 이 저장소에는 포함되어 있지 않습니다.

1. **안드로이드용 Java 런타임**
   예: [PojavLauncherTeam/android-openjdk-build-multiarch](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch) 의
   `jre8-*.tar.xz`, `jre17-*.tar.xz`, `jre21-*.tar.xz`.
   앱의 **설정 → Java 런타임 다운로드** (카탈로그 또는 링크 입력) 또는 **Java 런타임 가져오기** (파일 선택) 로 설치합니다. 1.16.5 이하는 Java 8, 1.17~1.20.4 는 17, 1.20.5 이상은 21 이 필요합니다.
2. **안드로이드용 LWJGL 3 jar** (예: PojavLauncher 의 lwjgl3 포크 빌드)
   **설정 → LWJGL jar 가져오기** 로 넣으면 모장의 LWJGL 대신 클래스패스에 들어갑니다.
3. **네이티브 라이브러리**: `liblwjgl.so`, GLFW 구현체, 렌더러 (`libgl4es_114.so` 또는 `libOSMesa.so` 등)
   `app/src/main/jniLibs/<abi>/` 에 넣고 빌드하면 APK 에 포함됩니다.
   GLFW 구현체는 [`qml_bridge.h`](app/src/main/cpp/qml_bridge.h) 의 함수로 안드로이드 창(`ANativeWindow`)과
   입력 이벤트를 받아오고, 커서 잡기 상태를 알려줘야 합니다.

### 모드 설치 (Modrinth)

하단 **모드** 탭에서 현재 선택한 인스턴스에 맞는 모드를 [Modrinth](https://modrinth.com) 에서 찾아 설치합니다.
Fabric / Quilt(Fabric 모드 겸용) / Forge / NeoForge 인스턴스를 인식하고, 바닐라 인스턴스에서는 리소스팩과 셰이더만 받을 수 있습니다.
파일은 인스턴스의 `mods`, `resourcepacks`, `shaderpacks` 폴더에 저장되고, 설치 기록은 `qml-mods.json` 에 남습니다.
다운로드는 Modrinth CDN(`cdn.modrinth.com`)에서만 허용하며 SHA-1 이 맞지 않으면 설치하지 않습니다.
셰이더는 Iris 같은 셰이더 모드가 따로 필요하고, 모바일 렌더러에서는 동작하지 않을 수 있습니다.

### 런타임 카탈로그

**Java 런타임 다운로드** 는 [`runtimes.json`](runtimes.json) 을 읽어 기기 CPU(ABI)에 맞는 항목만 보여줍니다.
주소는 설정의 "런타임 목록 주소"에서 바꿀 수 있어서, 직접 호스팅한 목록도 쓸 수 있습니다.
기본 목록은 비어 있으므로, 믿을 수 있는 빌드의 주소를 확인해서 아래 형식으로 채워 주세요 (https 만 허용).

```json
{"runtimes": [
  {"name": "My JRE", "java": 25, "abi": "arm64-v8a", "url": "https://…/jre25-aarch64.tar.xz", "sha256": "…", "sizeMb": 60}
]}
```

목록에 없는 런타임은 **링크 입력** 으로 직접 받을 수 있습니다. 받은 파일은 `sha256` 이 있으면 검증한 뒤 설치합니다.

위 파일들은 대부분 LGPL/GPL 계열 라이선스이므로, 함께 배포할 때는 각 라이선스를 지켜야 합니다.

## 구조

```
app/src/main/java/com/quantumobile/launcher/
├── core/
│   ├── auth/       계정 모델·저장소, 마이크로소프트 인증
│   ├── mc/         버전 매니페스트, 버전 JSON 병합, rule, 라이브러리, 에셋, 설치, 실행 인자, Fabric
│   ├── net/        OkHttp 래퍼, 병렬 다운로더(SHA-1 검증)
│   ├── runtime/    Java 런타임 설치/선택
│   ├── instance/   인스턴스
│   └── LaunchPreparer.kt   로그인 갱신 → 설치 → 런타임 선택 → 명령줄 생성
├── game/           GameActivity(:game 프로세스), 터치 컨트롤, 키 매핑, JNI 바인딩
└── ui/             홈/계정/인스턴스/설정 화면, MS 로그인 WebView, 로그 뷰어
app/src/main/cpp/
├── jvm_launcher.c  JRE 라이브러리 미리 로드 + JLI_Launch 호출
├── input_bridge.c  Surface/입력 이벤트 큐 (GLFW 구현체가 폴링)
└── qml_bridge.h    네이티브 ABI
```

게임은 별도 프로세스(`:game`)에서 실행됩니다. JVM 이 시작되면 프로세스를 점유하고, 게임 종료 시 `System.exit` 로 프로세스가 끝나기 때문입니다.

게임 파일 위치: `Android/data/com.quantumobile.launcher/files/.minecraft` (파일 관리자로 모드/리소스팩 추가 가능)

## 빌드

Android SDK (compileSdk 35), NDK 27.2, CMake 3.22.1, JDK 17 이상이 필요합니다.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 단위 테스트
QML_NETWORK_TESTS=1 ./gradlew testDebugUnitTest   # 실제 모장/Fabric 서버를 사용하는 테스트 포함
```

GitHub Actions 가 푸시마다 테스트, lint, APK 빌드를 수행하고 APK 를 아티팩트로 올립니다.

## 참고

- 마이크로소프트 로그인은 대부분의 서드파티 런처가 쓰는 공개 Live 클라이언트 ID(`00000000402b5328`)를 사용합니다.
- 계정 토큰은 앱 내부 저장소(`files/accounts.json`)에 저장되며 다른 앱은 읽을 수 없습니다.
- 마인크래프트는 Mojang/Microsoft 의 상표이며, 이 프로젝트는 공식 제품이 아닙니다. 정품 계정으로 플레이하세요.
