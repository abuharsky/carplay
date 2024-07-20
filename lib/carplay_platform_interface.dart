import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'carplay_method_channel.dart';
import 'usb.dart';

abstract class CarplayPlatform extends PlatformInterface {
  /// Constructs a CarplayPlatform.
  CarplayPlatform() : super(token: _token);

  static final Object _token = Object();

  static CarplayPlatform _instance = MethodChannelCarplay();

  /// The default instance of [CarplayPlatform] to use.
  ///
  /// Defaults to [MethodChannelCarplay].
  static CarplayPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [CarplayPlatform] when
  /// they register themselves.
  static set instance(CarplayPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  static setLogHandler(Function(String)? logHandler) {
    (_instance as MethodChannelCarplay).setLogHandler(logHandler);
  }

  Future<void> startReadingLoop(
    UsbEndpoint endpoint,
    int timeout, {
    required Function(int, Uint8List?) onMessage,
    required Function(String) onError,
  }) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<void> stopReadingLoop() async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<int> createTexture(int width, int height) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<void> removeTexture() async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<void> resetH264Renderer() async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<void> processData(Uint8List data) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<List<UsbDevice>> getDeviceList() async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<List<UsbDeviceDescription>> getDevicesWithDescription({
    bool requestPermission = true,
  }) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<UsbDeviceDescription> getDeviceDescription(
    UsbDevice usbDevice, {
    bool requestPermission = true,
  }) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> hasPermission(UsbDevice usbDevice) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> requestPermission(UsbDevice usbDevice) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> openDevice(UsbDevice usbDevice) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<void> closeDevice() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> resetDevice() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<UsbConfiguration> getConfiguration(int index) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> setConfiguration(UsbConfiguration config) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> claimInterface(UsbInterface intf) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> releaseInterface(UsbInterface intf) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<Uint8List> bulkTransferIn(
      UsbEndpoint endpoint, int maxLength, int timeout,
      {bool isVideoData = false}) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<int> bulkTransferOut(
      UsbEndpoint endpoint, Uint8List data, int timeout) async {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
