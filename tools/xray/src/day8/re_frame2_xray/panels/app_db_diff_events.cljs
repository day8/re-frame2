(ns day8.re-frame2-xray.panels.app-db-diff-events
  "Events and effects for the App-DB Diff panel.

  ## rf2-e9tb0 — pinned-slices events dropped

  The `:rf.xray/pin-slice`, `:rf.xray/unpin-slice`, and
  `:rf.xray/reorder-pinned-slices` events were removed when the
  pinned-watches strip was superseded by the path-segment inspector
  popup (Mike 2026-05-19 Q13). The matching `pin-path` / `unpin-path`
  / `reorder-paths` helpers were pulled in lockstep."
  (:require [day8.re-frame2-xray.views.diff-mode-toggle :as diff-mode]
            [re-frame.core :as rf]))

(defn install!
  "Install the App-DB Diff events and effects."
  []
  (rf/reg-event-db :rf.xray/focus-slice-path
    (fn [db [_ path]]
      (assoc db :focused-slice-path path)))

  (rf/reg-event-db :rf.xray/clear-slice-focus
    (fn [db _event]
      (dissoc db :focused-slice-path)))

  ;; ---- App-DB panel diff-mode toggle (rf2-yqjrd / rf2-0cyjm) ----
  ;;
  ;; Three-mode toggle `[diff][full][full+diff]` that drives every section
  ;; (top + per-`:rf/*` area) in the App-DB panel. Per pair-debug
  ;; 2026-05-27 the same shape every Xray diff surface uses — shared
  ;; widget in `views.diff-mode-toggle`.
  ;;
  ;; - `:diff`      — pure-diff lens: flat path-prefixed change list for
  ;;                  the focused epoch. Same source as the Epoch
  ;;                  HANDLER step's `:diff` view; same flat rows.
  ;; - `:full`      — pure-data lens: every section renders the LIVE
  ;;                  current-state value with no `:before` (plain
  ;;                  browse, no inline diff annotations).
  ;; - `:full+diff` — combined lens (mode-3): every section renders the
  ;;                  full current-state value WITH inline diff
  ;;                  annotations against the focused epoch's
  ;;                  `:db-before`. Default per pair-debug 2026-05-27 —
  ;;                  the operator's most-useful default; shape + delta
  ;;                  together.
  ;;
  ;; Mode persists via `:rf.xray.app-db/diff-mode` so the operator's
  ;; preference survives focus shifts. The sub + event pair + slot are
  ;; installed by the shared helper from `views.diff-mode-toggle` so
  ;; every Xray diff surface uses identical naming.
  (diff-mode/reg-mode-sub+event! :rf.xray.app-db)

  (rf/reg-fx :rf.xray.fx/copy-to-clipboard
    (fn [_ctx {:keys [text]}]
      (try
        (when (and (exists? js/navigator)
                   (.-clipboard js/navigator))
          (.writeText (.-clipboard js/navigator) (str text)))
        (catch :default _ nil))))

  (rf/reg-event-fx :rf.xray/copy-value-to-clipboard
    (fn [_ctx [_ value]]
      {:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str value)}]]}))

  (rf/reg-event-fx :rf.xray/copy-path-to-clipboard
    (fn [_ctx [_ path]]
      {:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str path)}]]})))
