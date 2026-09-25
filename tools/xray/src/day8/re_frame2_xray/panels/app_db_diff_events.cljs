(ns day8.re-frame2-xray.panels.app-db-diff-events
  "Events and effects for the App-DB Diff panel.

  ## One effect, no events

  There are no pinned-slice, slice-focus or path-inspector events: zoom
  into a node is the path interaction.

  There are no copy EVENTS either. The `:rf.xray.fx/copy-to-clipboard` fx
  below is the one registration: Static Machines' `Copy Mermaid` gesture
  rides it (`static/machines/panel.cljs`), and that text is value-free
  static topology rather than a value egress.

  The fail-closed egress proofs sit directly on `egress/egress-value` in
  `test/day8/re_frame2_xray/panels/app_db_diff_cljs_test.cljs`."
  (:require [re-frame.core :as rf]))

(defn install!
  "Install the App-DB Diff events and effects."
  []
  ;; ---- no diff-mode toggle ------------------------------------------------
  ;;
  ;; FULL+DIFF is the single rendering, as on the Epoch HANDLER `:db`,
  ;; SUBSCRIPTIONS value, and Machine Inspector snapshot surfaces — the
  ;; panel's view layer hard-wires that posture, so there is no
  ;; sub/event/slot for a mode toggle to register here.

  ;; `:on-success` / `:on-failure` (optional event vectors) let a
  ;; caller surface honest copy feedback: `navigator.clipboard.writeText`
  ;; returns a Promise that REJECTS on a denied/unavailable clipboard, and
  ;; an fx that swallowed that settlement would leave a caller unable to
  ;; distinguish "copied" from "silently dropped". The follow-up
  ;; dispatch is pinned to the fx-context frame (`(:frame ctx)` — the
  ;; active frame id per the v2 reg-fx contract) because the Promise
  ;; callback runs long after the dispatching frame's dynamic context has
  ;; unwound. Callers that pass no callbacks get the best-effort,
  ;; fire-and-forget contract.
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

  ;; ---- no value-copy EVENT here --------------------------------------------
  ;;
  ;; Anything that puts a VALUE on this fx must first cross Xray's single
  ;; named fail-closed projection `egress/egress-value`, NAMING the observed
  ;; frame so the no-target and stale-target cases redact whole rather than
  ;; resolving the ambient `:rf/xray` chrome frame and shipping raw. It
  ;; must also carry the value's absolute app-db `:path`,
  ;; because the framework keys `:sensitive` / `:large` declarations by
  ;; absolute path and a slice egress'd without one matches nothing. An
  ;; event receiving only `[_ value]` can satisfy the first requirement
  ;; and not the second.
  )
