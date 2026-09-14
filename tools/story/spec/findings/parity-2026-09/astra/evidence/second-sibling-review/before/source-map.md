# Source and baseline map

Repository `98e8ffe9cb339295fe2fa459900d9d9647fab015`. Paths below identify implementation and **existing coverage to consult**, not tests all executed in this research. Runtime evidence is linked separately in the evaluation protocol. Important functions and source line numbers in the report's probes are tied to this revision.

| Jobs | Public entry / implemented route | Existing test or executable fixture |
|---|---|---|
| J01, J02 | [Story API](../../../../../tools/story/src/re_frame/story.cljc): `reg-story`, `reg-variant`, `mount-shell!`; [registrar](../../../../../tools/story/src/re_frame/story/registrar.cljc) keyword component validation | [Login declarations](../../../../../tools/story/testbeds/login_form/stories.cljs), [login views](../../../../../tools/story/testbeds/login_form/views.cljs); our live controls receipt |
| J02 | [Controls](../../../../../tools/story/src/re_frame/story/ui/controls.cljs), [plan](../../../../../tools/story/src/re_frame/story/plan.cljc): schema derivation, `effective-args`, `explain` | [Nested controls](../../../../../tools/story/test/re_frame/story/ui/controls_nested_cljs_test.cljc), [validation](../../../../../tools/story/test/re_frame/story/ui/controls_validation_diff_cljs_test.cljc), [canvas resolved args](../../../../../tools/story/test/re_frame/story/ui/canvas_resolved_args_cljs_test.cljs), [Explain panel](../../../../../tools/story/test/re_frame/story/ui/explain_panel_test.cljc) |
| J02, J03 | [Fidelity scaffold](../../../../../tools/story/src/re_frame/story/ui/view_state.cljc), `upgrade-snippet` | [View-state tests](../../../../../tools/story/test/re_frame/story/ui/view_state_test.cljc); our actual helper→reader→compiler probe reveals the remaining handoff gap |
| J03, J04 | [Frames](../../../../../tools/story/src/re_frame/story/frames.cljc), [network fixtures](../../../../../tools/story/src/re_frame/story/network.cljc), [runtime](../../../../../tools/story/src/re_frame/story/runtime.cljc) | [Public network runtime tests](../../../../../tools/story/test/re_frame/story/network_runtime_test.clj), login all-states grid; selected-image/ownership tests read but not rerun |
| J05 | [Budgets](../../../../../tools/story/src/re_frame/story/budgets.cljc): 2,000 variants / 200 stories / 50 workspaces; structural boundedness distinct from latency targets | [Budget coverage](../../../../../tools/story/test/re_frame/story/budgets_cljs_test.cljc); no large-catalog timing executed |
| J06, J07 | Public `run`, `is`, `explain`; [runtime](../../../../../tools/story/src/re_frame/story/runtime.cljc), [Test-mode view](../../../../../tools/story/src/re_frame/story/ui/test_mode/view.cljs) | Our [public runner receipt](story-public-run.json), [failed UI follow-up](sibling-followup.json); known no-adapter/check/tape repairs checked at source |
| J07 | [UI promotion adapter](../../../../../tools/story/src/re_frame/story/ui/promotion.cljc), [body builder](../../../../../tools/story/src/re_frame/story/promotion.cljc) | [Promotion coverage](../../../../../tools/story/test/re_frame/story/promotion_cljs_test.cljc), [UI promotion coverage](../../../../../tools/story/test/re_frame/story/ui/promotion_cljs_test.cljc); our fail-before/pass-after and event-assertion positive controls |
| J08 | [Browser assertions](../../../../../tools/story/src/re_frame/story/play/browser.cljc), [requirements](../../../../../tools/story/src/re_frame/story/requirements.cljc), [axe UI](../../../../../tools/story/src/re_frame/story/ui/a11y.cljs), [identity](../../../../../tools/story/src/re_frame/story/identity.cljc) | [Axe stale settlement](../../../../../tools/story/test/re_frame/story/ui/a11y_stale_settlement_cljs_test.cljs), [teardown](../../../../../tools/story/test/re_frame/story/ui/a11y_teardown_eviction_cljs_test.cljs); actual scan receipt does not establish pixel regression |
| J09 | [Docs UI](../../../../../tools/story/src/re_frame/story/ui/docs.cljc), [static build CLI](../../../../../implementation/scripts/story-build.cjs), [snapshot/share tutorial](../../../../../docs/story/08-snapshot-identity-and-sharing.md) | Fresh named-variant deep link exercised; static rig inspected, not built/published |
| J10 | [Standalone Story-MCP](../../../../../tools/story-mcp/README.md), [Pair browser recipe](../../../../../skills/re-frame2-pair/references/stories.md), public Story functions | Browser calls exercised; configured Pair discovery failed at transport; no complete connected agent session claimed |

## Competitor pins

Versions were checked against primary registries/releases during September 13–14 research, not inferred from sibling reports. Detailed primary capability links are in the matrix.

| Tool | Release used / source pin | Execution |
|---|---|---|
| Storybook | 10.6.0; `a77777356be2aeaff89d7a2b25254db7b2318392` | React fixture, Controls, Interactions, Vitest 4.1.11 browser tests |
| Ladle | 5.1.1; `780c19e5756db21674db1bdf7ff995d858f4e3e1` | Primary documentation and selected source only |
| Histoire | npm latest 1.0.0-beta.1; `f04001937b86d330d1b8df3483adbad4bfbcc57c`; non-prerelease baseline caveat 0.17.17 | Documentation/source only; screenshot plugin capture distinguished from comparison |
| Portfolio | 2026.03.1; `c86b4582aa5da3b78f2afed58af5dcad956eab31` | Documentation/source only; current docs correct two release-era examples |
| Lookbook | 2.3.15; `d0a275682ad3920c0307edbe2b4ef3aa40df50f7` | Documentation/source only; docs banner 2.3.14 is stale |

Widgetbook v4 is a beta design reference added during synthesis; no local Flutter comparison was performed. Node/package/Chromium versions and physical environment are in [the research receipt](independent-draft-receipt.json) and [isolated fixture](storybook-fixture/package.json).
