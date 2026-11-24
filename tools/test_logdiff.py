"""Tests of logdiff.py on two sample logs (SOS-247): python tools\test_logdiff.py"""
import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import logdiff  # noqa: E402

HOST = """21:00:00.000 system: Windows 10 10.0 (amd64), Java 21.0.11 (Eclipse Adoptium), heap max 4096 MB, 16 cores
21:00:01.000 = 600 command: 7012 army move, slot 1
21:00:01.500 ~ 600 world: stance: Ann (1) and Bo (2), neutral to war
21:00:01.600 ~ d12 world: news: The Ann declares war on Bo
21:00:02.000 = 630 command: 7013 army stop, slot 1
21:00:02.100 = 630 army: 12 of Bo (2) recruits
21:00:03.000 this PC's engine found a desync at step 660:
             world differs: armies
             armies 3 1a2b
             armies 4 9f00
21:00:04.000 = 690 command: 7014 x, slot 0
"""

JOIN = """21:00:00.200 system: Linux 6.1 (amd64), Java 21.0.11 (Eclipse Adoptium), heap max 4096 MB, 8 cores
21:00:00.900 a line of this PC's own
21:00:01.100 = 600 command: 7012 army move, slot 1
21:00:01.700 ~ 600 world: stance: Ann (1) and Bo (2), neutral to war
21:00:01.800 ~ d12 world: news: The Ann declares war on Bo
21:00:02.300 = 630 army: 12 of Bo (2) recruits
21:00:02.400 = 630 command: 7013 army stop, slot 1
21:00:03.100 this PC's engine found a desync at step 660:
             world differs: armies
             armies 3 1a2b
             armies 4 9e00
"""


class LogdiffTest(unittest.TestCase):

    def run_on(self, a, b, *flags):
        with tempfile.TemporaryDirectory() as d:
            pa, pb = os.path.join(d, "a.log"), os.path.join(d, "b.log")
            for p, text in ((pa, a), (pb, b)):
                with open(p, "w", encoding="utf-8") as f:
                    f.write(text)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                status = logdiff.main([pa, pb] + list(flags))
            return status, out.getvalue()

    def test_same_lines_in_either_order_and_a_longer_log_are_no_difference(self):
        status, out = self.run_on(HOST.replace("9f00", "9e00"), JOIN, "--all")
        self.assertEqual(0, status, out)
        self.assertIn("are the same (6 in A, 5 in B", out)  # step 690 is past the joining PC's log

    def test_the_first_differing_step_with_its_lines(self):
        status, out = self.run_on(HOST, JOIN.replace("army stop", "army halt"))
        self.assertEqual(1, status)
        self.assertIn("the first difference is at step 630", out)
        self.assertIn(">     5  21:00:02.000 = 630 command: 7013 army stop, slot 1", out)

    def test_unconfirmed_lines_only_with_all(self):
        join = JOIN.replace("neutral to war", "neutral to trade")
        self.assertEqual(0, self.run_on(HOST.replace("9f00", "9e00"), join)[0])
        status, out = self.run_on(HOST, join, "--all")
        self.assertEqual(1, status)
        self.assertIn("at step 600", out)

    def test_the_desync_reports_item_by_item(self):
        status, out = self.run_on(HOST, JOIN)
        self.assertEqual(1, status)
        self.assertIn("desync reports at step 660: 3 items both have, 1 differ", out)
        self.assertIn("armies 4: A 9f00, B 9e00", out)


if __name__ == "__main__":
    unittest.main()
