(ns boot.boot
  "One state machine that owns the whole startup graph.

   Booting an app is a lifecycle: fetch some config, load the few things
   the first screen needs, then hand over. That's exactly what a machine
   is good at, so the whole sequence lives in one place — `:app/boot` —
   instead of scattered across mount hooks and callback chains. New to
   the model? The machines guide covers `:spawn` / `:spawn-all`
   (docs/machines/glossary.md#spawn), states (docs/machines/glossary.md#state),
   and the snapshot (docs/machines/glossary.md#snapshot).

   Read the four states as a story:

     :configuring   → fetch /config first, because everything else needs
                      it. One `:spawn`d child loader does the GET.
     :loading-deps  → the parallel step. Three loads (routes, feature
                      flags, user) don't depend on each other, so
                      `:spawn-all` runs them at once. The parent waits
                      for every child to finish.
     :hydrating     → the loads are done but parked in a staging area.
                      Copy them into the real top-level app-db slices
                      (`:config`, `:flags`, `:user`, `:routes`), then
                      move to `:ready`.
     :ready         → terminal. The main view watches the boot state and
                      unblocks the moment it reads `:ready`.

   If any child fails, the machine jumps to `:failed` (terminal) and
   records the reason in `:data :error`.

   The child loader is its own machine, `:boot/loader` — one spec, four
   instances, told apart only by the `:data` each was spawned with
   (parent-id, child-id, staging-key, URL). The parent plants that
   identity through a per-child `:data` fn, and since a `:data` fn gets
   handed the parent's snapshot, a child's URL can depend on something
   the parent already loaded.

   That's how config flows downstream without a global. The `:configuring`
   load returns an `:api-base`; `:promote-staged` records it into the
   boot machine's own `:data`; the three `:loading-deps` children then
   read it back off the parent's snapshot and build their URLs from it.
   The boot machine is the only place that ever reads host config —
   everything below gets it as plain data, never by reaching into a host
   global from an action body.

   Each child fetches via managed HTTP
   (docs/resources/glossary.md#managed-http), then hands its payload back
   to the parent. How it hands it back depends on the spawn shape, and
   this is the one genuinely subtle bit:

   - A `:spawn-all` child writes its payload into a staging slot at
     `[:boot/staging <staging-key>]`, then signals done. The runtime
     reads a `:spawn-all`'s `:on-child-done` / `:on-child-error` events
     only to track the join — they never reach the parent's `:on` table,
     and the synthesised join-resolution event
     (`:on-all-complete [:boot/deps-ready]`) carries no per-child data. So
     the payload can't ride the event; it goes through the staging slot,
     and the parent reads the whole slot once the join resolves.
   - The single `:spawn` in `:configuring` is the exception: its
     completion event *does* reach the parent's `:on` table (only join
     events get intercepted). So the config child carries its payload on
     the event, and `:promote-staged` reads it straight off.

   Staging through app-db has a nice side benefit: the loaded data sits
   in app-db the whole time — visible in the pair tools and snapshottable
   for SSR hydration.

   Kick the boot once at startup with `[:app/boot [:rf.machine/start]]`.
   That creation marker runs the initial-entry cascade and seeds the
   snapshot in runtime-db (docs/core/glossary.md#runtime-db)."
  (:require [re-frame.core :as rf]
            ;; State machines and managed HTTP each ship as their own
            ;; artefact. These two requires register what the boot needs:
            ;; `rf/reg-machine` and friends, and the managed-HTTP fx the
            ;; child loaders fetch through.
            [re-frame.machines]
            [re-frame.http.managed]
            [boot.schema :as schema]))

;; ============================================================================
;; STAGING-SLOT WRITER
;; ============================================================================
;;
;; The hand-off slot for :spawn-all children. Each one drops its payload into
;; `[:boot/staging <staging-key>]` just before signalling done, and the parent
;; scoops up the whole slot on the way into :hydrating. (Why a slot and not the
;; event? The ns docstring has the long answer; the short one is that join
;; events never reach the parent's :on table.)

(rf/reg-event :boot/stage-payload
  {:doc "Stash one child-loaded payload in the boot machine's staging
         slot. The :boot/loader child fires this from its :dispatch-done
         action, right before the completion event."}
  (fn handler-boot-stage-payload [{:keys [db]} [_ staging-key payload]]
    {:db (assoc-in db [:boot/staging staging-key] payload)}))

;; ============================================================================
;; CHILD LOADER MACHINE — :boot/loader
;; ============================================================================
;;
;; The reusable child: GET a URL, branch on the reply, report back. One spec,
;; four instances — config, routes, flags, user — distinguished only by the
;; `:data` (`:parent-id`, `:child-id`, `:staging-key`, `:url`) the parent
;; plants at spawn. Each instance is born in `:idle`; the runtime drops a
;; `:rf.machine.spawn/spawned` event into it, which moves it to `:loading` and
;; fires the `:begin-fetch` entry action. Write the fetch logic once, run it
;; four times.

(rf/reg-machine :boot/loader
  {:initial :idle
   ;; Validate each spawned loader's `:data` at spawn time. A loader is only
   ;; ever spawned — one instance per asset, with a generated id like
   ;; `:boot/loader#0` — so its snapshot lands at a per-instance path no fixed
   ;; app-schema could ever name. The machine's own `[:schemas :data]` slot is
   ;; the right surface: it checks `:data` directly. (The per-child `:data` fn
   ;; in :app/boot below swaps this base map for the planted identity.)
   :schemas {:data schema/LoaderData}
   :data    {:parent-id   nil
             :child-id    nil
             :staging-key nil
             :url         nil
             :payload     nil
             :error       nil}

   :actions
   {:begin-fetch
    ;; Fire the GET. The reply is addressed back to THIS child via
    ;; :rf/self-id (the spawn fx stamps it into :data), landing as the
    ;; `:asset/replied` event — so the loader sorts success from failure
    ;; itself before it ever bothers the parent.
    (fn [{data :data}]
      (let [self-id (:rf/self-id data)]
        {:fx [[:rf.http/managed
               {:request    {:method :get :url (:url data)}
                :decode     :json
                :on-success [self-id [:asset/replied :success]]
                :on-failure [self-id [:asset/replied :failure]]}]]}))

    :dispatch-done
    ;; Runs on entering :done. Two steps: stash the payload in the staging
    ;; slot, then tell the parent we're finished. The done event carries
    ;; the child-id and payload, but who actually reads that payload depends
    ;; on the spawn shape:
    ;;   - Under a single :spawn (config), the event reaches the parent's
    ;;     :on table, and :promote-staged plucks the payload off it to
    ;;     thread `:api-base` into the next phase.
    ;;   - Under a :spawn-all (the deps), the runtime keeps this event for
    ;;     join bookkeeping and never shows it to the parent's :on table.
    ;;     Those payloads travel through the staging slot instead, read in
    ;;     :enter-hydrating. Carrying it on the event too is harmless.
    (fn [{data :data}]
      {:fx [[:dispatch [:boot/stage-payload (:staging-key data) (:payload data)]]
            [:dispatch [(:parent-id data)
                        [:boot/asset-loaded (:child-id data) (:payload data)]]]]})

    :dispatch-error
    ;; Runs on entering :failed. Hands the failure up to the parent's
    ;; :on-child-error slot, where :on-any-failed routes the whole boot to
    ;; `:failed`. One bad child sinks the boot.
    (fn [{data :data}]
      {:fx [[:dispatch [(:parent-id data)
                        [:boot/asset-failed (:child-id data) (:error data)]]]]})}

   :states
   {;; :idle is where every loader starts. The runtime drops a
    ;; [:rf.machine.spawn/spawned] event into the fresh child; this
    ;; transition catches it and moves to :loading, which kicks off the
    ;; :begin-fetch entry action. Blink and you'll miss :idle.
    :idle
    {:on {:rf.machine.spawn/spawned :loading}}

    :loading
    {:entry :begin-fetch
     :on    {:asset/replied
             ;; A guarded fork on the reply kind — first branch whose
             ;; `:guard` passes wins, success before failure. The runtime
             ;; tacks the canonical reply envelope onto the end of the event
             ;; vector, so it arrives as
             ;; [:asset/replied :success {:status :ok :value ... …}]
             ;; and we read the value out of the 3rd slot. (The `:success` /
             ;; `:failure` tag in slot 1 is the loader's OWN wiring from
             ;; `:on-success [self-id [:asset/replied :success]]`, not the
             ;; reply's status.)
             [{:guard (fn [{ev :event}] (= :success (nth ev 1 nil)))
               :target :done
               :action (fn [{data :data [_ _ reply] :event}]
                         {:data (assoc data :payload (:value reply))})}
              {:target :failed
               ;; rf2-ibksxg — the classified failure map rides under :error.
               :action (fn [{data :data [_ _ reply] :event}]
                         {:data (assoc data :error (:error reply))})}]}}

    :done   {:entry :dispatch-done   :meta {:terminal? true}}
    :failed {:entry :dispatch-error  :meta {:terminal? true}}}})

;; ============================================================================
;; BOOT MACHINE — :app/boot
;; ============================================================================

(rf/reg-machine :app/boot
  {:initial :configuring
   ;; Validate the `:app/boot` snapshot's `:data`. Like the loader above,
   ;; the snapshot lives in runtime-db, so the machine's own
   ;; `[:schemas :data]` slot is the validation surface — an app-schema
   ;; can't reach it. `BootData` describes `:data` only.
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
    ;; Runs on the :configuring → :loading-deps transition, and it's the
    ;; hinge of the whole config-flows-downstream story. The config child's
    ;; done event reached us (a single :spawn's event does land in the :on
    ;; table), so we pull the config off it and stash it in the boot
    ;; machine's own :data. A transition's `:action` runs before the next
    ;; state's child `:data` fns, so by the time those three loaders ask for
    ;; the `:api-base`, it's already sitting in the parent's snapshot.
    (fn [{data :data [_ _child-id config] :event}]
      {:data (assoc data :config config)})

    :enter-hydrating
    ;; Scoop the staged payloads out of [:boot/staging ...] and promote them
    ;; into the top-level slices the live app's subs actually read. Then
    ;; self-transition to `:ready` once the write lands.
    (fn [{data :data}]
      {:data (assoc data :phase :hydrating)
       :fx   [[:dispatch [:boot/apply-hydration]]]})

    :promote-hydrated
    ;; Runs on the :hydrating → :ready transition, triggered by the very
    ;; `:boot/hydrated` event `:boot/apply-hydration` (below) dispatches once
    ;; it has promoted the staged payloads into app-db. That handler carries
    ;; the SAME payload as this event's arg, and this action is the only
    ;; place it's allowed to reach the machine's own snapshot: mirroring it
    ;; into `:data` here — from inside the machine's own action — is what
    ;; Spec 005 §Where snapshots live means by "the machine sets its own
    ;; :data." An ordinary handler writing straight into
    ;; `[:rf.runtime/machines ...]` (which `:boot/apply-hydration` used to
    ;; do) is the thing this action replaces.
    (fn [{data :data [_ staged] :event}]
      {:data (merge data staged)})}

   :states
   {;; ---- :configuring — the :initial state; a single :spawn fetches /config
    ;; Unlike the loader, the boot machine is born straight into real work.
    ;; The kick `[:app/boot [:rf.machine/start]]` is a creation marker: it
    ;; runs the initial-entry cascade — here, :configuring's `:spawn` — and
    ;; then it's spent. It never acts as an `:on` trigger, so there's no
    ;; `:idle` waiting room; the machine is working the instant it's kicked.
    :configuring
    {:spawn {:machine-id :boot/loader
              ;; `:data` accepts a function — `(fn [{:keys [snapshot event]}]
              ;; data)` — so a child's starting :data can lean on the
              ;; parent's snapshot at entry. Here we just plant the identity
              ;; (parent / child / staging-key / URL). This first URL is the
              ;; fixed config endpoint that the rest of the boot threads
              ;; from, so it's a literal — there's nothing to derive it from
              ;; yet.
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
      ;; Three children, all the same :boot/loader, differing only in their
      ;; :data. Each :data fn reads the `:api-base` that :promote-staged
      ;; recorded on the way in and builds its child's URL from it.
      [{:id         :routes
        :machine-id :boot/loader
        :data       (fn boot-routes-data [{snap :snapshot}]
                      ;; A :data fn is handed `{:snapshot :event}`. That
                      ;; snapshot is the parent's value (not app-db), and
                      ;; since :promote-staged already ran, the loaded
                      ;; `:api-base` is right there for the taking.
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

    ;; ---- :hydrating — promotes the loaded payloads into app-db ---------
    :hydrating
    {:entry :enter-hydrating
     :on    {:boot/hydrated {:target :ready :action :promote-hydrated}}}

    ;; ---- :ready / :failed — terminal -------------------------------------
    :ready  {:meta {:terminal? true}}
    :failed {;; Terminal, but not a dead end. The failure screen's retry
             ;; button dispatches `[:app/boot [:boot/restart]]`, which runs
             ;; the boot again from :configuring. Note it's a real event,
             ;; *not* the `:rf.machine/start` creation marker — that marker
             ;; only ever kicks a machine to life once, so on an existing
             ;; machine it's inert; re-boot has to be an ordinary transition.
             ;; We keep `terminal? true` so visualisers and conformance
             ;; harnesses still read :failed as terminal; the retry is the
             ;; explicit way out, not the flow's natural end.
             :meta {:terminal? true}
             :on   {:boot/restart {:target :configuring
                                   :action (fn [{data :data}]
                                             {:data (assoc data :error nil)})}}}}})

;; ============================================================================
;; HYDRATION PROMOTION
;; ============================================================================
;;
;; The :hydrating action could do these cross-slice writes itself, but it
;; hands them to a plain reg-event instead — on purpose. Splitting them out
;; keeps the boot trace readable one step at a time: `:enter-hydrating` is one
;; entry in the trace, this handler the next, rather than a single opaque blob.

(rf/reg-event :boot/apply-hydration
  {:doc "Promote every staged payload at [:boot/staging ...] into the
         top-level app-db slices the running app reads, then fire
         :boot/hydrated — carrying that same payload — back at :app/boot so
         it can finish the trip from :hydrating to :ready."}
  ;; An ordinary app handler like this one writes app-db and ONLY app-db
  ;; (`:db`, below) — never a machine's snapshot. A machine's snapshot lives
  ;; in runtime-db, and Spec 005 §Where snapshots live is unambiguous: user
  ;; code MUST NOT write under [:rf.runtime/machines ...] — only the owning
  ;; machine's own actions may. So the payload this handler promotes into
  ;; app-db rides along on the `:boot/hydrated` event instead, and it's
  ;; :app/boot's own `:promote-hydrated` action (see the machine's `:actions`
  ;; above) that mirrors it into the machine's `:data` — from inside the
  ;; machine, with framework authority, the only place that's allowed to
  ;; happen.
  (fn handler-boot-apply-hydration [{:keys [db]} _]
    (let [staging (:boot/staging db)]
      {:db (-> db
               (assoc :config (:config staging))
               (assoc :flags  (:flags staging))
               (assoc :user   (:user staging))
               (assoc :routes (:routes staging)))
       :fx [[:dispatch [:app/boot [:boot/hydrated staging]]]]})))

;; ============================================================================
;; PUBLIC ENTRY EVENT
;; ============================================================================

(rf/reg-event :boot/initialise
  {:doc "The one button that starts everything. It fires the :app/boot
         machine's `:rf.machine/start` creation marker, which births the
         machine into `:configuring` and runs its `:spawn` cascade. The
         frame-provider seeds this via `:initial-events` (see core.cljs),
         so it runs exactly once, on first mount."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:app/boot [:rf.machine/start]]]]}))

;; ============================================================================
;; SUBS — boot-state slots the views read
;; ============================================================================

;; A machine snapshot lives in runtime-db, so you reach it through the
;; framework's `:rf/machine` sub (docs/machines/glossary.md#snapshot). The
;; small subs below are just convenient slices off that one snapshot.
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
