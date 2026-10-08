(ns re-frame.cofx-envelope-test
  "The dispatch envelope's `:rf.cofx` causal token (Spec 002 §Recordable
  coeffects). The router fills `:rf/time-ms` into an absent or supplied map
  and otherwise preserves a supplied map verbatim; it rejects a malformed or
  non-EDN supplied token at the dispatch boundary; it stamps a `:dispatch-later`
  child fresh at fire time; and it treats the draft `:dispatched-at` opt as an
  unknown opt. `re-frame.recordable` is the structural-EDN walker behind the
  non-EDN check. The immediate `:dispatch` child is
  `re-frame.cofx-cljs-test/reply-envelope-carries-rf-cofx-flat-and-freshly-stamped`'s.

  JVM-only: the stamping path is platform-neutral. Only the unknown-opt warning
  is a dev-trace read, behind `rf.interop/debug-enabled?`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.recordable :as rf.recordable]
            [re-frame.registrar :as rf.registrar]
            [re-frame.router :as rf.router]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (when-let [clear-schemas! (rf.late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(def ^:private build-envelope
  "The private envelope builder; handlers never see the envelope itself."
  #'rf.router/build-envelope)

(defn- thrown
  "Call `f` and return the ExceptionInfo it throws, or nil when it returns."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

;; ---------------------------------------------------------------------------
;; Stamping and boundary validation
;; ---------------------------------------------------------------------------

(deftest preserves-caller-supplied-extra-keys-and-fills-time-ms
  (testing "supplied facts ride through verbatim and a missing :rf/time-ms is filled"
    (rf/make-frame {:id :wi/extra})
    (let [supplied {:todo/id    #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
                    :todo/color :green}
          cofx     (:rf.cofx (build-envelope [:noop] {:frame :wi/extra :rf.cofx supplied}))]
      (is (= supplied (dissoc cofx :rf/time-ms)))
      (is (number? (:rf/time-ms cofx))))))

(deftest non-map-cofx-is-a-hard-error
  (testing "a supplied non-map :rf.cofx is :rf.error/invalid-cofx, not coerced or stamped"
    (rf/make-frame {:id :wi/bad-shape})
    (is (= {:rf.error/id :rf.error/invalid-cofx :supplied [:not :a :map] :recovery :no-recovery}
           (select-keys (ex-data (thrown #(build-envelope [:noop] {:frame   :wi/bad-shape
                                                                   :rf.cofx [:not :a :map]})))
                        [:rf.error/id :supplied :recovery])))))

(deftest non-integer-time-ms-is-a-hard-error
  (testing "a supplied :rf/time-ms that is not an integer epoch-ms is
            :rf.error/invalid-cofx naming the value"
    (rf/make-frame {:id :wi/bad-time})
    (doseq [bad [nil 1781078400.5]]
      (testing (pr-str bad)
        (is (= [:rf.error/invalid-cofx bad]
               ((juxt :rf.error/id :rf/time-ms)
                (ex-data (thrown #(build-envelope [:noop] {:frame   :wi/bad-time
                                                          :rf.cofx {:rf/time-ms bad}}))))))))))

(deftest nil-supplied-cofx-passes-and-is-stamped
  (testing "an explicit nil :rf.cofx is accepted and stamped a fresh map"
    (rf/make-frame {:id :wi/valid})
    (is (number? (get-in (build-envelope [:noop] {:frame :wi/valid :rf.cofx nil})
                         [:rf.cofx :rf/time-ms])))))

;; ---------------------------------------------------------------------------
;; Supplied values must be recordable EDN
;; ---------------------------------------------------------------------------

(deftest explain-non-recordable-reports-path-and-type
  (testing "the whole EDN data domain is recordable"
    (is (nil? (rf.recordable/explain-non-recordable
                [nil true 0 -7 3.14 1/3 1000000000000000000000N "s" :k :ns/k 'sym \c
                 #uuid "00000000-0000-0000-0000-000000000000"
                 #inst "2026-06-14T00:00:00.000Z"
                 '(1 2) #{1 2} {:a 1 :b [2 {:c 3}]}]))))
  (testing "a host handle in a set element or a map key is found"
    (is (some? (rf.recordable/explain-non-recordable #{:a (fn [] 1)})))
    (is (some? (rf.recordable/explain-non-recordable {(Object.) :v}))))
  (testing "a java.time.Instant prints as #inst but reads back as a Date, so it
            is a host handle rather than recordable data"
    (let [bad (rf.recordable/explain-non-recordable (java.time.Instant/ofEpochMilli 1781078400123))]
      (is (= [] (:path bad)))
      (is (re-find #"(?i)instant" (:bad-type bad))))))

(deftest supplied-non-edn-cofx-value-is-cofx-value-invalid
  ;; This namespace also runs on the production-gate lane, which is what shows
  ;; the walk is not gated on `debug-enabled?`.
  (rf/make-frame {:id :wi/edn})
  (let [invalid (fn [supplied]
                  (ex-data (thrown #(build-envelope [:noop] {:frame :wi/edn :rf.cofx supplied}))))]
    (testing "a host handle supplied as a fact is :rf.error/cofx-value-invalid
              naming the fact and the host class, never the raw object"
      (let [data (invalid {:rf/time-ms 1781078400123 :app/handle (atom :host)})]
        (is (= {:rf.error/id         :rf.error/cofx-value-invalid
                :rf.cofx/value-error :non-edn-recordable-value
                :rf.cofx/id          :app/handle
                :path                [:app/handle]
                :recovery            :no-recovery}
               (select-keys data [:rf.error/id :rf.cofx/value-error :rf.cofx/id :path :recovery])))
        (is (re-find #"(?i)atom" (:bad-type data)))))
    (testing "the path locates a handle buried inside the fact"
      (is (= [:app/blob :items 0 :dom]
             (:path (invalid {:rf/time-ms 1781078400123
                              :app/blob   {:items [{:dom (Object.)}]}})))))
    (testing "EDN facts pass and ride through unchanged"
      (let [supplied {:rf/time-ms 1781078400123
                      :user/id    42
                      :user/prefs {:theme :dark :tags #{:a :b}}
                      :session/at #inst "2026-06-14T00:00:00.000Z"}]
        (is (= supplied (:rf.cofx (build-envelope [:noop] {:frame :wi/edn :rf.cofx supplied}))))))))

;; ---------------------------------------------------------------------------
;; Child tokens, draft opts, and handlers folding the token
;; ---------------------------------------------------------------------------

(deftest dispatch-later-child-gets-fresh-cofx-stamped-at-fire-time
  (testing "a :dispatch-later child is stamped :rf/time-ms from the clock at
            FIRE time, not the parent's token or the enqueue-time clock, while
            the trace-context keys are inherited"
    (rf/make-frame {:id :wi/later})
    (let [clock    (atom 1000)
          deferred (atom nil)
          child    (atom nil)]
      (rf/reg-fx :wi.later/capture-env (fn [m _] (reset! child (:envelope m))))
      (rf/reg-event :wi.later/parent
        (fn [_ _] {:fx [[:dispatch-later {:ms 50 :event [:wi.later/child]}]]}))
      (rf/reg-event :wi.later/child
        (fn [_ _] {:fx [[:wi.later/capture-env]]}))
      ;; Capture the timer thunk so the clock can advance between enqueue and
      ;; fire, and drain the deferred dispatch inline instead of on the JVM's
      ;; next-tick executor.
      (with-redefs [rf.interop/epoch-now-ms (fn [] @clock)
                    rf.interop/set-timeout! (fn [f _ms] (reset! deferred f) :handle)
                    rf.interop/next-tick    (fn [f] (f) nil)]
        (rf/dispatch-sync [:wi.later/parent]
                          {:frame    :wi/later
                           :trace-id :wi.later/T
                           :origin   :ui
                           :rf.cofx  {:rf/time-ms 1781078400000}})
        (reset! clock 5000)
        (@deferred))
      (is (= {:event    [:wi.later/child]
              :frame    :wi/later
              :trace-id :wi.later/T
              :origin   :ui
              :source   :fx-dispatch-later
              :rf.cofx  {:rf/time-ms 5000}}
             (select-keys @child [:event :frame :trace-id :origin :source :rf.cofx]))))))

(deftest dispatched-at-supplied-is-a-generic-unknown-opt-with-did-you-mean
  (testing "the draft :dispatched-at opt does not halt the dispatch; it trips
            the generic unknown-opt warning, whose reason points at :rf/time-ms"
    (rf/make-frame {:id :wi/retired-supply})
    (rf/reg-event :wi/retired-noop (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (let [seen (atom [])]
      (rf/register-listener! :trace ::dispatched-at (fn [ev] (swap! seen conj ev)))
      (rf/dispatch-sync [:wi/retired-noop] {:frame :wi/retired-supply :dispatched-at 123})
      (rf/unregister-listener! :trace ::dispatched-at)
      (is (true? (:ran? (rf/app-db-value :wi/retired-supply))))
      (when rf.interop/debug-enabled?
        (let [warns (filterv #(= :rf.warning/unknown-dispatch-opt (:operation %)) @seen)]
          (is (= [[:dispatched-at]] (mapv #(get-in % [:tags :unknown-keys]) warns)))
          ;; :rf/time-ms appears only in this key's did-you-mean hint, never in
          ;; the known-opts list the reason also prints.
          (is (re-find #":rf/time-ms" (get-in (first warns) [:tags :reason]))))))))

(deftest supplied-uuid-replay-stable-where-ambient-would-diverge
  (testing "a handler folding facts read off the :rf.cofx coeffect writes them
            exactly as supplied, so re-feeding the same token reproduces the
            same durable entity"
    (rf/make-frame {:id :wi/replay})
    (rf/reg-event :todo/create-from-token
      (fn [{:keys [db] cofx :rf.cofx} _]
        {:db (assoc db :entity (select-keys cofx [:todo/id :todo/color]))}))
    (let [token {:todo/id    #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
                 :todo/color :blue}
          run!  (fn []
                  (rf/dispatch-sync [:todo/create-from-token] {:frame :wi/replay :rf.cofx token})
                  (:entity (rf/app-db-value :wi/replay)))]
      (is (= token (run!) (run!))))))
