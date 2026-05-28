(ns day8.re-frame2-xray.panels.app-db-diff-events
  "Events and effects for the App-DB Diff panel.

  ## rf2-e9tb0 — pinned-slices events dropped

  The `:rf.xray/pin-slice`, `:rf.xray/unpin-slice`, and
  `:rf.xray/reorder-pinned-slices` events were removed when the
  pinned-watches strip was superseded by the path-segment inspector
  popup (Mike 2026-05-19 Q13). The matching `pin-path` / `unpin-path`
  / `reorder-paths` helpers were pulled in lockstep."
  (:require [re-frame.core :as rf]))

(defn install!
  "Install the App-DB Diff events and effects."
  []
  (rf/reg-event-db :rf.xray/focus-slice-path
    (fn [db [_ path]]
      (assoc db :focused-slice-path path)))

  (rf/reg-event-db :rf.xray/clear-slice-focus
    (fn [db _event]
      (dissoc db :focused-slice-path)))

  ;; ---- App-DB panel diff-mode toggle — RETIRED 2026-05-29 (rf2-vv3m6) -----
  ;;
  ;; The `[diff][full][full+diff]` toggle retired alongside its sibling
  ;; toggles on the Epoch HANDLER `:db`, SUBSCRIPTIONS value, and
  ;; Machine Inspector snapshot surfaces. FULL+DIFF is the single
  ;; rendering — the panel's view layer hard-wires that posture and
  ;; this install no longer registers the sub/event/slot trio.

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
