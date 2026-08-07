# No blocking long processes

Never leave the user waiting on a foreground command that never exits; run
long-lived processes in the background and poll for readiness patterns, not
open-ended waits.

Full guidance: [`.agents/OPERATING.md` § 4 No blocking long processes](../.agents/OPERATING.md).
