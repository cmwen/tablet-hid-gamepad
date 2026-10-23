import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tablet_hid_gamepad/main.dart';
import 'package:tablet_hid_gamepad/gamepad.dart';

void main() {
  test(
    'neutral, simultaneous buttons, signed axes and independent triggers',
    () {
      final pad = GamepadState();
      expect(pad.report(), [0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
      pad.button(0, true);
      pad.button(10, true);
      pad.dpad(3);
      pad.stick(true, -1, 1);
      pad.stick(false, .5, -.5);
      pad.trigger(false, true);
      final report = pad.report();
      final values = ByteData.sublistView(report);
      expect(values.getUint16(0, Endian.little), 1025);
      expect(report[2], 3);
      expect(values.getInt16(3, Endian.little), -32767);
      expect(values.getInt16(5, Endian.little), 32767);
      expect(report[11], 0);
      expect(report[12], 255);
      pad.reset();
      expect(pad.report(), [0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    },
  );
  test('D-pad combines diagonals and releases individual fingers', () {
    final pad = GamepadState();
    pad.direction(0, true);
    pad.direction(2, true);
    expect(pad.report()[2], 1);
    pad.direction(0, false);
    expect(pad.report()[2], 2);
    pad.direction(2, false);
    expect(pad.report()[2], 8);
  });
  testWidgets(
    'landscape controller supports concurrent holds and pointer cancel',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final reports = <List<int>>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('tablet_hid_gamepad/hid'),
        (call) async {
          if (call.method == 'status') {
            return {'connected': true, 'registered': true, 'mode': 'usb'};
          }
          if (call.method == 'report') {
            reports.add(List<int>.from(call.arguments['body']));
          }
          return true;
        },
      );
      await tester.pumpWidget(const GamepadApp());
      await tester.pump();
      expect(find.text('TABLET / GAMEPAD'), findsOneWidget);
      final a = await tester.startGesture(
        tester.getCenter(find.text('A')),
        pointer: 1,
      );
      final b = await tester.startGesture(
        tester.getCenter(find.text('B')),
        pointer: 2,
      );
      await tester.pump();
      expect(reports.last[0] & 3, 3);
      await a.cancel();
      await tester.pump();
      expect(reports.last[0] & 3, 2);
      await b.up();
      await tester.pump();
      expect(reports.last[0], 0);
      final stick = await tester.startGesture(
        tester.getTopLeft(find.byType(StickControl).first) +
            const Offset(115, 70),
      );
      await tester.pump();
      expect(reports.last.sublist(3, 5), isNot([0, 0]));
      await stick.cancel();
      await tester.pump();
      expect(reports.last.sublist(3, 11), everyElement(0));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
