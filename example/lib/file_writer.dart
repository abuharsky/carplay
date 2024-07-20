import 'dart:io';
import 'dart:typed_data';

import 'package:permission_handler/permission_handler.dart';

class FileWriter {
  static Future<File?> writeFileToDownloadsDir(Uint8List data, String name,
      {FileMode mode = FileMode.write}) async {
    // storage permission ask
    var status = await Permission.storage.status;
    if (!status.isGranted) {
      await Permission.storage.request();
    }
    // the downloads folder path

    var directory = "/storage/emulated/0/Download/";

    var dirDownloadExists = await Directory(directory).exists();
    if (dirDownloadExists) {
      directory = "/storage/emulated/0/Download/";
    } else {
      directory = "/storage/emulated/0/Downloads/";
    }

    Directory tempDir = Directory(directory);

    String tempPath = tempDir.path;
    var filePath = '$tempPath/$name';
    //

    // the data
    var bytes = ByteData.view(data.buffer);
    final buffer = bytes.buffer;
    // save the data in the path
    return File(filePath).writeAsBytes(
        buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
        flush: true,
        mode: mode);
  }
}
