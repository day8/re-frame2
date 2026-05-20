(ns re-frame.adapter.helix-cross-spec-cljs-test
  "Adapter-parity port of the headless subset of
  `re-frame.cross-spec-dom-cljs-test` to the Helix adapter (rf2-ta4b5;
  renamed rf2-2hrj8).

  Each deftest pins a Cross-Spec interaction (spec/Cross-Spec-Interactions.md)
  under a Helix-installed adapter. The subset ported here is the one
  whose contract is observable WITHOUT a real React render — every
  test runs through the runtime / registrar / trace channel only.

  Browser-only cross-spec cases (interactions 8 mid-render destroy, 10
  plain-fn warn-once, the rf2-d4sf provider-routing cases) ride through
  the Helix Playwright smoke and are out of scope here. The shared
  framework-level cross-spec tests in core also exercise these.

  Parallel to:
    - implementation/adapters/reagent/test/re_frame/cross_spec_cljs_test.cljs

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.frame :as frame]
            [re-frame.late-bind]
            [re-frame.routing]
            [re-frame.ssr :as ssr]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

(defn- collect-traces [k]
  (let [traces (atom [])]
    (trace-tooling/register-trace-listener! k (fn [ev] (swap! traces conj ev)))
    traces))

(defn- stop-traces [k]
  (trace-tooling/unregister-trace-listener! k))

;; --- Interaction 1 — Frame disposal with active machine instances ---------

(deftest frame-destroy-with-active-machines-helix
  "#1 Frame disposal with active machine instances — under Helix."
  (rf/reg-frame :tenant-x {:doc "tenant frame with two machines"})
  (rf/reg-event-db :seed
    (fn [db _]
      (assoc db :rf/machines {:flow/login    {:state :authed   :data {}}
                              :flow/checkout {:state :pending  :data {}}})))
  (rf/dispatch-sync [:seed] {:frame :tenant-x})
  (let [traces (collect-traces ::xspec-1)]
    (rf/destroy-frame! :tenant-x)
    (stop-traces ::xspec-1)
    (let [machine-traces (filter #(= :rf.machine.lifecycle/destroyed
                                      (:operation %))
                                 @traces)]
      (is (= 2 (count machine-traces))
          "one trace per active machine snapshot at frame destroy")
      (is (every? #(= :tenant-x (:frame (:tags %))) machine-traces)
          "each trace carries the destroyed frame's id")
      (is (= #{:authed :pending}
             (set (map #(:last-state (:tags %)) machine-traces)))
          "each trace records the machine's last state")
      (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) machine-traces)
          "each trace carries :reason :parent-frame-destroyed")
      (is (some #(= :frame/destroyed (:operation %)) @traces)
          ":frame/destroyed fires after the per-machine traces"))))

;; --- Interaction 2 — Sub-cache hit inside a machine microstep -------------

(deftest machine-microstep-subscribe-helix
  "#2 Sub-cache hit inside a machine microstep — under Helix."
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
                   (fn [_data _event]
                     (reset! observed-by-action (rf/subscribe-once [:user-role]))
                     nil)}}]
    (rf/reg-machine :auth/check machine)
    (rf/dispatch-sync [:auth/check [:go]])
    (is (= :admin @observed-by-action)
        "the sub returns the committed app-db value visible to the action body")))

;; --- Interaction 3 — Machine spawn at boot before substrate adapter ready -

(deftest boot-order-adapter-ready-helix
  "#3 Machine spawn at boot before substrate adapter ready — under Helix."
  (rf/reg-event-db :init-shape (fn [_ _] {:rf/machines {:flow/boot {:state :armed
                                                                    :data  {}}}}))
  (rf/reg-frame :booted {:on-create [:init-shape]})
  (let [db (rf/get-frame-db :booted)]
    (is (= :armed (get-in db [:rf/machines :flow/boot :state]))
        ":on-create completed against an installed adapter — app-db carries the seed")))

;; --- Interaction 4 — Machines under SSR (allowed-subset) ------------------

(deftest after-noop-shape-under-ssr-server-preset-helix
  "#4 Machines under SSR (allowed-subset) — under Helix."
  (rf/reg-frame :req {:preset :ssr-server})
  (let [meta (rf/frame-meta :req)]
    (is (= :server (:platform meta))
        ":ssr-server preset sets :platform :server on the frame metadata")
    (is (= :rf.error/server-projection (:on-error meta))
        ":ssr-server preset wires :on-error to :rf.error/server-projection"))
  (rf/reg-machine :ssr/timed
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:fetch {:target :loading}}}
               :loading {:after {500 :awake}}
               :awake   {}}})
  (let [traces (collect-traces ::xspec-4-after)]
    (rf/dispatch-sync [:ssr/timed [:fetch]] {:frame :req})
    (stop-traces ::xspec-4-after)
    (let [skipped   (filter #(= :rf.machine.timer/skipped-on-server
                                (:operation %))
                            @traces)
          scheduled (filter #(= :rf.machine.timer/scheduled
                                (:operation %))
                            @traces)]
      (is (seq skipped)
          ":after on :ssr-server emits :rf.machine.timer/skipped-on-server")
      (is (some #(= :server (get-in % [:tags :platform])) skipped)
          "the skipped-on-server trace records :platform :server")
      (is (some #(= 500 (get-in % [:tags :delay])) skipped)
          "the trace carries the declared :after delay")
      (is (empty? scheduled)
          "no :rf.machine.timer/scheduled trace fires on :ssr-server"))))

;; --- Interaction 7 — Route-not-found under SSR ----------------------------

(deftest route-not-found-ssr-status-helix
  "#7 Route-not-found under SSR — under Helix."
  (rf/reg-route :user/show {:path "/users/:id"})
  (let [m (rf/match-url "/no-such-thing")]
    (is (nil? (:route-id m))
        "match-url surfaces no route-id for an unmatched URL"))
  (let [traces (collect-traces ::xspec-7)]
    (rf/match-url "/no-such-thing")
    (stop-traces ::xspec-7)
    (is (empty? (filter #(= :error (:op-type %)) @traces))
        "match-url is pure: route-not-found does not emit error traces")))

;; --- Interaction 9 — Reactive substrate without React-context -------------

(deftest headless-explicit-frame-resolution-chain-helix
  "#9 Reactive substrate without React-context — under Helix."
  (rf/reg-frame :alt {:doc "alt frame"})
  (is (= :rf/default (rf/current-frame))
      "no dynamic binding → resolves to :rf/default")
  (rf/with-frame :alt
    (is (= :alt (rf/current-frame))
        "dynamic-var tier wins over :rf/default"))
  (is (= :rf/default (rf/current-frame))
      "with-frame's binding is scoped — dynamic var reverts on exit"))

;; --- Interaction 11 — Machine action throws -------------------------------

(deftest machine-action-throws-helix
  "#11 Machine action throws — under Helix."
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
      (let [errs (filter #(= :rf.error/machine-action-exception (:operation %))
                         @traces)]
        (is (seq errs)
            "an action throw surfaces as :rf.error/machine-action-exception")
        (is (some #(= :test/m (get-in % [:tags :machine-id])) errs)
            "the trace identifies the machine that threw")
        (is (some #(= :boom (get-in % [:tags :action-id])) errs)
            "the trace identifies the action that threw"))
      (is (not (some #(= :rf.error/handler-exception (:operation %)) @traces))
          "the generic :rf.error/handler-exception does NOT also fire")
      (is (= :before (:val (rf/get-frame-db :rf/default)))
          "a non-machine app-db slice is not touched when the cascade halts"))))

;; --- Interaction 12 — Effect handler throws inside a machine action's :fx -

(deftest machine-fx-handler-throws-helix
  "#12 Effect handler throws inside a machine action's :fx — under Helix."
  (let [seen (atom [])]
    (rf/reg-fx :throwy (fn [_ _] (throw (ex-info "fx-bang" {}))))
    (rf/reg-fx :record  (fn [_ args] (swap! seen conj args)))
    (let [machine {:initial :idle
                   :data    {}
                   :states  {:idle {:on {:go {:target :done :action :emit-fx}}}
                             :done {}}
                   :actions {:emit-fx
                             (fn [_data _event]
                               {:fx [[:throwy :a]
                                     [:record :b]]})}}]
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

;; --- Interaction 13 — Hot-reload of a machine action ----------------------

(deftest hot-reload-machine-action-helix
  "#13 Hot-reload of a machine action — under Helix."
  (let [machine-v1 {:initial :idle
                    :data    {}
                    :states  {:idle    {:on {:go {:target :working
                                                  :action :tag}}}
                              :working {:on {:go {:target :idle
                                                  :action :tag}}}}
                    :actions {:tag (fn [data _]
                                     {:data (assoc data :who :v1)})}}
        machine-v2 (assoc-in machine-v1 [:actions :tag]
                             (fn [data _] {:data (assoc data :who :v2)}))]
    (rf/reg-machine :test/m machine-v1)
    (rf/dispatch-sync [:test/m [:go]])
    (is (= :v1 (get-in (rf/get-frame-db :rf/default)
                       [:rf/machines :test/m :data :who]))
        "v1 action ran on the first dispatch")
    (rf/reg-machine :test/m machine-v2)
    (rf/dispatch-sync [:test/m [:go]])
    (is (= :v2 (get-in (rf/get-frame-db :rf/default)
                       [:rf/machines :test/m :data :who]))
        "the next dispatched event resolves to the new action body")))

;; --- Interaction 14 — Re-entrant dispatch from inside a handler -----------

(deftest dispatch-sync-from-handler-raises-helix
  "#14 Re-entrant dispatch from inside a handler — under Helix."
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

;; --- Interaction 15 — Tool-Pair revert via replace-container! -------------

(deftest time-travel-revert-helix
  "#15 Re-spawning a machine instance via Tool-Pair — under Helix."
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle    {:on {:go {:target :working}}}
                           :working {:on {:go {:target :idle}}}}}]
    (rf/reg-machine :test/m machine)
    (rf/dispatch-sync [:test/m [:go]])
    (let [post-go-db (rf/get-frame-db :rf/default)]
      (is (= :working (get-in post-go-db [:rf/machines :test/m :state]))
          "machine reached :working")
      (let [container (frame/get-frame-db :rf/default)
            reverted  (assoc-in post-go-db [:rf/machines :test/m :state] :idle)]
        (adapter/replace-container! container reverted))
      (is (= :idle (get-in (rf/get-frame-db :rf/default)
                           [:rf/machines :test/m :state]))
          "after replace-container! the snapshot reads back as :idle")
      (rf/dispatch-sync [:test/m [:go]])
      (is (= :working (get-in (rf/get-frame-db :rf/default)
                              [:rf/machines :test/m :state]))
          "re-dispatch after revert advances from the restored state"))))

;; --- Interaction 16 — Error projection on the server ----------------------

(deftest server-error-projection-shape-helix
  "#16 Error projection on the server — under Helix."
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
          "the trace records the request frame's id")
      (let [err          (first errs)
            public-error (ssr/apply-error-projection! :req err)]
        (is (= 500 (:status public-error))
            "default projector maps :rf.error/handler-exception → :status 500")
        (is (= :internal-error (:code public-error))
            "default projector's :code is :internal-error")
        (is (false? (:retryable? public-error))
            "default projector's :retryable? is false")
        (is (string? (:message public-error))
            "default projector emits a one-sentence human :message")
        (is (= 500 (:status (ssr/get-response :req)))
            "the projector's :status is stamped onto the [:rf/response] accumulator")))))

;; --- Interaction 18 — Re-registering a sub mid-cascade --------------------

(deftest hot-reload-sub-mid-cascade-helix
  "#18 Re-registering a sub mid-cascade — under Helix."
  (rf/reg-event-db :seed (fn [_ _] {:n 7}))
  (rf/reg-sub :answer (fn [db _] (:n db)))
  (rf/dispatch-sync [:seed])
  (is (= 7 (rf/subscribe-once [:answer]))
      "the v1 sub computes from app-db")
  (let [_pin (rf/subscribe [:answer])]
    (rf/reg-sub :answer (fn [db _] (* 100 (:n db))))
    (is (= 700 (rf/subscribe-once [:answer]))
        "after re-registration the new sub body is in effect")
    (rf/unsubscribe [:answer])))

;; --- Interaction 19 — Story decorators that override fx -------------------

(deftest portable-story-fx-override-helix
  "#19 Story decorators that override fx — under Helix."
  (let [seen (atom [])]
    (rf/reg-fx :http             (fn [_ args] (swap! seen conj [:real-http args])))
    (rf/reg-fx :rf.test/http-stub (fn [_ args] (swap! seen conj [:stub args])))
    (rf/reg-frame :story-frame {:fx-overrides {:http :rf.test/http-stub}})
    (rf/reg-event-fx :go (fn [_ _] {:fx [[:http {:url "/x"}]]}))
    (rf/dispatch-sync [:go] {:frame :story-frame})
    (is (= [[:stub {:url "/x"}]] @seen)
        "the id-valued override redirected :http → :rf.test/http-stub")))

;; --- Interaction 20 — Adapter swap mid-process is forbidden ---------------

(deftest adapter-already-installed-helix
  "#20 Adapter swap mid-process is forbidden — under Helix."
  (let [thrown? (try
                  (rf/install-adapter! helix-adapter/adapter)
                  false
                  (catch :default e
                    (= ":rf.error/adapter-already-installed"
                       (ex-message e))))]
    (is thrown?
        "second install-adapter! raises :rf.error/adapter-already-installed"))
  (rf/destroy-adapter!)
  (rf/install-adapter! helix-adapter/adapter)
  (is (some? (adapter/current-adapter))
      "after destroy, install succeeds again — clean swap path"))
