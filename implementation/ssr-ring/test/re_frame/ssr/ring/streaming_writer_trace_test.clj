(ns re-frame.ssr.ring.streaming-writer-trace-test
  "Pin the writer thread's load-bearing trace emission and frame teardown
  composition. Per rf2-u91hb (audit follow-on from the rare-corner-cases
  sweep).

  ## Why this fills a real gap

  `streaming_robustness_test` covers four behaviours of the streaming
  writer's `catch Throwable` arm:

    1. broken-pipe absorbed, OutputStream closed by `finally`,
    2. real-network disconnect cleans up,
    3. root-view throw fails closed to a non-200 on the request thread
       (rf2-r06pc — the shell render moved off the writer; a root-view
       throw never reaches the daemon writer now),
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

  Second gap covered here: the per-request frame destroy on a render
  failure. Post rf2-r06pc a shell-render throw (root-view throw) fails
  closed on the REQUEST thread and tears the frame down INLINE (the
  shell-render catch arm in `stream-handler`), before any writer thread
  is spawned. The continuation/final-payload writer-body throws that
  DO still reach the daemon thread tear the frame down in the spawned-
  thread `finally`. Either way the destroy MUST run; this ns pins the
  composition the existing tests test independently."
  (:require [clojure.set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace])
  (:import [java.io InputStream PipedInputStream PipedOutputStream]))

(use-fixtures :each tf/reset-runtime)

(defn- with-trace-capture
  [coll-atom body-fn]
  (let [k (str (gensym "ssr-writer-trace-cb"))]
    (trace/register-listener! k (fn [ev] (swap! coll-atom conj ev)))
    (try (body-fn)
         (finally (trace/unregister-listener! k)))))

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
      ;; Drive the writer body directly against the pre-broken pipe.
      ;; rf2-r06pc — the writer no longer resolves/renders the shell
      ;; (that moved to the request thread); we hand it a PRE-RENDERED
      ;; shell. The first chunk write of the shell prefix hits the
      ;; pre-broken pipe → IOException → the catch arm runs and emits
      ;; the trace.
      (with-trace-capture captured
        #(@#'streaming/run-streaming-writer!
           pipe-out :no-such-frame
           {:hiccup [:div] :head-html "" :html-attrs nil :body-attrs nil
            :shell-html "<div></div>" :continuations []}
           {:root-view [:div]}))
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
;; Shell-render-throw composition with frame destroy — when the shell
;; render throws (root-view throw), the per-request frame MUST still be
;; destroyed so its app-db + side-channel slots are released. Pin the
;; composition the existing tests test independently.
;;
;; rf2-r06pc — a root-view / shell-walk throw now fails closed on the
;; REQUEST thread (before the head commits + before any writer is
;; spawned): the shell-render catch arm projects a non-200 error page AND
;; tears the frame down inline. So this is now a SYNCHRONOUS teardown on
;; the request thread (no spawned-thread `finally` to wait on, no
;; InputStream body to drain). The frame-no-leak contract is unchanged —
;; only the mechanism moved earlier.
;; ===========================================================================

(deftest stream-handler-destroys-frame-when-shell-render-throws
  (testing "rf2-u91hb / rf2-r06pc: when the shell render throws (root-view
            throw), the per-request frame's app-db / sub-cache / side-
            channel slots MUST still be released. Post rf2-r06pc the
            teardown happens INLINE on the request thread (the shell-
            render fail-closed catch arm), not in a spawned-thread
            finally — the throw never reaches a writer thread. Without
            this every failed streaming request would leak a frame record."
    (rf/reg-event-fx :rf.test.writer/init
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [throwing-root (fn [] (throw (ex-info "shell-render teardown probe"
                                               {:reason :rf2-u91hb})))
          handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.writer/init]
                      :root-view throwing-root
                      :payload :rf.ssr.payload/whole-app-db})
          ;; Frame ids BEFORE the request — baseline.
          baseline-fids (disj (frame/frame-ids) :rf/default)
          response (handler {:uri "/" :request-method :get})]
      ;; rf2-r06pc — the shell render threw on the request thread, so the
      ;; response is the projected non-200 error page (an ordinary String
      ;; body), NOT a streamed InputStream. The frame teardown already ran
      ;; inline by the time the handler returned.
      (is (= 500 (:status response))
          "root-view throw fails closed to a non-200 projected error page
           on the request thread (rf2-r06pc)")
      (is (not (instance? InputStream (:body response)))
          "no streamed InputStream body — the chunked response was never
           committed (the shell render failed before the head commit)")
      (let [end-fids (disj (frame/frame-ids) :rf/default)
            leaked   (clojure.set/difference end-fids baseline-fids)]
        (is (empty? leaked)
            (str "the per-request frame MUST be destroyed even though
                 the shell render threw — found leaked frame-ids: "
                 (vec leaked)))))))
