(ns re-frame.ui.frame-ops-cljs-test
  "rf2-vxgfnd.184 — the `(frame)` operation-bundle runtime bridge
  (`re-frame.ui.frames/frame-ops`), host-shared (.cljc: node `test:cljs` /
  `test:ui` AND JVM `clojure -M:test`) against the REAL core machinery:

    - the bundle is the standard capture-frame shape
      `{:frame :dispatch :dispatch-sync :subscribe}`, minted by core's
      `make-capture-frame` and locked to the committed frame;
    - identity is STABLE across repeated reads within one live frame
      incarnation (rf= memo-friendly; no per-read construction);
    - ops survive scope unwind (the HOLD semantics) yet are
      INCARNATION-FENCED: a destroyed frame — and a destroyed-then-
      re-created SAME-ID frame — fails loud with the canonical
      `:rf.error/frame-destroyed`, never a silent retarget;
    - no ambient frame → the canonical `:rf.error/no-frame-context`;
      an ambient stamp naming no live frame → `:rf.error/frame-destroyed`.

  The compiled-view placement grammar rides the analyzer suites
  (analyze-accept/-reject + the error roster); mounted/structural
  placement rides frame-ops-view-jvm-test and frame-ops-dom-cljs-test."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.error-emit           :as error-emit]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.trace                :as trace]
            [re-frame.ui.frames            :as frames]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            ;; no default ambient frame — the
                                            ;; no-frame-context negative needs
                                            ;; a genuinely scope-free world
                                            :ambient-frame nil
                                            ;; the always-on error-emit listener
                                            ;; registry is a `defonce` atom that
                                            ;; survives test re-runs — clear it
                                            ;; before each test so a recorder
                                            ;; from one test cannot leak into the
                                            ;; next (the observability legs below).
                                            :init-fn (fn [] (error-emit/clear-error-listeners!))})
  (fn [f]
    (frames/reset-frame-ops-cache!)
    (try (f) (finally (frames/reset-frame-ops-cache!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- reg! []
  (rf/reg-event :ops/set-n (fn [_ [_ n]] {:db {:n n}}))
  (rf/reg-sub :ops/n (fn [db _] (:n db))))

;; ===========================================================================
;; Bundle contents + committed-frame lock
;; ===========================================================================

(deftest bundle-shape-and-frame-lock
  (reg!)
  (make-frame! :ops/a {:n 1})
  (make-frame! :ops/b {:n 100})
  (let [b (rf/with-frame :ops/a (frames/frame-ops))]
    (testing "the standard capture-frame bundle keys"
      (is (= #{:frame :dispatch :dispatch-sync :subscribe} (set (keys b))))
      (is (= :ops/a (:frame b)) ":frame names the committed frame id")
      (is (fn? (:dispatch b)))
      (is (fn? (:dispatch-sync b)))
      (is (fn? (:subscribe b))))
    (testing "ops fire into the LOCKED frame after the scope has unwound"
      ((:dispatch-sync b) [:ops/set-n 7])
      (is (= {:n 7} (rf/app-db-value :ops/a)))
      (is (= {:n 100} (rf/app-db-value :ops/b)) "the sibling frame is untouched"))
    (testing "a per-call {:frame …} opt CANNOT override the lock"
      ((:dispatch-sync b) [:ops/set-n 9] {:frame :ops/b})
      (is (= {:n 9} (rf/app-db-value :ops/a)))
      (is (= {:n 100} (rf/app-db-value :ops/b))))
    (testing ":subscribe reads the locked frame"
      (is (= 9 @((:subscribe b) [:ops/n]))))))

;; ===========================================================================
;; Stable identity per live incarnation
;; ===========================================================================

(deftest bundle-identity-is-stable-within-one-incarnation
  (make-frame! :ops/stable {:n 0})
  (let [b1 (rf/with-frame :ops/stable (frames/frame-ops))
        b2 (rf/with-frame :ops/stable (frames/frame-ops))]
    (is (identical? b1 b2)
        "repeated reads return the IDENTICAL bundle — one map lookup, no
         per-render construction")
    (is (identical? (:dispatch b1) (:dispatch b2)))
    (is (identical? (:subscribe b1) (:subscribe b2)))))

(deftest bundles-are-per-frame
  (make-frame! :ops/x {})
  (make-frame! :ops/y {})
  (let [bx (rf/with-frame :ops/x (frames/frame-ops))
        by (rf/with-frame :ops/y (frames/frame-ops))]
    (is (not (identical? bx by)))
    (is (= :ops/x (:frame bx)))
    (is (= :ops/y (:frame by)))))

;; ===========================================================================
;; The canonical no-frame / dead-frame diagnostics
;; ===========================================================================

(deftest no-ambient-frame-fails-loud
  (is (= :rf.error/no-frame-context (err-id #(frames/frame-ops)))
      "no scope anywhere — the runtime never invents a frame"))

(deftest ambient-stamp-naming-no-live-frame-fails-loud
  (is (= :rf.error/frame-destroyed
         (err-id #(binding [frame/*current-frame* :ops/ghost]
                    (frames/frame-ops))))
      "a scope stamp naming an absent/destroyed frame is the
       registry-lookup category, not no-frame-context"))

;; ===========================================================================
;; Teardown + same-id replacement — the no-silent-retarget fence
;; ===========================================================================

(deftest destroyed-frame-ops-fail-loud
  (reg!)
  (make-frame! :ops/doomed {:n 1})
  (let [b (rf/with-frame :ops/doomed (frames/frame-ops))]
    (frame/destroy-frame! :ops/doomed)
    (is (= :rf.error/frame-destroyed (err-id #((:dispatch b) [:ops/set-n 2])))
        "a carried dispatch outliving its frame fails loud")
    (is (= :rf.error/frame-destroyed (err-id #((:dispatch-sync b) [:ops/set-n 2]))))
    (is (= :rf.error/frame-destroyed (err-id #((:subscribe b) [:ops/n]))))))

(deftest same-id-replacement-never-silently-retargets
  (reg!)
  (make-frame! :ops/reused {:n 1})
  (let [stale (rf/with-frame :ops/reused (frames/frame-ops))]
    (frame/destroy-frame! :ops/reused)
    (make-frame! :ops/reused {:n 41})
    (testing "the STALE bundle fails loud against the fresh same-id frame"
      (is (= :rf.error/frame-destroyed
             (err-id #((:dispatch-sync stale) [:ops/set-n 999])))
          "ops are locked to the exact incarnation they captured")
      (is (= {:n 41} (rf/app-db-value :ops/reused))
          "the replacement frame was not written by the stale bundle"))
    (testing "a fresh read returns a NEW working bundle"
      (let [fresh (rf/with-frame :ops/reused (frames/frame-ops))]
        (is (not (identical? stale fresh))
            "a new incarnation mints a new bundle identity")
        ((:dispatch-sync fresh) [:ops/set-n 42])
        (is (= {:n 42} (rf/app-db-value :ops/reused)))))))

;; ===========================================================================
;; rf2-vxgfnd.230 — every (frame) operation-bundle failure fans the canonical
;; :rf.error/frame-destroyed record to PRODUCTION observability (the always-on
;; error-emit axis, surface #4) BEFORE it throws.
;;
;; The bundle throw sites used to fail loud through a BARE `error/throw-error!`,
;; so the failure was visible ONLY on the thrown exception and (in dev) the
;; trace surface — a listener saw ZERO records, and under `goog.DEBUG=false`
;; (where the dev trace is DCE'd) a boundary that swallowed the throw made the
;; production failure vanish entirely. These legs pin the fan-out: exactly one
;; always-on record PLUS one dev trace per failure, carrying the frame, the
;; failing op, and the attempted event/query head. They are the mutation teeth
;; too — reverting any arm to a bare throw fans zero records and goes red.
;; ===========================================================================

(defn- capture-frame-destroyed-emissions
  "Run `thunk` (expected to throw `:rf.error/frame-destroyed`), capturing the
  always-on error records (axis 1) AND the dev-trace error events (axis 2) it
  fans BEFORE the throw. Returns `{:records [...] :traces [...] :err-id <kw>}`.
  Each call uses freshly-gensym'd listener keys and unregisters on the way out,
  so arms don't cross-contaminate."
  [thunk]
  (let [recs   (atom [])
        traces (atom [])
        ekey   (keyword "test" (name (gensym "fd-rec")))
        tkey   (keyword "test" (name (gensym "fd-trace")))]
    (error-emit/register-error-listener! ekey (fn [r] (swap! recs conj r)))
    (trace/register-listener! tkey
                              (fn [ev] (when (= :rf.error/frame-destroyed (:operation ev))
                                         (swap! traces conj ev))))
    (try
      ;; Run the thunk FIRST (it fires the emit), THEN snapshot the atoms — a
      ;; map literal would not guarantee the deref happens after the call.
      (let [caught (err-id thunk)]
        {:records @recs :traces @traces :err-id caught})
      (finally
        (error-emit/unregister-error-listener! ekey)
        (trace/unregister-listener! tkey)))))

(deftest stale-bundle-op-failures-fan-to-observability
  (testing "Per rf2-vxgfnd.230: a stale bundle op (captured for an incarnation
            that was destroyed after capture) fans EXACTLY ONE always-on record
            + ONE dev trace, carrying the frame / op / attempted head, THEN
            throws the canonical :rf.error/frame-destroyed."
    (reg!)
    (make-frame! :ops/doomed {:n 1})
    (let [b (rf/with-frame :ops/doomed (frames/frame-ops))]
      (frame/destroy-frame! :ops/doomed)
      (doseq [[op thunk head]
              [[:dispatch      #((:dispatch b) [:ops/set-n 2])      :ops/set-n]
               [:dispatch-sync #((:dispatch-sync b) [:ops/set-n 2]) :ops/set-n]
               [:subscribe     #((:subscribe b) [:ops/n])           :ops/n]]]
        (testing (str "stale " op)
          (let [{:keys [records traces err-id]} (capture-frame-destroyed-emissions thunk)]
            (is (= :rf.error/frame-destroyed err-id)
                "the op still fails loud with the canonical typed error")
            (is (= 1 (count records))
                "exactly ONE always-on record — was ZERO under the bare throw")
            (let [r (first records)]
              (is (= :rf.error/frame-destroyed (:error r)))
              (is (= :ops/doomed (:frame r)) ":frame names the destroyed frame")
              (is (= op (:op r)) ":op names the failing bundle operation")
              ;; :event fails closed to :rf/redacted under the unresolvable
              ;; (destroyed) frame; the structural :event-id head survives.
              (is (= head (:event-id r)) ":event-id is the attempted vector head"))
            (is (= 1 (count traces))
                "exactly ONE dev trace on the axis-2 surface")))))))

(deftest absent-capture-failure-fans-to-observability
  (testing "Per rf2-vxgfnd.230: a (frame) read resolving an ambient stamp that
            names no live incarnation (absent / destroyed / closing) follows the
            SAME exact-one contract — one always-on record + one dev trace, then
            the typed throw — attributed to the :capture arm."
    (let [{:keys [records traces err-id]}
          (capture-frame-destroyed-emissions
           #(binding [frame/*current-frame* :ops/ghost] (frames/frame-ops)))]
      (is (= :rf.error/frame-destroyed err-id))
      (is (= 1 (count records))
          "the absent/closing capture fans exactly one always-on record")
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= :ops/ghost (:frame r)) ":frame names the resolved-but-dead frame")
        (is (= :capture (:op r)) ":op marks the capture-time failure"))
      (is (= 1 (count traces)) "exactly one dev trace"))))

(deftest frame-destroyed-record-survives-a-swallowed-callback
  (testing "Per rf2-vxgfnd.230: the record is fanned BEFORE the throw, so a
            boundary that SWALLOWS the thrown error (a callback / view error
            boundary) still leaves the production breadcrumb — the whole point
            of routing through the always-on axis rather than the throw alone."
    (reg!)
    (make-frame! :ops/swallow {:n 1})
    (let [b    (rf/with-frame :ops/swallow (frames/frame-ops))
          recs (atom [])]
      (frame/destroy-frame! :ops/swallow)
      (error-emit/register-error-listener! :test/swallow-rec (fn [r] (swap! recs conj r)))
      (try
        (try ((:dispatch b) [:ops/set-n 2])
             (catch #?(:clj Throwable :cljs :default) _ :swallowed))
        (finally (error-emit/unregister-error-listener! :test/swallow-rec)))
      (is (= 1 (count @recs))
          "the always-on record survived the swallowing boundary")
      (is (= :rf.error/frame-destroyed (:error (first @recs)))))))

;; ===========================================================================
;; rf2-r79gr — the THROWN stale-op exception carries the structural event/query
;; HEAD in its `:extra` ex-data, so a synchronous catcher attributes the failing
;; op from the exception ALONE (without correlating the async always-on record).
;; The record already exposes the head as `:event-id` (rf2-01ihi's surviving
;; evidence); this leg pins the SAME head onto the thrown exception's ex-data.
;; Head ONLY: rf2-01ihi's fail-closed policy stays intact — the raw payload BODY
;; NEVER rides the exception. Reverting `:event-id` out of `:extra` goes red.
;; ===========================================================================

(defn- ex-data-of
  "Run `thunk` (expected to throw the canonical ExceptionInfo) and return the
  caught exception's ex-data, or nil if it did not throw."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- deep-contains?
  "True when `target` appears anywhere in `x` (key, value, or leaf) — the
  redaction proof walks the WHOLE ex-data so a re-keyed payload leak cannot hide."
  [x target]
  (cond
    (= x target)    true
    (map? x)        (boolean (some (fn [[k v]] (or (deep-contains? k target)
                                                   (deep-contains? v target))) x))
    (sequential? x) (boolean (some #(deep-contains? % target) x))
    (set? x)        (boolean (some #(deep-contains? % target) x))
    :else           false))

(deftest thrown-stale-op-exception-carries-the-head-body-redacted
  (testing "Per rf2-r79gr: the THROWN :rf.error/frame-destroyed exception carries
            the structural event/query HEAD as :extra :event-id (dispatch /
            dispatch-sync / subscribe), so a try/catch attributes the stale op
            from the exception alone. Head ONLY — rf2-01ihi keeps the raw payload
            BODY off the exception entirely."
    (rf/reg-event :ops/secret   (fn [_ _] {:db {}}))
    (rf/reg-sub   :ops/secret-q (fn [db _] db))
    ;; A sensitive body the exception must NEVER carry; only the head survives.
    (let [secret-body  {:password :TOP-SECRET}
          secret-value :TOP-SECRET]
      (doseq [[op invoke head]
              [[:dispatch      (fn [b] ((:dispatch b) [:ops/secret secret-body]))      :ops/secret]
               [:dispatch-sync (fn [b] ((:dispatch-sync b) [:ops/secret secret-body])) :ops/secret]
               [:subscribe     (fn [b] ((:subscribe b) [:ops/secret-q secret-body]))   :ops/secret-q]]]
        (testing (str "stale " op)
          (make-frame! :ops/doomed {})
          (let [b (rf/with-frame :ops/doomed (frames/frame-ops))]
            (frame/destroy-frame! :ops/doomed)
            (let [data (ex-data-of #(invoke b))]
              (is (= :rf.error/frame-destroyed (:rf.error/id data))
                  "the stale op fails loud with the canonical typed error")
              (is (= :ops/doomed (:frame data)) ":extra carries the captured frame id")
              (is (= op (:op data))             ":extra carries the failing op")
              ;; THE FIX (red before rf2-r79gr — :event-id absent from :extra):
              (is (= head (:event-id data))
                  ":extra carries the STRUCTURAL event/query head (rf2-r79gr)")
              ;; rf2-01ihi INTACT: only the head rides the exception, never the body.
              (is (not (deep-contains? data secret-body))
                  "the raw payload BODY never rides the thrown exception")
              (is (not (deep-contains? data secret-value))
                  "no sensitive leaf from the payload reaches the exception ex-data"))))))))

;; ===========================================================================
;; rf2-vxgfnd.231 — the cross-frame carried-subscribe HONESTY warning.
;;
;; A `(frame)` operation bundle captured under frame A can be CARRIED across a
;; frame boundary (the HOLD semantics) and its `:subscribe` invoked beneath a
;; DIFFERENT ambient frame B. Frames are ISOLATED contexts (no cross-frame
;; reads), and this carry is not claimed impossible — the honesty contract
;; (03 §8) holds the doctrine with a DEV diagnostic, never a false-impossibility
;; claim. When a carried subscribe runs under a FOREIGN ambient frame the runtime
;; emits `:rf.warning/cross-frame-carried-op` and CONTINUES against the CAPTURED
;; (origin) frame — advisory only, never a retarget or a refusal.
;;
;; Same-frame invocation (ambient == origin) and no-ambient / async invocation
;; (nothing foreign to compare against) stay QUIET. The warning is NARROW to
;; `:subscribe`: a carried `dispatch` / `dispatch-sync` across a boundary does
;; not warn. The id carries no `-once` suffix (contrast
;; `:rf.warning/plain-fn-under-non-default-frame-once`) — it is an advisory
;; warning emitted on EACH cross-frame invocation, not a warn-once nag.
;; ===========================================================================

(defn- capture-cross-frame-warnings
  "Run `thunk`, capturing every `:rf.warning/cross-frame-carried-op` dev trace
  it emits. Returns `{:warnings [...] :result <thunk return>}`. Freshly-gensym'd
  listener key, unregistered on the way out, so arms don't cross-contaminate."
  [thunk]
  (let [ws   (atom [])
        tkey (keyword "test" (name (gensym "xfco")))]
    (trace/register-listener!
     tkey (fn [ev] (when (= :rf.warning/cross-frame-carried-op (:operation ev))
                     (swap! ws conj ev))))
    (try
      (let [r (thunk)]
        {:warnings @ws :result r})
      (finally
        (trace/unregister-listener! tkey)))))

(deftest carried-subscribe-under-foreign-frame-warns-and-reads-origin
  (testing "a carried :subscribe invoked beneath a FOREIGN ambient frame emits
            exactly one :rf.warning/cross-frame-carried-op carrying the exact
            origin / ambient / query evidence, and STILL reads the captured
            (origin) frame — advisory, never a retarget"
    (reg!)
    (make-frame! :ops/origin {:n 11})
    (make-frame! :ops/foreign {:n 22})
    (let [b (rf/with-frame :ops/origin (frames/frame-ops))
          {:keys [warnings result]}
          (capture-cross-frame-warnings
           #(rf/with-frame :ops/foreign (deref ((:subscribe b) [:ops/n]))))]
      (is (= 1 (count warnings))
          "exactly one cross-frame-carried-op warning fired")
      (is (= 11 result)
          "the carried subscribe read the CAPTURED origin frame (11), not the
           foreign ambient frame (22) — the op continues against A")
      (let [tags (:tags (first warnings))]
        (is (= :ops/origin (:origin-frame tags))   ":origin-frame is the captured frame")
        (is (= :ops/foreign (:ambient-frame tags))  ":ambient-frame is the foreign frame")
        (is (= [:ops/n] (:rf.sub/query-v tags))     ":rf.sub/query-v is the attempted query")))))

(deftest carried-subscribe-same-frame-is-quiet
  (testing "A-under-A: a carried :subscribe invoked under its OWN captured frame
            (ambient == origin) emits no warning"
    (reg!)
    (make-frame! :ops/same {:n 5})
    (let [b (rf/with-frame :ops/same (frames/frame-ops))
          {:keys [warnings result]}
          (capture-cross-frame-warnings
           #(rf/with-frame :ops/same (deref ((:subscribe b) [:ops/n]))))]
      (is (empty? warnings) "ambient == origin — nothing foreign to warn about")
      (is (= 5 result)))))

(deftest carried-subscribe-no-ambient-is-quiet
  (testing "no-ambient / async: a carried :subscribe invoked with NO ambient
            frame in effect (a top-of-stack / async-hop caller) emits no warning
            — there is no foreign frame to compare against"
    (reg!)
    (make-frame! :ops/lone {:n 7})
    (let [b (rf/with-frame :ops/lone (frames/frame-ops))
          {:keys [warnings result]}
          (capture-cross-frame-warnings
           #(deref ((:subscribe b) [:ops/n])))]   ;; NO with-frame — no ambient scope
      (is (empty? warnings) "no ambient frame — quiet")
      (is (= 7 result)))))

(deftest carried-subscribe-cross-frame-warns-each-invocation
  (testing "the id carries no -once suffix: EACH cross-frame invocation surfaces
            the anti-pattern (advisory, not warn-once)"
    (reg!)
    (make-frame! :ops/o {:n 1})
    (make-frame! :ops/f {:n 2})
    (let [b (rf/with-frame :ops/o (frames/frame-ops))
          {:keys [warnings]}
          (capture-cross-frame-warnings
           #(rf/with-frame :ops/f
              (do (deref ((:subscribe b) [:ops/n]))
                  (deref ((:subscribe b) [:ops/n])))))]
      (is (= 2 (count warnings))
          "two cross-frame invocations → two advisory warnings"))))

(deftest carried-dispatch-cross-frame-does-not-warn
  (testing "the honesty warning is NARROW to :subscribe — a carried dispatch /
            dispatch-sync driven across a frame boundary does not warn (it drives
            its locked frame, which is a legitimate imperative use of the hold)"
    (reg!)
    (make-frame! :ops/od {:n 0})
    (make-frame! :ops/fd {:n 0})
    (let [b (rf/with-frame :ops/od (frames/frame-ops))
          {:keys [warnings]}
          (capture-cross-frame-warnings
           #(rf/with-frame :ops/fd ((:dispatch-sync b) [:ops/set-n 3])))]
      (is (empty? warnings)
          "carried dispatch across a boundary is not the subscribe honesty case")
      (is (= {:n 3} (rf/app-db-value :ops/od)) "the carried dispatch drove its locked frame"))))
