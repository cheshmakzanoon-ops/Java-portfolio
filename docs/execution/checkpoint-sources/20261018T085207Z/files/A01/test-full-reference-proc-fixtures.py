#!/usr/bin/env python3
"""Pure proc-file fixtures; no real processes, Java, Docker or signals."""
import importlib.util
import pathlib
import tempfile
import unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("reference_proc_fixture", HERE / "run-full-reference.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class JavaProcessFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=HERE, prefix="proc-fixtures-")
        self.addCleanup(self.temp.cleanup)
        self.proc = pathlib.Path(self.temp.name)
        # Fail the suite if this pure regression starts any child.
        for name in ("Popen", "run"):
            patcher = mock.patch.object(module.subprocess, name, side_effect=AssertionError("pure fixture only"))
            patcher.start()
            self.addCleanup(patcher.stop)

    def entry(self, pid, command=b"/jdk/bin/java\0-Xmx512m\0", comm="java", state="S"):
        path = self.proc / str(pid)
        path.mkdir()
        (path / "cmdline").write_bytes(command)
        (path / "comm").write_text(comm + "\n")
        # State is field3; retain all fields through field22 (start time).
        (path / "stat").write_text(f"{pid} ({comm}) {state} " + " ".join(["0"] * 19) + "\n")
        return path

    def pids(self):
        return {row["pid"] for row in module.ReferenceStudy.java_processes(self.proc)}

    def test_live_java_by_argv_and_empty_argv_by_comm_are_retained(self):
        self.entry(101, comm="renamed-java", state="S")
        self.entry(102, command=b"", state="R")
        self.entry(103, command=b"/jdk/bin/javaw\0", comm="javaw", state="D")
        self.entry(104, command=b"/usr/bin/python3\0", comm="python3")
        self.assertEqual({101, 102, 103}, self.pids())

    def test_only_verified_zombies_are_ignored(self):
        self.entry(201, command=b"", state="Z")
        self.entry(202, state="Z")
        self.entry(203, state="X")
        self.entry(204, state="T")
        self.assertEqual({203, 204}, self.pids())

    def test_unknown_malformed_or_mismatched_stat_keeps_java(self):
        self.entry(301, command=b"").joinpath("stat").unlink()
        self.entry(302).joinpath("stat").write_text("302 (java) Z\n")
        self.entry(303).joinpath("stat").write_text("other (java) Z " + " ".join(["0"] * 19))
        self.entry(304).joinpath("stat").write_text("not proc stat")
        rows = module.ReferenceStudy.java_processes(self.proc)
        self.assertEqual({301, 302, 303, 304}, {row["pid"] for row in rows})
        self.assertEqual({"UNKNOWN"}, {row["state"] for row in rows})

    def test_vanished_entry_and_nonnumeric_proc_names_are_safe(self):
        live = self.entry(401)
        vanished = self.proc / "402"
        # Simulate a /proc directory entry disappearing after enumeration.
        with mock.patch.object(module.pathlib.Path, "iterdir", return_value=iter([vanished, live, self.proc / "self"])):
            self.assertEqual({401}, self.pids())


if __name__ == "__main__":
    unittest.main()
