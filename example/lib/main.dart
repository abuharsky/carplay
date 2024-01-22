import 'package:carplay/carplay.dart';
import 'package:carplay/common.dart';
import 'package:carplay/usb_device_wrapper.dart';
import 'package:flutter/material.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MainApp());
}

class MainApp extends StatefulWidget {
  const MainApp({super.key});

  @override
  State<StatefulWidget> createState() => _MainAppState();
}

class _MainAppState extends State<MainApp> {
  Carplay? _carplay;

  @override
  void initState() {
    super.initState();

    _startCarplay();
  }

  _startCarplay() async {
    await UsbManagerWrapper.init();

    _carplay = Carplay(DEFAULT_CONFIG, (message) {});
    _carplay?.start();
  }

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: Scaffold(
        body: Center(
          child: Text('Hello World!'),
        ),
      ),
    );
  }
}
