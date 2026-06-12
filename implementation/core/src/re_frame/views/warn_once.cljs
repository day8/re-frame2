(ns re-frame.views.warn-once
  "Warn-once caches and detection helpers for the Reagent-side views ns.
  Re-frame.views re-exports the publicly-referenced surface
  (`clear-warned-non-dom-roots!`, `clear-plain-fn-warned-pairs!`).

  Two warn-once caches live here:

  1. Non-DOM-root warning (Spec 006 §Source-coord annotation,
     documented exemption). When a reg-view'd component returns a
     non-DOM root (fn/class component or React Fragment) the source-
     coord walk skips the annotation and emits a one-shot
     console.warn per id. The `warned-non-dom-roots` defonce holds
     the per-process set; `make-reset-runtime-fixture` clears it via the
     chained `:adapter/clear-warn-once-caches!` hook.

  2. Plain-fn-under-non-default-frame suppression cache (`warned-plain-fn-
     frame-pairs`). The warning this cache once gated is RETIRED — see the
     section below — but the cache itself is retained as a governed member
     of the warn-once-clear chain (rf2-z79p8): it is one of the empirical
     example caches the warn-once-clear governance gate arms/fires/asserts
     against, proving the canonical `:adapter/clear-warn-once-caches!`
     chain wipes every enrolled cache between tests."
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

;; ---- plain-fn-under-non-default-frame warning — RETIRED (EP-0002) --------
;;
;; The `:rf.warning/plain-fn-under-non-default-frame-once` warning is RETIRED
;; (Spec 009 §Error event catalogue, the strikethrough row), superseded by the
;; always-on `:rf.error/no-frame-context` error per EP-0002 (rf2-69r7ui). The
;; old contract: a plain Reagent fn (not registered via `reg-view`, so without
;; the `^{:contextType frame-context}` metadata `reg-view` attaches) cannot
;; read the surrounding React-context frame, so its `(rf/subscribe ...)` /
;; `(rf/dispatch ...)` fell through to `:rf/default` — and a now-deleted helper
;; emitted a once-per-pair warning about the silent fall-through.
;;
;; Under EP-0002 there is NO `:rf/default` floor. A plain fn that cannot read
;; the context resolves to nil (the no-provider sentinel coerces to nil), and
;; `frame/require-current-frame!` — which `subs/subscribe` and the dispatch
;; envelope call BEFORE anything else — raises the always-on
;; `:rf.error/no-frame-context` and HALTS the operation. That loud error IS
;; the sharper diagnostic the warning used to soften. So the warning's firing
;; case (plain fn under a non-default provider, no `with-frame`) became
;; UNREACHABLE — the throw happened first — leaving the helper a structural
;; no-op contradicting the catalogue's RETIRED ruling. rf2-7yqn39 deleted the
;; dead emit site, the helper, its `:views/maybe-warn-plain-fn-under-non-
;; default-frame!` late-bind hook, the `subs/subscribe` call site, and the
;; `re-frame.views` re-export.
;;
;; The `warned-plain-fn-frame-pairs` suppression cache and its
;; `clear-plain-fn-warned-pairs!` clear-fn are RETAINED — not for the retired
;; warning, but as a governed member of the warn-once-clear chain (rf2-z79p8).
;; They enrol through `register-warn-once-clear-fn!` below so the warn-once-
;; clear governance gate (`re-frame.warn-once-clear-governance-{cljs-,}test`)
;; can arm/fire/assert that the canonical `:adapter/clear-warn-once-caches!`
;; chain wipes every enrolled cache between tests.

(defonce ^:private warned-plain-fn-frame-pairs
  ;; Set of `[component-id frame-id]` pairs. Retained as a governed member of
  ;; the warn-once-clear chain (rf2-z79p8); the warning it once gated is
  ;; retired (see the section above).
  (atom #{}))

(defn clear-plain-fn-warned-pairs!
  "Reset the warn-once suppression cache. Retained as the clear-fn the
  warn-once-clear governance chain (rf2-z79p8) drives — see the RETIRED
  section above and `register-warn-once-clear-fn!` below."
  []
  (reset! warned-plain-fn-frame-pairs #{})
  nil)

;; Publish the clear-fn through the late-bind hook table so re-frame.subs
;; (a leaf namespace, .cljc, JVM-runnable) and the governance chain can
;; reach it without statically requiring this CLJS-only ns. JVM builds
;; never load this file; the lookup returns nil there. Per re-frame.late-bind
;; §Hook keys.
(late-bind/set-fn! :views/clear-plain-fn-warned-pairs!
                   clear-plain-fn-warned-pairs!)

;; Enrol BOTH warn-once `defonce` caches this ns owns into the chained
;; `:adapter/clear-warn-once-caches!` hook, via the canonical governance
;; chokepoint `late-bind/register-warn-once-clear-fn!` (rf2-z79p8). Each
;; adapter (helix, uix), the slim hiccup interpreter, re-frame.views, and
;; this ns all contribute a clear-step; `make-reset-runtime-fixture`
;; invokes the top of the chain and every contributor's reset runs. The
;; chokepoint also records each cache in the warn-once-clear governance
;; registry so the governance assertion proves the chain wipes it.
;;
;;   1. `warned-non-dom-roots` — the rf2-4edk source-coord / non-DOM-root
;;      cache.
;;   2. `warned-plain-fn-frame-pairs` — the rf2-z79p8 4th straggler (its
;;      warning is now retired, but it stays a governed chain member).
(late-bind/register-warn-once-clear-fn!
  {:label    :views/warned-non-dom-roots
   :clear-fn clear-warned-non-dom-roots!
   :arm      (fn [] (swap! warned-non-dom-roots conj ::governance-sentinel))
   :armed?   (fn [] (contains? @warned-non-dom-roots ::governance-sentinel))})

(late-bind/register-warn-once-clear-fn!
  {:label    :views/warned-plain-fn-frame-pairs
   :clear-fn clear-plain-fn-warned-pairs!
   :arm      (fn [] (swap! warned-plain-fn-frame-pairs conj ::governance-sentinel))
   :armed?   (fn [] (contains? @warned-plain-fn-frame-pairs ::governance-sentinel))})
