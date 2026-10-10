(ns re-frame.smoke-test
  "End-to-end JVM checks of the foundation through the plain-atom adapter.

  ## Posture split

  Every assertion here is posture-independent — it must hold in the ordinary
  `clojure -M:test` suite AND under the real production gate
  (`scripts/test-core-prod-gate.sh`, `-Dre-frame.debug=false`) — UNLESS it sits
  inside a `(when rf.interop/debug-enabled? …)` arm. Those arms observe the DEV
  `:trace` stream, whose emit sites the production gate removes by design."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing]
            [re-frame.machines]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.ssr]
            ;; publishes the :http/reg-http-interceptor late-bind hook that
            ;; registry-introspection-round-trip exercises
            [re-frame.http.managed]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; a stale last-inputs entry would make a same-keyed flow's first
  ;; evaluation no-op (Spec 013 §Dirty-check semantics)
  (rf.flows/reset-last-inputs!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; clear-all! wiped the framework registrations these namespaces make at load
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http.managed :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(deftest compute-sub-emits-sub-exception-on-body-throw
  ;; parity with the reactive path (Spec 009 §Error contract): a throwing body
  ;; recovers to nil and emits :rf.error/sub-exception, never a silent nil
  (testing "layer-1 body throw"
    (rf/reg-sub :boom (fn [_db _q] (throw (ex-info "boom" {:k :v}))))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::boom (fn [ev] (swap! traces conj ev)))
      (is (nil? (rf/compute-sub [:boom] {})))
      (rf/unregister-listener! :trace ::boom)
      (when rf.interop/debug-enabled?
        (let [ev (some #(when (= :rf.error/sub-exception (:operation %)) %) @traces)]
          (is (= [:error :replaced-with-default
                  {:failing-id :boom :rf.sub/id :boom :sub-query [:boom]
                   :where :compute-sub :exception-message "boom"}
                  true]
                 [(:op-type ev) (:recovery ev)
                  (select-keys (:tags ev) [:failing-id :rf.sub/id :sub-query
                                           :where :exception-message])
                  (instance? Throwable (:exception (:tags ev)))]))))))
  (testing "layer-2 body throw"
    (rf/reg-sub :n   (fn [db _] (:n db)))
    (rf/reg-sub :n*2 {:inputs [[:n]]} (fn [[_n] _q] (throw (ex-info "kaboom" {}))))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::boom2 (fn [ev] (swap! traces conj ev)))
      (is (nil? (rf/compute-sub [:n*2] {:n 7})))
      (rf/unregister-listener! :trace ::boom2)
      (when rf.interop/debug-enabled?
        (is (some #(and (= :rf.error/sub-exception (:operation %))
                        (= [:n*2 :compute-sub] ((juxt :rf.sub/id :where) (:tags %))))
                  @traces))))))

(deftest subscribe-handles-missing-frame
  (rf/reg-sub :n (fn [db _] (:n db)))
  (let [traces (atom [])]
    (rf/register-listener! :trace ::missing (fn [ev] (swap! traces conj ev)))
    (is (= [nil nil] [(rf/subscribe [:n] {:frame :missing/frame})
                      (rf/subscribe-once [:n] {:frame :missing/frame})])
        "neither call throws; both recover to nil")
    (rf/unregister-listener! :trace ::missing)
    (when rf.interop/debug-enabled?
      (is (some #(= [:rf.error/frame-destroyed :replaced-with-default]
                    ((juxt :operation :recovery) %))
                @traces)))))

(deftest sync-dispatch-from-handler-body-routes-to-handlers-frame
  ;; the router binds *current-frame* to the envelope's :frame while a handler
  ;; runs, so a synchronous dispatch (and current-frame-id) inside it sees the
  ;; handler's frame, not the ambient one
  (rf/make-frame {:id :jvm/tenant-a})
  (rf/make-frame {:id :jvm/tenant-b})
  (rf/reg-event :jvm/seed (fn [_ _] {:db {:received []}}))
  (doseq [f [:jvm/tenant-a :jvm/tenant-b :rf/default]]
    (rf/dispatch-sync [:jvm/seed] {:frame f}))
  (let [observed (atom nil)]
    (rf/reg-event :jvm/parent
      (fn [_ _]
        (reset! observed (rf/current-frame-id))
        (rf/dispatch [:jvm/landed])
        {}))
    (rf/reg-event :jvm/landed
      (fn [{:keys [db]} _] {:db (update db :received conj :landed)}))
    (rf/dispatch-sync [:jvm/parent] {:frame :jvm/tenant-a})
    (is (= [:jvm/tenant-a [:landed] [] []]
           (into [@observed]
                 (map #(:received (rf/app-db-value %))
                      [:jvm/tenant-a :jvm/tenant-b :rf/default]))))))

(deftest compute-sub-memoises-shared-input-in-a-diamond-jvm
  ;; compute-sub threads a per-call memo through its declared-input recursion:
  ;; each distinct sub computes at most once per top-level call, and never
  ;; across calls
  (let [root-calls (atom 0)]
    (rf/reg-sub :memo/root (fn [db _] (swap! root-calls inc) (:n db)))
    (rf/reg-sub :memo/a {:inputs [[:memo/root]]} (fn [[r] _] (inc r)))
    (rf/reg-sub :memo/b {:inputs [[:memo/root]]} (fn [[r] _] (dec r)))
    (rf/reg-sub :memo/c {:inputs [[:memo/a] [:memo/b]]} (fn [[a b] _] {:a a :b b}))
    (is (= [{:a 11 :b 9} 1] [(rf/compute-sub [:memo/c] {:n 10}) @root-calls])
        ":root computed once despite two diamond paths reaching it")
    (is (= [{:a 21 :b 19} 2] [(rf/compute-sub [:memo/c] {:n 20}) @root-calls])
        "a second call recomputes against its own db")
    (is (nil? (rf/compute-sub [:no-such-sub] {})) "an unknown sub is nil, not a throw")))

(deftest flow-rectangle-area
  (rf/reg-event :init (fn [_ _] {:db {:width 0 :height 0}}))
  (rf/reg-event :w! (fn [{:keys [db]} [_ w]] {:db (assoc db :width w)}))
  (rf/reg-event :h! (fn [{:keys [db]} [_ h]] {:db (assoc db :height h)}))
  (rf/reg-flow :rect/area {:inputs [[:width] [:height]] :output-path [:area]} (fn [w h] (* w h)))
  (rf/dispatch-sync [:init])
  (rf/dispatch-sync [:w! 3])
  (rf/dispatch-sync [:h! 4])
  (is (= 12 (:area (rf/app-db-value :rf/default)))))

(deftest spawn-id-is-frame-scoped
  ;; the spawn counter lives in each parent snapshot, so independent frames
  ;; never share an actor-id sequence
  (rf/reg-machine :worker {:initial :running :data {} :states {:running {}}})
  (rf/reg-machine :flow
                  {:initial :idle
                   :data    {}
                   :states  {:idle    {:on {:start :working}}
                             :working {:spawn {:machine-id :worker
                                               :id-prefix  :worker
                                               :start      [:begin]}
                                       :on    {:done :idle}}}})
  (rf/make-frame {:id :left})
  (rf/make-frame {:id :right})
  (rf/dispatch-sync [:flow [:start]] {:frame :left})
  (rf/dispatch-sync [:flow [:start]] {:frame :right})
  (is (= [1 1]
         (mapv #(get-in (:rf.db/runtime (rf/frame-state-value %))
                        [:rf.runtime/machines :snapshots :flow :rf/spawn-counter :worker])
               [:left :right]))))

(deftest spawn-and-destroy-machine-fx
  (rf/reg-machine :worker {:initial :running :data {} :states {:running {}}})
  (rf/reg-event :do-spawn
    (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :worker
                                        :id-prefix  :worker
                                        :start      [:begin]}]
                    [:rf.machine/destroy :worker#1]]}))
  (rf/dispatch-sync [:do-spawn])
  (is (= {:spawn-counter {:worker 1} :snapshots {}}
         (select-keys (:rf.runtime/machines (:rf.db/runtime (rf/frame-state-value :rf/default)))
                      [:spawn-counter :snapshots]))
      "both fx were handled: the spawn allocated :worker#1 and the destroy removed it"))

;; The registered machine's SPEC map, read through the generic registrar query
;; plus the `:rf/machine` inner-key projection (Spec 005 §Querying machines).
(defn- machine-spec [machine-id]
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id machine-id})))

(deftest machines-introspection
  (let [tiny-spec  {:initial :idle
                    :doc     "A tiny test machine."
                    :states  {:idle {:on {:tick :idle}}}}
        other-spec {:initial :off
                    :states  {:off {:on {:flip :on}}
                              :on  {:on {:flip :off}}}}]
    (rf/reg-machine :test/tiny tiny-spec)
    (rf/reg-machine :test/other other-spec)
    (rf/reg-event :test/regular (fn [{:keys [db]} _] {:db db}))
    (is (= #{:test/tiny :test/other}
           (set/intersection #{:test/tiny :test/other :test/regular}
                             (set (keys (into {} (filter (fn [[_ m]] (:rf/machine? m)))
                                              (rf/registrations {:source :store :kind :event}))))))
        "the :rf/machine? filter lists reg-machine ids and excludes plain events")
    (is (= [tiny-spec other-spec nil nil]
           (map machine-spec [:test/tiny :test/other :test/regular :test/never-registered])))))

;; The introspection re-exports (`registrations`, `handler-meta`) across every
;; registration kind. There is no `handler-ids` projection — the id set is
;; `(set (keys (registrations kind)))`.
(deftest registry-introspection-round-trip
  (rf/reg-event :o1bp/evt1 (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :o1bp/evt2 (fn [_ _] {}))
  (rf/reg-sub :o1bp/sub1 (fn [db _] db))
  (rf/reg-fx :o1bp/fx1 {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-cofx :o1bp/cofx1 (fn [] :stub))
  (rf/reg-view* :o1bp/view1 (fn [] [:div "v1"]))
  (rf/reg-machine :o1bp/mach1 {:initial :idle :data {} :states {:idle {}}})
  (rf/reg-route :o1bp/route1 {} "/o1bp/landing")
  ;; flows and http interceptors live in their artefacts' own per-frame stores
  (rf/reg-flow :o1bp/flow1 {:inputs [] :output-path [:o1bp/flow-output]} (fn [_inputs] :computed))
  (rf/reg-http-interceptor :o1bp/interceptor1 {:before identity})
  (rf/reg-error-projector :o1bp/err1 (fn [_ _] {}))
  (rf/reg-app-schema [:o1bp/path] :any)
  (let [ids-of (fn [kind] (set (keys (rf/registrations {:source :store :kind kind}))))]
    (testing "the id set per kind"
      (doseq [[kind id] [[:event :o1bp/evt1] [:event :o1bp/evt2] [:event :o1bp/mach1]
                         [:sub :o1bp/sub1] [:fx :o1bp/fx1] [:cofx :o1bp/cofx1]
                         [:view :o1bp/view1] [:route :o1bp/route1]
                         [:error-projector :o1bp/err1]]]
        (is (contains? (ids-of kind) id) (str kind " lists " id)))
      ;; :flow is reserved-but-empty: the query throws rather than answering an
      ;; authoritative-looking {} over a store that lives elsewhere
      (is (thrown? clojure.lang.ExceptionInfo (ids-of :flow)))
      (is (not (rf.registrar/valid-kind? :app-schema)) ":app-schema is not a registrar kind"))
    (testing "handler-meta per id"
      (let [evt (rf/handler-meta {:source :store :kind :event :id :o1bp/evt1})]
        (is (= [true false] [(fn? (:handler-fn evt)) (contains? evt :event/kind)])
            "carries the handler fn and no :event/kind sub-tag (EP-0018)"))
      (is (= [true true] ((juxt (comp true? :rf/machine?) (comp map? :rf/machine))
                          (rf/handler-meta {:source :store :kind :event :id :o1bp/mach1}))))
      (is (= "/o1bp/landing" (:path (rf/handler-meta {:source :store :kind :route :id :o1bp/route1}))))
      (is (thrown? clojure.lang.ExceptionInfo
            (rf/handler-meta {:source :store :kind :flow :id :o1bp/flow1}))
          "a :flow query throws :rf.error/registrar-kind-not-queryable")
      (is (= {:output-path [:o1bp/flow-output] :inputs []}
             (select-keys (rf.flows/flow-meta {:frame :rf/default :id :o1bp/flow1})
                          [:output-path :inputs])))
      (is (nil? (rf/handler-meta {:source :store :kind :event :id :no-such-event}))))))
