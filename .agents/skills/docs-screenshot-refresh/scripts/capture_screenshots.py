#!/usr/bin/env python3
"""Capture screenshots from a running local simulation instance.

Capture targets live in targets.json so new pages and sections can be added
without touching this script. Use --discover to list pages and sections the
running app exposes that no target covers yet. Named viewport profiles make
the same target set reusable for desktop, laptop, tablet, and phone review.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from playwright.sync_api import Page

SCRIPT_DIR = Path(__file__).resolve().parent
# scripts/ → skill/ → skills/ → .agents/ → project root
PROJECT_ROOT = SCRIPT_DIR.parents[3]
IMAGE_DIR = PROJECT_ROOT / "docs" / "images"
MANIFEST = SCRIPT_DIR / "targets.json"
CHART_SETTLE_MS = 1_000
DEFAULT_PROFILE = "desktop"


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


def load_viewport_profiles(manifest: dict) -> tuple[str, dict[str, dict]]:
    """Load named profiles, retaining compatibility with the old single viewport."""
    profiles = manifest.get("viewportProfiles")
    if profiles is None:
        legacy_viewport = manifest.get("viewport")
        if legacy_viewport is None:
            sys.exit("Manifest must define viewportProfiles or viewport.")
        return "default", {"default": legacy_viewport}

    default_profile = manifest.get("defaultViewportProfile", DEFAULT_PROFILE)
    if default_profile not in profiles:
        sys.exit(f"Default viewport profile is not defined: {default_profile}")

    for name, viewport in profiles.items():
        for key in ("width", "height", "deviceScaleFactor"):
            if key not in viewport or viewport[key] <= 0:
                sys.exit(f"Viewport profile {name!r} must define a positive {key}.")
    return default_profile, profiles


def select_profiles(
    raw_profiles: list[str] | None,
    profiles: dict[str, dict],
    default: str,
) -> tuple[list[str], bool]:
    """Resolve repeated/comma-separated profile names and the special ``all`` name."""
    if not raw_profiles:
        return [default], False

    selected: list[str] = []
    for raw_value in raw_profiles:
        for name in raw_value.split(","):
            name = name.strip()
            names = list(profiles) if name == "all" else [name]
            for selected_name in names:
                if selected_name not in profiles:
                    available = ", ".join([*profiles, "all"])
                    sys.exit(f"Unknown viewport profile {selected_name!r}. Available: {available}")
                if selected_name not in selected:
                    selected.append(selected_name)
    return selected, True


def print_profiles(profiles: dict[str, dict]) -> None:
    print("Available viewport profiles:")
    for name, viewport in profiles.items():
        png_width = viewport["width"] * viewport["deviceScaleFactor"]
        png_height = viewport["height"] * viewport["deviceScaleFactor"]
        print(
            f"  {name}: {viewport['width']}x{viewport['height']} CSS px, "
            f"DPR {viewport['deviceScaleFactor']} -> {png_width}x{png_height} PNG px"
        )


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


def capture_profile(
    browser,
    base_url: str,
    targets: list[dict],
    profile_name: str,
    viewport: dict,
    out_dir: Path,
) -> None:
    context = browser.new_context(
        viewport={"width": viewport["width"], "height": viewport["height"]},
        device_scale_factor=viewport["deviceScaleFactor"],
        color_scheme="dark",
    )
    try:
        page = context.new_page()
        profile_dir = out_dir / profile_name
        profile_dir.mkdir(parents=True, exist_ok=True)
        for target in targets:
            # Optional per-target viewport changes height/width only; the profile
            # owns device scale so every image in a set remains comparable.
            target_vp = {**viewport, **(target.get("viewport") or {})}
            page.set_viewport_size(
                {"width": target_vp["width"], "height": target_vp["height"]}
            )
            prepare(page, base_url, target)
            output = profile_dir / target["file"]
            page.screenshot(path=output)
            print(f"Captured {output}")
    finally:
        context.close()


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
    parser.add_argument(
        "--profile",
        "--profiles",
        dest="profiles",
        action="append",
        metavar="NAME[,NAME...]",
        help="Viewport profile(s); repeat or comma-separate names, or use all",
    )
    parser.add_argument(
        "--list-profiles",
        action="store_true",
        help="List available viewport profiles without opening Chrome",
    )
    parser.add_argument("--only", help="Comma-separated subset of target filenames")
    parser.add_argument(
        "--discover",
        action="store_true",
        help="Report pages/sections lacking capture targets instead of capturing",
    )
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text())
    default_profile, viewport_profiles = load_viewport_profiles(manifest)
    profile_names, explicit_profiles = select_profiles(
        args.profiles,
        viewport_profiles,
        default_profile,
    )
    targets = manifest["targets"]

    if args.list_profiles:
        print_profiles(viewport_profiles)
        return

    if args.only:
        wanted = {name.strip() for name in args.only.split(",")}
        targets = [target for target in targets if target["file"] in wanted]
        if not targets:
            sys.exit(f"No targets matched --only {args.only}")

    chrome = find_chrome(args.chrome)
    out_dir = (args.out_dir or IMAGE_DIR).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        sys.exit(
            "Playwright is required for capture. Install it with:\n"
            "  python3 -m venv /tmp/kraken-screenshots\n"
            "  /tmp/kraken-screenshots/bin/pip install playwright\n"
        )

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=str(chrome),
            headless=True,
            args=["--hide-scrollbars"],
        )
        if args.discover:
            context = browser.new_context(
                viewport={
                    "width": viewport_profiles[profile_names[0]]["width"],
                    "height": viewport_profiles[profile_names[0]]["height"],
                },
                device_scale_factor=viewport_profiles[profile_names[0]]["deviceScaleFactor"],
                color_scheme="dark",
            )
            try:
                discover(context.new_page(), args.base_url, manifest["targets"])
            finally:
                context.close()
        else:
            for profile_name in profile_names:
                capture_profile(
                    browser,
                    args.base_url,
                    targets,
                    profile_name if explicit_profiles else "",
                    viewport_profiles[profile_name],
                    out_dir,
                )

        browser.close()


if __name__ == "__main__":
    main()
