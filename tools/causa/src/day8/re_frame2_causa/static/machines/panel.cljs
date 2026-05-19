(ns day8.re-frame2-causa.static.machines.panel
  "Top-level Machines sub-tab for Causa's Static surface (rf2-o5f5f.2).

  ## Shape

  Master-detail layout. Per the bead's §Browse-all list / §Definition
  detail header:

      ┌──────────────────────┬────────────────────────────────────┐
      │ L4-left (~280px)     │  L4-right (fills)                  │
      │ ─ search box         │  ─ machine-id · source-coord ↗     │
      │ ─ sort cycle button  │     · N states · M live (→ Runtime)│
      │ ─ scrollable rows    │  ─ sub-strip [T][S][I][C]          │
      │                      │  ─ mode renderer (Topology / Sim   │
      │                      │     placeholder / Instances JUMP / │
      │                      │     Cascade dimmed)                │
      └──────────────────────┴────────────────────────────────────┘

  ## Registers (install! installs)

  Subs:
    `:rf.causa.static.machines/rows`        — full projected rows
    `:rf.causa.static.machines/data`        — composite the view reads
    `:rf.causa.static.machines/search`      — current search-text
    `:rf.causa.static.machines/sort-key`    — current sort axis
    `:rf.causa.static.machines/selected-id` — user's selection (raw slot)
    `:rf.causa.static.machines/sub-mode-by-id` — per-machine sub-mode map
    `:rf.causa.static.machines/sub-mode`    — effective sub-mode for a machine
    `:rf.causa.static.machines/sim-by-machine`         — sim slots map (rf2-r4nao)
    `:rf.causa.static.machines/sim-state`              — sim slot for selected machine
    `:rf.causa.static.machines/sim-active?`            — sim on for selected machine?
    `:rf.causa.static.machines/sim-available-transitions` — picker source
    `:rf.causa.static.machines/sim-event-suggestions`  — datalist source

  Events:
    `:rf.causa.static.machines/select`         — set selected-id
    `:rf.causa.static.machines/set-search`     — set search-text
    `:rf.causa.static.machines/clear-search`   — drop search-text
    `:rf.causa.static.machines/cycle-sort`     — cycle through sort axes
    `:rf.causa.static.machines/set-sub-mode`   — set the per-machine sub-mode
    `:rf.causa.static.machines/hydrate`        — hydrate selection + sub-modes
                                                  from localStorage
    `:rf.causa.static.machines/sim-start`      — clone definition + seed sim
    `:rf.causa.static.machines/sim-stop`       — dispose sim slot for mid
    `:rf.causa.static.machines/sim-reset`      — rewind sim snapshot
    `:rf.causa.static.machines/sim-step`       — fire one event into sim
    `:rf.causa.static.machines/sim-set-pending-event` — controlled input
    `:rf.causa.static.machines/sim-set-pending-data`  — controlled input

  Fxs:
    `:rf.causa.static.machines/persist-selection` — write selected-id to LS
    `:rf.causa.static.machines/persist-sub-mode`  — write {mid sub-mode} to LS

  ## Frame isolation

  Same discipline as every other Static panel — the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs` scopes
  subscribes / dispatches to Causa's frame."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.static.machines.browse-list :as browse-list]
            [day8.re-frame2-causa.static.machines.definition-detail
             :as definition-detail]
            [day8.re-frame2-causa.static.machines.helpers :as h]
            [day8.re-frame2-causa.static.machines.persistence :as persistence]
            [day8.re-frame2-causa.static.machines.sim :as sim]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack type-scale]]))

;; ---- panel layout -------------------------------------------------------

(def ^:private left-pane-width "280px")

(rf/reg-view panel
  "L4 detail-panel content for the Static Machines tab. Master-detail
  with a browse-all list on the left and the per-machine definition
  detail on the right.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  [:div {:data-testid "rf-causa-static-machines-panel"
         :style {:display          "flex"
                 :flex-direction   "row"
                 :height           "100%"
                 :background       (:bg-2 tokens)
                 :color            (:text-primary tokens)
                 :font-family      sans-stack
                 :font-size        (:body type-scale)}}
   ;; ---- left pane ----
   [:div {:data-testid "rf-causa-static-machines-left"
          :style {:flex          (str "0 0 " left-pane-width)
                  :min-width     left-pane-width
                  :max-width     left-pane-width
                  :height        "100%"
                  :overflow      "hidden"
                  :display       "flex"
                  :flex-direction "column"
                  :border-right  (str "1px solid " (:border-subtle tokens))
                  :background    (:bg-1 tokens)}}
    [browse-list/browse-list]]
   ;; ---- right pane ----
   [:div {:data-testid "rf-causa-static-machines-right"
          :style {:flex        "1 1 auto"
                  :min-width   "0"
                  :overflow    "auto"
                  :background  (:bg-2 tokens)}}
    [definition-detail/detail]]])

;; ---- subs ---------------------------------------------------------------

(defn- install-subs! []
  ;; Raw slots
  (rf/reg-sub :rf.causa.static.machines/selected-id
    (fn [db _query]
      (get db :rf.causa.static.machines/selected-id)))

  (rf/reg-sub :rf.causa.static.machines/search
    (fn [db _query]
      (or (get db :rf.causa.static.machines/search) "")))

  (rf/reg-sub :rf.causa.static.machines/sort-key
    (fn [db _query]
      (h/normalise-sort-key
        (get db :rf.causa.static.machines/sort-key h/default-sort-key))))

  ;; Per-machine sub-mode map: {machine-id sub-mode-kw}
  (rf/reg-sub :rf.causa.static.machines/sub-mode-by-id
    (fn [db _query]
      (or (get db :rf.causa.static.machines/sub-mode-by-id) {})))

  ;; Effective sub-mode for a machine. Default :topology when the
  ;; machine has no stored choice.
  (rf/reg-sub :rf.causa.static.machines/sub-mode
    :<- [:rf.causa.static.machines/sub-mode-by-id]
    (fn [by-id [_ machine-id]]
      (h/normalise-sub-mode
        (get by-id machine-id h/default-sub-mode))))

  ;; Composite — feeds the browse-list + detail header. Reads the
  ;; existing :rf.causa/registered-machines + machine-definitions +
  ;; machine-snapshots subs registered by panels.machine-inspector
  ;; (install order is purely cosmetic — re-frame resolves :<- lazily).
  ;; The `:rf.causa/machine-snapshots-override` test-seam composes on
  ;; top of the live snapshots — same shape the Runtime Machine
  ;; Inspector's composite uses, so the override flips both surfaces.
  (rf/reg-sub :rf.causa.static.machines/rows
    :<- [:rf.causa/registered-machines]
    :<- [:rf.causa/machine-definitions]
    :<- [:rf.causa/machine-snapshots]
    :<- [:rf.causa/machine-snapshots-override]
    (fn [[machines definitions live-snapshots snapshots-override] _query]
      (h/project-rows machines definitions
                      (or snapshots-override live-snapshots {}))))

  (rf/reg-sub :rf.causa.static.machines/data
    :<- [:rf.causa/registered-machines]
    :<- [:rf.causa/machine-definitions]
    :<- [:rf.causa/machine-snapshots]
    :<- [:rf.causa/machine-snapshots-override]
    :<- [:rf.causa.static.machines/search]
    :<- [:rf.causa.static.machines/sort-key]
    :<- [:rf.causa.static.machines/selected-id]
    (fn [[machines definitions live-snapshots snapshots-override
          query sort-key selected-id] _query]
      (h/project-browse-list machines definitions
                             (or snapshots-override live-snapshots {})
                             query sort-key selected-id)))
  nil)

;; ---- events -------------------------------------------------------------

(defn- install-events! []
  (rf/reg-event-fx :rf.causa.static.machines/select
    (fn [{:keys [db]} [_ machine-id]]
      (let [next-db (assoc db :rf.causa.static.machines/selected-id machine-id)]
        {:db next-db
         :fx [[:rf.causa.static.machines/persist-selection machine-id]]})))

  (rf/reg-event-db :rf.causa.static.machines/set-search
    (fn [db [_ query]]
      (assoc db :rf.causa.static.machines/search (or query ""))))

  (rf/reg-event-db :rf.causa.static.machines/clear-search
    (fn [db _event]
      (dissoc db :rf.causa.static.machines/search)))

  (rf/reg-event-db :rf.causa.static.machines/cycle-sort
    (fn [db _event]
      (let [current (h/normalise-sort-key
                      (get db :rf.causa.static.machines/sort-key))
            ix      (.indexOf h/sort-keys current)
            next-ix (mod (inc ix) (count h/sort-keys))
            next-k  (nth h/sort-keys next-ix)]
        (assoc db :rf.causa.static.machines/sort-key next-k))))

  (rf/reg-event-fx :rf.causa.static.machines/set-sub-mode
    (fn [{:keys [db]} [_ machine-id sub-mode]]
      (let [normed  (h/normalise-sub-mode sub-mode)
            next-db (assoc-in db [:rf.causa.static.machines/sub-mode-by-id
                                  machine-id]
                              normed)
            by-id   (get next-db :rf.causa.static.machines/sub-mode-by-id)]
        {:db next-db
         :fx [[:rf.causa.static.machines/persist-sub-mode by-id]]})))

  (rf/reg-event-db :rf.causa.static.machines/hydrate
    (fn [db [_ {:keys [selected-id sub-mode-by-id]}]]
      (cond-> db
        (some? selected-id)
        (assoc :rf.causa.static.machines/selected-id selected-id)
        (map? sub-mode-by-id)
        (assoc :rf.causa.static.machines/sub-mode-by-id sub-mode-by-id))))

  ;; Click-on-state in the Topology mode. v1 is a no-op slot — the
  ;; metadata rail wires in a follow-on bead (per the bead's §Topology
  ;; mode 'Click state → metadata rail'). The event is registered now
  ;; so the chart's `:on-state-click` dispatch lands on a known handler
  ;; rather than emitting a `:rf.warning/no-handler` trace.
  (rf/reg-event-db :rf.causa.static.machines/state-clicked
    (fn [db [_ _payload]] db))

  ;; Open-chart-popout — same posture: registered as a no-op slot so
  ;; the affordance has a landing handler. The pop-out window
  ;; orchestration rides the second-window UX bead.
  (rf/reg-event-db :rf.causa.static.machines/open-chart-popout
    (fn [db [_ _machine-id]] db))
  nil)

;; ---- public install -----------------------------------------------------

(defn install!
  "Idempotent install for the Static Machines sub-tab's reactive surface.
  Called from `registry.cljs/register-causa-handlers!`.

  Registers the subs / events that drive the browse-list + the per-
  machine sub-mode strip; installs the persistence fx so selection +
  sub-mode round-trip to localStorage; hydrates the slots from
  localStorage so the first render after a reload restores the prior
  state."
  []
  (install-subs!)
  (install-events!)
  (persistence/install-fx!)
  ;; Sim sub-mode engine (rf2-r4nao rehost; originally rf2-v869p
  ;; Phase 2). Installs the `:rf.causa.static.machines/sim-*` event +
  ;; sub family the Sim rail consumes.
  (sim/install!)
  ;; Hydrate from localStorage. The persistence ns guards storage
  ;; availability internally so the JVM test path is a no-op.
  (persistence/hydrate!)
  nil)
