import 'dart:typed_data';

import 'package:quick_usb/quick_usb.dart';

import 'log.dart';

class UsbManagerWrapper {
  static init() async {
    log('USB Manager init');
    await QuickUsb.init();
  }

  static close() async {
    log('USB Manager close');
    await QuickUsb.exit();
  }

  static Future<List<UsbDeviceWrapper>> lookupForUsbDevice(
      List<Map<int, int>> vendorIdProductIdList) async {
    var devices = await QuickUsb.getDeviceList();
    log('USB Manager device list >>> $devices');

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
    var success = await QuickUsb.openDevice(_usbDevice);
    log('USB Device open >>> $success');

    var conf = await QuickUsb.getConfiguration(0);
    log('USB Device configuration');

    await QuickUsb.setConfiguration(conf);
    log('USB Device set configuration');

    var interface = conf.interfaces.first;
    success = await QuickUsb.claimInterface(interface);
    log('USB Device claimInterface');

    _endpointIn = interface.endpoints
        .firstWhere((e) => e.direction == UsbEndpoint.DIRECTION_IN);

    _endpointOut = interface.endpoints
        .firstWhere((e) => e.direction == UsbEndpoint.DIRECTION_OUT);

    _isOpened = true;
  }

  close() async {
    await QuickUsb.closeDevice();
    _isOpened = false;
  }

  reset() async {
    await QuickUsb.resetDevice(_usbDevice);
  }

  Future<Uint8List> read(int maxLength, {int timeout = 30000}) {
    if (!isOpened) throw "UsbDevice not opened";
    if (_endpointIn == null) throw "UsbDevice endpointIn is null";

    return QuickUsb.bulkTransferIn(_endpointIn!, maxLength, timeout: timeout);
  }

  Future<int> write(Uint8List data) {
    if (!isOpened) throw "UsbDevice not opened";
    if (_endpointOut == null) throw "UsbDevice endpointOut is null";

    return QuickUsb.bulkTransferOut(_endpointOut!, data);
  }
}
