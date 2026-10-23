import 'package:flutter/foundation.dart';

/// Shared input-only HID report. Stick axes center at 32768; hat 8 means released.
class GamepadState extends ChangeNotifier {
  final Set<int> buttons = {};
  int hat = 8;
  final Set<int> directions = {};
  double lx = 0, ly = 0, rx = 0, ry = 0;
  void button(int index, bool down) {
    if (down) {
      buttons.add(index);
    } else {
      buttons.remove(index);
    }
    notifyListeners();
  }

  void stick(bool left, double x, double y) {
    if (left) {
      lx = x;
      ly = y;
    } else {
      rx = x;
      ry = y;
    }
    notifyListeners();
  }

  void direction(int value, bool down) {
    if (down) {
      directions.add(value);
    } else {
      directions.remove(value);
    }
    final x =
        (directions.contains(2) ? 1 : 0) - (directions.contains(6) ? 1 : 0);
    final y =
        (directions.contains(4) ? 1 : 0) - (directions.contains(0) ? 1 : 0);
    hat = switch ((x, y)) {
      (0, -1) => 0,
      (1, -1) => 1,
      (1, 0) => 2,
      (1, 1) => 3,
      (0, 1) => 4,
      (-1, 1) => 5,
      (-1, 0) => 6,
      (-1, -1) => 7,
      _ => 8,
    };
    notifyListeners();
  }

  void dpad(int value) {
    hat = value;
    notifyListeners();
  }

  void trigger(bool left, bool down) {
    button(left ? 11 : 12, down);
  }

  void reset() {
    buttons.clear();
    directions.clear();
    hat = 8;
    lx = ly = rx = ry = 0;
    notifyListeners();
  }

  Uint8List report() {
    final data = ByteData(11);
    int mask = 0;
    for (final button in buttons) {
      mask |= 1 << button;
    }
    data.setUint16(0, mask, Endian.little);
    data.setUint8(2, hat);
    final axes = [lx, ly, rx, ry];
    for (int i = 0; i < 4; i++) {
      data.setUint16(
        3 + i * 2,
        ((axes[i].clamp(-1, 1) + 1) * 32767.5).round(),
        Endian.little,
      );
    }
    return data.buffer.asUint8List();
  }
}
