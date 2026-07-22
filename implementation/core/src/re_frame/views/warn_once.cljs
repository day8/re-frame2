(ns re-frame.views.warn-once
  "Warn-once cache and detection helpers for the Reagent-side views ns.
  Re-frame.views re-exports the publicly-referenced surface
  (`clear-warned-non-dom-roots!`).

  One warn-once cache lives here: the non-DOM-root warning (Spec 006
  §Source-coord annotation, documented exemption). When a reg-view'd
  component returns any non-nil root that cannot carry DOM attributes, the
  source-coordinate walk skips annotation and emits one console warning per
  id. `make-reset-runtime-fixture` clears the process-wide cache through the
  chained `:adapter/clear-warn-once-caches!` hook."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.late-bind :as late-bind]))

;; ---- non-DOM-root warning ------------------------------------------------

(defonce ^:private warned-non-dom-roots (atom #{}))

(defn clear-warned-non-dom-roots!
  "Reset the warn-once cache for non-DOM-root warnings. Tests use this
  between cases (via `make-reset-runtime-fixture` and the chained
  `:adapter/clear-warn-once-caches!` hook) so a sibling test's first-
  encounter warning cannot silently swallow a later test's same-id
  warning. The cache is a process-wide `defonce` so the user-facing
  warn-once UX is unchanged in production; test-time clearing is the
  only effect."
  []
  (reset! warned-non-dom-roots #{})
  nil)

(defn warn-non-dom-root!
  "Emit a one-shot warning per id that the reg-view'd component returned
  a non-DOM root. Pair tools fall back to the registry's `:rf/id`;
  documented exemption per Spec 006 §Source-coord annotation."
  [id head]
  (when-not (contains? @warned-non-dom-roots id)
    (swap! warned-non-dom-roots conj id)
    (when (exists? js/console)
      ;; nil substrate-name marks the Reagent Hiccup-walk path, which carries
      ;; no substrate qualifier in the shared warning text.
      (.warn js/console
        (adapter-context/non-dom-root-warning id head nil)))))

;; Enrol the `warned-non-dom-roots` cache into the chained
;; `:adapter/clear-warn-once-caches!` hook, via the canonical governance
;; chokepoint `late-bind/register-warn-once-clear-fn!`. Each
;; adapter (uix), the slim hiccup interpreter, re-frame.views, and
;; this ns contribute a clear-step; `make-reset-runtime-fixture` invokes
;; the top of the chain and every contributor's reset runs. The chokepoint
;; also records the cache in the warn-once-clear governance registry so the
;; governance assertion proves the chain wipes it.
(late-bind/register-warn-once-clear-fn!
  {:label    :views/warned-non-dom-roots
   :clear-fn clear-warned-non-dom-roots!
   :arm      (fn [] (swap! warned-non-dom-roots conj ::governance-sentinel))
   :armed?   (fn [] (contains? @warned-non-dom-roots ::governance-sentinel))})
