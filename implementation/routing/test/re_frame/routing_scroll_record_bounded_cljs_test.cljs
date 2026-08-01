(ns re-frame.routing-scroll-record-bounded-cljs-test
  "rf2-s3n6h — the always-on `:rf.error/unsupported-scroll-strategy` record is
  STRUCTURAL and BOUNDED.

  ## What rf2-2hkfy fixed, and what it left open

  rf2-2hkfy (#6376) fixed a real defect: the closed-vocabulary scroll
  rejection was emitted through `trace/emit-error!` alone, which DCEs under
  `:advanced` + `goog.DEBUG=false`, so the one configuration the branch exists
  to cover — a schemas-less PRODUCTION host — got no scroll and no record. It
  now fans through `error-emit/emit-error-both!`, and it must STAY that way:
  every assertion in this file is about the record's SHAPE, never about
  whether it fires. `re-frame.routing-scroll-always-on-elision-prod-test` owns
  the survives-production proof.

  What #6376 left open is what the record CARRIES. Its `record-attrs` copied
  the rejected `:strategy` verbatim and interpolated `(pr-str strategy)` into
  a `:reason` string. Both rode axis 1.

  That axis is not the dev trace. `dispatch-on-error!` passes the positional
  `:event` through `elision/elide-wire-value` — the per-path `:sensitive?` /
  `:large?` seam — but merges `record-attrs` UNCHANGED, and its own docstring
  contracts callers to keep those attributes to tight identifiers precisely
  because the listener registry is production-surviving and NOT privacy-gated.
  A `:rf.route/navigate` call's `:scroll` opt is per-call RUNTIME data, not
  necessarily static author configuration; on the schemas-less path it may be
  any map / string / collection / host value. So the rejected value bypassed
  the elision seam and rode off-box whole, with no bound.

  ## What is asserted here

  The two channels carry DIFFERENT payloads, deliberately:

    axis 2 (dev trace, DCE'd in prod) — the raw rejected value + rich prose
    axis 1 (always-on record, off-box) — structural, bounded, value-free

  The adversarial legs below drive a large / deeply nested / sentinel-bearing
  runtime value and assert that NO fragment of it appears ANYWHERE in the
  serialized record — a whole-record substring search, not a per-slot check,
  so a future slot that starts carrying the value goes red without anyone
  remembering to add an assertion for it.

  The final leg is a SEQUENCE, not a set of cases. A bound that is only ever
  checked against one big value is a static ceiling: it passes a single
  transition and still admits a later, larger one. So the sequence runs
  clean → small → huge → small → HUGER and pins the record's serialized size
  under a fixed ceiling at EVERY step, proving the bound holds across
  recovery and re-escalation rather than at one high-water mark."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.routing.scroll :as scroll]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                (error-emit/clear-error-listeners!)
                (scroll/reset-cache!))}))

;; ---- harness ---------------------------------------------------------------

(defn- record-always-on-errors!
  "Register a listener on the ALWAYS-ON (production-survivable) error axis and
  return the atom collecting its records."
  []
  (let [seen (atom [])]
    (rf/register-listener! :errors :bounded.scroll/recorder
                           (fn [record] (swap! seen conj record)))
    seen))

(defn- unsupported-records [records]
  (filterv #(= :rf.error/unsupported-scroll-strategy (:error %)) records))

(defn- reject!
  "Drive the handler's default branch with `strategy` (optionally under an
  originating `event`) and return the single always-on record."
  ([strategy] (reject! strategy nil))
  ([strategy event]
   (let [records (record-always-on-errors!)]
     (scroll/scroll-fx-handler
       (cond-> {:frame :bounded.scroll/frame} event (assoc :event event))
       {:strategy strategy})
     (let [errs (unsupported-records @records)]
       (is (= 1 (count errs))
           "exactly one always-on record — the rejection still fires (rf2-2hkfy)")
       (first errs)))))

;; A rejected value carrying a distinctive, greppable sentinel in EVERY
;; position a naive copy could pick it up from: map keys, map values, nested
;; collection members, and a long scalar leaf.
(def ^:private sentinel "ZZQQ-secret-payload-marker")

(defn- adversarial-strategy
  "A large, deeply nested runtime value of `width` top-level keys. Every leaf
  and every key embeds `sentinel`, so any copy of any FRAGMENT is detectable
  by a substring search of the serialized record."
  [width]
  (into {}
        (map (fn [i]
               [(keyword (str sentinel "-key-" i))
                {:nested {:deep [{:leaf (str sentinel "-leaf-" i)}
                                 #{(str sentinel "-set-" i)}]}
                 :blob   (str/join "" (repeat 40 sentinel))}]))
        (range width)))

(defn- record-size
  "Serialized size of the always-on record — the honest measure of what an
  off-box shipper pays and stores per rejection.

  `:time` is excluded, and ONLY `:time`. On CLJS `interop/now-ms` is
  `performance.now()`, an origin-relative high-resolution FLOAT whose printed
  length varies run to run (e.g. `987.2000000476837` vs `1234.5999999046326`).
  That jitter is unrelated to the rejected value, so leaving it in would couple
  a structural-invariance claim to a clock and make the assertion flaky in
  exactly the direction that teaches nothing. Every other slot is retained,
  including the ones a regression would grow."
  [record]
  (count (pr-str (dissoc record :time))))

;; ===========================================================================
;; (a) No fragment of the rejected value reaches the always-on record.
;; ===========================================================================

(deftest always-on-record-carries-no-fragment-of-the-rejected-value
  (testing "rf2-s3n6h: a large / nested runtime `:strategy` is rejected and NO
            fragment of it — key, value, nested member, or scalar leaf —
            appears ANYWHERE in the serialized always-on record. Checked
            against the WHOLE record rather than slot-by-slot, so a future
            slot that starts carrying the value goes red on its own"
    (let [record (reject! (adversarial-strategy 200))]
      (is (not (str/includes? (pr-str record) sentinel))
          "no fragment of the rejected value rides the production-surviving
           record — record-attrs bypass the elision seam, so the value must
           never enter them in the first place")
      (is (nil? (:strategy record))
          "no raw `:strategy` slot at all")
      (is (not (str/includes? (str (:reason record "")) sentinel))
          "`:reason` is not built by pr-str'ing the rejected value"))))

;; ===========================================================================
;; (b) What the record DOES carry is structural and closed-vocabulary.
;; ===========================================================================

(deftest always-on-record-is-structural
  (testing "rf2-s3n6h: the record keeps exactly the structural attribution —
            the supported vocabulary, the recovery, and a fixed
            closed-vocabulary type discriminator that cannot reproduce the
            value. `:strategy-type` reuses `re-frame.error/diag-value-summary`'s
            `:type` axis (one diagnostic vocabulary across surfaces) because
            the record wants a discriminator, not a size. When this test
            landed, taking only `:type` was also load-bearing: the summary's
            `:keys` leg was unbounded in map-key count and reproduced key
            content, and its `:head` leg reproduced a scalar's raw prefix.
            rf2-210uq removed both, so the summary is now content-free by
            construction and this assertion no longer depends on that"
    (let [record (reject! (adversarial-strategy 50))]
      (is (= [:top :restore :preserve] (:supported record))
          "the supported vocabulary is named — fixed size, author-independent")
      (is (= :no-scroll (:recovery record))
          ":recovery :no-scroll — navigation is unaffected, only the scroll")
      (is (= :map (:strategy-type record))
          ":strategy-type names the SHAPE the caller passed, not the value")
      (is (= :bounded.scroll/frame (:frame record)))
      (is (number? (:time record))))
    (testing "the discriminator is drawn from the closed shape vocabulary"
      (is (= :string  (:strategy-type (reject! (str/join "" (repeat 500 sentinel))))))
      (is (= :vector  (:strategy-type (reject! (vec (repeat 500 sentinel))))))
      (is (= :keyword (:strategy-type (reject! :nonsense))))
      (is (= :nil     (:strategy-type (reject! nil)))))))

;; ===========================================================================
;; (c) The dev trace keeps the raw value — the two-channel split is real.
;; ===========================================================================

(deftest dev-trace-retains-the-raw-value-and-rich-diagnosis
  (testing "rf2-s3n6h: bounding axis 1 must not blind local debugging. The
            dev-trace tags (axis 2, DCE'd under `:advanced` +
            `goog.DEBUG=false`) still carry the rejected value verbatim"
    (let [strategy (adversarial-strategy 3)
          traces   (atom [])
          cb-key   :bounded.scroll/trace-recorder]
      (trace-tooling/register-listener! cb-key (fn [ev] (swap! traces conj ev)))
      (try
        (scroll/scroll-fx-handler {:frame :bounded.scroll/frame}
                                  {:strategy strategy})
        (finally (trace-tooling/unregister-listener! cb-key)))
      (let [errs (filterv #(= :rf.error/unsupported-scroll-strategy (:operation %))
                          @traces)]
        (is (= 1 (count errs)) "exactly one dev trace")
        (let [tags (:tags (first errs))]
          (is (= strategy (:strategy tags))
              "the raw rejected value is retained for LOCAL debugging")
          (is (str/includes? (pr-str tags) sentinel)
              "…and so is its payload — this is the channel that may carry it")
          (is (string? (:reason tags))
              "the human diagnosis rides the dev trace"))))))

;; ===========================================================================
;; (d) Event elision is untouched by the record bounding.
;; ===========================================================================

(defn- reject-on-default!
  "As `reject!`, but against the fixture's REGISTERED `:rf/default` frame, so
  the positional `:event` walks a resolvable frame's elision policy rather than
  the unknown-frame fail-closed path."
  ([strategy] (reject-on-default! strategy nil))
  ([strategy event]
   (let [records (record-always-on-errors!)]
     (scroll/scroll-fx-handler
       (cond-> {:frame :rf/default} event (assoc :event event))
       {:strategy strategy})
     (first (unsupported-records @records)))))

(deftest event-attribution-and-elision-remain-intact
  (testing "rf2-s3n6h: bounding `record-attrs` must not disturb the positional
            `:event`, which reaches the record through
            `elision/elide-wire-value` — the documented seam. Attribution
            still names the originating navigation"
    (let [record (reject-on-default! :nonsense [:test/navigate-somewhere 42])]
      (is (= [:test/navigate-somewhere 42] (:event record))
          ":event still rides the record through the elision seam")
      (is (= :test/navigate-somewhere (:event-id record))
          ":event-id is still the event-vector head"))
    (let [record (reject-on-default! :nonsense)]
      (is (nil? (:event record))    "no event vector for a direct handler call")
      (is (nil? (:event-id record)) "no event-id for a direct handler call"))))

(deftest the-elision-seam-still-guards-the-event-slot-on-an-unknown-frame
  (testing "rf2-s3n6h: the contrast that names the defect. On ONE record from an
            UNKNOWN frame, the positional `:event` FAILS CLOSED to
            `:rf/redacted` (EP-0015 issue 1 — an unresolvable frame's elision
            registry is unreachable, so it must not fall through to a
            permissive walk), while `record-attrs` are merged unchanged. That
            asymmetry is exactly why the rejected value must never enter
            `record-attrs`: the seam that protects `:event` does not cover them"
    (let [record (reject! (adversarial-strategy 20) [:test/navigate 42])]
      (is (= :rf/redacted (:event record))
          ":event is fail-closed-redacted for an unregistered frame")
      (is (not (str/includes? (pr-str record) sentinel))
          "and the attrs the seam does NOT cover carry no payload either —
           because the value never enters them"))))

;; ===========================================================================
;; (e) The bound is a BOUND, not a static ceiling — proved over a SEQUENCE.
;; ===========================================================================

(def ^:private record-size-ceiling
  "A fixed ceiling on the serialized always-on record, in characters. The
  bounded record's slots are all fixed-size (`:error` / `:frame` / `:time` /
  `:supported` / `:recovery` / `:strategy-type` / the constant `:reason`), so
  its size does not track the rejected value at all. Sized with headroom over
  the observed constant so an ordinary prose edit to `:reason` does not
  false-red, but far below anything that could carry a real payload."
  600)

(deftest the-record-bound-holds-across-escalation-recovery-and-re-escalation
  (testing "rf2-s3n6h: a bound checked against ONE big value is a static
            ceiling — it passes a single transition and still admits a later,
            larger one. Drive the full sequence
            clean → small → HUGE → small → HUGER and pin the record size at
            EVERY step. The record must not grow with the rejected value at
            any point, and must not be permitted to grow again after a
            recovery to a small value"
    (let [sizes (atom [])
          step! (fn [label strategy]
                  (let [record (reject! strategy)
                        size   (record-size record)]
                    (swap! sizes conj [label size])
                    (is (<= size record-size-ceiling)
                        (str "record stays bounded at step " label
                             " (measured " size " chars)"))
                    (is (not (str/includes? (pr-str record) sentinel))
                        (str "no payload fragment at step " label))
                    size))]
      (let [small-1  (step! :small-1  (adversarial-strategy 1))
            huge     (step! :huge     (adversarial-strategy 400))
            small-2  (step! :small-2  (adversarial-strategy 1))
            huger    (step! :huger    (adversarial-strategy 2000))]
        (is (= small-1 huge small-2 huger)
            (str "the record is the SAME size for a 1-key value and a "
                 "2000-key one — the bound is structural (the record carries "
                 "no value-derived slot), not a truncation ceiling that a "
                 "bigger value could push against. Observed: " @sizes))
        (is (apply = (map second @sizes))
            "…and identical at every step of the escalate/recover/re-escalate
             sequence")))))
