(ns day8.re-frame2-xray.panels.app-db-diff-events
  "Events and effects for the App-DB Diff panel.

  ## rf2-e9tb0 — pinned-slices events dropped

  The `:rf.xray/pin-slice`, `:rf.xray/unpin-slice`, and
  `:rf.xray/reorder-pinned-slices` events were removed when the
  pinned-watches strip was superseded by the path-segment inspector
  popup (Mike 2026-05-19 Q13). The matching `pin-path` / `unpin-path`
  / `reorder-paths` helpers were pulled in lockstep."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.runtime :as runtime]))

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

  ;; ---- universal copy-to-clipboard — OFF-BOX EGRESS (rf2-uo0rc.2) ----------
  ;;
  ;; The system clipboard is an OFF-BOX SINK (Security.md §Off-box egress,
  ;; palette/events.cljs §Off-box egress): whatever lands there can be
  ;; pasted into a teammate's chat or scraped. So the COPIED VALUE must
  ;; cross it through the runtime's single named fail-closed safe-egress
  ;; fn `runtime/egress-value` — the SAME projection the palette snapshot
  ;; (rf2-mxzgg) and the `get-app-db` accessor (rf2-a96xq) use. On the
  ;; bare call the off-box defaults are baked in: `:sensitive?` slots ⇒
  ;; `:rf/redacted`, large slots ⇒ `:rf.size/large-elided`. The earlier
  ;; copy fx wrote the RAW `(pr-str value)` — a `[:auth :token]
  ;; {:sensitive? true}` slot or a large blob leaked verbatim.
  ;;
  ;; The egress is pinned to the OBSERVED frame (the frame Xray is
  ;; inspecting — `[:focus :frame]`, falling back to the legacy
  ;; `:target-frame` slot) via `(rf/with-frame …)` so THAT frame's own
  ;; schema declarations govern the redaction — mirroring the palette's
  ;; `snapshot-app-db!`, which pins to the focused frame for the same
  ;; reason (the displayed value's `:sensitive?` / size declarations live
  ;; on the inspected frame, not the Xray chrome frame the affordance
  ;; dispatches from). A nil / unregistered observed frame degrades to a
  ;; bare (current-frame-resolved) egress — still fail-closed.
  (rf/reg-event-fx :rf.xray/copy-value-to-clipboard
    (fn [{:keys [db]} [_ value]]
      (let [observed (or (get-in db [:focus :frame]) (get db :target-frame))
            elided   (if (and (some? observed) (some? (frame/frame observed)))
                       (rf/with-frame observed (runtime/egress-value value))
                       (runtime/egress-value value))]
        {:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str elided)}]]})))

  ;; The path-copy variant copies ONLY the path vector (key names, no
  ;; values) so there is nothing to elide — it is not a value-egress site.
  (rf/reg-event-fx :rf.xray/copy-path-to-clipboard
    (fn [_ctx [_ path]]
      {:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str path)}]]})))
