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
            [day8.re-frame2-xray.egress :as egress]))

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

  ;; ---- universal copy-to-clipboard — OFF-BOX EGRESS (rf2-uo0rc.2) ----------
  ;;
  ;; The system clipboard is an OFF-BOX SINK (Security.md §Off-box egress,
  ;; palette/events.cljs §Off-box egress): whatever lands there can be
  ;; pasted into a teammate's chat or scraped. So the COPIED VALUE must
  ;; cross it through Xray's single named fail-closed panel-local
  ;; safe-egress fn `egress/egress-value` — the SAME projection the
  ;; palette snapshot uses (rf2-mxzgg). On the
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
  (rf/reg-event :rf.xray/copy-value-to-clipboard
    (fn [{:keys [db]} [_ value]]
      (let [observed (or (get-in db [:focus :frame]) (get db :target-frame))
            elided   (if (and (some? observed) (some? (frame/frame observed)))
                       (rf/with-frame observed (egress/egress-value value))
                       (egress/egress-value value))]
        {:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str elided)}]]})))

  ;; The path-copy variant copies ONLY the path vector (key names, no
  ;; values) so there is nothing to elide — it is not a value-egress site.
  (rf/reg-event :rf.xray/copy-path-to-clipboard
    (fn [_ctx [_ path]]
      {:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str path)}]]})))
