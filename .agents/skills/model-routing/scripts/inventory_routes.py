#!/usr/bin/env python3
"""Summarize Kilo's verbose model catalog without replaying the raw catalog."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterator


ROUTE_HEADER = re.compile(r"(?m)^(?P<route>[^\s{][^\n]*)\n\{")


def values_at(value: dict[str, Any], path: str) -> Any:
    current: Any = value
    for key in path.split("."):
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def catalog_records(text: str) -> Iterator[tuple[str, dict[str, Any]]]:
    decoder = json.JSONDecoder()
    seen: set[str] = set()
    for match in ROUTE_HEADER.finditer(text):
        route = match.group("route").strip()
        if "/" not in route or route in seen:
            continue
        try:
            record, _ = decoder.raw_decode(text, match.end() - 1)
        except json.JSONDecodeError:
            continue
        if isinstance(record, dict):
            seen.add(route)
            yield route, record


def enabled_reasoning_variants(record: dict[str, Any]) -> str:
    variants = record.get("variants", {})
    if not isinstance(variants, dict):
        return "-"
    enabled = []
    for name, variant in variants.items():
        if not isinstance(variant, dict):
            continue
        reasoning = variant.get("reasoning", {})
        if isinstance(reasoning, dict) and (
            reasoning.get("enabled") is True or reasoning.get("effort")
        ):
            enabled.append(name)
    return ",".join(enabled) if enabled else "-"


def display(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Print a bounded, metadata-only summary of Kilo model routes."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=None,
        help="Read verbose catalog output from a file instead of stdin.",
    )
    parser.add_argument(
        "--match",
        help="Keep routes whose route ID, provider, family, or name contains this text.",
    )
    parser.add_argument(
        "--status",
        default="active",
        help="Filter by catalog status (default: active; use all to disable).",
    )
    parser.add_argument(
        "--tool-call",
        action="store_true",
        help="Keep only routes whose catalog supports tool calls.",
    )
    parser.add_argument(
        "--reasoning",
        action="store_true",
        help="Keep only routes whose catalog supports reasoning.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=40,
        help="Maximum route rows to print (default: 40).",
    )
    parser.add_argument(
        "--probe",
        action="store_true",
        help="Run one bounded kilo roll-call probe per selected route.",
    )
    parser.add_argument(
        "--verified-only",
        action="store_true",
        help="After probing, print only routes that responded successfully.",
    )
    parser.add_argument(
        "--probe-prompt",
        default="Reply with READY only.",
        help="Prompt sent by each opt-in availability probe.",
    )
    parser.add_argument(
        "--probe-timeout",
        type=int,
        default=25000,
        help="Timeout per availability probe in milliseconds (default: 25000).",
    )
    return parser.parse_args()


def probe_route(route: str, prompt: str, timeout_ms: int) -> tuple[str, str]:
    command = [
        "kilo",
        "roll-call",
        f"^{re.escape(route)}$",
        "--prompt",
        prompt,
        "--timeout",
        str(timeout_ms),
        "--parallel",
        "1",
        "--quiet",
        "--output",
        "json",
    ]
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=max(timeout_ms / 1000, 0.001) + 5,
            check=False,
        )
        responses = json.loads(result.stdout)
    except (FileNotFoundError, OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
        return "unavailable", "-"

    if not isinstance(responses, list):
        return "unavailable", "-"
    response = next(
        (item for item in responses if isinstance(item, dict) and item.get("model") == route),
        None,
    )
    if not isinstance(response, dict):
        return "unavailable", "-"
    return ("verified" if response.get("access") is True else "unavailable"), display(
        response.get("latency")
    )


def main() -> int:
    args = parse_args()
    if args.limit < 1:
        raise SystemExit("--limit must be positive")
    if args.probe_timeout < 1:
        raise SystemExit("--probe-timeout must be positive")
    if args.verified_only and not args.probe:
        raise SystemExit("--verified-only requires --probe")
    text = args.input.read_text(encoding="utf-8") if args.input else sys.stdin.read()
    records = list(catalog_records(text))

    def matches(item: tuple[str, dict[str, Any]]) -> bool:
        route, record = item
        status = record.get("status")
        if args.status != "all" and status != args.status:
            return False
        if args.tool_call and values_at(record, "capabilities.toolcall") is not True:
            return False
        if args.reasoning and values_at(record, "capabilities.reasoning") is not True:
            return False
        if args.match:
            haystack = " ".join(
                display(record.get(key))
                for key in ("providerID", "family", "name")
            )
            if args.match.lower() not in f"{route} {haystack}".lower():
                return False
        return True

    candidates = [item for item in records if matches(item)]
    selected = candidates[: args.limit]
    probes: dict[str, tuple[str, str]] = {}
    probed_count = 0
    verified_count = 0
    if args.probe:
        probed_count = len(selected)
        for route, _ in selected:
            probes[route] = probe_route(route, args.probe_prompt, args.probe_timeout)
        verified_count = sum(status == "verified" for status, _ in probes.values())
        if args.verified_only:
            selected = [item for item in selected if probes[item[0]][0] == "verified"]

    print(
        f"Parsed {len(records)} catalog routes; {len(candidates)} matched filters; "
        f"showing {len(selected)}."
    )
    if args.probe:
        print(f"Probed {probed_count} selected routes; {verified_count} verified.")
        if args.verified_only and not selected:
            print(
                "WARNING: no probed route verified; this does not prove that other "
                "catalog routes or host-pinned profiles are unavailable. Use a "
                "narrow --match for the exact provider/model route."
            )
        elif args.verified_only and probed_count < len(candidates):
            print(
                f"WARNING: only the first {probed_count} of {len(candidates)} "
                "matching routes were probed; narrow --match before treating "
                "the result as route availability."
            )
    else:
        print("Availability is catalog-only; current quota and request health remain unknown.")
    print(
        "route | status | availability | latency | context | output | tool | reasoning | variants | input | output | name"
    )
    print("--- | --- | --- | ---: | ---: | ---: | --- | --- | --- | ---: | ---: | ---")
    for route, record in selected:
        print(
            " | ".join(
                (
                    route,
                    display(record.get("status")),
                    probes.get(route, ("catalog-only", "-"))[0],
                    probes.get(route, ("catalog-only", "-"))[1],
                    display(values_at(record, "limit.context")),
                    display(values_at(record, "limit.output")),
                    display(values_at(record, "capabilities.toolcall")),
                    display(values_at(record, "capabilities.reasoning")),
                    enabled_reasoning_variants(record),
                    display(values_at(record, "cost.input")),
                    display(values_at(record, "cost.output")),
                    display(record.get("name")),
                )
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
