(ns re-frame.frame-teardown-report-cljs-test
  "The frame-teardown report (Spec 009 §Channel-promotion catalogue). When
  late-bound cleanup hooks or guarded teardown steps throw during destroy, the
  runtime emits ONE always-on `:rf.error/frame-teardown-failed` record carrying
  every failure in `:hook-failures`, not one record per hook. The report
  flushes even when teardown aborts part-way, carries no raw app values, keeps
  nested destroys' failures apart, and a throwing machine-teardown step cannot
  leave the frame live and half torn down. In dev each failure also emits a
  per-hook diagnostic row.

  The diagnostic test is `^:requires-debug`; every other test reads the
  always-on error channel, which is live in the prod gate."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!))}))

(defn- with-hooks*
  "Install each `hook-key -> fn` of `hook-map` for the extent of `f`,
  restoring the prior bindings after."
  [hook-map f]
  (let [originals (into {} (map (fn [k] [k (rf.late-bind/get-fn k)]) (keys hook-map)))]
    (try
      (doseq [[k v] hook-map] (rf.late-bind/set-fn! k v))
      (f)
      (finally
        (doseq [[k orig] originals] (rf.late-bind/set-fn! k orig))))))

(defn- throwing-hook
  "A cleanup hook that always throws, at any arity."
  [label]
  (fn [& _] (throw (ex-info (str "teardown hook threw: " label) {:hook label}))))

(deftest n-hook-failures-yield-one-report-with-n-entries
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/n-failures :doc "three hooks will throw"})
    (with-hooks*
      {:ssr/on-frame-destroyed           (throwing-hook :ssr)
       :schemas/on-frame-destroyed!      (throwing-hook :schemas)
       :flows/teardown-on-frame-destroy! (throwing-hook :flows)}
      (fn [] (rf/destroy-frame! :teardown/n-failures)))
    (let [reports (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)
          r       (first reports)]
      (is (= [1
              :teardown/n-failures
              [:flows/teardown-on-frame-destroy! :schemas/on-frame-destroyed! :ssr/on-frame-destroyed]
              #{:safe-call-hook!}
              true
              :ignored
              true
              true]
             [(count reports)
              (:frame r)
              (sort (map :hook (:hook-failures r)))
              (set (map :where (:hook-failures r)))
              (every? #(some? (:exception %)) (:hook-failures r))
              (:recovery r)
              (string? (:reason r))
              (number? (:time r))])
          "one report for the destroy, with one entry per failed hook"))))

(deftest clean-destroy-emits-no-report
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/clean :doc "no hooks throw"})
    (rf/destroy-frame! :teardown/clean)
    (is (empty? (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)))))

(deftest partial-teardown-abort-still-flushes-collected-entries
  ;; two hooks fail, then a later non-hook step throws out of destroy-frame!;
  ;; the finally-shaped flush still ships the entries gathered so far
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/abort :doc "aborts mid-teardown"})
    (with-hooks*
      {:ssr/on-frame-destroyed      (throwing-hook :ssr)
       :schemas/on-frame-destroyed! (throwing-hook :schemas)}
      (fn []
        (with-redefs [rf.frame/emit-frame-destroyed-trace!
                      (fn [_id]
                        (throw (ex-info "mid-teardown collapse" {})))]
          (is (thrown? #?(:clj Throwable :cljs js/Error)
                       (rf/destroy-frame! :teardown/abort))))))
    (let [reports (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)]
      (is (= [1 [:schemas/on-frame-destroyed! :ssr/on-frame-destroyed]]
             [(count reports) (sort (map :hook (:hook-failures (first reports))))])))))

(deftest ^:requires-debug dev-per-hook-diagnostic-rows-still-emit
  (when rf.interop/debug-enabled?
    (let [traces (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
      (rf/make-frame {:id :teardown/diagnostic :doc "two hooks throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          (try
            (rf/destroy-frame! :teardown/diagnostic)
            (finally (rf/unregister-listener! :trace ::rec)))))
      (is (= [[:teardown/diagnostic :schemas/on-frame-destroyed!]
              [:teardown/diagnostic :ssr/on-frame-destroyed]]
             (sort (keep #(when (= :rf.warning/teardown-hook-exception (:operation %))
                            [(get-in % [:tags :frame]) (get-in % [:tags :hook])])
                         @traces)))
          "one frame-attributed diagnostic row per failed hook"))))

(deftest report-record-carries-no-raw-values
  ;; the always-on axis is not privacy-gated, so the record carries no event
  ;; vector, app-db slice or other raw payload
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/no-raw :doc "two hooks throw"})
    (with-hooks*
      {:ssr/on-frame-destroyed      (throwing-hook :ssr)
       :schemas/on-frame-destroyed! (throwing-hook :schemas)}
      (fn [] (rf/destroy-frame! :teardown/no-raw)))
    (let [r (first (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen))]
      (is (= [#{:error :frame :hook-failures :recovery :reason :time}
              #{#{:hook :exception :where}}]
             [(set (keys r)) (set (map (comp set keys) (:hook-failures r)))])))))

(deftest step2-teardown-throw-is-accumulated-frame-fully-torn-down
  ;; Unguarded, a throwing machine-teardown consumer would escape destroy-frame!
  ;; before :destroyed? flipped, leaving the frame live with :on-destroy already
  ;; run, and a second destroy would re-run the recipe.
  (let [on-destroy-runs (atom 0)
        reports         (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! reports conj record)))
    (rf/reg-event :teardown/count-on-destroy
      (fn [{:keys [db]} _]
        (swap! on-destroy-runs inc)
        {:db db}))
    (rf/make-frame {:id         :teardown/step2 :doc "machine-teardown consumer throws"
                    :on-destroy [:teardown/count-on-destroy]})
    (with-hooks*
      {:machines/teardown-on-frame-destroy! (throwing-hook :machines-teardown)}
      (fn []
        (is (nil? (try (rf/destroy-frame! :teardown/step2) nil
                       (catch #?(:clj Throwable :cljs :default) e e)))
            "the throw did not escape destroy-frame!")))
    (let [reps (filter #(= :rf.error/frame-teardown-failed (:error %)) @reports)]
      ;; fully torn down, :on-destroy ran once, and the failure is one entry of
      ;; the one report, recorded at the direct-step boundary
      (is (= [nil 1 1 [:safe-teardown-step!]]
             [(rf.frame/frame :teardown/step2)
              @on-destroy-runs
              (count reps)
              (keep #(when (= :frame/notify-machine-destruction! (:hook %)) (:where %))
                    (:hook-failures (first reps)))])))
    (try (rf/destroy-frame! :teardown/step2)
         (catch #?(:clj Throwable :cljs :default) _ nil))
    (is (= 1 @on-destroy-runs) "a second destroy does not re-fire :on-destroy")))

(deftest nested-destroy-accumulators-are-isolated
  ;; A's :on-destroy destroys B, nested inside A's teardown. Every installed hook
  ;; fails in both extents, so a shared accumulator would show six in one report.
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/inner-B :doc "inner frame, destroyed nested"})
    (rf/reg-event :teardown/destroy-inner
      (fn [{:keys [db]} _]
        (rf/destroy-frame! :teardown/inner-B)
        {:db db}))
    (rf/make-frame {:id :teardown/outer-A :doc        "outer frame"
                    :on-destroy [:teardown/destroy-inner]})
    (with-hooks*
      {:ssr/on-frame-destroyed           (throwing-hook :ssr)
       :schemas/on-frame-destroyed!      (throwing-hook :schemas)
       :flows/teardown-on-frame-destroy! (throwing-hook :flows)}
      (fn [] (rf/destroy-frame! :teardown/outer-A)))
    (let [reports  (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)
          expected [:flows/teardown-on-frame-destroy!
                    :schemas/on-frame-destroyed!
                    :ssr/on-frame-destroyed]]
      (is (= [2 {:teardown/outer-A expected :teardown/inner-B expected}]
             [(count reports)
              (into {} (map (juxt :frame #(sort (map :hook (:hook-failures %))))) reports)])
          "two reports, each carrying only its own extent's three failures"))))
