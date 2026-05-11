(ns re-frame.interop
  "CLJS host primitives. Everything platform-specific lives here.

  See Spec 002 §Interop layer and Spec 005 §Interop layer.

  All other namespaces depend only on these abstractions, not on
  goog/reagent/js* directly. A non-CLJS port replaces this file; the rest
  of the runtime is host-agnostic in shape.

  Reactive-substrate surfaces (`ratom`, `ratom?`, `make-reaction`,
  `add-on-dispose!`, `dispose!`, `reactive?`, `after-render`) dispatch
  through the late-bind hook table per rf2-s36l. The active adapter
  publishes its substrate's impls at ns-load time:

    :adapter/ratom            — (fn [v])         → ratom
    :adapter/ratom?           — (fn [x])         → boolean
    :adapter/make-reaction    — (fn [f])         → reaction
    :adapter/add-on-dispose!  — (fn [a-ratom f]) → nil
    :adapter/dispose!         — (fn [a-ratom])   → nil
    :adapter/reactive?        — (fn [])          → boolean
    :adapter/after-render     — (fn [f])         → nil

  Before rf2-s36l this ns statically `:require`d `reagent.core` /
  `reagent.ratom` and forwarded straight to stock Reagent. That hard-
  coupled every CLJS app — including those built on the slim
  (`reagent-slim`), UIx, or Helix adapters — to stock Reagent's
  protocols at every reaction/disposal call site. The slim adapter's
  `reagent2.ratom/Reaction` does NOT reify stock Reagent's
  `IDisposable`, so the first `(interop/add-on-dispose! ...)` call
  under a slim-installed app threw a protocol-dispatch error
  (counter-slim-and-fast Playwright smoke, parked by PR #305).

  After rf2-s36l: each adapter ns publishes its own per-fn hook set
  at load time. The classic Reagent bridge wires the hooks to
  `reagent.core/atom`, `reagent.ratom/make-reaction`, etc.; the slim
  adapter wires them to `reagent2.*` equivalents; UIx and Helix —
  which reify stock `reagent.ratom/IDisposable` on their derived
  values — wire to stock Reagent.

  Each adapter's hook installation routes through
  `(substrate-adapter/current-adapter)` per rf2-0d35: the hook runs
  the adapter's impl only when that adapter is the installed one and
  otherwise chains to the previously-registered reader. This keeps
  test bundles (which load multiple adapter ns's at once) honest about
  which substrate is active.

  Per the bundle-size note in rf2-5lbx R-007 and rf2-s36l: once this
  ns stops statically `:require`ing `reagent.core` / `reagent.ratom`,
  the slim Maven artefact (`day8/reagent-slim`) can drop its stock-
  Reagent dep entirely and downstream consumers depending on
  `reagent-slim` alone gain the ~25 KB gz saving. The in-tree
  shadow-cljs build still pulls all adapter trees (the monorepo
  configuration coexists them), so the in-tree bundle sizes do not
  change — that's expected and fine; the bundle-comparison test
  (rf2-51x5 / rf2-5lbx) is structural, not size-comparative."
  (:require [goog.async.nextTick]
            [re-frame.late-bind :as late-bind]))

;; ---- next-tick scheduling -------------------------------------------------

(def next-tick goog.async.nextTick)

;; ---- after-render hook ----------------------------------------------------

(defn after-render
  "Schedule f to run after the next render. Dispatches through the
  active adapter's `:adapter/after-render` hook (rf2-s36l). Returns nil
  when no adapter has registered the hook."
  [f]
  (when-let [hook (late-bind/get-fn :adapter/after-render)]
    (hook f)))

;; ---- mutable cells (used by the runtime, opaque to user code) -------------

(defn ratom
  "Construct a reactive atom seeded with v. Dispatches through the active
  adapter's `:adapter/ratom` hook (rf2-s36l)."
  [v]
  (when-let [hook (late-bind/get-fn :adapter/ratom)]
    (hook v)))

(defn ratom?
  "True if x is a reactive atom (per the active adapter's substrate).
  Dispatches through `:adapter/ratom?` (rf2-s36l); returns false when
  no adapter has registered the hook."
  [x]
  (if-let [hook (late-bind/get-fn :adapter/ratom?)]
    (hook x)
    false))

;; ---- reactions ------------------------------------------------------------

(defn make-reaction
  "Build a reaction that recomputes f when its dependencies change.
  Dispatches through `:adapter/make-reaction` (rf2-s36l)."
  [f]
  (when-let [hook (late-bind/get-fn :adapter/make-reaction)]
    (hook f)))

(defn add-on-dispose!
  "Register a teardown callback on a-ratom. Dispatches through
  `:adapter/add-on-dispose!` (rf2-s36l)."
  [a-ratom f]
  (when-let [hook (late-bind/get-fn :adapter/add-on-dispose!)]
    (hook a-ratom f)))

(defn dispose!
  "Tear down a reactive atom / reaction. Dispatches through
  `:adapter/dispose!` (rf2-s36l)."
  [a-ratom]
  (when-let [hook (late-bind/get-fn :adapter/dispose!)]
    (hook a-ratom)))

(defn reactive?
  "True when called from inside a reactive context (e.g. a render).
  Dispatches through `:adapter/reactive?` (rf2-s36l); returns false
  when no adapter has registered the hook."
  []
  (if-let [hook (late-bind/get-fn :adapter/reactive?)]
    (hook)
    false))

;; ---- timers ---------------------------------------------------------------

(defn set-timeout!
  "Schedule f to run after ms milliseconds. Returns an opaque handle."
  [f ms]
  (js/setTimeout f ms))

(defn clear-timeout!
  "Cancel a previously-scheduled timer."
  [handle]
  (js/clearTimeout handle))

;; ---- clock ----------------------------------------------------------------

(defn now-ms []
  (if (and (exists? js/performance) (exists? js/performance.now))
    (js/performance.now)
    (js/Date.now)))

;; ---- queue primitives -----------------------------------------------------

(def empty-queue #queue [])

;; ---- platform marker ------------------------------------------------------

(def platform :client)

;; ---- compile-time constants -----------------------------------------------

(def ^boolean debug-enabled? "@define {boolean}" ^boolean goog/DEBUG)
