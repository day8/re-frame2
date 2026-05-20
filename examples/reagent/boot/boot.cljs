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
   spawn-spec `:data` fn closes over the parent's snapshot).

   Each child fetches via `:rf.http/managed` and, on success, writes
   its payload into the boot machine's staging slot at
   `[:boot/staging <staging-key>]` before dispatching the canonical
   `:spawn-all` child-completion event back to the parent's
   `:on-child-done` slot.

   Why a staging slot rather than threading payloads through the
   join-event: per Spec 005, the runtime intercepts the parent's
   `:on-child-done` / `:on-child-error` events for join bookkeeping
   ONLY — they are not fed into the parent's `:on` lookup. The
   join-resolution event the runtime synthesises
   (`:on-all-complete [:boot/deps-ready]`) carries no per-child
   payload. So the canonical Pattern-Boot shape for a `:spawn-all`
   that needs to thread loaded data into the parent is: each child
   writes its result into a known app-db slice, the parent reads
   that slice on the join-resolved transition. This keeps the
   loaded data in app-db throughout — inspectable in pair-tools
   and snapshottable for SSR hydration.

   Trigger boot once at app start via `[:app/boot [:rf/start]]`. The
   machine self-initialises (per Spec 005 §Restore semantics): the
   `:initial` state and `:data` seed `[:rf/machines :app/boot]` when
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
            [re-frame.http-managed]
            [boot.schema]))

;; ============================================================================
;; STAGING-SLOT WRITER
;; ============================================================================
;;
;; The child loader dispatches this event with its loaded payload
;; before transitioning into its terminal `:done` state. The boot
;; machine reads `[:boot/staging <staging-key>]` on the
;; :hydrating transition to write the loaded values into the
;; top-level app-db slices.

(rf/reg-event-db :boot/stage-payload
  {:doc "Write a child-loaded payload into the boot machine's
         staging slot. Dispatched by the :boot/loader child's
         :dispatch-done action just before it fires the join-completion
         event back to the parent."}
  (fn handler-boot-stage-payload [db [_ staging-key payload]]
    (assoc-in db [:boot/staging staging-key] payload)))

;; ============================================================================
;; CHILD LOADER MACHINE — :boot/loader
;; ============================================================================
;;
;; The reusable child machine. One spec, four instances. Each
;; instance carries its own `:data` map (`:parent-id`, `:child-id`,
;; `:staging-key`, `:url`) planted by the parent's per-child
;; `:spawn` / `:spawn-all`-child `:data` slot (fn-form per
;; Spec 005 §Spec-spec keys). The child machine spawns in `:idle`
;; and the runtime-synthesised `:rf.machine/spawned` event
;; transitions it to `:loading`, which fires the entry-cascade's
;; `:begin-fetch` action.

(rf/reg-machine :boot/loader
  {:initial :idle
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
    ;; [:boot/staging <staging-key>], then fire the canonical
    ;; `:spawn-all` child-completion event back to the parent's
    ;; :on-child-done slot. The runtime intercepts the second
    ;; dispatch for join bookkeeping.
    (fn [{data :data}]
      {:fx [[:dispatch [:boot/stage-payload (:staging-key data) (:payload data)]]
            [:dispatch [(:parent-id data)
                        [:boot/asset-loaded (:child-id data)]]]]})

    :dispatch-error
    ;; Terminal failure-state entry. Forwards the failure to the
    ;; parent's :on-child-error slot; the parent's :on-any-failed
    ;; routes it onward to `:failed`.
    (fn [{data :data}]
      {:fx [[:dispatch [(:parent-id data)
                        [:boot/asset-failed (:child-id data) (:error data)]]]]})}

   :states
   {;; :idle is the FSM's :initial. The spawn-fx synthesises a
    ;; [:rf.machine/spawned] event into the new actor if no :start is
    ;; supplied — :idle's transition picks it up and moves to :loading,
    ;; which fires the entry-cascade's :begin-fetch action.
    :idle
    {:on {:rf.machine/spawned :loading}}

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
  {:initial :idle
   :data    {:phase  :idle
             :config nil
             :flags  nil
             :user   nil
             :routes nil
             :error  nil}

   :actions
   {:record-failure
    (fn [{data :data [_ _child-id failure] :event}]
      {:data (assoc data :error failure)})

    :enter-hydrating
    ;; Read all the staged payloads out of [:boot/staging ...] and
    ;; write them into the canonical top-level slices the running
    ;; app's subs read. Self-transitions to `:ready` once the
    ;; hydration write lands.
    (fn [{data :data}]
      {:data (assoc data :phase :hydrating)
       :fx   [[:dispatch [:boot/apply-hydration]]]})}

   :states
   {;; ---- :idle — the parking spot before the boot kicks off -------------
    ;; Per the standard re-frame2 state-machine convention (see the
    ;; `:spawn-all` conformance fixtures), a machine's initial state
    ;; is `:idle` and the first work-event transitions it onward. Here
    ;; the `:rf/start` event the dispatched-on-app-boot fires moves
    ;; :idle → :configuring, which fires the :spawn entry-cascade.
    :idle
    {:on {:rf/start :configuring}}

    ;; ---- :configuring — a single :spawn fetches /config -----------------
    :configuring
    {:spawn {:machine-id :boot/loader
              ;; Per Spec 005 §Spec-spec keys, `:data` admits a
              ;; function form `(fn [snap ev] data)` so the child's
              ;; initial :data can depend on the parent's snapshot at
              ;; the moment of entry. We plant the identity (parent
              ;; / child / staging-key / URL) here — the canonical
              ;; Pattern-AsyncEffect mechanism 2 (parameter passing
              ;; via the spawn-spec :data fn).
              :data       (fn boot-config-data [_snap _ev]
                            {:parent-id   :app/boot
                             :child-id    :config
                             :staging-key :config
                             :url         "/api/config.json"})}
     :on     {:boot/asset-loaded {:target :loading-deps}
              :boot/asset-failed {:target :failed
                                  :action :record-failure}}}

    ;; ---- :loading-deps — :spawn-all fans out THREE parallel children ---
    :loading-deps
    {:spawn-all
     {:children
      ;; Each child is the same :boot/loader machine, distinguished
      ;; only by its :data slot. The :data fn-form reads the
      ;; previously-staged `[:boot/staging :config]` to thread the
      ;; loaded `:api-base` into each child's URL (Pattern-Boot
      ;; mechanism 3 — boot reads host config; threads via
      ;; mechanism 2).
      [{:id         :routes
        :machine-id :boot/loader
        :data       (fn boot-routes-data [_snap _ev]
                      ;; The :data fn receives the PARENT's snapshot
                      ;; — not app-db. The parent records the staged
                      ;; config into its own :data slot on the
                      ;; :configuring → :loading-deps transition (see
                      ;; the :promote-staged action below). At spawn
                      ;; time, that value is visible via _snap, but
                      ;; for the boot example the api-base is empty
                      ;; (the demo stubs match by suffix).
                      {:parent-id   :app/boot
                       :child-id    :routes
                       :staging-key :routes
                       :url         "/api/routes.json"})}
       {:id         :flags
        :machine-id :boot/loader
        :data       (fn boot-flags-data [_snap _ev]
                      {:parent-id   :app/boot
                       :child-id    :flags
                       :staging-key :flags
                       :url         "/api/flags.json"})}
       {:id         :user
        :machine-id :boot/loader
        :data       (fn boot-user-data [_snap _ev]
                      {:parent-id   :app/boot
                       :child-id    :user
                       :staging-key :user
                       :url         "/api/user.json"})}]
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
             ;; re-entrant — dispatching `[:app/boot [:rf/start]]`
             ;; from the failure screen re-runs the boot from
             ;; :configuring. The terminal? meta is still true so
             ;; visualisers / conformance harnesses see :failed as a
             ;; terminal state; the re-entry transition is the
             ;; explicit re-boot, not the default end of the flow.
             :meta {:terminal? true}
             :on   {:rf/start {:target :configuring
                               :action (fn [{data :data}]
                                         {:data (assoc data :error nil)})}}}}})

;; ============================================================================
;; HYDRATION PROMOTION
;; ============================================================================
;;
;; A plain reg-event-fx does the cross-slice writes the boot
;; machine's :hydrating action dispatches. Keeping the cross-slice
;; reads / writes in an explicit handler (not inside the machine's
;; action body) makes the boot trace one-step inspectable: the
;; machine's `:enter-hydrating` action is one trace; the
;; :boot/apply-hydration handler is another.

(rf/reg-event-fx :boot/apply-hydration
  {:doc "Promote every staged child payload at [:boot/staging ...]
         into the canonical top-level app-db slices the running app
         reads. Fires :boot/hydrated back at :app/boot to transition
         from :hydrating to :ready."}
  (fn handler-boot-apply-hydration [{:keys [db]} _]
    (let [staging (:boot/staging db)]
      {:db (-> db
               (assoc :config (:config staging))
               (assoc :flags  (:flags staging))
               (assoc :user   (:user staging))
               (assoc :routes (:routes staging))
               ;; Mirror the loaded values into the boot machine's
               ;; :data slice so the snapshot is self-describing
               ;; for SSR / tools / pair-tools inspection.
               (update-in [:rf/machines :app/boot :data] assoc
                          :config (:config staging)
                          :flags  (:flags staging)
                          :user   (:user staging)
                          :routes (:routes staging)))
       :fx [[:dispatch [:app/boot [:boot/hydrated]]]]})))

;; ============================================================================
;; PUBLIC ENTRY EVENT
;; ============================================================================

(rf/reg-event-fx :boot/initialise
  {:doc "Top-level app boot. Fires the :app/boot machine's :rf/start
         event to kick the boot sequence off. The frame's :on-create
         points at this event (see core.cljs)."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:app/boot [:rf/start]]]]}))

;; ============================================================================
;; SUBS — boot-state slots the views read
;; ============================================================================

(rf/reg-sub :app.boot/snapshot
  (fn [db _]
    (get-in db [:rf/machines :app/boot])))

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
