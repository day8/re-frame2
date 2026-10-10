(ns re-frame.test-support-test
  "The public `re-frame.test-support` helpers (Spec 008 §Built-in test-runner
  namespace): `assert-path-equals`, `poll-until`, and the hook cascades of
  `make-reset-runtime-fixture` and `destroy-frame!` — every late-bind reset
  hook fires the documented number of times, so a dropped row breaks here at
  the seam rather than as long-range cross-test pollution."
  (:require [clojure.test :refer [deftest is testing use-fixtures report]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.flows :as rf.flows]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.observability :as rf.observability]
            [re-frame.schemas :as rf.schemas]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.test-support :as rf.test-support]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-reports
  "The `:type`s `clojure.test/report` receives while `body-fn` runs."
  [body-fn]
  (let [recorded (atom [])]
    (with-redefs [report (fn [m] (swap! recorded conj (:type m)))]
      (body-fn))
    @recorded))

(defn- register-counter-handlers! []
  (rf/reg-event :counter/init (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :counter/add (fn [{:keys [db]} [_ amt]] {:db (update db :n + amt)})))

(defn- fixture [] (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- assert-path-equals ---------------------------------------------------

(deftest assert-path-equals-frame-opt
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (rf/make-frame {:id :test-support/assert-frame :initial-events [[:counter/init]]})
  (rf/dispatch-sync [:counter/add 3] {:frame :test-support/assert-frame})
  (is (= [:pass :pass]
         (record-reports
           (fn []
             (rf.test-support/assert-path-equals [:n] 3 {:frame :test-support/assert-frame})
             (rf.test-support/assert-path-equals [:n] 0 {:frame :rf/default}))))
      ":frame selects which frame's app-db is asserted against"))

(deftest assert-path-equals-inside-with-new-frame
  ;; a frame OBJECT — the ambient scope `with-new-frame` binds, or an explicit
  ;; {:frame <object>} — resolves to that frame's app-db; nil is the mismatch a
  ;; mis-resolution would silently accept
  (register-counter-handlers!)
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:counter/init] [:counter/add 5]]})]
    (is (= [:pass :fail :fail :pass :fail]
           (record-reports
             (fn []
               (rf.test-support/assert-path-equals [:n] 5)
               (rf.test-support/assert-path-equals [:n] 6)
               (rf.test-support/assert-path-equals [:n] nil)
               (rf.test-support/assert-path-equals [:n] 5 {:frame f})
               (rf.test-support/assert-path-equals [:n] nil {:frame f})))))))

;; ---- make-reset-runtime-fixture hook cascade ------------------------------

(def ^:private reset-hook-expected-counts
  "Per-fixture-invocation call count for every reset-hook-table row.
  `:flows/reset-flows!` fires twice — pre-test and in the `finally` — so a
  failing test leaves no residue for the next."
  {:flows/reset-flows!                2
   :schemas/clear-by-frame!           1
   :machines/reset-timers!            1
   :fx/reset-dispatch-later-timers!   1
   :machines/reset-spawn-order!       1
   :routing/reset-counters!           1
   :routing/reset-nav-counters!       1
   :routing/reset-url-claims!         1
   :routing/reset-url-listener!       1
   :resources/reset-resources!        1
   :http/clear-all-in-flight!         1
   :http/clear-all-http-interceptors! 1
   :epoch/clear-history!              1
   :epoch/clear-epoch-listeners!      1
   :epoch/reset-config!               1
   :ssr/reinstall-error-projection!   1
   :adapter/clear-warn-once-caches!   1})

(deftest make-reset-runtime-fixture-fires-every-hook-the-documented-number-of-times
  (let [snapshot     @rf.late-bind/hooks
        call-counts  (atom (zipmap (keys reset-hook-expected-counts) (repeat 0)))
        orig-restore (rf.late-bind/get-fn :schemas/restore-by-frame!)]
    (try
      (doseq [k (keys reset-hook-expected-counts)]
        (rf.late-bind/set-fn! k (fn [& _] (swap! call-counts update k inc) nil)))
      ;; the schemas snapshot/restore pair is separate plumbing, stubbed so the
      ;; body's finally does not depend on the production restore
      (rf.late-bind/set-fn! :schemas/restore-by-frame! (fn [_snap] nil))
      (rf.late-bind/set-fn! :schemas/snapshot-by-frame (fn [] nil))
      ((fixture) (fn [] :ran))
      (is (= (set (map :hook @#'rf.test-support/reset-hook-table))
             (set (keys reset-hook-expected-counts)))
          "the expected counts name every reset-hook-table row and no other key")
      (is (= reset-hook-expected-counts @call-counts))
      (finally
        (reset! rf.late-bind/hooks snapshot)
        (when orig-restore
          (rf.late-bind/set-fn! :schemas/restore-by-frame! orig-restore))))))

(deftest make-reset-runtime-fixture-resets-per-frame-schemas-with-no-option
  (rf/reg-app-schema [:counter] :int)
  (let [present? #(some? (rf.schemas/app-schema-meta {:frame :rf/default :path [:counter]}))
        in-body  (atom :unset)
        before   (present?)]
    ((fixture) (fn [] (reset! in-body (present?))))
    (is (= [true false true] [before @in-body (present?)])
        "absent inside the body, restored afterwards")))

(deftest make-reset-runtime-fixture-pre-dispose-fires-before-adapter-dispose
  ;; :pre-dispose (flows) runs before :post-dispose (epoch); the finally-block
  ;; flows reset is third
  (let [snapshot @rf.late-bind/hooks
        order    (atom [])]
    (try
      (rf.late-bind/set-fn! :flows/reset-flows! (fn [] (swap! order conj :pre)))
      (rf.late-bind/set-fn! :epoch/clear-history! (fn [] (swap! order conj :post)))
      ((fixture) (fn [] :ran))
      (is (= [:pre :post :pre] @order))
      (finally
        (reset! rf.late-bind/hooks snapshot)))))

;; ---- make-reset-runtime-fixture observer isolation ------------------------

(deftest make-reset-runtime-fixture-drops-the-observers-a-previous-test-left
  ;; Two test bodies, in a fixed order, under one fixture. A leaves an error
  ;; listener, a sink, a process-default policy and a trace-disabled frame
  ;; behind; B emits an error into a frame whose policy names A's sink. B's own
  ;; listener and sink are the control proving the error reached both routes.
  (let [fixture (fixture)
        a-seen  (atom [])
        b-seen  (atom [])]
    (try
      (fixture
        (fn []
          (rf.error-emit/register-error-listener! ::a-listener
                                                  #(swap! a-seen conj [:listener (:error %)]))
          (rf/register-observability-sink! ::a-sink #(swap! a-seen conj [:sink (:error %)]))
          (rf/configure! {:observability {:errors [{:sink ::a-sink}]}})
          (rf.trace/set-frame-no-emit! ::tool true)))
      (fixture
        (fn []
          (testing "the process default and the trace-disabled frame are gone"
            (is (nil? (rf.observability/current-observability-config)))
            (is (not (rf.trace/frame-trace-disabled? ::tool))))
          (rf.error-emit/register-error-listener! ::b-listener
                                                  #(swap! b-seen conj [:listener (:error %)]))
          (rf/register-observability-sink! ::b-sink #(swap! b-seen conj [:sink (:error %)]))
          (rf/make-frame {:id            ::app
                          :observability {:errors [{:sink ::a-sink} {:sink ::b-sink}]}})
          (rf/reg-event ::boom (fn [_ _] (throw (ex-info "boom" {}))))
          (rf/dispatch-sync [::boom] {:frame ::app})))
      (is (= [[:listener :rf.error/handler-exception] [:sink :rf.error/handler-exception]]
             @b-seen)
          "control: test B's error reached its own listener and sink")
      (is (= [] @a-seen) "neither of test A's callbacks fired in test B")
      (finally
        (run! rf.error-emit/unregister-error-listener! [::a-listener ::b-listener])
        (run! rf.observability/unregister-observability-sink! [::a-sink ::b-sink])
        (rf.observability/clear-observability-default!)
        (rf.trace/clear-frame-no-emit!)))))

(deftest make-reset-runtime-fixture-reinstates-the-ssr-error-projection
  ;; `re-frame.ssr` installs its error-projection listener at load, and the
  ;; reset's error-listener clear must not take it away from the next test.
  (let [present? #(contains? @@#'rf.error-emit/listeners :re-frame.ssr/error-projection)
        in-body  (atom :unset)]
    (is (present?) "control: the listener is installed before the reset")
    ((fixture) (fn [] (reset! in-body (present?))))
    (is (true? @in-body))))

;; ---- `:init-fn` runs under the body's ambient frame -----------------------
;;
;; App setup thunks run context-required frame-local ops (`reg-app-schema`,
;; a bare `dispatch`); run frameless they raise :rf.error/no-frame-context.
;; Each test unbinds the outer fixture's ambient frame, as under cljs.test.

(deftest make-reset-runtime-fixture-runs-init-fn-under-ambient-frame
  (binding [rf.frame/*current-frame* nil]
    (let [seen-frame (atom :unset)]
      ((rf.test-support/make-reset-runtime-fixture
         {:adapter rf.substrate.plain-atom/adapter
          :init-fn (fn []
                     (reset! seen-frame (rf.frame/current-frame))
                     (rf/reg-app-schema [:counter] :int))})
       (fn [] :ran))
      (is (= :rf/default @seen-frame)
          "the bare reg-app-schema did not throw, under the ambient :rf/default"))))

(deftest make-reset-runtime-fixture-init-fn-frameless-when-ambient-opted-out
  (binding [rf.frame/*current-frame* nil]
    (let [seen-frame (atom :unset)]
      ((rf.test-support/make-reset-runtime-fixture
         {:adapter       rf.substrate.plain-atom/adapter
          :ambient-frame nil
          :init-fn       (fn [] (reset! seen-frame (rf.frame/current-frame)))})
       (fn [] :ran))
      (is (nil? @seen-frame) "`:ambient-frame nil` keeps :init-fn frameless"))))

;; ---- destroy-frame! hook cascade ------------------------------------------

(deftest destroy-frame-cleanup-hooks-receive-frame-id
  ;; each cleanup hook fires exactly once with the destroyed id; the epoch
  ;; teardown spans the PRE-dissoc snapshot hook (id fs-before fs-after
  ;; committed-at) and the POST-dissoc hook (id owner-token evidence), and the
  ;; post hook runs after the snapshot was captured
  (let [snapshot      @rf.late-bind/hooks
        calls         (atom {})
        record!       (fn [k v] (swap! calls update k (fnil conj []) v))
        original-snap (rf.late-bind/get-fn :epoch/snapshot-frame-destroyed)
        fid           :t/arg-target]
    (try
      (doseq [k [:ssr/on-frame-destroyed :machines/on-frame-destroyed!]]
        (rf.late-bind/set-fn! k (fn [id] (record! k id))))
      (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed
        (fn [id fs-before fs-after committed-at]
          (record! :epoch/snapshot-frame-destroyed [id fs-before fs-after committed-at])
          (when original-snap (original-snap id fs-before fs-after committed-at))))
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [id owner-token _evidence]
          (record! :epoch/on-frame-destroyed
                   [id (some? owner-token) (contains? @calls :epoch/snapshot-frame-destroyed)])))
      (rf/make-frame {:id fid})
      (rf.frame/destroy-frame! fid)
      ;; an out-of-run destroy has no pre-run snapshot and no causal token, and
      ;; fs-after is the whole two-partition frame-state (EP-0001)
      (is (= {:ssr/on-frame-destroyed         [fid]
              :machines/on-frame-destroyed!   [fid]
              :epoch/snapshot-frame-destroyed [[fid nil {:rf.db/app {} :rf.db/runtime {}} nil]]
              :epoch/on-frame-destroyed       [[fid true true]]}
             @calls))
      (finally
        (reset! rf.late-bind/hooks snapshot)))))

;; ---- poll-until (JVM / CLJS parity) ---------------------------------------

(deftest poll-until-swallows-a-throwing-pred-jvm
  ;; a throwing pred is a falsy probe: keep polling to the deadline, and on
  ;; timeout surface the poll-until discriminator, not the pred's own throw
  (let [calls (atom 0)]
    (is (= :ok (rf.test-support/poll-until
                 (fn [] (if (< (swap! calls inc) 3) (throw (ex-info "transient" {})) :ok))
                 {:timeout-ms 2000 :interval-ms 1}))))
  (is (= :rf.error/poll-until-timeout
         (try (rf.test-support/poll-until (fn [] (throw (ex-info "always" {:boom true})))
                                          {:timeout-ms 40 :interval-ms 5})
              nil
              (catch clojure.lang.ExceptionInfo ex (:rf.error/id (ex-data ex)))))))
