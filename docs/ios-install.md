# 아이폰에 설치하기

아이폰은 앱스토어 밖의 앱을 바로 설치할 수 없고, 마인크래프트(자바)를 쓸 만한 속도로 돌리려면 **JIT**가 필요합니다.
그래서 아래 두 도구를 씁니다.

- **SideStore**: 내 Apple ID로 앱을 서명해서 설치해 주는 앱 (무료 Apple ID 가능)
- **StikDebug**: 게임을 켤 때 JIT를 켜 주는 앱 (App Store에 있음)

> 아이폰판은 실험 단계입니다. 실제 아이폰에서의 동작은 아직 검증 중이에요. 안 되면 로그를 보내 주세요(맨 아래 참고).
> 도구들의 화면과 순서는 자주 바뀝니다. 막히면 [SideStore 공식 안내](https://docs.sidestore.io)를 먼저 확인해 주세요.

## 준비물

- 아이폰 (iOS 14 이상, iPhone XS 이상 권장)
- PC 또는 Mac (처음 한 번만), USB 케이블
- Apple ID (무료 계정 가능)

## 1. SideStore 설치 (처음 한 번, PC 필요)

1. PC에서 [SideStore 공식 안내](https://docs.sidestore.io)의 설치 도구를 받아 실행합니다.
2. 아이폰을 케이블로 연결하고, 아이폰에서 **"이 컴퓨터를 신뢰"** 를 누릅니다.
3. 설치 도구에 Apple ID로 로그인해 SideStore를 아이폰에 설치합니다.
   이때 만들어지는 **페어링 파일**도 안내대로 아이폰에 넣어 둡니다 (StikDebug에서도 씁니다).
4. 아이폰 **설정 → 개인정보 보호 및 보안 → 개발자 모드** 를 켜고 재시동합니다.
5. **설정 → 일반 → VPN 및 기기 관리** 에서 내 Apple ID를 **신뢰** 합니다.

이후에는 PC 없이 아이폰만으로 됩니다.

## 2. QuantuMobile 설치

1. 아이폰 Safari로 [릴리즈 페이지](https://github.com/qmftm/QuantuMobileLauncher/releases/latest)에 들어가
   `QuantuMobile-iOS-v….ipa` 를 받습니다. (파일 앱의 "다운로드" 폴더에 저장됩니다)
2. **SideStore → My Apps → 왼쪽 위 +** 를 누르고 받은 `.ipa` 를 고릅니다.
3. 설치가 끝나면 홈 화면에 **QuantuMobile** 이 생깁니다.

TrollStore를 쓸 수 있는 구형 iOS라면 `-TrollStore.tipa` 를 TrollStore로 열면 되고, JIT도 자동으로 켜집니다 (3번 생략).

## 3. 게임 켤 때마다: JIT 켜기

1. App Store에서 **StikDebug** 를 설치하고, 처음 한 번 1번에서 만든 페어링 파일을 넣습니다.
2. 게임을 하기 전에 StikDebug를 열고 목록에서 **QuantuMobile** 을 눌러 JIT를 켭니다. QuantuMobile이 열립니다.
3. 로그인 → 버전 받기 → 플레이.

JIT 없이 켜면 경고가 뜨고, 게임이 아주 느리거나 시작되지 않습니다.

## 7일마다: 갱신

무료 Apple ID로 서명한 앱은 **7일** 뒤 열리지 않습니다. 그 전에 **SideStore → My Apps → Refresh** 를 눌러 주세요.
(유료 Apple 개발자 계정이면 1년)

## 안 될 때

- 앱 안의 로그 화면 내용이나, **파일 앱 → QuantuMobile → `latestlog.txt`** 를 보내 주세요.
- 26.x는 특히 실험 단계입니다. 1.21.x 이하 버전으로도 되는지 같이 알려 주시면 원인을 좁히는 데 도움이 됩니다.
