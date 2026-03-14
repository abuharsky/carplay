# CarPlay Diagnostic Android Client

Android-приложение на Kotlin для работы с USB-адаптером `CARLINKIT CPC200-CCPA` в режиме диагностического хоста.

## Что уже сделано

- `Foreground Service` держит USB-сессию, heartbeat, init sequence, reconnect и low-level обработку.
- `Activity` работает как экран: fullscreen `SurfaceView`, overlay, connect/reconnect кнопка, replay-кнопка и живой лог.
- Реализованы слои `presentation`, `domain`, `data`, `platform`, чтобы UI не владел USB/codec логикой.
- Добавлены `MediaCodec` renderer, `PacketRingByteBuffer`, базовый `AudioStreamManager`, `TouchInputMapper`, протокол CPC200.
- В проекте сразу заложена отладка через `adb logcat`.
- Добавлен `replay mode` для входящих USB-дампов: можно гонять реальные capture-файлы через тот же сервис и тот же декодер.

## Архитектура

- `platform/service/DongleService`
  Отдельный foreground service. Живет дольше activity и владеет сервисной сессией.

- `data/session/DongleSessionManager`
  Оркестратор подключения: USB или replay dump, heartbeat, init sequence, read/write loops, reconnect, Surface focus, audio/video/touch.

- `data/usb`
  Поиск устройства, permission request, bulk transfer, chunked read/write.

- `data/replay`
  File-backed session для воспроизведения входящих CPC200 сообщений без железа.

- `data/protocol`
  Заголовок CPC200, сериализация `Open`, `SendFile`, `BoxSettings`, `Command`, `HeartBeat`, `MultiTouch`.

- `data/video`
  `MediaCodec` + ring buffer для H.264.

- `presentation`
  `CarPlayActivity` + `CarPlayViewModel`.

## Состояние протокола

- Реализован обязательный `P0`-каркас:
  - header magic + `typeCheck`
  - bulk transfer chunking 16 KB
  - align-to-16 sizing
  - foreground service
  - heartbeat
  - init sequence
  - surface attach/detach
  - request/release video focus
  - reconnect path
  - overlay + logs

- Частично заложен `P1`:
  - per-stream audio manager
  - frame request timer
  - auto reconnect

- Пока не реализовано:
  - полноценные settings UI
  - last-frame screenshot UX
  - microphone capture
  - media metadata parsing UI

## Быстрый запуск

```bash
./gradlew installDebug
adb shell am start -n com.alexander.carplay.debug/com.alexander.carplay.presentation.ui.CarPlayActivity
```

## Replay дампа в эмуляторе

Если есть capture-файл с входящими USB-сообщениями:

```bash
./scripts/replay-capture-emulator.sh /path/to/test-capture.bin
```

Что делает скрипт:

1. устанавливает debug APK
2. `adb push` дамп в `/data/local/tmp/test-capture.bin`
3. открывает `CarPlayActivity` с extra `replay_capture_path=/data/local/tmp/test-capture.bin`

Путь `/data/local/tmp` выбран специально для эмуляторов Android Automotive, чтобы не упираться в multi-user особенности `/sdcard/Android/data/...`.

Можно также запустить replay вручную кнопкой `Replay dump` внутри activity.

## ADB-отладка

Основной лог-тег:

```bash
adb logcat -v time CarPlayDiag:D *:S
```

Для анализа capture-файла локально:

```bash
python3 ./scripts/analyze_capture.py /path/to/test-capture.bin
```

Полезный цикл работы:

1. Подключить телефон к ADB.
2. Подключить адаптер по USB-OTG.
3. Установить/обновить debug APK.
4. Запустить activity.
5. Смотреть `CarPlayDiag` и вносить точечные правки.

## Ключевые файлы

- `app/src/main/java/com/alexander/carplay/data/session/DongleSessionManager.kt`
- `app/src/main/java/com/alexander/carplay/platform/service/DongleService.kt`
- `app/src/main/java/com/alexander/carplay/data/replay/CaptureReplaySession.kt`
- `app/src/main/java/com/alexander/carplay/data/protocol/Cpc200Protocol.kt`
- `app/src/main/java/com/alexander/carplay/data/video/H264Renderer.kt`
- `app/src/main/java/com/alexander/carplay/presentation/ui/CarPlayActivity.kt`
