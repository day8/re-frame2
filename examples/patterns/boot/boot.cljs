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
     :hydrating     → the loads are done and already folded into the
                      machine's own `:data`. Copy them out into the real
                      top-level app-db slices (`:config`, `:flags`,
                      `:user`, `:routes`), then move to `:ready`.
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
   load returns an `:api-base`; that `:spawn`'s `:on-done` fold records it
   into the boot machine's own `:data`; the three `:loading-deps` children
   then read it back off the parent's snapshot and build their URLs from it.
   The boot machine is the only place that ever reads host config —
   everything below gets it as plain data, never by reaching into a host
   global from an action body.

   Each child fetches via managed HTTP
   (docs/resources/glossary.md#managed-http), then hands its payload back
   to the parent. There used to be a genuinely subtle bit here — one
   hand-off route under `:spawn`, a different one under `:spawn-all`.
   There isn't any more, and the absence is the thing worth noticing:

   - A child completes by reaching a `:final?` state. `:output-key` names
     the `:data` slot holding its result (`:payload` on `:done`, `:error`
     on the `:error? true` `:failed` leaf), and the child dispatches
     nothing and names no parent vocabulary at all.
   - The parent folds that result in with `:on-done`, which is
     `(fn [{:keys [data result]}] new-data)` — declared on the `:spawn`
     map for the single child, and on a child's own spec inside
     `:spawn-all`. Same contract either way, which is exactly why one
     `:boot/loader` composes unchanged under both spawn forms.

   So there's no staging slot in app-db here: each payload lands straight
   in the boot machine's own `:data`, which is what the pair tools read
   the snapshot from anyway.

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
;; CHILD LOADER MACHINE — :boot/loader
;; ============================================================================
;;
;; The reusable child: GET a URL, branch on the reply, finish. One spec,
;; four instances — config, routes, flags, user — distinguished only by the
;; `:data` the parent plants at spawn. `:url` is the load-bearing one; the
;; rest is descriptive identity that labels the instance for tools and
;; schemas. Note there is no parent address among them: this child never
;; dispatches anything home, so it needs none. Each instance is born in
;; `:idle`; the runtime drops a
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
                :on-failure [self-id [:asset/replied :failure]]}]]}))}

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
               ;; Managed HTTP puts the classified failure map under :error.
               :action (fn [{data :data [_ _ reply] :event}]
                         {:data (assoc data :error (:error reply))})}]}}

    ;; The two terminals, and between them the whole completion protocol.
    ;; `:final? true` says arriving here IS finishing; `:output-key` names
    ;; the :data slot the runtime lifts as the result and hands to the
    ;; spawning parent's `:on-done`. `:error? true` marks the second one a
    ;; FAILURE, which routes to the parent's `:on-error` under a single
    ;; `:spawn` and trips `:on-any-failed` under a join. No :entry action,
    ;; no dispatch, no parent keyword — which is what lets this one child
    ;; serve both spawn forms unchanged.
    :done   {:final? true :output-key :payload}
    :failed {:final? true :error? true :output-key :error}}})

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

   :guards
   {:config-loaded?
    ;; The hinge of the new :configuring phase. The `:spawn`'s `:on-done`
    ;; fold has already dropped the config into :data by the time the
    ;; parent takes its next macrostep, so the eventless `:always` below
    ;; just asks "is it there yet?" and moves on when it is.
    (fn guard-config-loaded? [{:keys [data]}]
      (some? (:config data)))}

   :actions
   {:record-failure
    ;; Serves both failure routes, because both events put the payload in
    ;; the same slot: `[:rf.machine.spawn/error <invoke-id> <error>]` from
    ;; the single :spawn's :on-error, and
    ;; `[:boot/deps-failed <decisive-child-id> <error>]` from the join's
    ;; :on-any-failed. Either way the child's :error output is third.
    (fn [{data :data [_ _child failure] :event}]
      {:data (assoc data :error failure)})

    :enter-hydrating
    ;; By now every payload is already sitting in this machine's own :data
    ;; — the `:spawn` and per-child `:on-done` folds put it there, each one
    ;; a write the machine made itself. All that's left is to publish those
    ;; four values into the top-level app-db slices the live app's subs
    ;; actually read, which is an app-db write and therefore an ordinary
    ;; handler's job: we hand it the values on the event and let it get on
    ;; with it. Then self-transition to `:ready` once the write lands.
    (fn [{data :data}]
      {:data (assoc data :phase :hydrating)
       :fx   [[:dispatch [:boot/apply-hydration
                          (select-keys data [:config :flags :user :routes])]]]})}

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
              ;; parent's snapshot at entry. Here we just plant the child's
              ;; descriptive identity and the URL it fetches — the URL being
              ;; the only one the loader acts on. This first URL is the
              ;; fixed config endpoint that the rest of the boot threads
              ;; from, so it's a literal — there's nothing to derive it from
              ;; yet.
              :data       (fn boot-config-data [_]
                            {:parent-id   :app/boot
                             :child-id    :config
                             :staging-key :config
                             :url         "/api/config.json"})
              ;; The `:data` fold. It runs at THIS parent's handler
              ;; boundary when the child's completion arrives, so by the
              ;; next macrostep the config is in :data — which is what
              ;; the `:always` below is waiting for.
              :on-done    (fn boot-config-done [{:keys [data result]}]
                            (assoc data :config result))
              ;; Control flow for the failure half. The child's `:failed`
              ;; leaf is `:error? true`, so its finality routes here
              ;; rather than to `:on-done`.
              :on-error   {:target :failed :action :record-failure}}
     ;; Advancing on a child's completion. `:on-done` folds the payload,
     ;; then the completion event flows on into the parent's ordinary
     ;; macrostep — so this eventless `:always` sees the folded :data and
     ;; carries the boot into its next phase. That's the replacement for
     ;; a child hand-dispatching an event to move its parent along.
     :always [{:guard :config-loaded? :target :loading-deps}]}

    ;; ---- :loading-deps — :spawn-all fans out THREE parallel children ---
    :loading-deps
    {:spawn-all
     {:children
      ;; Three children, all the same :boot/loader, differing only in their
      ;; :data. Each :data fn reads the `:api-base` that :configuring's
      ;; `:on-done` fold recorded and builds its child's URL from it.
      [{:id         :routes
        :machine-id :boot/loader
        :data       (fn boot-routes-data [{snap :snapshot}]
                      ;; A :data fn is handed `{:snapshot :event}`. That
                      ;; snapshot is the parent's value (not app-db), and
                      ;; since :configuring's `:on-done` fold already ran,
                      ;; the loaded `:api-base` is right there for the taking.
                      {:parent-id   :app/boot
                       :child-id    :routes
                       :staging-key :routes
                       :url         (str (-> snap :data :config :api-base) "/routes.json")})
        ;; Each child folds its own payload straight into the parent's
        ;; :data as it reaches finality, BEFORE the join fold. Same
        ;; `:on-done` contract the single `:spawn` above uses — which is
        ;; what retired the app-db staging slot this example used to need.
        ;; (A `:spawn-all` child may NOT declare `:on-error`; failure
        ;; control flow under a join is the block's `:on-any-failed`.)
        :on-done    (fn boot-routes-done [{:keys [data result]}]
                      (assoc data :routes result))}
       {:id         :flags
        :machine-id :boot/loader
        :data       (fn boot-flags-data [{snap :snapshot}]
                      {:parent-id   :app/boot
                       :child-id    :flags
                       :staging-key :flags
                       :url         (str (-> snap :data :config :api-base) "/flags.json")})
        :on-done    (fn boot-flags-done [{:keys [data result]}]
                      (assoc data :flags result))}
       {:id         :user
        :machine-id :boot/loader
        :data       (fn boot-user-data [{snap :snapshot}]
                      {:parent-id   :app/boot
                       :child-id    :user
                       :staging-key :user
                       :url         (str (-> snap :data :config :api-base) "/user.json")})
        :on-done    (fn boot-user-done [{:keys [data result]}]
                      (assoc data :user result))}]
      :join             :all
      ;; The block carries no child-vocabulary keys at all: the parent
      ;; names no event for a child to report with, because the runtime
      ;; counts finality directly. Only the join-resolution events below
      ;; belong to the parent.
      :on-all-complete  [:boot/deps-ready]
      :on-any-failed    [:boot/deps-failed]}
     :on    {:boot/deps-ready  {:target :hydrating}
             :boot/deps-failed {:target :failed
                                :action :record-failure}}}

    ;; ---- :hydrating — promotes the loaded payloads into app-db ---------
    :hydrating
    {:entry :enter-hydrating
     :on    {:boot/hydrated {:target :ready}}}

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
             ;; The retry clears the loaded payloads as well as the error.
             ;; It has to: :configuring now advances on an `:always` whose
             ;; guard reads `:config` out of :data, so a config left over
             ;; from the failed run would satisfy the guard on entry and
             ;; skip straight past the fetch it is meant to wait for.
             :meta {:terminal? true}
             :on   {:boot/restart {:target :configuring
                                   :action (fn [{data :data}]
                                             {:data (assoc data
                                                           :error  nil
                                                           :config nil
                                                           :flags  nil
                                                           :user   nil
                                                           :routes nil)})}}}}})

;; ============================================================================
;; HYDRATION PROMOTION
;; ============================================================================
;;
;; The :hydrating action could do these cross-slice writes itself, but it
;; hands them to a plain reg-event instead — on purpose. Splitting them out
;; keeps the boot trace readable one step at a time: `:enter-hydrating` is one
;; entry in the trace, this handler the next, rather than a single opaque blob.

(rf/reg-event :boot/apply-hydration
  {:doc "Promote the four loaded payloads — handed over on the event by
         :enter-hydrating, straight out of the boot machine's own :data —
         into the top-level app-db slices the running app reads, then fire
         :boot/hydrated back at :app/boot so it can finish the trip from
         :hydrating to :ready."}
  ;; An ordinary app handler like this one writes app-db and ONLY app-db
  ;; (`:db`, below) — never a machine's snapshot. A machine's snapshot lives
  ;; in runtime-db, and Spec 005 §Where snapshots live is unambiguous: user
  ;; code MUST NOT write under [:rf.runtime/machines ...] — only the owning
  ;; machine's own actions may. That boundary is why the payloads travel TO
  ;; this handler on the event rather than it reaching into the snapshot for
  ;; them, and why nothing here writes back: every payload got into :data
  ;; through an `:on-done` fold, which is the machine folding its own :data
  ;; with framework authority — the only place that's allowed to happen.
  (fn handler-boot-apply-hydration [{:keys [db]} [_ loaded]]
    {:db (-> db
             (assoc :config (:config loaded))
             (assoc :flags  (:flags loaded))
             (assoc :user   (:user loaded))
             (assoc :routes (:routes loaded)))
     :fx [[:dispatch [:app/boot [:boot/hydrated]]]]}))

;; ============================================================================
;; PUBLIC ENTRY EVENT
;; ============================================================================

(rf/reg-event :boot/initialise
  {:doc "The one button that starts everything. It fires the :app/boot
         machine's `:rf.machine/start` creation marker, which births the
         machine into `:configuring` and runs its `:spawn` cascade. The
         frame-root seeds this via `:initial-events` (see core.cljs),
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
  {:inputs [[:rf/machine :app/boot]]}
  (fn [[snapshot] _] snapshot))

(rf/reg-sub :app.boot/state
  {:inputs [[:app.boot/snapshot]]}
  (fn [[snap] _] (:state snap)))

(rf/reg-sub :app.boot/error
  {:inputs [[:app.boot/snapshot]]}
  (fn [[snap] _] (get-in snap [:data :error])))

(rf/reg-sub :app.boot/ready?
  {:inputs [[:app.boot/state]]}
  (fn [[state] _] (= state :ready)))

(rf/reg-sub :app.boot/failed?
  {:inputs [[:app.boot/state]]}
  (fn [[state] _] (= state :failed)))

(rf/reg-sub :app/config (fn [db _] (:config db)))
(rf/reg-sub :app/flags  (fn [db _] (:flags db)))
(rf/reg-sub :app/user   (fn [db _] (:user db)))
(rf/reg-sub :app/routes (fn [db _] (:routes db)))
