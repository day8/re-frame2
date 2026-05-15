# rf2-k0xrc: JVM diagnostics gating across core and tooling

Report-only security/spec review. No implementation changes made.

## Scope

Reviewed the JVM/SSR diagnostics posture for:

- `re-frame.interop/debug-enabled?`
- core trace emission, trace listeners, and retain-N trace buffer
- epoch capture/history/listeners/restore/reset surfaces
- SSR/headless production behavior
- tooling-facing diagnostics exposure, especially pair/Causa MCP-style reads
- specs/docs that describe the production gate

Related bead: `rf2-ir48i` overlaps on epoch exposure and redaction. This report only goes deep enough to identify the overlap and recommend follow-up ownership; it does not duplicate the full epoch redaction policy review.

## Current Behavior

The bead description says JVM `debug-enabled?` is hardcoded `true`. That was the historical risk, but the current worktree is ahead of that description:

- CLJS keeps the existing compile-time gate: `implementation/core/src/re_frame/interop.cljs` defines `debug-enabled?` as `goog.DEBUG`.
- JVM now has a real runtime gate: `implementation/core/src/re_frame/interop.clj` reads `re-frame.debug` system property first, then `RE_FRAME_DEBUG`, once at namespace load.
- The JVM gate defaults to `true` for local dev/test parity.
- Recognized false-y values are `false`, `0`, `no`, `off`, and empty string, trimmed/case-insensitive.
- With the gate false, `trace/emit!`, `trace/emit-error!`, trace-buffer query/clear/config, epoch capture, epoch settle, restore, reset, and epoch listener delivery short-circuit to no-op/false/empty behavior.
- Always-on production observability is intentionally separate: event-emit, error-emit, and per-frame `:on-error` remain live even when `debug-enabled?` is false.

The core gate is covered by tests:

- `implementation/core/test/re_frame/interop_debug_gate_test.clj` pins JVM gate vocabulary and default.
- `implementation/core/test/re_frame/jvm_prod_gate_integration_test.clj` pins trace-buffer/listener no-op behavior with the gate false, and confirms always-on event/error emit still fire.
- `implementation/epoch/test/re_frame/epoch_jvm_prod_gate_test.clj` pins epoch history/listener/restore/reset false-path behavior.

There is still spec/doc drift:

- `spec/Security.md` and `spec/009-Instrumentation.md` describe the new dual-host gate accurately.
- `implementation/SECURITY.md` also describes the JVM gate accurately.
- `spec/API.md` still lists `re-frame.interop/debug-enabled?` as "`goog.DEBUG` on CLJS; `true` on JVM", which is now stale.
- Some Tool-Pair/Causa wording still over-emphasizes CLJS `:advanced` DCE and should be checked for JVM wording where it claims universal production elision.

## Risks

Without an explicit JVM/SSR production-off gate, SSR/headless production processes would retain and expose diagnostics that are safe in dev but unsafe in production:

- Trace buffers hold recent event maps, handler/source metadata, dispatch ids, and raw tags.
- Trace listeners provide synchronous in-process fan-out to any loaded diagnostic consumer.
- Epoch history stores `:db-before`, `:db-after`, `:trace-events`, and projections for recent cascades.
- `restore-epoch` and `reset-frame-db!` are state-rewrite/admin surfaces, not production request-path APIs.
- SSR processes tend to be long-running and handle untrusted/user-derived input, so heap retention, crash dumps, logs, and accidental in-process tool loading matter.

The current explicit-off gate addresses the broad production risk when operators set it. The remaining risk is operational/documentation drift: default-on means a production JVM deployment that forgets `-Dre-frame.debug=false` or `RE_FRAME_DEBUG=false` still runs dev diagnostics.

## Options

1. **Keep current default-on, explicit production opt-out.**
   - Best for pre-alpha dev velocity and JVM test ergonomics.
   - Avoids breaking SSR examples and headless tests.
   - Requires strong docs/templates/release checks so production deployments actually set the flag false.

2. **Default JVM gate off unless explicitly enabled.**
   - Best security default.
   - Worse pre-alpha operability: tools/tests/SSR debugging silently lose diagnostics unless every dev/test launcher opts in.
   - Creates asymmetry with CLJS dev builds, where `goog.DEBUG` is normally true.

3. **Profile-aware default.**
   - Dev/test launchers set true; production templates set false.
   - Good end state, but the runtime still needs a simple final rule. This is mainly packaging/tooling on top of option 1 or 2.

4. **Split gates by surface.**
   - Separate flags for trace, epoch history, admin writes, source coords, schema diagnostics.
   - More precise but over-complex for pre-alpha; risks inconsistent behavior and missed call sites.

## Recommendation

Use option 1 for pre-alpha, with one hardening addition: production scaffolds and release docs must make the JVM false setting mandatory and visible.

Recommended policy:

- Browser/CLJS dev: diagnostics default on via `goog.DEBUG=true`.
- Browser/CLJS production: diagnostics default off via `goog.DEBUG=false` and DCE.
- JVM local dev/test/SSR debugging: diagnostics default on.
- JVM production SSR/headless: diagnostics must be explicitly off with `-Dre-frame.debug=false` or `RE_FRAME_DEBUG=false`.
- Production-safe observability should use event-emit/error-emit/per-frame `:on-error`, not the trace/epoch dev surface.
- Off-box tool surfaces should keep their own privacy gates (`:include-sensitive? false`, size elision, default-drop sensitive events) even when attached to a dev runtime.

This is the cleanest opt-in model because it keeps one conceptual gate, mirrors CLJS semantics as closely as the JVM allows, and avoids per-surface policy fragmentation. The cost is operational: production launch paths must be checked.

## Default-on/off In Pre-alpha

Default on:

- `debug-enabled?` in CLJS dev and JVM dev/test.
- Trace listener registration and trace buffer behavior when the gate is true.
- Epoch recording/listeners/restore/reset when the gate is true and the epoch artefact is loaded.
- Tooling diagnostics in explicit dev tools/preloads.

Default off:

- CLJS production diagnostics via `goog.DEBUG=false`.
- JVM production diagnostics via explicit runtime flag.
- Causa/pair/MCP inclusion in production deps/preloads.
- Sensitive event/tool egress by default (`:include-sensitive? false`).
- Raw state egress unless an operator explicitly allows it.

Always on, including production:

- Event-emit listener substrate.
- Error-emit listener substrate.
- Per-frame `:on-error`.

## Implementation/Test Implications

Current implementation already has the core shape this review recommends. Follow-up should focus on tightening drift and making production misconfiguration harder:

- Update `spec/API.md` to replace "`true` on JVM" with the env/property gate semantics.
- Audit `spec/Tool-Pair.md`, Causa docs, and templates for wording that says "compile-time gate" where JVM production needs "runtime gate".
- Add a template/release-process check documenting `-Dre-frame.debug=false` / `RE_FRAME_DEBUG=false` for JVM SSR production launches.
- Consider a small startup warning for JVM `platform :server` when the process appears production-like but `debug-enabled?` is true. This should be advisory only; do not make runtime heuristics decide security policy.
- Keep the existing false-path tests. They are the right contract tests for core and epoch.
- If JVM artefact packaging has launch scripts or generated Docker/systemd snippets, include the flag there.

## Follow-up Beads Needed

Suggested beads, not created by this report-only task:

- `spec(api): update JVM debug-enabled? row to env/property gate`
- `docs(ssr/templates): require RE_FRAME_DEBUG=false or -Dre-frame.debug=false in JVM production launch docs`
- `audit(docs): normalize Tool-Pair/Causa production-elision wording for dual CLJS/JVM gates`
- `security(epoch): decide default redaction/retention posture for epoch records` - this is the deep `rf2-ir48i` overlap and should stay there, not in `rf2-k0xrc`.

## Decision Answer

Yes, JVM/SSR should have an explicit production-off diagnostics gate. The current runtime now has the right primitive: a single JVM mirror of the CLJS debug gate, default-on for pre-alpha dev/test and explicitly off for production. The clean opt-in model is not per-surface toggles; it is one host-level diagnostics gate plus separate production-safe event/error substrates and separate off-box privacy controls.
