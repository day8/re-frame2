(ns re-frame.story.play.substrate-boundary
  "The `:settled-boundary-hooks` PRODUCER — the adapter-aware caller the
  `settled-boundary` contract has always been written against
  (`re-frame.story.play.settled-boundary` §The flush-hook seam).

  ## What was missing

  `settled-boundary` names a ladder (`:headless` → `:cljs-reactive` →
  `:dom` → `:browser`) and takes its richer flushes from a hooks map the
  host registers under the `:settled-boundary-hooks` late-bind slot. The
  CONSUMER shipped (`runner-events/current-flush-hooks`); no producer
  ever did. Every host therefore ran at `:provides :headless`, whose only
  flush is a no-op — so the live browser shell had no way to ask the
  substrate to commit a render, and a play's real settle after an
  interaction was the run-loop's `setTimeout` 0 yield (rf2-ek9qb).

  A macrotask yield drains the MICROTASK queue, which is the right
  instrument for a host that commits inside `flushSync` or on a promise.
  It is the wrong one for the reference substrates, which schedule
  re-renders on a `requestAnimationFrame`-style tick — `setTimeout` 0
  fires BEFORE the next frame, and in a backgrounded or headless tab that
  frame is throttled to ~never (`re-frame.substrate.adapter/flush-render!`
  §Why it exists). So the yield raced the render, and raced it hardest
  exactly where the machine was busiest.

  ## The settle signal, and why it is not `reagent.core/flush`

  The framework already owns the substrate-neutral answer:
  **`flush-render!`**, the optional adapter-contract fn that commits
  pending renders through the substrate's SYNCHRONOUS-commit path
  (`flushSync` for the React-hook substrates, `reagent.core/flush` for the
  ratom family) rather than the rAF-scheduled tick — Spec 006
  §`flush-render!`, and the primitive `spec/Tool-Pair.md` §Driving the
  render builds its own `dispatch-and-settle` op on.

  Tool-Pair is explicit that a tool MUST reach it through the LIVE
  adapter, \"never a hardcoded substrate API (the framework knows its own
  registered adapter; a tool that names `reagent.core/flush!` is
  non-conforming and breaks under UIx)\". This ns therefore names no
  substrate at all: it reads `:flush-render!` off
  `re-frame.core/current-adapter-spec` — the introspection surface Spec
  006 documents for exactly this purpose (\"give me the adapter fns to
  call — tools\"). Story consequently settles correctly under Reagent,
  reagent-slim and UIx today, and under any adapter added later, with no
  entry here per substrate and no substrate on Story's classpath.

  ## What it declares

  `:provides :dom` when — and only when — an adapter is seated AND ships
  `:flush-render!`. That is an honest reading of the ladder: `flush-render!`
  commits the pending renders to the DOM before it returns, which is the
  `:dom` rung's guarantee. An adapter with no live commit (plain-atom,
  SSR) ships no `:flush-render!`, and a bare JVM run seats no adapter at
  all; both fall back to `headless-flush-hooks`, so nothing about the
  headless floor changes and the fail-closed ladder still refuses a step
  it cannot honour.

  `:dispatch!` stays `drain-sync!` — the framework's run-to-fixed-point
  drain is the same at every rung, and the richer rungs add the render
  commit on top of it rather than replacing it."
  (:require [re-frame.core :as rf]
            [re-frame.story.late-bind :as rf.story.late-bind]
            [re-frame.story.play.settled-boundary :as rf.story.play.settled-boundary]))

(defn adapter-flush-render
  "The installed adapter's optional `:flush-render!` contract fn, or nil.

  Nil covers all three of \"no adapter seated\" (a bare JVM run, or before
  `rf/init!`), \"adapter seated but shipping no live commit\" (plain-atom,
  SSR) and \"the adapter is mid-dispose\" — `current-adapter-spec` already
  returns nil for the last. Tolerant: a throwing host reads as nil rather
  than taking the play down, which matches every other Story probe of
  framework state.

  The returned fn takes a THUNK (`(fn [f] …)`, per the Spec 006 contract
  shape): it runs `f` and then commits, so the 0-arity \"flush what is
  already pending\" form is `(flush-render (fn [] nil))`."
  []
  (try
    (:flush-render! (rf/current-adapter-spec))
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn substrate-flush-hooks
  "Build the flush-hooks map for `frame-id` off the LIVE adapter.

  This is the fn registered under `:settled-boundary-hooks`; the runner
  calls it per step (`runner-events/current-flush-hooks`), so an adapter
  installed, swapped or disposed mid-session is picked up on the next
  step rather than frozen at boot.

  With a `:flush-render!` in hand the hooks declare `:provides :dom` and
  register the SAME synchronous commit at both richer rungs. That is not
  a doubled flush in any meaningful sense: `flush-render!` is documented
  no-op-safe, so the second call over already-committed work returns
  having done nothing. Registering it at both is the honest reading —
  each rung genuinely re-establishes its own guarantee, and a step
  requiring only `:cljs-reactive` gets the reaction flush without being
  told it got a DOM commit it did not ask for.

  Without one, the headless default — unchanged behaviour, and the
  reason installing this producer is safe on every host."
  [_frame-id]
  (if-let [flush-render (adapter-flush-render)]
    (let [commit! (fn substrate-commit [_frame-id]
                    (flush-render (fn [] nil))
                    nil)]
      {:provides  :dom
       :dispatch! rf.story.play.settled-boundary/drain-sync!
       :flush!    {:headless      (fn headless-flush [_frame-id] nil)
                   :cljs-reactive commit!
                   :dom           commit!}})
    rf.story.play.settled-boundary/headless-flush-hooks))

(defn install!
  "Register `substrate-flush-hooks` under the `:settled-boundary-hooks`
  late-bind slot. Idempotent (`set-fn!` replaces the slot).

  Called from `re-frame.story.canonical`'s installer chain. A host with a
  richer boundary than this one can offer — a real browser runner that
  settles layout and paint, say — registers its own hooks after boot and
  wins the slot, which is what the late-bind seam is for."
  []
  (rf.story.late-bind/set-fn! :settled-boundary-hooks substrate-flush-hooks)
  nil)
