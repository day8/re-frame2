# Naming ledger — every naming question, one consolidation point

**Rule**: nobody renames mid-flow; prototype names are used consistently everywhere; every naming question found by any bead is appended here as a row. The consolidation bead (rf2-hic-065) publishes the complete packet; its recommendations apply as defaults immediately; the operator's single sitting overrides rows asynchronously and rf2-hic-066 re-runs the sweep as a diff.

| # | Current name | Candidate(s) | Recommendation | Status |
|---|---|---|---|---|
| 1 | `h/fn` | `h/handler` | `h/handler` (one invariant meaning; spec §4) | open |
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
| 13 | root lifecycle | `mount!` `hydrate!` `render!` `unmount!` | keep the four | open |
| 14 | package/artifact name | `implementation/hicasso`, artifact name TBD | `re-frame.hicasso` (provisional) | open |
| 15 | test-kit namespace | `re-frame.hicasso.test` | keep | open |
| 16 | forms module ns | `re-frame.hicasso.forms` | provisional | open |
| 17 | overlay module ns | `re-frame.hicasso.overlay` | provisional | open |
