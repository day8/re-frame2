(ns day8.re-frame2-xray.palette.events
  "Events for the Xray command palette.

  Every event is registered under `:rf.xray/palette-*` so the
  `:rf.xray/*` collision contract (registry.cljs) holds. The events
  fire against the `:rf/xray` frame — they are dispatched from
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

  `:rf.xray/palette-invoke` lowers them into the right Xray-side
  side-effect: most translate to a `[:rf.xray/<verb> ...]` dispatch;
  a few invoke mount-layer fns directly via the `:rf.xray.palette
  .fx/*` effect family. Keeping the lowering server-side (in the
  events ns, not the view) means the view stays a pure subscriber +
  dispatcher of a single uniform event id."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.runtime :as runtime]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.palette.recents :as recents]
            [day8.re-frame2-xray.palette.sources :as sources]))

;; ---- effect: mount-level pop-out -----------------------------------------
;;
;; The pop-out semantics for palette items reuse Xray's existing
;; `mount/popout!` — `Ctrl+Enter` on a popout-eligible result opens
;; the popout window (or focuses it if one already exists) and then
;; lowers the underlying action.
;;
;; ## Why we late-bind through the browser API instead of requiring mount
;;
;; A direct `[day8.re-frame2-xray.mount]` require here would form the
;; cycle: `mount → shell → palette → palette.events → mount`. The
;; shell mounts the palette modal; the palette events lower into
;; mount actions; mount requires the shell to render. Breaking the
;; cycle by requiring mount only from non-shell call sites means we
;; reach mount fns via the browser API exports `preload/install-
;; browser-api-exports!` installs on `window.day8.re_frame2_xray.
;; popout_BANG_`. Late-bound at fx-fire time, not at namespace-load
;; time — preload runs BEFORE any palette dispatch can land, so the
;; export is always present in production paths.
;;
;; Tests stub the `:rf.xray.palette.fx/popout` registration directly
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
            xray (when day8 (gobj/get day8 "re_frame2_xray"))
            fn-h  (when xray (gobj/get xray "popout_BANG_"))]
        (when (fn? fn-h) (fn-h))))
    (catch :default _ nil)))

(defn- popout-fx!
  "Effect handler: open the Xray pop-out window."
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

;; ---- recents ------------------------------------------------------------

(defn- record-recent
  "Pure reducer. Update `db`'s `:palette-recents` slot to put
  `command-id` at the head (dedup'd, capped). Returns the new db.
  No-op for nil ids."
  [db command-id]
  (if (nil? command-id)
    db
    (assoc db :palette-recents
              (recents/record (get db :palette-recents []) command-id))))

;; ---- snapshot app-db ----------------------------------------------------
;;
;; Snapshot the focused frame's app-db onto the JS console as a single
;; `console.log(value)` call. Side effect; never blocks. Reads the
;; focused frame via Xray's app-db `:target-frame` slot (the frame
;; the user picked in the L1 frame-picker) — defaulting to `:rf/default`
;; when unset. We also try to copy a pretty-printed EDN string to the
;; clipboard via `navigator.clipboard.writeText` when available so the
;; user can paste into a teammate's chat / a gist. Clipboard writes
;; can reject (insecure context, no permission); we swallow the throw
;; so the console log lands either way.
;;
;; ## Off-box egress
;;
;; The JS console AND the system clipboard are both OFF-BOX egress
;; sinks (Tool-Pair §Privacy egress, Security.md §Off-box egress):
;; whatever lands there can be screenshotted, pasted into a teammate's
;; chat, or scraped from a console log. The raw `(rf/app-db-value tf)`
;; can carry schema-/path-declared sensitive slots (`{:auth {:token …}}`)
;; or oversized blobs, so it MUST NOT cross either sink unredacted.
;;
;; We therefore route the captured value through the runtime's single
;; named safe-egress fn `runtime/egress-value` — the SAME fail-closed
;; projection the `get-app-db` accessor uses. On the
;; bare (no-opt) call the off-box defaults are baked in: sensitive
;; slots ⇒ `:rf/redacted`, large slots ⇒ `:rf.size/large-elided`. The
;; whole-db read has no `:path` (the value IS the walked root), so the
;; root-keyed schema declarations match directly. Raw egress is only
;; ever reachable via the documented operator opt-in
;; (`{:include-sensitive? true}` / `{:include-large? true}`); the
;; palette command surfaces NO opt-in arg, so the command default is
;; always the redacted/elided projection — never the raw db.
;;
;; ## Why the egress runs inside `(rf/with-frame tf …)`
;;
;; `egress-value` (→ `elide-wire-value`) resolves the frame-owned
;; sensitive / large app-db declarations from `frame/current-frame` when no
;; explicit `:frame` opt is passed. The snapshot reads the FOCUSED
;; frame's db (`tf`), which is NOT necessarily the frame the fx fires
;; in (the palette dispatches against `:rf/xray`). Pinning the current
;; frame to `tf` for the egress makes the walker match `tf`'s OWN
;; declarations — so a host frame's `[:auth :token]` redacts against
;; the host frame's classification, not the (empty) Xray-frame declarations.
;; Fail-closed: a frame with no declarations still emits the raw value
;; only when that frame declared nothing sensitive, exactly as the
;; per-frame contract intends.

(defn- snapshot-app-db!
  "Side-effect: drop the focused frame's app-db onto the JS console and
  copy a pr-str of it to the clipboard when reachable. The value is
  routed through `runtime/egress-value` FIRST (fail-closed off-box
  defaults — sensitive ⇒ `:rf/redacted`, large ⇒ `:rf.size/large-
  elided`), pinned to the FOCUSED frame so that frame's own schema
  declarations govern the redaction, so neither off-box sink ever
  receives the raw db. No-op when neither console nor
  clipboard is present (test runtimes). Returns nil."
  [target-frame]
  (try
    ;; EP-0002 — the snapshot targets the SELECTED inspected
    ;; frame. When unselected (`target-frame` nil) this is a no-op: a
    ;; whole-db egress to console/clipboard is a read of a specific host
    ;; frame, never a synthesised `:rf/default` (Spec 002 §Frame target
    ;; resolution). With no frame selected there is nothing to snapshot.
    (let [tf      target-frame
          ;; Egress BEFORE anything reaches console/clipboard. Both are
          ;; off-box sinks; `egress-value`'s default polarity redacts
          ;; sensitive + size-elides large slots. No `:path` — the
          ;; whole-db snapshot IS the walked root, so root-keyed schema
          ;; declarations match directly. `with-frame tf` pins the
          ;; declaration lookup to the focused frame (see comment above).
          payload (when (and (some? tf) (some? (frame/frame tf)))
                    (rf/with-frame tf
                      (runtime/egress-value (rf/app-db-value tf))))
          tag     (str "[rf2-xray] palette snapshot · frame "
                       (pr-str tf))]
      ;; Only emit when a target frame is selected — no unselected-state
      ;; snapshot is written to either off-box sink.
      (when (some? tf)
        (when (and (exists? js/console) (.-log js/console))
          (try
            (.log js/console tag payload)
            (catch :default _ nil)))
        (when (and (exists? js/navigator)
                   (.-clipboard js/navigator)
                   (.-writeText (.-clipboard js/navigator)))
          (try
            (.writeText (.-clipboard js/navigator) (pr-str payload))
            (catch :default _ nil)))))
    (catch :default _ nil))
  nil)

;; ---- theme cycle --------------------------------------------------------

(defn- next-theme
  "Pure helper. Cycle `:dark → :light → :dark`. Anything unknown lands
  on `:dark` (the canonical default per `config/default-settings`)."
  [current]
  (case current
    :dark  :light
    :light :dark
    :dark))

;; ---- reduced-motion cycle -----------------------------------------------

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

  (rf/reg-fx :rf.xray.palette.fx/popout popout-fx!)

  ;; Snapshot app-db fx. Side-effect handler; reads the
  ;; focused frame's db, routes it through `runtime/egress-value` (the
  ;; fail-closed off-box safe-egress projection) and ships
  ;; the REDACTED/size-elided payload to the JS console + clipboard —
  ;; both off-box sinks. Late-bound through the framework's
  ;; `frame/frame` registry so a nil-frame ctx is a silent no-op rather
  ;; than a throw.
  (rf/reg-fx :rf.xray.palette.fx/snapshot-app-db
    (fn [_ctx {:keys [target-frame]}]
      (snapshot-app-db! target-frame)))

  ;; Recents persistence fx. The pure reducer writes the
  ;; vector into app-db; this fx mirrors the same vector into
  ;; localStorage so a reload surfaces the user's recents. Best-effort
  ;; — `recents/save!` swallows quota / availability failures.
  (rf/reg-fx :rf.xray.palette.fx/persist-recents
    (fn [_ctx recents-vec]
      (recents/save! recents-vec)))

  ;; ---- open / close / toggle --------------------------------------------
  ;;
  ;; On open we ensure the `:palette-recents` slot is
  ;; seeded from localStorage. Open is rare (user-driven keystroke)
  ;; so the load cost is negligible; lazy-seed means we don't have to
  ;; thread a preload hook through every test that drives the registry.

  (rf/reg-event :rf.xray/palette-open
    (fn [{:keys [db]} _event]
      {:db (let [seeded (if (contains? db :palette-recents)
                     db
                     (assoc db :palette-recents (recents/load)))]
        (-> seeded
            (assoc :palette-open? true)
            (assoc :palette-query "")
            (reset-cursor)))}))

  (rf/reg-event :rf.xray/palette-close
    (fn [{:keys [db]} _event]
      {:db (close-palette db)}))

  (rf/reg-event :rf.xray/palette-toggle
    (fn [{:keys [db]} _event]
      {:db (if (get db :palette-open? false)
        (close-palette db)
        (let [seeded (if (contains? db :palette-recents)
                       db
                       (assoc db :palette-recents (recents/load)))]
          (-> seeded
              (assoc :palette-open? true)
              (assoc :palette-query "")
              (reset-cursor))))}))

  ;; ---- query / cursor ---------------------------------------------------

  (rf/reg-event :rf.xray/palette-set-query
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ text]]
      {:db (-> db
          (assoc :palette-query (or text ""))
          (reset-cursor))}))

  (rf/reg-event :rf.xray/palette-cursor-up
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _event]
      {:db (update db :palette-cursor #(max 0 (dec (or % 0))))}))

  (rf/reg-event :rf.xray/palette-cursor-down
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ max-idx]]
      {:db (update db :palette-cursor
              (fn [c]
                (min (or max-idx 0) (inc (or c 0)))))}))

  (rf/reg-event :rf.xray/palette-cursor-set
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ idx]]
      {:db (assoc db :palette-cursor (max 0 (or idx 0)))}))

  ;; ---- invoke -----------------------------------------------------------
  ;;
  ;; The view dispatches `[:rf.xray/palette-invoke item popout?]`
  ;; on Enter / Ctrl+Enter. The handler lowers the item's action
  ;; tuple into the appropriate Xray-side dispatch (or mount-layer
  ;; side effect) and closes the palette.

  (rf/reg-event :rf.xray/palette-invoke
    (fn [{:keys [db]} [_ item popout?]]
      (let [[verb & args]    (:action item)
            ;; Record recents for `:command` source items
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
            ;; Persist the recents vector on every command
            ;; invoke so the next session surfaces the user's history.
            ;; Pure recents reads (no command invoked) skip this.
            base-fx          (cond-> []
                               popout-now?
                               (conj [:rf.xray.palette.fx/popout {}])
                               (some? recent-id)
                               (conj [:rf.xray.palette.fx/persist-recents
                                      (get db-with-recent :palette-recents)]))]
        (case verb
          :palette/select-panel
          ;; Panel ids in `palette-panels` are the 6 L3 tab ids per
          ;; spec/018 §5. Dispatch into `:rf.xray/select-tab` so the
          ;; visible tab flips — the slot the 4-layer shell reads.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.xray/select-tab (first args)]])}

          :palette/select-static-tab
          ;; Static-mode L3 tab jump. Routes through
          ;; `:rf.xray.static/select-tab` (the Static-scoped tab slot
          ;; that the static shell's tab-bar reads). The palette
          ;; filter prevents Static jumps from surfacing while the
          ;; user is in Dynamic mode.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.xray.static/select-tab (first args)]])}

          :palette/select-event
          ;; Route to the Epoch tab with the event pre-selected. The
          ;; Epoch panel is the canonical "what happened in this epoch"
          ;; surface; `:rf.xray/selected-event-id` is the selected-event
          ;; slot the panels read.
          {:db (assoc close-db
                      :selected-tab :epoch
                      :selected-event-id (first args))
           :fx base-fx}

          :palette/select-frame
          ;; Drive the frame-picker selection event so the user's
          ;; choice flows through every per-frame composite (App-DB
          ;; Diff, Views, Routing).
          ;;
          ;; Dispatches the canonical `:rf.xray/select-frame` event-fx
          ;; (NOT the spine's `:rf.xray/set-frame` primitive directly) —
          ;; every persistence / instrumentation layer attached to the
          ;; frame-switcher contract lives behind the one event surface,
          ;; so the palette + the L1 ribbon picker + headless tests all
          ;; share the same write path.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.xray/select-frame (first args)]])}

          :palette/inspect-handler
          ;; Phase 1: route to a Xray-side store of the inspected
          ;; handler choice. The handler-detail panel is itself a
          ;; follow-on bead — for now the store is enough so the
          ;; palette completes its part of the contract. The canonical
          ;; "what happened in this epoch" tab is `:epoch`, so the
          ;; selection lands there.
          (let [[kind id] args]
            {:db (assoc close-db
                        :selected-tab :epoch
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
            ;; The framework's per-frame rings + Xray's
            ;; frameless secondary ring + Xray's mirrored
            ;; `:trace-buffer` slot all drop together via
            ;; `trace-collector/retroactive-scrub!` (the same path
            ;; the egress-profile reveal → redact narrowing uses).
            ;; The slot dispatches a coalesced
            ;; clear so the panel re-renders on the standard
            ;; reactive path.
            (try (trace-collector/retroactive-scrub!) (catch :default _ nil))
            {:db close-db
             :fx base-fx})

          :palette/clear-epoch-history
          ;; Drop Xray's epoch ring. The slot lives in
          ;; Xray's app-db at `:epoch-history`; clearing it lets the
          ;; user start a fresh session without restarting the host
          ;; app. App-DB Diff + Views read off this slot so the next
          ;; epoch lands cleanly.
          {:db (dissoc close-db :epoch-history)
           :fx base-fx}

          :palette/reset-suppressed-counters
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.xray/reset-suppressed-counters]])}

          :palette/snapshot-app-db
          ;; Snapshot the SELECTED inspected frame's app-db
          ;; onto the console + clipboard. The target is the slot the L1
          ;; frame-picker writes (`:target-frame`). EP-0002 —
          ;; pass the slot value THROUGH (nil = unselected); the
          ;; `snapshot-app-db!` fx no-ops when unselected rather than
          ;; snapshotting a synthesised `:rf/default`.
          {:db close-db
           :fx (conj base-fx
                     [:rf.xray.palette.fx/snapshot-app-db
                      {:target-frame
                       (get db-with-recent :target-frame)}])}

          :palette/toggle-theme
          ;; Flip the Settings popup's theme slot via the
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
                      [:rf.xray/settings-update :theme nil
                       (next-theme (config/get-setting :theme nil))]])}

          :palette/cycle-reduced-motion
          ;; Cycle the user-side reduced-motion override
          ;; (:os → :always → :never → :os). Routes through the
          ;; settings-update event so the apply-fn + persist path are
          ;; the canonical ones — no parallel state.
          {:db close-db
           :fx (conj base-fx
                     [:dispatch
                      [:rf.xray/settings-update :general
                       :reduced-motion-override
                       (next-motion-override
                         (config/get-setting :general :reduced-motion-override))]])}

          :palette/jump-to-settings
          ;; Open the Settings popup. Routes through the
          ;; existing `:rf.xray/settings-open` event so the popup's
          ;; open path is the canonical one (lifts the atom snapshot
          ;; into app-db, defaults the active-tab to `:general`).
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.xray/settings-open]])}

          :palette/toggle-mode
          ;; Flip Dynamic ↔ Static. Routes through the
          ;; existing `:rf.xray/toggle-mode` event (also bound to
          ;; Cmd/Ctrl+Shift+M). Single source of truth for the
          ;; mode-flip + persistence side-effect.
          {:db close-db
           :fx (conj base-fx [:dispatch [:rf.xray/toggle-mode]])}

          :palette/open-popout
          ;; The :open-popout verb always pops out — guard against
          ;; double-fire when the user also held Ctrl/Meta (popout-now?
          ;; would have appended the popout fx to `base-fx`).
          {:db close-db
           :fx (cond-> base-fx
                 (not popout-now?)
                 (conj [:rf.xray.palette.fx/popout {}]))}

          :palette/close
          {:db close-db
           :fx base-fx}

          ;; Unknown verb — close the palette and log to console so
          ;; the dev sees the gap. Never silently swallow.
          (do
            (when (and (exists? js/console) (.-warn js/console))
              (.warn js/console
                     (str "[rf2-xray] palette: unknown action verb "
                          (pr-str verb))))
            {:db close-db
             :fx base-fx})))))

  nil)
