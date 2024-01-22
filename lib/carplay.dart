import 'dart:async';

import 'dongle_driver.dart';
import 'usb_device_wrapper.dart';
import 'common.dart';
import 'log.dart';

// ignore: constant_identifier_names
const USB_WAIT_PERIOD_MS = 3000;

const EXTENDED_LOG = false;

class Carplay {
  Timer? _pairTimeout;
  Timer? _frameInterval;

  final DongleConfig _config;

  Dongle? _dongleDriver;

  final Function(Message) _messageHandler;

  Carplay(this._config, this._messageHandler);

  Future<UsbDeviceWrapper> _findDevice() async {
    UsbDeviceWrapper? device;

    while (device == null) {
      try {
        final deviceList =
            await UsbManagerWrapper.lookupForUsbDevice(knownDevices);
        device = deviceList.firstOrNull;
      } catch (err) {
        // ^ requestDevice throws an error when no device is found, so keep retrying
      }

      if (device == null) {
        log('No device found, retrying');
        await Future.delayed(const Duration(milliseconds: USB_WAIT_PERIOD_MS));
      }
    }

    return device;
  }

  start() async {
    // Find device to "reset" first
    var device = await _findDevice();

    await device.open();
    await device.reset();
    await device.close();
    // Resetting the device causes an unplug event in node-usb
    // so subsequent writes fail with LIBUSB_ERROR_NO_DEVICE
    // or LIBUSB_TRANSFER_ERROR

    log('Reset device, finding again...');
    await Future.delayed(const Duration(milliseconds: USB_WAIT_PERIOD_MS));
    // ^ Device disappears after reset for 1-3 seconds

    device = await _findDevice();
    log('found & opening');

    _dongleDriver = Dongle(device, _handleDongleMessage, _handleDongleError);

    try {
      await device.open();

      await _dongleDriver?.start();

      _pairTimeout = Timer(const Duration(seconds: 15), () {
        _dongleDriver?.send(SendCommand(CommandMapping.wifiPair));
      });
    } catch (e) {
      log(e.toString());

      log('carplay not initialised, retrying in 2s');

      await Future.delayed(const Duration(seconds: 2), start);
    }
  }

  stop() async {
    try {
      _clearPairTimeout();
      _clearFrameInterval();
      await _dongleDriver?.close();
    } catch (err) {
      log(err.toString());
    }
  }

  sendKey(CommandMapping action) {
    _dongleDriver?.send(SendCommand(action));
  }

  sendTouch(TouchAction type, int x, int y) {
    _dongleDriver?.send(SendTouch(type, x, y));
  }

  _handleDongleMessage(Message message) {
    if (message is Plugged) {
      _clearPairTimeout();
      _clearFrameInterval();

      final phoneTypeConfig = _config.phoneConfig[message.phoneType];
      final interval = phoneTypeConfig?["frameInterval"];
      if (interval != null) {
        _frameInterval =
            Timer.periodic(Duration(milliseconds: interval), (timer) async {
          await _dongleDriver?.send(SendCommand(CommandMapping.frame));
        });
      }

      _messageHandler(message);
    } else if (message is Unplugged) {
      _messageHandler(message);
    } else if (message is VideoData) {
      _clearPairTimeout();
      _messageHandler(message);
    } else if (message is AudioData) {
      _clearPairTimeout();
      _messageHandler(message);
    } else if (message is MediaData) {
      _clearPairTimeout();
      _messageHandler(message);
    } else if (message is Command) {
      _messageHandler(message);
    }

    // Trigger internal event logic
    if (message is AudioData && message.command != null) {
      switch (message.command) {
        case AudioCommand.AudioSiriStart:
        case AudioCommand.AudioPhonecallStart:
//            mic.start()
          break;
        case AudioCommand.AudioSiriStop:
        case AudioCommand.AudioPhonecallStop:
//            mic.stop()
          break;

        default:
          break;
      }
    }
  }

  _handleDongleError({String? error}) async {
    await stop();
    await start();
  }

  _clearPairTimeout() {
    _pairTimeout?.cancel();
    _pairTimeout = null;
  }

  _clearFrameInterval() {
    if (_frameInterval != null) {
      _frameInterval?.cancel();
      _frameInterval = null;
    }
  }
}
