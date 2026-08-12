"""Stable error boundaries for the model-router runtime."""


class RouterError(RuntimeError):
    """An expected, user-actionable routing failure."""


class NoRouteError(RouterError):
    """No candidate satisfied the current routing policy."""


class ARRIntegrationError(RouterError):
    """ARR could not be imported, translated, or invoked safely."""
