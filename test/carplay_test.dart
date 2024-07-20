// import 'dart:convert';
// import 'dart:io';
// import 'dart:typed_data';

// import 'package:carplay/list_split_by_chunk.dart';
// import 'package:dart_buffer/dart_buffer.dart';
// import 'package:flutter_test/flutter_test.dart';
// import 'package:carplay/carplay.dart';
// import 'package:carplay/carplay_platform_interface.dart';
// import 'package:carplay/carplay_method_channel.dart';
// import 'package:plugin_platform_interface/plugin_platform_interface.dart';

// class MockCarplayPlatform
//     with MockPlatformInterfaceMixin
//     implements CarplayPlatform {
//   @override
//   Future<int> createTexture(int width, int height) {
//     // TODO: implement createTexture
//     throw UnimplementedError();
//   }

//   @override
//   Future<void> processData(Uint8List data) {
//     // TODO: implement processData
//     throw UnimplementedError();
//   }

//   @override
//   Future<void> removeTexture() {
//     // TODO: implement removeTexture
//     throw UnimplementedError();
//   }
// }

// void main() {
//   final CarplayPlatform initialPlatform = CarplayPlatform.instance;

//   test('$MethodChannelCarplay is the default instance', () {
//     expect(initialPlatform, isInstanceOf<MethodChannelCarplay>());
//   });

//   test('list_chunks', () async {
//     final chunk = [0, 0, 0, 1];

//     var list = [];
//     var result = [];

//     list = [5, 5, 5, 5, ...chunk, 0, 0, ...chunk, 0, 1];
//     result = list.split(chunk);

//     expect([
//       [5, 5, 5, 5],
//       [0, 0],
//       [0, 1]
//     ], result);

//     list = [5, 5, 5, 5, ...chunk, 0, 0, ...chunk, 0, 1];
//     result = list.split(chunk, excludeChunk: false);

//     expect([
//       [5, 5, 5, 5],
//       [...chunk, 0, 0],
//       [...chunk, 0, 1]
//     ], result);

//     list = [...chunk, 5, 5, 5, 5, ...chunk, 0, 0, ...chunk, 0, 1, ...chunk];
//     result = list.split(chunk);

//     expect([
//       [5, 5, 5, 5],
//       [0, 0],
//       [0, 1]
//     ], result);
//   });

//   test('mp4', () {
//     final file = File("/Users/alexander/Desktop/video2.mp4");
//     final data = file.readAsBytesSync();

//     final reader = BufferReader(ByteData.view(data.buffer));
//     readBoxes(reader);
//   });
// }

// readBoxes(BufferReader reader) {
//   while (reader.offset < reader.length) {
//     final box = Box.fromBufferReader(reader)!;
//     print("${reader.offset} > ${box.toString()}");
//     // if (['moov', 'trak', 'mdia'].contains(box.name)) {
//     //   readBoxes(reader);
//     // }
//   }
// }

// class Box {
//   final int size;
//   final String name;

//   Box(this.size, this.name);

//   static Box? fromBufferReader(BufferReader reader) {
//     if (reader.remainingLength() < 8) {
//       return null;
//     }

//     final size = reader.getUInt32(Endian.big);
//     final name = reader.getUtf8String(4);

//     if (reader.remainingLength() < size - 8) {
//       return null;
//     }

//     final data = reader.getSlice(size - 8);
//     final dataReader = BufferReader(data);

//     switch (name) {
//       case "ftyp":
//         return FileTypeBox(size, name, dataReader);

//       case "moov":
//         return MovieBox(size, name, dataReader);

//       case "mvhd":
//         return MovieHeaderBox(size, name, dataReader);

//       case "trak":
//         return TrackBox(size, name, dataReader);

//       case "tkhd":
//         return TrackHeaderBox(size, name, dataReader);

//       case "moof":
//         return MediaFragmentBox(size, name, dataReader);

//       case "mdat":
//         return MediaDataBox(size, name, dataReader);

//       default:
//         return Box(size, name);
//     }
//   }

//   @override
//   String toString() => "Box: name=$name, size=$size";
// }

// extension ReadUtf8String on BufferReader {
//   getUtf8String(int length) {
//     final str = utf8.decode(buffer.buffer.asInt8List(offset, length));
//     offset += length;
//     return str;
//   }

//   remainingLength() {
//     return length - offset;
//   }
// }

// class FileTypeBox extends Box {
//   late final String majorBrand;
//   late final int minorVersion;
//   late final List<String> compBrands;

//   FileTypeBox(super.size, super.name, BufferReader reader) {
//     majorBrand = reader.getUtf8String(4);
//     minorVersion = reader.getUInt32(Endian.big);

//     compBrands = [];
//     for (var i = 0; i < (size - 8 - 8) / 4; i++) {
//       compBrands.add(reader.getUtf8String(4));
//     }
//   }

//   @override
//   String toString() =>
//       "FileTypeBox: name=$name, size=$size, \nmajorBrand=$majorBrand, \nminorVersion=$minorVersion, \ncompBrands=${compBrands.join(", ")}";
// }

// class MovieBox extends Box {
//   late final MovieHeaderBox movieHeaderBox;
//   final List<TrackBox> tracks = [];

//   MovieBox(super.size, super.name, BufferReader reader) {
//     Box? box;
//     while ((box = Box.fromBufferReader(reader)) != null) {
//       if (box is MovieHeaderBox) {
//         movieHeaderBox = box;
//       } else if (box is TrackBox) {
//         tracks.add(box);
//       } else {
//         print(box.toString());
//       }
//     }
//   }

//   @override
//   String toString() =>
//       "MovieBox: name=$name, size=$size, \n -- ${movieHeaderBox.toString()}\n -- Tracks:${tracks.map((e) => "\n    -- ${e.toString()}")}";
// }

// class MovieHeaderBox extends Box {
//   late final int version; //          uint8
//   late final int flags; //            uint32
//   late final int creationTime; //     uint32
//   late final int modificationTime; // uint32
//   late final int timescale; //        uint32
//   late final int duration; //         uint32
//   late final int nextTrackId; //         uint32
//   late final double rate; //             Fixed32
//   late final double volume; //           Fixed16

//   MovieHeaderBox(super.size, super.name, BufferReader reader) {
//     version = reader.getUInt8();
//     flags = reader.getUInt(3, Endian.big);
//     creationTime = reader.getUInt32(Endian.big);
//     modificationTime = reader.getUInt32(Endian.big);
//     timescale = reader.getUInt32(Endian.big);
//     duration = reader.getUInt32(Endian.big);
//     rate = reader.getFloat32(Endian.big);
//     volume = (reader.getUInt16(Endian.big)).toDouble();
//   }

//   @override
//   String toString() =>
//       "MovieHeaderBox: name=$name, size=$size,\nversion=$version, \nflags=$flags, \ncreationTime=$creationTime, \nmodificationTime=$modificationTime, \ntimescale=$timescale, \nduration=$duration, \nrate=$rate, \nvolume=$volume";
// }

// class TrackHeaderBox extends Box {
//   late final int version; //          byte
//   late final int flags; //            [3]byte
//   late final int creationTime; //     uint32
//   late final int modificationTime; // uint32
//   late final int trackId; //          uint32
//   late final int duration; //         uint32
//   late final int layer; //            uint16
//   late final int alternateGroup; //   uint16 // should be int16
//   late final double volume; //           Fixed16
//   late final Uint8List matrix; //           []byte
//   late final double width; //
//   late final double height; //    Fixed32

//   TrackHeaderBox(super.size, super.name, BufferReader reader) {
//     version = reader.getUInt8();
//     flags = reader.getUInt(3, Endian.big);
//     creationTime = reader.getUInt32(Endian.big);
//     modificationTime = reader.getUInt32(Endian.big);
//     trackId = reader.getUInt32(Endian.big);
//     duration = reader.getUInt32(Endian.big);
//     layer = reader.getUInt16(Endian.big);
//     alternateGroup = reader.getUInt32(Endian.big);
//     volume = (reader.getUInt16(Endian.big)).toDouble();
//     matrix = reader.getSlice(36).buffer.asUint8List();
//     width = reader.getFloat32(Endian.big);
//     height = reader.getFloat32(Endian.big);
//   }

//   @override
//   String toString() =>
//       "TrackHeaderBox: name=$name, size=$size,\nversion=$version, \nflags=$flags, \ncreationTime=$creationTime, \nmodificationTime=$modificationTime, \ntrackId=$trackId, \nduration=$duration, \nlayer=$layer, \nvolume=$volume, \nalternateGroup=$alternateGroup, \nwidth=$width, \nheight=$height, \nmatrix=${matrix.toString()}";
// }

// class TrackBox extends Box {
//   late final TrackHeaderBox trackHeaderBox;

//   TrackBox(super.size, super.name, BufferReader reader) {
//     Box? box;
//     while ((box = Box.fromBufferReader(reader)) != null) {
//       if (box is TrackHeaderBox) {
//         trackHeaderBox = box;
//       } else {
//         print(box.toString());
//       }
//     }
//   }

//   @override
//   String toString() =>
//       "TrackBox: name=$name, size=$size, \n -- ${trackHeaderBox.toString()}";
// }

// class MediaDataBox extends Box {
//   MediaDataBox(super.size, super.name, BufferReader reader) {}
// }

// class MediaFragmentBox extends Box {
//   MediaFragmentBox(super.size, super.name, BufferReader reader) {
//     Box? box;
//     while ((box = Box.fromBufferReader(reader)) != null) {
//       print(box.toString());
//     }
//   }
// }

// class MovieFragmentHeader extends Box {
//   MovieFragmentHeader(super.size, super.name, BufferReader reader) {}
// }

// class TrackFragment extends Box {
//   late final int version; //               byte
//   late final int flags; //                uint32
//   late final int trackId; //              uint32
//   late final int lengthSizeOfTrafNum; //   byte
//   late final int lengthSizeOfTrunNum; //  byte
//   late final int lengthSizeOfSampleNum; //byte
//   late final List<TrackFragmentEntry> entries; //             []TfraEntry

//   TrackFragment(super.size, super.name, BufferReader reader) {
//     version = reader.getUInt8();
//     flags = reader.getUInt(3, Endian.big);
//     trackId = reader.getUInt32(Endian.big);
//     lengthSizeOfTrafNum = reader.getUInt8();
//     lengthSizeOfTrunNum = reader.getUInt8();
//     lengthSizeOfSampleNum = reader.getUInt8();
//   }
// }

// class TrackFragmentEntry {
//   late final int time; //         uint64
//   late final int moofOffset; //   uint64
//   late final int trafNumber; //   uint32
//   late final int trunNumber; //   uint32
//   late final int sampleNumber; // uint32

//   TrackFragmentEntry(BufferReader reader) {
//     time = reader.getUInt64(Endian.big);
//     moofOffset = reader.getUInt64(Endian.big);
//     trafNumber = reader.getUInt32(Endian.big);
//     trunNumber = reader.getUInt32(Endian.big);
//     sampleNumber = reader.getUInt32(Endian.big);
//   }
// }
