# Parallel multi-agent work

Parallelize independent workstreams through the host's native parallel task
surface; subagents inherit the parent session's model. Keep tracks bounded with
disjoint file ownership; file disjointness alone does not justify delegation.
The parent owns integration, cleanup, and final gates.

Full guidance: [`.agents/OPERATING.md` § 3 Parallel multi-agent work](../.agents/OPERATING.md).
Deep how-to: [`.agents/skills/parallel-multi-agent/SKILL.md`](../.agents/skills/parallel-multi-agent/SKILL.md).
