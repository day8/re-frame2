(ns re-frame.views.warn-once
  "Warn-once cache and detection helpers for the Reagent-side views ns.
  Re-frame.views re-exports the publicly-referenced surface
  (`clear-warned-non-dom-roots!`).

  One warn-once cache lives here: the non-DOM-root warning (Spec 006
  §Source-coord annotation, documented exemption). When a reg-view'd
  component returns a non-DOM root (fn/class component or React Fragment)
  the source-coord walk skips the annotation and emits a one-shot
  console.warn per id. The `warned-non-dom-roots` defonce holds the
  per-process set; `make-reset-runtime-fixture` clears it via the chained
  `:adapter/clear-warn-once-caches!` hook (enrolled through the governance
  chokepoint `register-warn-once-clear-fn!`, rf2-z79p8)."
  (:require [re-frame.late-bind :as late-bind]))

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
  a non-DOM root (a fn/class component, or a React Fragment). Pair tools
  fall back to the registry's `:rf/id`; documented exemption per Spec 006
  §Source-coord annotation."
  [id head]
  (when-not (contains? @warned-non-dom-roots id)
    (swap! warned-non-dom-roots conj id)
    (when (exists? js/console)
      (.warn js/console
        (str "[re-frame] reg-view " id " — root element is "
             (pr-str head) "; data-rf2-source-coord skipped (Spec 006 "
             "§Source-coord annotation: pair tools fall back to :rf/id "
             "for non-DOM roots).")))))

;; Enrol the `warned-non-dom-roots` cache into the chained
;; `:adapter/clear-warn-once-caches!` hook, via the canonical governance
;; chokepoint `late-bind/register-warn-once-clear-fn!` (rf2-z79p8). Each
;; adapter (helix, uix), the slim hiccup interpreter, re-frame.views, and
;; this ns contribute a clear-step; `make-reset-runtime-fixture` invokes
;; the top of the chain and every contributor's reset runs. The chokepoint
;; also records the cache in the warn-once-clear governance registry so the
;; governance assertion proves the chain wipes it.
;;
;; (The retired `warned-plain-fn-frame-pairs` suppression cache — the
;; rf2-z79p8 4th straggler — was removed in rf2-k4xous: its warning
;; `:rf.warning/plain-fn-under-non-default-frame-once` is RETIRED per
;; EP-0002 (rf2-7yqn39 deleted the emit site, helper, hook, call site and
;; re-export, superseding it with the always-on `:rf.error/no-frame-context`
;; error). It was kept SOLELY as a governance test subject; the three live
;; probe-carrying caches — `warned-non-dom-roots` here, `seen-render-keys`
;; in re-frame.views, and the spine's per-adapter source-coord cache — still
;; exercise the arm/fire/assert-empty governance proof, so removing it loses
;; zero coverage.)
(late-bind/register-warn-once-clear-fn!
  {:label    :views/warned-non-dom-roots
   :clear-fn clear-warned-non-dom-roots!
   :arm      (fn [] (swap! warned-non-dom-roots conj ::governance-sentinel))
   :armed?   (fn [] (contains? @warned-non-dom-roots ::governance-sentinel))})
