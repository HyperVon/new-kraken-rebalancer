"""Recovery decisions for persisted orders."""

from __future__ import annotations

from collections.abc import Mapping


def recovery_action(row: Mapping[str, object]) -> str:
    """Return the safe action for a persisted order row."""

    state = row["state"]
    if state == "PENDING":
        return "RETRY"
    if state == "CLOSED":
        return "IGNORE"
    return "RELEASE"
