(ns day8.re-frame2-causa.palette.events
  "Events for the Causa command palette (rf2-wm7z4).

  Every event is registered under `:rf.causa/palette-*` so the
  `:rf.causa/*` collision contract (registry.cljs) holds. The events
  fire against the `:rf/causa` frame — they are dispatched from
  inside the palette modal (which renders under the shell's
  `frame-provider`) so re-frame's frame-resolver routes them
  correctly without further `with-frame` wrappers.

  ## Action lowering

  Source items in `palette/sources` declare their action as one of:

    [:palette/select-panel id]
    [:palette/select-event ev-id]
    [:palette/select-frame fid]
    [:palette/inspect-handler kind id]
    [:palette/cycle-density]
    [:palette/clear-trace-buffer]
    [:palette/reset-suppressed-counters]
    [:palette/open-popout]
    [:palette/close]

  `:rf.causa/palette-invoke` lowers them into the right Causa-side
  side-effect: most translate to a `[:rf.causa/<verb> ...]` dispatch;
  a few invoke mount-layer fns directly via the `:rf.causa.palette
  .fx/*` effect family. Keeping the lowering server-side (in the
  events ns, not the view) means the view stays a pure subscriber +
  dispatcher of a single uniform event id."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.palette.sources :as sources]))

;; ---- effect: mount-level pop-out -----------------------------------------
;;
;; The pop-out semantics for palette items reuse Causa's existing
;; `mount/popout!` — `Ctrl+Enter` on a popout-eligible result opens
;; the popout window (or focuses it if one already exists) and then
;; lowers the underlying action.
;;
;; ## Why we late-bind through the browser API instead of requiring mount
;;
;; A direct `[day8.re-frame2-causa.mount]` require here would form the
;; cycle: `mount → shell → palette → palette.events → mount`. The
;; shell mounts the palette modal; the palette events lower into
;; mount actions; mount requires the shell to render. Breaking the
;; cycle by requiring mount only from non-shell call sites means we
;; reach mount fns via the browser API exports `preload/install-
;; browser-api-exports!` installs on `window.day8.re_frame2_causa.
;; popout_BANG_`. Late-bound at fx-fire time, not at namespace-load
;; time — preload runs BEFORE any palette dispatch can land, so the
;; export is always present in production paths.
;;
;; Tests stub the `:rf.causa.palette.fx/popout` registration directly
;; (see palette/events_cljs_test.cljs `with-popout-counter`) so the
;; late-bind never matters for the registered-event contract — the fx
;; redefinition wins.

(defn- mount-popout!
  "Reach `mount/popout!` through the browser API the preload installs.
  Returns nil when the API is unreachable (preload not loaded, no
  browser global — test runtime without the export, etc.). Swallows
  any throw so a popup-blocked failure never poisons the event-
  handler chain that dispatched it."
  []
  (try
    (when (exists? js/window)
      (let [day8  (gobj/get js/window "day8")
            causa (when day8 (gobj/get day8 "re_frame2_causa"))
            fn-h  (when causa (gobj/get causa "popout_BANG_"))]
        (when (fn? fn-h) (fn-h))))
    (catch :default _ nil)))

(defn- popout-fx!
  "Effect handler: open the Causa pop-out window."
  [_]
  (mount-popout!)
  nil)

;; ---- helpers -------------------------------------------------------------

(defn- reset-cursor [db]
  (assoc db :palette-cursor 0))

(defn- close-palette [db]
  (-> db
      (assoc :palette-open? false)
      (assoc :palette-query "")
      (reset-cursor)))

;; ---- install --------------------------------------------------------------

(defn install!
  "Install the palette's events + effects. Idempotent — re-frame's
  registrar replaces handlers in place, so a second call is harmless
  beyond the `:rf.warning/handler-replaced` trace it emits (which
  the orchestrator's `registered?` sentinel already protects
  against)."
  []

  (rf/reg-fx :rf.causa.palette.fx/popout popout-fx!)

  ;; ---- open / close / toggle --------------------------------------------

  (rf/reg-event-db :rf.causa/palette-open
    (fn [db _event]
      (-> db
          (assoc :palette-open? true)
          (assoc :palette-query "")
          (reset-cursor))))

  (rf/reg-event-db :rf.causa/palette-close
    (fn [db _event]
      (close-palette db)))

  (rf/reg-event-db :rf.causa/palette-toggle
    (fn [db _event]
      (if (get db :palette-open? false)
        (close-palette db)
        (-> db
            (assoc :palette-open? true)
            (assoc :palette-query "")
            (reset-cursor)))))

  ;; ---- query / cursor ---------------------------------------------------

  (rf/reg-event-db :rf.causa/palette-set-query
    {:rf.trace/no-emit? true}
    (fn [db [_ text]]
      (-> db
          (assoc :palette-query (or text ""))
          (reset-cursor))))

  (rf/reg-event-db :rf.causa/palette-cursor-up
    {:rf.trace/no-emit? true}
    (fn [db _event]
      (update db :palette-cursor #(max 0 (dec (or % 0))))))

  (rf/reg-event-db :rf.causa/palette-cursor-down
    {:rf.trace/no-emit? true}
    (fn [db [_ max-idx]]
      (update db :palette-cursor
              (fn [c]
                (min (or max-idx 0) (inc (or c 0)))))))

  (rf/reg-event-db :rf.causa/palette-cursor-set
    {:rf.trace/no-emit? true}
    (fn [db [_ idx]]
      (assoc db :palette-cursor (max 0 (or idx 0)))))

  ;; ---- invoke -----------------------------------------------------------
  ;;
  ;; The view dispatches `[:rf.causa/palette-invoke item popout?]`
  ;; on Enter / Ctrl+Enter. The handler lowers the item's action
  ;; tuple into the appropriate Causa-side dispatch (or mount-layer
  ;; side effect) and closes the palette.

  (rf/reg-event-fx :rf.causa/palette-invoke
    (fn [{:keys [db]} [_ item popout?]]
      (let [[verb & args]    (:action item)
            close-db         (close-palette db)
            ;; Pop-out is gated on the item's own opt-in (per
            ;; sources/popoutable?) — Ctrl+Enter on a non-popoutable
            ;; item invokes normally so the user never gets surprised
            ;; with a no-op.
            popout-now?      (and popout? (sources/popoutable? item))
            base-fx          (cond-> []
                               popout-now?
                               (conj [:rf.causa.palette.fx/popout {}]))]
        (case verb
          :palette/select-panel
          ;; Panel ids in `palette-panels` are the 6 L3 tab ids per
          ;; spec/018 §5 (rf2-qy0nu trimmed the 14-id legacy list).
          ;; Dispatch into `:rf.causa/select-tab` so the visible tab
          ;; flips; the legacy `:rf.causa/select-panel` slot is no
          ;; longer read by the 4-layer shell.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa/select-tab (first args)]])}

          :palette/select-event
          ;; Future: route to event-detail with the event pre-
          ;; selected. Phase 1 routes to the Event tab; the event-detail
          ;; panel reads `:rf.causa/selected-event-id` for the specific
          ;; event focus (event-detail panel handles the missing-id
          ;; gracefully — see panels/event_detail.cljs).
          {:db (assoc close-db
                      :selected-tab :event
                      :selected-event-id (first args))
           :fx base-fx}

          :palette/select-frame
          ;; Frame focus is a Causa-side annotation today (rf2-wm7z4
          ;; Phase 1 — frame picker UI lives at the top strip per
          ;; spec/007-UX-IA.md). Store the choice; a follow-on bead
          ;; wires the picker UI to read it.
          {:db (assoc close-db :selected-target-frame (first args))
           :fx base-fx}

          :palette/inspect-handler
          ;; Phase 1: route to a Causa-side store of the inspected
          ;; handler choice. The handler-detail panel is itself a
          ;; follow-on bead — for now the store is enough so the
          ;; palette completes its part of the contract.
          (let [[kind id] args]
            {:db (assoc close-db
                        :selected-tab :event
                        :inspecting-handler [kind id])
             :fx base-fx})

          :palette/cycle-density
          ;; Phase 1: density toggle is wired through the existing
          ;; density-sub once the density-runtime bead lands. The
          ;; palette event records the user intent so the follow-on
          ;; bead has the data point.
          {:db (assoc close-db :density-cycle-requested? true)}

          :palette/clear-trace-buffer
          (do
            ;; The trace-bus atom is the canonical write surface
            ;; (per registry.cljs §trace-buffer mirror); clearing
            ;; the atom dispatches the coalesced sync into the
            ;; mirrored slot so the panel re-renders on the
            ;; standard reactive path.
            (try (trace-bus/clear-buffer!) (catch :default _ nil))
            {:db close-db
             :fx base-fx})

          :palette/reset-suppressed-counters
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa/reset-suppressed-counters]])}

          :palette/open-popout
          {:db close-db
           :fx [[:rf.causa.palette.fx/popout {}]]}

          :palette/close
          {:db close-db}

          ;; Unknown verb — close the palette and log to console so
          ;; the dev sees the gap. Never silently swallow.
          (do
            (when (and (exists? js/console) (.-warn js/console))
              (.warn js/console
                     (str "[rf2-causa] palette: unknown action verb "
                          (pr-str verb))))
            {:db close-db})))))

  nil)
