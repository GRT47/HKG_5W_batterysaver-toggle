# HKG Gen5W 시동 OFF 후 절전 상시 유지 & 12V 보조배터리 자동충전 모니터 (`hkg.auxmon`)

현대·기아 표준형 5세대 와이드(Standard Gen5W, Telechips `tcc897x`, Android 4.4.2 KitKat API 19) 헤드유닛에서 **시동을 끈 뒤에도 헤드유닛 전원을 끄지 않고 절전 상태로 유지(`MicomPowerManager.requestPwrKeepStart(2)`)**하며, 주차 시간 동안 **12V 보조배터리 충전율(SOC %)·전압(추정/실측 V) 변화와 자동충전(Aux. Battery Saver+ / IG3) 작동 이력을 기록하고 USB 메모리로 내보내는 NEMESIS 패키지**입니다.

---

## 1. 핵심 동작 및 수정 반영 사항

1. **이벤트 기반 즉시 전원 유지 (`PwrKeep`) 및 60초 주기 재전송**
   - `MicomPowerStatusListener`의 `onTeleAccStatusChanged(ACCOFF=1)`, `onPowerStatusChanged(LOGICON=1 / ADM=2 / DELAYMODE=6 / PRESYSTEMOFF=7)`, `onPrepareSystemOff()`, `onSystemOff()` 콜백이 호출되는 즉시 지연 없이 `MicomPowerManager.requestPwrKeepStart(2)`를 전송하고, 안전 조건이 유지되는 동안 `confirmPrepareSystemOff()` / `confirmSystemOff()` 호출을 보류합니다.
   - 순정 텔레매틱스 앱(`HKMC_TAppService`, `HKMC_TAppVRM`, `HKMC_DMClient_OTA`)이 동일한 `mode=2`로 `requestPwrKeepStop(2)`을 보내더라도 전원이 꺼지지 않도록, 주차 유지 중 **60초마다 `requestPwrKeepStart(2)`를 재전송(Heartbeat)**합니다.
   - 목표 유지 시간으로 **`상시(무제한)` (`target_hours=0`, 기본값)** 및 **`1 / 3 / 6 / 12 / 24 / 48시간`** 프리셋(수동 1~72시간 조절 가능)을 지원합니다.

2. **절전 진입·복구 및 저부하 폴링**
   - `powerStatus == 0 (ACCON)`을 최우선으로 검사하여, 이전 원격/주차 이벤트로 `TeleAcc == 1 (ACCOFF)`이 캐시에 남아 있더라도 주행 중에는 절대 주차로 오판하거나 화면을 끄지 않습니다.
   - LCD 화면 끄기(`MicomPowerManager.setScreenOn(false)`)는 `powerStatus`가 **`6 (DELAYMODE)` 또는 `7 (PRESYSTEMOFF)`로 확인되었을 때만** 수행하며, `LOGICON(1)`·`ADM(2)`에서는 전원만 유지하고 화면은 끄지 않습니다. 또한 `6/7`에서 화면이 꺼진 뒤 사용자가 전원 버튼을 눌러 `1/2 (LOGICON/ADM)` 또는 `0 (ACCON)`으로 전환되면 즉시 `setScreenOn(true)`로 화면을 복구합니다.
   - 부팅 시에는 패널을 자동으로 띄우지 않습니다. 패키지 아이콘(`bin/launch.sh`)을 실행하면 전체화면 패널을, 주차 감시 후 재시동하면 주행을 방해하지 않도록 작은 상태 표시줄만 표시합니다.
   - 주차 중 샘플링 및 하드웨어 폴링 주기를 패널에서 **`10s / 30s / 60s` (기본 `30s`)**로 선택할 수 있으며, 불필요한 `getIGN3Status()` 매초 호출을 제거하여 CAN/Binder 부하를 최소화했습니다.

3. **엄격한 12V 보조배터리 방전 보호 (Safety Cutoff) 및 업데이트 인계**
   - `BCMManager.getBatSoc_M()` 및 `TM_HSCANAdapter.getBatSoc()`에서 유효한 SOC(`1~100%`)를 읽지 못할 경우(`0`, `255`, 또는 `-1`), 임의의 값(`85%`)으로 대체하지 않고 **`-1 (미확인)`**으로 처리합니다.
   - 세션 활성 여부와 무관하게 `PwrKeep`이 켜져 있는 동안 항상 방전 안전 조건을 평가하며, 2분 이상 세션 없이 `PwrKeep`만 켜져 있는 고아 상태도 자동 해제합니다.
   - 다음 조건 중 하나라도 발생하면 즉시 `requestPwrKeepStop(2)`을 전송(앱이 IG3를 켠 경우에만 `reqEVIG3Change(false)` 전송)하고 `confirmPrepareSystemOff()` / `confirmSystemOff()`를 호출해 헤드유닛을 안전하게 종료합니다:
     1. **실측 SOC 하한**: `socPct < 55%`
     2. **BCM 배터리 상태 플래그 이상**: `BCMVehicleStatusListener.onBatSocStatus_M(1 /* UNNORMAL */)`
     3. **ICU 전원 자동 차단 사전 경보**: `BCMManager.getBattAutoCutPreWrng() == 1` (`onChangedBattAutoCutPreWrng(1)`)
     4. **BCM 배터리 방전 경보**: `BCMManager.getBatEvtAlramState() > 0` (`onBatEvtAlarmChanged`)
     5. **Micom 방전 경보**: `MicomPowerStatusListener.onDischargeAlert()` / `onLogicOnDischargeAlert()`
     6. **실측 전압 하한**: 직접 측정된 전압(`SYSFS_ADC` / `SYS_PROP`)이 `11.55V` 미만일 때
     7. **SOC 미확인 시간 한도**: 주차 중 SOC 미확인(`-1`) 상태가 **30분 이상** 지속될 때
   - 주차 중 수동 중지 및 안전 차단 상태는 `power_latch.txt`에 영구 저장되어 데몬 재시작이나 원격 시동/공조(`TeleAcc == 2`)에도 풀리지 않으며, 실제 시동 ON(`powerStatus == 0`) 또는 사용자의 수동 시작 시에만 해제됩니다.
   - 데몬 중지(`on_stop`) 또는 패키지 제거·업데이트(`scripts/preun.sh`) 시에는 자기 PID가 일치할 때만 PID 파일을 지우며, 리스너 등록 없이 `Looper.prepareMainLooper()`가 준비된 `AuxMonMain release-keep` 원샷(실패 시 1회 재시도 및 `logcat` 기록)을 실행하여 `PwrKeep(2)` 해제를 보장합니다.
   - **패키지 업데이트 안내**: `/system/xbin/nemesis`는 패키지 업데이트 시 기존 설치본의 `preun.sh`(`release-keep` 및 `confirmSystemOff`)를 먼저 실행한 뒤 새 `post.sh`를 실행하므로, **패키지 설치 및 업데이트는 반드시 시동 ON (`ACCON`) 상태에서 진행**하세요.

4. **정직한 차량 텔레메트리 수집 및 충전 횟수 감지**
   - **사용 API**:
     - `MicomPowerManager`: `getPowerStatus()`, `requestPwrKeepStart(2)`, `requestPwrKeepStop(2)`, `confirmPrepareSystemOff()`, `confirmSystemOff()`, `setScreenOn(boolean)`
     - `BCMManager`: `getIGNStatus()`, `getBatSoc_M()`, `getBatSocSegment()`, `getBatEvtAlramState()`, `getBattAutoCutPreWrng()`, `getBattMngAlert()` (`1=HAZARDON 비상등`, `2=TAILON 미등`)
     - `HEVManager`: `getLastIGN3Status()`, `getIGN3Status()`, `reqEVIG3Change(boolean)`, `onEVIG3Changed(boolean)`, `onEVIG3Responded(boolean)`
     - `DATCManager`: `isIGN3Status()`
     - `TM_HSCANAdapter`: `getBatSoc()`, `getIG3RlyMode()`(원시값 로깅 전용), `get_BAT_AuxBattOpt()` (순정 TMU 원격 공조·충전 신호와 충돌하는 `sendIG3RlyMode` 호출 제거)
   - **전압 표시 및 충전 판정**:
     - 12V 직접 전압 센서가 없는 환경에서는 SOC 기반 추정 전압임을 화면과 로그에 **`SOC_EST(추정)` / `IG3_EST(추정)`**으로 명시하며, 추정 전압은 충전 여부 판정이나 전압 안전 차단에 사용하지 않습니다.
     - VCU `getIG3RlyMode()`는 평상시에도 `2` 등을 반환할 수 있으므로 원시값만 CSV/JSON에 기록하고, 주차 중 원격 공조(`powerStatus != 0 && teleAccStatus == 2`)에 의한 IG3 활성은 제외하여 실제 보조배터리 자동충전(`ig3On == true` 또는 실측 전압 `>= 13.55V`)만 충전 횟수로 집계합니다.
     - 수동 IG3 테스트 시 가짜 상태값 강제 주입을 제거하여 실제 차량의 IG3 응답 여부를 그대로 확인할 수 있습니다.
     - 모든 경과 시간 및 스케줄러 주기는 `SystemClock.elapsedRealtime()` 단조 시계를 기준으로 계산합니다.

---

## 2. 설치 및 사용 방법

> **주의**: 패키지 설치·업데이트·제거 시 `/system/xbin/nemesis`가 `preun.sh`(`release-keep`)를 실행하므로, 반드시 **차량 시동 ON (`ACCON`) 상태**에서 진행하세요.

### 방법 A: USB 메모리로 설치 (권장)

1. **USB 메모리 준비**:
   - USB 메모리를 **`FAT32`** (또는 헤드유닛이 인식하는 `exFAT`)로 포맷합니다.
2. **패키지 파일 복사**:
   - [GitHub Releases](https://github.com/GRT47/HKG_5W_batterysaver-toggle/releases)에서 최신 **`HKG_5W_AuxBattery_Monitor_USB.zip`**을 다운로드하여 **USB 메모리 최상위(루트) 경로**에 그대로 압축을 풉니다.
   - 압축을 풀면 USB 루트에 자동 설치 트리거용 `NEMESIS.data`(`type=install`, `path=packages/hkg.auxmon.zip`)와 패키지 본체가 함께 배치됩니다:
     ```text
     [USB 루트]/
     ├── NEMESIS.data
     └── packages/
         └── hkg.auxmon.zip
     ```
3. **헤드유닛에서 설치**:
   - 차량 시동 ON (`ACCON`) 상태에서 헤드유닛의 **데이터 통신용 USB 포트**에 USB 메모리를 꽂으면 NEMESIS가 USB 루트의 `NEMESIS.data`를 인식해 `packages/hkg.auxmon.zip`을 설치합니다.
   - 설치가 완료되면 약 2초 뒤 백그라운드 감시 서비스(`hkg-auxmon`)가 자동으로 시작됩니다.

### 방법 B: ADB 또는 헤드유닛 터미널(Shell)에서 직접 설치

ADB 또는 루트 쉘 접근이 가능한 경우 `hkg.auxmon.zip`을 이용해 직접 설치할 수 있습니다:

```sh
# 1. USB가 연결된 경우 USB에서 바로 설치
/system/xbin/nemesis install /mnt/media_rw/usb0/packages/hkg.auxmon.zip

# 2. 또는 /data/local/tmp로 전송 후 설치 (PC에서 adb push 실행 시)
# adb push hkg.auxmon.zip /data/local/tmp/hkg.auxmon.zip
/system/xbin/nemesis install /data/local/tmp/hkg.auxmon.zip
```

### 패널 실행 및 사용 방법

1. **모니터 패널 열기**:
   - NEMESIS 런처(앱 목록)에서 **`HKG 12V 보조배터리 주차 전압 & 자동충전 모니터`** 아이콘을 터치하면 전체화면 패널이 표시됩니다.
   - 터미널에서 직접 패널을 띄우거나 숨기려면 다음 명령을 사용할 수 있습니다:
     ```sh
     # 패널 표시
     /data/nemesis/apps/hkg.auxmon/bin/launch.sh
     # 또는 명령 파일로 제어 (show / hide / toggle / demo / export)
     echo "show" > /data/local/tmp/hkg_auxmon_cmd
     ```
2. **화면 구성** (전체화면, 1920×720 기준):
   - **왼쪽 · 현재 상태**: `대기 중` / `절전 유지 중` / `자동 충전 중` / `배터리 보호로 중지됨` / `자동 유지 꺼짐` 중 하나를 쉬운 말 설명과 함께 표시하고, 절전 유지 경과 시간과 큰 버튼 하나(`지금 절전 유지 시작` / `절전 유지 중지`)를 둡니다. 주차 중에 중지하면 헤드유닛이 바로 꺼지므로 **중지는 한 번 더 눌러야 실행**됩니다(4초 안에 두 번째 터치).
   - **오른쪽 탭**:
     - `한눈에 보기`: 12V 전압(실측/추정 구분), 충전율, 주차 중 자동 충전 횟수, 방전 보호 상태 4칸
     - `전압 그래프`: 주차 시간대별 전압·충전율·자동 충전 구간 (`◀ 이전` / `다음 ▶`으로 지난 기록 선택)
     - `충전 기록`: 선택한 주차 기록의 충전 회차별 상세와 전압 변화, **`💾 USB로 내보내기`** 버튼
     - `설정`: 절전 유지 시간(`상시` 기본, `1 / 3 / 6 / 12 / 24 / 48시간`, 세부 조절 최대 72시간), 기록 간격(`10 / 30 / 60초`, 기본 30초), 자동 충전 방식, 점검 도구(IG3 충전 테스트, 샘플 기록 만들기)
   - **자동 충전 방식**:
     - `차량 기본 동작만 기록` (기본값): 차량 자체의 Aux. Battery Saver+(IG3) 작동 여부와 전압·SOC 변화를 그대로 관찰·기록합니다.
     - `1시간마다 충전 요청`: 주차 중 1시간마다(또는 SOC 75% 이하 시) `HEVManager.reqEVIG3Change(true)`를 20분간 요청합니다.
   - 상단 **`작게 보기`**를 누르면 화면 위 작은 상태 표시줄로 바뀌며(끌어서 이동 가능, 터치하면 다시 전체화면), **`닫기`**를 눌러도 감시는 계속됩니다.
3. **자동 주차 기록 및 재시동 시 확인**:
   - **시동을 끄면(ACC/IGN OFF)** 자동으로 `PwrKeep(2)`이 활성화되고, Micom이 `DELAYMODE(6)`/`PRESYSTEMOFF(7)`로 바뀌면 LCD 화면이 꺼진 채 절전 감시가 계속됩니다.
   - **다시 시동을 걸면(ACCON)** 주차 기록이 자동 종료·저장됩니다. 주행 중 내비게이션을 가리지 않도록 이때는 **작은 상태 표시줄만** 띄우며, 터치하면 전체 화면에서 직전 주차의 그래프와 충전 횟수를 볼 수 있습니다.
   - USB 메모리를 꽂고 `충전 기록` 탭의 **`💾 USB로 내보내기`** 버튼을 누르면 USB의 `hkg_aux_battery_logs/` 폴더에 CSV·JSON·한글 리포트가 저장됩니다.

### 패키지 제거 (Uninstall) 방법

- **NEMESIS 메뉴에서 제거**: 차량 시동 ON 상태에서 NEMESIS 패키지 매니저를 열고 `hkg.auxmon`을 선택해 **제거(Uninstall)**합니다.
- **터미널에서 제거**:
  ```sh
  /system/xbin/nemesis uninstall hkg.auxmon
  ```
- 제거 시 `scripts/preun.sh`가 실행되어 백그라운드 데몬을 종료하고 `MicomPowerManager.requestPwrKeepStop(2)` 원샷을 호출해 전원 유지 상태를 안전하게 해제합니다.

---

## 3. 소스 빌드 방법

```bash
python build_package.py
```

1. `src_stubs/` 및 `src_java/`를 Java 8 바이트코드로 컴파일합니다.
2. 호스트 JVM에서 `com.hkg.auxmon.AuxMonMain test-self` 자가 테스트를 실행합니다.
3. `d8 --min-api 19 --no-desugaring`으로 KitKat 4.4.2 Dalvik 호환 `classes.dex`(`dex\n035\x00`)를 생성합니다.
4. `build/` 디렉터리에 다음 배포 파일이 생성됩니다:
   - `build/usb_root/packages/hkg.auxmon.zip` (NEMESIS 패키지 파일)
   - `build/HKG_5W_AuxBattery_Monitor_USB.zip` (USB 루트에 바로 압축 해제 가능한 번들)

---

## 4. USB 내보내기 파일 구조

`충전 기록` 탭의 **`💾 USB로 내보내기`** 버튼을 누르면(기록된 세션이 없을 때는 가짜 데이터를 만들지 않고 안내 메시지를 표시합니다), 연결된 USB 메모리(`/mnt/media_rw/usb0`, `/storage/usb0` 등)의 `hkg_aux_battery_logs/` 폴더에 세션별(`_<sessionId>`)로 다음 4종의 파일이 저장됩니다:

| 파일명 | 설명 |
|---|---|
| `parking_report_<sid>.txt` | 주차 세션 요약 리포트 (유지 시간, 총 충전 횟수, 최저/최고 전압·SOC, 충전 회차별 상세 이력) |
| `parking_voltage_samples_<sid>.csv` | 주차 시간 동안 기록된 시계열 CSV (`timestamp_iso, epoch_ms, elapsed_parking_sec, power_status, ig3_on, ig3_rly_mode, voltage_v, voltage_source, soc_pct, bat_soc_status_flag, ...`) |
| `parking_charge_events_<sid>.csv` | 주차 중 발생한 각 충전 회차(`#1, #2, ...`)의 시작/종료 시각, 지속 시간, 전압/SOC 변화 CSV |
| `parking_session_<sid>.json` | 세션 메타데이터 및 충전 이력 JSON |
