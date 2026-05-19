(ns day8.re-frame2-causa.panels.app-db-diff-events
  "Events and effects for the App-DB Diff panel.

  ## rf2-e9tb0 — pinned-slices events dropped

  The `:rf.causa/pin-slice`, `:rf.causa/unpin-slice`, and
  `:rf.causa/reorder-pinned-slices` events were removed when the
  pinned-watches strip was superseded by the path-segment inspector
  popup (Mike 2026-05-19 Q13). The matching `pin-path` / `unpin-path`
  / `reorder-paths` helpers were pulled in lockstep."
  (:require [re-frame.core :as rf]))

(defn install!
  "Install the App-DB Diff events and effects."
  []
  (rf/reg-event-db :rf.causa/focus-slice-path
    (fn [db [_ path]]
      (assoc db :focused-slice-path path)))

  (rf/reg-event-db :rf.causa/clear-slice-focus
    (fn [db _event]
      (dissoc db :focused-slice-path)))

  (rf/reg-fx :rf.causa.fx/copy-to-clipboard
    (fn [_ctx {:keys [text]}]
      (try
        (when (and (exists? js/navigator)
                   (.-clipboard js/navigator))
          (.writeText (.-clipboard js/navigator) (str text)))
        (catch :default _ nil))))

  (rf/reg-event-fx :rf.causa/copy-value-to-clipboard
    (fn [_ctx [_ value]]
      {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str value)}]]}))

  (rf/reg-event-fx :rf.causa/copy-path-to-clipboard
    (fn [_ctx [_ path]]
      {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str path)}]]})))
