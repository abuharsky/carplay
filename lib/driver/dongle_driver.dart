import 'dart:async';

import '../common.dart';

import 'readable.dart';
import 'sendable.dart';
import 'usb/usb_device_wrapper.dart';

class Dongle {
  final UsbDeviceWrapper _usbDevice;

  Function(Message)? _messageHandler;
  Function({String? error})? _errorHandler;

  final Function(String) _logHandler;

  Timer? _heartBeat;

  late final int _readTimeout;
  late final int _writeTimeout;

  Dongle(this._usbDevice, this._messageHandler, this._errorHandler,
      this._logHandler,
      {int readTimeout = 30000, writeTimeout = 1000}) {
    _readTimeout = readTimeout;
    _writeTimeout = writeTimeout;
  }

  start() async {
    _logHandler('Dongle initializing');

    if (!_usbDevice.isOpened) {
      _logHandler('usbDevice not opened');
      _errorHandler?.call(error: 'usbDevice not opened');
      return;
    }

    final config = DEFAULT_CONFIG;

    final initMessages = [
      SendNumber(config.dpi, FileAddress.DPI),
      SendOpen(config),
      SendBoolean(config.nightMode, FileAddress.NIGHT_MODE),
      SendNumber(config.hand.id, FileAddress.HAND_DRIVE_MODE),
      SendBoolean(true, FileAddress.CHARGE_MODE),
      SendString(config.boxName, FileAddress.BOX_NAME),
      SendBoxSettings(config, null),
      SendCommand(CommandMapping.wifiEnable),
      SendCommand(config.wifiType == '5ghz'
          ? CommandMapping.wifi5g
          : CommandMapping.wifi24g),
      SendCommand(
          config.micType == 'box' ? CommandMapping.boxMic : CommandMapping.mic),
      SendCommand(
        config.audioTransferMode
            ? CommandMapping.audioTransferOn
            : CommandMapping.audioTransferOff,
      ),
      if (config.androidWorkMode == true)
        SendBoolean(config.androidWorkMode!, FileAddress.ANDROID_WORK_MODE),
    ];

    for (final message in initMessages) {
      await send(message);
    }

    _heartBeat?.cancel();
    _heartBeat = Timer.periodic(const Duration(seconds: 2), (timer) async {
      await send(HeartBeat());
    });

    await _readLoop();
  }

  close() async {
    _heartBeat?.cancel();
    _heartBeat = null;

    _errorHandler = null;
    _messageHandler = null;

    await _usbDevice.stopReadingLoop();
    await _usbDevice.close();
  }

  Future<bool> send(SendableMessage message) async {
    try {
      final data = message.serialise();

      _logHandler(
          '[SEND] ${message.type.name} ${(message is SendCommand ? message.value.name : "")}');

      final length = await _usbDevice.write(data, timeout: _writeTimeout);

      if (data.length == length) {
        return true;
      }
    } catch (e) {
      _logHandler("send error $e");
      _errorHandler?.call(error: e.toString());
    }

    return false;
  }

  _readLoop() async {
    await _usbDevice.startReadingLoop(
      //
      onMessage: (type, data) async {
        final header =
            MessageHeader(data?.length ?? 0, MessageType.fromId(type));
        final message = header.toMessage(data?.buffer.asByteData());
        if (message != null) {
          _logHandler(
              "[RECV] ${message.header.type.name} ${(message is Command ? message.value.name : "")}, length: ${data?.lengthInBytes ?? 0}");

          try {
            _messageHandler?.call(message);
          } catch (e) {
            _logHandler("Error handling message, ${e.toString()}");
          }

          if (message is Opened) {
            await send(SendCommand(CommandMapping.wifiConnect));
          }
        }
      },
      //
      onError: (error) {
        _logHandler("ReadingLoopError $error");
        _errorHandler?.call(error: "ReadingLoopError $error");
      },
      //
      timeout: _readTimeout,
    );
  }
}
