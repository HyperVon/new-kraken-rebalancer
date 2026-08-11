# Persistence contract

`PENDING` means the order intent has not reached a terminal state and may be
retried.

`UNCERTAIN` means the persisted record cannot prove which exchange order, if
any, owns the intent. Recovery must hold the intent until identity is confirmed;
it must not release or retry the order based on the state alone.

`CLOSED` is terminal and should be ignored by recovery.
