import unittest

from app.recovery import recovery_action


class RecoveryTest(unittest.TestCase):
    def test_uncertain_without_identity_is_held(self) -> None:
        row = {
            "state": "UNCERTAIN",
            "exchange_order_id": None,
        }

        self.assertEqual("HOLD", recovery_action(row))


if __name__ == "__main__":
    unittest.main()
