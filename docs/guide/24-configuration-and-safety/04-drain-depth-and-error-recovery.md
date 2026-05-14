# 24.04 — Drain depth and error recovery

## TL;DR

re-frame2's run-to-completion drain has a ceiling. A handler that dispatches itself, or a cascade that bounces back and forth between two machines without ever settling, will eventually trip the ceiling. When it does, the drain aborts **atomically** — `app-db` is rolled back to its pre-cascade snapshot — and the failure surfaces as `:rf.error/drain-depth-exceeded` on your frame's `:on-error` policy. No partial writes, no half-applied cascades, no "the third event in the chain got through but the fourth didn't." This page covers the ceiling, the rollback, the tuning knob, and how it composes with the per-frame `:on-error` story.

[Chapter 06a](../06a-frames.md) names `:drain-depth` as a frame-metadata key in passing. [Chapter 14](../14-errors.md) names `:rf.error/drain-depth-exceeded` as a row in the error taxonomy. This page is the *why* — the threat the ceiling defends against, the semantics of the rollback, and the integration with `:on-error`.

## The threat — recursive-cascade DoS

re-frame's run-to-completion drain is the property that makes event handlers easy to reason about: a dispatched event runs end-to-end before any other event is observed, including events that dispatch other events. A handler returning `{:fx [[:dispatch [:next-step]]]}` queues the dispatched event in the drain's FIFO; the drain processes it as part of the same cascade.

The problem is that nothing structural prevents that cascade from going on forever. A handler that dispatches itself (`{:fx [[:dispatch [:current-event]]]}`) loops. Two handlers that dispatch each other ping-pong. A state machine with an `:always` transition whose guard is somehow always true microstep-loops. Without a ceiling, any of these would consume the drain loop indefinitely — the JavaScript event loop blocks, the JVM thread spins, the UI freezes, the SSR request hangs.

The ceiling makes that into a bounded failure rather than an unbounded freeze. **Recursive cascades fail; they don't hang.** That's the load-bearing property.

## The ceiling — `:drain-depth`

Every frame carries a `:drain-depth` setting. The default is **100** — comfortably more than any legitimate cascade (a single user action typically dispatches between 1 and 10 events; the worst legitimate case is a complex boot sequence that fans out to maybe 30). 100 is two orders of magnitude above legitimate; well below "the dev's editor froze."

You tune per frame:

```clojure
(rf/reg-frame :auth
  {:on-create  [:auth/initialise]
   :drain-depth 100})            ;; the framework default — written explicitly

(rf/reg-frame :test-fixture
  {:drain-depth 1000             ;; tests that deliberately fire long sagas
   :on-create  [:test/setup]})

(rf/reg-frame :story-variant
  {:preset      :story
   :drain-depth 16})             ;; story preset's default — fail fast in interactive demos
```

The `:test` and `:story` presets ship with their own defaults (1000 and 16 respectively). Tests legitimately deliberately exercise long sagas; story variants are interactive demos where a runaway cascade should fail fast under a story rather than spinning up to the production limit and confusing the demo.

You can also tune at dispatch-time if you've got a specific cascade that legitimately wants more headroom for one call:

```clojure
(rf/dispatch [:bulk-import-large-csv] {:drain-depth 500})
```

Per-call overrides merge over per-frame metadata; the call-site value wins.

## The rollback — atomic, complete

When the cascade hits the ceiling, the runtime does **three** things, in order:

1. **Restore the pre-drain `app-db` snapshot.** Whatever state the frame was in before the cascade began is the state it's in after the abort. The drain's partial writes — every event that did successfully run before the ceiling tripped — are discarded.
2. **Restore frame-local registrations** that the cascade made. A handler that ran `(rf/dispatch [:rf.machine/spawn ...])` inside the cascade — which registered a frame-local handler at the spawned actor's `[:rf/machines <id>]` slot — has that registration reverted along with the `app-db` rollback. Otherwise an aborted drain would leave orphaned handlers attached to a frame at a value that never references them.
3. **Surface the failure.** `:rf.error/drain-depth-exceeded` is emitted with `:tags {:depth :queue-size :last-event :rollback? true}` and routed through your frame's `:on-error` policy.

The remaining queued events — the ones the cascade hadn't yet reached when the ceiling tripped — are discarded. The epoch buffer ([chapter 15](../15-devtools-and-pair-tools.md)) records nothing for the failed drain. The frame is at the last settled state, which is always reachable by replay.

This is the "events are atomic" principle scaled up to the cascade boundary. A handler is atomic with respect to its own side effects ([ch.04](../04-events-state-cycle.md)); a *cascade* is atomic with respect to depth-exceeded aborts. If you've thought about events as "either all the effects happen or none of them do," the same model now applies to cascades: either the whole cascade settles or it's rolled back.

The rollback boundary is **value-shape**, not "rewind real-world side effects." Out-of-band side effects that already committed to external substrates — an HTTP request that flew, a `dispatch-later` timer that scheduled — are not undone. The framework can't unsend a request; what it *can* do is keep its own state consistent so your replay path has somewhere honest to start from.

## The integration — `:on-error`

`:rf.error/drain-depth-exceeded` arrives at your frame's `:on-error` policy like any other error category ([chapter 14](../14-errors.md)). Three things you can do with it:

```clojure
(rf/reg-frame :auth
  {:on-create [:auth/initialise]
   :on-error
   {:default                       :log
    :rf.error/drain-depth-exceeded :halt}})        ;; opinionated — stop, don't try to recover
```

The three policies that make sense for this specific category:

- **`:log`** — emit the error to your observability surface and move on. The frame is at the pre-cascade state; the next dispatched event will run. This is the right policy for *unexpected* cascades — a bug you want to surface and fix, not a system you want to halt over.

- **`:halt`** — emit and stop processing further events on this frame. Useful for fixtures where any drain overflow is a test failure and there's no point letting subsequent events run on a frame whose dynamic story has gone sideways. Story variants (`:preset :story`) lean toward this; production frames lean toward `:log`.

- **A custom handler** — `(fn [error] ...)` that does whatever your app needs. Reset a known-good state, log to a specific channel, fire a side-effect that pages an on-call human. The handler runs after the rollback, so your code is starting from the pre-cascade state — not from the half-applied middle.

The default (no `:on-error` registered for the category) is `:log`. The frame stays alive; the next event will drain normally.

See [014's `:rf.error/drain-depth-exceeded` row](../../spec/009-Instrumentation.md) and [Chapter 14 §Frame-scoped error policy](../14-errors.md) for the broader `:on-error` story.

## Tuning checklist

Default `:drain-depth 100` is right for almost every frame. Cases for tuning:

- **Bump up** if you've got a frame that legitimately fans out deep cascades — bulk-import flows, multi-step migrations, test fixtures that exercise long sagas. 500 or 1000 are typical bumped values.

- **Bump down** if you've got an interactive surface (a story variant, a dev sandbox) where any runaway cascade should fail fast rather than waste a user's clock on a runaway loop. `:preset :story` defaults to 16 for exactly this reason.

- **Don't bump in production unless you've audited why your cascade is long.** A production frame routinely hitting 50+ depth is a code smell — usually a state machine ping-ponging, or an unintended self-dispatch. Bumping the ceiling masks the design issue; fixing the dispatch loop is the right move.

If you're not sure what depth your cascades typically reach, the trace surface tells you: every successful drain records its depth as part of the per-cascade trace ([ch.15](../15-devtools-and-pair-tools.md)). Check the trace for your typical user actions; pick a ceiling at 5x the observed maximum.

## A note on `:always`-depth and `:raise`-depth

Inside a state machine, `:always` (eventless transitions) and `:raise` (action-scoped re-dispatch) each have their own depth ceilings — independent of the outer drain. Their defaults are both **16**, and they emit their own error categories (`:rf.error/machine-always-depth-exceeded`, `:rf.error/machine-raise-depth-exceeded`). The next page covers `:always` and friends in detail; the depth ceilings on them are part of the same defense-in-depth shape as the outer drain. Three independent depth counters, three independent ceilings, three independent abort paths — each catching the runaway-loop case at its own layer.

## Cross-references

- [Chapter 06a — Frames](../06a-frames.md) — `:drain-depth` as a frame-metadata key.
- [Chapter 14 — Errors](../14-errors.md) — the full `:on-error` story.
- [Chapter 15 — Tooling](../15-devtools-and-pair-tools.md) — the trace surface that lets you observe drain depth in legitimate cascades.
- [Spec 002 — Frames §Drain loop](../../spec/002-Frames.md) — the normative description of the depth-limited drain and the atomic rollback.
- [Security.md §DoS by input](../../spec/Security.md#dos-by-input) — drain-depth as one of the bounded-resource defenses.
- [§06 — Machine substrate features](06-state-machine-substrate-features.md) — `:always`-depth and `:raise`-depth.
