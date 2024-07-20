import 'package:android_automotive_plugin/android_automotive_plugin.dart';
import 'package:carplay/carplay.dart';
import 'package:carplay/driver/sendable.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'file_writer.dart';
import 'logger.dart';
import 'settings_page.dart';

class MainPage extends StatefulWidget {
  const MainPage({super.key});

  @override
  State<StatefulWidget> createState() => _MainPageState();
}

class _MainPageState extends State<MainPage> {
  Carplay? _carplay;
  int? _textureId;

  final DongleConfig _dongleConfig = DEFAULT_CONFIG;

  bool loading = true;

  final AndroidAutomotivePlugin _automotivePlugin = AndroidAutomotivePlugin();

  final List<TouchItem> _multitouch = [];

  @override
  void initState() {
    super.initState();

    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);

    Future.delayed(const Duration(seconds: 3), () {
      _start();
    });
  }

  void _openSettings(BuildContext context) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => SettingsPage(),
      ),
    );
  }

  bool _initialized = false;
  void _start() {
    if (_initialized) return;

    //
    final displaySize = MediaQuery.of(context).size;
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;

    if (displaySize.width == 0 || displaySize.height == 0) {
      return;
    }

    _dongleConfig.width = (displaySize.width * pixelRatio).toInt();
    _dongleConfig.height = (displaySize.height * pixelRatio).toInt();

    _dongleConfig.dpi = 160;
    _dongleConfig.fps = 25;

    Logger.log(
        "DISPLAY SIZE w:${displaySize.width} h:${displaySize.height}, pixelRatio:$pixelRatio");

    _startCarplay(_dongleConfig);

    _initialized = true;
  }

  _startCarplay(DongleConfig config) async {
    _carplay = Carplay(
      config: config,
      onTextureChanged: (textureId) async {
        Logger.log("TEXTURE CREATED: $textureId");
        setState(() {
          _textureId = textureId;
        });
      },
      onStateChanged: (carplayState) {
        Logger.log("CARPLAY STATE: ${carplayState.name}");

        setState(() {
          loading = carplayState != CarplayState.streaming;
        });
      },
      onMediaInfoChanged: (mediaInfo) {
        try {
          Logger.log(
              "onMediaInfoChanged: songTitle: ${mediaInfo.songTitle}, songArtist: ${mediaInfo.songArtist}, albumName: ${mediaInfo.albumName}, appName: ${mediaInfo.appName}, albumCoverImageData length: ${mediaInfo.albumCoverImageData?.length}");
          _setInfoAndCover(
            mediaInfo.songTitle,
            mediaInfo.songArtist,
            mediaInfo.appName,
            mediaInfo.albumName,
            mediaInfo.albumCoverImageData,
          );
        } catch (e) {
          Logger.log(e.toString());
        }
      },
      onLogMessage: (log) {
        Logger.log(log);
      },
      onHostUIPressed: () {
        Logger.log("onHostUIPressed");
        _openSettings(context);
      },
    );

    _carplay?.start();
  }

  _processMultitouchEvent(
      MultiTouchAction action, int id, Offset offset) async {
    final touch = TouchItem(
      offset.dx / _dongleConfig.width,
      offset.dy / _dongleConfig.height,
      action,
      id,
    );

    final index = _multitouch.indexWhere((e) => e.id == id);
    if (action == MultiTouchAction.Down) {
      _multitouch.add(touch);
    } else if (index != -1) {
      if (action == MultiTouchAction.Up) {
        _multitouch[index] = touch;
      } else if (action == MultiTouchAction.Move) {
        final existed = _multitouch[index];
        final dx = (existed.x * 1000 - touch.x * 1000).abs();
        final dy = (existed.y * 1000 - touch.y * 1000).abs();

        if ((dx > 3 || dy > 3)) {
          _multitouch[index] = touch;
        } else {
          return;
        }
      }
    } else {
      return;
    }

    // Logger.log(
    //     "${_multitouch.map((e) => "${e.id}(${_multitouch.indexOf(e)})|${e.action.name}|${e.x}|${e.y}")}");

    _carplay?.sendMultiTouch(_multitouch
        .map((e) => TouchItem(e.x, e.y, e.action, _multitouch.indexOf(e)))
        .toList());

    _multitouch.removeWhere((e) => e.action == MultiTouchAction.Up);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Center(
        child: Stack(
          children: [
            FittedBox(
              child: SizedBox(
                width: _dongleConfig.width.toDouble(),
                height: _dongleConfig.height.toDouble(),
                child: Listener(
                  onPointerDown: (p) async => await _processMultitouchEvent(
                    MultiTouchAction.Down,
                    p.pointer,
                    p.localPosition,
                  ),
                  onPointerMove: (p) async => await _processMultitouchEvent(
                    MultiTouchAction.Move,
                    p.pointer,
                    p.localPosition,
                  ),
                  onPointerUp: (p) async => await _processMultitouchEvent(
                    MultiTouchAction.Up,
                    p.pointer,
                    p.localPosition,
                  ),
                  onPointerCancel: (p) async => await _processMultitouchEvent(
                    MultiTouchAction.Up,
                    p.pointer,
                    p.localPosition,
                  ),
                  //
                  child: _textureId != null
                      ? Texture(
                          textureId: _textureId!,
                        )
                      : Container(),
                ),
              ),
            ),
            if (loading)
              Positioned.fill(
                child: Container(
                  color: Colors.black.withOpacity(0.7),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Image.asset(
                          "assets/carplay.png",
                          height: 220,
                        ),
                        const SizedBox(height: 24),
                        const CupertinoActivityIndicator(
                          color: Colors.white,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            if (loading)
              Positioned(
                top: 24,
                right: 24,
                child: IconButton(
                  icon: const Icon(Icons.settings),
                  onPressed: () => _openSettings(context),
                ),
              )
            // Positioned(
            //     right: 20,
            //     bottom: 20,
            //     width: 500,
            //     height: 150,
            //     child: SingleChildScrollView(
            //       child: Text(
            //         log,
            //         style: TextStyle(color: Colors.white60, fontSize: 18),
            //       ),
            //     ))
          ],
        ),
      ),
    );
  }

  Future<void> _setInfoAndCover(
    String? mediaSongName,
    String? mediaArtistName,
    String? mediaAppName,
    String? mediaAlbumName,
    Uint8List? coverData,
  ) async {
    String? path;
    if (coverData != null) {
      final file =
          await FileWriter.writeFileToDownloadsDir(coverData, "cover.jpg");
      path = file?.absolute.path.replaceAll('//', '/');
    }

    try {
      if (path != null) {
        await _automotivePlugin
            .setVehicleSettingMusicAlbumPictureFilePath(path);
      }

      await _automotivePlugin.setDoubleMediaMusicSource(
        playingId: 1,
        programName: mediaAlbumName ?? mediaAppName ?? " ",
        singerName: mediaArtistName ?? " ",
        songName: mediaSongName ?? mediaAppName ?? " ",
        sourceType: 25,
      );

      if (path != null) {
        await _automotivePlugin.setDoubleMediaMusicAlbumPictureFilePath(
          doublePlayingId: 1,
          songId: "test-song",
          path: path,
        );
      }
    } catch (e) {
      // ignore
    }
  }
}
