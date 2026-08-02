#!/usr/bin/env python3
"""Capture README screenshots from a running local simulation instance.

Capture targets live in targets.json so new pages and sections can be added
without touching this script. Use --discover to list pages and sections the
running app exposes that no target covers yet.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

try:
    from playwright.sync_api import Page, sync_playwright
except ImportError:
    sys.exit(
        "Playwright is required. Install it with:\n"
        "  python3 -m venv /tmp/kraken-screenshots\n"
        "  /tmp/kraken-screenshots/bin/pip install playwright\n"
    )

SCRIPT_DIR = Path(__file__).resolve().parent
# scripts/ → skill/ → skills/ → .agents/ → project root
PROJECT_ROOT = SCRIPT_DIR.parents[4]
IMAGE_DIR = PROJECT_ROOT / "docs" / "images"
MANIFEST = SCRIPT_DIR / "targets.json"
CHART_SETTLE_MS = 1_000


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


def prepare(page: Page, base_url: str, target: dict) -> None:
    page.goto(f"{base_url}{target['path']}", wait_until="domcontentloaded")

    if button := target.get("click_button"):
        page.get_by_role("button", name=button, exact=True).click()

    for text in target.get("await_text", []):
        page.get_by_text(text, exact=True).first.wait_for()

    if chart_count := target.get("await_charts"):
        page.wait_for_function(
            "expected => [...document.querySelectorAll('canvas')]"
            ".filter(c => c.width && c.height).length >= expected",
            arg=chart_count,
        )
        page.wait_for_timeout(CHART_SETTLE_MS)

    if anchor := target.get("anchor"):
        page.get_by_role("heading", name=anchor, exact=True).evaluate(
            "element => element.scrollIntoView({block: 'start'})"
        )
    elif target.get("position") == "bottom":
        page.evaluate("window.scrollTo(0, document.documentElement.scrollHeight)")
    else:
        page.evaluate("window.scrollTo(0, 0)")

    # Keep a trailing element (e.g. net cash flow caption) in frame after the
    # primary scroll without jumping past the section the target is documenting.
    if ensure := target.get("ensure_visible"):
        page.get_by_text(ensure, exact=False).first.evaluate(
            "element => element.scrollIntoView({block: 'nearest'})"
        )


def discover(page: Page, base_url: str, targets: list[dict]) -> None:
    covered_paths = {target["path"] for target in targets}
    covered_sections = {
        text
        for target in targets
        for text in [target.get("anchor"), *target.get("await_text", [])]
        if text
    }

    found_paths: set[str] = set()
    found_sections: dict[str, list[str]] = {}

    for path in sorted(covered_paths):
        page.goto(f"{base_url}{path}", wait_until="domcontentloaded")
        page.wait_for_timeout(CHART_SETTLE_MS)
        found_paths.update(
            page.eval_on_selector_all(
                "a[href^='/']",
                "links => links.map(a => new URL(a.href).pathname)",
            )
        )
        found_sections[path] = page.eval_on_selector_all(
            "h1, h2, h3",
            "nodes => nodes.map(n => n.innerText.trim()).filter(Boolean)",
        )

    uncovered_paths = sorted(found_paths - covered_paths)
    print("Pages with no capture target:", ", ".join(uncovered_paths) or "none")

    for path, sections in found_sections.items():
        missing = [
            section
            for section in sections
            if section.title() not in {text.title() for text in covered_sections}
        ]
        print(f"{path} sections not referenced by any target: {', '.join(missing) or 'none'}")

    print(
        "\nAdd new entries to targets.json for anything above that belongs in the README, "
        "then rerun without --discover."
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--chrome", type=Path, default=None, help="Chrome/Chromium executable")
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=None,
        help="Write PNGs here instead of docs/images/ (for UI review / verify runs)",
    )
    parser.add_argument("--only", help="Comma-separated subset of target filenames")
    parser.add_argument(
        "--discover",
        action="store_true",
        help="Report pages/sections lacking capture targets instead of capturing",
    )
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text())
    viewport = manifest["viewport"]
    targets = manifest["targets"]

    if args.only:
        wanted = {name.strip() for name in args.only.split(",")}
        targets = [target for target in targets if target["file"] in wanted]
        if not targets:
            sys.exit(f"No targets matched --only {args.only}")

    chrome = find_chrome(args.chrome)
    out_dir = (args.out_dir or IMAGE_DIR).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=str(chrome),
            headless=True,
            args=["--hide-scrollbars"],
        )
        context = browser.new_context(
            viewport={"width": viewport["width"], "height": viewport["height"]},
            device_scale_factor=viewport["deviceScaleFactor"],
            color_scheme="dark",
        )
        page = context.new_page()

        if args.discover:
            discover(page, args.base_url, manifest["targets"])
        else:
            for target in targets:
                # Optional per-target viewport (e.g. taller frame so a chart
                # header + trailing caption both fit). Device scale stays global.
                target_vp = {**viewport, **(target.get("viewport") or {})}
                page.set_viewport_size(
                    {"width": target_vp["width"], "height": target_vp["height"]}
                )
                # device_scale_factor is fixed on the context; recreate if needed
                prepare(page, args.base_url, target)
                output = out_dir / target["file"]
                page.screenshot(path=output)
                try:
                    shown = output.relative_to(PROJECT_ROOT)
                except ValueError:
                    shown = output
                print(f"Captured {shown}")

        browser.close()


if __name__ == "__main__":
    main()
