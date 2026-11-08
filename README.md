# HKG Gen5W 시동 OFF 후 절전 상시 유지 & 12V 보조배터리 자동충전 모니터 (`hkg.auxmon`)

현대·기아 표준형 5세대 와이드(Standard Gen5W, Telechips `tcc897x`, Android 4.4.2 KitKat API 19) 헤드유닛에서 **시동을 끈 뒤에도 헤드유닛 전원을 끄지 않고 절전 상태로 유지(`MicomPowerManager.requestPwrKeepStart(2)`)**하며, 주차 시간 동안 **12V 보조배터리 충전율(SOC %)·전압(추정/실측 V) 변화와 자동충전(Aux. Battery Saver+ / IG3) 작동 이력을 기록하고 USB 메모리로 내보내는 NEMESIS 패키지**입니다.

---

## 1. 핵심 동작 및 수정 반영 사항

1. **이벤트 기반 즉시 전원 유지 (`PwrKeep`) 및 60초 주기 재전송**
   - `MicomPowerStatusListener`의 `onTeleAccStatusChanged(ACCOFF=1)`, `onPowerStatusChanged(LOGICON=1 / ADM=2 / DELAYMODE=6 / PRESYSTEMOFF=7)`, `onPrepareSystemOff()`, `onSystemOff()` 콜백이 호출되는 즉시 지연 없이 `MicomPowerManager.requestPwrKeepStart(2)`를 전송하고, 안전 조건이 유지되는 동안 `confirmPrepareSystemOff()` / `confirmSystemOff()` 호출을 보류합니다.
   - 순정 텔레매틱스 앱(`HKMC_TAppService`, `HKMC_TAppVRM`, `HKMC_DMClient_OTA`)이 동일한 `mode=2`로 `requestPwrKeepStop(2)`을 보내더라도 전원이 꺼지지 않도록, 주차 유지 중 **60초마다 `requestPwrKeepStart(2)`를 재전송(Heartbeat)**합니다.
   - 목표 유지 시간으로 **`상시(무제한)` (`target_hours=0`, 기본값)** 및 **`1 / 3 / 6 / 12 / 24 / 48시간`** 프리셋(수동 1~72시간 조절 가능)을 지원합니다.

2. **절전 진입 및 저부하 폴링**
   - `powerStatus == 0 (ACCON)`을 최우선으로 검사하여, 이전 원격/주차 이벤트로 `TeleAcc == 1 (ACCOFF)`이 캐시에 남아 있더라도 주행 중에는 절대 주차로 오판하거나 화면을 끄지 않습니다.
   - LCD 화면 끄기(`MicomPowerManager.setScreenOn(false)`)는 `powerStatus`가 **`6 (DELAYMODE)` 또는 `7 (PRESYSTEMOFF)`로 확인되었을 때만** 수행하며, `LOGICON(1)`·`ADM(2)`(시동 OFF 상태에서 헤드유닛 사용 중)에서는 전원만 유지하고 화면은 끄지 않습니다.
   - 부팅 시에는 오버레이 패널을 자동으로 띄우지 않으며, 사용자가 패키지 아이콘(`bin/launch.sh`)을 실행하거나 주차 감시 후 재시동했을 때만 패널을 표시합니다.
   - 주차 중 샘플링 및 하드웨어 폴링 주기를 패널에서 **`10s / 30s / 60s` (기본 `30s`)**로 선택할 수 있으며, 불필요한 `getIGN3Status()` 매초 호출을 제거하여 CAN/Binder 부하를 최소화했습니다.

3. **엄격한 12V 보조배터리 방전 보호 (Safety Cutoff) 및 래치 유지**
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
   - 주차 중 수동 중지 및 안전 차단 상태는 `power_latch.txt`에 영구 저장되어 데몬 재시작 시에도 풀리지 않으며(주행 중 테스트 세션을 수동 중지한 경우에는 `auto_keep=0`을 저장하지 않음), 다음 `ACCON`(시동 ON) 또는 사용자의 수동 시작 시에만 해제됩니다.
   - 데몬 중지(`on_stop`) 또는 패키지 제거(`scripts/preun.sh`) 시 래퍼 재시작을 먼저 차단한 뒤 리스너 등록 없이 `Looper.prepareMainLooper()`가 준비된 `AuxMonMain release-keep` 원샷을 실행(실패 시 1회 재시도 및 `logcat` 기록)하여 `PwrKeep(2)` 해제를 보장합니다.

4. **정직한 차량 텔레메트리 수집 및 충전 횟수 감지**
   - **사용 API**:
     - `MicomPowerManager`: `getPowerStatus()`, `requestPwrKeepStart(2)`, `requestPwrKeepStop(2)`, `confirmPrepareSystemOff()`, `confirmSystemOff()`, `setScreenOn(boolean)`
     - `BCMManager`: `getIGNStatus()`, `getBatSoc_M()`, `getBatSocSegment()`, `getBatEvtAlramState()`, `getBattAutoCutPreWrng()`, `getBattMngAlert()` (`1=HAZARDON 비상등`, `2=TAILON 미등`)
     - `HEVManager`: `getLastIGN3Status()`, `getIGN3Status()`, `reqEVIG3Change(boolean)`, `onEVIG3Changed(boolean)`, `onEVIG3Responded(boolean)`
     - `DATCManager`: `isIGN3Status()`
     - `TM_HSCANAdapter`: `getBatSoc()`, `getIG3RlyMode()`(원시값 로깅 전용), `get_BAT_AuxBattOpt()` (순정 TMU 원격 공조·충전 신호와 충돌하는 `sendIG3RlyMode` 호출 제거)
   - **전압 표시 및 충전 판정**:
     - 12V 직접 전압 센서가 없는 환경에서는 SOC 기반 추정 전압임을 화면과 로그에 **`SOC_EST(추정)` / `IG3_EST(추정)`**으로 명시하며, 추정 전압은 충전 여부 판정이나 전압 안전 차단에 사용하지 않습니다.
     - VCU `getIG3RlyMode()`는 평상시에도 `2` 등을 반환할 수 있으므로 원시값만 CSV/JSON에 기록하며, 실제 충전 판정은 권위 있는 `ig3On == true` (`HEVManager` / `DATCManager`) 또는 실측 전압 상승(`>= 13.55V`)만을 기준으로 충전 횟수를 집계합니다.
     - 수동 IG3 테스트 시 가짜 상태값 강제 주입을 제거하여 실제 차량의 IG3 응답 여부를 그대로 확인할 수 있습니다.
     - 모든 경과 시간 및 스케줄러 주기는 `SystemClock.elapsedRealtime()` 단조 시계를 기준으로 계산합니다.

---

## 2. 빌드 방법

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

## 3. USB 내보내기 파일 구조

패널 하단의 **`💾 데이터 내보내기 (USB 저장)`** 버튼을 누르면(기록된 세션이 없을 때는 가짜 데이터를 만들지 않고 안내 메시지를 표시합니다), 연결된 USB 메모리(`/mnt/media_rw/usb0`, `/storage/usb0` 등)의 `hkg_aux_battery_logs/` 폴더에 세션별(`_<sessionId>`)로 다음 4종의 파일이 저장됩니다:

| 파일명 | 설명 |
|---|---|
| `parking_report_<sid>.txt` | 주차 세션 요약 리포트 (유지 시간, 총 충전 횟수, 최저/최고 전압·SOC, 충전 회차별 상세 이력) |
| `parking_voltage_samples_<sid>.csv` | 주차 시간 동안 기록된 시계열 CSV (`timestamp_iso, epoch_ms, elapsed_parking_sec, power_status, ig3_on, ig3_rly_mode, voltage_v, voltage_source, soc_pct, bat_soc_status_flag, ...`) |
| `parking_charge_events_<sid>.csv` | 주차 중 발생한 각 충전 회차(`#1, #2, ...`)의 시작/종료 시각, 지속 시간, 전압/SOC 변화 CSV |
| `parking_session_<sid>.json` | 세션 메타데이터 및 충전 이력 JSON |
