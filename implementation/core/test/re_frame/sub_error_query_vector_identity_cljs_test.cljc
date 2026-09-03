(ns re-frame.sub-error-query-vector-identity-cljs-test
  "rf2-px0i9 — honor raw query-vector IDENTITY on the always-on production
  sub-error egress, per the #6441 / rf2-zwgqe ruling.

  RULED (option c, accepted fail-open, documented): a subscription query
  vector is IDENTITY — the sub-cache key (Spec 006), the skip-dedup key, the
  reactive-graph edge endpoint. It is structurally public and NEVER redacted at
  the classification chokepoint; it egresses VERBATIM on every
  query-vector-bearing slot, INCLUDING the always-on error `:query-v` / `:event`
  slots. The only retained exception is the SEPARATE Spec 010 schema-axis
  backstop (a `:sensitive?`-schema'd sub's validation-failure trace whole-slot
  scrubs `:rf.sub/query-v` in `re-frame.schemas.validate`) — a different path,
  untouched here.

  THE DEFECT this file pins closed: `rf.error-emit/dispatch-on-error!` ran every
  `:event` argument through the frame's durable app-db elision registry. For a
  dispatched event that is payload hygiene; for a SUB error `:event` is the
  query vector, so a COINCIDENTAL concrete integer app-db path (`[1]`) redacted
  a query-vector coordinate — `[:patient/record \"SECRET\"]` became
  `[:patient/record :rf/redacted]` — mutating identity at egress. The generic
  walker is CORRECT (integer paths matching vector coordinates is intended,
  position-precise app-db elision — `elision_test.clj` pins it and it stays);
  the fix is that the always-on error CALLER must SKIP elision for a
  query-vector `:event`.

  PROOF DISCIPLINE. Each defect test asserts the OBSERVABLE payload the
  consumer receives on BOTH production egress routes — the corpus-wide
  `register-error-listener!` record AND the frame-owned `:observability :errors`
  sink — and, on the SAME frame + declaration + input, asserts the generic
  walker STILL redacts (so the redaction machinery is intact and WOULD have
  bitten — the fix is the caller skipping it, not a broken walker). The
  regression pins show a genuine DISPATCHED-EVENT error still gets its app-db
  elision on both routes.

  Dual-runtime `*_cljs_test.cljc`: the shadow-cljs `:node-test`
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Plain CLJC; no DOM dependency, no `identical?` on keyword literals."
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

;; ---------------------------------------------------------------------------
;; Shared harness. A LIVE frame `:probe` that (a) classifies the concrete
;; integer app-db path `[1]` sensitive through the EP-0025 commit-plane effect
;; path (the durable frame annotation is removed; this is the canonical durable
;; classification the bead's reproduction names), and (b) declares an `:errors`
;; observability sink so BOTH production egress routes are live. A corpus-wide
;; listener and the sink both record every error they see.
;; ---------------------------------------------------------------------------

(def ^:private secret "SECRET")
(def ^:private query-v [:patient/record secret])  ;; the sub's query vector — IDENTITY

(defn- install-probe!
  "Register a corpus-wide error listener under `:px0i9/listener` and an
  `:errors` sink under `:px0i9/sink`, make the live `:probe` frame declaring
  that sink, and classify the concrete integer path `[1]` sensitive on it.
  Returns `[listener-seen sink-seen]` atoms."
  []
  (let [listener-seen (atom [])
        sink-seen     (atom [])]
    (rf/register-observability-sink! :px0i9/sink
                                     (fn [record] (swap! sink-seen conj record)))
    (rf/register-listener! :errors :px0i9/listener
                           (fn [record] (swap! listener-seen conj record)))
    (rf/make-frame {:id :probe
                    :observability
                    {:errors [{:sink :px0i9/sink
                               :rf.egress/profile :rf.egress/off-box-observability}]}})
    ;; Durable app-db classification at the CONCRETE integer path `[1]` — the
    ;; coordinate that coincidentally matches query-vector position 1.
    (rf.frame/swap-runtime-db! :probe
      (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[1]]})))
    [listener-seen sink-seen]))

(defn- emit!
  "Drive `rf.error-emit/dispatch-on-error!` exactly as a production error site
  does — positional `[error-kw event event-id frame exception elapsed-ms time
  attrs]`. `:time` is a fixed literal (never asserted — it is performance.now()
  on CLJS, variable-length)."
  ([error-kw event event-id] (emit! error-kw event event-id nil))
  ([error-kw event event-id attrs]
   (rf.error-emit/dispatch-on-error!
     error-kw event event-id :probe nil 0 1000 attrs)))

(defn- only [xs] (is (= 1 (count xs))) (first xs))

;; ---------------------------------------------------------------------------
;; 1. The headline reproduction — :rf.error/sub-exception, BOTH routes.
;; ---------------------------------------------------------------------------

(deftest sub-exception-query-vector-egresses-raw-on-both-routes
  (testing "the bead's exact reproduction: a durable classification at `[1]`,
            `:rf.error/sub-exception` with `[:patient/record \"SECRET\"]`. The
            generic walker STILL redacts the coordinate (machinery intact), yet
            BOTH production egress routes ship the query vector VERBATIM."
    (let [[listener-seen sink-seen] (install-probe!)]
      ;; The generic walker redacts the coincidental integer coordinate — this
      ;; is the value dispatch-on-error! USED to ship (and must no longer).
      (is (= [:patient/record :rf/redacted]
             (rf.elision/elide-wire-value query-v {:frame :probe}))
          "the walker still redacts `[1]` — position-precise app-db elision is
           intact (elision_test.clj); the fix is the CALLER skipping it")
      (emit! :rf.error/sub-exception query-v :patient/record)
      ;; Route 1 — corpus-wide always-on listener record.
      (let [r (only @listener-seen)]
        (is (= :rf.error/sub-exception (:error r)))
        (is (= :patient/record (:event-id r)))
        (is (= query-v (:event r))
            "corpus listener :event is the RAW query vector, VERBATIM (#6441)"))
      ;; Route 2 — frame-owned observability sink (projected, still raw).
      (let [r (only @sink-seen)]
        (is (= :rf.observe/error (:kind r)))
        (is (= :rf.error/sub-exception (:error r)))
        (is (= query-v (:event r))
            "observability sink :event is the RAW query vector, VERBATIM (#6441)")
        (is (not (contains? r :re-frame.projection/raw-event?))
            "the internal raw-event? marker is dropped from the projected record")))))

;; ---------------------------------------------------------------------------
;; 2. Every query-vector-bearing category — enumerated STRUCTURALLY, not by a
;;    `sub-*` prefix. The structural enumeration is the point and it is what
;;    rf2-s3n6h bought: it once carried three internal-observation-port
;;    categories a `sub-*` prefix check would have missed, and those went with
;;    the port on 2026-08-21 (rf2-63t1i). The realm-ambiguous
;;    `:rf.error/frame-destroyed` in section 3 below is the surviving member a
;;    prefix check still misses.
;; ---------------------------------------------------------------------------

(deftest every-query-vector-category-egresses-raw
  (testing "every category whose `:event` is a query vector ships it VERBATIM
            on both routes (rf2-s3n6h)."
    (doseq [cat [:rf.error/sub-input-fn-exception
                 :rf.error/sub-input-fn-bad-return
                 :rf.error/sub-exception
                 :rf.error/no-such-sub]]
      (let [[listener-seen sink-seen] (install-probe!)]
        (emit! cat query-v :patient/record)
        (is (= query-v (:event (only @listener-seen)))
            (str cat " — corpus listener :event VERBATIM"))
        (is (= query-v (:event (only @sink-seen)))
            (str cat " — observability sink :event VERBATIM"))
        ;; isolate the sinks/listeners between categories
        (rf/unregister-observability-sink! :px0i9/sink)
        (rf.error-emit/clear-error-listeners!)
        (rf/destroy-frame! :probe)))))

;; ---------------------------------------------------------------------------
;; 3. The realm-attributed :rf.error/frame-destroyed subscription case — a
;;    prefix check misses it; the fix keys on the `:subscribe` operation realm.
;; ---------------------------------------------------------------------------

(deftest frame-destroyed-subscribe-realm-egresses-raw-dispatch-still-elides
  (testing "`:rf.error/frame-destroyed` is realm-ambiguous: the `:subscribe`
            realm carries a QUERY VECTOR (raw identity) while `:dispatch`
            carries a dispatched EVENT (keeps elision). Both proven on the
            corpus route on the SAME classified live frame."
    (let [[listener-seen _] (install-probe!)]
      ;; subscribe realm — a captured/superseded subscribe against a live
      ;; successor: the query vector must ride VERBATIM.
      (emit! :rf.error/frame-destroyed query-v :patient/record {:op :subscribe})
      (is (= query-v (:event (only @listener-seen)))
          "frame-destroyed :subscribe :event is the RAW query vector, VERBATIM"))
    ;; dispatch realm — a dispatched event into a destroyed frame keeps its
    ;; per-path app-db elision (payload hygiene).
    (let [[listener-seen _] (install-probe!)]
      (emit! :rf.error/frame-destroyed [:some/event secret] :some/event {:op :dispatch})
      (is (= [:some/event :rf/redacted] (:event (only @listener-seen)))
          "frame-destroyed :dispatch :event keeps its app-db elision"))))

;; ---------------------------------------------------------------------------
;; 4. Regression — a genuine DISPATCHED-EVENT error still elides on BOTH routes.
;;    (You fixed sub-errors without breaking event hygiene.)
;; ---------------------------------------------------------------------------

(deftest dispatched-event-error-still-elides-on-both-routes
  (testing "a `:rf.error/handler-exception` (a dispatched-event error) still
            has its `[1]`-classified event arg elided on both egress routes —
            the query-vector exception did NOT weaken event payload hygiene."
    (let [[listener-seen sink-seen] (install-probe!)]
      (emit! :rf.error/handler-exception [:some/event secret] :some/event)
      (is (= [:some/event :rf/redacted] (:event (only @listener-seen)))
          "corpus listener still elides the dispatched event's sensitive arg")
      (is (= [:some/event :rf/redacted] (:event (only @sink-seen)))
          "observability sink still elides the dispatched event's sensitive arg"))))

;; ---------------------------------------------------------------------------
;; 5. The generic walker's integer-path-matches-coordinate behaviour is
;;    UNTOUCHED (the fix must not weaken position-precise app-db elision —
;;    elision_test.clj's own pin lives in JVM; this is the CLJC restatement).
;; ---------------------------------------------------------------------------

(deftest integer-path-still-matches-vector-coordinate-in-generic-walker
  (testing "`elide-wire-value` still redacts a concrete integer path against a
            vector coordinate — the position-precise behaviour is correct and
            preserved; only the always-on error CALLER opts out for a query
            vector."
    (let [_ (install-probe!)]
      (is (= [:some/event :rf/redacted]
             (rf.elision/elide-wire-value [:some/event secret] {:frame :probe}))
          "the walker redacts position 1 against decl `[1]` — unchanged"))))
