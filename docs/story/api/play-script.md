# Play scripts

This chapter is about the surface that turns a variant body's `:play-script` slot into a deterministic, replayable sequence of dispatches, DOM gestures, sleeps, and assertions. The core of it is **a ten-step grammar** of bare-vector forms — `[:dispatch ...]`, `[:dispatch-sync ...]`, `[:wait ms]`, `[:click selector]`, `[:type selector text]`, four `[:assert-* ...]` shapes — that the runtime's phase-4 driver walks in order against the variant's frame. Around the grammar sit the **seven canonical `:rf.assert/*` events** (the assertion vocabulary the `[:dispatch-sync ...]` rail rides), the **record-don't-throw** discipline (failures append to the variant's `:assertions` accumulator rather than aborting the play), and the **recorder facade** (six fns that capture canvas gestures and emit a paste-ready `:play-script` body).

`:play-script` is the **canonical AND ONLY** phase-4 play surface as of 2026-05-20 — the legacy `:play` event-vector slot has been removed under pre-alpha posture, with no transitional dual-acceptance.

## The grammar — ten step forms

Every step is a bare vector. Phase 4 of the variant lifecycle iterates the script in order, dispatch-syncs each step into the variant's frame, drains to completion between steps, and records the result.

| Step | Semantics |
|---|---|
| `[:dispatch event-vec]` | `rf/dispatch` (async) into the variant's frame. Returns immediately; the runtime drains the cascade before stepping. |
| `[:dispatch-sync event-vec]` | `rf/dispatch-sync` (synchronous) into the variant's frame. The runtime waits for the drain to settle. The canonical seven `:rf.assert/*` events ride this rail. |
| `[:wait ms]` | Sleep N milliseconds. Use sparingly — async settlement is the canonical reason to wait, and the runtime already drains between steps. |
| `[:assert-db path value]` | Assert `(= (get-in @app-db path) value)`. A bare-vector shorthand for `[:dispatch-sync [:rf.assert/path-equals path value]]`. |
| `[:assert-db path :pred fn-or-sym]` | Assert custom predicate. The predicate is a unary fn over the value at `path`. |
| `[:assert-dom selector :visible]` | Assert selector resolves to a visible DOM node. |
| `[:assert-dom selector :hidden]` | Assert selector resolves to nothing. |
| `[:assert-dom selector :text txt]` | Assert selector's text-content matches `txt`. |
| `[:click selector]` | Synthetic click event at selector. |
| `[:type selector text]` | Synthetic input event at selector with `text`. |

The script body can be either:

- **Bare vector** — `:play-script [[:dispatch-sync [:foo]] [:assert-db [:n] 1]]`
- **Map** — `:play-script {:script [...] :auto-run? bool :name str}`

The map shape carries optional metadata: `:auto-run?` (default `true` — phase 4 fires on variant mount) and `:name` (surfaced in the *Tests* mode pane summary).

## The canonical seven `:rf.assert/*` events

The assertion vocabulary auto-registers at Story load (the auto-install gate fires from the first `reg-*`). All seven record results into the variant frame's `:rf.story/assertions` slot rather than throwing.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)`. The most-cited assertion — most `:play-script` rows that aren't dispatches end here. |
| `:rf.assert/path-matches` | `[path malli-schema]` | `(m/validate schema (get-in @app-db path))`. Use when the expected shape is a structural match, not a value match. |
| `:rf.assert/sub-equals` | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)`. Asserts against a subscription's computed value rather than raw `app-db`. |
| `:rf.assert/dispatched?` | `[event-vec]` | Was this event dispatched against this frame during phase-4? The accumulator is phase-4-scoped — events dispatched during phase-2 `:events` setup don't count. |
| `:rf.assert/state-is` | `[machine-id state]` | Active state of `reg-machine` machine-id is `state`. Pairs with the per-variant trace-buffer's `:rf.machine/guard-evaluated` + `:rf.machine/action-ran` ops so failures can be diagnosed against the captured guard / action trace. |
| `:rf.assert/no-warnings` | `[]` | No `:rf.warn/*` events seen during play. The "did anything misbehave?" assertion. |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | Did the variant's drain emit `fx-id`? The optional `pred` is a unary fn over the matched fx-id keyword (exceptions count as `false`). |

Each handler returns a map:

```clojure
{:assertion :rf.assert/path-equals
 :payload   [[:auth :status] :authenticated]
 :passed?   true
 :actual    :authenticated
 :expected  :authenticated
 :source    {:file "..." :line ...}}             ; source-coord stamped at macro-expansion
```

The play-runner concatenates these into the variant frame's `:rf.story/assertions` vector. `assertions-passing?` over the final vector projects to a single boolean for `run-variant` callers; the test-runner adapter walks each entry to drive `cljs.test/is` reporting.

## `:rf.assert/effect-emitted` payload shape

The only assertion whose payload carries an **optional second slot**. Both shapes are legal:

- **`[fx-id]`** — passes iff `fx-id` was emitted at least once during phase-4 (the variant's frame accumulates emitted fx-ids into its per-frame `:emitted-fx` slot).
- **`[fx-id pred]`** — passes iff `fx-id` was emitted **and** `(pred fx-id)` returns truthy. `pred` is a unary fn over the matched fx-id keyword; exceptions thrown by `pred` count as a `false` return.

The pred slot is deliberately a unary fn over the fx-id keyword, not over the fx-args map. The play-runner's emitted-fx accumulator tracks **which fx-ids fired**, not the per-call fx-args payload. Authors who need an argument-level assertion compose two checks: an `:rf.assert/effect-emitted` for the fx-id (set membership) plus an `:rf.assert/path-equals` against the slot in `app-db` the fx writes through.

## Record-don't-throw semantics

Every `:rf.assert/*` event records into `:rf.story/assertions` and continues the play. A failing `:rf.assert/path-equals` does NOT abort the script — phase-4 walks every remaining step, accumulates every assertion record, and the test-runner adapter asks "did every entry pass?" at the end. A play sequence with eight assertions where three fail still runs all eight; you get the full picture from a broken variant, not a stack trace and a single failure.

The discipline diverges from Storybook (which throws on the first assertion failure). Storybook's choice is constrained by JavaScript's async-throw mess; re-frame2's run-to-completion drain gives Story room to do better.

Worked example — a counter variant whose play asserts both the dispatch trace and the resulting `app-db`:

```clojure
(story/reg-variant :story.counter/clicked-three-times
  {:doc         "Counter after three increments from zero."
   :events      [[:counter/initialise 0]]
   :play-script [[:dispatch-sync [:counter/inc]]
                 [:dispatch-sync [:counter/inc]]
                 [:dispatch-sync [:counter/inc]]
                 [:dispatch-sync [:rf.assert/path-equals [:count] 3]]
                 [:dispatch-sync [:rf.assert/dispatched? [:counter/inc]]]]
   :tags        #{:dev :docs :test}
   :substrates  #{:reagent}})
```

A subtle but load-bearing detail: the three `:counter/inc` events live in `:play-script`, not `:events`. The `:rf.assert/dispatched?` accumulator is only wired during phase-4 — if the increments were in `:events` (phase-2 setup), the dispatch-trace listener wouldn't have observed them and the assertion would fail. The general rule for hand-authored variants: **assertions about dispatches only see what happens during play, not what happens during setup.**

## Privacy posture

`:rf.assert/*` records build `:actual` / `:expected` / `:payload` slots through `re-frame.elision/elide-wire-value` before landing in `:assertions`. If a variant declared per-frame marks via `(re-frame.core/add-marks <variant-id> {path mark, ...})` or `set-marks`, then a `:rf.assert/path-equals [:auth :token] :rf/redacted` lookup against a path-marked-sensitive slot records `:actual :rf/redacted`, NOT the raw value.

The sentinel literal is a legal `:expected` value. Authors write the `:rf/redacted` sentinel directly into the assertion to pin the redaction contract:

```clojure
(re-frame.core/add-marks :story.auth/login {:auth.token :sensitive})

(story/reg-variant :story.auth/login
  {:events      [[:auth/login {:user "alice" :password "..."}]]
   :play-script [[:dispatch-sync [:rf.assert/path-equals [:auth :token] :rf/redacted]]]})
```

A passing assertion proves the observation surface saw a sentinel, not the secret. The display contract (the `:test` mode pane and the `[data-test="story-test-row-detail"]` disclosure) matches Causa's posture: a disclosure that revealed the underlying value would be non-conformant.

## The recorder facade

Story's canvas recorder captures dispatched events and DOM-level interactions into a paste-ready `:play-script` body. The facade exposes six entries on `re-frame.story`.

| Fn | Signature | Status | Intuition |
|---|---|---|---|
| `start-recording!` | `(start-recording! variant-id)` → nil | v1 (dev-only) | Begin recording dispatched events against `variant-id`'s frame. The recorder filters on dispatch-provenance `:rf.story/source :user` — setup-phase events (`:variant-events`) are excluded. |
| `stop-recording!` | `(stop-recording!)` → events-vec | v1 (dev-only) | Stop the in-flight recording; return the captured events vector. |
| `clear-recording!` | `(clear-recording!)` → nil | v1 (dev-only) | Drop the buffer + return the recorder to idle. |
| `recording?` | `(recording?)` → bool | v1 (dev-only) | Predicate — is a recording in flight? |
| `recorder-state` | `(recorder-state)` → map | v1 (dev-only) | Read-only view of the current recorder state map. |
| `gen-play-snippet` | `(gen-play-snippet events opts)` → string | v1 (dev-only) | Pure codegen: render a captured `events` vector as a `(reg-variant <id> {... :play-script {:script [...]}})` EDN snippet. Each captured event vector is wrapped as `[:dispatch-sync <event-vec>]`. |

The facade's `gen-play-snippet` is the simpler event-vector → `:dispatch-sync` step projection. A typical recorder modal calls `start-recording!` on the *record* chip click, hooks the user's canvas interaction, then on *stop* calls `stop-recording!` + `gen-play-snippet` to render the modal's EDN body.

### Rich DSL recorder — out of facade

The richer DOM-capture-aware translator (tagged `:click` / `:type` / `:wait` steps derived from the recorder's `:entries` capture stream) is exported by `re-frame.story.recorder.play-export` — a sub-namespace, not re-exported through `re-frame.story`. The facade exposes only `gen-play-snippet`; consumers wanting the rich DOM-derived DSL `:require` the sub-namespace directly.

| Fn | Signature | Status | Intuition |
|---|---|---|---|
| `recording->play-script` | `(recording->play-script entries opts)` → map | v1 (dev-only) | Translate captured `:entries` into a normalised `:play-script` body map. Derives `[:click selector]`, `[:type selector text]`, `[:wait ms]` steps from the entries stream. |
| `render-play-script` | `(render-play-script body)` → string | v1 (dev-only) | Render the `:play-script` map to EDN. |
| `render-variant-form` | `(render-variant-form variant-id metadata)` → string | v1 (dev-only) | Render a full `(reg-variant <id> {...})` form to EDN. |

Lives in `re-frame.story.recorder.play-export`. Two `:require`s — `[re-frame.story :as story]` for the facade plus `[re-frame.story.recorder.play-export :as play-export]` for the rich translator.

## A complete worked example

```clojure
;; 1. Register the variant with a hand-authored play script.
(story/reg-variant :story.login/error-then-recovery
  {:doc         "User enters wrong password, then corrects it."
   :events      [[:auth/initialise]]
   :play-script [[:type "[data-test='username']" "alice"]
                 [:type "[data-test='password']" "wrong"]
                 [:click "[data-test='submit']"]
                 [:wait 50]
                 [:assert-dom "[data-test='error']" :visible]
                 [:assert-dom "[data-test='error']" :text "Incorrect password."]
                 [:type "[data-test='password']" "correct"]
                 [:click "[data-test='submit']"]
                 [:dispatch-sync [:rf.assert/path-equals [:auth :status] :authenticated]]
                 [:dispatch-sync [:rf.assert/effect-emitted :http]]
                 [:dispatch-sync [:rf.assert/no-warnings]]]
   :tags        #{:dev :test}})

;; 2. The Tests mode tab auto-runs the script on variant mount;
;;    the sidebar dot flips green when all 11 step records pass.
```

The script mixes DOM gestures (`:type` / `:click`), explicit dispatches (`:dispatch-sync`), DOM-shape assertions (`:assert-dom`), framework assertions (`:rf.assert/path-equals`, `:rf.assert/effect-emitted`, `:rf.assert/no-warnings`), and a single `:wait`. Each row records into `:rf.story/assertions`; the *Tests* tab's auto-run reports the count.

## See also

- [Registration](registration.md) — the `reg-variant` macro the `:play-script` slot lives on. The `force-fx-stub-id` decorator that mocks fx-handlers for assertion-friendly scripts.
- [Runtime](runtime.md) — the four-phase lifecycle (`:loaders` → `:events` → render → `:play-script`); `read-assertions` / `assertions-passing?` for post-run inspection.
- [MCP surface](mcp-surface.md) — the gated agent-write path that calls `reg-variant*` with a generated `:play-script` body.
- [Story tutorial — Recorder + Test Codegen](../03-recorder-codegen.md) — record a canvas interaction end-to-end; the hero chapter.
- [Story tutorial — Time-travel in Story](../06-time-travel.md) — Causa embedded in the RHS, scoped per variant frame; pairs with the `:rf.assert/state-is` machine-state assertion.
- [Framework API — Schemas and data classification](../../api/08-schemas.md) — `add-marks` / `set-marks`, the path-mark primitives `:rf.assert/*` records elide through.
