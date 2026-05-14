(ns re-frame.disposable
  "Re-frame-owned disposable protocol for substrate-side derived values
  that have no native reactive-atom primitive (UIx, Helix, and any
  future minimal-React-wrapper substrate).

  Background. Before rf2-jicu2 the substrate spine's `make-derived-value`
  reified `reagent.ratom/IDisposable` to satisfy the sub-cache's
  cross-substrate teardown contract (`re-frame.interop/add-on-dispose!`
  on the cached reaction at slot construction; `re-frame.interop/
  dispose!` at slot evict). That single require dragged ~9KB of
  `reagent.ratom` + `reagent.impl.batching` into every UIx-only and
  Helix-only bundle — code paths neither substrate ever executes —
  because the protocol's defining ns sat inside Reagent. The protocol
  shape itself is one defprotocol with two method slots; there is no
  Reagent-implementation surface a non-Reagent substrate needs.

  Resolution (rf2-ykqee Verdict B). Move the protocol home to core,
  re-frame-owned. The spine reifies this protocol on its derived value
  containers; UIx and Helix adapters wire `:adapter/add-on-dispose!`
  and `:adapter/dispose!` straight to the protocol fns; the
  `reagent.ratom` require is dropped from UIx and Helix entirely. The
  Reagent and reagent-slim adapters keep their substrate's IDisposable
  protocol on Reagent reactions but their adapter dispatchers route
  via a fall-through that checks BOTH this protocol AND Reagent's —
  so a spine-produced derived value that finds its way into a Reagent
  bundle's `interop/add-on-dispose!` call site disposes cleanly.

  Method naming. Single-leading-dash (`-add-on-dispose`, `-dispose`)
  follows CLJS convention for internal protocol methods: the protocol
  is a structural extension point, callers reach it through
  `re-frame.interop`'s thin wrappers (which in turn route through the
  late-bind `:adapter/*` hook table). Users do not call these directly."
  (:refer-clojure :exclude []))

(defprotocol IDisposable
  "Cross-substrate disposal protocol owned by re-frame. The
  cache-wiring contract per Spec 006 §subscription-cache: re-frame's
  per-slot evict logic calls `re-frame.interop/add-on-dispose!` which
  dispatches via the adapter's hook; substrates without a reactive-atom
  primitive (UIx, Helix) satisfy this protocol on their derived-value
  reifies. Substrates with their own IDisposable (Reagent,
  reagent-slim) leave their reaction objects alone and route through
  their existing protocol — see each adapter's `:adapter/add-on-dispose!`
  routing."
  (-add-on-dispose [this on-dispose-fn]
    "Register a 0-arg `on-dispose-fn` to fire when `this` is disposed.
     Multiple callbacks accumulate; they run in registration order at
     `-dispose` time.")
  (-dispose [this]
    "Tear down `this` synchronously: unwire any source watches and fire
     every registered on-dispose callback. Idempotent — a second
     `-dispose` is a no-op."))
