(ns re-frame.ui.frame-ops-reincarnation-elision-cljs-test
  "rf2-01ihi — FAIL CLOSED when a stale `(frame)` operation bundle reports its
  captured payload ACROSS a same-id reincarnation.

  A `(frame)` bundle is locked to the exact incarnation A it captured. When A is
  destroyed and an unclassified successor B is created under the SAME id, an op
  invoked on A's stale bundle is correctly REJECTED as stale
  (`:rf.error/frame-destroyed`). But PR #5954 (rf2-vxgfnd.230) made that
  rejection production-observable by fanning the attempted event/query + frame-id
  through `error-emit/emit-error-both!`. The always-on path elides the record's
  `:event` through `elision/elide-wire-value` under that frame-id, and that walk
  fails closed ONLY when the frame-id is UNRESOLVABLE. The live successor B makes
  it resolvable, so the walk borrowed B's (unrelated, permissive) elision policy
  and projected incarnation A's RAW payload off-box — an off-box/log privacy leak
  (A's payload under unrelated successor B's authority), on BOTH the corpus-wide
  record and the frame-owned observability sink route.

  These legs pin the fix: the dead-incarnation emit seam
  (`emit-and-throw-frame-destroyed!`) redacts the payload BODY to `:rf/redacted`
  AT SOURCE, so no successor policy can be borrowed. The record still retains the
  category, the captured frame id, the operation, and the structural event/query
  HEAD (`:event-id`). Dispatch, dispatch-sync, and subscribe stay
  exact-incarnation fenced and still throw after EXACTLY ONE observability
  fan-out. The absent-frame and destroyed-without-successor paths fail closed
  identically.

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both run it. Plain
  CLJC; no DOM dependency. The sibling `frame-ops-always-on-elision-prod-test`
  pins the same survival under `:advanced` + `goog.DEBUG=false`, where the dev
  trace (axis 2) is DCE'd and the always-on record is the sole surviving channel."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.elision              :as elision]
            [re-frame.error-emit           :as error-emit]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.privacy              :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.trace                :as trace]
            [re-frame.ui.frames            :as frames]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :init-fn       (fn [] (error-emit/clear-error-listeners!))})
  (fn [f]
    (frames/reset-frame-ops-cache!)
    (try (f) (finally (frames/reset-frame-ops-cache!)))))

(def ^:private fid :reinc/id)

;; The attempted event / query the stale bundle carries: a keyword head plus a
;; sensitive body. `:audit/secret` (head) is a structural identifier; the value
;; `:TOP-SECRET` is the payload that must NEVER egress under a successor policy.
(def ^:private secret-event [:audit/secret {:password :TOP-SECRET}])
(def ^:private secret-value :TOP-SECRET)

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- deep-contains?
  "True when `target` appears anywhere in `x` (as a key, value, or leaf) —
  the fail-closed proof walks the WHOLE record so a re-keyed leak cannot hide."
  [x target]
  (cond
    (= x target)         true
    (map? x)             (boolean (some (fn [[k v]] (or (deep-contains? k target)
                                                        (deep-contains? v target))) x))
    (sequential? x)      (boolean (some #(deep-contains? % target) x))
    (set? x)             (boolean (some #(deep-contains? % target) x))
    :else                false))

(defn- capture-emissions
  "Run `thunk` (expected to throw `:rf.error/frame-destroyed`), capturing the
  always-on error records (axis 1) AND the dev-trace error events (axis 2) it
  fans BEFORE the throw. Returns `{:records [...] :traces [...] :err-id <kw>}`.
  Freshly-gensym'd listener keys, unregistered on the way out."
  [thunk]
  (let [recs   (atom [])
        traces (atom [])
        ekey   (keyword "test" (name (gensym "reinc-rec")))
        tkey   (keyword "test" (name (gensym "reinc-trace")))]
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
;; The reincarnation composition (the bead's exact reproduction).
;; ---------------------------------------------------------------------------

(deftest stale-bundle-op-across-reincarnation-fails-closed
  (testing "Per rf2-01ihi: with DIVERGENT A/B policies — incarnation A OWNS a
            sensitive elision policy, successor B is unclassified/permissive — a
            stale bundle op fired after the same-id reincarnation redacts the
            attempted payload in the always-on record (never borrows B's policy),
            while retaining the category, captured frame id, operation, and the
            structural event/query head. Dispatch, dispatch-sync, and subscribe
            all fail closed, each throwing after EXACTLY ONE fan-out."
    (rf/reg-event :reinc/classify
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:secret] "A-owned")
         :sensitive [[:secret]]}))
    (rf/reg-sub :reinc/n (fn [db _] (:n db)))
    (doseq [[op invoke head]
            [[:dispatch      (fn [b] ((:dispatch b) secret-event))      :audit/secret]
             [:dispatch-sync (fn [b] ((:dispatch-sync b) secret-event)) :audit/secret]
             [:subscribe     (fn [b] ((:subscribe b) secret-event))     :audit/secret]]]
      (testing (str "stale " op " across reincarnation")
        (make-frame! fid {:n 1})
        (let [stale (rf/with-frame fid (frames/frame-ops))]
          ;; A owns a sensitive elision policy (create A with elision ownership).
          ((:dispatch-sync stale) [:reinc/classify])
          (is (contains? (elision/sensitive-declarations fid) [:secret])
              "incarnation A owns a non-empty sensitive elision policy")
          ;; Destroy A; reincarnate an UNCLASSIFIED successor B under the same id.
          (frame/destroy-frame! fid)
          (make-frame! fid {:n 41})
          (is (empty? (elision/sensitive-declarations fid))
              "successor B is unclassified — a permissive policy that must NOT be borrowed")
          ;; Fire A's stale bundle op into the same-id successor world.
          (let [{:keys [records traces err-id]} (capture-emissions #(invoke stale))]
            (is (= :rf.error/frame-destroyed err-id)
                "the stale op still fails loud with the canonical typed error")
            (is (= 1 (count records)) "EXACTLY ONE always-on record")
            (is (= 1 (count traces))  "EXACTLY ONE dev trace on axis 2")
            (let [r (first records)]
              (is (= :rf.error/frame-destroyed (:error r)) "category retained")
              (is (= fid (:frame r))                       "captured frame id retained")
              (is (= op (:op r))                           "operation retained")
              (is (= head (:event-id r))                   "structural event/query head retained")
              ;; THE FIX: the body is redacted at source — B's permissive policy
              ;; can never be borrowed to ship A's raw payload.
              (is (= privacy/redacted-sentinel (:event r))
                  ":event fails closed to the reserved sentinel, NOT B's identity walk")
              (is (not (deep-contains? r secret-value))
                  "the raw secret reaches the always-on record NOWHERE"))
            ;; The dev trace (axis 2) is fenced at the same source.
            (is (not (some #(deep-contains? % secret-value) traces))
                "the raw secret reaches the dev trace NOWHERE either")))))))

;; ---------------------------------------------------------------------------
;; The sibling dead-incarnation paths fail closed identically.
;; ---------------------------------------------------------------------------

(deftest destroyed-without-successor-fails-closed
  (testing "Per rf2-01ihi: the ordinary destroyed-WITHOUT-successor path fails
            closed too — no successor exists, the frame is unresolvable, and the
            attempted payload is redacted rather than shipped."
    (rf/reg-event :reinc/set-n (fn [_ [_ n]] {:db {:n n}}))
    (make-frame! fid {:n 1})
    (let [stale (rf/with-frame fid (frames/frame-ops))]
      (frame/destroy-frame! fid)                 ;; no reincarnation
      (let [{:keys [records err-id]}
            (capture-emissions #((:dispatch-sync stale) secret-event))]
        (is (= :rf.error/frame-destroyed err-id))
        (is (= 1 (count records)))
        (let [r (first records)]
          (is (= :audit/secret (:event-id r)) "structural head retained")
          (is (= :dispatch-sync (:op r)))
          (is (= privacy/redacted-sentinel (:event r))
              ":event fails closed even with no successor to borrow from")
          (is (not (deep-contains? r secret-value))
              "the raw secret reaches the record NOWHERE"))))))

(deftest absent-capture-fails-closed
  (testing "Per rf2-01ihi: the absent-frame `(frame)` CAPTURE arm carries no
            payload, so there is nothing to leak — it fails closed with the
            :capture attribution and a payload-free record."
    (let [{:keys [records err-id]}
          (capture-emissions
            #(binding [frame/*current-frame* :reinc/ghost] (frames/frame-ops)))]
      (is (= :rf.error/frame-destroyed err-id))
      (is (= 1 (count records)))
      (let [r (first records)]
        (is (= :reinc/ghost (:frame r)))
        (is (= :capture (:op r)) ":op marks the capture-time failure")
        (is (not (deep-contains? r secret-value))
            "no payload, so no secret anywhere in the capture-arm record")))))
