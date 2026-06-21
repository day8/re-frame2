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
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.streaming :as streaming]
            [re-frame.ssr.ring.test-support :as ts]
            [re-frame.trace :as trace])
  (:import [java.io InputStream OutputStream PipedInputStream PipedOutputStream]))

(use-fixtures :each ts/reset-runtime)

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
    (rf/reg-event :rf.test.writer/init
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [throwing-root (fn [] (throw (ex-info "shell-render teardown probe"
                                               {:reason :rf2-u91hb})))
          handler  (ssr-ring/stream-handler
                     {:initial-events [[:rf.test.writer/init]]
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

;; ===========================================================================
;; Writer-failure PHASE context (rf2-l1qgjw issue 2)
;; ===========================================================================
;;
;; The writer-failed trace now names WHICH chunk phase was in flight when
;; the post-commit write threw (`:phase`), the continuation `:boundary-id`
;; when the failure is inside a continuation drain, and a coarse
;; `:committed?`. These tests force DIFFERENT writer phases to throw and
;; assert the traces carry DISTINCT phase tags — so ops can tell a broken
;; client pipe from a bad final payload from a specific boundary drain.
;;
;; Mechanism: a counting `OutputStream` that throws on its Nth
;; `write(byte[])` call. The writer calls `.write` once per chunk via
;; `write-chunk!`, so the chunk ordering (shell-prefix, shell-html,
;; [continuation-template, continuation-delta]*, final-payload, suffix)
;; maps writes → phases deterministically. We build a GENUINE `rendered`
;; map by setting up a real per-request frame and calling
;; `render-streaming-shell!`, so `build-final-payload` succeeds and the
;; throw comes from the WRITE (the phase under test), not from shell
;; resolution.

(defn- throw-on-nth-write-stream
  "An OutputStream that throws `IOException` on its Nth `write(byte[])`
  call (1-indexed). Earlier writes are swallowed. Used to target a
  specific writer phase deterministically."
  ^OutputStream [n]
  (let [calls (atom 0)]
    (proxy [OutputStream] []
      (write
        ([b]
         (when (= n (swap! calls inc))
           (throw (java.io.IOException. (str "forced-write-failure-at-" n))))
         nil)
        ([b off len]
         (when (= n (swap! calls inc))
           (throw (java.io.IOException. (str "forced-write-failure-at-" n))))
         nil))
      (flush [] nil)
      (close [] nil))))

(defn- setup-streaming-frame!
  "Register a real per-request server frame for `opts` and return its
  frame-id. The `:initial-events` event seeds the app-db the views read."
  [opts]
  (let [{:keys [frame-id]} (pipeline/setup-request-frame! opts {:uri "/" :request-method :get})]
    frame-id))

(defn- capture-writer-failure
  "Drive `run-streaming-writer!` against a stream that throws on the Nth
  write, capture the writer-failed trace, and return its first event (or
  nil). `rendered` is a genuine `render-streaming-shell!` result."
  [frame-id rendered opts throw-at-write]
  (let [captured (atom [])]
    (with-trace-capture captured
      #(@#'streaming/run-streaming-writer!
         (throw-on-nth-write-stream throw-at-write) frame-id rendered opts))
    (->> @captured
         (filterv #(= :rf.error/ssr-streaming-writer-failed (:operation %)))
         first)))

(deftest writer-failed-trace-carries-distinct-phase-per-chunk
  (testing "rf2-l1qgjw: forcing different writer chunks to throw produces
            writer-failed traces with DISTINCT :phase tags (shell-prefix
            vs final-payload vs suffix), all marked :committed? true"
    (rf/reg-event :rf.test.phase/init
      {:platforms #{:server}}
      (fn [_ _] {:db {:n 1}}))
    (rf/reg-sub :rf.test.phase/n (fn [db _] (:n db)))
    (rf/reg-view ^{:rf/id :rf.test.phase/root} phase-root []
      [:main [:span @(subscribe [:rf.test.phase/n])]])
    (let [opts     {:initial-events [[:rf.test.phase/init]]
                    :root-view [:rf.test.phase/root]
                    :emit-hash? true
                    :payload :rf.ssr.payload/whole-app-db}
          ;; A no-suspense root → the write order is exactly:
          ;;   1 shell-prefix, 2 shell-html, 3 final-payload, 4 suffix.
          mk       (fn [throw-at]
                     (let [fid      (setup-streaming-frame! opts)
                           rendered (#'streaming/render-streaming-shell! fid opts)
                           ev       (capture-writer-failure fid rendered opts throw-at)]
                       (lifecycle/destroy-frame-quietly! fid)
                       ev))
          prefix-ev (mk 1)
          final-ev  (mk 3)
          suffix-ev (mk 4)]
      (is (= :shell-prefix (-> prefix-ev :tags :phase))
          "throw on write 1 → :phase :shell-prefix")
      (is (= :final-payload (-> final-ev :tags :phase))
          "throw on write 3 → :phase :final-payload")
      (is (= :suffix (-> suffix-ev :tags :phase))
          "throw on write 4 → :phase :suffix")
      ;; The discriminating contract: the three phases are pairwise
      ;; distinct (a single undifferentiated trace would fail this).
      (is (= 3 (count (distinct [(-> prefix-ev :tags :phase)
                                 (-> final-ev :tags :phase)
                                 (-> suffix-ev :tags :phase)])))
          "three forced phases yield three DISTINCT :phase tags")
      ;; Every writer phase runs post-head-commit.
      (doseq [ev [prefix-ev final-ev suffix-ev]]
        (is (true? (-> ev :tags :committed?))
            ":committed? true on every writer phase (post-head-commit)")
        (is (= :truncate-and-close (:recovery ev))
            ":recovery hoisted to top-level, unchanged"))
      ;; No :boundary-id outside a continuation drain.
      (doseq [ev [prefix-ev final-ev suffix-ev]]
        (is (not (contains? (:tags ev) :boundary-id))
            "no :boundary-id tag outside a continuation phase")))))

(deftest writer-failed-trace-carries-boundary-id-on-continuation-phase
  (testing "rf2-l1qgjw: a write throw during a continuation drain tags the
            trace :phase :continuation-template AND :boundary-id <id>, so
            ops correlate the failure to a specific deferred subtree"
    (rf/reg-event :rf.test.phase/init-sb
      {:platforms #{:server}}
      (fn [_ _] {:db {:items [:a :b]}}))
    (rf/reg-sub :rf.test.phase/items (fn [db _] (:items db)))
    (rf/reg-view ^{:rf/id :rf.test.phase/section} phase-section []
      (into [:ul] (for [i @(subscribe [:rf.test.phase/items])] [:li (name i)])))
    (rf/reg-view ^{:rf/id :rf.test.phase/sb-root} sb-root []
      [:main
       [:h1 "hdr"]
       [:rf/suspense-boundary
        {:id :rf.test.phase/the-boundary :fallback [:p "loading"]}
        [:rf.test.phase/section]]])
    (let [opts     {:initial-events [[:rf.test.phase/init-sb]]
                    :root-view [:rf.test.phase/sb-root]
                    :emit-hash? true
                    :payload :rf.ssr.payload/whole-app-db}
          fid      (setup-streaming-frame! opts)
          rendered (#'streaming/render-streaming-shell! fid opts)]
      ;; With one boundary the write order is:
      ;;   1 shell-prefix, 2 shell-html, 3 continuation-template, ...
      ;; Throw on write 3 to hit the continuation-template phase.
      (is (seq (:continuations rendered))
          "the rendered shell registered the suspense continuation")
      (let [ev (capture-writer-failure fid rendered opts 3)]
        (lifecycle/destroy-frame-quietly! fid)
        (is (= :continuation-template (-> ev :tags :phase))
            "throw on the boundary's template write → :phase :continuation-template")
        (is (= :rf.test.phase/the-boundary (-> ev :tags :boundary-id))
            ":boundary-id names the specific continuation that was draining")
        (is (true? (-> ev :tags :committed?))
            ":committed? true — the continuation phase is post-head-commit")))))

;; ===========================================================================
;; EP-0008 (rf2-hhutya) — the writer-failed record reaches the ALWAYS-ON
;; register-error-listener! axis under production hardening (debug-off),
;; WITHOUT touching the wire (the chunked 200 is already committed —
;; NON-PROJECTING). The dev-trace pin above runs debug-ON; this is its
;; production-survivable counterpart.
;; ===========================================================================

(deftest hhutya-writer-failed-reaches-always-on-listener-under-debug-off
  (testing "rf2-hhutya: when run-streaming-writer!'s catch arm absorbs a
            post-commit write throw under `interop/debug-enabled? = false`,
            it ALSO fans :rf.error/ssr-streaming-writer-failed out on the
            always-on register-error-listener! axis (alongside the dev
            trace, which is elided under debug-off). NON-PROJECTING: the
            chunked 200 is already on the wire, so there is no status to
            flip — pure off-box telemetry."
    (error-emit/clear-error-listeners!)
    (let [pipe-in  (PipedInputStream. 1024)
          pipe-out (PipedOutputStream. pipe-in)
          _        (.close pipe-in) ;; pre-broken pipe — every write throws
          seen     (atom [])]
      (rf/register-listener! :errors
        ::hhutya-writer-recorder
        (fn [record] (swap! seen conj record)))
      (with-redefs [interop/debug-enabled? false]
        (@#'streaming/run-streaming-writer!
          pipe-out :no-such-frame
          {:hiccup [:div] :head-html "" :html-attrs nil :body-attrs nil
           :shell-html "<div></div>" :continuations []}
          {:root-view [:div]}))
      (error-emit/clear-error-listeners!)
      (let [hits (filterv #(= :rf.error/ssr-streaming-writer-failed (:error %))
                          @seen)]
        (is (= 1 (count hits))
            "exactly one writer-failed record fanned out on the always-on
             axis under debug-off (the dev trace is elided there)")
        (when (seq hits)
          (let [rec (first hits)]
            (is (= :no-such-frame (:frame rec))
                "the always-on record carries the failing frame on its flat
                 :frame slot (union shape)")
            (is (some? (:phase rec))
                "the flat :phase slot rides through (which chunk was in flight)")
            (is (= :truncate-and-close (:recovery rec))
                "the flat :recovery slot rides through — NON-PROJECTING,
                 post-commit truncate-and-close (no status to change)")
            (is (true? (:committed? rec))
                ":committed? true — post-head-commit, the wire is unchanged")))))))
