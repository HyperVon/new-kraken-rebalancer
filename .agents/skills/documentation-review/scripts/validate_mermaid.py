#!/usr/bin/env python3
"""Parse every ```mermaid block under Mermaid 8.8.0 (IDE preview baseline).

GitHub may render newer Mermaid fine while Cursor/VS Code preview still ships
8.x — unquoted non-ASCII labels and the sequenceDiagram `actor` keyword fail
there with "Syntax error in graph".

Usage (from repo root):

  python3 -m venv /tmp/kraken-screenshots
  /tmp/kraken-screenshots/bin/pip install playwright
  /tmp/kraken-screenshots/bin/python \\
    .agents/skills/documentation-review/scripts/validate_mermaid.py

  # Optional: also write PNG renders for visual review
  .../validate_mermaid.py --render /tmp/mermaid-renders

  # Limit to specific files
  .../validate_mermaid.py docs/ALGORITHM.md docs/FLOWS.md
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
import urllib.request
from pathlib import Path

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sys.exit(
        "Playwright is required. Install it with:\n"
        "  python3 -m venv /tmp/kraken-screenshots\n"
        "  /tmp/kraken-screenshots/bin/pip install playwright\n"
    )

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[3]
MERMAID_VERSION = "8.8.0"
MERMAID_URL = f"https://unpkg.com/mermaid@{MERMAID_VERSION}/dist/mermaid.min.js"
MERMAID_CACHE = Path(f"/tmp/mermaid-{MERMAID_VERSION}.js")
MIN_MERMAID_BYTES = 100_000
DEFAULT_FILES = (
    PROJECT_ROOT / "README.md",
    PROJECT_ROOT / "docs" / "ALGORITHM.md",
    PROJECT_ROOT / "docs" / "FLOWS.md",
)
BLOCK = re.compile(r"^```mermaid\n(.*?)^```", re.MULTILINE | re.DOTALL)


def find_chrome(explicit: Path | None) -> Path:
    candidates = [
        explicit,
        Path(os.environ["CHROME_PATH"]) if os.environ.get("CHROME_PATH") else None,
        Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
        *(
            Path(path)
            for name in ("google-chrome", "chromium", "chromium-browser")
            if (path := shutil.which(name))
        ),
    ]
    for candidate in candidates:
        if candidate and candidate.is_file():
            return candidate
    sys.exit("Chrome/Chromium not found. Pass --chrome PATH or set CHROME_PATH.")


def is_valid_mermaid_artifact(path: Path) -> bool:
    try:
        return path.is_file() and path.stat().st_size > MIN_MERMAID_BYTES
    except OSError:
        return False


def ensure_mermaid() -> Path:
    if is_valid_mermaid_artifact(MERMAID_CACHE):
        return MERMAID_CACHE
    print(f"Downloading Mermaid {MERMAID_VERSION} → {MERMAID_CACHE}")
    try:
        # urllib uses the platform/Python trust store here; certificate failures must remain fatal.
        with urllib.request.urlopen(MERMAID_URL, timeout=15) as response:
            artifact = response.read()
        if len(artifact) <= MIN_MERMAID_BYTES:
            raise ValueError(
                f"Downloaded Mermaid artifact is suspiciously short ({len(artifact)} bytes; "
                f"expected more than {MIN_MERMAID_BYTES})."
            )
        MERMAID_CACHE.write_bytes(artifact)
    except Exception as err:
        if is_valid_mermaid_artifact(MERMAID_CACHE):
            print(f"Warning: Failed to download Mermaid ({err}), using existing cached file {MERMAID_CACHE}")
            return MERMAID_CACHE
        sys.exit(
            f"Error: Unable to download Mermaid {MERMAID_VERSION} from {MERMAID_URL} ({err}).\n"
            f"If working offline, place a valid mermaid.min.js manually at {MERMAID_CACHE}, "
            "or configure Python's trusted CA store."
        )
    return MERMAID_CACHE


def collect_blocks(paths: list[Path]) -> list[tuple[Path, int, int, str]]:
    """Return (file, line, block_index, diagram) for each mermaid fence."""
    found: list[tuple[Path, int, int, str]] = []
    for path in paths:
        text = path.read_text()
        for index, match in enumerate(BLOCK.finditer(text), start=1):
            line = text[: match.start()].count("\n") + 1
            found.append((path, line, index, match.group(1)))
    return found


def main() -> None:
    parser = argparse.ArgumentParser(
        description=f"Validate Mermaid diagrams against {MERMAID_VERSION} (IDE baseline)."
    )
    parser.add_argument(
        "files",
        nargs="*",
        type=Path,
        help="Markdown files to scan (default: README + docs/ALGORITHM + docs/FLOWS)",
    )
    parser.add_argument("--chrome", type=Path, default=None)
    parser.add_argument(
        "--render",
        type=Path,
        default=None,
        help="Directory to write PNG renders of each block (optional visual check)",
    )
    args = parser.parse_args()

    paths = [p.resolve() for p in args.files] if args.files else list(DEFAULT_FILES)
    missing = [str(p) for p in paths if not p.is_file()]
    if missing:
        sys.exit(f"File(s) not found: {', '.join(missing)}")

    blocks = collect_blocks(paths)
    if not blocks:
        print("No ```mermaid blocks found.")
        return

    mermaid_js = ensure_mermaid().read_text()
    chrome = find_chrome(args.chrome)
    if args.render:
        args.render.mkdir(parents=True, exist_ok=True)

    harness = Path("/tmp/mermaid-validate-harness.html")
    harness.write_text(
        f"""<!doctype html><html><body style="background:#fff;margin:0;padding:16px">
<div id="host"></div>
<script>{mermaid_js}</script>
<script>
mermaid.initialize({{startOnLoad:false}});
window.check = (diagram) => {{
  try {{ mermaid.parse(diagram); return null; }}
  catch (e) {{ return e && e.str ? e.str : String(e); }}
}};
window.draw = (diagram, id) => {{
  return new Promise((resolve, reject) => {{
    try {{
      mermaid.render(id, diagram, (svg) => {{
        const host = document.getElementById('host');
        host.innerHTML = svg;
        resolve(host.querySelectorAll('.error-text, text.error-text').length);
      }});
    }} catch (e) {{ reject(e); }}
  }});
}};
</script></body></html>"""
    )

    failures = 0
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=str(chrome),
            headless=True,
        )
        page = browser.new_page(
            viewport={"width": 1400, "height": 1200},
            device_scale_factor=2,
        )
        page.goto(harness.as_uri())

        for path, line, index, diagram in blocks:
            try:
                rel = path.relative_to(PROJECT_ROOT)
            except ValueError:
                rel = path
            error = page.evaluate("d => window.check(d)", diagram)
            if error:
                failures += 1
                first = error.splitlines()[0][:300]
                print(f"FAIL {rel}:{line} (block {index}) — {first}")
                continue

            if args.render:
                render_id = f"d{index}_{path.stem}"
                error_nodes = page.evaluate(
                    "([d, id]) => window.draw(d, id)",
                    [diagram, render_id],
                )
                page.wait_for_selector("#host svg")
                out = args.render / f"{path.stem}-block{index}.png"
                page.locator("#host").screenshot(path=str(out))
                if error_nodes:
                    failures += 1
                    print(
                        f"FAIL {rel}:{line} (block {index}) — "
                        f"render has {error_nodes} error node(s)"
                    )
                    continue
                print(f"OK   {rel}:{line} (block {index}) → {out}")
            else:
                print(f"OK   {rel}:{line} (block {index})")

        browser.close()

    print(f"\n{failures} failing block(s) under Mermaid {MERMAID_VERSION}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
