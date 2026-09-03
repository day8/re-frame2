(ns re-frame.capture-frame-reincarnation-sink-route-cljs-test
  "rf2-qjfrw — keep a stale `rf/capture-frame` op's dead-incarnation failure OUT of
  the same-id SUCCESSOR frame's own `:observability :errors` sink. The capture-realm
  residual of rf2-bf0io (which fixed the compiled `re-frame.ui` `(frame)` bundle).

  `rf/capture-frame` is EXACT-INCARNATION authority: an op invoked on a bundle
  captured for incarnation A, after A is destroyed, RECOVER-but-EMITs
  `:rf.error/frame-destroyed` (never leaks into a same-id successor). The emit
  fans out on TWO channels — the corpus-wide always-on record (axis 1) and the
  dev trace (axis 2). But the corpus emit ALSO drove the EP-0015 §9 frame-OWNED
  `:observability :errors` sink route, which resolves the record's bare frame id
  to the CURRENT frame. When a same-id SUCCESSOR B has replaced the destroyed A,
  that bare id resolves to B — so A's stale-op failure was delivered into B's OWN
  error sink (the bead's repro: B's sink receives A's `:rf.error/frame-destroyed`).
  A dead incarnation must NOT reach a live frame's sink (frame isolation;
  exact-incarnation attribution).

  The fix reuses rf2-bf0io's internal `route-frame?` seam at BOTH capture rejection
  sites:
    - the SYNCHRONOUS supersession PRE-CHECK (`capture-target-superseded?` →
      `router/emit-captured-frame-superseded!` → `router/emit-frame-destroyed!`,
      which now passes `route-frame?` false whenever a captured-op `:op` is
      present), and
    - the durable LATE expected-incarnation-mismatch fences in `dispatch!` /
      `dispatch-sync!` (router) and `subscribe-in-frame` (subs — via
      `emit-frame-destroyed-recovery!`, which now takes an explicit
      `route-frame?`).
  The corpus-wide record and the dev trace still fire EXACTLY ONCE; only the
  frame-owned sink route is suppressed. Ordinary address-directed errors keep the
  default route.

  These tests pin, for `:dispatch` / `:dispatch-sync` / `:subscribe`:
    - the REINCARNATION regression through the PRE-CHECK seam: A's stale captured
      op never increments same-id B's `:errors` sink (red-before: it does;
      after: 0), with a vacuity probe proving B's sink is genuinely armed;
    - the same regression through the LATE expected-incarnation-mismatch seam
      (the op passes the pre-check, then A is superseded by B before the bare-id
      resolve — reproduced by a one-shot interposition on the pre-check's own
      liveness read);
    - PRESERVED: exactly one corpus-wide `:rf.error/frame-destroyed` record +
      one dev trace still fire; the surviving record keeps A's bare frame id,
      `:op` realm, and structural head; a subscribe keeps RAW query identity;
    - LIVE routing intact: an ordinary handler-exception on a LIVE frame still
      reaches its frame-owned sink (the default `route-frame?` path is untouched).

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both run it. Plain
  CLJC; no DOM dependency. The interposition redefines the PUBLIC
  `rf.frame/frame-incarnation-live?` (a plain `defn`), so `with-redefs` intercepts
  the cross-namespace pre-check call on both hosts.

  ## Posture split (rf2-d2841)

  This file is almost entirely ALWAYS-ON already, because the bead it pins is
  itself an always-on concern: the EP-0015 §9 frame-owned `:errors` sink route
  and the corpus-wide error record (axis 1) both survive
  `-Dre-frame.debug=false`. The regression — B's sink staying clean — the
  recovery, the attribution on the surviving record, and the vacuity probe that
  proves B's sink is armed therefore ALL run under
  `scripts/test-core-prod-gate.sh` unchanged.

  Exactly ONE assertion is posture-dependent: `(= 1 (count traces))`, the axis-2
  DEV TRACE counterpart in `assert-preserved-record!`. It sits inside a
  `(when rf.interop/debug-enabled? …)` arm. Note what does NOT need one — the
  `(zero? (count @b-sink))` negative, which would be the textbook vacuous pass
  if the sink were dev-gated. It is not, and `probe-vacuity!` proves it by
  landing a real record in the same sink immediately afterwards. That probe is
  the shape worth copying."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core          :as rf]
            [re-frame.error-emit    :as rf.error-emit]
            [re-frame.frame         :as rf.frame]
            [re-frame.interop       :as rf.interop]
            [re-frame.observability :as rf.observability]
            [re-frame.privacy       :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support  :as rf.test-support]
            [re-frame.trace         :as rf.trace]))

;; Rebuild registrar / frames / runtime per test; additionally clear the corpus
;; error-listener registry AND the observability sink registry so a listener /
;; sink from one test cannot leak into the next.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.substrate.plain-atom/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.error-emit/clear-error-listeners!)
                      (rf.observability/clear-observability-sinks!))}))

;; The attempted event / query the stale captured op carries: a keyword head plus
;; a distinctive body leaf, so a leak into a sink would be unmistakable.
(def ^:private secret-event    [:audit/secret {:password :TOP-SECRET}])
(def ^:private subscribe-query [:reinc/n {:token :SUBSCRIBE-IDENTITY}])

;; The three captured ops under test, each invoking its arm of a captured bundle.
;; `head` is the structural event/query head that must ride the surviving record;
;; `raw-event` is the corpus `:event` we can pin (subscribe keeps RAW identity per
;; #6497/#6516; dispatch/dispatch-sync `:event` is elision-dependent and NOT
;; asserted here — the route suppression this bead adds does not touch it).
(def ^:private op-cases
  [{:op :subscribe     :invoke (fn [h] ((:subscribe h)     subscribe-query))
    :head :reinc/n      :raw-event subscribe-query}
   {:op :dispatch      :invoke (fn [h] ((:dispatch h)      secret-event))
    :head :audit/secret :raw-event ::unchecked}
   {:op :dispatch-sync :invoke (fn [h] ((:dispatch-sync h) secret-event))
    :head :audit/secret :raw-event ::unchecked}])

(defn- register-event+sub!
  "Register the trivial event / sub the captured ops target, so a LEAK into
  successor B would actually resolve a handler / reaction (making the zero a real
  suppression, not an unresolved miss)."
  []
  (rf/reg-event :audit/secret (fn [{:keys [db]} _] {:db (assoc db :marked-by :stale-capture)}))
  (rf/reg-sub   :reinc/n      (fn [db _] (:n db))))

(defn- make-frame-with-error-sink!
  "Create frame `id` declaring an `:observability :errors` sink named `sink-id`,
  and register the concrete sink fn (conj'ing every record it receives into
  `seen`). Returns `id`."
  [id sink-id seen]
  (rf.observability/register-observability-sink! sink-id (fn [r] (swap! seen conj r)))
  (rf/make-frame {:id id
                  :observability {:errors [{:sink sink-id
                                            :rf.egress/profile :rf.egress/off-box-observability}]}})
  id)

(defn- capture-emits
  "Run `thunk` (a stale captured op — RECOVERS, never throws), capturing the
  corpus-wide always-on `:rf.error/frame-destroyed` records (axis 1) AND the
  dev-trace frame-destroyed events (axis 2) it fans. Returns `{:records [...]
  :traces [...] :result <thunk-return>}`. Freshly-gensym'd listener keys,
  unregistered on the way out."
  [thunk]
  (let [recs   (atom [])
        traces (atom [])
        ekey   (keyword "test" (name (gensym "qjfrw-rec")))
        tkey   (keyword "test" (name (gensym "qjfrw-trace")))]
    (rf.error-emit/register-error-listener!
      ekey (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! recs conj r))))
    (rf.trace/register-listener!
      tkey (fn [ev] (when (= :rf.error/frame-destroyed (:operation ev)) (swap! traces conj ev))))
    (try
      (let [result (thunk)]
        {:records @recs :traces @traces :result result})
      (finally
        (rf.error-emit/unregister-error-listener! ekey)
        (rf.trace/unregister-listener! tkey)))))

(defn- assert-preserved-record!
  "The route suppression must NOT drop the corpus record or the dev trace, nor
  mutate the surviving record's attribution. Exactly one of each still fires,
  carrying A's bare captured frame id, the `:op` realm, and the structural head."
  [{:keys [records traces]} op fid head raw-event]
  (is (= 1 (count records)) "EXACTLY ONE corpus-wide :rf.error/frame-destroyed record still fans")
  ;; rf2-d2841 — axis 2 is the DEV trace and emits nothing under
  ;; -Dre-frame.debug=false. Axis 1, asserted above and picked apart below, is
  ;; the production-survivable channel and the one this bead's regression
  ;; actually lives on, so the rest of this fn stays outside the arm.
  (when rf.interop/debug-enabled?
    (is (= 1 (count traces))  "EXACTLY ONE dev trace on axis 2 still fires"))
  (let [r (first records)]
    (is (= :rf.error/frame-destroyed (:error r))    "corpus category retained")
    (is (= fid (:frame r))                          "corpus record carries A's captured bare frame id")
    (is (= op  (:op r))                             "operation realm retained")
    (is (= head (:event-id r))                      "structural event/query head retained")
    (when (not= ::unchecked raw-event)
      (is (= raw-event (:event r))
          "subscribe keeps RAW query identity on the surviving corpus record (#6497/#6516)"))))

(defn- probe-vacuity!
  "B's sink IS armed and capable of receiving: an ordinary error routed DIRECTLY
  to B lands in it — so the zero above is REAL suppression, not an unwired /
  mis-declared sink. Mirrors the rf2-bf0io vacuity probe."
  [fid b-sink]
  (let [before (count @b-sink)]
    (rf.observability/route-error!
      :rf.error/handler-exception [:qjfrw/probe] :qjfrw/probe fid nil 0 0 nil)
    (is (= (inc before) (count @b-sink))
        "B's sink IS live — a directly-routed ordinary error reaches it")))

;; ---------------------------------------------------------------------------
;; Seam 1 — the SYNCHRONOUS supersession PRE-CHECK.
;;
;; Destroy A, reseat same-id B (with an armed sink), THEN invoke A's stale
;; captured op: `capture-target-superseded?` sees A's pin gone and recover-but-
;; emits via `router/emit-captured-frame-superseded!`.
;; ---------------------------------------------------------------------------

(deftest stale-capture-precheck-never-reaches-successor-error-sink
  (testing "Per rf2-qjfrw (pre-check seam): a captured op whose pinned incarnation
            A was destroyed and reseated as same-id B fails the synchronous
            pre-check and recover-but-emits — but B's OWN :errors sink stays CLEAN
            (red before: B's sink count is 1 with A's stale op). The dead
            incarnation's bare frame id must never resolve to the live successor's
            sink."
    (doseq [{:keys [op invoke head raw-event]} op-cases]
      (testing (str "stale " (name op) " across a same-id reincarnation (pre-check)")
        (register-event+sub!)
        (let [fid     (keyword "qjfrw.pre" (str "id-" (name op)))
              sink-id (keyword "qjfrw.pre.sinks" (str "sentry-" (name op)))
              b-sink  (atom [])]
          ;; A: create + capture its incarnation-fenced bundle, then destroy.
          (rf/make-frame {:id fid})
          (let [stale (rf/capture-frame fid)]              ; pins incarnation A
            (rf/destroy-frame! fid)
            ;; B: same id, declaring its OWN :errors sink (armed BEFORE the op).
            (make-frame-with-error-sink! fid sink-id b-sink)
            (is (some? (rf.frame/frame fid)) "successor B is live under the reused id")
            ;; Fire A's stale captured op into the same-id successor world.
            (let [emits (capture-emits #(invoke stale))]
              ;; THE REGRESSION: B's OWN sink must stay clean.
              (is (zero? (count @b-sink))
                  "A's dead-incarnation failure NEVER reaches successor B's :errors sink")
              ;; PRESERVED: one corpus record + one dev trace, unchanged attribution.
              (assert-preserved-record! emits op fid head raw-event)
              ;; RECOVERY: the op returns nil and never mutates B.
              (is (nil? (:result emits)) "the stale captured op recovers to nil")
              (is (nil? (:marked-by (rf/app-db-value fid)))
                  "the stale captured op mutated nothing in successor B"))
            ;; VACUITY: B's sink is genuinely armed.
            (probe-vacuity! fid b-sink)))))))

;; ---------------------------------------------------------------------------
;; Seam 2 — the durable LATE expected-incarnation MISMATCH fence.
;;
;; A one-shot interposition on the pre-check's own liveness read destroys A and
;; reseats same-id B (with an armed sink) at the moment the pre-check validates A
;; as live — so the op PASSES the pre-check, then resolves the bare id to B and
;; hits the late fence (`dispatch!` / `dispatch-sync!` A→B mismatch;
;; `subscribe-in-frame` `superseded?`). This is the exact JVM interleaving the
;; pre-check seam cannot reach; single-threaded + deterministic (the swap runs
;; inside the interposed call), so no latch.
;; ---------------------------------------------------------------------------

(defn- run-superseded-after-precheck
  "Interpose ONE-SHOT on `rf.frame/frame-incarnation-live?` (the predicate the
  capture pre-check consults): when the pre-check validates incarnation `a-token`
  of `frame-id` as live, run `(make-b!)` (destroy A + reseat same-id B with an
  armed sink) BEFORE handing back A's (true) liveness, then run `op`. The
  interposition fires exactly once (the pre-check); every later call — including
  the destroy/create machinery's own — delegates to the real fn."
  [frame-id a-token make-b! op]
  (let [real  rf.frame/frame-incarnation-live?
        fired (atom false)]
    (with-redefs [rf.frame/frame-incarnation-live?
                  (fn [id token]
                    (let [live? (real id token)]
                      (when (and (not @fired)
                                 (= id frame-id)
                                 (identical? token a-token)
                                 live?)
                        (reset! fired true)   ;; set BEFORE make-b! so the
                        ;; destroy/create's own liveness reads take the real path
                        (make-b!))
                      live?))]
      (op))))

(deftest stale-capture-late-mismatch-never-reaches-successor-error-sink
  (testing "Per rf2-qjfrw (late expected-incarnation-mismatch seam): a captured op
            that PASSES the pre-check, then loses A to a same-id successor B before
            the bare-id resolve, recover-but-emits at the late fence — but B's OWN
            :errors sink stays CLEAN (red before: B's sink receives A's failure).
            Reproduces the concurrent-JVM destroy-A/create-B window deterministically."
    (doseq [{:keys [op invoke head raw-event]} op-cases]
      (testing (str "stale " (name op) " across a same-id reincarnation (late mismatch)")
        (register-event+sub!)
        (let [fid     (keyword "qjfrw.late" (str "id-" (name op)))
              sink-id (keyword "qjfrw.late.sinks" (str "sentry-" (name op)))
              b-sink  (atom [])]
          (rf/make-frame {:id fid})
          (let [a-token (rf.frame/frame-incarnation-token fid)
                stale   (rf/capture-frame fid)             ; pins incarnation A
                make-b! (fn []
                          (rf/destroy-frame! fid)
                          (make-frame-with-error-sink! fid sink-id b-sink))
                emits   (capture-emits
                          #(run-superseded-after-precheck fid a-token make-b! (fn [] (invoke stale))))]
            (is (some? (rf.frame/frame fid)) "successor B is live under the reused id")
            ;; THE REGRESSION: the LATE fence routed nothing to B's own sink.
            (is (zero? (count @b-sink))
                "A's dead-incarnation failure NEVER reaches successor B's :errors sink (late fence)")
            ;; PRESERVED: one corpus record + one dev trace, unchanged attribution.
            (assert-preserved-record! emits op fid head raw-event)
            (is (nil? (:result emits)) "the stale captured op recovers to nil")
            (is (nil? (:marked-by (rf/app-db-value fid)))
                "the late-superseded captured op mutated nothing in successor B")
            ;; VACUITY: B's sink is genuinely armed.
            (probe-vacuity! fid b-sink)))))))

;; ---------------------------------------------------------------------------
;; PRESERVED — live routing is untouched: the default route-frame? path still
;; delivers an ordinary handler-exception to a LIVE frame's own sink.
;; ---------------------------------------------------------------------------

(deftest ordinary-live-error-still-reaches-frame-owned-sink
  (testing "the default route-frame? path is untouched (rf2-qjfrw suppresses ONLY
            the dead-incarnation capture seams): a handler-exception on a LIVE
            frame still routes ONE :rf.observe/error record to that frame's
            declared :observability :errors sink."
    (let [seen    (atom [])
          fid     :qjfrw/live
          sink-id :qjfrw.sinks/live-sentry]
      (make-frame-with-error-sink! fid sink-id seen)
      (rf/reg-event :qjfrw/boom {:frame fid}
        (fn [_ _] (throw (ex-info "kaboom" {:cause :test}))))
      (rf/dispatch-sync [:qjfrw/boom] {:frame fid})
      (is (= 1 (count @seen)) "the live frame's error sink still receives exactly one record")
      (let [r (first @seen)]
        (is (= :rf.observe/error (:kind r)))
        (is (= fid (:frame r)))
        (is (= :rf.error/handler-exception (:error r)))))))
