#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { execSync } from "node:child_process";

const server = new McpServer({
  name: "quota-mcp",
  version: "0.1.0",
});

server.registerTool(
  "quota_check",
  {
    description: "Show OpenCode quota usage with visual bars (OpenAI, OpenRouter, OpenCode Go).",
    inputSchema: {},
  },
  async () => {
    try {
      const out = execSync("npx @slkiser/opencode-quota show", {
        encoding: "utf-8",
        maxBuffer: 1024 * 1024,
      }).trim();
      if (!out) {
        return {
          content: [{ type: "text", text: "(quota: no output)" }],
        };
      }
      return {
        content: [{ type: "text", text: out }],
      };
    } catch (err) {
      const msg = err.stderr?.toString?.() || err.message || String(err);
      return {
        content: [{ type: "text", text: `quota check failed: ${msg}` }],
        isError: true,
      };
    }
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((error) => {
  console.error("Server error:", error);
  process.exit(1);
});
