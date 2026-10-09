(ns re-frame.schemas-test
  "JVM tests for Spec 010 schema validation: the validation sites and their
  traces (app-db, event, sub-return, fx-args, recordable cofx), per-frame
  registration, the digest, the validator-install seam and the
  `:boundary? true` production arm. The dev/production toggle is the JVM
  mirror of `goog.DEBUG`, `re-frame.interop/debug-enabled?` (or
  `re-frame.spec/dev-mode?` for the boundary arm), flipped with `with-redefs`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.validator :as rf.schemas.validator]
            [re-frame.schemas.digest-parity-fixtures :as rf.schemas.digest-parity-fixtures]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.spec :as rf.spec]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- failures [traces]
  (filterv #(= :rf.error/schema-validation-failure (:operation %)) @traces))

;; ---- app-db ----------------------------------------------------------------

(deftest app-db-rejection-skips-fx-on-failure
  (testing "a rejected candidate never installs and its :fx do not run"
    (let [fx-calls (atom [])]
      (rf/reg-fx :test/note (fn [v] (swap! fx-calls conj v)))
      (rf/reg-app-schema [:n] [:int])
      (rf/reg-event :n/init (fn [_ _] {:db {:n 0}}))
      (rf/reg-event :n/break-with-fx
        (fn [_ _] {:db {:n "boom"} :fx [[:test/note :should-not-fire]]}))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break-with-fx])
      (is (= [{:n 0} []] [(rf/app-db-value :rf/default) @fx-calls])))))

(deftest validate-app-schema-returns-boolean
  (testing "with the default validator, true on conform and false on failure"
    (rf/reg-app-schema [:n] [:int])
    (is (= [true false] [(rf.schemas/validate-app-schema! {:n 42})
                         (rf.schemas/validate-app-schema! {:n "boom"})]))))

(deftest schema-fires-only-on-the-frame-it-registers-against
  (testing "a commit is checked against its own frame's schemas only"
    (rf/make-frame {:id :test/main})
    (rf/make-frame {:id :test/other})
    (rf/reg-app-schema [:n] {:frame :test/other} [:int])
    (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "not-an-int")}))
    (let [failing-frames (fn []
                           (with-trace-recorder! [traces]
                             (rf/dispatch-sync [:n/break] {:frame :test/main})
                             (mapv (comp :frame :tags) (failures traces))))]
      (is (= [] (failing-frames)))
      (rf/reg-app-schema [:n] {:frame :test/main} [:int])
      (is (= [:test/main] (failing-frames))))))

;; ---- event, sub-return, fx-args and recordable cofx through dispatch -------

(deftest event-payload-validation-failure-still-runs-after-pass
  (testing "a failed event :schema skips the handler but the interceptor chain
            still runs, so :after cleanup fires"
    (let [calls (atom [])]
      (rf/reg-interceptor ::probe
        {:before (fn [ctx] (swap! calls conj :before) ctx)
         :after  (fn [ctx] (swap! calls conj :after) ctx)})
      (rf/reg-event :user/probe
        {:schema [:cat [:= :user/probe] :int]
         :interceptors [::probe]}
        (fn [{:keys [db]} _] (swap! calls conj :handler) {:db db}))
      (rf/dispatch-sync [:user/probe "not-an-int"])
      (is (= [:before :after] @calls)))))

(deftest sub-return-validation-fires-and-replaces-with-default
  (testing "a sub return failing its :schema reads as nil on the reactive and
            the pure path; the reactive trace carries the reaction's frame"
    (rf/make-frame {:id :test/sub-frame})
    (rf/reg-event :items/init (fn [_ _] {:db {:items ["a" "b" "c"]}}))
    (rf/reg-event :items/break (fn [{:keys [db]} _] {:db (assoc db :items [1 2 3])}))
    (rf/reg-sub :items
      {:schema [:vector :string]}
      (fn [db _] (:items db)))
    (rf/dispatch-sync [:items/init] {:frame :test/sub-frame})
    (is (= ["a" "b" "c"] (rf/subscribe-once [:items] {:frame :test/sub-frame})))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:items/break] {:frame :test/sub-frame})
      (is (nil? (rf/subscribe-once [:items] {:frame :test/sub-frame})))
      (is (= #{:test/sub-frame} (set (map (comp :frame :tags) (failures traces))))))
    (is (= [["a"] nil] [(rf/compute-sub [:items] {:items ["a"]})
                        (rf/compute-sub [:items] {:items [1]})]))))

(deftest fx-args-validation-fires-and-skips-only-the-offending-fx
  (testing "an fx whose args fail its :schema is skipped while a conforming
            sibling runs; the trace carries the in-flight frame"
    (let [calls (atom [])]
      (rf/make-frame {:id :test/fx-frame})
      (rf/reg-fx :my/notify
        {:schema [:map [:level :keyword]]}
        (fn [_ctx args] (swap! calls conj [:my/notify args])))
      (rf/reg-fx :my/log
        {:schema :string}
        (fn [_ctx args] (swap! calls conj [:my/log args])))
      (rf/reg-event :ui/announce
        (fn [_ _] {:fx [[:my/notify {:level "error"}]
                        [:my/log "anything"]]}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:ui/announce] {:frame :test/fx-frame})
        (is (= [[:my/log "anything"]] @calls))
        (is (= [:test/fx-frame] (map (comp :frame :tags) (failures traces))))
        (is (= 1 (count (filter #(= :rf.fx/handled (:operation %)) @traces)))
            ":rf.fx/handled fires only for the fx that ran")))))

(deftest recordable-cofx-value-invalid-fires-and-skips-handler
  (testing "a supplied recordable value failing its reg-cofx :schema stops the
            dispatch before the handler and traces :rf.error/cofx-value-invalid
            on the in-flight frame"
    (rf/make-frame {:id :test/cofx-frame})
    (rf/reg-cofx :app-version/v
      {:recordable? true :provided? true :schema :string})
    (let [calls (atom 0)]
      (rf/reg-event :cap/seed
        {:rf.cofx/requires [:app-version/v]}
        (fn [_ _] (swap! calls inc) {}))
      (with-trace-recorder! [traces]
        (try
          (rf/dispatch-sync [:cap/seed] {:frame   :test/cofx-frame
                                         :rf.cofx {:app-version/v 42}})
          (catch clojure.lang.ExceptionInfo _))
        (is (= [0 [[:no-recovery {:rf.cofx/id :app-version/v
                                  :failing-id :cap/seed
                                  :value      42
                                  :frame      :test/cofx-frame}]]]
               [@calls
                (for [v @traces
                      :when (= :rf.error/cofx-value-invalid (:operation v))]
                  [(:recovery v)
                   (select-keys (:tags v) [:rf.cofx/id :failing-id :value :frame])])]))))))

;; ---- the meta-bearing validators, called directly ---------------------------

(deftest meta-bearing-validators-direct-call-shape
  (testing "a failing direct call returns false and emits its surface's tags,
            with no :frame (runtime callers supply it)"
    (doseq [[validate! recovery tags]
            [[#(rf.schemas/validate-event! :user/strict [:user/strict "bad"]
                                           {:schema [:cat [:= :user/strict] :int]})
              :no-recovery
              {:where :event :event-id :user/strict :failing-id :user/strict
               :schema-id :user/strict
               :value [:user/strict "bad"] :received [:user/strict "bad"]}]
             [#(rf.schemas/validate-sub! :items [:items] [1 2] {:schema [:vector :string]})
              :replaced-with-default
              {:where :sub-return :rf.sub/id :items :failing-id :items :schema-id :items
               :rf.sub/query-v [:items] :value [1 2] :received [1 2]}]
             [#(rf.schemas/validate-fx! :my/fx :ev/origin {:x "bad"} {:schema [:map [:x :int]]})
              :skipped
              {:where :fx-args :rf.fx/id :my/fx :failing-id :my/fx :schema-id :my/fx
               :event-id :ev/origin
               :rf.fx/args {:x "bad"} :value {:x "bad"} :received {:x "bad"}}]]]
      (with-trace-recorder! [traces]
        (is (false? (validate!)))
        (is (= [[recovery tags]]
               (map (fn [v] [(:recovery v) (select-keys (:tags v) (conj (keys tags) :frame))])
                    (failures traces))))))))

(deftest fx-args-validation-redacts-when-sensitive
  (testing "a :sensitive? slot in the fx :schema scrubs every value-bearing
            slot, :rf.fx/args included, and keeps the structural ones"
    (with-trace-recorder! [traces]
      (rf.schemas/validate-fx! :my/secret :ev/origin {:token 42}
                               {:schema [:map [:token {:sensitive? true} :string]]})
      (is (= [[true {:value      :rf/redacted
                     :received   :rf/redacted
                     :rf.fx/args :rf/redacted
                     :explain    :rf/redacted
                     :rf.fx/id   :my/secret
                     :failing-id :my/secret
                     :where      :fx-args}]]
             (map (fn [v] [(:sensitive? v)
                           (select-keys (:tags v) [:value :received :rf.fx/args :explain
                                                   :rf.fx/id :failing-id :where])])
                  (failures traces)))))))

;; Each validate-*! sits behind its own debug gate, so each is pinned directly.
;; A no-trace assertion could not pin one: `emit-error!` is debug-gated too.
(deftest validators-elide-when-debug-disabled
  (testing "under debug-enabled? false every dev-time validator returns true
            on a non-conforming value without consulting the validator"
    (let [consulted (atom 0)]
      (rf.schemas/set-schema-fns! {:validate (fn [_ _] (swap! consulted inc) false)})
      (rf/reg-app-schema [:n] [:int])
      (with-redefs [rf.interop/debug-enabled? false]
        (is (= [true true true true]
               [(rf.schemas/validate-app-schema! {:n "boom"} :some/handler)
                (rf.schemas/validate-event! :user/strict [:user/strict "not-an-int"]
                                            {:schema [:cat [:= :user/strict] :int]})
                (rf.schemas/validate-sub! :items [:items] [1 2] {:schema [:vector :string]})
                (rf.schemas/validate-fx! :strict/fx :strict/trigger {:x "not-an-int"}
                                         {:schema [:map [:x :int]]})])))
      (is (zero? @consulted)))))

;; ---- app-schemas-digest ------------------------------------------------------

(deftest app-schemas-digest-frame-isolated
  (testing "a frame's digest covers only its own schemas; a frame with none
            has the empty-set digest"
    (rf/make-frame {:id :test/a})
    (rf/make-frame {:id :test/b})
    (rf/reg-app-schema [:user] {:frame :test/a} [:map [:id :uuid]])
    (let [empty-set-digest (:expected rf.schemas.digest-parity-fixtures/empty-set)]
      (is (= empty-set-digest (rf.schemas/app-schemas-digest {:frame :test/b})))
      (is (not= empty-set-digest (rf.schemas/app-schemas-digest {:frame :test/a}))))))

;; ---- the validator-install seam ------------------------------------------------

(deftest nil-validator-disables-validation-on-every-surface
  (testing "an installed nil :validate passes the app-db walk and the
            meta-bearing validators without inspecting the schema"
    (rf.schemas/set-schema-fns! {:validate nil})
    (rf/reg-app-schema [:n] [:int])
    (is (= [true true]
           [(rf.schemas/validate-app-schema! {:n "bad"} :test/h)
            (rf.schemas/validate-event! :ev/x [:ev/x "bad"] {:schema [:cat [:= :ev/x] :int]})]))))

(deftest set-schema-fns-bundle-installs-both-fns
  (testing "an installed validator decides, and an installed explainer runs
            only on failure, its output riding the trace's :explain"
    (let [explain-calls (atom 0)]
      (rf.schemas/set-schema-fns!
        {:validate (fn [_s v] (= v :good))
         :explain  (fn [s v] (swap! explain-calls inc) {:my-explanation [s v]})})
      (rf/reg-app-schema [:k] :keyword)
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:k :good} :h/pass)
        (rf.schemas/validate-app-schema! {:k :nope} :h/fail)
        (is (= [1 [{:my-explanation [:keyword :nope]}]]
               [@explain-calls (map (comp :explain :tags) (failures traces))]))))))

(deftest set-schema-fns-installs-only-the-keys-it-carries
  (testing "an omitted key keeps its registration and an explicit nil clears
            it; the install returns what `schema-fns` reads; installing
            `default-schema-fns`, or a captured bundle, reinstates it"
    (let [v-fn (fn [_ _] true)
          p-fn (fn [_] "::P::")]
      (rf.schemas/set-schema-fns! {:validate v-fn :print p-fn})
      (let [installed (rf.schemas/set-schema-fns! {:explain nil})]
        (is (= {:validate v-fn :explain nil :print p-fn} installed (rf.schemas/schema-fns)))
        (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
        (is (= rf.schemas/default-schema-fns (rf.schemas/schema-fns)))
        ;; `using-default-validator?` is an identity check, so the defaults
        ;; must be the very fn objects the atoms were seeded with.
        (is (true? (rf.schemas.validator/using-default-validator?)))
        (is (= installed (rf.schemas/set-schema-fns! installed)))))))

(deftest validate-with-registered-fn-bypasses-debug-gate
  (testing "the boundary seam routes through the registered validator even
            with debug-enabled? false"
    (rf.schemas/set-schema-fns! {:validate (fn [_ v] (= v :good))})
    (with-redefs [rf.interop/debug-enabled? false]
      (is (= [true false]
             [(rf.schemas/validate-with-registered-fn :keyword :good)
              (rf.schemas/validate-with-registered-fn :keyword :bad)])))))

;; ---- the `:boundary? true` production arm ------------------------------------
;;
;; `rf.spec/dev-mode?` false takes the router's production arm while
;; `debug-enabled?` stays true, so the trace surface still fires and the
;; production arm's emission is observable on the JVM.

(deftest boundary-flag-emits-failure-trace-with-source-tag
  (testing "the production arm refuses with the dev :where :event trace shape
            plus :source :boundary"
    (rf/reg-event :api/strict
      {:schema    [:cat [:= :api/strict] :int]
       :boundary? true}
      (fn [_ _] {}))
    (with-trace-recorder! [traces]
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (rf/dispatch-sync [:api/strict "not-an-int"]))
      (is (= [[:no-recovery {:where      :event
                             :event-id   :api/strict
                             :failing-id :api/strict
                             :schema-id  :api/strict
                             :source     :boundary
                             :received   [:api/strict "not-an-int"]
                             :value      [:api/strict "not-an-int"]}]]
             (map (fn [v] [(:recovery v)
                           (select-keys (:tags v) [:where :event-id :failing-id :schema-id
                                                   :source :received :value])])
                  (failures traces)))))))

(deftest boundary-flag-is-a-no-op-for-unflagged-handlers
  (testing "a production build does not check an unflagged handler's :schema"
    (let [calls (atom 0)]
      (rf/reg-event :api/unflagged
        {:schema [:cat [:= :api/unflagged] :int]}
        (fn [_ _] (swap! calls inc) {}))
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (rf/dispatch-sync [:api/unflagged "not-an-int"]))
      (is (= 1 @calls)))))

(deftest boundary-flag-honours-custom-validator
  (testing "the production arm calls the registered validator exactly once per
            dispatch and runs the handler only when it passes"
    (let [validator-calls (atom 0)
          handler-calls   (atom 0)]
      (rf/reg-event :api/custom
        {:schema    :rf/any
         :boundary? true}
        (fn [_ _] (swap! handler-calls inc) {}))
      (rf.schemas/set-schema-fns!
        {:validate (fn [_schema value]
                     (swap! validator-calls inc)
                     (= value [:api/custom :good]))})
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (rf/dispatch-sync [:api/custom :good])
        (is (= [1 1] [@validator-calls @handler-calls]))
        (rf/dispatch-sync [:api/custom :bad])
        (is (= [2 1] [@validator-calls @handler-calls]))))))

(deftest boundary-flag-noop-in-dev-mode
  (testing "in a dev build the ordinary step-1 check refuses the event and the
            production arm emits nothing of its own"
    (let [calls (atom 0)]
      (rf/reg-event :api/dev
        {:schema    [:cat [:= :api/dev] :int]
         :boundary? true}
        (fn [_ _] (swap! calls inc) {}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:api/dev "not-an-int"])
        (is (= [0 [nil]] [@calls (map (comp :source :tags) (failures traces))]))))))

(deftest dev-and-prod-agree-under-an-event-transforming-interceptor
  (testing "both builds check the ORIGINAL dispatched vector at step 1, not the
            one an interceptor rewrote, so a conforming event runs in both"
    (rf/reg-interceptor :api/rewrites-event
      {:before (fn [ctx]
                 (assoc-in ctx [:coeffects :event] [:api/transformed 999]))})
    (let [calls (atom 0)]
      (rf/reg-event :api/transform-probe
        {:schema       [:cat [:= :api/transform-probe] :int]
         :boundary?    true
         :interceptors [:api/rewrites-event]}
        (fn [_ _] (swap! calls inc) {}))
      (rf/dispatch-sync [:api/transform-probe 7])
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (rf/dispatch-sync [:api/transform-probe 7]))
      (is (= 2 @calls)))))

;; ---- rf/reg-app-schemas (plural) ---------------------------------------------

(deftest reg-app-schemas-returns-paths-registered
  (testing "returns the registered paths, and [] for the documented empty batch"
    (is (= #{[:a] [:b]} (set (rf/reg-app-schemas {[:a] [:int] [:b] [:int]}))))
    (is (= [] (rf/reg-app-schemas {})))))

(deftest reg-app-schemas-rejects-non-map-batches
  (testing "a nil or non-map batch throws instead of registering nothing, which
            would be indistinguishable from the empty-map no-op"
    (doseq [bad [nil []]]
      (is (= :rf.error/app-schemas-bad-batch
             (try (rf/reg-app-schemas bad) nil
                  (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
          (pr-str bad)))))
