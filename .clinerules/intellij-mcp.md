# IntelliJ MCP structural work

When the `intellij` MCP server is connected (IDE open with *Enable MCP Server*
ticked), prefer its structural tools for Kotlin work: `search_symbol` +
`analyze_calls` for call hierarchy over text grep, `rename_refactoring` over
manual find-and-replace, `get_file_problems` for quick post-edit diagnostics.

IDE diagnostics are advisory — Gradle gates remain authoritative. If tools are
missing or the connection fails, fall back to native grep/Gradle and continue.

Full guidance: [`.agents/OPERATING.md` § 10 IntelliJ MCP server](../.agents/OPERATING.md).
