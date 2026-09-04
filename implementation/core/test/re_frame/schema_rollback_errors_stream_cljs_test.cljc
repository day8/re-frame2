(ns re-frame.schema-rollback-errors-stream-cljs-test
  "rf2-xpd8 (PR1) — a rejected `app-db` candidate reports on the always-on
  `:errors` stream, under the validator's own dev gate.

  ## The measurement this suite reproduces

  Before this change, `:rf.error/schema-validation-failure :where :app-db`
  reached the DEV-ONLY `:trace` stream and nothing else. On one live page,
  one frame, one dispatch, a listener on `:errors` recorded 0 records while a
  listener on `:trace` recorded 17 rollback records for the SAME dispatch —
  whose `:events` outcome read `:rolled-back`. The control that makes that
  zero real: dispatching an unregistered event id DID reach the `:errors`
  listener as `:rf.error/no-such-handler`, so the listener was live.

  That is the whole cost of the incident this bead was filed against. An
  application-wide, permanent rollback loop produced no page error, no console
  message of any level, no failed request, and nothing on the stream an
  application registers to hear about its own errors. The only surface that
  said anything was the dev trace, which has no default listener — so unless
  the developer already had Xray open and knew to look, the app rendered
  empty and said nothing about why.

  So this suite drives the 0-vs-N split and asserts the fixed side of it,
  WITH the same control. Every deftest below carries the control or the
  negative half beside its positive claim: a suite that only asserts records
  arrive cannot tell a working fan-out from a listener that receives
  everything.

  ## The record's shape is the contract, not an implementation detail

  It is BUILT FROM a closed allow-list of structural inputs — never filtered
  down from the dev trace's tags — so the key set is pinned EQUAL here rather
  than sampled, and every payload-bearing slot the trace carries is asserted
  ABSENT by name. `pr-str` over the whole record is then swept for a planted
  sentinel value, which is the assertion that survives a future slot being
  added without anyone re-reading this list.

  The dev trace is BYTE-IDENTICAL to before the change: it keeps `:value`,
  `:explain` and the leaf `:path`, so Xray, Story and the epoch recorder read
  exactly what they read before. That half is asserted too — a change that
  quietened the trace to make room for the record would pass every
  record-shaped assertion here.

  ## Posture

  The positive deftests are `^:requires-debug`: the check they observe is
  dev-only BY DESIGN, and the record is emitted from inside the check's own
  gate, so under `-Dre-frame.debug=false` there is nothing to see. The
  `^:prod-gate` deftest at the foot is the discriminator that says so
  positively — same violating dispatch, zero records, `:outcome :ok`, the
  candidate installed — and it runs ONLY in the `jvm-core-prod-gate` lane
  (`scripts/test-core-prod-gate.sh`). Its CLJS counterpart is the elision
  probe: `check-elision.cjs` pins the reason string's distinctive tail absent
  from a `goog.DEBUG=false` release bundle.

  ## Why `.cljc` with a `-cljs-test` suffix

  The `cljs-test$` regexp puts this namespace on the `:node-test` build, and
  `.cljc` runs it under `clojure -M:test` in core too. The rejection path is
  `.cljc` in `re-frame.schemas.validate`, so one file pins both hosts and
  neither can drift from the other. Precedent:
  `re-frame.always-on-axis-conformance-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            ;; Load the schemas artefact explicitly. It is an OPTIONAL
            ;; dependency reached through late-bind hooks, so without this
            ;; require `reg-app-schema` registers into a registry no validator
            ;; ever consults and every deftest below passes VACUOUSLY — which
            ;; is exactly how this file first ran: two tests, ZERO assertions,
            ;; exit 0. The `schemas-present?` guard is the belt to this
            ;; require's braces, not a substitute for it.
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                ;; The error-listener registry is a `defonce` atom that
                ;; survives test re-runs. A listener leaked from a sibling
                ;; suite would both pollute the counts here AND silence the
                ;; rf2-fu75 console fallback the browser suite asserts.
                (rf.error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; The pinned record shape
;; ---------------------------------------------------------------------------

(def ^:private rejection-record-keys
  "The CLOSED key set of the dev-gated app-db candidate-rejection record.

  Pinned EQUAL rather than sampled, the way
  `re-frame.always-on-validation-production-test` pins the boundary arm's
  record: this fans onto the corpus-wide `:errors` registry and the frame's
  `:observability :errors` sink, so a slot added later reaches whatever an
  application wired to those. Every member is a framework keyword, a
  structural identifier, or the developer's own registration root.

  `:event-id` and `:failing-id` are BOTH always present and both hold the
  dispatched event's id — nil only on a direct `validate-app-schema!` call
  outside a run (the elision probe and unit tests), which no runtime path
  produces. So the key set is closed and fixed rather than conditional."
  #{:error :where :registered-path :event-id :failing-id :frame
    :rollback? :recovery :reason :time})

(def ^:private payload-bearing-keys
  "Slots the RICH DEV TRACE carries that this record must NEVER.

  `:value` is the failing leaf; `:received` is its sibling spelling;
  `:explain` / `:explain-humanized` carry the WHOLE registered value (Malli's
  explanation root is the whole input); `:schema` is the registered form,
  which `pr-str`s unbounded. `:path` is the subtle one and it is omitted for
  a reason that is not about size: the trace's `:path` is the registered root
  conj'd with Malli's `:in`, and `:in` segments are not all structural — a
  `:set` failure's segment IS the failing element value, and a `:map-of` key
  rides as the key. `sanitize-sensitive-path` scrubs those only when the leaf
  is declared `:sensitive?`, so a non-sensitive set element would ship
  verbatim. A structural-path projection would have to drop those segment
  kinds first."
  #{:value :received :explain :explain-humanized :schema :path})

;; ---------------------------------------------------------------------------
;; Fixture app — the incident's shape: several registered paths over slices
;; nothing has written yet
;; ---------------------------------------------------------------------------

(def ^:private planted-value
  "A value planted in the rejected candidate so `pr-str` over the record can
  be swept for it. Distinctive enough that a substring hit is never a
  coincidence."
  "rf2-xpd8-planted-payload-must-not-egress")

(def ^:private frame-id :xpd8/rollback)

(def ^:private fx-ran (atom 0))

(defn- register-app! []
  (reset! fx-ran 0)
  (rf/make-frame {:id frame-id :doc "rf2-xpd8 rollback witness"})
  ;; FOUR registered paths, all `:int`, over an EMPTY app-db — the incident's
  ;; shape exactly: schemas declared non-nilable over slices nothing has
  ;; written yet. The seeded event writes a conforming value into ONE of them
  ;; and a violating one into another, so three of the four fail.
  (rf/with-frame frame-id
    (rf/reg-app-schema [:conforms] :int)
    (rf/reg-app-schema [:absent-a] :int)
    (rf/reg-app-schema [:absent-b] :int)
    (rf/reg-app-schema [:leaks]    :int))
  (rf/reg-fx :xpd8/must-not-run
    {:doc "a rejected candidate does not walk :fx — this counter proves it"}
    (fn [_ctx _args] (swap! fx-ran inc) nil))
  (rf/reg-event :xpd8/seed
    {:doc "writes one conforming slot and one violating one — three of the
           four registered paths are left absent or wrong"}
    (fn [_ _]
      {:db {:conforms 1 :leaks planted-value}
       :fx [[:xpd8/must-not-run {}]]}))
  (rf/reg-event :xpd8/all-good
    {:doc "writes every registered path with a conforming value"}
    (fn [_ _] {:db {:conforms 1 :absent-a 2 :absent-b 3 :leaks 4}}))
  nil)

(defn- first-index
  "Index of the first element of `coll` satisfying `pred`, or nil. Portable —
  `.indexOf` is a JVM interop call and this namespace runs on both hosts."
  [pred coll]
  (first (keep-indexed (fn [i x] (when (pred x) i)) coll)))

(defn- capture
  "Run `body-fn` with listeners on all three streams, recording `:errors` and
  `:trace` into ONE sequenced vector so their relative ORDER is observable,
  and `:events` separately. Returns
  `{:errors [...] :traces [...] :events [...] :sequence [[:errors r] ...]}`.

  Unregisters in a `finally` so a thrown body cannot leak a listener into the
  next deftest and silently invert its counts."
  [body-fn]
  (let [sequenced (atom [])
        events    (atom [])]
    (rf/register-listener! :errors ::rec (fn [r] (swap! sequenced conj [:errors r])))
    (rf/register-listener! :trace  ::rec (fn [e] (swap! sequenced conj [:trace e])))
    (rf/register-listener! :events ::rec (fn [r] (swap! events conj r)))
    (try
      (body-fn)
      (finally
        (rf/unregister-listener! :errors ::rec)
        (rf/unregister-listener! :trace  ::rec)
        (rf/unregister-listener! :events ::rec)))
    (let [seqd @sequenced]
      {:sequence seqd
       :errors   (mapv second (filter #(= :errors (first %)) seqd))
       :traces   (mapv second (filter #(= :trace  (first %)) seqd))
       :events   @events})))

(defn- rejection-records [{:keys [errors]}]
  (filterv #(and (= :rf.error/schema-validation-failure (:error %))
                 (= :app-db (:where %)))
           errors))

(defn- rejection-traces [{:keys [traces]}]
  (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                 (= :app-db (:where (:tags %))))
           traces))

(defn- schemas-present? []
  (some? (rf.late-bind/get-fn :schemas/validate-app-schema!)))

;; ===========================================================================
;; The 0-vs-N split, with its control
;; ===========================================================================

(deftest ^:requires-debug rejected-candidate-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (register-app!)
    (let [before   (rf/app-db-value frame-id)
          captured (capture #(rf/dispatch-sync [:xpd8/seed] {:frame frame-id}))
          records  (rejection-records captured)]

      (testing "ONE record per FAILING registered entry — the trace's own
                granularity. Four paths registered, one seeded conforming, so
                three fail and three records arrive."
        (is (= 3 (count records))
            (str "expected one :errors record per failing registered path; got "
                 (count records) " — "
                 (pr-str (mapv :registered-path records))))
        (is (= #{[:absent-a] [:absent-b] [:leaks]}
               (set (map :registered-path records)))
            "each record names a DISTINCT violated registration, so a reader
             can tell which declarations the candidate broke")
        (is (every? #(= :app-db (:where %)) records))
        (is (every? #(true? (:rollback? %)) records)
            ":rollback? true is the fact that makes this worth reporting — a
             whole transaction was discarded")
        (is (every? #(= :no-recovery (:recovery %)) records))
        (is (every? #(= frame-id (:frame %)) records)
            "the frame id routes the record to that frame's :observability
             :errors sink")
        (is (every? #(= :xpd8/seed (:event-id %)) records))
        (is (every? #(= :xpd8/seed (:failing-id %)) records)))

      (testing "the key set is CLOSED and EQUAL — not merely a superset"
        (doseq [r records]
          (is (= rejection-record-keys (set (keys r)))
              (str "record for " (pr-str (:registered-path r))
                   " does not match the pinned key set; extra "
                   (pr-str (into #{} (remove rejection-record-keys) (keys r)))
                   ", missing "
                   (pr-str (into #{} (remove (set (keys r))) rejection-record-keys))))))

      (testing "no payload-bearing slot, by name AND by sweep"
        (doseq [r records]
          (doseq [k payload-bearing-keys]
            (is (not (contains? r k))
                (str "the record carries the payload-bearing slot " k
                     " — it belongs on the dev trace only"))))
        (doseq [r records]
          (is (not (str/includes? (pr-str r) planted-value))
              (str "the planted candidate value reached the always-on record: "
                   (pr-str r)))))

      (testing "the :reason is composed from structural inputs — it names the
                registered path and the TYPE of the failing leaf, never the
                leaf. `got string` on the violating path, `got nil` on the
                absent ones, which is what makes the console line
                self-diagnosing."
        (let [by-path (into {} (map (juxt :registered-path :reason)) records)]
          (is (str/includes? (get by-path [:leaks] "") "got string")
              (str "expected the type tag, got " (pr-str (get by-path [:leaks]))))
          (is (str/includes? (get by-path [:absent-a] "") "got nil"))
          (is (every? #(str/includes? % "the candidate transition was rejected")
                      (vals by-path))
              "and every reason says what happened to the transaction")
          (doseq [[path reason] by-path]
            (is (str/includes? reason (pr-str path))
                (str "the reason names its registered path; got "
                     (pr-str reason))))))

      (testing "the DEV TRACE is unchanged — same count, still carrying the
                payload. A change that quietened the trace to make room for the
                record would pass every assertion above."
        (let [traces (rejection-traces captured)]
          (is (= 3 (count traces)))
          (is (every? #(contains? (:tags %) :value) traces)
              "the trace still carries the failing leaf")
          (is (every? #(contains? (:tags %) :path) traces)
              "and the leaf path the record deliberately omits")
          (is (some #(str/includes? (pr-str (:tags %)) planted-value) traces)
              "the trace is where the offending value still lives")))

      (testing "the record precedes its trace — `emit-error-both!`'s
                axis-1-then-axis-2 ordering, so the JVM SSR listener's
                last-write-wins buffer keeps the richer trace as its final
                input. Compared over the REJECTION entries only: the dispatch
                emits unrelated traces either side of them."
        (let [seqd     (:sequence captured)
              rec-idx  (first-index (fn [[kind v]]
                                      (and (= :errors kind)
                                           (= :rf.error/schema-validation-failure
                                              (:error v))
                                           (= :app-db (:where v))))
                                    seqd)
              trc-idx  (first-index (fn [[kind v]]
                                      (and (= :trace kind)
                                           (= :rf.error/schema-validation-failure
                                              (:operation v))
                                           (= :app-db (:where (:tags v)))))
                                    seqd)]
          (is (some? rec-idx) "premise: the always-on record was observed")
          (is (some? trc-idx) "premise: the dev trace was observed")
          (is (< rec-idx trc-idx)
              (str "the record must be emitted BEFORE its trace; record at "
                   rec-idx ", trace at " trc-idx))))

      (testing "the transaction really was discarded"
        (is (= before (rf/app-db-value frame-id))
            "app-db keeps its pre-event value — the candidate never installed")
        (is (nil? (:leaks (rf/app-db-value frame-id)))
            "and specifically the violating slot never landed")
        (is (zero? @fx-ran)
            ":fx does not walk for a rejected dispatch")
        (is (= :rolled-back
               (:outcome (first (filter #(= :xpd8/seed (:event-id %))
                                        (:events captured)))))
            "the always-on :events record already carried the CONSEQUENCE; this
             bead is about the CAUSE never reaching the errors stream")))))

(deftest ^:requires-debug conforming-commit-is-silent-and-the-control-is-live
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (register-app!)

    (testing "a conforming commit fans NOTHING onto :errors — the negative half
              that stops this being a nag-diagnostic"
      (let [captured (capture #(rf/dispatch-sync [:xpd8/all-good] {:frame frame-id}))]
        (is (empty? (rejection-records captured)))
        (is (= {:conforms 1 :absent-a 2 :absent-b 3 :leaks 4}
               (rf/app-db-value frame-id))
            "and the candidate installed")))

    (testing "CONTROL — the same listener on the same frame DOES receive an
              unrelated always-on category. This is what made the original
              0-of-17 measurement real rather than a dead listener."
      (let [captured (capture #(rf/dispatch-sync [:xpd8/no-such-event] {:frame frame-id}))]
        (is (seq (filter #(= :rf.error/no-such-handler (:error %))
                         (:errors captured)))
            "the control category reached the listener")))))

;; ===========================================================================
;; The production gate — the negative control that proves the record is gated
;; WITH the check rather than merely beside it
;; ===========================================================================

(deftest ^:prod-gate rejection-record-is-absent-under-the-production-gate
  (testing "Under `-Dre-frame.debug=false` the candidate validator does not
            run at all (Spec 010 §Production builds), so there is no rejection
            to report and the record must be absent with it. Runs ONLY in the
            `jvm-core-prod-gate` lane — the default `:test` alias excludes
            `^:prod-gate` — so it is a statement about the real production
            posture, not a `with-redefs` imitation of one: `debug-enabled?` is
            read ONCE at namespace load and a rebinding after that is
            invisible to it.

            The CLJS half of the same claim is `check-elision.cjs`, which pins
            this record's reason-string tail ABSENT from a `goog.DEBUG=false`
            release bundle."
    (is (false? rf.interop/debug-enabled?)
        "premise: this deftest is running under the real production gate")
    (when (schemas-present?)
      (register-app!)
      (let [captured (capture #(rf/dispatch-sync [:xpd8/seed] {:frame frame-id}))]
        (is (empty? (rejection-records captured))
            (str "the dev-gated record survived into the production posture: "
                 (pr-str (rejection-records captured))))
        (is (= :ok (:outcome (first (filter #(= :xpd8/seed (:event-id %))
                                            (:events captured)))))
            "the candidate INSTALLS under the gate — the designed posture
             (Spec 010 §What elision means for `reg-app-schema`), and the
             reason there is nothing to report")))))
