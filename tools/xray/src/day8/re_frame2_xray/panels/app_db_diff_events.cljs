(ns day8.re-frame2-xray.panels.app-db-diff-events
  "Events and effects for the App-DB Diff panel.

  ## rf2-e9tb0 — pinned-slices events dropped

  The `:rf.xray/pin-slice`, `:rf.xray/unpin-slice`, and
  `:rf.xray/reorder-pinned-slices` events were removed when the
  pinned-watches strip was superseded by the path-segment inspector
  popup (Mike 2026-05-19 Q13). The matching `pin-path` / `unpin-path`
  / `reorder-paths` helpers were pulled in lockstep.

  ## rf2-6r9j.24 — the two copy EVENTS dropped; the fx stays

  `:rf.xray/copy-value-to-clipboard` and `:rf.xray/copy-path-to-clipboard`
  were retired on 2026-09-04 with the universal EDN-widget `⎘` affordance
  that was their only intended dispatcher — neither had a dispatcher
  anywhere in `tools/xray/src`. The `:rf.xray.fx/copy-to-clipboard` fx
  below SURVIVES: Static Machines' reachable `Copy Mermaid` gesture
  rides it (`static/machines/panel.cljs`), and that text is value-free
  static topology rather than a value egress.

  The fail-closed egress proofs those events carried (rf2-7htk7) now sit
  directly on `egress/egress-value` in
  `test/day8/re_frame2_xray/panels/app_db_diff_cljs_test.cljs`."
  (:require [re-frame.core :as rf]))

(defn install!
  "Install the App-DB Diff events and effects."
  []
  (rf/reg-event :rf.xray/focus-slice-path
    (fn [{:keys [db]} [_ path]]
      {:db (assoc db :focused-slice-path path)}))

  (rf/reg-event :rf.xray/clear-slice-focus
    (fn [{:keys [db]} _event]
      {:db (dissoc db :focused-slice-path)}))

  ;; ---- App-DB panel diff-mode toggle — RETIRED 2026-05-29 (rf2-vv3m6) -----
  ;;
  ;; The `[diff][full][full+diff]` toggle retired alongside its sibling
  ;; toggles on the Epoch HANDLER `:db`, SUBSCRIPTIONS value, and
  ;; Machine Inspector snapshot surfaces. FULL+DIFF is the single
  ;; rendering — the panel's view layer hard-wires that posture and
  ;; this install no longer registers the sub/event/slot trio.

  ;; `:on-success` / `:on-failure` (optional event vectors, rf2-sxw06) let a
  ;; caller surface honest copy feedback: `navigator.clipboard.writeText`
  ;; returns a Promise that REJECTS on a denied/unavailable clipboard, and
  ;; the pre-fix fx swallowed that settlement entirely — a caller could
  ;; never distinguish "copied" from "silently dropped". The follow-up
  ;; dispatch is pinned to the fx-context frame (`(:frame ctx)` — the
  ;; active frame id per the v2 reg-fx contract) because the Promise
  ;; callback runs long after the dispatching frame's dynamic context has
  ;; unwound. Callers that pass no callbacks keep the original
  ;; best-effort/fire-and-forget contract unchanged.
  (rf/reg-fx :rf.xray.fx/copy-to-clipboard
    (fn [ctx {:keys [text on-success on-failure]}]
      (let [frame-id (:frame ctx)
            notify!  (fn [ev]
                       (when (vector? ev)
                         (try
                           (if (some? frame-id)
                             (rf/with-frame frame-id (rf/dispatch ev))
                             (rf/dispatch ev))
                           (catch :default _ nil))))]
        (try
          (if (and (exists? js/navigator)
                   (.-clipboard js/navigator))
            (-> (.writeText (.-clipboard js/navigator) (str text))
                (.then (fn [_] (notify! on-success))
                       (fn [_] (notify! on-failure))))
            (notify! on-failure))
          (catch :default _ (notify! on-failure))))))

  ;; ---- no value-copy EVENT here (rf2-6r9j.24) ------------------------------
  ;;
  ;; Anything that puts a VALUE on this fx must first cross Xray's single
  ;; named fail-closed projection `egress/egress-value`, NAMING the observed
  ;; frame so the no-target and stale-target cases redact whole rather than
  ;; resolving the ambient `:rf/xray` chrome frame and shipping raw
  ;; (rf2-7htk7). It must also carry the value's absolute app-db `:path`,
  ;; because the framework keys `:sensitive` / `:large` declarations by
  ;; absolute path and a slice egress'd without one matches nothing
  ;; (rf2-a96xq). The retired value-copy event (see the ns docstring)
  ;; satisfied the first requirement and not the second — it received only
  ;; `[_ value]` — which is why it could not simply be rewired.
  )
