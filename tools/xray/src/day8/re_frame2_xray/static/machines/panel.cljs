(ns day8.re-frame2-xray.static.machines.panel
  "Top-level Machines sub-tab for Xray's Static surface.

  ## Shape

  Master-detail layout (browse-all list · definition detail header):

      ┌──────────────────────┬────────────────────────────────────┐
      │ L4-left (~280px)     │  L4-right (fills)                  │
      │ ─ search box         │  ─ machine-id · source-coord ↗     │
      │ ─ sort cycle button  │     · N states · M live (→ Dynamic)│
      │ ─ scrollable rows    │  ─ sub-strip [T][S][I][C]          │
      │                      │  ─ mode renderer (Topology / Sim   │
      │                      │     placeholder / Instances JUMP / │
      │                      │     Cascade dimmed)                │
      └──────────────────────┴────────────────────────────────────┘

  ## Registers (install! installs)

  Subs:
    `:rf.xray.static.machines/rows`        — full projected rows
    `:rf.xray.static.machines/data`        — composite the view reads
    `:rf.xray.static.machines/search`      — current search-text
    `:rf.xray.static.machines/sort-key`    — current sort axis
    `:rf.xray.static.machines/selected-id` — user's selection (raw slot)
    `:rf.xray.static.machines/sub-mode-by-id` — per-machine sub-mode map
    `:rf.xray.static.machines/sub-mode`    — effective sub-mode for a machine
    `:rf.xray.static.machines/sim-by-machine`         — sim slots map
    `:rf.xray.static.machines/sim-state`              — sim slot for selected machine
    `:rf.xray.static.machines/sim-active?`            — sim on for selected machine?
    `:rf.xray.static.machines/sim-available-transitions` — picker source
    `:rf.xray.static.machines/sim-event-suggestions`  — datalist source
    `:rf.xray.static.machines/copy-mermaid-status`    — copy feedback for a machine

  Events:
    `:rf.xray.static.machines/select`         — set selected-id
    `:rf.xray.static.machines/set-search`     — set search-text
    `:rf.xray.static.machines/clear-search`   — drop search-text
    `:rf.xray.static.machines/cycle-sort`     — cycle through sort axes
    `:rf.xray.static.machines/set-sub-mode`   — set the per-machine sub-mode
    `:rf.xray.static.machines/hydrate`        — hydrate selection + sub-modes
                                                  from localStorage
    `:rf.xray.static.machines/sim-start`      — clone definition + seed sim
    `:rf.xray.static.machines/sim-stop`       — dispose sim slot for mid
    `:rf.xray.static.machines/sim-reset`      — rewind sim snapshot
    `:rf.xray.static.machines/sim-step`       — fire one event into sim
    `:rf.xray.static.machines/sim-set-pending-event` — controlled input
    `:rf.xray.static.machines/sim-set-pending-data`  — controlled input
    `:rf.xray.static.machines/copy-mermaid`          — emit + copy to clipboard
    `:rf.xray.static.machines/copy-mermaid-done`     — record copy outcome

  Fxs:
    `:rf.xray.static.machines/persist-selection` — write selected-id to LS
    `:rf.xray.static.machines/persist-sub-mode`  — write {mid sub-mode} to LS

  ## Frame isolation

  Same discipline as every other Static panel — the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs` scopes
  subscribes / dispatches to Xray's frame.

  ## Substrate (rf2-k97c.3)

  This sub-tab is THREE Fresco boundaries, not one: [[panel]] here plus
  `browse-list/browse-list` and `definition-detail/detail`, each an
  `rf.fresco/defview`. The count is not an accident of file layout — it
  is the reactive granularity the three `reg-view`s already had, kept.
  Collapsing the two panes into this one boundary would move their reads
  up here, and every search keystroke would then re-render the Topology
  chart. Boundary count tracks READS and head-position use.

  [[panel]] itself reads NOTHING. It is pure two-pane chrome, and it
  heads the two pane boundaries directly — which is legal, and is the
  one hiccup head shape that IS: `codec/boundary-head?` reads one own
  property (`frescoBoundary`) that only `rf.fresco/defview` sets, so a
  plain `defn` AND an `rf/reg-view` both grade `:invalid` down the same
  arm while a boundary heads a boundary cleanly."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.machines.browse-list :as browse-list]
            [day8.re-frame2-xray.static.machines.definition-detail
             :as definition-detail]
            [day8.re-frame2-xray.static.machines.helpers :as h]
            [day8.re-frame2-xray.static.machines.persistence :as persistence]
            [day8.re-frame2-xray.static.machines.sim :as sim]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack type-scale]]))

;; ---- panel layout -------------------------------------------------------

(def ^:private left-pane-width "280px")

(defn panel-tree
  "The Static Machines sub-tab's two-pane CHROME, as a pure function of
  the two pane bodies. Genuinely shared between the two lanes rather
  than reproduced for them: [[panel]] passes the two BOUNDARY-headed
  vectors, and `test-helpers.static-machines-tree` passes the two panes'
  already-expanded plain hiccup. The chrome — the testids, the widths,
  the borders — is one definition either way, so a node-lane row that
  walks it is walking the real thing.

  SPLIT OUT OF [[panel]] BY rf2-k97c.3, for the reason every migrated
  panel splits: a boundary's body may only run inside a React render
  window, so `(panel)` is no longer a callable that answers hiccup."
  [left right]
  [:div {:data-testid "rf-xray-static-machines-panel"
         :style {:display          "flex"
                 :flex-direction   "row"
                 :height           "100%"
                 :background       (:bg-2 tokens)
                 :color            (:text-primary tokens)
                 :font-family      sans-stack
                 :font-size        (:body type-scale)}}
   ;; ---- left pane ----
   [:div {:data-testid "rf-xray-static-machines-left"
          :style {:flex          (str "0 0 " left-pane-width)
                  :min-width     left-pane-width
                  :max-width     left-pane-width
                  :height        "100%"
                  :overflow      "hidden"
                  :display       "flex"
                  :flex-direction "column"
                  :border-right  (str "1px solid " (:border-subtle tokens))
                  :background    (:bg-1 tokens)}}
    left]
   ;; ---- right pane ----
   [:div {:data-testid "rf-xray-static-machines-right"
          :style {:flex        "1 1 auto"
                  :min-width   "0"
                  :overflow    "auto"
                  :background  (:bg-2 tokens)}}
    right]])

(rf.fresco/defview panel
  "L4 detail-panel content for the Static Machines tab — a FRESCO
  BOUNDARY (rf2-k97c.3), not an `rf/reg-view`. Master-detail with a
  browse-all list on the left and the per-machine definition detail on
  the right.

  IT READS NOTHING, and it is still a boundary rather than a plain fn
  for two reasons. First, it is what the L4 registry mounts, so it is
  where the Reagent→Fresco crossing has to sit — one bridge for the
  whole sub-tab. Second, a boundary is the only legal hiccup head for
  the two pane boundaries below it.

  THE NAME IS LOWERCASE `panel`, deliberately and normatively. Every
  other panel namespace exports a `Panel`; this one is the documented
  exception (`tools/xray/spec/API.md` §Static-mode Panel reg-views), and
  BOTH `spec/api-manifest.edn` and its curated
  `spec/api-manifest-metadata.edn` sidecar row
  `day8.re-frame2-xray.static.machines.panel/panel` with
  `:runtime-verified? true`. Both are hot zone. Keeping the natural name
  on the BOUNDARY — the #9581 spelling the mayor's RULING 1 fixed as the
  surviving one — means neither file moves.

  The argument is the ordinary one-props-map vector every `defview`
  takes. The L4 registry mounts it with none, so it is destructured
  away."
  [_props]
  (panel-tree [browse-list/browse-list] [definition-detail/detail]))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; Xray's Static shell is still a `reg-view` tree rendered by the installed
;; adapter. `static/shell.cljs`'s `detail-panel` mounts the active tab as
;; the hiccup head `[(:panel tab)]`, and `panel-registry/reg-l4-tab!`'s
;; `:pre` requires `:panel` to be CALLABLE — neither of which a React
;; component is.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; it answers a real React component for a boundary, which a React parent
;; (Reagent, UIx or plain JavaScript) mounts UNDER THE FRAME IT IS ALREADY
;; IN, taking the frame from React context rather than from a second root.
;; So there is no second root here, no adapter-kind branch, and no props
;; ABI.
;;
;; BOTH DEFS ARE PRIVATE, and that is measured rather than defaulted:
;; `panel` is named outside this file only in `static/shell.cljs`'s PROSE
;; (a docstring listing the L4 tabs) and in the two `spec/api-manifest*.edn`
;; rows — never mounted or called by name. The L4 registry is the only
;; consumer, and `install!` below is the only thing that passes the bridge.
;; A panel carrying a standalone `mount-*!` facade would need a PUBLIC
;; bridge instead, because `panels/render-panel!` takes the view to mount
;; as an argument and needs a name to pass; this panel has none —
;; `panels.cljs` names no Static sub-tab, so no caller line changes and
;; nothing outside `tools/xray/{src,test}/…/static/machines/` is touched.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When the Static shell is itself
;; a Fresco tree, `reg-l4-tab!` takes `panel` directly, `[:>]` goes, and
;; both defs below are deleted.

(def ^:private panel-component
  "The React component `panel` presents as, for a non-Fresco parent.
  Declared once at top level beside the view, as `rf.fresco/as-component`'s
  contract requires — deriving it per render would mint a new component
  type every time and remount the panel on each parent render."
  (rf.fresco/as-component panel))

(defn ^:private panel-bridge
  "The callable the L4 tab registry stores. Returns Reagent-shaped hiccup
  interoping to the React component above; the Static shell's enclosing
  `rf/frame-provider` is what puts `:rf/xray` in React context for it."
  []
  [:> panel-component {}])

;; ---- subs ---------------------------------------------------------------

(defn- install-subs! []
  ;; Raw slots
  (rf/reg-sub :rf.xray.static.machines/selected-id
    (fn [db _query]
      (get db :rf.xray.static.machines/selected-id)))

  (rf/reg-sub :rf.xray.static.machines/search
    (fn [db _query]
      (or (get db :rf.xray.static.machines/search) "")))

  (rf/reg-sub :rf.xray.static.machines/sort-key
    (fn [db _query]
      (h/normalise-sort-key
        (get db :rf.xray.static.machines/sort-key h/default-sort-key))))

  ;; Per-machine sub-mode map: {machine-id sub-mode-kw}
  (rf/reg-sub :rf.xray.static.machines/sub-mode-by-id
    (fn [db _query]
      (or (get db :rf.xray.static.machines/sub-mode-by-id) {})))

  ;; Effective sub-mode for a machine. Default :topology when the
  ;; machine has no stored choice.
  (rf/reg-sub :rf.xray.static.machines/sub-mode
    {:inputs [[:rf.xray.static.machines/sub-mode-by-id]]}
    (fn [[by-id] [_ machine-id]]
      (h/normalise-sub-mode
        (get by-id machine-id h/default-sub-mode))))

  ;; Copy-Mermaid feedback for ONE machine (rf2-sxw06). The db slot is a
  ;; single `{:machine-id <mid> :status :pending|:copied|:failed}` map —
  ;; keyed answers for any OTHER machine read nil, so a stale outcome can
  ;; never masquerade as feedback about the machine on screen. `:pending`
  ;; is deliberately surfaced as nil too: the header span only ever
  ;; renders a SETTLED outcome, so an in-flight write can't be mistaken
  ;; for a completed copy.
  (rf/reg-sub :rf.xray.static.machines/copy-mermaid-status
    (fn [db [_ machine-id]]
      (let [{mid :machine-id status :status}
            (get db :rf.xray.static.machines/copy-mermaid-status)]
        (when (and (some? machine-id)
                   (= mid machine-id)
                   (contains? #{:copied :failed} status))
          status))))

  ;; Composite — feeds the browse-list + detail header. Reads the
  ;; existing :rf.xray/registered-machines + machine-definitions +
  ;; machine-snapshots subs registered by panels.machine-inspector
  ;; (install order is purely cosmetic — re-frame resolves declared inputs lazily).
  ;; The `:rf.xray/machine-snapshots-override` test-seam composes on top
  ;; of the live snapshots in `install-test-overrides!` —
  ;; production registration carries no override branch.
  (rf/reg-sub :rf.xray.static.machines/rows
    {:inputs [[:rf.xray/registered-machines]
              [:rf.xray/machine-definitions]
              [:rf.xray/machine-snapshots]]}
    (fn [[machines definitions live-snapshots] _query]
      (h/project-rows machines definitions (or live-snapshots {}))))

  (rf/reg-sub :rf.xray.static.machines/data
    {:inputs [[:rf.xray/registered-machines]
              [:rf.xray/machine-definitions]
              [:rf.xray/machine-snapshots]
              [:rf.xray.static.machines/search]
              [:rf.xray.static.machines/sort-key]
              [:rf.xray.static.machines/selected-id]]}
    (fn [[machines definitions live-snapshots query sort-key selected-id] _query]
      (h/project-browse-list machines definitions (or live-snapshots {})
                             query sort-key selected-id)))
  nil)

;; ---- events -------------------------------------------------------------

(defn- install-events! []
  (rf/reg-event :rf.xray.static.machines/select
    (fn [{:keys [db]} [_ machine-id]]
      ;; Selection change also clears the Copy-Mermaid feedback span
      ;; (rf2-sxw06) — feedback is about ONE machine's copy gesture and
      ;; must not survive onto another machine's header.
      (let [next-db (-> db
                        (assoc :rf.xray.static.machines/selected-id machine-id)
                        (dissoc :rf.xray.static.machines/copy-mermaid-status))]
        {:db next-db
         :fx [[:rf.xray.static.machines/persist-selection machine-id]]})))

  (rf/reg-event :rf.xray.static.machines/set-search
    (fn [{:keys [db]} [_ query]]
      {:db (assoc db :rf.xray.static.machines/search (or query ""))}))

  (rf/reg-event :rf.xray.static.machines/clear-search
    (fn [{:keys [db]} _event]
      {:db (dissoc db :rf.xray.static.machines/search)}))

  (rf/reg-event :rf.xray.static.machines/cycle-sort
    (fn [{:keys [db]} _event]
      {:db (let [current (h/normalise-sort-key
                      (get db :rf.xray.static.machines/sort-key))
            ix      (.indexOf h/sort-keys current)
            next-ix (mod (inc ix) (count h/sort-keys))
            next-k  (nth h/sort-keys next-ix)]
        (assoc db :rf.xray.static.machines/sort-key next-k))}))

  (rf/reg-event :rf.xray.static.machines/set-sub-mode
    (fn [{:keys [db]} [_ machine-id sub-mode]]
      (let [normed  (h/normalise-sub-mode sub-mode)
            next-db (assoc-in db [:rf.xray.static.machines/sub-mode-by-id
                                  machine-id]
                              normed)
            by-id   (get next-db :rf.xray.static.machines/sub-mode-by-id)]
        {:db next-db
         :fx [[:rf.xray.static.machines/persist-sub-mode by-id]]})))

  (rf/reg-event :rf.xray.static.machines/hydrate
    (fn [{:keys [db]} [_ {:keys [selected-id sub-mode-by-id]}]]
      {:db (cond-> db
        (some? selected-id)
        (assoc :rf.xray.static.machines/selected-id selected-id)
        (map? sub-mode-by-id)
        (assoc :rf.xray.static.machines/sub-mode-by-id sub-mode-by-id))}))

  ;; Click-on-state in the Topology mode. v1 is a no-op slot — the
  ;; metadata rail wires in a follow-on bead (per the bead's §Topology
  ;; mode 'Click state → metadata rail'). The event is registered now
  ;; so the chart's `:on-state-click` dispatch lands on a known handler
  ;; rather than emitting a `:rf.warning/no-handler` trace.
  (rf/reg-event :rf.xray.static.machines/state-clicked
    (fn [{:keys [db]} [_ _payload]] {:db db}))

  ;; Open-chart-popout — same posture: registered as a no-op slot so
  ;; the affordance has a landing handler. The pop-out window
  ;; orchestration rides the second-window UX bead.
  (rf/reg-event :rf.xray.static.machines/open-chart-popout
    (fn [{:keys [db]} [_ _machine-id]] {:db db}))

  ;; ---- Copy Mermaid (rf2-sxw06) -----------------------------------------
  ;;
  ;; The definition-detail header's one-gesture "copy this registered
  ;; topology as Mermaid" action. The HOST owns the gesture: the view
  ;; passes the selected machine's definition (which it already holds)
  ;; straight to the pure `mermaid/emit`, and the fenced markdown block
  ;; crosses the clipboard through the existing Xray-owned
  ;; `:rf.xray.fx/copy-to-clipboard` fx — MachineChart stays
  ;; presentation-only and gains no registry subscription.
  ;;
  ;; Egress posture: the copied text is STATIC TOPOLOGY ONLY — state /
  ;; event / guard / action NAMES from the registered definition, never
  ;; runtime or definition `:data` values (`mermaid/emit` is value-free
  ;; by contract; its invalid-definition diagnostic is value-free too,
  ;; rf2-8nzxib). This is therefore NOT a value-egress site, so the text
  ;; rides the fx directly rather than through `egress/egress-value` —
  ;; routing it there would `pr-str` the block (breaking the exact-emit
  ;; contract) without ever finding a value to elide. Since rf2-6r9j.24
  ;; this is the only gesture in Xray that reaches the clipboard fx.
  ;;
  ;; `emit` throws on a definition it cannot project. The header already
  ;; gates the control on `grammar/valid-definition?` (whose truth means
  ;; emit cannot throw), but the handler still catches — a race between
  ;; render and a registry mutation must land as honest `:failed`
  ;; feedback, never as an unhandled event error.
  (rf/reg-event :rf.xray.static.machines/copy-mermaid
    (fn [{:keys [db]} [_ machine-id definition]]
      (let [md (try (mermaid/emit definition) (catch :default _ nil))]
        (if (some? md)
          {:db (assoc db :rf.xray.static.machines/copy-mermaid-status
                      {:machine-id machine-id :status :pending})
           :fx [[:rf.xray.fx/copy-to-clipboard
                 {:text       md
                  :on-success [:rf.xray.static.machines/copy-mermaid-done
                               machine-id :copied]
                  :on-failure [:rf.xray.static.machines/copy-mermaid-done
                               machine-id :failed]}]]}
          {:db (assoc db :rf.xray.static.machines/copy-mermaid-status
                      {:machine-id machine-id :status :failed})}))))

  ;; Records the SETTLED clipboard outcome. The write is guarded on the
  ;; machine still being selected: the async settlement can land after
  ;; the user has moved on, and feedback about a machine no longer on
  ;; screen must not repopulate the slot `select` just cleared.
  (rf/reg-event :rf.xray.static.machines/copy-mermaid-done
    (fn [{:keys [db]} [_ machine-id status]]
      (if (= machine-id (get db :rf.xray.static.machines/selected-id))
        {:db (assoc db :rf.xray.static.machines/copy-mermaid-status
                    {:machine-id machine-id :status status})}
        {:db db})))
  nil)

;; ---- public install -----------------------------------------------------

(defn install!
  "Idempotent install for the Static Machines sub-tab's reactive surface.
  Called from `registry.cljs/register-xray-handlers!`.

  Registers the subs / events that drive the browse-list + the per-
  machine sub-mode strip; installs the persistence fx so selection +
  sub-mode round-trip to localStorage; hydrates the slots from
  localStorage so the first render after a reload restores the prior
  state."
  []
  (install-subs!)
  (install-events!)
  (persistence/install-fx!)
  ;; Sim sub-mode engine. Installs the `:rf.xray.static.machines/sim-*`
  ;; event + sub family the Sim rail consumes.
  (sim/install!)
  ;; Hydrate from localStorage. The persistence ns guards storage
  ;; availability internally so the JVM test path is a no-op, and guards
  ;; on the shell frame being registered so this orchestrator-time call
  ;; short-circuits cleanly when it isn't (rf2-qw0o). On the production
  ;; path it isn't: `register-xray-handlers!` runs well before
  ;; `mount/ensure-xray-frame!`, whose `::hydrate-static-machines`
  ;; first-mount hook is the call that actually lands the restore. This
  ;; call stays for the paths where the frame ALREADY exists when the
  ;; handlers (re-)register — a shadow-cljs `:after-load`, or a test
  ;; installing handlers against a live frame.
  (persistence/hydrate!)
  ;; Register the Static Machines tab with the internal L4 tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :machines
     :label "Machines"
     :mnem  "m"
     :modes #{:static}
     :order 0
     ;; rf2-k97c.3 — `panel-bridge`, not `panel`. `panel` is now a React
     ;; component (a Fresco boundary) and the Static shell mounts
     ;; `:panel` as a Reagent hiccup head; the bridge is the one line
     ;; between them and goes when the shell is a Fresco tree.
     :panel panel-bridge})
  nil)

;; ---- test-only override seam --------------------------------------------

(defn install-test-overrides!
  "Re-register the Static Machines browse-list subs to layer the
  `:rf.xray/machine-snapshots-override` test-seam on top of the live
  snapshots — same shape the Dynamic Machine Inspector's composite uses,
  so the override flips both surfaces. The override event + `*-override`
  sub themselves are owned by `panels.machine-inspector/install-test-
  overrides!`; this only re-points the two static-machines composites.
  Tests opt in via `test-support/install-test-overrides!` AFTER
  `register-xray-handlers!`. **Test-only — never call from production.**"
  []
  (rf/reg-sub :rf.xray.static.machines/rows
    {:inputs [[:rf.xray/registered-machines]
              [:rf.xray/machine-definitions]
              [:rf.xray/machine-snapshots]
              [:rf.xray/machine-snapshots-override]]}
    (fn [[machines definitions live-snapshots snapshots-override] _query]
      (h/project-rows machines definitions
                      (or snapshots-override live-snapshots {}))))

  (rf/reg-sub :rf.xray.static.machines/data
    {:inputs [[:rf.xray/registered-machines]
              [:rf.xray/machine-definitions]
              [:rf.xray/machine-snapshots]
              [:rf.xray/machine-snapshots-override]
              [:rf.xray.static.machines/search]
              [:rf.xray.static.machines/sort-key]
              [:rf.xray.static.machines/selected-id]]}
    (fn [[machines definitions live-snapshots snapshots-override
          query sort-key selected-id] _query]
      (h/project-browse-list machines definitions
                             (or snapshots-override live-snapshots {})
                             query sort-key selected-id)))
  nil)
