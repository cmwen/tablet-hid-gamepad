import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:tablet_hid_gamepad/gamepad.dart';

// Decode HID short items independently of the encoder. This catches inherited
// globals, accidental report size changes and signedness errors at the boundary.
void main() {
  test(
    'native descriptor and Dart neutral agree on ranges and report length',
    () {
      final source = File(
        'android/app/src/main/kotlin/com/cmwen/tablet_hid_gamepad/GamepadProtocol.kt',
      ).readAsStringSync().split('intArrayOf(')[1].split(').map')[0];
      final descriptor = RegExp(
        r'0x([0-9A-Fa-f]+)',
      ).allMatches(source).map((m) => int.parse(m[1]!, radix: 16)).toList();
      var size = 0, count = 0, page = 0, minimum = 0, maximum = 0;
      var physicalMinimum = 0, physicalMaximum = 0, unit = 0, bits = 0;
      final usages = <int>[];
      final axes = <int>[];
      final neutral = ByteData.sublistView(GamepadState().report());
      for (var i = 0; i < descriptor.length;) {
        final prefix = descriptor[i++];
        final length = [0, 1, 2, 4][prefix & 3];
        var value = 0;
        for (var n = 0; n < length; n++) {
          value |= descriptor[i++] << (8 * n);
        }
        final type = (prefix >> 2) & 3;
        final tag = prefix >> 4;
        if (type == 1) {
          switch (tag) {
            case 0:
              page = value;
            case 1:
              minimum = value;
            case 2:
              maximum = value;
            case 3:
              physicalMinimum = value;
            case 4:
              physicalMaximum = value;
            case 6:
              unit = value;
            case 7:
              size = value;
            case 9:
              count = value;
          }
        } else if (type == 2 && tag == 0) {
          usages.add(value);
        } else if (type == 0) {
          if (tag == 8) {
            if (page == 1 && size == 16) {
              expect(usages, [0x30, 0x31, 0x32, 0x35]);
              expect([minimum, maximum], [0, 65535]);
              expect([physicalMinimum, physicalMaximum, unit], [0, 65535, 0]);
              for (var n = 0; n < count; n++) {
                axes.add(neutral.getUint16(bits ~/ 8 + n * 2, Endian.little));
              }
            }
            // Every non-hat axis must be one of the four centered sticks.
            if (page == 1 && usages.any((u) => u >= 0x30 && u <= 0x38)) {
              expect(size, 16);
              expect(count, 4);
            }
            bits += size * count;
          }
          usages.clear();
        }
      }
      expect(bits, neutral.lengthInBytes * 8);
      expect(axes, [32768, 32768, 32768, 32768]);
    },
  );

  test(
    'stick center, full travel and out of range clamping remain symmetric',
    () {
      final pad = GamepadState();
      for (final pair in [
        (-2.0, 0),
        (-1.0, 0),
        (0.0, 32768),
        (1.0, 65535),
        (2.0, 65535),
      ]) {
        pad.stick(true, pair.$1, pair.$1);
        final report = ByteData.sublistView(pad.report());
        expect(report.getUint16(3, Endian.little), pair.$2);
        expect(report.getUint16(5, Endian.little), pair.$2);
      }
    },
  );
}
