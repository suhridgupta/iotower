#!/usr/bin/env python3
"""Unit tests for the I/O Tower companion's pure parsers + output path.

Standard library only (``unittest``); no TV, no root, no network. Run:

    python3 -m unittest discover -s companion
    # or
    python3 companion/test_iotowerd.py

Fixtures are captured from real stock ``usbip`` output (Fedora usbip 2.x).
"""
import io
import os
import sys
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(__file__))
import iotowerd  # noqa: E402


# `usbip list -r <host>` with a device exported as busid 1-1 (discovery).
LIST_ONE = """\
Exportable USB devices
======================
 - 192.168.1.107
      1-1: Logitech, Inc. : F310 Gamepad [XInput Mode] (046d:c21d)
         : /sys/devices/platform/usb/1-1
         : (Defined at Interface level) (00/00/00)
"""

LIST_EMPTY = """\
Exportable USB devices
======================
 - 192.168.1.107
"""

# `usbip port` run as ROOT: the usbip:// record line is present.
PORT_ONE_ROOT = """\
Imported USB devices
====================
Port 00: <Port in Use> at Full Speed(12Mbps)
       Logitech, Inc. : F310 Gamepad [XInput Mode] (046d:c21d)
       5-1 -> usbip://192.168.1.107:3240/1-1
           -> remote bus/dev 001/001
"""

# `usbip port` run WITHOUT root: libusbip can't read the record, so the usbip://
# line is replaced by "unknown host ...". The Port header is still present --
# this is exactly why detection must key on the header, not the record.
PORT_ONE_NOSUDO = """\
Imported USB devices
====================
libusbip: error: fopen
libusbip: error: read_record
Port 00: <Port in Use> at Full Speed(12Mbps)
       Logitech, Inc. : F310 Gamepad [XInput Mode] (046d:c21d)
       5-1 -> unknown host, remote port and remote busid
           -> remote bus/dev 001/001
"""

# Two ports in use (e.g. a pre-existing attachment plus ours).
PORT_TWO = """\
Imported USB devices
====================
Port 00: <Port in Use> at Full Speed(12Mbps)
       some device
Port 03: <Port in Use> at High Speed(480Mbps)
       another device
"""

PORT_EMPTY = """\
Imported USB devices
====================
"""


class ExportedBusidTests(unittest.TestCase):
    def test_one_device(self):
        self.assertEqual(iotowerd.parse_exported_busids(LIST_ONE), ["1-1"])

    def test_empty(self):
        self.assertEqual(iotowerd.parse_exported_busids(LIST_EMPTY), [])

    def test_is_exported(self):
        self.assertTrue(iotowerd.device_is_exported(LIST_ONE, "1-1"))
        self.assertFalse(iotowerd.device_is_exported(LIST_ONE, "1-2"))
        self.assertFalse(iotowerd.device_is_exported(LIST_EMPTY, "1-1"))


class PortsInUseTests(unittest.TestCase):
    def test_root_output(self):
        self.assertEqual(iotowerd.parse_ports_in_use(PORT_ONE_ROOT), {0})

    def test_nonroot_unknown_host_still_detected(self):
        # The whole point: detection works even when the record is unreadable.
        self.assertEqual(iotowerd.parse_ports_in_use(PORT_ONE_NOSUDO), {0})

    def test_multiple_ports(self):
        self.assertEqual(iotowerd.parse_ports_in_use(PORT_TWO), {0, 3})

    def test_empty(self):
        self.assertEqual(iotowerd.parse_ports_in_use(PORT_EMPTY), set())

    def test_delta_identifies_our_attach(self):
        # The supervisor's detection model: new in-use port after attach is ours.
        before = iotowerd.parse_ports_in_use(PORT_EMPTY)
        after = iotowerd.parse_ports_in_use(PORT_ONE_ROOT)
        self.assertEqual(sorted(after - before), [0])


class UiColorTests(unittest.TestCase):
    def test_no_color_emits_no_ansi(self):
        # Piped/`--no-color`/systemd journals must stay free of escape codes.
        ui = iotowerd.Ui(color=False)
        buf = io.StringIO()
        with redirect_stdout(buf):
            ui.state("ATTACHED", "device ready")
            ui.ok("done")
        out = buf.getvalue()
        self.assertNotIn("\033", out)
        self.assertIn("ATTACHED", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
