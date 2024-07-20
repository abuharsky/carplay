final logEnabled = false;

log(String s) {
  // ignore: avoid_print
  final String l = "${DateTime.now().toString().substring(11, 22)} > $s";
  if (logEnabled) print(l);
  if (logListener != null) logListener!(l);
}

Function(String)? logListener;
