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
            [re-frame.frame :as frame]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.palette.recents :as recents]
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

;; ---- rf2-ybjkx — recents ------------------------------------------------

(defn- record-recent
  "Pure reducer. Update `db`'s `:palette-recents` slot to put
  `command-id` at the head (dedup'd, capped). Returns the new db.
  No-op for nil ids."
  [db command-id]
  (if (nil? command-id)
    db
    (assoc db :palette-recents
              (recents/record (get db :palette-recents []) command-id))))

;; ---- rf2-ybjkx — snapshot app-db ---------------------------------------
;;
;; Snapshot the focused frame's app-db onto the JS console as a single
;; `console.log(value)` call. Side effect; never blocks. Reads the
;; focused frame via Causa's app-db `:target-frame` slot (the frame
;; the user picked in the L1 frame-picker) — defaulting to `:rf/default`
;; when unset. We also try to copy a pretty-printed EDN string to the
;; clipboard via `navigator.clipboard.writeText` when available so the
;; user can paste into a teammate's chat / a gist. Clipboard writes
;; can reject (insecure context, no permission); we swallow the throw
;; so the console log lands either way.

(defn- snapshot-app-db!
  "Side-effect: drop `(rf/get-frame-db target-frame)` onto the JS
  console and copy a pr-str of it to the clipboard when reachable.
  No-op when neither console nor clipboard is present (test
  runtimes). Returns nil."
  [target-frame]
  (try
    (let [tf  (or target-frame :rf/default)
          db  (when (some? (frame/frame tf))
                (rf/get-frame-db tf))
          tag (str "[rf2-causa] palette snapshot · frame "
                   (pr-str tf))]
      (when (and (exists? js/console) (.-log js/console))
        (try
          (.log js/console tag db)
          (catch :default _ nil)))
      (when (and (exists? js/navigator)
                 (.-clipboard js/navigator)
                 (.-writeText (.-clipboard js/navigator)))
        (try
          (.writeText (.-clipboard js/navigator) (pr-str db))
          (catch :default _ nil))))
    (catch :default _ nil))
  nil)

;; ---- rf2-ybjkx — theme cycle -------------------------------------------

(defn- next-theme
  "Pure helper. Cycle `:dark → :light → :dark`. Anything unknown lands
  on `:dark` (the canonical default per `config/default-settings`)."
  [current]
  (case current
    :dark  :light
    :light :dark
    :dark))

;; ---- rf2-ybjkx — reduced-motion cycle ----------------------------------

(defn- next-motion-override
  "Pure helper. Cycle `:os → :always → :never → :os`. Unknown lands on
  `:os` (the conservative default)."
  [current]
  (case current
    :os      :always
    :always  :never
    :never   :os
    :os))

;; ---- install --------------------------------------------------------------

(defn install!
  "Install the palette's events + effects. Idempotent — re-frame's
  registrar replaces handlers in place, so a second call is harmless
  beyond the `:rf.warning/handler-replaced` trace it emits (which
  the orchestrator's `registered?` sentinel already protects
  against)."
  []

  (rf/reg-fx :rf.causa.palette.fx/popout popout-fx!)

  ;; rf2-ybjkx — snapshot app-db fx. Side-effect handler; reads the
  ;; focused frame's db and ships it to the JS console + clipboard.
  ;; Late-bound through the framework's `frame/frame` registry so a
  ;; nil-frame ctx is a silent no-op rather than a throw.
  (rf/reg-fx :rf.causa.palette.fx/snapshot-app-db
    (fn [_ctx {:keys [target-frame]}]
      (snapshot-app-db! target-frame)))

  ;; rf2-ybjkx — recents persistence fx. The pure reducer writes the
  ;; vector into app-db; this fx mirrors the same vector into
  ;; localStorage so a reload surfaces the user's recents. Best-effort
  ;; — `recents/save!` swallows quota / availability failures.
  (rf/reg-fx :rf.causa.palette.fx/persist-recents
    (fn [_ctx recents-vec]
      (recents/save! recents-vec)))

  ;; ---- open / close / toggle --------------------------------------------
  ;;
  ;; rf2-ybjkx — on open we ensure the `:palette-recents` slot is
  ;; seeded from localStorage. Open is rare (user-driven keystroke)
  ;; so the load cost is negligible; lazy-seed means we don't have to
  ;; thread a preload hook through every test that drives the registry.

  (rf/reg-event-db :rf.causa/palette-open
    (fn [db _event]
      (let [seeded (if (contains? db :palette-recents)
                     db
                     (assoc db :palette-recents (recents/load)))]
        (-> seeded
            (assoc :palette-open? true)
            (assoc :palette-query "")
            (reset-cursor)))))

  (rf/reg-event-db :rf.causa/palette-close
    (fn [db _event]
      (close-palette db)))

  (rf/reg-event-db :rf.causa/palette-toggle
    (fn [db _event]
      (if (get db :palette-open? false)
        (close-palette db)
        (let [seeded (if (contains? db :palette-recents)
                       db
                       (assoc db :palette-recents (recents/load)))]
          (-> seeded
              (assoc :palette-open? true)
              (assoc :palette-query "")
              (reset-cursor))))))

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
            ;; rf2-ybjkx — record recents for `:command` source items
            ;; only. Panels / frames / handlers / settings already
            ;; have their own recency surface (the recent-event source
            ;; and the user's session memory); the palette's recents
            ;; track command verbs.
            recent-id        (when (= :command (:source item)) (:id item))
            db-with-recent   (record-recent db recent-id)
            close-db         (close-palette db-with-recent)
            ;; Pop-out is gated on the item's own opt-in (per
            ;; sources/popoutable?) — Ctrl+Enter on a non-popoutable
            ;; item invokes normally so the user never gets surprised
            ;; with a no-op.
            popout-now?      (and popout? (sources/popoutable? item))
            ;; rf2-ybjkx — persist the recents vector on every command
            ;; invoke so the next session surfaces the user's history.
            ;; Pure recents reads (no command invoked) skip this.
            base-fx          (cond-> []
                               popout-now?
                               (conj [:rf.causa.palette.fx/popout {}])
                               (some? recent-id)
                               (conj [:rf.causa.palette.fx/persist-recents
                                      (get db-with-recent :palette-recents)]))]
        (case verb
          :palette/select-panel
          ;; Panel ids in `palette-panels` are the 7 L3 tab ids per
          ;; spec/018 §5 (rf2-qy0nu trimmed the 14-id legacy list).
          ;; Dispatch into `:rf.causa/select-tab` so the visible tab
          ;; flips; the legacy `:rf.causa/select-panel` slot is no
          ;; longer read by the 4-layer shell.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa/select-tab (first args)]])}

          :palette/select-static-tab
          ;; rf2-ybjkx — Static-mode L3 tab jump. Routes through
          ;; `:rf.causa.static/select-tab` (the Static-scoped tab slot
          ;; that the static shell's tab-bar reads). The dispatch is
          ;; safe even when `:experimental/static-mode?` is OFF — the
          ;; event handler is registered regardless; the surface
          ;; composer just doesn't read the slot until the flag flips
          ;; on. The palette filter prevents Static jumps from
          ;; surfacing in Runtime mode in the first place.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa.static/select-tab (first args)]])}

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
          ;; rf2-ybjkx — drive the frame-picker spine event so the
          ;; user's choice flows through every per-frame composite
          ;; (App-DB Diff, Views, Routing). The pre-bead behaviour
          ;; just stamped `:selected-target-frame` into Causa's app-db
          ;; with no plumb-through; this dispatches the canonical
          ;; `:rf.causa/set-frame` event the L1 frame-picker uses so
          ;; the palette is wire-compatible with the rest of the
          ;; chrome.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa/set-frame (first args)]])}

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

          :palette/clear-epoch-history
          ;; rf2-ybjkx — drop Causa's epoch ring. The slot lives in
          ;; Causa's app-db at `:epoch-history`; clearing it lets the
          ;; user start a fresh session without restarting the host
          ;; app. App-DB Diff + Views read off this slot so the next
          ;; epoch lands cleanly.
          {:db (dissoc close-db :epoch-history)
           :fx base-fx}

          :palette/reset-suppressed-counters
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa/reset-suppressed-counters]])}

          :palette/snapshot-app-db
          ;; rf2-ybjkx — Snapshot the FOCUSED frame's app-db onto the
          ;; console + clipboard. The focused-frame is the slot the
          ;; L1 frame-picker writes (`:target-frame`); falling back to
          ;; `:rf/default` when unset.
          {:db close-db
           :fx (conj base-fx
                     [:rf.causa.palette.fx/snapshot-app-db
                      {:target-frame
                       (or (get db-with-recent :target-frame) :rf/default)}])}

          :palette/toggle-theme
          ;; rf2-ybjkx — flip the Settings popup's theme slot via the
          ;; existing settings-update event. Routes through the same
          ;; reducer that the popup's radio uses so the popup's
          ;; reactive sub re-fires + the localStorage round-trip
          ;; lands + `apply-theme!` flips the `<html>` class. Reads
          ;; the current theme from `config/get-setting` (the live
          ;; atom; the popup-seeded app-db slot mirrors the same
          ;; value but reading the atom keeps the toggle pure on the
          ;; cold path — no settings-open prerequisite).
          {:db close-db
           :fx (conj base-fx
                     [:dispatch
                      [:rf.causa/settings-update :theme nil
                       (next-theme (config/get-setting :theme nil))]])}

          :palette/cycle-reduced-motion
          ;; rf2-ybjkx — cycle the user-side reduced-motion override
          ;; (:os → :always → :never → :os). Routes through the
          ;; settings-update event so the apply-fn + persist path are
          ;; the canonical ones — no parallel state.
          {:db close-db
           :fx (conj base-fx
                     [:dispatch
                      [:rf.causa/settings-update :general
                       :reduced-motion-override
                       (next-motion-override
                         (config/get-setting :general :reduced-motion-override))]])}

          :palette/jump-to-settings
          ;; rf2-ybjkx — open the Settings popup. Routes through the
          ;; existing `:rf.causa/settings-open` event so the popup's
          ;; open path is the canonical one (lifts the atom snapshot
          ;; into app-db, defaults the active-tab to `:general`).
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa/settings-open]])}

          :palette/toggle-mode
          ;; rf2-ybjkx — flip Runtime ↔ Static. Routes through the
          ;; existing `:rf.causa/toggle-mode` event (also bound to
          ;; Cmd/Ctrl+Shift+M). Single source of truth for the
          ;; mode-flip + persistence side-effect.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.causa/toggle-mode]])}

          :palette/open-popout
          ;; The :open-popout verb always pops out — guard against
          ;; double-fire when the user also held Ctrl/Meta (popout-now?
          ;; would have appended the popout fx to `base-fx`).
          {:db close-db
           :fx (cond-> base-fx
                 (not popout-now?)
                 (conj [:rf.causa.palette.fx/popout {}]))}

          :palette/close
          {:db close-db
           :fx base-fx}

          ;; Unknown verb — close the palette and log to console so
          ;; the dev sees the gap. Never silently swallow.
          (do
            (when (and (exists? js/console) (.-warn js/console))
              (.warn js/console
                     (str "[rf2-causa] palette: unknown action verb "
                          (pr-str verb))))
            {:db close-db
             :fx base-fx})))))

  nil)
