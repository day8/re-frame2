(ns re-frame.non-event-observability-routing-cljs-test
  "Spec 015 §Frame-owned observability sink policy — the NON-EVENT always-on
  records (the frame-teardown report, the SSR categories) reach the owning
  frame's `:observability :errors` sinks through
  `rf.error-emit/dispatch-error-record!`, in parallel with the corpus-wide
  error listener.

  The listener carries the raw record (an off-box shipper needs the exception);
  the sink receives a projected `:rf.observe/error` whose flat category slots
  ride `:tags`, walked under the frame's classification.

  No process default is declared here, so the unroutable records reach no sink;
  the default's own arms are pinned by
  `re-frame.observability-process-default-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(deftest teardown-report-routes-projected-to-frame-error-sink
  (let [sink-seen     (atom [])
        listener-seen (atom [])
        secret        "S3CR3T-rf2-ntv9i9-DO-NOT-LEAK-TO-SINK"]
    (rf/register-observability-sink! :test.sinks/sentry
                                     (fn [record] (swap! sink-seen conj record)))
    (rf.error-emit/register-error-listener! :test/listener
                                            (fn [record] (swap! listener-seen conj record)))
    (rf/make-frame {:id :obs/teardown :observability
                    {:errors [{:sink :test.sinks/sentry
                               :rf.egress/profile :rf.egress/off-box-observability}]}})
    ;; The index-free declaration matches the runtime
    ;; `[:hook-failures 0 :exception-data :token]`.
    (rf.frame/swap-runtime-db! :obs/teardown
      (fn [rt] (rf.elision/apply-classification-effects rt
                 {:sensitive [[:hook-failures :exception-data :token]]})))
    (rf.error-emit/dispatch-frame-teardown-report!
      :obs/teardown
      [{:hook           :flows/teardown-on-frame-destroy!
        :exception      (ex-info "boom" {})
        :exception-data {:token secret}
        :where          :safe-call-hook!}]
      12345)
    (testing "the corpus-wide listener receives the raw record"
      (is (= [:rf.error/frame-teardown-failed] (mapv :error @listener-seen)))
      (is (re-find (re-pattern secret) (pr-str (first @listener-seen)))))
    (testing "the frame's :errors sink receives the record projected"
      (is (= 1 (count @sink-seen)))
      (let [r (first @sink-seen)]
        (is (= {:kind  :rf.observe/error
                :frame :obs/teardown
                :error :rf.error/frame-teardown-failed}
               (select-keys r [:kind :frame :error])))
        (is (= :rf/redacted (get-in r [:tags :hook-failures 0 :exception-data :token])))
        (is (string? (get-in r [:tags :reason])) ":reason rides :tags")
        (is (not (re-find (re-pattern secret) (pr-str r)))
            "the secret appears nowhere in the projected record")))))

(deftest exception-dropped-under-public-error-profile
  (let [sink-seen (atom [])]
    (rf/register-observability-sink! :test.sinks/public
                                     (fn [record] (swap! sink-seen conj record)))
    (rf/make-frame {:id :obs/public :observability
                    {:errors [{:sink :test.sinks/public
                               :rf.egress/profile :rf.egress/public-error}]}})
    (rf.error-emit/dispatch-error-record!
      {:error     :rf.error/ssr-render-failed
       :frame     :obs/public
       :time      7
       :exception (ex-info "render blew up" {:internal :detail})
       :reason    "render failed"})
    (is (= 1 (count @sink-seen)))
    (let [r (first @sink-seen)]
      (is (not (contains? r :exception)) ":exception dropped under :rf.egress/public-error")
      (is (= {:frame :obs/public :error :rf.error/ssr-render-failed}
             (select-keys r [:frame :error]))))))

(deftest unroutable-records-reach-the-listener-and-no-sink
  (let [sink-seen     (atom [])
        listener-seen (atom [])
        hook-failures [{:hook :ssr/on-frame-destroyed :exception (ex-info "x" {}) :where :safe-call-hook!}]]
    (rf/register-observability-sink! :test.sinks/sentry
                                     (fn [record] (swap! sink-seen conj record)))
    (rf.error-emit/register-error-listener! :test/listener
                                            (fn [record] (swap! listener-seen conj record)))
    (rf/make-frame {:id :obs/nopolicy})
    (doseq [[why emit! error]
            [["a frame with no :observability :errors policy"
              #(rf.error-emit/dispatch-frame-teardown-report! :obs/nopolicy hook-failures 1)
              :rf.error/frame-teardown-failed]
             ["a frameless record (the pre-frame hydration-parse path)"
              #(rf.error-emit/dispatch-error-record!
                 {:error      :rf.error/malformed-hydration-payload
                  :frame      nil
                  :time       3
                  :where      :rf/read-server-payload
                  :failing-id :rf/hydrate
                  :reason     :unreadable-edn
                  :recovery   :no-recovery})
              :rf.error/malformed-hydration-payload]
             ["a never-registered frame, with no :rf/default synthesis"
              #(rf.error-emit/dispatch-error-record!
                 {:error         :rf.error/frame-teardown-failed
                  :frame         :obs/ghost
                  :time          1
                  :hook-failures hook-failures})
              :rf.error/frame-teardown-failed]]]
      (reset! sink-seen [])
      (reset! listener-seen [])
      (emit!)
      (is (empty? @sink-seen) why)
      (is (= [error] (mapv :error @listener-seen)) why))))
