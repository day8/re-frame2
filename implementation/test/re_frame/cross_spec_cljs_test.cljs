(ns re-frame.cross-spec-cljs-test
  "Cross-Spec interaction edge cases. One deftest per documented case in
  docs/specification/Cross-Spec-Interactions.md.

  Each deftest's docstring carries the section anchor from the doc; if a
  case requires a real DOM / browser harness it is left as a placeholder
  test pointing at rf2-443l (browser-test runner).

  ns ends in -cljs-test so shadow-cljs ':node-test' picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.reagent :as reagent-adapter]
            [re-frame.views]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (adapter/dispose-adapter!)
  (rf/init! reagent-adapter/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces [k]
  (let [traces (atom [])]
    (rf/register-trace-cb! k (fn [ev] (swap! traces conj ev)))
    traces))

(defn- stop-traces [k]
  (rf/remove-trace-cb! k))

;; ---------------------------------------------------------------------------
;; Interaction 1 — Frame disposal with active machine instances
;; docs/specification/Cross-Spec-Interactions.md#1-frame-disposal-with-active-machine-instances
;; ---------------------------------------------------------------------------

(deftest frame-destroy-with-active-machines
  "#1 Frame disposal with active machine instances —
   destroy-frame emits :rf.machine/destroyed-on-frame-exit per active machine."
  (rf/reg-frame :tenant-x {:doc "tenant frame with two machines"})
  (rf/reg-event-db :seed
    (fn [db _]
      (assoc db :rf/machines {:flow/login    {:state :authed   :data {}}
                              :flow/checkout {:state :pending  :data {}}})))
  (rf/dispatch-sync [:seed] {:frame :tenant-x})
  (let [traces (collect-traces ::xspec-1)]
    (rf/destroy-frame :tenant-x)
    (stop-traces ::xspec-1)
    (let [machine-traces (filter #(= :rf.machine/destroyed-on-frame-exit
                                      (:operation %))
                                 @traces)]
      (is (= 2 (count machine-traces))
          "one trace per active machine snapshot at frame destroy")
      (is (every? #(= :tenant-x (:frame (:tags %))) machine-traces)
          "each trace carries the destroyed frame's id")
      (is (= #{:authed :pending}
             (set (map #(:last-state (:tags %)) machine-traces)))
          "each trace records the machine's last state")
      (is (some #(= :frame/destroyed (:operation %)) @traces)
          ":frame/destroyed fires after the per-machine traces"))))

;; ---------------------------------------------------------------------------
;; Interaction 2 — Sub-cache hit inside a machine microstep
;; docs/specification/Cross-Spec-Interactions.md#2-sub-cache-hit-inside-a-machine-microstep
;; ---------------------------------------------------------------------------

(deftest machine-microstep-subscribe
  "#2 Sub-cache hit inside a machine microstep —
   subscribe inside an action body sees the pre-cascade app-db, not the
   in-flight :data."
  (rf/reg-event-db :seed (fn [_ _] {:user/role :admin}))
  (rf/reg-sub :user-role (fn [db _] (:user/role db)))
  (rf/dispatch-sync [:seed])
  (let [observed-by-action (atom nil)
        machine
        {:initial :idle
         :data    {}
         :states  {:idle    {:on {:go {:target :acting :action :record-role}}}
                   :acting  {}}
         :actions {:record-role
                   (fn [snapshot _event]
                     ;; Read a sub from inside the action — the machine
                     ;; cascade has not committed yet but the sub sees
                     ;; the *current* committed app-db.
                     (reset! observed-by-action (rf/subscribe-value [:user-role]))
                     snapshot)}}]
    (rf/reg-machine :auth/check machine)
    (rf/dispatch-sync [:auth/check [:go]])
    (is (= :admin @observed-by-action)
        "the sub returns the committed app-db value visible to the action body")))

;; ---------------------------------------------------------------------------
;; Interaction 3 — Machine spawn at boot before substrate adapter ready
;; docs/specification/Cross-Spec-Interactions.md#3-machine-spawn-at-boot-before-substrate-adapter-ready
;; ---------------------------------------------------------------------------

(deftest boot-order-adapter-ready
  "#3 Machine spawn at boot before substrate adapter ready —
   :on-create runs synchronously after the adapter is installed."
  ;; In :node-test the adapter is installed by reset-runtime before any
  ;; reg-frame call, so :on-create always runs against a ready adapter.
  ;; This test pins that property: a frame's :on-create event reaches a
  ;; live sub-cache and the spawned machine's snapshot lands in app-db.
  (rf/reg-event-db :init-shape (fn [_ _] {:rf/machines {:flow/boot {:state :armed
                                                                    :data  {}}}}))
  (rf/reg-frame :booted {:on-create [:init-shape]})
  (let [db (rf/get-frame-db :booted)]
    (is (= :armed (get-in db [:rf/machines :flow/boot :state]))
        ":on-create completed against an installed adapter — app-db carries the seed")))

;; ---------------------------------------------------------------------------
;; Interaction 4 — Machines under SSR (allowed-subset)
;; docs/specification/Cross-Spec-Interactions.md#4-machines-under-ssr-allowed-subset
;; ---------------------------------------------------------------------------

(deftest after-noop-shape-under-ssr-server-preset
  "#4 Machines under SSR (allowed-subset) —
   the :ssr-server preset stamps :platform :server on the frame, which
   is the channel through which `:after` is suppressed.

   ;; TODO browser harness — rf2-443l: the full :after no-op end-to-end
   ;; verification needs a real timer harness."
  (rf/reg-frame :req {:preset :ssr-server})
  (let [meta (rf/frame-meta :req)]
    (is (= :server (get-in meta [:config :platform]))
        ":ssr-server preset sets :platform :server on the frame config")
    (is (= :rf.error/server-projection (get-in meta [:config :on-error]))
        ":ssr-server preset wires :on-error to :rf.error/server-projection")))

;; ---------------------------------------------------------------------------
;; Interaction 5 — Hydration with machine snapshots
;; docs/specification/Cross-Spec-Interactions.md#5-hydration-with-machine-snapshots
;; ---------------------------------------------------------------------------

(deftest ssr-hydrate-with-machines
  "#5 Hydration with machine snapshots —
   machine snapshots live at [:rf/machines <id>] inside app-db so they
   serialise as part of the standard hydration payload (no separate
   machine channel)."
  (rf/reg-event-db :hydrate-payload
    (fn [_ [_ payload]] payload))
  (let [server-db {:user/id 7
                   :rf/machines {:auth/session {:state :authenticated
                                                :data  {:token "abc"}}}}]
    (rf/dispatch-sync [:hydrate-payload server-db])
    (let [client-db (rf/get-frame-db :rf/default)]
      (is (= :authenticated
             (get-in client-db [:rf/machines :auth/session :state]))
          "machine state survives hydration as a plain app-db slice")
      (is (= "abc"
             (get-in client-db [:rf/machines :auth/session :data :token]))
          "machine :data survives hydration with the rest of app-db"))))

;; ---------------------------------------------------------------------------
;; Interaction 6 — Routing in SSR
;; docs/specification/Cross-Spec-Interactions.md#6-routing-in-ssr
;; ---------------------------------------------------------------------------

(deftest routing-in-ssr-nav-fx-skipped-on-server
  "#6 Routing in SSR —
   :rf.nav/push-url and :rf.nav/replace-url are no-ops on :server; the
   route slice itself is just app-db so it hydrates trivially."
  (rf/reg-route :user/show {:path "/users/:id"})
  (rf/reg-frame :req {:preset :ssr-server})
  ;; Re-register the framework nav fx (reset-runtime cleared the
  ;; registrar) so this test exercises the same platform-gating shape
  ;; the production fx use.
  (rf/reg-fx :rf.nav/push-url
    {:platforms #{:client}}
    (fn [_ _] :should-not-run-on-server))
  (rf/reg-event-fx :emit-nav
    (fn [_ _]
      {:fx [[:rf.nav/push-url "/users/42"]]}))
  (let [traces (collect-traces ::xspec-6)]
    (rf/dispatch-sync [:emit-nav] {:frame :req})
    (stop-traces ::xspec-6)
    (is (some #(and (= :rf.fx/skipped-on-platform (:operation %))
                    (= :rf.nav/push-url (get-in % [:tags :fx-id])))
              @traces)
        ":rf.nav/push-url emits :rf.fx/skipped-on-platform under :server"))
  (testing "routes round-trip the same as on the client"
    (let [m (rf/match-url "/users/42")]
      (is (= :user/show (:route-id m)))
      (is (= "42" (:id (:params m)))))))

;; ---------------------------------------------------------------------------
;; Interaction 7 — Route-not-found under SSR
;; docs/specification/Cross-Spec-Interactions.md#7-route-not-found-under-ssr
;; ---------------------------------------------------------------------------

(deftest route-not-found-ssr-status
  "#7 Route-not-found under SSR —
   match-url returns nil-id for an unmatched URL; route-not-found is
   normal control flow, not an :rf.error trace."
  (rf/reg-route :user/show {:path "/users/:id"})
  (let [m (rf/match-url "/no-such-thing")]
    (is (nil? (:route-id m))
        "match-url surfaces no route-id for an unmatched URL"))
  ;; The error-projector contract is the user-side concern; here we
  ;; confirm that match-url itself does not emit :rf.error traces for
  ;; an unmatched URL — i.e., a missing route is signal, not an error.
  (let [traces (collect-traces ::xspec-7)]
    (rf/match-url "/no-such-thing")
    (stop-traces ::xspec-7)
    (is (empty? (filter #(= :error (:op-type %)) @traces))
        "match-url is pure: route-not-found does not emit error traces")))

;; ---------------------------------------------------------------------------
;; Interaction 8 — Frame disposal during render
;; docs/specification/Cross-Spec-Interactions.md#8-frame-disposal-during-render
;; ---------------------------------------------------------------------------

(deftest frame-destroy-during-render-placeholder
  "#8 Frame disposal during render —
   ;; TODO browser harness — rf2-443l. Requires a real React render
   ;; pass to be in flight at the moment destroy-frame is called; the
   ;; node-test runner has no DOM."
  (is true
      "placeholder; mid-render destroy needs the browser-test runner (rf2-443l)"))

;; ---------------------------------------------------------------------------
;; Interaction 9 — Reactive substrate without React-context
;; docs/specification/Cross-Spec-Interactions.md#9-reactive-substrate-without-react-context
;; ---------------------------------------------------------------------------

(deftest headless-explicit-frame-resolution-chain
  "#9 Reactive substrate without React-context —
   the resolution chain is dynamic-var → :rf/default when the substrate
   has no context concept (the dynamic var is the always-available tier)."
  (rf/reg-frame :alt {:doc "alt frame"})
  ;; Outside any with-frame: falls back to :rf/default.
  (is (= :rf/default (rf/current-frame))
      "no dynamic binding → resolves to :rf/default")
  ;; with-frame binds the dynamic var; resolution lands on the bound id.
  (rf/with-frame :alt
    (fn []
      (is (= :alt (rf/current-frame))
          "dynamic-var tier wins over :rf/default")))
  ;; After with-frame returns, dynamic var is unbound again.
  (is (= :rf/default (rf/current-frame))
      "with-frame's binding is scoped — dynamic var reverts on exit"))

;; ---------------------------------------------------------------------------
;; Interaction 10 — Plain Reagent fn under a non-default frame
;; docs/specification/Cross-Spec-Interactions.md#10-plain-reagent-fn-under-a-non-default-frame
;; ---------------------------------------------------------------------------

(deftest plain-fn-under-non-default-frame-placeholder
  "#10 Plain Reagent fn under a non-default frame —
   ;; TODO browser harness — rf2-443l. The :rf.warning/plain-fn-under-
   ;; non-default-frame-once trace fires when a plain fn renders inside
   ;; a non-default frame-provider; verifying the once-per-(fn,frame)
   ;; suppression requires a real React render."
  (is true
      "placeholder; warning emission needs the browser-test runner (rf2-443l)"))

;; ---------------------------------------------------------------------------
;; Interaction 11 — Machine action throws
;; docs/specification/Cross-Spec-Interactions.md#11-machine-action-throws
;; ---------------------------------------------------------------------------

(deftest machine-action-throws
  "#11 Machine action throws —
   when an action fn throws the cascade halts; the error is surfaced
   via :rf.error/handler-exception (the route through reg-event-fx)."
  (rf/reg-event-db :seed-state (fn [_ _] {:val :before}))
  (rf/dispatch-sync [:seed-state])
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle  {:on {:bang {:target :angry :action :boom}}}
                           :angry {}}
                 :actions {:boom (fn [_ _]
                                   (throw (ex-info "kaboom" {})))}}]
    (rf/reg-machine :test/m machine)
    (let [traces (collect-traces ::xspec-11)]
      (rf/dispatch-sync [:test/m [:bang]])
      (stop-traces ::xspec-11)
      (is (some #(= :rf.error/handler-exception (:operation %)) @traces)
          "an action throw surfaces as :rf.error/handler-exception")
      (is (= :before (:val (rf/get-frame-db :rf/default)))
          "a non-machine app-db slice is not touched when the cascade halts"))))

;; ---------------------------------------------------------------------------
;; Interaction 12 — Effect handler throws inside a machine action's :fx
;; docs/specification/Cross-Spec-Interactions.md#12-effect-handler-throws-inside-a-machine-actions-fx
;; ---------------------------------------------------------------------------

(deftest machine-fx-handler-throws
  "#12 Effect handler throws inside a machine action's :fx —
   the snapshot commit precedes :fx; if a fx handler throws the walk
   continues to subsequent :fx entries (rule: ordering ≠ dependency)."
  (let [seen (atom [])]
    (rf/reg-fx :throwy (fn [_ _] (throw (ex-info "fx-bang" {}))))
    (rf/reg-fx :record  (fn [_ args] (swap! seen conj args)))
    (let [machine {:initial :idle
                   :data    {}
                   :states  {:idle {:on {:go {:target :done :action :emit-fx}}}
                             :done {}}
                   :actions {:emit-fx
                             (fn [snap _]
                               (assoc snap :fx [[:throwy :a]
                                                [:record :b]]))}}]
      (rf/reg-machine :test/m machine)
      (let [traces (collect-traces ::xspec-12)]
        (rf/dispatch-sync [:test/m [:go]])
        (stop-traces ::xspec-12)
        (is (some #(and (= :rf.error/fx-handler-exception (:operation %))
                        (= :throwy (get-in % [:tags :fx-id])))
                  @traces)
            "the throwing fx surfaces as :rf.error/fx-handler-exception")
        (is (= [:b] @seen)
            ":fx walk continued past the throwing fx — :record still ran")
        (is (= :done
               (get-in (rf/get-frame-db :rf/default) [:rf/machines :test/m :state]))
            "the machine snapshot committed even though a downstream :fx threw")))))

;; ---------------------------------------------------------------------------
;; Interaction 13 — Hot-reload of a machine action while instance is running
;; docs/specification/Cross-Spec-Interactions.md#13-hot-reload-of-a-machine-action-while-instance-is-running
;; ---------------------------------------------------------------------------

(deftest hot-reload-machine-action
  "#13 Hot-reload of a machine action —
   re-registering the machine handler picks up the new action body for
   the next dispatched event; in-flight events complete against the
   handler resolved at the start of the drain cycle."
  (let [machine-v1 {:initial :idle
                    :data    {}
                    :states  {:idle    {:on {:go {:target :working
                                                  :action :tag}}}
                              :working {:on {:go {:target :idle
                                                  :action :tag}}}}
                    :actions {:tag (fn [snap _]
                                     (assoc-in snap [:data :who] :v1))}}
        machine-v2 (assoc-in machine-v1 [:actions :tag]
                             (fn [snap _] (assoc-in snap [:data :who] :v2)))]
    (rf/reg-machine :test/m machine-v1)
    (rf/dispatch-sync [:test/m [:go]])
    (is (= :v1 (get-in (rf/get-frame-db :rf/default)
                       [:rf/machines :test/m :data :who]))
        "v1 action ran on the first dispatch")
    ;; Hot-reload — re-register with v2 spec.
    (rf/reg-machine :test/m machine-v2)
    (rf/dispatch-sync [:test/m [:go]])
    (is (= :v2 (get-in (rf/get-frame-db :rf/default)
                       [:rf/machines :test/m :data :who]))
        "the next dispatched event resolves to the new action body")))

;; ---------------------------------------------------------------------------
;; Interaction 14 — Re-entrant dispatch from inside a render
;; docs/specification/Cross-Spec-Interactions.md#14-re-entrant-dispatch-from-inside-a-render
;; ---------------------------------------------------------------------------

(deftest dispatch-sync-from-handler-raises
  "#14 Re-entrant dispatch from inside a render —
   dispatch-sync from inside a running drain raises
   :rf.error/dispatch-sync-in-handler. (The render-time variant of this
   is identical at the runtime layer.)"
  (let [traces (collect-traces ::xspec-14)]
    (rf/reg-event-db :outer (fn [db _] (assoc db :ran? true)))
    (rf/reg-event-fx :nested
      (fn [_ _]
        (rf/dispatch-sync [:outer])
        {}))
    (rf/dispatch-sync [:nested])
    (stop-traces ::xspec-14)
    (is (some (fn [ev]
                (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                     (= :error (:op-type ev))))
              @traces)
        "a nested dispatch-sync emits :rf.error/dispatch-sync-in-handler")))

;; ---------------------------------------------------------------------------
;; Interaction 15 — Re-spawning a machine instance via Tool-Pair
;; docs/specification/Cross-Spec-Interactions.md#15-re-spawning-a-machine-instance-via-tool-pair
;; ---------------------------------------------------------------------------

(deftest time-travel-revert
  "#15 Re-spawning a machine instance via Tool-Pair —
   replace-container! reverts app-db (including the [:rf/machines ...]
   slice); the machine handler is still in the registrar so the next
   dispatch resolves it and reads the restored snapshot."
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle    {:on {:go {:target :working}}}
                           :working {:on {:go {:target :idle}}}}}]
    (rf/reg-machine :test/m machine)
    ;; Drive the machine to :working.
    (rf/dispatch-sync [:test/m [:go]])
    (let [post-go-db (rf/get-frame-db :rf/default)]
      (is (= :working (get-in post-go-db [:rf/machines :test/m :state]))
          "machine reached :working")
      ;; Tool-Pair-style revert: replace-container! to a snapshot where
      ;; the machine is in :idle.
      (let [container (frame/get-frame-db :rf/default)
            reverted  (assoc-in post-go-db [:rf/machines :test/m :state] :idle)]
        (adapter/replace-container! container reverted))
      (is (= :idle (get-in (rf/get-frame-db :rf/default)
                           [:rf/machines :test/m :state]))
          "after replace-container! the snapshot reads back as :idle")
      ;; Re-dispatch — the existing handler resolves and reads the
      ;; restored snapshot, transitioning :idle → :working again.
      (rf/dispatch-sync [:test/m [:go]])
      (is (= :working (get-in (rf/get-frame-db :rf/default)
                              [:rf/machines :test/m :state]))
          "re-dispatch after revert advances from the restored state"))))

;; ---------------------------------------------------------------------------
;; Interaction 16 — Error projection on the server
;; docs/specification/Cross-Spec-Interactions.md#16-error-projection-on-the-server
;; ---------------------------------------------------------------------------

(deftest server-error-projection-shape
  "#16 Error projection on the server —
   when a handler throws on a :ssr-server frame, :rf.error/handler-
   exception fires; the user-supplied projector consumes the trace and
   builds the response.

   ;; TODO browser harness — rf2-443l for the full HTTP shape; here we
   ;; confirm the trace channel a projector would subscribe to fires
   ;; under :ssr-server."
  (rf/reg-frame :req {:preset :ssr-server})
  (rf/reg-event-fx :handler-throws
    (fn [_ _] (throw (ex-info "boom" {}))))
  (let [traces (collect-traces ::xspec-16)]
    (rf/dispatch-sync [:handler-throws] {:frame :req})
    (stop-traces ::xspec-16)
    (let [errs (filter #(= :rf.error/handler-exception (:operation %)) @traces)]
      (is (seq errs)
          ":rf.error/handler-exception fires on the server frame for a thrown handler")
      (is (some #(= :req (get-in % [:tags :frame])) errs)
          "the trace records the request frame's id"))))

;; ---------------------------------------------------------------------------
;; Interaction 17 — Machine error inside SSR
;; docs/specification/Cross-Spec-Interactions.md#17-machine-error-inside-ssr
;; ---------------------------------------------------------------------------

(deftest ssr-machine-error
  "#17 Machine error inside SSR —
   composes Interaction 11 (action-throw → snapshot does not commit)
   with Interaction 16 (server projection). The trace fires under the
   request frame; the snapshot is unchanged."
  (rf/reg-frame :req {:preset :ssr-server})
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle  {:on {:bang {:target :angry :action :boom}}}
                           :angry {}}
                 :actions {:boom (fn [_ _]
                                   (throw (ex-info "ssr-bang" {})))}}]
    (rf/reg-machine :test/m machine)
    (let [traces (collect-traces ::xspec-17)]
      (rf/dispatch-sync [:test/m [:bang]] {:frame :req})
      (stop-traces ::xspec-17)
      (let [errs (filter #(= :rf.error/handler-exception (:operation %)) @traces)]
        (is (seq errs)
            "machine action throw surfaces as :rf.error/handler-exception under :ssr-server")
        (is (some #(= :req (get-in % [:tags :frame])) errs)
            "the trace records the request frame's id"))
      (let [snap (get-in (rf/get-frame-db :req) [:rf/machines :test/m])]
        (is (or (nil? snap) (= :idle (:state snap)))
            "no committed machine snapshot at :angry — the cascade halted")))))

;; ---------------------------------------------------------------------------
;; Interaction 18 — Re-registering a sub mid-cascade
;; docs/specification/Cross-Spec-Interactions.md#18-re-registering-a-sub-mid-cascade
;; ---------------------------------------------------------------------------

(deftest hot-reload-sub-mid-cascade
  "#18 Re-registering a sub mid-cascade —
   re-registering a sub disposes the cache slot and the next subscribe
   sees the new body, even when an active subscriber was holding a
   reaction on the old version."
  (rf/reg-event-db :seed (fn [_ _] {:n 7}))
  (rf/reg-sub :answer (fn [db _] (:n db)))
  (rf/dispatch-sync [:seed])
  (is (= 7 (rf/subscribe-value [:answer]))
      "the v1 sub computes from app-db")
  (let [_pin (rf/subscribe [:answer])]
    ;; Re-register: replacement-hook fires, cache slot is disposed, the
    ;; next subscribe builds against the new body.
    (rf/reg-sub :answer (fn [db _] (* 100 (:n db))))
    (is (= 700 (rf/subscribe-value [:answer]))
        "after re-registration the new sub body is in effect")
    (rf/unsubscribe [:answer])))

;; ---------------------------------------------------------------------------
;; Interaction 19 — Story decorators that override fx
;; docs/specification/Cross-Spec-Interactions.md#19-story-decorators-that-override-fx
;; ---------------------------------------------------------------------------

(deftest portable-story-fx-override
  "#19 Story decorators that override fx —
   id-valued fx-overrides on the frame redirect calls to the override
   target; the same map is portable across stories and tests."
  (let [seen (atom [])]
    (rf/reg-fx :http             (fn [_ args] (swap! seen conj [:real-http args])))
    (rf/reg-fx :rf.test/http-stub (fn [_ args] (swap! seen conj [:stub args])))
    ;; Frame with an id-valued override: :http is rerouted to :rf.test/http-stub.
    (rf/reg-frame :story-frame {:fx-overrides {:http :rf.test/http-stub}})
    (rf/reg-event-fx :go (fn [_ _] {:fx [[:http {:url "/x"}]]}))
    (rf/dispatch-sync [:go] {:frame :story-frame})
    (is (= [[:stub {:url "/x"}]] @seen)
        "the id-valued override redirected :http → :rf.test/http-stub")))

;; ---------------------------------------------------------------------------
;; Interaction 20 — Adapter swap mid-process is forbidden
;; docs/specification/Cross-Spec-Interactions.md#20-adapter-swap-mid-process-is-forbidden
;; ---------------------------------------------------------------------------

(deftest adapter-already-installed
  "#20 Adapter swap mid-process is forbidden —
   a second install-adapter! call without an intervening dispose throws
   :rf.error/adapter-already-installed."
  ;; reset-runtime has already installed the Reagent adapter; calling
  ;; install-adapter! again should throw.
  (let [thrown? (try
                  (rf/install-adapter! reagent-adapter/adapter)
                  false
                  (catch :default e
                    (= ":rf.error/adapter-already-installed"
                       (ex-message e))))]
    (is thrown?
        "second install-adapter! raises :rf.error/adapter-already-installed"))
  ;; Sanity-check the dispose-then-install path remains valid.
  (rf/dispose-adapter!)
  (rf/install-adapter! reagent-adapter/adapter)
  (is (some? (adapter/current-adapter))
      "after dispose, install succeeds again — clean swap path"))
