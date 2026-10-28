(ns re-frame.test-support-test
  "The public `re-frame.test-support` helpers (Spec 008 §Built-in test-runner
  namespace): `assert-path-equals`, `poll-until`, and the lifecycle cascades of
  `make-reset-runtime-fixture` and `destroy-frame!` — every contributed reset
  runs the documented number of times, so a dropped reset breaks here at the
  seam rather than as long-range cross-test pollution."
  (:require [clojure.test :refer [deftest is testing use-fixtures report]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.flows :as rf.flows]
            [re-frame.interop :as rf.interop]
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
    (is (= [:pass :fail :pass :fail]
           (record-reports
             (fn []
               (rf.test-support/assert-path-equals [:n] 5)
               (rf.test-support/assert-path-equals [:n] nil)
               (rf.test-support/assert-path-equals [:n] 5 {:frame f})
               (rf.test-support/assert-path-equals [:n] nil {:frame f})))))))

;; ---- make-reset-runtime-fixture reset cascade -----------------------------

(deftest make-reset-runtime-fixture-runs-every-reset-the-documented-number-of-times
  ;; Every contribution's :test-reset runs once. The flows memo reset runs
  ;; twice — pre-test and in the `finally` — so a failing test leaves no
  ;; residue for the next; the chained adapter reset runs once.
  (let [snapshot  @rf.late-bind/hooks
        saved     @rf.frame/lifecycle-contributions
        counts    (atom {})
        count!    (fn [k] (fn [& _] (swap! counts update k (fnil inc 0)) nil))
        resetters (into #{} (keep #(when (:test-reset %) (:id %))) saved)]
    (try
      (doseq [id resetters]
        (rf.frame/contribute-lifecycle! {:id id :test-reset (count! id)}))
      (doseq [k [:flows/reset-flows! :adapter/clear-warn-once-caches!]]
        (rf.late-bind/set-fn! k (count! k)))
      ((fixture) (fn [] :ran))
      (is (every? resetters (cond-> [:fx :machines :routing]
                              rf.interop/debug-enabled? (conj :epoch)))
          "FIXTURE — the artefacts this lane loads contribute their resets;
           epoch contributes only in a development build")
      (is (= (merge (zipmap resetters (repeat 1))
                    {:flows/reset-flows! 2 :adapter/clear-warn-once-caches! 1})
             @counts))
      (finally
        (reset! rf.late-bind/hooks snapshot)
        (reset! rf.frame/lifecycle-contributions saved)))))

(deftest make-reset-runtime-fixture-rolls-back-a-schema-registered-in-the-body
  (let [present? #(some? (rf.schemas/app-schema-meta {:frame :rf/default :path [:counter]}))
        in-body  (atom :unset)]
    ((fixture) (fn []
                 (rf/reg-app-schema [:counter] :int)
                 (reset! in-body (present?))))
    (is (= [true false] [@in-body (present?)])
        "a schema is registrar state: present in the body that registered it,
         rolled back with the rest of the table afterwards")))

(deftest make-reset-runtime-fixture-clears-load-time-schemas-for-the-body
  (rf/reg-app-schema [:loaded] :int)
  (let [present? #(some? (rf.schemas/app-schema-meta {:frame :rf/default :path [:loaded]}))
        built    (fixture)
        in-body  (atom :unset)]
    (built (fn [] (reset! in-body (present?))))
    (is (= [false true] [@in-body (present?)])
        "a schema in the fixture's baseline is cleared for the body and back
         afterwards, so a test checks only the schemas it registers")))

(deftest make-reset-runtime-fixture-baseline-is-the-table-when-it-is-built
  ;; Spec 008 §Fixture-granularity ladder: a suite registers its top-level
  ;; state above its `use-fixtures` form, because that is when the fixture
  ;; is built and its baseline taken.
  (rf/reg-event :test-support/above-the-fixture (fn [_ _] {}))
  (let [built    (fixture)
        present? #(some? (rf/handler-meta {:kind :event :id %}))
        in-body  (atom :unset)]
    (rf/reg-event :test-support/below-the-fixture (fn [_ _] {}))
    (built (fn [] (reset! in-body (mapv present? [:test-support/above-the-fixture
                                                  :test-support/below-the-fixture]))))
    (is (= [true false] @in-body)
        "a registration made after the fixture was built is outside its baseline,
         so the reset before the test removes it")))

(deftest make-reset-runtime-fixture-pre-dispose-fires-before-adapter-dispose
  ;; the pre-dispose flows reset runs before the post-dispose contributed
  ;; resets; the finally-block flows reset is third
  (let [snapshot @rf.late-bind/hooks
        saved    @rf.frame/lifecycle-contributions
        order    (atom [])]
    (try
      (rf.late-bind/set-fn! :flows/reset-flows! (fn [] (swap! order conj :pre)))
      (rf.frame/contribute-lifecycle! {:id         ::post-marker
                                       :test-reset (fn [] (swap! order conj :post))})
      ((fixture) (fn [] :ran))
      (is (= [:pre :post :pre] @order))
      (finally
        (reset! rf.late-bind/hooks snapshot)
        (reset! rf.frame/lifecycle-contributions saved)))))

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
                                                  #(swap! a-seen conj [:listener (:rf.error/id %)]))
          (rf/register-observability-sink! ::a-sink #(swap! a-seen conj [:sink (:rf.error/id %)]))
          (rf/configure! {:observability {:errors [{:sink ::a-sink}]}})
          (rf.trace/set-frame-no-emit! ::tool true)))
      (fixture
        (fn []
          (testing "the process default and the trace-disabled frame are gone"
            (is (nil? (rf.observability/current-observability-config)))
            (is (not (rf.trace/frame-trace-disabled? ::tool))))
          (rf.error-emit/register-error-listener! ::b-listener
                                                  #(swap! b-seen conj [:listener (:rf.error/id %)]))
          (rf/register-observability-sink! ::b-sink #(swap! b-seen conj [:sink (:rf.error/id %)]))
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

(deftest make-reset-runtime-fixture-keeps-the-ssr-error-capture
  ;; `re-frame.ssr` captures errors through a late-bind hook, not an error
  ;; listener, so the reset's error-listener clear has nothing of SSR's to
  ;; take away and the next test still projects.
  (let [observe (fn []
                  {:hook?         (some? (rf.late-bind/get-fn :ssr/capture-error-record))
                   :ssr-listener? (boolean
                                    (some #(and (keyword? %) (= "re-frame.ssr" (namespace %)))
                                          (keys @@#'rf.error-emit/listeners)))})
        in-body (atom :unset)]
    (is (= {:hook? true :ssr-listener? false} (observe))
        "control: re-frame.ssr is loaded, and registers no error listener")
    ((fixture) (fn [] (reset! in-body (observe))))
    (is (= {:hook? true :ssr-listener? false} @in-body))))

;; ---- `:init-fn` runs under the body's ambient frame -----------------------
;;
;; App setup thunks run frame-scoped ops (a bare `dispatch`); run frameless
;; they raise :rf.error/no-frame-context.
;; Each test unbinds the outer fixture's ambient frame, as under cljs.test.

(deftest make-reset-runtime-fixture-runs-init-fn-under-ambient-frame
  (binding [rf.frame/*current-frame* nil]
    (let [seen (atom [])
          run  (fn [opts init-fn]
                 ((rf.test-support/make-reset-runtime-fixture
                    (merge {:adapter rf.substrate.plain-atom/adapter :init-fn init-fn} opts))
                  (fn [] :ran)))]
      (run {} #(swap! seen conj (rf.frame/current-frame)))
      (run {:ambient-frame nil} #(swap! seen conj (rf.frame/current-frame)))
      (is (= [:rf/default nil] @seen)
          ":init-fn ran under the ambient :rf/default; `:ambient-frame nil` keeps it frameless"))))

;; ---- snapshot-registrar / restore-registrar! -------------------------------
;;
;; A frame made without `:images` resolves the registration table live, so the
;; bracket has to roll back what that frame runs, not only what the ambient
;; frame resolves.

(deftest restore-registrar-drops-a-registration-a-frame-without-images-would-run
  (let [ran  (atom false)
        snap (rf.test-support/snapshot-registrar)]
    (rf/reg-event :test-support/bracketed (fn [_ _] (reset! ran true) {}))
    (rf.test-support/restore-registrar! snap)
    (let [f (rf/make-frame {})]
      (try
        (try (rf/dispatch-sync [:test-support/bracketed] {:frame f})
             (catch Exception _ nil))
        (is (false? @ran)
            "the handler registered inside the bracket no longer runs once the bracket is restored")
        (finally (rf/destroy-frame! f))))))

;; ---- destroy-frame! contribution cascade ----------------------------------

(deftest destroy-frame-calls-each-contribution-with-the-frame-id-and-phase
  ;; each member runs once per phase it joins, with the destroyed id; epoch's
  ;; after-dissoc publication runs once the frame has left the registry; an
  ;; immediate destroy has no event boundary, so it carries no halt evidence
  (let [saved   @rf.frame/lifecycle-contributions
        calls   (atom {})
        record! (fn [k v] (swap! calls update k (fnil conj []) v))
        fid     :t/arg-target]
    (try
      (doseq [{:keys [id on-frame-destroyed]} saved
              :when (#{:ssr :machines :epoch} id)]
        (rf.frame/contribute-lifecycle!
          {:id id
           :on-frame-destroyed
           (fn [frame-id phase]
             (record! id [frame-id phase rf.frame/*destroy-halt*])
             (when-let [after (on-frame-destroyed frame-id phase)]
               (fn []
                 (record! id [frame-id :after-dissoc (contains? @rf.frame/frames frame-id)])
                 (after))))}))
      (rf/make-frame {:id fid})
      (rf.frame/destroy-frame! fid)
      (is (= (cond-> {:ssr      [[fid :post-dead nil]]
                      :machines [[fid :pre-dead nil] [fid :post-dead nil]]}
               rf.interop/debug-enabled?
               (assoc :epoch [[fid :post-dead nil] [fid :after-dissoc false]]))
             @calls)
          "epoch contributes only in a development build")
      (finally
        (reset! rf.frame/lifecycle-contributions saved)))))

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
