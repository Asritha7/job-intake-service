"""Offline regression checks for the opt-in performance client (no real job service)."""
import contextlib
import io
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from scripts import performance_baseline as baseline


class FakeIntake(BaseHTTPRequestHandler):
    jobs = {}
    guard = threading.Lock()

    def log_message(self, *_args):
        return

    def do_GET(self):
        if self.path != "/ready":
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ready")

    def do_POST(self):
        if self.path != "/jobs":
            self.send_error(404)
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        key = self.headers.get("Idempotency-Key")
        with self.guard:
            if key in self.jobs:
                status = 200 if self.jobs[key] == body else 409
            else:
                self.jobs[key] = body
                status = 201
        self.send_response(status)
        self.send_header("Location", "/jobs/fake-" + key)
        self.end_headers()
        self.wfile.write(b'{"status":"accepted"}')


class PerformanceClientTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        FakeIntake.jobs = {}
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeIntake)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = "http://127.0.0.1:" + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def test_rejects_remote_or_ambiguous_targets(self):
        for target in ("https://127.0.0.1:8080", "http://example.com:8080",
                       "http://0.0.0.0:8080", "http://127.0.0.1:8080/jobs",
                       "http://127.0.0.1:8080?x=1", "http://user@localhost:8080"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                baseline.validate_url(target)
        self.assertEqual(baseline.validate_url(self.url), self.url)

    def test_percentile_definition(self):
        self.assertIsNone(baseline.percentile([], 0.95))
        self.assertEqual(baseline.percentile([4, 1, 2, 3], 0.95), 4)
        self.assertEqual(baseline.percentile([4, 1, 2, 3], 0.5), 2)

    def test_dry_run_does_not_submit(self):
        before = len(FakeIntake.jobs)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(baseline.main(["--url", self.url]), 0)
        self.assertIn("DRY RUN", output.getvalue())
        self.assertEqual(before, len(FakeIntake.jobs))

    def test_new_acceptance_and_replays_with_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            with contextlib.redirect_stdout(io.StringIO()):
                result = baseline.main(["--url", self.url, "--allow-writes", "--new-jobs", "8",
                                        "--concurrency", "3", "--replay-percent", "50",
                                        "--output", str(path)])
            self.assertEqual(result, 0)
            report = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(report["new"]["status_counts"], {"201": 8})
            self.assertEqual(report["replay"]["status_counts"], {"200": 4})
            self.assertEqual(report["new"]["unexpected_results"], 0)
            self.assertEqual(report["replay"]["unexpected_results"], 0)
            self.assertEqual(report["new"]["requests"], 8)
            self.assertEqual(report["replay"]["requests"], 4)
            self.assertEqual(report["store_mode"], "not_verified_by_client")
            self.assertNotIn("benchmark-payload", path.read_text())
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(baseline.main(["--url", self.url, "--allow-writes",
                                                "--new-jobs", "1", "--output", str(path)]), 2)

    def test_replay_detects_changed_location(self):
        with FakeIntake.guard:
            FakeIntake.jobs["known"] = b"payload"
        summary, rows = baseline.phase(self.url, [("known", "payload")], 1, 3, 200,
                                       {"known": "/jobs/another-id"})
        self.assertEqual(rows[0]["status"], 200)
        self.assertEqual(summary["unexpected_results"], 1)


if __name__ == "__main__":
    unittest.main()
