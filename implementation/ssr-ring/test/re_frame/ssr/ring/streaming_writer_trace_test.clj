(ns re-frame.ssr.ring.streaming-writer-trace-test
  "Pin the writer thread's load-bearing trace emission and frame teardown
  composition. Per rf2-u91hb (audit follow-on from the rare-corner-cases
  sweep).

  ## Why this fills a real gap

  `streaming_robustness_test` covers four behaviours of the streaming
  writer's `catch Throwable` arm:

    1. broken-pipe absorbed, OutputStream closed by `finally`,
    2. real-network disconnect cleans up,
    3. root-view throw absorbed, daemon terminates,
    4. daemon thread name carries the frame-id.

  What it does NOT cover — and what `re-frame.ssr.ring.streaming/run-
  streaming-writer!` line 180 explicitly produces — is the
  `:rf.error/ssr-streaming-writer-failed` trace event itself. The
  streaming.cljc docstring (line 22-23) names the trace as the
  load-bearing observability signal for writer-thread failures, but
  no test grep'd anywhere in the suite finds an assertion on the
  trace keyword: `ssr-streaming-writer-failed` appears only in the
  impl + docstrings.

  That's the gap this ns fills. Trace observability is a production-
  monitoring contract — apps registering trace listeners for the
  failure category MUST see events fire. If a refactor of
  `run-streaming-writer!` drops the trace emit by accident (it would
  pass every existing robustness test, since those only assert
  absence-of-escape and pipe-close), the gap re-opens silently and
  ops loses the signal.

  Second gap covered here: the writer thread's `finally` arm wraps
  the per-request frame destroy (`lifecycle/destroy-frame-quietly!`
  in `stream-handler`'s spawn body — `streaming.clj` line 273). The
  contract is that EVEN WHEN the writer body throws, the destroy
  still runs in the spawned-thread `finally`. The existing tests
  cover writer-body throws AND daemon thread cleanup, but not the
  composition: a writer-body throw co-occurs with a frame whose
  side-channel atoms become observable via the destroy hook."
  (:require [clojure.set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [java.io InputStream PipedInputStream PipedOutputStream]))

(use-fixtures :each tf/reset-runtime)

(defn- with-trace-capture
  [coll-atom body-fn]
  (let [k (str (gensym "ssr-writer-trace-cb"))]
    (trace/register-trace-listener! k (fn [ev] (swap! coll-atom conj ev)))
    (try (body-fn)
         (finally (trace/unregister-trace-listener! k)))))

;; ===========================================================================
;; The trace emit itself — the gap streaming_robustness_test left open.
;; ===========================================================================

(deftest writer-catch-arm-emits-ssr-streaming-writer-failed-trace
  (testing "rf2-u91hb: when run-streaming-writer!'s outer catch arm
            absorbs a throw, it MUST emit :rf.error/ssr-streaming-
            writer-failed on the trace bus per the streaming.cljc /
            streaming.clj failure-semantics contract. The existing
            robustness tests pin absorb behaviour + pipe-close, but
            never the trace itself — a refactor that silently drops
            the emit would pass every existing test."
    (let [pipe-in  (PipedInputStream. 1024)
          pipe-out (PipedOutputStream. pipe-in)
          _        (.close pipe-in) ;; pre-broken pipe — every write throws
          captured (atom [])]
      ;; Drive the writer body directly against the pre-broken pipe;
      ;; the writer's outer try fires on the first internal
      ;; with-frame deref (no such frame) AND on every subsequent
      ;; pipe write. Either way the catch arm runs.
      (with-trace-capture captured
        #(@#'streaming/run-streaming-writer!
           pipe-out :no-such-frame {} {:root-view [:div]}))
      (let [hits (filterv #(= :rf.error/ssr-streaming-writer-failed (:operation %))
                          @captured)]
        (is (= 1 (count hits))
            (str "expected exactly one :rf.error/ssr-streaming-writer-
                 failed trace; saw: " (count hits) " (all operations: "
                 (pr-str (mapv :operation @captured)) ")"))
        (when (seq hits)
          (let [ev (first hits)]
            (is (= :error (:op-type ev))
                ":op-type is :error per Spec 009 — writer-failed is a
                 hard failure, not a warning")
            (is (some? (-> ev :tags :exception))
                ":exception tag carries the throwable's message")
            (is (some? (-> ev :tags :ex-class))
                ":ex-class tag carries the throwable's class name")
            (is (= :truncate-and-close (:recovery ev))
                ":recovery is hoisted to top-level per Spec 009
                 §Error event shape — names the failure-recovery
                 policy (partial response on the wire, pipe closes)")
            (is (= :no-such-frame (-> ev :tags :frame))
                ":frame tag identifies which request failed — load-
                 bearing for ops correlating writer failures to
                 specific requests in JFR / log streams")))))))

;; ===========================================================================
;; Writer-throw composition with frame destroy — even when the writer
;; body throws, the spawned-thread `finally` MUST run destroy-frame-
;; quietly! so the per-request frame's app-db + side-channel slots are
;; released. Pin the composition the existing tests test independently.
;; ===========================================================================

(deftest stream-handler-destroys-frame-when-writer-body-throws
  (testing "rf2-u91hb: when the streaming writer body throws (root-view
            throw), the spawned thread's finally MUST still invoke
            destroy-frame-quietly! so the per-request frame's app-db
            / sub-cache / side-channel slots are released. Without this
            composition every aborted/failed streaming request would
            leak a frame record. Per stream-handler line ~268-273."
    (rf/reg-event-fx :rf.test.writer/init
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [throwing-root (fn [] (throw (ex-info "writer-thread teardown probe"
                                               {:reason :rf2-u91hb})))
          handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.writer/init]
                      :root-view throwing-root
                      :payload-policy :rf.ssr.payload/whole-app-db})
          ;; Frame ids BEFORE the request — baseline.
          baseline-fids (disj (frame/frame-ids) :rf/default)
          response (handler {:uri "/" :request-method :get})
          ;; Drain the body so the writer thread runs to completion +
          ;; the spawned-thread `finally` fires.
          _drain   (slurp ^InputStream (:body response))]
      ;; Poll until the spawned thread's `finally` has run the destroy
      ;; (rf2-fun38). The frame-set returning to baseline IS the
      ;; observable signal; no need for a fixed wait.
      (test-support/poll-until
        (fn []
          (let [end-fids (disj (frame/frame-ids) :rf/default)]
            (empty? (clojure.set/difference end-fids baseline-fids))))
        {:timeout-ms 2000
         :label "per-request frame destroyed after writer-throw"})
      (let [end-fids (disj (frame/frame-ids) :rf/default)
            leaked   (clojure.set/difference end-fids baseline-fids)]
        (is (empty? leaked)
            (str "the per-request frame MUST be destroyed even though
                 the writer body threw — found leaked frame-ids: "
                 (vec leaked)))))))
