import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'gamepad.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const GamepadApp());
}

const mint = Color(0xff79efd1);

class GamepadApp extends StatelessWidget {
  const GamepadApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Tablet HID Gamepad',
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xff101923),
      colorSchemeSeed: mint,
      useMaterial3: true,
    ),
    home: const ControllerScreen(),
  );
}

class ControllerScreen extends StatefulWidget {
  const ControllerScreen({super.key});
  @override
  State<ControllerScreen> createState() => _ControllerScreenState();
}

class _ControllerScreenState extends State<ControllerScreen>
    with WidgetsBindingObserver {
  static const channel = MethodChannel('tablet_hid_gamepad/hid');
  final pad = GamepadState();
  Map<String, dynamic> status = {};
  Timer? timer;
  bool sending = false, dirty = false, connected = false;
  String? localError;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    pad.addListener(input);
    timer = Timer.periodic(const Duration(milliseconds: 250), (_) => refresh());
    refresh();
  }

  Future<void> refresh() async {
    try {
      final value = await channel.invokeMapMethod<String, dynamic>('status');
      if (!mounted) return;
      final nextConnected = value?['connected'] == true;
      if (connected != nextConnected) pad.reset();
      setState(() {
        status = value ?? {};
        connected = nextConnected;
      });
    } on MissingPluginException {
      /* Widget preview has no native transport. */
    } on PlatformException catch (e) {
      if (mounted) setState(() => localError = e.message);
    }
  }

  void input() {
    dirty = true;
    send();
  }

  Future<void> send() async {
    if (sending) return;
    sending = true;
    try {
      while (dirty && mounted) {
        dirty = false;
        await channel.invokeMethod<bool>('report', {'body': pad.report()});
      }
    } on MissingPluginException {
      dirty = false;
    } on PlatformException catch (e) {
      dirty = false;
      if (mounted) setState(() => localError = e.message);
    } finally {
      sending = false;
    }
  }

  Future<void> command(String name, [Map<String, dynamic>? args]) async {
    pad.reset();
    try {
      await channel.invokeMethod<void>(name, args);
      if (mounted) setState(() => localError = null);
      await refresh();
    } on PlatformException catch (e) {
      if (mounted) setState(() => localError = e.message);
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) pad.reset();
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    pad.dispose();
    super.dispose();
  }

  Future<void> setup() async {
    pad.reset();
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: SizedBox(
          height: MediaQuery.sizeOf(context).height * .88,
          child: StatefulBuilder(
            builder: (context, update) => SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Connect your controller',
                    style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Windows: open joy.cpl to check inputs, then enable Steam Input for generic controllers. This is standard HID, without rumble or native XInput.',
                  ),
                  const Divider(height: 28),
                  const Text(
                    'BLUETOOTH • Android 9+',
                    style: TextStyle(color: mint),
                  ),
                  const Text(
                    'Windows shows your tablet’s current Android Bluetooth name. Start Bluetooth before pairing. Wait for HID ready, make the tablet visible, then pair from Windows. If already paired without a gamepad, remove the pairing on both devices and pair again while HID is ready. Select the paired computer below to connect.',
                  ),
                  Wrap(
                    spacing: 12,
                    children: [
                      FilledButton(
                        onPressed: () async {
                          await command('startBluetooth');
                          update(() {});
                        },
                        child: const Text('Start Bluetooth'),
                      ),
                      OutlinedButton(
                        onPressed: () => command('discoverable'),
                        child: const Text('Make visible (120s)'),
                      ),
                    ],
                  ),
                  for (final host in (status['hosts'] as List? ?? []))
                    ListTile(
                      leading: const Icon(Icons.computer),
                      title: Text('${host['name']}'),
                      trailing: const Icon(Icons.link),
                      onTap: () =>
                          command('connect', {'address': host['address']}),
                    ),
                  const Divider(height: 28),
                  const Text(
                    'USB • Root required',
                    style: TextStyle(color: mint),
                  ),
                  const Text(
                    'Experimental FunctionFS mode. Requires Magisk root, a supported configfs gadget and ADB already enabled. USB reconnects briefly. Stop other tablet HID apps first. Keep a recovery path available. Original USB configuration is saved and restored; no persistent kernel or boot changes.',
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 12,
                    children: [
                      FilledButton.tonal(
                        onPressed: () async {
                          final allowed = await showDialog<bool>(
                            context: context,
                            builder: (context) => AlertDialog(
                              title: const Text('Enable rooted USB?'),
                              content: const Text(
                                'USB will reconnect with gamepad + ADB. Grant root only if you accept temporary USB changes. Unsupported or conflicting setups are refused. Use Restore USB if interrupted.',
                              ),
                              actions: [
                                TextButton(
                                  onPressed: () =>
                                      Navigator.pop(context, false),
                                  child: const Text('Cancel'),
                                ),
                                FilledButton(
                                  onPressed: () => Navigator.pop(context, true),
                                  child: const Text('Enable USB'),
                                ),
                              ],
                            ),
                          );
                          if (allowed == true) {
                            await command('startUsb');
                            update(() {});
                          }
                        },
                        child: const Text('Enable rooted USB'),
                      ),
                      OutlinedButton(
                        onPressed: () => command('restoreUsb'),
                        child: const Text('Restore USB'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  Text('${status['message'] ?? 'Disconnected'}'),
                  const SizedBox(height: 16),
                  OutlinedButton(
                    onPressed: () {
                      Navigator.pop(context);
                    },
                    child: const Text('Back to gamepad'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 10, 12, 4),
            child: Row(
              children: [
                const Icon(Icons.sports_esports, color: mint),
                const SizedBox(width: 10),
                const Text(
                  'TABLET / GAMEPAD',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    letterSpacing: 2,
                  ),
                ),
                const Spacer(),
                Icon(
                  Icons.circle,
                  size: 9,
                  color: connected ? mint : Colors.orangeAccent,
                ),
                const SizedBox(width: 6),
                Text(
                  connected
                      ? '${status['mode']} connected'
                      : (status['registered'] == true
                            ? 'Ready · pair a host'
                            : 'Offline · try the controls'),
                ),
                const SizedBox(width: 12),
                FilledButton.tonal(
                  onPressed: setup,
                  child: const Text('Connect'),
                ),
                IconButton(
                  tooltip: 'Release all / stop',
                  onPressed: () => command('stop'),
                  icon: const Icon(Icons.stop_circle_outlined),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    localError ??
                        status['error']?.toString() ??
                        status['message']?.toString() ??
                        'USB + Bluetooth HID controller',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: localError != null || status['error'] != null
                          ? Colors.orangeAccent
                          : Colors.white54,
                      fontSize: 11,
                    ),
                  ),
                ),
                Text(
                  'Reports ${status['sent'] ?? 0}',
                  style: const TextStyle(color: Colors.white54, fontSize: 11),
                ),
              ],
            ),
          ),
          Expanded(
            child: LayoutBuilder(
              builder: (context, box) {
                final unit = math.min(box.maxHeight / 350, box.maxWidth / 760);
                return Center(
                  child: SizedBox(
                    width: 760 * unit,
                    height: 350 * unit,
                    child: FittedBox(
                      child: SizedBox(
                        width: 760,
                        height: 350,
                        child: Stack(
                          children: [
                            positioned(28, 12, hold('LB', 4, width: 120)),
                            positioned(610, 12, hold('RB', 5, width: 120)),
                            positioned(
                              170,
                              12,
                              HoldControl(
                                label: 'LT',
                                width: 80,
                                onChanged: (v) => pad.trigger(true, v),
                              ),
                            ),
                            positioned(
                              510,
                              12,
                              HoldControl(
                                label: 'RT',
                                width: 80,
                                onChanged: (v) => pad.trigger(false, v),
                              ),
                            ),
                            positioned(
                              38,
                              78,
                              StickControl(
                                label: 'LEFT STICK',
                                onChanged: (x, y) => pad.stick(true, x, y),
                              ),
                            ),
                            positioned(
                              420,
                              174,
                              StickControl(
                                label: 'RIGHT STICK',
                                onChanged: (x, y) => pad.stick(false, x, y),
                              ),
                            ),
                            positioned(
                              222,
                              194,
                              SizedBox(
                                width: 132,
                                height: 132,
                                child: Stack(
                                  children: [
                                    positioned(44, 0, hat('↑', 0)),
                                    positioned(88, 44, hat('→', 2)),
                                    positioned(44, 88, hat('↓', 4)),
                                    positioned(0, 44, hat('←', 6)),
                                    const Positioned(
                                      left: 51,
                                      top: 52,
                                      child: Icon(
                                        Icons.add,
                                        color: Colors.white24,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                            positioned(
                              590,
                              76,
                              SizedBox(
                                width: 142,
                                height: 142,
                                child: Stack(
                                  children: [
                                    positioned(
                                      47,
                                      0,
                                      hold(
                                        'Y',
                                        3,
                                        color: const Color(0xffeacb77),
                                      ),
                                    ),
                                    positioned(
                                      94,
                                      47,
                                      hold(
                                        'B',
                                        1,
                                        color: const Color(0xfff1989c),
                                      ),
                                    ),
                                    positioned(
                                      47,
                                      94,
                                      hold('A', 0, color: mint),
                                    ),
                                    positioned(
                                      0,
                                      47,
                                      hold(
                                        'X',
                                        2,
                                        color: const Color(0xff87bafb),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                            positioned(285, 84, hold('SELECT', 6, width: 76)),
                            positioned(395, 84, hold('START', 7, width: 76)),
                            positioned(350, 140, hold('⌂', 8, width: 60)),
                            positioned(44, 288, hold('L3', 9, width: 120)),
                            positioned(610, 288, hold('R3', 10, width: 120)),
                            const Positioned(
                              left: 288,
                              top: 28,
                              child: Text(
                                'PLAY / ANYWHERE',
                                style: TextStyle(
                                  color: Colors.white24,
                                  letterSpacing: 3,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    ),
  );
  Widget positioned(double x, double y, Widget child) =>
      Positioned(left: x, top: y, child: child);
  Widget hold(
    String label,
    int index, {
    double width = 46,
    Color color = Colors.white70,
  }) => HoldControl(
    label: label,
    width: width,
    color: color,
    onChanged: (v) => pad.button(index, v),
  );
  Widget hat(String label, int value) => HoldControl(
    label: label,
    width: 44,
    onChanged: (v) => pad.direction(value, v),
  );
}

class HoldControl extends StatefulWidget {
  const HoldControl({
    super.key,
    required this.label,
    required this.onChanged,
    this.width = 46,
    this.color = Colors.white70,
  });
  final String label;
  final ValueChanged<bool> onChanged;
  final double width;
  final Color color;
  @override
  State<HoldControl> createState() => _HoldControlState();
}

class _HoldControlState extends State<HoldControl> {
  int? pointer;
  void release() {
    if (pointer != null) {
      pointer = null;
      widget.onChanged(false);
      if (mounted) setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) => Semantics(
    label: widget.label,
    button: true,
    child: Listener(
      onPointerDown: (e) {
        if (pointer != null) return;
        pointer = e.pointer;
        widget.onChanged(true);
        setState(() {});
      },
      onPointerUp: (e) {
        if (e.pointer == pointer) release();
      },
      onPointerCancel: (e) {
        if (e.pointer == pointer) release();
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 60),
        width: widget.width,
        height: 44,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: pointer != null
              ? widget.color.withValues(alpha: .28)
              : const Color(0xff24313e),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: widget.color.withValues(alpha: pointer != null ? .9 : .25),
          ),
        ),
        child: Text(
          widget.label,
          style: TextStyle(
            color: widget.color,
            fontWeight: FontWeight.bold,
            fontSize: widget.label.length > 2 ? 12 : 18,
          ),
        ),
      ),
    ),
  );
}

class StickControl extends StatefulWidget {
  const StickControl({super.key, required this.label, required this.onChanged});
  final String label;
  final void Function(double x, double y) onChanged;
  @override
  State<StickControl> createState() => _StickControlState();
}

class _StickControlState extends State<StickControl> {
  int? pointer;
  Offset offset = Offset.zero;
  void move(PointerEvent e) {
    if (e.pointer != pointer) return;
    var v = (e.localPosition - const Offset(70, 70)) / 48;
    if (v.distance > 1) v /= v.distance;
    if (v.distance < .08) v = Offset.zero;
    setState(() => offset = v);
    widget.onChanged(v.dx, v.dy);
  }

  void release(PointerEvent e) {
    if (e.pointer != pointer) return;
    pointer = null;
    setState(() => offset = Offset.zero);
    widget.onChanged(0, 0);
  }

  @override
  Widget build(BuildContext context) => Semantics(
    label: widget.label,
    child: Column(
      children: [
        Listener(
          onPointerDown: (e) {
            if (pointer == null) {
              pointer = e.pointer;
              move(e);
            }
          },
          onPointerMove: move,
          onPointerUp: release,
          onPointerCancel: release,
          child: Container(
            width: 140,
            height: 140,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: const Color(0xff172430),
              border: Border.all(color: Colors.white12, width: 2),
            ),
            child: Center(
              child: Transform.translate(
                offset: offset * 40,
                child: Container(
                  width: 68,
                  height: 68,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: const Color(0xff344653),
                    border: Border.all(
                      color: mint.withValues(alpha: .5),
                      width: 2,
                    ),
                    boxShadow: const [
                      BoxShadow(
                        color: Colors.black38,
                        blurRadius: 12,
                        offset: Offset(0, 6),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Text(
          widget.label,
          style: const TextStyle(
            color: Colors.white38,
            fontSize: 10,
            letterSpacing: 2,
          ),
        ),
      ],
    ),
  );
}
