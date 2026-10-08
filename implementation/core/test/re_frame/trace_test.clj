(ns re-frame.trace-test
  "Spec 009 — trace-stream completeness and the listener API.

  A representative flow must emit every documented operation with the
  canonical envelope. JVM-only by intent: the trace stream is
  substrate-independent, so a CLJS run adds no signal."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            ;; Registers the reg-machine late-bind the representative flow uses.
            [re-frame.machines]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            ;; Publishes the listener late-bind hooks; this ns does not load
            ;; re-frame.test-support, which would otherwise pull it in.
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; clear-all! wiped the framework :rf.route/* registrations and the
  ;; test-only :rf.test/simulate-http-resolution event; re-seat both.
  (require 're-frame.routing :reload)
  (require 're-frame.routing.test-support :reload)
  ;; init! does not synthesise :rf/default (EP-0002); emit sites need a
  ;; carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; Every deftest is ^:requires-debug: emit! is a no-op under
;; -Dre-frame.debug=false (see scripts/test-core-prod-gate.sh).

(defn- valid-envelope? [ev]
  (and (integer? (:id ev))
       (number?  (:time ev))
       (keyword? (:operation ev))
       (keyword? (:op-type ev))
       (map?     (:tags ev))))

(def ^:private documented-ops
  #{[:rf.event :rf.event/run-start]
    [:rf.event :rf.event/run-end]
    [:rf.event :rf.event/db-changed]
    [:rf.fx :rf.fx/do-fx]
    [:rf.fx :rf.fx/override-applied]
    [:warning :rf.fx/skipped-on-platform]
    [:warning :rf.warning/route-shadowed-by-equal-score]
    [:rf.frame :rf.frame/created]
    [:rf.frame :rf.frame/re-registered]
    [:rf.frame :rf.frame/destroyed]
    [:rf.registry :rf.registry/handler-registered]
    [:rf.registry :rf.registry/handler-replaced]
    [:rf.registry :rf.registry/handler-cleared]
    [:rf.sub :rf.sub/run]
    [:rf.sub :rf.sub/create]
    [:rf.machine :rf.machine/transition]
    [:rf.machine :rf.machine.timer/scheduled]
    [:rf.machine :rf.machine/event-received]
    [:rf.machine :rf.machine/snapshot-updated]
    [:rf.machine.lifecycle/created :rf.machine.lifecycle/created]
    [:rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed]
    [:rf.event :rf.route.nav-token/allocated]
    [:rf.event :rf.route/fragment-changed]
    [:rf.event :rf.route/navigation-blocked]
    [:error :rf.error/handler-exception]
    [:error :rf.error/fx-handler-exception]
    [:error :rf.error/no-such-fx]
    [:error :rf.error/no-such-handler]
    [:error :rf.error/no-such-sub]
    [:error :rf.error/sub-exception]
    [:error :rf.error/dispatch-sync-in-handler]
    [:error :rf.error/frame-destroyed]
    [:error :rf.error/override-fallthrough]
    [:error :rf.error/drain-depth-exceeded]
    [:error :rf.route.nav-token/stale-suppressed]})

(deftest ^:requires-debug trace-stream-completeness
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::recorder (fn [ev] (swap! recorded conj ev)))

    (rf/make-frame {:id :test/main :doc "comprehensive flow frame"})
    (rf/make-frame {:id :test/main :doc "comprehensive flow frame (rev 2)"})

    (rf/reg-event :seed (fn [_ _] {:db {:n 0 :items [1 2 3]}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; Same fn, changed :doc: a :different-fn? false replacement that the
    ;; hot-reload dedup does not suppress.
    (let [same-fx (fn [_ _] :same)]
      (rf/reg-fx :test/same-fx same-fx)
      (rf/reg-fx :test/same-fx {:doc "same fn, new doc (rev 2)"} same-fx))
    (rf/reg-event :short-lived (fn [_ _] {}))
    (rf.registrar/unregister! :event :short-lived)
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/reg-fx :prod/sender (fn [_ _] :prod-fired))
    (rf/reg-fx :stub/sender (fn [_ _] :stub-fired))
    (rf/reg-event :send (fn [_ _] {:fx [[:prod/sender :payload]]}))
    (rf/dispatch-sync [:send] {:frame :test/main :fx-overrides {:prod/sender :stub/sender}})
    (rf/dispatch-sync [:send] {:frame :test/main :fx-overrides {:prod/sender :no-such/fx}})

    (rf/reg-event :send-broken (fn [_ _] {:fx [[:nonexistent/fx :payload]]}))
    (rf/dispatch-sync [:send-broken] {:frame :test/main})
    (rf/dispatch-sync [:no.such/event] {:frame :test/main})

    (rf/reg-event :throws (fn [_ _] {:db (throw (ex-info "oops" {:bad? true}))}))
    (rf/dispatch-sync [:throws] {:frame :test/main})

    (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "fx blew" {}))))
    (rf/reg-event :run-throwing-fx (fn [_ _] {:fx [[:throwing-fx :ignored]]}))
    (rf/dispatch-sync [:run-throwing-fx] {:frame :test/main})

    ;; The JVM plain-atom platform is :server, so a :client-only fx skips.
    (rf/reg-fx :client-only-fx {:platforms #{:client}} (fn [_ _] :nope))
    (rf/reg-event :run-client-fx (fn [_ _] {:fx [[:client-only-fx :payload]]}))
    (rf/dispatch-sync [:run-client-fx] {:frame :test/main})

    (rf/reg-sub :unresolved {:inputs [[:no-such/input]]} (fn [[v] _] v))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/subscribe-once [:unresolved] {:frame :test/main})
    (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
    (rf/subscribe-once [:throwing-sub] {:frame :test/main})

    (rf/reg-event :nested-sync
      (fn [_ _]
        (rf/dispatch-sync [:inc] {:frame :test/main})
        {}))
    (rf/dispatch-sync [:nested-sync] {:frame :test/main})
    (rf/subscribe-once [:n] {:frame :no.such/frame})

    (rf/reg-event :loop-forever (fn [_ _] {:fx [[:dispatch [:loop-forever]]]}))
    (rf/dispatch-sync [:loop-forever] {:frame :test/main})

    ;; Identical patterns: the second registration is the shadowed one.
    (rf/reg-route :route/a {} "/foo")
    (rf/reg-route :route/b {} "/foo")
    (rf/reg-route :user/show {} "/users/:id")
    (rf/dispatch-sync [:rf.route/handle-url-change "/users/42" {:rf.route/cause :link}] {:frame :test/main})
    (rf/dispatch-sync [:rf.route/handle-url-change "/users/42#section" {:rf.route/cause :link}] {:frame :test/main})
    (rf/reg-sub :always-block (fn [_ _] false))
    (rf/reg-route :nav/blocker {:can-leave :always-block} "/blockable")
    (rf/dispatch-sync [:rf.route/handle-url-change "/blockable" {:rf.route/cause :link}] {:frame :test/main})
    (rf/dispatch-sync [:rf.route/url-requested {:url "/users/42"}] {:frame :test/main})
    (rf/dispatch-sync [:rf.route/handle-url-change "/users/7" {:rf.route/cause :link}] {:frame :test/main})
    (rf/dispatch-sync [:rf.test/simulate-http-resolution
                       {:carried-nav-token :stale/token :on-success-event [:noop]}]
                      {:frame :test/main})

    ;; :green declares an :after, so entering it schedules the timer.
    (rf/reg-machine :machine/tl
                    {:initial :red
                     :data    {}
                     :states  {:red    {:on {:tick {:target :green}}}
                               :green  {:after {500 {:target :yellow}}
                                        :on    {:tick {:target :yellow}}}
                               :yellow {:on {:tick {:target :red}}}}})
    (rf/reg-event :machine/init
      (fn [{:keys [db]} _]
        {:db (assoc-in db [:rf.runtime/machines :snapshots :machine/tl] {:state :red :data {}})}))
    (rf/dispatch-sync [:machine/init] {:frame :test/main})
    (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})
    (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})

    (rf/destroy-frame! :test/main)
    (rf/unregister-listener! :trace ::recorder)

    (let [events   @recorded
          event-of (fn [op-type operation]
                     (some #(when (and (= op-type (:op-type %)) (= operation (:operation %))) %)
                           events))]
      (is (every? valid-envelope? events)
          (str "non-conformant envelopes — first 3: "
               (vec (take 3 (remove valid-envelope? events)))))

      (is (= #{} (set/difference documented-ops (set (map (juxt :op-type :operation) events))))
          "every documented Spec 009 operation is emitted")

      (testing "op-specific tag values"
        (doseq [[op-type operation path expected]
                [[:rf.fx    :rf.fx/override-applied       [:tags :rf.fx/from]                 :prod/sender]
                 [:rf.fx    :rf.fx/override-applied       [:tags :rf.fx/to]                   :stub/sender]
                 [:warning  :rf.fx/skipped-on-platform    [:tags :rf.fx/id]                   :client-only-fx]
                 [:warning  :rf.fx/skipped-on-platform    [:tags :rf.fx/registered-platforms] #{:client}]
                 [:error    :rf.error/handler-exception   [:recovery]                         :no-recovery]
                 [:error    :rf.error/fx-handler-exception [:tags :rf.fx/id]                  :throwing-fx]
                 [:error    :rf.error/no-such-fx          [:tags :rf.fx/id]                   :nonexistent/fx]
                 [:error    :rf.error/no-such-handler     [:tags :rf.trace/event-id]          :no.such/event]
                 [:error    :rf.error/no-such-handler     [:tags :kind]                       :event]]]
          (is (= expected (get-in (event-of op-type operation) path)) (str operation " " path))))

      (testing "op-specific tag shapes"
        (doseq [[op-type operation k pred]
                [[:rf.event   :rf.event/run-start         :rf.trace/event-id keyword?]
                 [:rf.event   :rf.event/run-start         :rf.event/v        vector?]
                 [:rf.event   :rf.event/db-changed        :rf.trace/event-id keyword?]
                 [:rf.event   :rf.event/db-changed        :rf.event/v        vector?]
                 [:rf.fx      :rf.fx/do-fx                :frame             keyword?]
                 [:warning    :rf.warning/route-shadowed-by-equal-score :route-id    keyword?]
                 [:warning    :rf.warning/route-shadowed-by-equal-score :shadowed-by keyword?]
                 [:warning    :rf.warning/route-shadowed-by-equal-score :rank
                  #(and (vector? %) (= 5 (count %)))]
                 [:rf.frame   :rf.frame/created           :frame             keyword?]
                 [:rf.frame   :rf.frame/created           :config            map?]
                 [:rf.frame   :rf.frame/re-registered     :frame             keyword?]
                 [:rf.frame   :rf.frame/destroyed         :frame             keyword?]
                 [:rf.machine :rf.machine/transition      :actor-id          keyword?]
                 [:rf.machine :rf.machine/transition      :event             vector?]
                 [:rf.machine :rf.machine/transition      :before            map?]
                 [:rf.machine :rf.machine/transition      :after             map?]
                 [:rf.machine :rf.machine.timer/scheduled :state             keyword?]
                 [:rf.machine :rf.machine.timer/scheduled :delay             number?]
                 [:rf.event   :rf.route.nav-token/allocated :route-id        keyword?]
                 [:rf.event   :rf.route.nav-token/allocated :nav-token       some?]
                 [:rf.event   :rf.route/fragment-changed  :route-id          keyword?]
                 [:rf.event   :rf.route/fragment-changed  :next-fragment     string?]
                 [:rf.event   :rf.route/navigation-blocked :requested-url    string?]
                 [:rf.event   :rf.route/navigation-blocked :rejecting-route  keyword?]
                 [:error      :rf.error/handler-exception :exception-message string?]
                 [:error      :rf.error/handler-exception :event             some?]
                 [:error      :rf.error/fx-handler-exception :exception-message string?]
                 [:error      :rf.error/drain-depth-exceeded :depth          number?]
                 [:error      :rf.error/drain-depth-exceeded :last-event     some?]]]
          (is (pred (get-in (event-of op-type operation) [:tags k])) (str operation " " k))))

      (testing ":rf.registry/handler-replaced fires on every re-registration, tagged :different-fn?"
        (let [replaced (filter #(= :rf.registry/handler-replaced (:operation %)) events)
              changed  (:tags (first (filter #(true? (get-in % [:tags :different-fn?])) replaced)))]
          (is (set/subset? #{true false} (set (map #(get-in % [:tags :different-fn?]) replaced))))
          (is (keyword? (:kind changed)))
          (is (some? (:id changed))))))))

;; ---- per-op DURATION timing -------------------------------------------------
;; :rf.sub/elapsed-ms needs a reactive adapter, so view_rendered_op_cljs_test
;; pins it; fx, flow and handler-body timing fire on plain-atom JVM.

(deftest ^:requires-debug per-op-timing-tags-on-the-trace-stream
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::timing (fn [ev] (swap! recorded conj ev)))
    (rf/reg-flow :timing/doubled {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 (or n 0))))
    (rf/reg-fx :timing/side (fn [_ _] :ok))
    (rf/reg-event :timing/seed (fn [_ _] {:db {:n 1} :fx [[:timing/side :go]]}))
    (rf/dispatch-sync [:timing/seed])
    (rf/unregister-listener! :trace ::timing)
    (doseq [[operation k] [[:rf.fx/handled    :rf.fx/elapsed-ms]
                           [:rf.flow/computed :elapsed-ms]
                           [:rf.event/run-end :rf.event/elapsed-ms]]]
      (let [evs (filter #(= operation (:operation %)) @recorded)]
        (is (seq evs) (str operation " emitted"))
        (is (every? #(number? (get-in % [:tags k])) evs)
            (str "every " operation " carries a numeric " k))))))

;; ---- listener API: lifecycle and isolation --------------------------------

(deftest ^:requires-debug trace-listener-lifecycle
  (let [a (atom 0) b (atom 0) a2 (atom 0)]
    (rf/register-listener! :trace ::a (fn [_] (swap! a inc)))
    (rf/register-listener! :trace ::b (fn [_] (swap! b inc)))
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db (assoc db :ping? true)}))
    (rf/dispatch-sync [:ping])
    (is (pos? @a))
    (is (= @a @b) "every listener receives every event")
    (testing "unregister-listener! removes only that id"
      (let [a-before @a b-before @b]
        (rf/unregister-listener! :trace ::b)
        (rf/dispatch-sync [:ping])
        (is (= b-before @b))
        (is (> @a a-before))))
    (testing "re-registering an id replaces its listener"
      (let [a-before @a]
        (rf/register-listener! :trace ::a (fn [_] (swap! a2 inc)))
        (rf/dispatch-sync [:ping])
        (is (= a-before @a))
        (is (pos? @a2))))
    (rf/unregister-listener! :trace ::a)))

(deftest ^:requires-debug trace-listener-exception-isolation
  (let [survivor    (atom 0)
        throw-count (atom 0)]
    (rf/register-listener! :trace ::throwing
      (fn [_] (swap! throw-count inc) (throw (ex-info "tool blew up" {}))))
    (rf/register-listener! :trace ::survivor (fn [_] (swap! survivor inc)))
    (rf/reg-event :init (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    ;; Two dispatches: the second proves the first throw left delivery intact.
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:inc])
    (is (= 1 (:n (rf/app-db-value :rf/default))) "dispatch ran to completion")
    (is (pos? @throw-count) "the throwing listener was invoked")
    (is (pos? @survivor) "the other listener still received events")
    (rf/unregister-listener! :trace ::throwing)
    (rf/unregister-listener! :trace ::survivor)))

;; ---- :rf.trace/no-emit? handler opt-out (Spec 009 §Trace-emission opt-out) --

(defn- traces-for
  "Operations of the recorded traces attributed to `event-id`."
  [recorded event-id]
  (set (keep (fn [ev]
               (let [tags (:tags ev)
                     eid  (or (:rf.trace/event-id tags)
                              (let [v (:rf.event/v tags)] (when (vector? v) (first v))))]
                 (when (= event-id eid) (:operation ev))))
             recorded)))

(deftest ^:requires-debug no-emit-handler-suppresses-every-cascade-trace
  (rf/reg-event :rf2-qsjda/internal-bookkeeping {:rf.trace/no-emit? true}
                (fn [{:keys [db]} _] {:db (assoc db :bookkeeping/ran? true)}))
  (rf/reg-event :rf2-qsjda/normal (fn [{:keys [db]} _] {:db (assoc db :normal/ran? true)}))
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
    (rf/dispatch-sync [:rf2-qsjda/internal-bookkeeping])
    (rf/dispatch-sync [:rf2-qsjda/normal])
    (rf/unregister-listener! :trace ::rec)
    (is (true? (:bookkeeping/ran? (rf/app-db-value :rf/default)))
        "the flag opts out of trace emission, not handler execution")
    (is (= #{} (traces-for @recorded :rf2-qsjda/internal-bookkeeping)))
    (is (set/subset? #{:rf.event/dispatched :rf.event/run-start :rf.event/run-end :rf.event/db-changed}
                     (traces-for @recorded :rf2-qsjda/normal))
        "control: the same dispatch shape without the flag emits the cascade")))

(deftest ^:requires-debug no-such-sub-recovery-is-replaced-with-default
  (testing "both :rf.error/no-such-sub emit sites carry :recovery :replaced-with-default"
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::no-such-sub (fn [ev] (swap! recorded conj ev)))
      @(rf/subscribe [:never/registered])
      (is (nil? (rf.subs/compute-sub-with-memo
                  [:never/registered-cold] {}
                  (atom {rf.subs/observation-opts-key {:frame :rf/default}})))
          "the ownership-free cold read substitutes nil")
      (rf/unregister-listener! :trace ::no-such-sub)
      (let [by-site (group-by #(= :observation-cold-probe (get-in % [:tags :where]))
                              (filter #(= :rf.error/no-such-sub (:operation %)) @recorded))]
        (is (= [:replaced-with-default] (mapv :recovery (get by-site false))) "reactive build")
        (is (= [:replaced-with-default] (mapv :recovery (get by-site true))) "cold probe")))))
