// ignore_for_file: constant_identifier_names

import 'dart:convert';
import 'dart:typed_data';

import 'package:convert/convert.dart';
import 'package:dart_buffer/dart_buffer.dart';

enum CommandMapping {
  invalid(0), //'invalid'),
  startRecordAudio(1),
  stopRecordAudio(2),
  requestHostUI(3), //'Carplay Interface My Car button clicked'),
  siri(5), //'Siri Button'),
  mic(7), //'Car Microphone'),
  boxMic(15), //'Box Microphone'),
  enableNightMode(16), // night mode
  disableNightMode(17), // disable night mode
  wifi24g(24), //'2.4G Wifi'),
  wifi5g(25), //'5G Wifi'),
  left(100), //'Button Left'),
  right(101), //'Button Right'),
  frame(12),
  audioTransferOn(
      22), // Phone will Stream audio directly to car system and not dongle
  audioTransferOff(
      23), // DEFAULT - Phone will stream audio to the dongle and it will send it over the link
  selectDown(104), //'Button Select Down'),
  selectUp(105), //'Button Select Up'),
  back(106), //'Button Back'),
  down(114), //'Button Down'),
  home(200), //'Button Home'),
  play(201), //'Button Play'),
  pause(202), //'Button Pause'),
  next(204), //'Button Next Track'),
  prev(205), //'Button Prev Track'),
  requestVideoFocus(500),
  releaseVideoFocus(501),
  wifiEnable(1000),
  autoConnectEnable(1001),
  wifiConnect(1002),
  scanningDevice(1003),
  deviceFound(1004),
  deviceNotFound(1005),
  connectDeviceFailed(1006),
  btConnected(1007),
  btDisconnected(1008),
  wifiConnected(1009),
  wifiDisconnected(1010),
  btPairStart(1011),
  wifiPair(1012);

  final int id;
  const CommandMapping(this.id);

  factory CommandMapping.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => invalid);
  }
}

enum MessageType {
  Open(0x01),
  Plugged(0x02),
  Phase(0x03),
  Unplugged(0x04),
  Touch(0x05),
  VideoData(0x06),
  AudioData(0x07),
  Command(0x08),
  LogoType(0x09),
  DisconnectPhone(0xf),
  CloseDongle(0x15),
  BluetoothAddress(0x0a),
  BluetoothPIN(0x0c),
  BluetoothDeviceName(0x0d),
  WifiDeviceName(0x0e),
  BluetoothPairedList(0x12),
  ManufacturerInfo(0x14),
  MultiTouch(0x17),
  HiCarLink(0x18),
  BoxSettings(0x19),
  MediaData(0x2a),
  SendFile(0x99),
  HeartBeat(0xaa),
  SoftwareVersion(0xcc),

  Unknown(-1);

  final int id;
  const MessageType(this.id);

  factory MessageType.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

enum AudioCommand {
  AudioOutputStart(1),
  AudioOutputStop(2),
  AudioInputConfig(3),
  AudioPhonecallStart(4),
  AudioPhonecallStop(5),
  AudioNaviStart(6),
  AudioNaviStop(7),
  AudioSiriStart(8),
  AudioSiriStop(9),
  AudioMediaStart(10),
  AudioMediaStop(11),
  AudioAlertStart(12),
  AudioAlertStop(13),

  Unknown(-1);

  final int id;
  const AudioCommand(this.id);

  factory AudioCommand.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

enum FileAddress {
  DPI('/tmp/screen_dpi'),
  NIGHT_MODE('/tmp/night_mode'),
  HAND_DRIVE_MODE('/tmp/hand_drive_mode'),
  CHARGE_MODE('/tmp/charge_mode'),
  BOX_NAME('/etc/box_name'),
  OEM_ICON('/etc/oem_icon.png'),
  AIRPLAY_CONFIG('/etc/airplay.conf'),
  ICON_120('/etc/icon_120x120.png'),
  ICON_180('/etc/icon_180x180.png'),
  ICON_250('/etc/icon_256x256.png'),
  ANDROID_WORK_MODE('/etc/android_work_mode'),

  Unknown("");

  final String path;
  const FileAddress(this.path);

  factory FileAddress.fromId(String path) {
    return values.firstWhere((e) => e.path == path, orElse: () => Unknown);
  }
}

class HeaderBuildError extends Error {
  final String message;

  HeaderBuildError(this.message);
}

class MessageHeader {
  final int length;
  final MessageType type;

  MessageHeader(this.length, this.type);

  @override
  String toString() => "MessageHeader: length=$length, type=${type.name}";

  static MessageHeader fromBuffer(BufferReader data) {
    if (data.length != 16) {
      throw HeaderBuildError(
          'Invalid buffer size - Expecting 16, got ${data.length}');
    }
    final magic = data.getUInt32();
    if (magic != MessageHeader.magic) {
      throw HeaderBuildError('Invalid magic number, received $magic');
    }
    final length = data.getUInt32();
    final typeInt = data.getUInt32();
    final msgType = MessageType.fromId(typeInt);

    if (msgType == MessageType.Unknown) {
      print("Unknown message type: $typeInt");
    } else {
      final typeCheck = data.getUInt32();
      if (typeCheck != ((msgType.id ^ -1) & 0xffffffff) >>> 0) {
        throw HeaderBuildError('Invalid type check, received $typeCheck');
      }
    }

    return MessageHeader(length, msgType);
  }

  static ByteData asBuffer(MessageType messageType, int byteLength) {
    final data = BufferWriter.withCapacity(4 + 4 + 4 + 4);
    data.setUInt32(MessageHeader.magic);
    data.setUInt32(byteLength);
    data.setUInt32(messageType.id);
    data.setUInt32(((messageType.id ^ -1) & 0xffffffff) >>> 0);
    return data.buffer;
  }

  Message? toMessage(ByteData? data) {
    if (data != null) {
      switch (type) {
        case MessageType.AudioData:
          return AudioData(this, data);
        case MessageType.VideoData:
          return VideoData(this, data);
        case MessageType.MediaData:
          return MediaData(this, data);
        case MessageType.BluetoothAddress:
          return BluetoothAddress(this, data);
        case MessageType.BluetoothDeviceName:
          return BluetoothDeviceName(this, data);
        case MessageType.BluetoothPIN:
          return BluetoothPIN(this, data);
        case MessageType.ManufacturerInfo:
          return ManufacturerInfo(this, data);
        case MessageType.SoftwareVersion:
          return SoftwareVersion(this, data);
        case MessageType.Command:
          return Command(this, data);
        case MessageType.Plugged:
          return Plugged(this, data);
        case MessageType.WifiDeviceName:
          return WifiDeviceName(this, data);
        case MessageType.HiCarLink:
          return HiCarLink(this, data);
        case MessageType.BluetoothPairedList:
          return BluetoothPairedList(this, data);
        case MessageType.Open:
          return Opened(this, data);
        case MessageType.BoxSettings:
          return BoxInfo(this, data);
        case MessageType.Phase:
          return Phase(this, data);
        default:
          print('Unknown message type: $type, data: ${data.toString()}');

          return null;
      }
    } else {
      switch (type) {
        case MessageType.Unplugged:
          return Unplugged(this);

        default:
          print('Unknown message type without data: ${type}');

          return null;
      }
    }
  }

  static int dataLength = 16;
  static int magic = 0x55aa55aa;
}

abstract class Message {
  final MessageHeader header;

  Message(this.header);
}

class Command extends Message {
  late final CommandMapping value;

  Command(super.header, ByteData data) {
    value = CommandMapping.fromId(BufferReader(data).getUInt32());
  }

  @override
  String toString() => "Command: ${value.name}";
}

class ManufacturerInfo extends Message {
  late final int a;
  late final int b;

  ManufacturerInfo(super.header, ByteData data) {
    final reader = BufferReader(data);

    a = reader.getUInt32();
    a = reader.getUInt32();
  }

  @override
  String toString() => "ManufacturerInfo: a=$a, b=$b";
}

class SoftwareVersion extends Message {
  late final String version;

  SoftwareVersion(super.header, ByteData data) {
    version = ascii.decode(data.buffer.asUint8List(), allowInvalid: true);
  }

  @override
  String toString() => "SoftwareVersion: $version";
}

class BluetoothAddress extends Message {
  late final String address;

  BluetoothAddress(super.header, ByteData data) {
    address = ascii.decode(data.buffer.asUint8List(), allowInvalid: true);
  }

  @override
  String toString() => "BluetoothAddress: $address";
}

class BluetoothPIN extends Message {
  late final String pin;

  BluetoothPIN(super.header, ByteData data) {
    pin = ascii.decode(data.buffer.asUint8List(), allowInvalid: true);
  }

  @override
  String toString() => "BluetoothPIN: $pin";
}

class BluetoothDeviceName extends Message {
  late final String name;

  BluetoothDeviceName(super.header, ByteData data) {
    name = ascii.decode(data.buffer.asUint8List(), allowInvalid: true);
  }

  @override
  String toString() => "BluetoothDeviceName: $name";
}

class WifiDeviceName extends Message {
  late final String name;

  WifiDeviceName(super.header, ByteData data) {
    name = ascii.decode(data.buffer.asUint8List(), allowInvalid: true);
  }

  @override
  String toString() => "WifiDeviceName: $name";
}

class HiCarLink extends Message {
  late final String link;

  HiCarLink(super.header, ByteData data) {
    link = ascii.decode(data.buffer.asUint8List(), allowInvalid: true);
  }

  @override
  String toString() => "HiCarLink: $link";
}

class BluetoothPairedList extends Message {
  late final String data;

  BluetoothPairedList(super.header, ByteData buf) {
    data = ascii.decode(buf.buffer.asUint8List(), allowInvalid: true);
  }

  @override
  String toString() => "BluetoothPairedList: $data";
}

enum PhoneType {
  AndroidMirror(1),
  CarPlay(3),
  iPhoneMirror(4),
  AndroidAuto(5),
  HiCar(6),

  Unknown(-1);

  final int id;
  const PhoneType(this.id);

  factory PhoneType.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class Plugged extends Message {
  late final PhoneType phoneType;
  late final int? wifi;

  Plugged(super.header, ByteData data) {
    final reader = BufferReader(data);
    phoneType = PhoneType.fromId(reader.getUInt32());

    final wifiAvail = data.lengthInBytes == 8;
    if (wifiAvail) {
      wifi = reader.getUInt32();

      print('wifi avail, phone type: $phoneType wifi: $wifi');
    } else {
      print('no wifi avail, phone type: $phoneType');
    }
  }

  @override
  String toString() => "Plugged: phoneType=${phoneType.name}, wifi=$wifi";
}

class Unplugged extends Message {
  Unplugged(super.header);

  @override
  String toString() => "Unplugged";
}

class AudioFormat {
  final int frequency;
  final int channel;
  final int bitrate;

  const AudioFormat(
      {required this.frequency, required this.channel, required this.bitrate});
}

const Map<int, AudioFormat> decodeTypeMap = {
  1: AudioFormat(
    frequency: 44100,
    channel: 2,
    bitrate: 16,
  ),
  2: AudioFormat(
    frequency: 44100,
    channel: 2,
    bitrate: 16,
  ),
  3: AudioFormat(
    frequency: 8000,
    channel: 1,
    bitrate: 16,
  ),
  4: AudioFormat(
    frequency: 48000,
    channel: 2,
    bitrate: 16,
  ),
  5: AudioFormat(
    frequency: 16000,
    channel: 1,
    bitrate: 16,
  ),
  6: AudioFormat(
    frequency: 24000,
    channel: 1,
    bitrate: 16,
  ),
  7: AudioFormat(
    frequency: 16000,
    channel: 2,
    bitrate: 16,
  ),
};

class AudioData extends Message {
  late final AudioCommand? command;
  late final int decodeType;
  late final double volume;
  late final double? volumeDuration;
  late final int audioType;
  late final Uint16List? data;

  AudioData(super.header, ByteData data) {
    final reader = BufferReader(data);

    decodeType = reader.getUInt32();
    volume = reader.getFloat32();
    audioType = reader.getUInt32();
    final amount = data.lengthInBytes - 12;
    if (amount == 1) {
      command = AudioCommand.fromId(reader.getInt8());
    } else if (amount == 4) {
      volumeDuration = reader.getFloat32();
    } else {
      this.data = data.buffer.asUint16List(12);
    }
  }

  @override
  String toString() =>
      "AudioData: command=${command?.name}, decodeType=$decodeType, volume=$volume, audioType=$audioType";
}

class VideoData extends Message {
  late final int width;
  late final int height;
  late final int flags;
  late final int length;
  late final int unknown;
  late final ByteBuffer data;

  VideoData(super.header, ByteData data) {
    final reader = BufferReader(data);

    width = reader.getUInt32();
    height = reader.getUInt32();
    flags = reader.getUInt32();
    length = reader.getUInt32();
    unknown = reader.getUInt32();

    this.data = data.buffer.asByteData(20).buffer;
  }

  @override
  String toString() =>
      "VideoData: width=$width, height=$height, flags=$flags, length=$length";
}

enum MediaType {
  Data(1),
  AlbumCover(3),

  Unknown(-1);

  final int id;
  const MediaType(this.id);

  factory MediaType.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class MediaData extends Message {
  late final MediaType type;
  late final Map? payload;
  // | {
  //     type: MediaType.Data
  //     media: {
  //       MediaSongName?: string
  //       MediaAlbumName?: string
  //       MediaArtistName?: string
  //       MediaAPPName?: string
  //       MediaSongDuration?: number
  //       MediaSongPlayTime?: number
  //     }
  //   }
  // | { type: MediaType.AlbumCover; base64Image: string }

  MediaData(super.header, ByteData data) {
    final reader = BufferReader(data);

    type = MediaType.fromId(reader.getUInt32());

    final extraData = data.buffer.asUint8List().sublist(4);

    if (type == MediaType.AlbumCover) {
      print("MediaType.AlbumCover");
      // const imageData = data.subarray(4)
      // payload = {
      //   type,
      //   base64Image: imageData.toString('base64'),
      // }
    } else if (type == MediaType.Data) {
      print("MediaType.Data");
      print(utf8.decode(extraData));
      // const mediaData = data.subarray(4, data.length - 1)
      // payload = {
      //   type,
      //   media: JSON.parse(mediaData.toString('utf8')),
      // }
    } else {
      print("Unexpected media type: $type");
    }
  }

  @override
  String toString() => "MediaData: type=${type.name}";
}

class Opened extends Message {
  late final int width;
  late final int height;
  late final int fps;
  late final int format;
  late final int packetMax;
  late final int iBox;
  late final int phoneMode;

  Opened(super.header, ByteData data) {
    final reader = BufferReader(data);

    width = reader.getUInt32();
    height = reader.getUInt32();
    fps = reader.getUInt32();
    format = reader.getUInt32();
    packetMax = reader.getUInt32();
    iBox = reader.getUInt32();
    phoneMode = reader.getUInt32();
  }

  @override
  String toString() =>
      "Opened: width=$width, height=$height, fps=$fps, format=$format, packetMax=$packetMax, iBox=$iBox, phoneMode=$phoneMode";
}

class BoxInfo extends Message {
  late final Map settings;
  // :
  //   | {
  //       HiCar: number
  //       OemName: string
  //       WiFiChannel: number
  //       boxType: string
  //       hwVersion: string
  //       productType: string
  //       uuid: string
  //     }
  //   | {
  //       MDLinkType: string
  //       MDModel: string
  //       MDOSVersion: string
  //       MDLinkVersion: string
  //       cpuTemp: number
  //     }

  BoxInfo(super.header, ByteData data) {
    try {
      final str = ascii.decode(data.buffer.asUint8List(), allowInvalid: true);
      settings = jsonDecode(str);
    } catch (e) {
      print(e);
    }
  }

  @override
  String toString() =>
      "BoxInfo: settings=${const JsonEncoder.withIndent('  ').convert(settings)}";
}

class Phase extends Message {
  late final int phase;

  Phase(super.header, ByteData data) {
    phase = BufferReader(data).getUInt32();
  }

  @override
  String toString() => "Phase: phase=${phase}";
}

//////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////

abstract class SendableMessage {
  final MessageType type;

  SendableMessage(this.type);

  Uint8List serialise() => MessageHeader.asBuffer(type, 0).buffer.asUint8List();
}

abstract class SendableMessageWithPayload extends SendableMessage {
  SendableMessageWithPayload(super.type);

  ByteData getPayload();

  @override
  Uint8List serialise() {
    final data = getPayload();
    final header = MessageHeader.asBuffer(type, data.lengthInBytes);

    return Uint8List.fromList(
        header.buffer.asUint8List() + data.buffer.asUint8List());
  }
}

class SendCommand extends SendableMessageWithPayload {
  final CommandMapping value;

  SendCommand(this.value) : super(MessageType.Command);

  @override
  ByteData getPayload() {
    return ByteData(4)..setUint32(0, value.id, Endian.little);
  }
}

enum TouchAction {
  Down(14),
  Move(15),
  Up(16),

  Unknown(-1);

  final int id;
  const TouchAction(this.id);

  factory TouchAction.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class SendTouch extends SendableMessageWithPayload {
  late final TouchAction action;
  late final int x;
  late final int y;

  SendTouch(this.action, this.x, this.y) : super(MessageType.Touch);

  @override
  ByteData getPayload() {
    return ByteData(16)
      ..setUint32(0, action.id, Endian.little)
      ..setUint32(4, x, Endian.little)
      ..setUint32(8, y, Endian.little);
  }
}

enum MultiTouchAction {
  Down(1),
  Move(2),
  Up(0),

  Unknown(-1);

  final int id;
  const MultiTouchAction(this.id);

  factory MultiTouchAction.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class TouchItem {
  late final int x;
  late final int y;
  late final MultiTouchAction action;
  late final int id;

  TouchItem(this.x, this.y, this.action, this.id);
}

class SendMultiTouch extends SendableMessageWithPayload {
  final List<TouchItem> touches;

  SendMultiTouch(this.touches) : super(MessageType.MultiTouch);

  @override
  ByteData getPayload() {
    final writer = BufferWriter.withCapacity(touches.length * 16);
    for (var touch in touches) {
      writer.setUInt32(touch.x);
      writer.setUInt32(touch.y);
      writer.setUInt32(touch.action.id);
      writer.setUInt32(touch.id);
    }
    return writer.buffer;
  }
}

class SendAudio extends SendableMessageWithPayload {
  final Uint16List data;

  SendAudio(this.data) : super(MessageType.AudioData);

  @override
  ByteData getPayload() {
    final audioData = ByteData(12)
      ..setUint32(0, 5, Endian.little)
      ..setFloat32(4, 0.0, Endian.little)
      ..setUint32(8, 3, Endian.little);

    return Uint8List.fromList(
      [
        ...audioData.buffer.asUint8List(),
        ...data.buffer.asUint8List(),
      ],
    ).buffer.asByteData();
  }
}

class SendFile extends SendableMessageWithPayload {
  final ByteData content;
  final String fileName;

  SendFile(this.content, this.fileName) : super(MessageType.SendFile);

  ByteData _getFileName(String name) =>
      Uint8List.fromList([...ascii.encode(name), 0]).buffer.asByteData();

  ByteData _getLength(ByteData data) =>
      ByteData(4)..setUint32(0, data.lengthInBytes, Endian.little);

  @override
  ByteData getPayload() {
    final newFileName = _getFileName(fileName);
    final nameLength = _getLength(newFileName);
    final contentLength = _getLength(content);

    return Uint8List.fromList(
      [
        ...nameLength.buffer.asUint8List(),
        ...newFileName.buffer.asUint8List(),
        ...contentLength.buffer.asUint8List(),
        ...content.buffer.asUint8List(),
      ],
    ).buffer.asByteData();
  }
}

class SendNumber extends SendFile {
  SendNumber(int number, FileAddress file)
      : super(ByteData(4)..setUint32(0, number, Endian.little), file.path);
}

class SendBoolean extends SendNumber {
  SendBoolean(bool value, FileAddress file) : super(value ? 1 : 0, file);
}

class SendString extends SendFile {
  SendString(String string, FileAddress file)
      : super(ascii.encode(string).buffer.asByteData(), file.path);
}

class HeartBeat extends SendableMessage {
  HeartBeat() : super(MessageType.HeartBeat);
}

enum HandDriveType {
  LHD(0),
  RHD(1),

  Unknown(-1);

  final int id;
  const HandDriveType(this.id);

  factory HandDriveType.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class DongleConfig {
  final bool? androidWorkMode;
  final int width;
  final int height;
  final int fps;
  final int dpi;
  final int format;
  final int iBoxVersion;
  final int packetMax;
  final int phoneWorkMode;
  final bool nightMode;
  final String boxName;
  final HandDriveType hand;
  final int mediaDelay;
  final bool audioTransferMode;
  final String wifiType; // '2.4ghz' | '5ghz'
  final String micType; // 'box' | 'os'
  final dynamic phoneConfig;

  const DongleConfig({
    this.androidWorkMode,
    required this.width,
    required this.height,
    required this.fps,
    required this.dpi,
    required this.format,
    required this.iBoxVersion,
    required this.packetMax,
    required this.phoneWorkMode,
    required this.nightMode,
    required this.boxName,
    required this.hand,
    required this.mediaDelay,
    required this.audioTransferMode,
    required this.wifiType,
    required this.micType,
    required this.phoneConfig,
  });
}

const DEFAULT_CONFIG = DongleConfig(
  androidWorkMode: false,
  width: 1024,
  height: 600,
  fps: 60,
  dpi: 160,
  format: 5,
  iBoxVersion: 2,
  phoneWorkMode: 2,
  packetMax: 49152,
  boxName: 'nodePlay',
  nightMode: false,
  hand: HandDriveType.LHD,
  mediaDelay: 300,
  audioTransferMode: true,
  wifiType: '5ghz',
  micType: 'box',
  phoneConfig: {
    PhoneType.CarPlay: {
      'frameInterval': 5000,
    },
    PhoneType.AndroidAuto: {
      'frameInterval': null,
    },
  },
);

const knownDevices = [
  {0x1314: 0x1520},
  {0x1314: 0x1521},
];

class SendOpen extends SendableMessageWithPayload {
  final DongleConfig config;

  SendOpen(this.config) : super(MessageType.Open);

  @override
  ByteData getPayload() => ByteData(28)
    ..setUint32(0, config.width, Endian.little)
    ..setUint32(4, config.height, Endian.little)
    ..setUint32(8, config.fps, Endian.little)
    ..setUint32(12, config.format, Endian.little)
    ..setUint32(16, config.packetMax, Endian.little)
    ..setUint32(20, config.iBoxVersion, Endian.little)
    ..setUint32(24, config.phoneWorkMode, Endian.little);
}

class SendBoxSettings extends SendableMessageWithPayload {
  final int? syncTime;
  final DongleConfig config;

  SendBoxSettings(this.config, this.syncTime) : super(MessageType.BoxSettings);

  @override
  ByteData getPayload() {
    final json = {
      "mediaDelay": config.mediaDelay,
      "syncTime": syncTime ?? DateTime.now().millisecondsSinceEpoch ~/ 1000,
      "androidAutoSizeW": config.width,
      "androidAutoSizeH": config.height,
    };

    final jsonString = jsonEncode(json);

    print(jsonString);

    final data = ascii.encode(jsonString);

    return data.buffer.asByteData();
  }
}
