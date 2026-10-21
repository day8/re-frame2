(ns re-frame.frame-teardown-report-cljs-test
  "The frame-teardown report (Spec 009 §Channel-promotion catalogue). When
  lifecycle contributions or core's own teardown steps throw during destroy,
  the runtime emits ONE always-on `:rf.error/frame-teardown-failed` record
  carrying every failure in `:hook-failures`, not one record per failure. The
  report flushes even when teardown aborts part-way, carries no raw app values,
  keeps nested destroys' failures apart, and a throwing `:pre-dead` member
  cannot leave the frame live and half torn down. In dev each failure also
  emits a per-failure diagnostic row.

  The diagnostic test is `^:requires-debug`; every other test reads the
  always-on error channel, which is live in the prod gate."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!))}))

(defn- with-contributions*
  "Register each of `contributions` for the extent of `f` — merged by `:id`
  into a loaded artefact's — then put the contribution list back."
  [contributions f]
  (let [saved @rf.frame/lifecycle-contributions]
    (try
      (run! rf.frame/contribute-lifecycle! contributions)
      (f)
      (finally
        (reset! rf.frame/lifecycle-contributions saved)))))

(defn- throwing
  "A contribution `id` joining only `phase` (default `:post-dead`), whose
  destroy member throws."
  ([id] (throwing id :post-dead))
  ([id phase]
   {:id                 id
    :phase              #{phase}
    :on-frame-destroyed (fn [_ _]
                          (throw (ex-info (str "teardown member threw: " id) {:hook id})))}))

(deftest n-hook-failures-yield-one-report-with-n-entries
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/n-failures :doc "three hooks will throw"})
    (with-contributions*
      [(throwing :ssr) (throwing :machines) (throwing :flows)]
      (fn [] (rf/destroy-frame! :teardown/n-failures)))
    (let [reports (filter #(= :rf.error/frame-teardown-failed (:rf.error/id %)) @seen)
          r       (first reports)]
      (is (= [1
              :teardown/n-failures
              [:flows :machines :ssr]
              #{:safe-call-contribution!}
              true
              :ignored
              true
              true
              #{:rf.error/id :frame :hook-failures :recovery :reason :time}
              #{#{:hook :phase :exception :where}}]
             [(count reports)
              (:frame r)
              (sort (map :hook (:hook-failures r)))
              (set (map :where (:hook-failures r)))
              (every? #(some? (:exception %)) (:hook-failures r))
              (:recovery r)
              (string? (:reason r))
              (number? (:time r))
              (set (keys r))
              (set (map (comp set keys) (:hook-failures r)))])
          "one report for the destroy, with one entry per failed member"))))

(deftest clean-destroy-emits-no-report
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/clean :doc "no hooks throw"})
    (rf/destroy-frame! :teardown/clean)
    (is (empty? (filter #(= :rf.error/frame-teardown-failed (:rf.error/id %)) @seen)))))

(deftest partial-teardown-abort-still-flushes-collected-entries
  ;; two hooks fail, then a later non-hook step throws out of destroy-frame!;
  ;; the finally-shaped flush still ships the entries gathered so far
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/make-frame {:id :teardown/abort :doc "aborts mid-teardown"})
    (with-contributions*
      [(throwing :ssr) (throwing :machines)]
      (fn []
        (with-redefs [rf.frame/emit-frame-destroyed-trace!
                      (fn [_id]
                        (throw (ex-info "mid-teardown collapse" {})))]
          (is (thrown? #?(:clj Throwable :cljs js/Error)
                       (rf/destroy-frame! :teardown/abort))))))
    (let [reports (filter #(= :rf.error/frame-teardown-failed (:rf.error/id %)) @seen)]
      (is (= [1 [:machines :ssr]]
             [(count reports) (sort (map :hook (:hook-failures (first reports))))])))))

(deftest ^:requires-debug dev-per-hook-diagnostic-rows-still-emit
  (when rf.interop/debug-enabled?
    (let [traces (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
      (rf/make-frame {:id :teardown/diagnostic :doc "two hooks throw"})
      (with-contributions*
        [(throwing :ssr) (throwing :machines)]
        (fn []
          (try
            (rf/destroy-frame! :teardown/diagnostic)
            (finally (rf/unregister-listener! :trace ::rec)))))
      (is (= [[:teardown/diagnostic :machines]
              [:teardown/diagnostic :ssr]]
             (sort (keep #(when (= :rf.warning/teardown-hook-exception (:operation %))
                            [(get-in % [:tags :frame]) (get-in % [:tags :hook])])
                         @traces)))
          "one frame-attributed diagnostic row per failed member"))))

(deftest step2-teardown-throw-is-accumulated-frame-fully-torn-down
  ;; Unguarded, a throwing :pre-dead member would escape destroy-frame!
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
    (rf/make-frame {:id         :teardown/step2 :doc "a :pre-dead member throws"
                    :on-destroy [:teardown/count-on-destroy]})
    (with-contributions*
      [(throwing :machines :pre-dead)]
      (fn [] (rf/destroy-frame! :teardown/step2)))
    (let [reps (filter #(= :rf.error/frame-teardown-failed (:rf.error/id %)) @reports)]
      ;; fully torn down, :on-destroy ran once, and the failure is one entry of
      ;; the one report, recorded at the contribution boundary
      (is (= [nil 1 1 [:safe-call-contribution!]]
             [(rf.frame/frame :teardown/step2)
              @on-destroy-runs
              (count reps)
              (keep #(when (= [:machines :pre-dead] ((juxt :hook :phase) %)) (:where %))
                    (:hook-failures (first reps)))])))
    (try (rf/destroy-frame! :teardown/step2)
         (catch #?(:clj Throwable :cljs :default) _ nil))
    (is (= 1 @on-destroy-runs) "a second destroy does not re-fire :on-destroy")))

(deftest nested-destroy-accumulators-are-isolated
  ;; A's :on-destroy destroys B, nested inside A's teardown. Every installed member
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
    (with-contributions*
      [(throwing :ssr) (throwing :machines) (throwing :flows)]
      (fn [] (rf/destroy-frame! :teardown/outer-A)))
    (let [reports  (filter #(= :rf.error/frame-teardown-failed (:rf.error/id %)) @seen)
          expected [:flows :machines :ssr]]
      (is (= [2 {:teardown/outer-A expected :teardown/inner-B expected}]
             [(count reports)
              (into {} (map (juxt :frame #(sort (map :hook (:hook-failures %))))) reports)])
          "two reports, each carrying only its own extent's three failures"))))
