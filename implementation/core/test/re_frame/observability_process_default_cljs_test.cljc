(ns re-frame.observability-process-default-cljs-test
  "rf2-kuky.67 — the PROCESS-DEFAULT observability policy
  `(rf/configure! {:observability …})`, and the frameless / unresolved-owner
  routing it exists to carry.

  Two claims, and the second is why the first is structural rather than a
  convenience:

  1. **Declared once per process.** A multi-frame app restated its Sentry
     policy on every `make-frame` call. Precedence is PER STREAM: a frame
     that DECLARES a stream uses its own entries for it, one that OMITS the
     stream inherits the default's, and `{:errors []}` on a frame is that
     frame's opt-out. Exactly ONE source is consulted per record per stream,
     so a sink id named by both is invoked ONCE.

  2. **Records no frame policy can ever route.** Three producers stamp
     `:frame nil` BY CONSTRUCTION (`:rf.error/no-frame-context`, the
     pre-frame SSR hydration-parse arm of
     `:rf.error/malformed-hydration-payload`, hicasso's compute-sub
     `:rf.error/sub-exception`), and a record whose `:frame` no longer
     RESOLVES (a dissociated incarnation's teardown report) is in the same
     position. These reach the process default under an EXPLICITLY NIL
     governing frame — fail-closed, so tree slots project to `:rf/redacted`
     while summary ids stay intact — and a stale `:frame` id is kept as a
     diagnostic but NEVER re-resolved, so it can never reach a same-id
     successor's sink.

  Pins:

    (a) Q1/Q5 per-stream precedence: a frame with NO `:observability`
        inherits the default and is delivered to ONCE, projected under its
        OWN classification; a frame declaring `{:errors []}` opts out; a
        frame declaring the same sink id gets exactly ONE delivery; a frame
        declaring only `:handled-events` still inherits the default's
        `:errors`.
    (b) Q2 absent vs empty: `{:observability nil}` CLEARS; a malformed
        policy throws `:rf.error/bad-frame-classification` with
        `:where 'rf/configure!` at CALL time.
    (c) Q3 frameless: a `:frame nil` record reaches the default with tree
        slots redacted and summary ids intact — and a live UNRELATED ambient
        frame's policy is NOT consulted.
    (d) Q4 unresolved owner: a `route-frame? false` teardown report reaches
        the default and NOT a same-id successor's sink, keeping its stale
        `:frame` id as a diagnostic.
    (e) Q6/Q7: an explicit `:rf.egress/local-raw` entry on the default is
        the sanctioned cross-frame raw hook; a delivery to a REGISTERED
        default sink OWNS the record (a non-zero delivered-count), while an
        entry naming an unregistered sink does not.
    (f) `rf/current-config` reflects the declared default and omits it when
        none is declared.

  Dual-runtime `*_cljs_test.cljc`: the shadow-cljs `:node-test`
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))})
  ;; The process default is a `defonce` atom deliberately OUTSIDE the runtime
  ;; reset (a hot reload must not drop a production policy declared at boot),
  ;; so this suite clears it on BOTH sides of every test. Clearing only before
  ;; would leave the last test's default installed for whatever file the
  ;; runner reaches next, where it would silently satisfy a fail-closed pin.
  (fn [t]
    (rf.observability/clear-observability-default!)
    (try (t)
         (finally (rf.observability/clear-observability-default!)))))

(defn- redacted? [v] (= :rf/redacted v))

;; ===========================================================================
;; (a) Q1 per-stream precedence + Q5 exactly-one-source.
;; ===========================================================================

(deftest frame-without-policy-inherits-the-process-default
  (testing "rf2-kuky.67 Q1 — an error on a frame declaring NO :observability
            reaches the process-default sink ONCE, projected under THAT
            frame's classification (the frame is the governing frame even
            when the ENTRIES came from the default)."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf/make-frame {:id :obs.default/plain})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/test-union :frame :obs.default/plain :time 1})
      (is (= 1 (count @seen))
          "delivered exactly once from the process default")
      (let [r (first @seen)]
        (is (= :rf.observe/error (:kind r)))
        (is (= :obs.default/plain (:frame r))
            "the LIVE frame governs the projection, not a nil frame")
        (is (= :rf.error/test-union (:error r)))))))

(deftest frame-empty-stream-is-the-opt-out
  (testing "rf2-kuky.67 Q2 — `{:errors []}` on a FRAME declares the stream and
            names no sink, so it opts that frame OUT of the process default.
            Declaration is read by KEY PRESENCE, not truthiness."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf/make-frame {:id :obs.default/opted-out :observability {:errors []}})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/test-union :frame :obs.default/opted-out :time 1})
      (is (zero? (count @seen))
          "an empty stream on the frame routes NOTHING — not an inherit"))))

(deftest same-sink-in-both-sources-is-delivered-once
  (testing "rf2-kuky.67 Q5 — a sink id named by BOTH the frame's entries and
            the process default's is invoked ONCE. Exactly one policy source
            is consulted per record per stream, so the duplicate rule is a
            consequence of per-stream precedence rather than a de-dupe pass."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf/make-frame {:id :obs.default/both
                      :observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/test-union :frame :obs.default/both :time 1})
      (is (= 1 (count @seen))
          "one delivery, not two"))))

(deftest precedence-is-per-stream-not-whole-map
  (testing "rf2-kuky.67 Q1 — a frame declaring ONLY :handled-events still
            inherits the process default's :errors. Whole-map replacement is
            rejected: declaring one stream must not silently disable the
            other."
    (let [errors-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [r] (swap! errors-seen conj r)))
      (rf/register-observability-sink! :test.sinks/datadog (fn [_] nil))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf/make-frame {:id :obs.default/events-only
                      :observability
                      {:handled-events [{:sink :test.sinks/datadog}]}})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/test-union :frame :obs.default/events-only :time 1})
      (is (= 1 (count @errors-seen))
          ":errors still inherited even though :handled-events was declared"))))

;; ===========================================================================
;; (b) Q2 — clear, and fail-loud validation at CALL time.
;; ===========================================================================

(deftest explicit-nil-clears-the-default
  (testing "rf2-kuky.67 Q2 — `{:observability nil}` CLEARS the process
            default. Read by key PRESENCE: omitting the key leaves the
            default untouched, an explicit nil removes it."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf/make-frame {:id :obs.default/cleared})
      ;; An unrelated configure! call must NOT disturb the default.
      (rf/configure! {:elision {:rf.size/threshold-bytes 8192}})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/test-union :frame :obs.default/cleared :time 1})
      (is (= 1 (count @seen))
          "omitting the key left the default installed")
      (rf/configure! {:observability nil})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/test-union :frame :obs.default/cleared :time 2})
      (is (= 1 (count @seen))
          "an explicit nil cleared it — no second delivery"))))

(deftest malformed-default-fails-loud-at-configure-time
  (testing "rf2-kuky.67 Q2 — a malformed process default throws
            :rf.error/bad-frame-classification with :where 'rf/configure! at
            CALL time, under the SAME closed grammar make-frame applies. A
            policy that installed silently would only surface as a dropped
            record at the first sink fire."
    (let [ex (try (rf/configure! {:observability {:errors "x"}})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is (some? ex) "a non-vector stream is refused")
      (is (= :rf.error/bad-frame-classification (:rf.error/id (ex-data ex)))
          "the SAME category make-frame raises — one grammar, two doors")
      (is (= 'rf/configure! (:where (ex-data ex)))
          ":where names the door the author actually typed"))
    (testing "and the closed ENTRY grammar is enforced from this door too"
      (let [ex (try (rf/configure!
                      {:observability {:errors [{:sink :s :opts {:a 1}}]}})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
        (is (some? ex) "an unknown entry key is refused")
        (is (= 'rf/configure! (:where (ex-data ex))))))
    (testing "a refused call installs NOTHING"
      (is (nil? (rf.observability/current-observability-config))))))

;; ===========================================================================
;; (c) Q3 — the FRAMELESS records, and the ambient-frame property.
;; ===========================================================================

(deftest frameless-record-reaches-the-default-fail-closed
  (testing "rf2-kuky.67 Q3 — a `:frame nil` record (the shape
            :rf.error/no-frame-context and hicasso's compute-sub
            :rf.error/sub-exception carry BY CONSTRUCTION) reaches the
            process default, projected under an EXPLICITLY nil governing
            frame: tree slots are :rf/redacted, summary ids intact. Before
            the default it reached no sink at all."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/sentry}]}})
      (rf.error-emit/dispatch-error-record!
        {:error  :rf.error/no-frame-context
         :frame  nil
         :time   42
         :reason "no frame in scope"})
      (is (= 1 (count @seen)) "the frameless record reached the default sink")
      (let [r (first @seen)]
        (is (= :rf.error/no-frame-context (:error r))
            "summary id intact")
        (is (= 42 (:time r)) "summary slot intact")
        (is (nil? (:frame r)) "no frame is claimed for it")
        (is (redacted? (:tags r))
            "the tree-shaped :tags slot FAILS CLOSED under a nil governing
             frame — no frame's classification vouched for its contents")))))

(deftest a-live-ambient-frame-policy-is-not-consulted-for-a-frameless-record
  (testing "rf2-kuky.67 Q3 (the rf2-kuky.5 property) — with a live UNRELATED
            frame declaring its own :errors sink, a FRAMELESS record still
            goes only to the PROCESS DEFAULT. An ambient frame is not an
            owner: `:frame nil` says *this record has no governing frame*,
            and it must not fall through to whatever frame happens to be in
            scope."
    (let [default-seen (atom [])
          ambient-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/default
                                       (fn [r] (swap! default-seen conj r)))
      (rf/register-observability-sink! :test.sinks/ambient
                                       (fn [r] (swap! ambient-seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/default}]}})
      (rf/make-frame {:id :obs.default/ambient
                      :observability {:errors [{:sink :test.sinks/ambient}]}})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/no-frame-context :frame nil :time 1})
      (is (= 1 (count @default-seen)) "the process default received it")
      (is (zero? (count @ambient-seen))
          "the live ambient frame's policy was NOT consulted"))))

;; ===========================================================================
;; (d) Q4 — unresolved owner. The producer's authority bit, not an id test.
;; ===========================================================================

(deftest stale-owner-report-reaches-the-default-not-a-successor
  (testing "rf2-kuky.67 Q4 — a record emitted with `route-frame?` FALSE (an
            exact-incarnation teardown report, after that incarnation was
            dissociated) reaches the PROCESS DEFAULT and NOT the same-id
            successor's sink. The stale :frame id is KEPT as a diagnostic and
            never re-resolved: `frame` cannot tell a never-registered id from
            a dissociated one, so a successor would pass any id test — which
            is why the authority bit is carried from the producer."
    (let [default-seen   (atom [])
          successor-seen (atom [])]
      (rf/register-observability-sink! :test.sinks/default
                                       (fn [r] (swap! default-seen conj r)))
      (rf/register-observability-sink! :test.sinks/successor
                                       (fn [r] (swap! successor-seen conj r)))
      (rf/configure! {:observability {:errors [{:sink :test.sinks/default}]}})
      ;; A LIVE frame carrying the same id as the dead incarnation, with its
      ;; own sink — the same-id successor the pin refuses.
      (rf/make-frame {:id :obs.default/reborn
                      :observability {:errors [{:sink :test.sinks/successor}]}})
      (#'rf.error-emit/dispatch-error-record*
        {:error :rf.error/frame-teardown-failed
         :frame :obs.default/reborn
         :time  7}
        false)
      (is (zero? (count @successor-seen))
          "the same-id successor's sink NEVER sees the dead incarnation's report")
      (is (= 1 (count @default-seen))
          "the process default delivers it — `route-frame? false` means *no
           frame authority*, not *no sink route*")
      (let [r (first @default-seen)]
        (is (= :obs.default/reborn (:frame r))
            "the stale id is kept in the summary as a DIAGNOSTIC")
        (is (= :rf.error/frame-teardown-failed (:error r)))))))

;; ===========================================================================
;; (e) Q6 raw cross-frame hook + Q7 console-fallback ownership.
;; ===========================================================================

(deftest local-raw-profile-is-the-sanctioned-cross-frame-hook
  (testing "rf2-kuky.67 Q6 — an explicit `:rf.egress/local-raw` entry on the
            process default is the ONE sanctioned way to see records across
            frames unprojected. It is a projection PROFILE on the
            :rf.observe/* record, not a second routing model and not an
            additive global observer."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/raw
                                       (fn [r] (swap! seen conj r)))
      (rf/configure! {:observability
                      {:errors [{:sink :test.sinks/raw
                                 :rf.egress/profile :rf.egress/local-raw}]}})
      (rf.error-emit/dispatch-error-record!
        {:error :rf.error/no-frame-context :frame nil :time 1 :reason "r"})
      (is (= 1 (count @seen)))
      (is (not (redacted? (:tags (first @seen))))
          "the trusted-local profile keeps the tree slot, where the default
           off-box profile fails closed on a nil governing frame"))))

(deftest a-registered-default-sink-owns-the-record
  (testing "rf2-kuky.67 Q7 — the console fallback (rf2-kuky.18) keys on the
            DELIVERED count, so a process-default delivery to a REGISTERED
            sink owns the record exactly as a frame's policy would, while an
            entry naming an UNREGISTERED sink does not. Declaring a policy is
            not routing."
    (rf/configure! {:observability {:errors [{:sink :test.sinks/never-wired}]}})
    (is (zero? (rf.observability/route-error-record!
                 {:error :rf.error/no-frame-context :frame nil :time 1}))
        "an unregistered sink id delivers nothing and owns nothing")
    (rf/register-observability-sink! :test.sinks/never-wired (fn [_] nil))
    (is (= 1 (rf.observability/route-error-record!
               {:error :rf.error/no-frame-context :frame nil :time 1}))
        "once registered, the process default OWNS the record")))

;; ===========================================================================
;; (f) The read twin.
;; ===========================================================================

(deftest current-config-reflects-the-process-default
  (testing "rf2-kuky.67 — `rf/current-config` reports the declared default
            verbatim, and OMITS the key when none is declared (absent, never
            a fabricated nil — this fn's standing rule)."
    (is (not (contains? (rf/current-config) :observability))
        "absent before anything is declared")
    (let [policy {:errors [{:sink :test.sinks/sentry}]}]
      (rf/configure! {:observability policy})
      (is (= policy (:observability (rf/current-config)))
          "reported verbatim — no frame resolution, no synthesised defaults")
      (rf/configure! {:observability nil})
      (is (not (contains? (rf/current-config) :observability))
          "absent again once cleared"))))
