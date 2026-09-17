"""Smoke tests for top-level entry points.

P8.7-fix added these after G-RC-8 audit caught that main.py and mcp_server.py
still imported from the deleted core.agent shim (a regression that escaped
P-RC-7 because these modules were not in the gate selector).

These tests assert that the CLI and MCP server modules can be imported
without raising, guarding against future shim-deletion drift.
"""

import importlib
import shutil
import subprocess
import sys


def test_newsclaw_main_imports():
    """src/newsclaw/main.py must import without ImportError after shim deletion."""
    importlib.import_module("newsclaw.main")


def test_newsclaw_mcp_server_imports():
    """src/newsclaw/mcp_server.py must import without ImportError after shim deletion."""
    importlib.import_module("newsclaw.mcp_server")


def test_newsclaw_cli_help_smoke():
    """`newsclaw --help` must exit 0 (catches missing console script entry).

    Prefers ``python -m newsclaw`` (uses ``src/newsclaw/__main__.py``) so we
    do not depend on the installed console script being on PATH. Falls back
    to the installed ``newsclaw`` executable via ``shutil.which`` for
    environments where ``__main__.py`` is missing.
    """
    cmd = [sys.executable, "-m", "newsclaw", "--help"]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=60,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError:
        exe = shutil.which("newsclaw")
        assert exe is not None, (
            "neither `python -m newsclaw` nor `newsclaw` console script available"
        )
        result = subprocess.run(
            [exe, "--help"],
            capture_output=True,
            text=True,
            timeout=60,
            encoding="utf-8",
            errors="replace",
        )

    assert result.returncode == 0, (
        f"newsclaw --help exited {result.returncode}\n"
        f"stdout: {result.stdout[:500]}\n"
        f"stderr: {result.stderr[:500]}"
    )
    assert "newsclaw" in (result.stdout + result.stderr).lower()
