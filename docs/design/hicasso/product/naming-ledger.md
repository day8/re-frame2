# Naming ledger — every naming question, one consolidation point

**Rule**: nobody renames mid-flow; prototype names are used consistently everywhere; every naming question found by any bead is appended here as a row. The consolidation bead (rf2-hic-065) publishes the complete packet; its recommendations apply as defaults immediately; the operator's single sitting overrides rows asynchronously and rf2-hic-066 re-runs the sweep as a diff.

| # | Current name | Candidate(s) | Recommendation | Status |
|---|---|---|---|---|
| 1 | `hfn` (taught as `h/fn`) | `h/handler` (spec §4 facade table); `h/event` (cross-adaptor vocabulary) | `h/event` — the established v/·ui/ vocabulary reserves `event` for "convert invoker's args to one event vector or `nil`" and `handler` for imperative return-ignored work (spec/004-Views.md §4.x table, 004D callback table); Hicasso's form has exactly the `event` contract, so `handler` would be a cross-adaptor false friend. One invariant meaning either way; never `fn` (shadows `cljs.core/fn` for `:refer` users). Both candidates stand for the sitting (rf2-84w6) | open |
| 2 | `:&` merge key | remove; pure owned-wins merge helper/recipe | remove from grammar (spec §4 disposition) | open |
| 3 | `h/reg-state` | remove from adaptor core; reconsider in forms | remove from core | open |
| 4 | `subscribe-once` | internal/advanced | internal until a caller proves `sub` inadequate | open |
| 5 | presence namespace | optional motion namespace name | `re-frame.hicasso.motion` (provisional) | open |
| 6 | route-link home | routing-integration namespace name | `re-frame.hicasso.routing` (provisional) | open |
| 7 | `n/$` | keep | keep (ruled surface) | open |
| 8 | `n/props` | keep | keep (normative grammar) | open |
| 9 | `n/defcomponent` | keep | keep | open |
| 10 | `n/use-sub` / `n/use-frame` | keep | keep | open |
| 11 | `h/as-element` | keep | keep | open |
| 12 | `h/error-boundary` | keep | keep | open |
| 13 | root lifecycle: door has `root!` + `release!` (impl also holds `hydrate-root!` `render!` `unmount!`, docstring-unfrozen) | `mount!` `hydrate!` `render!` `unmount!` on the facade; `release!` → test kit | the four verbs (`root!`→`mount!`, `hydrate-root!`→`hydrate!`; matches React's createRoot/hydrateRoot model); retire `release!` from the door — it calls process-wide `collector/reset-runtime!`, a test-fixture reset, not per-root lifecycle in a multi-root product; it moves to the test kit under an explicit test-wide-reset name. `root!`/`release!` ratified provisional until the sweep (rf2-84w6) | open |
| 14 | package/artifact name | `implementation/hicasso`, artifact name TBD | `re-frame.hicasso` (provisional) | open |
| 15 | test-kit namespace | `re-frame.hicasso.test` | keep | open |
| 16 | forms module ns | `re-frame.hicasso.forms` | provisional | open |
| 17 | overlay module ns | `re-frame.hicasso.overlay` | provisional | open |
| 18 | `hframe` (taught as `h/frame`) | `frame` (mechanical rename); or retire in favour of the core doors `rf/current-frame-id` + zero-arity `rf/capture-frame` admitted during a Hicasso body | retire — spec §4 already says "Existing `rf/current-frame-id` and `rf/capture-frame` remain the frame doors; Hicasso should not duplicate them"; and `hframe` returns a frame-id keyword while `ui/frame`/`n/use-frame` name frame *operations* (false friend). NOTE: the retire path is SEMANTIC, not mechanical — the ambient-refusal seam must admit exactly `:current-frame-id`/`:capture-frame` inside a Hicasso body (resolving to the body's `:extent-frame`; ambient `rf/subscribe`/`rf/dispatch` stay refused), so rf2-hic-066 files a bead for it rather than improvising (rf2-84w6) | open |
