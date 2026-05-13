// End-to-end MCP-client conformance test for tools/causa-mcp.
//
// SKIPPED (rf2-cum40): causa-mcp is spec-only at the moment — see
// tools/causa-mcp/ which contains a README + spec/ subtree but no
// server implementation yet. When the implementation lands, this
// script should be filled in mirroring end-to-end-{pair2,story}.js
// against the canonical causa workflow (likely something along the
// lines of:
//
//   1. connect (initialize)
//   2. tools/list — assert advertised catalogue
//   3. walk a representative read-loop (record → query → reset, or
//      whatever the eventual server surfaces)
//   4. clean disconnect
//
// The script intentionally exits 0 with a "SKIPPED" marker so the CI
// step that runs it can stay green and the placeholder doesn't go
// stale. When the implementation lands, replace this body wholesale.

console.log('SKIP causa-mcp end-to-end conformance test (impl not landed yet; see rf2-cum40)');
process.exit(0);
