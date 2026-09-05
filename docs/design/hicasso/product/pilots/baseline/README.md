# The behavioural baselines

Two test files, one per pilot, that [`workspace.md`](../workspace.md)'s source manifest copies into the workspace's `app/test/`. They are what outcome 1 of the [friction log](../friction-log.md) measures against: the operator runs them with the app still on Reagent and records the exit code, and the pilot runs them again after the migration.

Added under `rf2-xkhul`, on `rf2-hic-063`'s 2026-09-02 ruling that a workspace with no runnable baseline leaves outcome 1 blocked at hour zero.

| File | Lands at | Covers |
| --- | --- | --- |
| [`realworld_http/baseline_test.cljs`](realworld_http/baseline_test.cljs) | `app/test/realworld_http/` | The feed, the article editor, the routing both sit in, and boot |
| [`linearlite/baseline_test.cljs`](linearlite/baseline_test.cljs) | `app/test/linearlite/` | The board: route-owned load, optimistic create / retitle / change-status, commit, rollback, and the demo backend's armed 503 |

## Where they come from

The examples tree is test-free by policy, so neither app carries tests of its own. Their behavioural suites live in the Reagent adapter's test tree — `implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs` and `linearlite_example_cljs_test.cljs` — and run on the in-repo harness, which co-loads every example into one bundle and carries hygiene for that (each suite's fixture hides its own app's registrations behind an `:app-ns` prefix and reinstates them per test; the trace bus is isolated separately). None of that applies to a workspace with one app in it, and none of it travels.

What travels is the subset that exercises the nominated screens, rewritten as the app's own test namespace (`realworld-http.baseline-test`, `linearlite.baseline-test`). Each stands on `cljs.test`, on the canned-reply effects managed HTTP ships for exactly this, and on the runtime-reset fixture from core's test support — all of which the published `:local/root` route puts on the workspace classpath — and on nothing else. The whole RealWorld suite is deliberately not carried: the pilot migrates two screens, and the baseline is the behaviour of those two.

## Two rules

**No in-tree reference inside the files.** The pilot may read everything under `app/`, and these land there. A bead id, a spec section or a repository path in a test comment is a leak the pilot did not choose, so the files name none — the comments were rewritten in the app's voice, not merely trimmed. A change here that reintroduces one breaks the read fence.

**These are fixtures, not a test lane.** Nothing in this repository compiles or runs them; the workspace does, through the `:test` build and `npm test` that `workspace.md` sets up. The proof they are green is the captured exit code from a workspace built by following that page alone, which is what step 5b records. When the adapter suites change in a way that touches the covered behaviour, refresh the corresponding file here by hand and re-run step 5b in a clean workspace before the next pilot is dispatched.
