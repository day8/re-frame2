(ns boot.boot
  "Boot the app with one state machine that owns the whole startup graph.

   See the machines guide for the model — `:spawn` / `:spawn-all`
   (docs/machines/glossary.md#spawn), states and transitions
   (docs/machines/glossary.md#state), and the machine snapshot
   (docs/machines/glossary.md#snapshot).

   The boot machine is `:app/boot`. It runs through four states:

     :configuring   → fetch /config via a single `:spawn`d child loader.
     :loading-deps  → fan out THREE parallel child loads (routes,
                      feature flags, initial user) via `:spawn-all`.
                      The parent advances only when every child is done.
     :hydrating     → write the loaded payloads into top-level app-db
                      slices (`:config`, `:flags`, `:user`, `:routes`),
                      then self-transition to `:ready`.
     :ready         → terminal. The main view watches the boot state and
                      unblocks once it reads `:ready`.

   Any child failure transitions the machine to `:failed` (terminal),
   with the failure recorded in `:data :error`.

   The child loader is the reusable `:boot/loader` machine — one spec,
   four instances. Each instance carries its own identity (parent-id,
   child-id, staging-key, URL) in `:data`, planted by the parent's
   per-child `:data` fn. A `:data` fn receives the parent's snapshot, so
   a child's URL can depend on values the parent has already loaded.

   That is how config flows downstream. The `:configuring` config load
   returns an `:api-base`; the `:promote-staged` action records it into
   the boot machine's `:data`; the three `:loading-deps` children then
   read that `:api-base` off the parent's snapshot and build their URLs
   from it. The boot machine is the only place that reads host config —
   everything downstream threads it through a snapshot `:data` fn,
   never reaching into a host global from an action body.

   Each child fetches via managed HTTP
   (docs/resources/glossary.md#managed-http). On success it writes its
   payload into the boot machine's staging slot at
   `[:boot/staging <staging-key>]`, then dispatches its completion
   event back to the parent.

   Why stage into app-db rather than carry payloads on the join event:
   the runtime intercepts a `:spawn-all`'s `:on-child-done` /
   `:on-child-error` events for join bookkeeping only — they are NOT
   fed into the parent's `:on` lookup, and the synthesised
   join-resolution event (`:on-all-complete [:boot/deps-ready]`)
   carries no per-child payload. So each child writes its result into a
   known app-db slice and the parent reads that slice once the join
   resolves. The loaded data lives in app-db throughout — inspectable
   in tools and snapshottable for SSR hydration.

   The single `:spawn` in `:configuring` is different: its completion
   event IS fed into the parent's `:on` lookup (only `:spawn-all` join
   events are intercepted). So the config child carries its payload on
   the completion event, and `:promote-staged` reads it from there.

   Start the boot once at app start via `[:app/boot [:rf.machine/start]]`.
   That creation marker runs the initial-entry cascade and seeds the
   machine snapshot in runtime-db (docs/guide/glossary.md#runtime-db)."
  (:require [re-frame.core :as rf]
            ;; State machines ship in a separate artefact. The require
            ;; registers `rf/reg-machine` and friends.
            [re-frame.machines]
            ;; Managed HTTP ships in a separate artefact. The require
            ;; registers the fx; without it the child loaders can't
            ;; issue requests.
            [re-frame.http.managed]
            [boot.schema :as schema]))

;; ============================================================================
;; STAGING-SLOT WRITER
;; ============================================================================
;;
;; A :spawn-all child hands its payload back to the parent through a staging
;; slot in app-db, not on the completion event (the runtime intercepts
;; :spawn-all join events and never feeds them into the parent's :on lookup —
;; see the ns docstring). Each child writes into `[:boot/staging <staging-key>]`
;; just before signalling completion; the parent reads the whole slot once on
;; the :hydrating transition. The loaded data sits in app-db the whole time,
;; inspectable in tools and snapshottable for SSR.

(rf/reg-event :boot/stage-payload
  {:doc "Write a child-loaded payload into the boot machine's
         staging slot. Dispatched by the :boot/loader child's
         :dispatch-done action just before the completion event."}
  (fn handler-boot-stage-payload [{:keys [db]} [_ staging-key payload]]
    {:db (assoc-in db [:boot/staging staging-key] payload)}))

;; ============================================================================
;; CHILD LOADER MACHINE — :boot/loader
;; ============================================================================
;;
;; The reusable child machine. One spec, four instances. Each instance
;; carries its own `:data` map (`:parent-id`, `:child-id`, `:staging-key`,
;; `:url`) planted by the parent's per-child `:data` fn. The child spawns in
;; `:idle`; the runtime synthesises a `:rf.machine.spawn/spawned` event that
;; moves it to `:loading`, which fires the `:begin-fetch` entry action.

(rf/reg-machine :boot/loader
  {:initial :idle
   ;; Validate each spawned loader's `:data` at spawn time. The loader is
   ;; only ever spawned (one instance per asset, with a generated id like
   ;; `:boot/loader#0`), so its snapshot sits at a per-instance path no
   ;; fixed app-schema can reach. The machine `[:schemas :data]` slot
   ;; checks `:data` instead. The per-child `:data` fn (see :app/boot below)
   ;; replaces this base map with the planted identity at spawn.
   :schemas {:data schema/LoaderData}
   :data    {:parent-id   nil
             :child-id    nil
             :staging-key nil
             :url         nil
             :payload     nil
             :error       nil}

   :actions
   {:begin-fetch
    ;; Issue the GET. The reply routes back to THIS child via
    ;; :rf/self-id, which the spawn fx stamps into :data. The reply lands
    ;; at the machine's `:asset/replied` event, so the machine branches on
    ;; success vs failure before forwarding to the parent.
    (fn [{data :data}]
      (let [self-id (:rf/self-id data)]
        {:fx [[:rf.http/managed
               {:request    {:method :get :url (:url data)}
                :decode     :json
                :on-success [self-id [:asset/replied :success]]
                :on-failure [self-id [:asset/replied :failure]]}]]}))

    :dispatch-done
    ;; Terminal-state entry. First write the loaded payload into
    ;; [:boot/staging <staging-key>], then dispatch the completion event
    ;; back to the parent. The completion event carries both the child-id
    ;; and the payload:
    ;;   - In :configuring (a single :spawn), this event lands in the
    ;;     parent's :on lookup, where :promote-staged reads the payload
    ;;     off the event to thread `:api-base` into the next phase.
    ;;   - In :loading-deps (a :spawn-all), the runtime intercepts this
    ;;     event for join bookkeeping only — it is NOT fed into the
    ;;     parent's :on lookup. Those payloads reach the parent via the
    ;;     staging slot read in :enter-hydrating. The event payload is
    ;;     harmless there.
    (fn [{data :data}]
      {:fx [[:dispatch [:boot/stage-payload (:staging-key data) (:payload data)]]
            [:dispatch [(:parent-id data)
                        [:boot/asset-loaded (:child-id data) (:payload data)]]]]})

    :dispatch-error
    ;; Terminal failure-state entry. Forwards the failure to the parent's
    ;; :on-child-error slot; the parent's :on-any-failed routes it to
    ;; `:failed`.
    (fn [{data :data}]
      {:fx [[:dispatch [(:parent-id data)
                        [:boot/asset-failed (:child-id data) (:error data)]]]]})}

   :states
   {;; :idle is the :initial state. The spawn fx synthesises a
    ;; [:rf.machine.spawn/spawned] event into the new child; :idle's
    ;; transition picks it up and moves to :loading, which fires the
    ;; :begin-fetch entry action.
    :idle
    {:on {:rf.machine.spawn/spawned :loading}}

    :loading
    {:entry :begin-fetch
     :on    {:asset/replied
             ;; Guarded fork on the reply kind: the first branch whose
             ;; `:guard` passes wins. The runtime appends the reply payload
             ;; as the last element of the event vector, so the event arrives
             ;; as [:asset/replied :success {:kind :success :value ...}]
             ;; — pick the value out of the 3rd-position arg.
             [{:guard (fn [{ev :event}] (= :success (nth ev 1 nil)))
               :target :done
               :action (fn [{data :data [_ _ reply] :event}]
                         {:data (assoc data :payload (:value reply))})}
              {:target :failed
               :action (fn [{data :data [_ _ reply] :event}]
                         {:data (assoc data :error (:failure reply))})}]}}

    :done   {:entry :dispatch-done   :meta {:terminal? true}}
    :failed {:entry :dispatch-error  :meta {:terminal? true}}}})

;; ============================================================================
;; BOOT MACHINE — :app/boot
;; ============================================================================

(rf/reg-machine :app/boot
  {:initial :configuring
   ;; Validate the `:app/boot` snapshot's `:data` slot. `BootData`
   ;; describes `:data` only. The snapshot lives in runtime-db, so its
   ;; `[:schemas :data]` slot is the validation surface (not an
   ;; app-schema), same as the `:boot/loader` child above.
   :schemas {:data schema/BootData}
   :data    {:phase  :configuring
             :config nil
             :flags  nil
             :user   nil
             :routes nil
             :error  nil}

   :actions
   {:record-failure
    (fn [{data :data [_ _child-id failure] :event}]
      {:data (assoc data :error failure)})

    :promote-staged
    ;; Runs on the :configuring → :loading-deps transition. The config
    ;; child carried its payload on the `:boot/asset-loaded` completion
    ;; event (a single :spawn's completion event IS fed into the parent's
    ;; :on lookup), so this action reads the config off the event and
    ;; records it into the boot machine's :data. The next phase's `:data`
    ;; fns then read the loaded `:api-base` and build each child's URL
    ;; from it. The transition's `:action` runs before the child `:data`
    ;; fns, so they see the value in the parent's snapshot.
    (fn [{data :data [_ _child-id config] :event}]
      {:data (assoc data :config config)})

    :enter-hydrating
    ;; Read the staged payloads out of [:boot/staging ...] and write them
    ;; into the top-level slices the running app's subs read. Self-
    ;; transitions to `:ready` once the hydration write lands.
    (fn [{data :data}]
      {:data (assoc data :phase :hydrating)
       :fx   [[:dispatch [:boot/apply-hydration]]]})}

   :states
   {;; ---- :configuring — the :initial state; a single :spawn fetches /config
    ;; The boot machine is born directly into its first work state. The
    ;; kick `[:app/boot [:rf.machine/start]]` is a creation marker: it runs
    ;; the initial-entry cascade — here `:configuring`'s `:spawn` — and
    ;; stops. It is never fed into an `:on` map as a trigger, so there is no
    ;; `:idle` parking spot: the machine starts working the moment it is
    ;; kicked.
    :configuring
    {:spawn {:machine-id :boot/loader
              ;; `:data` admits a function form
              ;; `(fn [{:keys [snapshot event]}] data)`, so the child's
              ;; initial :data can depend on the parent's snapshot at the
              ;; moment of entry. Plant the identity (parent / child /
              ;; staging-key / URL) here. This first URL is the fixed config
              ;; endpoint — the source the rest of the boot threads from —
              ;; so it is a literal, not derived.
              :data       (fn boot-config-data [_]
                            {:parent-id   :app/boot
                             :child-id    :config
                             :staging-key :config
                             :url         "/api/config.json"})}
     :on     {:boot/asset-loaded {:target :loading-deps
                                  :action :promote-staged}
              :boot/asset-failed {:target :failed
                                  :action :record-failure}}}

    ;; ---- :loading-deps — :spawn-all fans out THREE parallel children ---
    :loading-deps
    {:spawn-all
     {:children
      ;; Each child is the same :boot/loader machine, distinguished only
      ;; by its :data slot. Each :data fn reads the parent's `:api-base`
      ;; (recorded by :promote-staged on the way into this state) and
      ;; builds the child's URL from it.
      [{:id         :routes
        :machine-id :boot/loader
        :data       (fn boot-routes-data [{snap :snapshot}]
                      ;; The :data fn receives `{:snapshot :event}`. The
                      ;; snapshot is the parent's value, not app-db.
                      ;; :promote-staged already ran, so the loaded
                      ;; `:api-base` is visible here.
                      {:parent-id   :app/boot
                       :child-id    :routes
                       :staging-key :routes
                       :url         (str (-> snap :data :config :api-base) "/routes.json")})}
       {:id         :flags
        :machine-id :boot/loader
        :data       (fn boot-flags-data [{snap :snapshot}]
                      {:parent-id   :app/boot
                       :child-id    :flags
                       :staging-key :flags
                       :url         (str (-> snap :data :config :api-base) "/flags.json")})}
       {:id         :user
        :machine-id :boot/loader
        :data       (fn boot-user-data [{snap :snapshot}]
                      {:parent-id   :app/boot
                       :child-id    :user
                       :staging-key :user
                       :url         (str (-> snap :data :config :api-base) "/user.json")})}]
      :join             :all
      :on-child-done    :boot/asset-loaded
      :on-child-error   :boot/asset-failed
      :on-all-complete  [:boot/deps-ready]
      :on-any-failed    [:boot/deps-failed]}
     :on    {:boot/deps-ready  {:target :hydrating}
             :boot/deps-failed {:target :failed
                                :action :record-failure}}}

    ;; ---- :hydrating — applies the loaded payloads into app-db ----------
    :hydrating
    {:entry :enter-hydrating
     :on    {:boot/hydrated {:target :ready}}}

    ;; ---- :ready / :failed — terminal -------------------------------------
    :ready  {:meta {:terminal? true}}
    :failed {;; :failed is re-entrant: dispatching `[:app/boot [:boot/restart]]`
             ;; from the failure screen re-runs the boot from :configuring.
             ;; Re-boot uses a real event, not the `:rf.machine/start`
             ;; creation marker — the marker is never fed into an `:on` map,
             ;; so once the machine exists it is inert. The terminal? meta
             ;; stays true so visualisers and conformance harnesses see
             ;; :failed as a terminal state; the re-entry transition is the
             ;; explicit re-boot, not the default end of the flow.
             :meta {:terminal? true}
             :on   {:boot/restart {:target :configuring
                                   :action (fn [{data :data}]
                                             {:data (assoc data :error nil)})}}}}})

;; ============================================================================
;; HYDRATION PROMOTION
;; ============================================================================
;;
;; A plain reg-event does the cross-slice writes the boot machine's
;; :hydrating action dispatches. Keeping these reads and writes in an
;; explicit handler (not in the machine's action body) makes the boot trace
;; one step at a time: `:enter-hydrating` is one trace, this handler another.

(rf/reg-event :boot/apply-hydration
  {:doc "Promote every staged child payload at [:boot/staging ...]
         into the top-level app-db slices the running app reads. Fires
         :boot/hydrated back at :app/boot to transition from :hydrating
         to :ready."}
  ;; The app slices land in app-db (`:db`); the boot machine's snapshot is
  ;; runtime-db state, so the snapshot mirror below is a `:rf.db/runtime`
  ;; effect (docs/guide/glossary.md#runtime-db).
  ;;
  ;; ADVANCED — runtime-db writes are NOT an everyday-app pattern. This is the
  ;; only handler in the examples tree that returns a `:rf.db/runtime` effect,
  ;; deliberately: it teaches the hydration reference shape (mirroring loaded
  ;; values into a machine snapshot so the snapshot is self-describing for
  ;; SSR and tools). Ordinary app handlers write app-db via `:db` only and let
  ;; the machine runtime own its snapshot; reach for `:rf.db/runtime` only
  ;; when you are intentionally authoring boot/hydration glue.
  (fn handler-boot-apply-hydration [{:keys [db] rt :rf.db/runtime} _]
    (let [staging (:boot/staging db)]
      {:db (-> db
               (assoc :config (:config staging))
               (assoc :flags  (:flags staging))
               (assoc :user   (:user staging))
               (assoc :routes (:routes staging)))
       ;; Mirror the loaded values into the boot machine's :data slice so the
       ;; snapshot is self-describing for SSR and tools.
       :rf.db/runtime (update-in (or rt {})
                                 [:rf.runtime/machines :snapshots :app/boot :data] assoc
                                 :config (:config staging)
                                 :flags  (:flags staging)
                                 :user   (:user staging)
                                 :routes (:routes staging))
       :fx [[:dispatch [:app/boot [:boot/hydrated]]]]})))

;; ============================================================================
;; PUBLIC ENTRY EVENT
;; ============================================================================

(rf/reg-event :boot/initialise
  {:doc "Top-level app boot. Fires the :app/boot machine's
         `:rf.machine/start` creation marker to kick the boot off — the
         machine is born directly into `:configuring` and runs its
         `:spawn` entry cascade. The app seeds this event via the
         frame-provider's `:initial-events` (see core.cljs), which runs
         it once on first mount."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:app/boot [:rf.machine/start]]]]}))

;; ============================================================================
;; SUBS — boot-state slots the views read
;; ============================================================================

;; Machine snapshots are runtime-db state — read them through the
;; framework `:rf/machine` sub (docs/machines/glossary.md#snapshot).
(rf/reg-sub :app.boot/snapshot
  :<- [:rf/machine :app/boot]
  (fn [snapshot _] snapshot))

(rf/reg-sub :app.boot/state
  :<- [:app.boot/snapshot]
  (fn [snap _] (:state snap)))

(rf/reg-sub :app.boot/error
  :<- [:app.boot/snapshot]
  (fn [snap _] (get-in snap [:data :error])))

(rf/reg-sub :app.boot/ready?
  :<- [:app.boot/state]
  (fn [state _] (= state :ready)))

(rf/reg-sub :app.boot/failed?
  :<- [:app.boot/state]
  (fn [state _] (= state :failed)))

(rf/reg-sub :app/config (fn [db _] (:config db)))
(rf/reg-sub :app/flags  (fn [db _] (:flags db)))
(rf/reg-sub :app/user   (fn [db _] (:user db)))
(rf/reg-sub :app/routes (fn [db _] (:routes db)))
