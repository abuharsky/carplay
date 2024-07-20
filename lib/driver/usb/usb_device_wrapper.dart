import 'dart:typed_data';

import '../../carplay_platform_interface.dart';
import '../../usb.dart';

class UsbManagerWrapper {
  static Future<List<UsbDeviceWrapper>> lookupForUsbDevice(
      List<Map<int, int>> vendorIdProductIdList) async {
    var devices = await CarplayPlatform.instance.getDeviceList();

    var filtered = devices.where((device) => vendorIdProductIdList
        .where((pair) =>
            device.vendorId == pair.entries.first.key &&
            device.productId == pair.entries.first.value)
        .isNotEmpty);

    var wrapped = filtered.map((e) => UsbDeviceWrapper(e)).toList();

    return wrapped;
  }
}

class UsbDeviceWrapper {
  bool _isOpened = false;
  bool get isOpened => _isOpened;

  final UsbDevice _usbDevice;

  UsbEndpoint? _endpointIn;
  UsbEndpoint? _endpointOut;

  UsbDeviceWrapper(this._usbDevice);

  open() async {
    await CarplayPlatform.instance.requestPermission(_usbDevice);

    await CarplayPlatform.instance.openDevice(_usbDevice);

    var conf = await CarplayPlatform.instance.getConfiguration(0);

    await CarplayPlatform.instance.setConfiguration(conf);

    var interface = conf.interfaces.first;
    await CarplayPlatform.instance.claimInterface(interface);

    _endpointIn = interface.endpoints
        .firstWhere((e) => e.direction == UsbEndpoint.DIRECTION_IN);

    _endpointOut = interface.endpoints
        .firstWhere((e) => e.direction == UsbEndpoint.DIRECTION_OUT);

    _isOpened = true;
  }

  close() async {
    await CarplayPlatform.instance.closeDevice();
    _isOpened = false;
  }

  reset() async {
    await CarplayPlatform.instance.resetDevice();
  }

  Future<void> startReadingLoop({
    required Function(int, Uint8List?) onMessage,
    required Function(String) onError,
    int timeout = 10000,
  }) {
    if (!isOpened) throw "UsbDevice not opened";
    if (_endpointIn == null) throw "UsbDevice endpointIn is null";

    return CarplayPlatform.instance.startReadingLoop(
      _endpointIn!,
      timeout,
      onMessage: onMessage,
      onError: onError,
    );
  }

  Future<void> stopReadingLoop() {
    return CarplayPlatform.instance.stopReadingLoop();
  }

  Future<Uint8List> read(int maxLength,
      {int timeout = 10000, bool isVideoData = false}) {
    if (!isOpened) throw "UsbDevice not opened";
    if (_endpointIn == null) throw "UsbDevice endpointIn is null";

    return CarplayPlatform.instance.bulkTransferIn(
      _endpointIn!,
      maxLength,
      timeout,
      isVideoData: isVideoData,
    );
  }

  Future<int> write(Uint8List data, {int timeout = 10000}) async {
    if (!isOpened) throw "UsbDevice not opened";
    if (_endpointOut == null) throw "UsbDevice endpointOut is null";

    return CarplayPlatform.instance.bulkTransferOut(
      _endpointOut!,
      data,
      timeout,
    );
  }
}
