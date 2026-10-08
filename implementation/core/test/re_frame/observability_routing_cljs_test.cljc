(ns re-frame.observability-routing-cljs-test
  "EP-0015 §9 — frame-owned observability sink routing, end to end through a
  real dispatch: the runtime projects every record under the owning frame's
  classification and the sink's egress profile before the sink sees it, so a
  sink consumes already-projected records only. `re-frame.projection-cljs-test`
  unit-tests `project-egress` on hand-built records; here the router's
  cascade trailers build the `:rf.observe/handled-event` record, and a real
  failure drives `rf.error-emit/dispatch-on-error!` to build the
  `:rf.observe/error` one."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.observability :as rf.observability]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(defn- classify-sensitive!
  [frame-id path]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [path]}))))

(defn- error-of-kind [kind records]
  (some #(when (= kind (:error %)) %) records))

;; ---- handled events ---------------------------------------------------------

(deftest handled-event-routes-projected-to-declared-sink
  (testing "a real dispatch routes one projected :rf.observe/handled-event record
            to the frame's declared sink, and the off-box default omits the
            :event args slot"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/datadog
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/main :observability
                      {:handled-events [{:sink :test.sinks/datadog
                                         :rf.egress/profile :rf.egress/off-box-observability}]}})
      (rf/reg-event :auth/login {:frame :obs/main} (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:auth/login {:password "hunter2"}] {:frame :obs/main})
      (is (= [{:kind :rf.observe/handled-event :frame :obs/main :event-id :auth/login
               :status :ok :effects [:db]}]
             (mapv #(select-keys % [:kind :frame :event-id :status :effects]) @seen)))
      (let [r (first @seen)]
        (is (integer? (:elapsed-ms r)))
        (is (not (contains? r :event))
            "the sink never sees the raw event payload")))))

(deftest handled-event-sink-honours-no-emit
  (testing "a handler registered with :rf.trace/no-emit? true routes no
            :handled-events record (Spec 009 §Trace-emission opt-out)"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/no-emit
                                       (fn [record] (swap! seen conj (:event-id record))))
      (rf/make-frame {:id :obs/quiet :observability
                      {:handled-events [{:sink :test.sinks/no-emit}]}})
      (rf/reg-event :bookkeeping/tick {:rf.trace/no-emit? true} (fn [_ _] {}))
      (rf/reg-event :app/tick (fn [_ _] {}))
      (rf/dispatch-sync [:bookkeeping/tick] {:frame :obs/quiet})
      (rf/dispatch-sync [:app/tick] {:frame :obs/quiet})
      (is (= [:app/tick] @seen)))))

(deftest handled-event-sink-receives-already-projected-event-under-raw-profile
  (testing "a frame's :rf.egress/local-raw entry keeps the :event slot the off-box
            default omits"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/local
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/raw :observability
                      {:handled-events [{:sink :test.sinks/local
                                         :rf.egress/profile :rf.egress/local-raw}]}})
      (rf/reg-event :pay/submit {:frame :obs/raw} (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:pay/submit {:amount 10}] {:frame :obs/raw})
      (is (= [[:pay/submit {:amount 10}]] (mapv :event @seen))))))

;; ---- errors -----------------------------------------------------------------

(deftest error-routes-projected-to-declared-error-sink
  (testing "a handler exception routes one projected :rf.observe/error record to
            the frame's declared :errors sink, with the frame-classified token
            inside the error's :event redacted"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/err :observability
                      {:errors [{:sink :test.sinks/sentry
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      (classify-sensitive! :obs/err [:auth :token])
      (rf/reg-event :auth/login
                    {:frame :obs/err}
                    (fn [{:keys [db]} _] {:db (throw (ex-info "kaboom" {:cause :test}))}))
      (rf/dispatch-sync [:auth/login {:auth {:token "super-secret-token"}}]
                        {:frame :obs/err})
      (is (= [{:kind :rf.observe/error :frame :obs/err
               :error :rf.error/handler-exception :event-id :auth/login}]
             (mapv #(select-keys % [:kind :frame :error :event-id]) @seen)))
      (is (= :rf/redacted (get-in (first @seen) [:event 1 :auth :token]))))))

;; ---- fail-closed, and sink isolation ----------------------------------------

(deftest no-observability-policy-routes-nothing
  (testing "a frame with no :observability policy routes nothing, whatever sinks
            are registered"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/unused
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/none})
      (rf/reg-event :evt/noop {:frame :obs/none} (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:evt/noop] {:frame :obs/none})
      (is (empty? @seen)))))

(deftest buggy-sink-is-isolated-from-siblings
  (testing "a throwing sink cannot block a sibling sink on the same stream"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/boom
                                       (fn [_record] (throw (ex-info "sink bug" {}))))
      (rf/register-observability-sink! :test.sinks/good
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/sib :observability
                      {:handled-events [{:sink :test.sinks/boom
                                         :rf.egress/profile :rf.egress/off-box-observability}
                                        {:sink :test.sinks/good
                                         :rf.egress/profile :rf.egress/off-box-observability}]}})
      (rf/reg-event :evt/go {:frame :obs/sib} (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:evt/go] {:frame :obs/sib})
      (is (= [:rf.observe/handled-event] (mapv :kind @seen))))))

;; ---- producer attribution on the sink route ---------------------------------
;;
;; The sink is the only production door for errors, so it carries the producer's
;; component attribution. Structural ids are summary slots; `:reason` is prose
;; that can interpolate app values, so it rides `:tags`, walked under the
;; frame's classification (Spec 015 §Frame-owned observability sink policy).
;; Each event plants its own source-coord so the `:source-coord` equality
;; compares values rather than two nils.

(deftest error-sink-record-carries-producer-component-attribution
  (testing "a throwing interceptor and a throwing coeffect supplier each deliver a
            record whose :failing-id names the failing component, distinct from
            :event-id, with the producer's :source-coord, and :reason on :tags"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/attribution
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/attr :observability
                      {:errors [{:sink :test.sinks/attribution
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      (rf/reg-interceptor :kuky65/boom-after
                          {:after (fn [_ctx] (throw (ex-info "after boom" {})))})
      (rf/reg-event :kuky65/with-throwing-interceptor
                    {:frame :obs/attr :interceptors [:kuky65/boom-after]}
                    (fn [{:keys [db]} _] {:db (assoc db :x 1)}))
      (rf/reg-cofx :kuky65/boom-cofx
                   (fn [] (throw (ex-info "cofx supplier boom" {}))))
      (rf/reg-event :kuky65/needs-boom-cofx
                    {:frame :obs/attr :rf.cofx/requires [:kuky65/boom-cofx]}
                    (fn [{:keys [db]} _] {:db db}))
      (doseq [[error event-id failing-id coord]
              [[:rf.error/interceptor-exception :kuky65/with-throwing-interceptor :kuky65/boom-after
                {:ns 'kuky65.attr :file "test/kuky65/attr.cljc" :line 4242}]
               [:rf.error/coeffect-exception :kuky65/needs-boom-cofx :kuky65/boom-cofx
                {:ns 'kuky65.cofx :file "test/kuky65/cofx.cljc" :line 77}]]]
        (testing (name error)
          (rf.source-coords/remember-error-coords! :event event-id coord)
          (rf/dispatch-sync [event-id] {:frame :obs/attr})
          (let [r (error-of-kind error @seen)]
            (is (= [event-id failing-id coord nil]
                   [(:event-id r) (:failing-id r) (:source-coord r) (:reason r)]))
            (is (string? (get-in r [:tags :reason])))))))))

(deftest classified-reason-redacts-whole-slot-while-attribution-survives
  (testing "with [:reason] classified sensitive, the interpolated :reason redacts
            whole while :failing-id and :source-coord survive beside it"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/classified-reason
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/reason :observability
                      {:errors [{:sink :test.sinks/classified-reason
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      (classify-sensitive! :obs/reason [:reason])
      (rf/reg-cofx :kuky65/classified-boom-cofx
                   (fn [] (throw (ex-info "supplier leaked hunter2 into its message" {}))))
      (rf/reg-event :kuky65/classified-reason-event
                    {:frame :obs/reason :rf.cofx/requires [:kuky65/classified-boom-cofx]}
                    (fn [{:keys [db]} _] {:db db}))
      (rf.source-coords/remember-error-coords!
        :event :kuky65/classified-reason-event
        {:ns 'kuky65.reason :file "test/kuky65/reason.cljc" :line 11})
      (try (rf/dispatch-sync [:kuky65/classified-reason-event] {:frame :obs/reason})
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
      (let [r (error-of-kind :rf.error/coeffect-exception @seen)]
        (is (= [:rf/redacted
                :kuky65/classified-boom-cofx
                {:ns 'kuky65.reason :file "test/kuky65/reason.cljc" :line 11}]
               [(get-in r [:tags :reason]) (:failing-id r) (:source-coord r)]))))))

(deftest error-sink-attribution-survives-public-error-profile
  (testing "under :rf.egress/public-error, which drops :exception, the attribution
            survives while a frame-classified path inside :event stays redacted"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/public
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/pub :observability
                      {:errors [{:sink :test.sinks/public
                                 :rf.egress/profile :rf.egress/public-error}]}})
      (classify-sensitive! :obs/pub [:auth :token])
      (rf/reg-interceptor :kuky65/pub-boom
                          {:after (fn [_ctx] (throw (ex-info "after boom" {})))})
      (rf/reg-event :kuky65/pub-event
                    {:frame :obs/pub :interceptors [:kuky65/pub-boom]}
                    (fn [{:keys [db]} _] {:db (assoc db :x 1)}))
      (rf/dispatch-sync [:kuky65/pub-event {:auth {:token "super-secret-token"}}]
                        {:frame :obs/pub})
      (let [r (error-of-kind :rf.error/interceptor-exception @seen)]
        (is (= [:kuky65/pub-boom false :rf/redacted]
               [(:failing-id r) (contains? r :exception) (get-in r [:event 1 :auth :token])]))))))
