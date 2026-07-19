(ns re-frame.ui.frame-ops-reincarnation-sink-route-cljs-test
  "rf2-bf0io — keep a stale UI `(frame)` bundle's dead-incarnation failure OUT of
  the same-id SUCCESSOR frame's own `:observability :errors` sink.

  A compiled `re-frame.ui` `(frame)` bundle is EXACT-INCARNATION authority: an op
  invoked on a bundle captured for incarnation A, after A is destroyed, fails
  loud with `:rf.error/frame-destroyed` (fenced by `emit-and-throw-frame-
  destroyed!`). That seam fans the failure out on TWO channels — the corpus-wide
  always-on record (axis 1) and the dev trace (axis 2) — and then throws. But the
  corpus emit (`error-emit/dispatch-on-error!`) ALSO drove the EP-0015 §9
  frame-OWNED `:observability :errors` sink route, which resolves the record's
  bare frame id to the CURRENT frame. When a same-id SUCCESSOR B has replaced the
  destroyed A, that bare id resolves to B — so A's stale-op failure was delivered
  into B's OWN error sink (the bead's repro: B's sink count becomes 1, carrying
  A's stale query and `:rf.error/frame-destroyed`). A dead incarnation must NOT
  reach a live frame's sink (frame isolation; exact-incarnation attribution).

  The fix passes `error-emit/emit-error-both!` a false `route-frame?` at the UI
  dead-incarnation seam, suppressing ONLY the frame-owned sink route. The
  corpus-wide record and the dev trace still fire EXACTLY ONCE, and the typed
  throw still propagates — this is the event-centric mirror of the predecessor
  teardown-report seam rf2-vxgfnd.118 (`route-frame?` on the union-record path).

  These tests pin:
    - the REINCARNATION regression: A's stale bundle op never increments same-id
      B's `:errors` sink (red-before: B's sink becomes 1; after: 0), with a
      vacuity probe proving B's sink is genuinely armed;
    - PRESERVED: exactly one corpus-wide `:rf.error/frame-destroyed` record + one
      dev trace still fire, then the throw;
    - WHAT-STAYS: subscribe keeps RAW query identity (#6497/#6516), dispatch /
      dispatch-sync keep source redaction, capture is payload-free — on the
      surviving corpus record;
    - LIVE routing intact: an ordinary live handler-exception still reaches its
      frame-owned sink (the default `route-frame?` path is untouched).

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both run it. Plain
  CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core          :as rf]
            [re-frame.error-emit    :as error-emit]
            [re-frame.frame         :as frame]
            [re-frame.observability :as observability]
            [re-frame.privacy       :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support  :as test-support]
            [re-frame.trace         :as trace]
            [re-frame.ui.frames     :as frames]))

;; The reset-runtime fixture rebuilds registrar / frames / runtime per test; we
;; additionally clear the corpus error-listener registry AND the observability
;; sink registry so a listener / sink from one test cannot leak into the next.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (error-emit/clear-error-listeners!)
                      (observability/clear-observability-sinks!))})
  (fn [f]
    (frames/reset-frame-ops-cache!)
    (try (f) (finally (frames/reset-frame-ops-cache!)))))

;; The attempted event / query the stale bundle carries: a keyword head plus a
;; distinctive body leaf, so a leak into a sink would be unmistakable.
(def ^:private secret-event    [:audit/secret {:password :TOP-SECRET}])
(def ^:private subscribe-query [:reinc/n {:token :SUBSCRIBE-IDENTITY}])

(defn- err-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- make-frame-with-error-sink!
  "Create frame `id` declaring an `:observability :errors` sink named `sink-id`,
  and register the concrete sink fn (conj'ing every record it receives into
  `seen`). Returns `id`."
  [id sink-id seen]
  (observability/register-observability-sink! sink-id (fn [r] (swap! seen conj r)))
  (rf/make-frame {:id id
                  :observability {:errors [{:sink sink-id
                                            :rf.egress/profile :rf.egress/off-box-observability}]}})
  id)

(defn- capture
  "Run `thunk` (expected to throw `:rf.error/frame-destroyed`), capturing the
  corpus-wide always-on records (axis 1) AND the dev-trace frame-destroyed events
  (axis 2) it fans BEFORE the throw. Returns `{:records [...] :traces [...]
  :err-id <kw>}`. Freshly-gensym'd listener keys, unregistered on the way out."
  [thunk]
  (let [recs   (atom [])
        traces (atom [])
        ekey   (keyword "test" (name (gensym "bf0io-rec")))
        tkey   (keyword "test" (name (gensym "bf0io-trace")))]
    (error-emit/register-error-listener! ekey (fn [r] (swap! recs conj r)))
    (trace/register-listener! tkey
                              (fn [ev] (when (= :rf.error/frame-destroyed (:operation ev))
                                         (swap! traces conj ev))))
    (try
      (let [caught (err-id thunk)]
        {:records @recs :traces @traces :err-id caught})
      (finally
        (error-emit/unregister-error-listener! ekey)
        (trace/unregister-listener! tkey)))))

;; ---------------------------------------------------------------------------
;; The reincarnation regression (the bead's exact reproduction).
;; ---------------------------------------------------------------------------

(deftest stale-bundle-op-never-reaches-successor-error-sink
  (testing "Per rf2-bf0io: create A, capture its (frame) bundle, destroy A, then
            create same-id B declaring its OWN :observability :errors sink.
            Invoking A's stale bundle op fails loud with :rf.error/frame-destroyed
            and STILL fans exactly one corpus record + one dev trace — but B's own
            error sink stays CLEAN (red before the fix: B's sink count is 1 with
            A's stale query). The dead incarnation's bare frame id must never
            resolve to the live successor's sink."
    (doseq [[op invoke head expected-event]
            [[:subscribe     (fn [b] ((:subscribe b)     subscribe-query)) :reinc/n      subscribe-query]
             [:dispatch      (fn [b] ((:dispatch b)      secret-event))    :audit/secret privacy/redacted-sentinel]
             [:dispatch-sync (fn [b] ((:dispatch-sync b) secret-event))    :audit/secret privacy/redacted-sentinel]]]
      (testing (str "stale " op " across a same-id reincarnation")
        (let [fid     (keyword "bf0io" (str "id-" (name op)))
              sink-id (keyword "bf0io.sinks" (str "sentry-" (name op)))
              b-sink  (atom [])]
          ;; A: create + capture its incarnation-fenced bundle, then destroy.
          (rf/make-frame {:id fid})
          (let [stale (rf/with-frame fid (frames/frame-ops))]
            (frame/destroy-frame! fid)
            ;; B: same id, declaring its OWN :errors sink (armed BEFORE the op).
            (make-frame-with-error-sink! fid sink-id b-sink)
            (is (some? (frame/frame fid)) "successor B is live under the reused id")
            ;; Fire A's stale bundle op into the same-id successor world.
            (let [{:keys [records traces err-id]} (capture #(invoke stale))]
              (is (= :rf.error/frame-destroyed err-id)
                  "the stale op still fails loud with the canonical typed error")
              ;; THE REGRESSION: B's OWN sink must stay clean.
              (is (zero? (count @b-sink))
                  "A's dead-incarnation failure NEVER reaches successor B's :errors sink")
              ;; PRESERVED: exactly one corpus record + one dev trace still fire.
              (is (= 1 (count records)) "EXACTLY ONE corpus-wide :rf.error/frame-destroyed record still fans")
              (is (= 1 (count traces))  "EXACTLY ONE dev trace on axis 2 still fires")
              (let [r (first records)]
                (is (= :rf.error/frame-destroyed (:error r)) "corpus category retained")
                (is (= fid (:frame r))                       "corpus record carries A's captured bare frame id")
                (is (= op (:op r))                           "operation retained")
                (is (= head (:event-id r))                   "structural event/query head retained")
                ;; WHAT-STAYS (#6497/#6516): subscribe keeps RAW query identity;
                ;; dispatch / dispatch-sync keep source redaction — on the
                ;; surviving corpus record, unchanged by the route suppression.
                (is (= expected-event (:event r))
                    "corpus :event keeps subscribe raw-identity / dispatch source-redaction")))
            ;; VACUITY PROBE: B's sink IS armed and capable of receiving. An
            ;; ordinary error routed directly to B lands in it — so the zero
            ;; above is REAL suppression, not an unwired / mis-declared sink.
            (observability/route-error!
              :rf.error/handler-exception [:bf0io/probe] :bf0io/probe fid nil 0 0 nil)
            (is (= 1 (count @b-sink))
                "B's sink IS live — a directly-routed ordinary error reaches it")))))))

;; ---------------------------------------------------------------------------
;; The capture arm is payload-free and routes nothing.
;; ---------------------------------------------------------------------------

(deftest capture-arm-is-payload-free-and-routes-nothing
  (testing "Per rf2-bf0io / rf2-01ihi: a (frame) read that resolves a dead /
            absent incarnation fails closed with the :capture attribution, a
            payload-free corpus record, and routes nothing to a frame-owned sink."
    (let [ghost   :bf0io/ghost
          sink-id :bf0io.sinks/ghost-sentry
          seen    (atom [])]
      (observability/register-observability-sink! sink-id (fn [r] (swap! seen conj r)))
      (let [{:keys [records err-id]}
            (capture #(binding [frame/*current-frame* ghost] (frames/frame-ops)))]
        (is (= :rf.error/frame-destroyed err-id))
        (is (= 1 (count records)) "exactly one corpus record for the capture arm")
        (let [r (first records)]
          (is (= ghost (:frame r)))
          (is (= :capture (:op r))     ":op marks the capture-time failure")
          (is (nil? (:event-id r))     "no op ran — the capture arm carries no structural head")
          ;; Payload-free: no real payload survives — `:event` is at most the
          ;; fail-closed sentinel (the ghost frame is unresolvable), never a body.
          (is (contains? #{nil privacy/redacted-sentinel} (:event r))
              "the capture arm is payload-free — :event is nil or the fail-closed sentinel"))
        (is (zero? (count @seen))
            "the capture arm routes nothing to a frame-owned sink")))))

;; ---------------------------------------------------------------------------
;; Live routing is untouched — the default route-frame? path still delivers.
;; ---------------------------------------------------------------------------

(deftest ordinary-live-error-still-reaches-frame-owned-sink
  (testing "the default route-frame? path is untouched (rf2-bf0io suppresses ONLY
            the dead-incarnation UI seam): a handler-exception on a LIVE frame
            still routes ONE :rf.observe/error record to that frame's declared
            :observability :errors sink."
    (let [seen    (atom [])
          fid     :bf0io/live
          sink-id :bf0io.sinks/live-sentry]
      (make-frame-with-error-sink! fid sink-id seen)
      (rf/reg-event :bf0io/boom {:frame fid}
        (fn [_ _] (throw (ex-info "kaboom" {:cause :test}))))
      (rf/dispatch-sync [:bf0io/boom] {:frame fid})
      (is (= 1 (count @seen)) "the live frame's error sink still receives exactly one record")
      (let [r (first @seen)]
        (is (= :rf.observe/error (:kind r)))
        (is (= fid (:frame r)))
        (is (= :rf.error/handler-exception (:error r)))))))
