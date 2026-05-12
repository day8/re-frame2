(ns re-frame.story.ui.cofx
  "Coeffect + subscription wiring for the chrome-level toolbar's
  `:active-modes` slot. Per spec/010 §`with-mode` accessor — the
  three caller patterns:

  1. Story code at registration time uses the `:args` deep-merge —
     nothing to expose.
  2. Event-handler code reads via `:story/active-modes` /
     `:story/active-args` cofx.
  3. Reagent view code subscribes via `[:story/active-modes]` /
     `[:story/active-args]`.

  The cofx values + the subscription source both read off
  `re-frame.story.ui.state/shell-state-atom` — the same slot the
  toolbar writes. JVM-side the atom is a plain `clojure.core/atom`;
  CLJS-side it's a `reagent.core/atom`, so the subscription propagates
  through Reagent's signal graph the way every other shell sub does.

  ## Generalisation note

  Spec/010 §`with-mode` accessor §Single-mode call sites — the spec
  formerly carried `:story/mode` (singular) but no implementation ever
  registered it. This is the replacement landing point — the cofx
  pair lands here as `:story/active-modes` (plural) + `:story/active-
  args`. Pre-alpha, no shim for the singular name (per AGENTS.md
  `pre-alpha-no-back-compat`).

  ## Registration

  `install-canonical-cofx!` is called from
  `re-frame.story/install-canonical-vocabulary!`. Idempotent — re-
  registering with the same body replaces the slot atomically."
  (:require [re-frame.core :as rf]
            [re-frame.story.args :as args]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.state :as state]))

(defn active-modes-snapshot
  "Return the current `:active-modes` vector from the shell state.
  Pure data → data when paired with the shell-state-atom deref."
  []
  (vec (or (:active-modes (state/get-state)) [])))

(defn- mode-args
  "Lookup the `:args` map for `mode-id`, or `{}` if the mode is
  unregistered. Mirrors `re-frame.story.args/mode-args` (private)."
  [mode-id]
  (or (:args (registrar/handler-meta :mode mode-id)) {}))

(defn active-args-snapshot
  "Return the deep-merged `:args` from every active mode, in the order
  the modes were activated. Mirrors `re-frame.story.args/resolve-args`'
  modes-layer composition. Pure data → data."
  []
  (args/deep-merge-all (map mode-args (active-modes-snapshot))))

(defn install-canonical-cofx!
  "Register the three canonical Story cofx + the matching subs. Per
  spec/010 §`with-mode` accessor:

  - `:story/active-modes` — vector of mode ids currently active.
  - `:story/active-args`  — deep-merge of all active modes' `:args`.

  Both back off `re-frame.story.ui.state/shell-state-atom` — the same
  slot the chrome-level toolbar writes."
  []
  (rf/reg-cofx
    :story/active-modes
    (fn [coeffects _]
      (assoc coeffects :story/active-modes (active-modes-snapshot))))

  (rf/reg-cofx
    :story/active-args
    (fn [coeffects _]
      (assoc coeffects :story/active-args (active-args-snapshot))))

  ;; Subscriptions backed by the shell-state-atom. The atom is a
  ;; Reagent ratom on CLJS, plain atom on JVM — deref participates in
  ;; Reagent's signal graph in the browser, returns a snapshot value
  ;; on the JVM (no reaction wrapper, matching spec/010's note).
  (rf/reg-sub
    :story/active-modes
    (fn [_ _]
      (vec (or (:active-modes @state/shell-state-atom) []))))

  (rf/reg-sub
    :story/active-args
    (fn [_ _]
      (active-args-snapshot))))
