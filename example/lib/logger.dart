import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart';

class Logger {
  static const ENABLED = true;
  static final StringBuffer _logBuffer = StringBuffer();

  static start() async {
    if (!ENABLED) return;

    log("\n\n");
    log("---");
    log("STARTING SESSION");
    log("---");

    Timer.periodic(const Duration(seconds: 5), (timer) {
      _saveToFile();
    });
  }

  static log(String value) async {
    if (ENABLED) {
      final string =
          "${DateTime.now().toString().substring(11, 23)} > [LOGGER] $value";

      debugPrint(string);
      _logBuffer.writeln(string);
    }
  }

  static _saveToFile() async {
    if (_logBuffer.isEmpty) return;

    final message = _logBuffer.toString();
    _logBuffer.clear();

    _appendLogToFile(message);
  }

  static Future<File> _appendLogToFile(String string) async {
    var status = await Permission.storage.status;
    if (!status.isGranted) {
      await Permission.storage.request();
    }
    // the downloads folder path

    var path = "/storage/sdcard0/Download";

    if (!(await Directory(path).exists())) {
      path = "/storage/emulated/0/Download";
    }

    if (!(await Directory(path).exists())) {
      path = "/storage/emulated/0/Downloads";
    }

    return await compute(
      (string) {
        return File("$path/carplay.log")
            .writeAsString(string, mode: FileMode.writeOnlyAppend);
      },
      string,
    );
    // save the data in the path
  }
}
