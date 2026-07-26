import React from "react";
import {
  Button,
  Callout,
  Card,
  Grid,
  Pill,
  Select,
  Stack,
  dispatch,
  useCanvasState,
} from "cursor/canvas";

interface DecisionState {
  strategicDirection: string;
  decisions: Record<string, string>;
}

const DEFAULT_STATE: DecisionState = {
  strategicDirection: "evolve_monolith",
  decisions: {
    "SEC-BIND": "evolve_loopback",
    "CONC-CANCEL": "evolve_non_cancellable",
    "DOMAIN-IO": "evolve_pure_engine",
    "MONO-MOD": "keep_current",
    "ORDER-IDEM": "evolve_userref",
  },
};

const RECOMMENDED_LEANS: Record<string, string> = {
  "SEC-BIND": "evolve_loopback",
  "CONC-CANCEL": "evolve_non_cancellable",
  "DOMAIN-IO": "evolve_pure_engine",
  "MONO-MOD": "keep_current",
  "ORDER-IDEM": "evolve_userref",
};

export default function ArchitectureReviewDecisionsCanvas() {
  const [state, setState] = useCanvasState<DecisionState>(
    "architecture-review-decisions-v1",
    DEFAULT_STATE
  );

  const setStrategicDirection = (dir: string) => {
    setState((prev) => ({ ...prev, strategicDirection: dir }));
  };

  const setDecision = (id: string, value: string) => {
    setState((prev) => ({
      ...prev,
      decisions: { ...prev.decisions, [id]: value },
    }));
  };

  const applyReviewerLeans = () => {
    setState((prev) => ({
      ...prev,
      decisions: { ...RECOMMENDED_LEANS },
    }));
  };

  const clearAll = () => {
    setState((prev) => ({
      ...prev,
      decisions: {
        "SEC-BIND": "keep_current",
        "CONC-CANCEL": "keep_current",
        "DOMAIN-IO": "keep_current",
        "MONO-MOD": "keep_current",
        "ORDER-IDEM": "keep_current",
      },
    }));
  };

  const handleSendDecisions = () => {
    const summaryLines = [
      `### Strategic Architecture Decisions Selected`,
      `**Overall Direction**: ${state.strategicDirection}`,
      ``,
      `**Item Decisions**:`,
      ...Object.entries(state.decisions).map(([id, choice]) => `- **${id}**: ${choice}`),
      ``,
      `Please create a detailed implementation plan for the selected Evolve/Replace/Greenfield items.`,
    ];
    dispatch({
      type: "newComposerChat",
      userPrompt: summaryLines.join("\n"),
    });
  };

  return (
    <Stack spacing={4} style={{ padding: "20px", maxWidth: "900px", margin: "0 auto" }}>
      <Stack spacing={2}>
        <h1 style={{ fontSize: "24px", fontWeight: "bold", margin: 0 }}>
          Kraken Rebalancer — Architecture Decisions
        </h1>
        <p style={{ color: "#888", margin: 0 }}>
          Review the findings below, select target paths for each architectural dimension, and submit decisions to generate an implementation plan.
        </p>
      </Stack>

      <Callout variant="info">
        Select your preferred path for each item. Items marked <b>Keep current</b> or <b>Defer / skip</b> will remain untouched.
      </Callout>

      <Card style={{ padding: "16px" }}>
        <Stack spacing={2}>
          <h3 style={{ margin: 0, fontSize: "16px", fontWeight: "600" }}>Strategic System Direction</h3>
          <Select
            value={state.strategicDirection}
            onChange={(val) => setStrategicDirection(val)}
            options={[
              { label: "Evolve Monolith — Fix P0/P1 safety & domain purity inside Kotlin/JVM app (Recommended)", value: "evolve_monolith" },
              { label: "Pure Domain Core + Port Adapters — Modular Hexagonal Refactor", value: "pure_domain_hexagonal" },
              { label: "Full Greenfield Rewrite — Re-architect in Go / Rust / TS", value: "greenfield_rewrite" },
            ]}
          />
        </Stack>
      </Card>

      <Stack spacing={3}>
        {/* SEC-BIND */}
        <Card style={{ padding: "16px" }}>
          <Stack spacing={2}>
            <Grid columns="1fr auto" gap="12px" style={{ alignItems: "center" }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={2} style={{ alignItems: "center" }}>
                  <Pill variant="danger">P0</Pill>
                  <strong style={{ fontSize: "16px" }}>SEC-BIND — Public Network Interface Binding (0.0.0.0)</strong>
                </Stack>
                <span style={{ fontSize: "13px", color: "#666" }}>
                  Netty binds to 0.0.0.0 without authentication, exposing settings and order triggers to LAN/Wi-Fi.
                </span>
              </Stack>
              <Pill variant="info">Reviewer Lean: Loopback Default</Pill>
            </Grid>
            <Select
              value={state.decisions["SEC-BIND"] || "keep_current"}
              onChange={(val) => setDecision("SEC-BIND", val)}
              options={[
                { label: "(Recommended) Evolve: Default to 127.0.0.1, require explicit config for 0.0.0.0 + log warning", value: "evolve_loopback" },
                { label: "Replace: Add HTTP Bearer/Cookie Authentication to all endpoint routes", value: "replace_add_auth" },
                { label: "Keep current: Leave 0.0.0.0 default unauthenticated binding", value: "keep_current" },
                { label: "Defer / skip", value: "defer" },
              ]}
            />
          </Stack>
        </Card>

        {/* CONC-CANCEL */}
        <Card style={{ padding: "16px" }}>
          <Stack spacing={2}>
            <Grid columns="1fr auto" gap="12px" style={{ alignItems: "center" }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={2} style={{ alignItems: "center" }}>
                  <Pill variant="warning">P1</Pill>
                  <strong style={{ fontSize: "16px" }}>CONC-CANCEL — Mid-Trade Execution Cancellation via collectLatest</strong>
                </Stack>
                <span style={{ fontSize: "13px", color: "#666" }}>
                  POST /settings triggers collectLatest cancellation mid-rebalance cycle, interrupting live order execution.
                </span>
              </Stack>
              <Pill variant="info">Reviewer Lean: NonCancellable Cycle</Pill>
            </Grid>
            <Select
              value={state.decisions["CONC-CANCEL"] || "keep_current"}
              onChange={(val) => setDecision("CONC-CANCEL", val)}
              options={[
                { label: "(Recommended) Evolve: Wrap rebalance cycle in NonCancellable context & read config at cycle boundary", value: "evolve_non_cancellable" },
                { label: "Evolve: Atomic Mutex lock preventing concurrent settings emission processing", value: "evolve_mutex_lock" },
                { label: "Keep current: Allow collectLatest to interrupt running cycles", value: "keep_current" },
                { label: "Defer / skip", value: "defer" },
              ]}
            />
          </Stack>
        </Card>

        {/* DOMAIN-IO */}
        <Card style={{ padding: "16px" }}>
          <Stack spacing={2}>
            <Grid columns="1fr auto" gap="12px" style={{ alignItems: "center" }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={2} style={{ alignItems: "center" }}>
                  <Pill variant="warning">P1</Pill>
                  <strong style={{ fontSize: "16px" }}>DOMAIN-IO — Intertwined Rebalancing Core and REST/DB I/O</strong>
                </Stack>
                <span style={{ fontSize: "13px", color: "#666" }}>
                  PortfolioAnalyzerImpl mixes REST ticker/balance calls and SQLite ATH saving with pure rebalancing math.
                </span>
              </Stack>
              <Pill variant="info">Reviewer Lean: Pure Engine</Pill>
            </Grid>
            <Select
              value={state.decisions["DOMAIN-IO"] || "keep_current"}
              onChange={(val) => setDecision("DOMAIN-IO", val)}
              options={[
                { label: "(Recommended) Evolve: Extract 100% pure RebalancerEngine domain functions with zero IO dependencies", value: "evolve_pure_engine" },
                { label: "Replace: Extract separate :domain-core KMP module", value: "replace_domain_module" },
                { label: "Keep current: Maintain PortfolioAnalyzerImpl as hybrid service", value: "keep_current" },
                { label: "Defer / skip", value: "defer" },
              ]}
            />
          </Stack>
        </Card>

        {/* MONO-MOD */}
        <Card style={{ padding: "16px" }}>
          <Stack spacing={2}>
            <Grid columns="1fr auto" gap="12px" style={{ alignItems: "center" }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={2} style={{ alignItems: "center" }}>
                  <Pill variant="neutral">P2</Pill>
                  <strong style={{ fontSize: "16px" }}>MONO-MOD — View DSL & CSS Monolith Coupling</strong>
                </Stack>
                <span style={{ fontSize: "13px", color: "#666" }}>
                  HTML component singletons and kotlinx.css styles live inside the core trading JVM JAR package.
                </span>
              </Stack>
              <Pill variant="info">Reviewer Lean: Keep Current</Pill>
            </Grid>
            <Select
              value={state.decisions["MONO-MOD"] || "keep_current"}
              onChange={(val) => setDecision("MONO-MOD", val)}
              options={[
                { label: "(Recommended) Keep current: Monolith SSR is well-suited for single-operator desktop tool", value: "keep_current" },
                { label: "Evolve: Clean package separation (com.gemini.krakenbot.web.*) and separate web Koin module", value: "evolve_package_split" },
                { label: "Replace: Replace kotlinx.html/css with standalone SPA (React/Vite) & pure REST JSON API", value: "replace_spa" },
                { label: "Defer / skip", value: "defer" },
              ]}
            />
          </Stack>
        </Card>

        {/* ORDER-IDEM */}
        <Card style={{ padding: "16px" }}>
          <Stack spacing={2}>
            <Grid columns="1fr auto" gap="12px" style={{ alignItems: "center" }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={2} style={{ alignItems: "center" }}>
                  <Pill variant="neutral">P2</Pill>
                  <strong style={{ fontSize: "16px" }}>ORDER-IDEM — Client Order Ref Tracking (userref)</strong>
                </Stack>
                <span style={{ fontSize: "13px", color: "#666" }}>
                  Kraken AddOrder calls omit userref, risking double-trades during network timeouts.
                </span>
              </Stack>
              <Pill variant="info">Reviewer Lean: Pass UserRef</Pill>
            </Grid>
            <Select
              value={state.decisions["ORDER-IDEM"] || "keep_current"}
              onChange={(val) => setDecision("ORDER-IDEM", val)}
              options={[
                { label: "(Recommended) Evolve: Generate deterministic 32-bit userref per order attempt for Kraken API idempotency", value: "evolve_userref" },
                { label: "Keep current: Rely on existing retry and manual balance checks", value: "keep_current" },
                { label: "Defer / skip", value: "defer" },
              ]}
            />
          </Stack>
        </Card>
      </Stack>

      <Stack direction="row" spacing={2} style={{ justifyContent: "flex-end", marginTop: "16px" }}>
        <Button variant="secondary" onClick={applyReviewerLeans}>
          Apply Reviewer Leans
        </Button>
        <Button variant="secondary" onClick={clearAll}>
          Reset to Keep
        </Button>
        <Button variant="primary" onClick={handleSendDecisions}>
          Send Decisions to Chat
        </Button>
      </Stack>
    </Stack>
  );
}
