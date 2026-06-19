(ns boot.boot
  "Pattern-Boot: the canonical re-frame2 application boot shape.

   The boot machine owns the initialisation graph. It runs through
   four states:

     :configuring   → fetch the mock /config endpoint via a single
                      `:spawn`d child loader machine.
     :loading-deps  → fan out THREE parallel child loads (routes,
                      feature flags, initial user) via `:spawn-all`;
                      the parent reaches the next state only when
                      EVERY child reports done.
     :hydrating     → applies the four loaded payloads into top-level
                      app-db slices (`:config`, `:flags`, `:user`,
                      `:routes`) via the `:enter-hydrating` action.
                      Self-transitions to `:ready` once the writes
                      land.
     :ready         → terminal. The main view subscribes to the boot
                      state and unblocks once it reads `:ready`.

   On any child failure the boot machine transitions to `:failed`
   (terminal) with the failure payload recorded in `:data :error`.

   The boot machine itself is `:app/boot`; the child loader is the
   reusable `:boot/loader` machine — one spec, four instances spawned
   with different `:data`. Each instance's identity (parent-id,
   child-id, staging-key, URL) is planted via the `:data` fn-form on
   the per-child invoke-spec — Pattern-AsyncEffect mechanism 2 (the
   spawn-spec `:data` fn reads the parent's post-action snapshot).

   The boot also demonstrates mechanism 3 → mechanism 2 end-to-end:
   the `:configuring` config load returns an `:api-base`; the
   `:promote-staged` action records it into the boot machine's own
   `:data` on the `:configuring → :loading-deps` transition; the
   three `:loading-deps` children then read that promoted `:api-base`
   off the parent's snapshot (mechanism 2) and thread it into their
   own URLs. This is the canonical Pattern-Boot parameter shape (see
   Pattern-Boot §Parameters): the boot machine is the only place that
   reads host config; everything downstream threads it via a snapshot
   `:data` fn — nothing reaches into a host global from an action body.

   Each child fetches via `:rf.http/managed` and, on success, writes
   its payload into the boot machine's staging slot at
   `[:boot/staging <staging-key>]` before dispatching its
   child-completion event back to the parent.

   Why a staging slot rather than threading the `:spawn-all` payloads
   through the join-event: per Spec 005, the runtime intercepts the
   parent's `:on-child-done` / `:on-child-error` events for join
   bookkeeping ONLY — they are NOT fed into the parent's `:on` lookup.
   The join-resolution event the runtime synthesises
   (`:on-all-complete [:boot/deps-ready]`) carries no per-child
   payload. So the canonical Pattern-Boot shape for a `:spawn-all`
   that needs to thread loaded data into the parent is: each child
   writes its result into a known app-db slice, the parent reads
   that slice on the join-resolved transition. This keeps the
   loaded data in app-db throughout — inspectable in pair-tools
   and snapshottable for SSR hydration.

   The single `:spawn` in `:configuring` is different: its
   child-completion event IS fed into the parent's `:on` lookup (only
   `:spawn-all` join events are intercepted). The config child
   therefore carries its loaded payload on the completion event, and
   the `:promote-staged` action reads it from there to thread the
   `:api-base` into the next phase (mechanism 3 → mechanism 2 above).

   Trigger boot once at app start via `[:app/boot [:rf.machine/start]]`.
   The machine self-initialises (per Spec 005 §Restore semantics): the
   `:initial` state and `:data` seed `[:rf.runtime/machines :snapshots :app/boot]` (runtime-db) when
   the dispatch lands."
  (:require [re-frame.core :as rf]
            ;; Spec 005 state-machine ns ships in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so `rf/reg-machine` and
            ;; `rf/make-machine-handler` resolve at ns-load.
            [re-frame.machines]
            ;; `:rf.http/managed` ships in day8/re-frame2-http. Loading
            ;; the ns registers the fx; without it, the child loaders
            ;; can't issue requests.
            [re-frame.http.managed]
            [boot.schema :as schema]))

;; ============================================================================
;; STAGING-SLOT WRITER
;; ============================================================================
;;
;; The child loader dispatches this event with its loaded payload
;; before transitioning into its terminal `:done` state. The boot
;; machine reads `[:boot/staging <staging-key>]` on the
;; :hydrating transition to write the loaded values into the
;; top-level app-db slices.

(rf/reg-event :boot/stage-payload
  {:doc "Write a child-loaded payload into the boot machine's
         staging slot. Dispatched by the :boot/loader child's
         :dispatch-done action just before it fires the join-completion
         event back to the parent."}
  (fn handler-boot-stage-payload [{:keys [db]} [_ staging-key payload]]
    {:db (assoc-in db [:boot/staging staging-key] payload)}))

;; ============================================================================
;; CHILD LOADER MACHINE — :boot/loader
;; ============================================================================
;;
;; The reusable child machine. One spec, four instances. Each
;; instance carries its own `:data` map (`:parent-id`, `:child-id`,
;; `:staging-key`, `:url`) planted by the parent's per-child
;; `:spawn` / `:spawn-all`-child `:data` slot (fn-form per
;; Spec 005 §Spec-spec keys). The child machine spawns in `:idle`
;; and the runtime-synthesised `:rf.machine.spawn/spawned` event
;; transitions it to `:loading`, which fires the entry-cascade's
;; `:begin-fetch` action.

(rf/reg-machine :boot/loader
  {:initial :idle
   ;; Validates each spawned loader's initial `:data` at spawn time,
   ;; before the snapshot installs (Spec 005 §Schema validation
   ;; §Spawn-time validation). The loader is only ever spawned (one
   ;; instance per asset, gensym'd id e.g. `:boot/loader#0`), so its
   ;; snapshot lives at a per-instance path no fixed `reg-app-schema`
   ;; can reach — the machine `:data-schema` slot is the mechanism that
   ;; boundary-checks the spawn. The per-child `:data` fn (see :app/boot
   ;; below) replaces this base map with the planted identity at spawn.
   :data-schema schema/LoaderData
   :data    {:parent-id   nil
             :child-id    nil
             :staging-key nil
             :url         nil
             :payload     nil
             :error       nil}

   :actions
   {:begin-fetch
    ;; Issue the GET. The reply addressing routes back to THIS child
    ;; (self-dispatch via :rf/self-id, which the spawn fx stamps
    ;; into :data per Spec 005 §Spec-spec keys — always present).
    ;; The reply lands at the FSM's `:asset/replied` inner event so
    ;; the FSM branches on success vs failure before forwarding to
    ;; the parent.
    (fn [{data :data}]
      (let [self-id (:rf/self-id data)]
        {:fx [[:rf.http/managed
               {:request    {:method :get :url (:url data)}
                :decode     :json
                :on-success [self-id [:asset/replied :success]]
                :on-failure [self-id [:asset/replied :failure]]}]]}))

    :dispatch-done
    ;; Terminal-state entry. First write the loaded payload into
    ;; [:boot/staging <staging-key>], then fire the child-completion
    ;; event back to the parent. The completion event carries both the
    ;; child-id AND the loaded payload:
    ;;   - In :configuring (a single :spawn), this event lands in the
    ;;     parent's :on lookup, where the :promote-staged action reads
    ;;     the payload off the event to thread `:api-base` into the
    ;;     next phase (mechanism 3 → mechanism 2).
    ;;   - In :loading-deps (a :spawn-all), the runtime intercepts this
    ;;     event for join bookkeeping ONLY — it is NOT fed into the
    ;;     parent's :on lookup (per Spec 005). Those payloads reach the
    ;;     parent via the staging slot the parent reads in
    ;;     :enter-hydrating. The event payload is harmless there.
    (fn [{data :data}]
      {:fx [[:dispatch [:boot/stage-payload (:staging-key data) (:payload data)]]
            [:dispatch [(:parent-id data)
                        [:boot/asset-loaded (:child-id data) (:payload data)]]]]})

    :dispatch-error
    ;; Terminal failure-state entry. Forwards the failure to the
    ;; parent's :on-child-error slot; the parent's :on-any-failed
    ;; routes it onward to `:failed`.
    (fn [{data :data}]
      {:fx [[:dispatch [(:parent-id data)
                        [:boot/asset-failed (:child-id data) (:error data)]]]]})}

   :states
   {;; :idle is the FSM's :initial. The spawn-fx synthesises a
    ;; [:rf.machine.spawn/spawned] event into the new actor if no :start is
    ;; supplied — :idle's transition picks it up and moves to :loading,
    ;; which fires the entry-cascade's :begin-fetch action.
    :idle
    {:on {:rf.machine.spawn/spawned :loading}}

    :loading
    {:entry :begin-fetch
     :on    {:asset/replied
             ;; Guarded fork on the reply kind. The runtime appends
             ;; the reply payload as the LAST element of the event
             ;; vector, so the event arrives as
             ;; [:asset/replied :success {:kind :success :value ...}]
             ;; — we pick the value out from the 3rd-position arg.
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
   ;; Validates the `:app/boot` snapshot's `:data` slot at the
   ;; `:where :machine-data` boundary (Spec 010 §Machine data schema +
   ;; Spec 005 §Schema validation). `BootData` describes `:data` only —
   ;; the singleton's snapshot is runtime-db, so its `:data-schema` (not
   ;; an `reg-app-schema`) is the snapshot-validation surface, symmetric
   ;; with the `:boot/loader` child above.
   :data-schema schema/BootData
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
    ;; Runs on the :configuring → :loading-deps transition. The single
    ;; config child carried its loaded payload on the
    ;; `:boot/asset-loaded` completion event (a single :spawn's
    ;; completion event IS fed into the parent's :on lookup — only
    ;; :spawn-all join events are intercepted), so this action reads
    ;; the config straight off the event and records it into the boot
    ;; machine's own :data slot. The next phase's spawn-spec `:data`
    ;; fns then thread host config (here, the loaded `:api-base`) into
    ;; each child's URL. This is the canonical Pattern-Boot parameter
    ;; shape: mechanism 3 (boot reads host config) → mechanism 2 (the
    ;; spawn-spec `:data` fn reads the parent's post-action snapshot).
    ;; The :data fn-form sees the value because the transition's
    ;; `:action` runs before the spawn-spec `:data` fn materialises
    ;; (Spec 005 §Spec-spec keys — the snapshot is post-action).
    (fn [{data :data [_ _child-id config] :event}]
      {:data (assoc data :config config)})

    :enter-hydrating
    ;; Read all the staged payloads out of [:boot/staging ...] and
    ;; write them into the canonical top-level slices the running
    ;; app's subs read. Self-transitions to `:ready` once the
    ;; hydration write lands.
    (fn [{data :data}]
      {:data (assoc data :phase :hydrating)
       :fx   [[:dispatch [:boot/apply-hydration]]]})}

   :states
   {;; ---- :configuring — the :initial state; a single :spawn fetches /config
    ;; Per Pattern-Boot the boot machine is BORN directly into its first
    ;; work state. The init-kick `[:app/boot [:rf.machine/start]]` is a
    ;; PURE creation marker (Spec 005 §F‴ / transition/start-marker): it
    ;; runs the initial-entry cascade — here `:configuring`'s `:spawn` —
    ;; and STOPS; it is NEVER fed into an `:on` map as a trigger. So there
    ;; is no `:idle` parking spot and no start-marker transition: the
    ;; machine starts working the moment it is kicked.
    :configuring
    {:spawn {:machine-id :boot/loader
              ;; Per Spec 005 §Spec-spec keys, `:data` admits a
              ;; function form `(fn [{:keys [snapshot event]}] data)`
              ;; so the child's initial :data can depend on the
              ;; parent's snapshot at the moment of entry. We plant the
              ;; identity (parent / child / staging-key / URL) here —
              ;; mechanism 2 (parameter passing via the spawn-spec
              ;; :data fn). This first URL is the host-fixed config
              ;; endpoint — the mechanism-3 SOURCE the rest of the boot
              ;; threads from, so it is a literal, not derived.
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
      ;; Each child is the same :boot/loader machine, distinguished
      ;; only by its :data slot. The :data fn-form reads the
      ;; parent's promoted `:api-base` (recorded into the parent's
      ;; :data by the :promote-staged action on the
      ;; :configuring → :loading-deps transition) and threads it into
      ;; each child's URL. This demonstrates the canonical
      ;; Pattern-Boot parameter shape end-to-end: mechanism 3 (boot
      ;; reads host config) → mechanism 2 (the spawn-spec :data fn
      ;; reads the parent's post-action snapshot).
      [{:id         :routes
        :machine-id :boot/loader
        :data       (fn boot-routes-data [{snap :snapshot}]
                      ;; The :data fn receives the unified
                      ;; context-map `{:snapshot :event}` — the
                      ;; snapshot is the PARENT's post-action value,
                      ;; not app-db. `:promote-staged` already ran, so
                      ;; the loaded `:api-base` is visible here.
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
    :failed {;; Per Pattern-Boot §Re-boot semantics, :failed is
             ;; re-entrant — dispatching the explicit re-entry event
             ;; `[:app/boot [:boot/restart]]` from the failure screen
             ;; re-runs the boot from :configuring. Re-boot uses a REAL
             ;; event, NOT the `:rf.machine/start` creation marker: the
             ;; marker is a pure init-kick that is never fed into an `:on`
             ;; map (Spec 005 §F‴), so once the machine exists a start
             ;; marker is inert. The terminal? meta is still true so
             ;; visualisers / conformance harnesses see :failed as a
             ;; terminal state; the re-entry transition is the explicit
             ;; re-boot, not the default end of the flow.
             :meta {:terminal? true}
             :on   {:boot/restart {:target :configuring
                                   :action (fn [{data :data}]
                                             {:data (assoc data :error nil)})}}}}})

;; ============================================================================
;; HYDRATION PROMOTION
;; ============================================================================
;;
;; A plain reg-event does the cross-slice writes the boot
;; machine's :hydrating action dispatches. Keeping the cross-slice
;; reads / writes in an explicit handler (not inside the machine's
;; action body) makes the boot trace one-step inspectable: the
;; machine's `:enter-hydrating` action is one trace; the
;; :boot/apply-hydration handler is another.

(rf/reg-event :boot/apply-hydration
  {:doc "Promote every staged child payload at [:boot/staging ...]
         into the canonical top-level app-db slices the running app
         reads. Fires :boot/hydrated back at :app/boot to transition
         from :hydrating to :ready."}
  ;; EP-0001 (rf2-vzld77): the app slices land in app-db (`:db`); the boot
  ;; machine's snapshot is durable runtime-db state, so the snapshot mirror
  ;; is a `:rf.db/runtime` effect (the reference handler is framework-shaped
  ;; example code — emitting the reserved runtime-db effect is in-bounds,
  ;; decision #4).
  ;;
  ;; ADVANCED — runtime-db writes are NOT an everyday-app pattern. This is the
  ;; ONLY handler in the whole examples tree that returns a `:rf.db/runtime`
  ;; effect, and deliberately so: it teaches the hydration REFERENCE shape
  ;; (mirroring loaded values into a machine snapshot so the snapshot is
  ;; self-describing for SSR / tools). Ordinary app handlers write app-db via
  ;; `:db` only and let the machine runtime own its own snapshot; reach for a
  ;; `:rf.db/runtime` effect only when you are intentionally authoring
  ;; framework-shaped boot/hydration glue, with the reserved-key contract
  ;; (Conventions §Reserved app-db keys / runtime-db) clearly in view.
  (fn handler-boot-apply-hydration [{:keys [db] rt :rf.db/runtime} _]
    (let [staging (:boot/staging db)]
      {:db (-> db
               (assoc :config (:config staging))
               (assoc :flags  (:flags staging))
               (assoc :user   (:user staging))
               (assoc :routes (:routes staging)))
       ;; Mirror the loaded values into the boot machine's :data slice so the
       ;; snapshot is self-describing for SSR / tools / pair-tools inspection.
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
         `:rf.machine/start` creation marker to kick the boot sequence
         off — the machine is born directly into `:configuring` and runs
         its `:spawn` entry-cascade. The frame's :on-create points at
         this event (see core.cljs)."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:app/boot [:rf.machine/start]]]]}))

;; ============================================================================
;; SUBS — boot-state slots the views read
;; ============================================================================

;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state — read
;; them through the framework `:rf/machine` sub.
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
