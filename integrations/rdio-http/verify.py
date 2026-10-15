#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Clean-apply and verify the pinned operator extension; never deploys a server."""
from pathlib import Path
import re
import subprocess
import tempfile


def run(*args: str, cwd: Path) -> None:
    subprocess.run(args, cwd=cwd, check=True)


def main() -> None:
    package = Path(__file__).resolve().parent
    revision = (package / "UPSTREAM_SHA").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Expected a pinned 40-character upstream revision")
    with tempfile.TemporaryDirectory(prefix="sweep-rdio-verify-") as temporary:
        checkout = Path(temporary)
        run("git", "init", "--quiet", cwd=checkout)
        run("git", "remote", "add", "origin", "https://github.com/chuot/rdio-scanner.git", cwd=checkout)
        run("git", "fetch", "--quiet", "--depth=1", "origin", revision, cwd=checkout)
        run("git", "checkout", "--quiet", "--detach", "FETCH_HEAD", cwd=checkout)
        patch = str(package / "rdio-http-v1.patch")
        run("git", "apply", "--check", patch, cwd=checkout)
        run("git", "apply", patch, cwd=checkout)
        server = checkout / "server"
        # Compilation-only embed fixture. No binary or placeholder webapp is shipped.
        fixture = server / "webapp" / "index.html"
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.write_text("<!doctype html><title>SWEEP test-only embed fixture</title>")
        run("go", "test", "./...", cwd=server)
        run("go", "build", "-o", str(checkout / "verification-only-binary"), ".", cwd=server)
        print(f"Verified clean patch, Go tests and server compilation at {revision}; no deployment.")


if __name__ == "__main__":
    main()
