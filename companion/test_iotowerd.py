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


# `usbip list -r <host>` with the G29 (046d:c24f) exported as busid 1-1.
LIST_ONE = """\
Exportable USB devices
======================
 - 192.168.1.50
      1-1: Logitech, Inc. : unknown product (046d:c24f)
         : /sys/devices/platform/usb/1-1
         : (Defined at Interface level) (00/00/00)
"""

LIST_EMPTY = """\
Exportable USB devices
======================
 - 192.168.1.50
"""

# `usbip port` with one imported port attached to our TV.
PORT_ONE = """\
Imported USB devices
====================
Port 03: <Port in Use> at High Speed(480Mbps)
       Logitech, Inc. : unknown product (046d:c24f)
       6-1 -> usbip://192.168.1.50:3240/1-1
           -> remote bus/dev 001/005
"""

# `usbip port` with a port attached to a DIFFERENT remote (must be excluded).
PORT_OTHER_HOST = """\
Imported USB devices
====================
Port 00: <Port in Use> at Full Speed(12Mbps)
       unknown vendor : unknown product (1234:5678)
       2-1 -> usbip://10.0.0.9:3240/1-2
           -> remote bus/dev 001/003
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


class AttachedPortTests(unittest.TestCase):
    def test_single_port_on_our_host(self):
        rows = iotowerd.parse_attached_ports(PORT_ONE, host="192.168.1.50")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].port, 3)
        self.assertEqual(rows[0].host, "192.168.1.50")
        self.assertEqual(rows[0].busid, "1-1")

    def test_host_filter_excludes_other_remote(self):
        # Teardown safety: never detach a port attached to another server.
        self.assertEqual(
            iotowerd.parse_attached_ports(PORT_OTHER_HOST, host="192.168.1.50"), [])
        # Without a filter it is still parsed (so we can see it).
        rows = iotowerd.parse_attached_ports(PORT_OTHER_HOST)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].port, 0)
        self.assertEqual(rows[0].host, "10.0.0.9")

    def test_empty_table(self):
        self.assertEqual(iotowerd.parse_attached_ports(PORT_EMPTY, host="x"), [])


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
