(ns re-frame.schema-rollback-errors-stream-cljs-test
  "rf2-xpd8 / rf2-vkn8 — a rejected CANDIDATE TRANSITION reports on the
  always-on `:errors` stream, under the validator's own dev gate.

  PR1 (rf2-xpd8) took the `:where :app-db` arm, which is everything above the
  `PR2` banner. PR2 (rf2-vkn8) took the ruling's other three `:rollback? true`
  producers — `:where :machine-data`, and the two `:rf.error/malformed-schema`
  rejection sites — and they live below that banner, sharing this file's
  fixtures and its pinning discipline so the four arms cannot drift apart.

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
            ;; rf2-vkn8: the `:where :machine-data` arm below drives a real
            ;; machine, and `reg-machine`'s macro expansion needs the machines
            ;; artefact LOADED — its late-bind hooks (`:machines/machine-meta`,
            ;; `:machines/validate-machine-data!`) are what the candidate
            ;; walker resolves. Same braces-to-the-require's-belt reasoning as
            ;; `re-frame.schemas` above: without it those deftests would
            ;; register a machine nothing validates and pass vacuously.
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                ;; The error-listener registry is a `defonce` atom that
                ;; survives test re-runs. A listener leaked from a sibling
                ;; suite would both pollute the counts here AND silence the
                ;; rf2-fu75 console fallback the browser suite asserts — which
                ;; since rf2-kuky.18 fires when NOTHING ROUTED the record, a
                ;; registered `:errors` listener and a frame's registered
                ;; `:observability :errors` sink each counting as owning it.
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

;; ---------------------------------------------------------------------------
;; PR2 (rf2-vkn8) — the campaign's other three `:rollback? true` producers
;; ---------------------------------------------------------------------------
;;
;; One ruling, four arms. PR1 above took `:where :app-db`; these three take the
;; rest of what discards a whole candidate transition:
;;
;;   1. `:where :machine-data` at `:rollback? true` (`:phase :macrostep` /
;;      `:bootstrap`) — a machine snapshot's `:data` violating its
;;      `[:schemas :data]` schema.
;;   2. `:rf.error/malformed-schema` per entry in `validate-app-schema!` — a
;;      REGISTERED app-db schema whose FORM is broken, so the validator throws
;;      and the candidate is rejected fail-closed.
;;   3. `:rf.error/malformed-schema` from the router's wholesale
;;      validator-machinery backstop.
;;
;; Leaving any of them trace-only would have been the worse half of an
;; inconsistency rather than a smaller version of the same gap: to the
;; developer these are one symptom — the app stopped updating — reached
;; through the value, the schema form, or the validator itself, and only one
;; of the three would have said so.
;;
;; The `:frame` slot in arm 1 is the re-verification this bead turned on, and
;; it is asserted rather than assumed: `validate-machine-data!` RECEIVED a
;; frame-id and discarded it (`_frame-id`), and the record without it can
;; reach corpus listeners but never the frame's own `:observability :errors`
;; sink — which is most of the point.

(def ^:private machine-frame-id :vkn8/machine)
(def ^:private machine-id       :vkn8.machine/counter)
(def ^:private malformed-frame-id :vkn8/malformed)
(def ^:private throw-frame-id     :vkn8/validator-throw)

(def ^:private machine-planted-value
  "Planted inside the machine's rejected `:data` so `pr-str` over the record
  can be swept for it."
  "rf2-vkn8-planted-machine-data-must-not-egress")

(def ^:private machine-record-keys
  "The CLOSED key set of the dev-gated `:where :machine-data` rejection
  record. Pinned EQUAL, like PR1's: every member is a framework keyword
  (`:error`, `:where`, `:rollback?`, `:recovery`, and `:phase` — a closed
  lifecycle vocabulary) or a structural id (`:machine-id` / `:failing-id`,
  both the machine's registered keyword, and `:frame`)."
  #{:error :where :machine-id :failing-id :phase :frame
    :rollback? :recovery :reason :time})

(def ^:private malformed-record-keys
  "The CLOSED key set of both `:rf.error/malformed-schema` rejection records.
  The router's backstop carries the same set MINUS `:registered-path` — it has
  no registration to name, because the throw was wholesale rather than
  per-entry."
  #{:error :where :registered-path :event-id :failing-id :frame
    :rollback? :recovery :reason :time})

(defn- machine-records [{:keys [errors]}]
  (filterv #(and (= :rf.error/schema-validation-failure (:error %))
                 (= :machine-data (:where %)))
           errors))

(defn- machine-traces [{:keys [traces]}]
  (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                 (= :machine-data (:where (:tags %))))
           traces))

(defn- malformed-records [{:keys [errors]}]
  (filterv #(= :rf.error/malformed-schema (:error %)) errors))

(defn- runtime-db-value [frame]
  (:rf.db/runtime (rf/frame-state-value frame)))

(defn- machines-present? []
  (some? (rf.late-bind/get-fn :machines/validate-machine-data!)))

;; ===========================================================================
;; Arm 1 — `:where :machine-data` at `:rollback? true`
;; ===========================================================================

(defn- register-machine-app! []
  (rf/make-frame {:id machine-frame-id :doc "rf2-vkn8 machine rollback witness"})
  (rf/reg-machine machine-id
    {:initial :idle
     :data    {:n 1}
     :schemas {:data [:map [:n pos-int?] [:note {:optional true} :string]]}
     :actions {:break (fn [_] {:data {:n 0 :note machine-planted-value}})
               :bump  (fn [_] {:data {:n 2}})
               ;; The escape-hatch fx validates the WOULD-BE-MERGED snapshot
               ;; at `:phase :update-snapshot` with `:rollback? false` — a
               ;; local skipped write, not a discarded transaction. It is the
               ;; negative control: same boundary, same schema, same trace
               ;; category, and it must fan NOTHING onto `:errors`.
               :patch (fn [_]
                        {:fx [[:rf.machine/update-snapshot
                               {:rf/machine-id machine-id
                                :rf/patch      {:data {:n 0}}}]]})}
     :states  {:idle {:on {:break {:target :idle :action :break}
                           :bump  {:target :idle :action :bump}
                           :patch {:target :idle :action :patch}}}}})
  nil)

(defn- bootstrap-machine! []
  ;; Settle the machine into runtime-db with its conforming initial `:data`,
  ;; so the violation under test is the MACROSTEP's and not the bootstrap's.
  (rf/dispatch-sync [machine-id [:noop]] {:frame machine-frame-id}))

(deftest ^:requires-debug rejected-machine-data-candidate-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?) (machines-present?))
    (register-machine-app!)
    (bootstrap-machine!)
    (let [before   (runtime-db-value machine-frame-id)
          captured (capture #(rf/dispatch-sync [machine-id [:break]]
                                               {:frame machine-frame-id}))
          records  (machine-records captured)]

      (testing "one record for the machine whose :data broke its schema"
        (is (= 1 (count records))
            (str "expected one :errors record for the rejected machine "
                 "transition; got " (count records) " — " (pr-str records)))
        (let [r (first records)]
          (is (= :machine-data (:where r)))
          (is (= machine-id (:machine-id r)))
          (is (= machine-id (:failing-id r))
              "the uniform error-emit alias names the machine, as the trace does")
          (is (= :macrostep (:phase r))
              "the lifecycle position an operator reads the blast radius off")
          (is (true? (:rollback? r))
              ":rollback? true — the WHOLE candidate frame transition was discarded")
          (is (= :no-recovery (:recovery r)))))

      (testing "the `:frame` slot — the re-verification this bead turned on.
                `validate-machine-data!` received a frame-id and discarded it
                (`_frame-id`); without threading it the record reaches corpus
                listeners but never the frame's own `:observability :errors`
                sink, which is most of the point."
        (is (= machine-frame-id (:frame (first records)))
            (str "the record must name the frame whose candidate transition "
                 "was rejected; got " (pr-str (:frame (first records))))))

      (testing "the key set is CLOSED and EQUAL — not merely a superset"
        (let [r (first records)]
          (is (= machine-record-keys (set (keys r)))
              (str "record does not match the pinned key set; extra "
                   (pr-str (into #{} (remove machine-record-keys) (keys r)))
                   ", missing "
                   (pr-str (into #{} (remove (set (keys r))) machine-record-keys))))))

      (testing "no payload-bearing slot, by name AND by sweep. The machine's
                `:data` is its working memory — the slot a machine's
                `:sensitive` classification exists to protect."
        (let [r (first records)]
          (doseq [k payload-bearing-keys]
            (is (not (contains? r k))
                (str "the record carries the payload-bearing slot " k)))
          (is (not (str/includes? (pr-str r) machine-planted-value))
              (str "the planted `:data` value reached the always-on record: "
                   (pr-str r)))
          (is (str/includes? (:reason r) (pr-str machine-id))
              "the reason names the machine, so a console line is actionable")
          (is (str/includes? (:reason r) "rejected")
              "and says what happened to the transaction")))

      (testing "the DEV TRACE is unchanged — still one, still carrying the
                offending `:data`. The frame was threaded for the RECORD
                ALONE: nothing was added to the tags map `emit-failure!`
                builds, so Xray, Story and the epoch recorder read a
                byte-identical trace and `tools/xray/spec` needed no edit.

                And the delivered trace was never frameless, which is worth
                pinning because it is the half a reader gets wrong: the EMIT
                SITE built no `:frame`, but `re-frame.trace/stamp-frame`
                supplies one on the way out for any emit correlated to a run.
                An always-on RECORD has no such bus — `dispatch-error-record!`
                delivers the map the caller built, verbatim — which is exactly
                why the frame had to be threaded down to the emit site rather
                than left to be stamped."
        (let [traces (machine-traces captured)]
          (is (= 1 (count traces)))
          (is (contains? (:tags (first traces)) :value))
          (is (str/includes? (pr-str (:tags (first traces))) machine-planted-value)
              "the trace is where the offending :data still lives")
          (is (= machine-frame-id (:frame (:tags (first traces))))
              "the trace's frame comes from the bus stamp, unchanged by this bead")))

      (testing "the record precedes its trace — axis-1-then-axis-2 ordering"
        (let [seqd    (:sequence captured)
              rec-idx (first-index (fn [[kind v]]
                                     (and (= :errors kind)
                                          (= :machine-data (:where v))))
                                   seqd)
              trc-idx (first-index (fn [[kind v]]
                                     (and (= :trace kind)
                                          (= :rf.error/schema-validation-failure
                                             (:operation v))
                                          (= :machine-data (:where (:tags v)))))
                                   seqd)]
          (is (some? rec-idx) "premise: the always-on record was observed")
          (is (some? trc-idx) "premise: the dev trace was observed")
          (is (< rec-idx trc-idx))))

      (testing "the transaction really was discarded"
        (is (= before (runtime-db-value machine-frame-id))
            "runtime-db keeps its pre-event value — the snapshot never installed")
        (is (= :rolled-back
               (:outcome (first (filter #(= machine-id (:event-id %))
                                        (:events captured)))))
            "the always-on :events record already carried the CONSEQUENCE")))))

(deftest ^:requires-debug machine-data-negative-controls
  (when (and rf.interop/debug-enabled? (schemas-present?) (machines-present?))
    (register-machine-app!)
    (bootstrap-machine!)

    (testing "a CONFORMING macrostep fans nothing — the half that stops this
              being a nag-diagnostic"
      (let [captured (capture #(rf/dispatch-sync [machine-id [:bump]]
                                                 {:frame machine-frame-id}))]
        (is (empty? (machine-records captured)))))

    (testing "an `:rf.machine/update-snapshot` violation is `:rollback? false`
              — a local skipped write, not a discarded transaction — so it
              stays trace-only. Same boundary, same schema, same trace
              CATEGORY, and it must NOT reach the always-on stream: this is
              the assertion that says the promotion is scoped to
              `:rollback? true` rather than to `:where :machine-data`."
      (let [captured (capture #(rf/dispatch-sync [machine-id [:patch]]
                                                 {:frame machine-frame-id}))]
        (is (seq (machine-traces captured))
            "premise: the escape-hatch violation really did fire its trace")
        (is (= #{:update-snapshot}
               (set (map #(:phase (:tags %)) (machine-traces captured))))
            "premise: and it is the :update-snapshot phase, the :rollback? false one")
        (is (empty? (machine-records captured))
            (str "a :rollback? false machine-data failure must fan NOTHING onto "
                 ":errors; got " (pr-str (machine-records captured))))))))

;; ===========================================================================
;; Arm 2 — `:rf.error/malformed-schema`, per registered entry
;; ===========================================================================

(defn- register-malformed-app! []
  (rf/make-frame {:id malformed-frame-id :doc "rf2-vkn8 malformed-schema witness"})
  ;; A childless `[:vector]` registers cleanly — Malli validates schema FORMS
  ;; lazily — and then makes the registered validator THROW on the first
  ;; candidate validation.
  (rf/with-frame malformed-frame-id
    (rf/reg-app-schema [:broken] [:vector]))
  (rf/reg-event :vkn8/malformed-write
    {:doc "commits a :db so the candidate walker runs over the broken entry"}
    (fn [_ _] {:db {:broken [1 2 3] :note planted-value}}))
  nil)

(deftest ^:requires-debug malformed-registered-schema-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (register-malformed-app!)
    (let [before   (rf/app-db-value malformed-frame-id)
          captured (capture #(rf/dispatch-sync [:vkn8/malformed-write]
                                               {:frame malformed-frame-id}))
          records  (malformed-records captured)]

      (testing "one record per malformed registration"
        (is (= 1 (count records))
            (str "expected one :rf.error/malformed-schema record; got "
                 (count records) " — " (pr-str records)))
        (let [r (first records)]
          (is (= :app-db (:where r)))
          (is (= [:broken] (:registered-path r))
              "the record names the registration the developer must fix")
          (is (= :vkn8/malformed-write (:event-id r)))
          (is (= :vkn8/malformed-write (:failing-id r)))
          (is (= malformed-frame-id (:frame r)))
          (is (true? (:rollback? r))
              "fail-CLOSED: the unvalidated candidate is rejected, not installed")
          (is (= :no-recovery (:recovery r)))))

      (testing "the key set is CLOSED and EQUAL"
        (is (= malformed-record-keys (set (keys (first records))))
            (str "extra "
                 (pr-str (into #{} (remove malformed-record-keys)
                               (keys (first records))))
                 ", missing "
                 (pr-str (into #{} (remove (set (keys (first records))))
                               malformed-record-keys)))))

      (testing "the `:reason` is a CONSTANT sentence, NOT the throwing
                validator's message. That message is unbounded and
                author-controlled — a user-supplied validator may say
                anything, and Malli's own form errors `pr-str` the offending
                schema — so an unbounded reason on a bounded record is the
                defect shape this campaign exists to avoid."
        (let [r      (first records)
              traces (filterv #(= :rf.error/malformed-schema (:operation %))
                              (:traces captured))]
          (is (not (contains? r :schema))
              "the malformed registration FORM stays on the dev trace")
          (is (not (str/includes? (pr-str r) planted-value)))
          (is (str/includes? (:reason r) "[:broken]")
              "the reason names the registered path")
          (is (seq traces) "premise: the dev trace fired too")
          (is (contains? (:tags (first traces)) :schema)
              "and the trace keeps the form the record omits")
          (is (not= (:reason (:tags (first traces))) (:reason r))
              "the two reasons are composed separately, on purpose")))

      (testing "the candidate was rejected fail-closed"
        (is (= before (rf/app-db-value malformed-frame-id))
            "nothing installed — a validator that threw cannot prove conformance")))))

(deftest ^:requires-debug a-well-formed-registration-is-silent
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (rf/make-frame {:id :vkn8/well-formed :doc "rf2-vkn8 negative control"})
    (rf/with-frame :vkn8/well-formed
      (rf/reg-app-schema [:ok] [:vector :int]))
    (rf/reg-event :vkn8/well-formed-write (fn [_ _] {:db {:ok [1 2]}}))
    (testing "a well-formed registration over a conforming commit fans nothing"
      (let [captured (capture #(rf/dispatch-sync [:vkn8/well-formed-write]
                                                 {:frame :vkn8/well-formed}))]
        (is (empty? (malformed-records captured)))
        (is (empty? (rejection-records captured)))))))

;; ===========================================================================
;; Arm 3 — the router's wholesale validator-machinery backstop
;; ===========================================================================

(deftest ^:requires-debug validator-machinery-throw-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (rf/make-frame {:id throw-frame-id :doc "rf2-vkn8 validator-throw witness"})
    (rf/reg-event :vkn8/throw-write (fn [_ _] {:db {:whatever 1}}))
    (let [real (rf.late-bind/get-fn :schemas/validate-app-schema!)]
      (try
        ;; Break the HOOK itself, not a registered schema: a per-entry throw is
        ;; isolated inside `validate-app-schema!` (arm 2 above). Only a
        ;; WHOLESALE machinery throw escapes to the router's catch, which is
        ;; the arm under test. `set-fn!` invalidates the sticky resolution
        ;; cache, so the swap takes effect on the next dispatch.
        (rf.late-bind/set-fn! :schemas/validate-app-schema!
                              (fn [& _] (throw (ex-info planted-value {}))))
        (let [before   (rf/app-db-value throw-frame-id)
              captured (capture #(rf/dispatch-sync [:vkn8/throw-write]
                                                   {:frame throw-frame-id}))
              records  (malformed-records captured)]

          (testing "the backstop fans exactly one record"
            (is (= 1 (count records))
                (str "expected one :rf.error/malformed-schema record from the "
                     "router backstop; got " (count records) " — "
                     (pr-str records)))
            (let [r (first records)]
              (is (= :app-db (:where r)) "the partition arm that threw")
              (is (= :vkn8/throw-write (:event-id r)))
              (is (= :vkn8/throw-write (:failing-id r)))
              (is (= throw-frame-id (:frame r)))
              (is (true? (:rollback? r)))
              (is (= :no-recovery (:recovery r)))))

          (testing "the key set is CLOSED and EQUAL — the per-entry set MINUS
                    `:registered-path`, because a wholesale throw names no
                    registration"
            (is (= (disj malformed-record-keys :registered-path)
                   (set (keys (first records))))
                (str "got " (pr-str (sort (keys (first records)))))))

          (testing "the `:reason` is a CONSTANT sentence — never the throwing
                    validator's message, which is unbounded and may embed the
                    value it choked on. The dev trace keeps that message; the
                    record does not."
            (let [r      (first records)
                  traces (filterv #(= :rf.error/malformed-schema (:operation %))
                                  (:traces captured))]
              (is (not (str/includes? (pr-str r) planted-value))
                  (str "the throwing validator's message reached the always-on "
                       "record: " (pr-str r)))
              (is (seq traces) "premise: the dev trace fired")
              (is (str/includes? (pr-str (:tags (first traces))) planted-value)
                  "and it is the trace that still carries the message")))

          (testing "the candidate was rejected fail-closed"
            (is (= before (rf/app-db-value throw-frame-id))
                "a throwing validator cannot prove conformance, so nothing installs")))
        (finally
          (rf.late-bind/set-fn! :schemas/validate-app-schema! real))))))

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

;; JVM-ONLY, and the reader conditional is load-bearing rather than tidy.
;; `^:prod-gate` is a cognitect-test-runner VAR filter, and only the JVM lanes
;; pass `-e` / drop it: shadow-cljs `:node-test` runs every `deftest` it finds,
;; tags and all. So on CLJS this deftest ran in an ordinary DEV build and its
;; own premise assertion — `debug-enabled?` is false — failed, which is the
;; RIGHT failure for a test that has no business running there. Measured: three
;; failures in `npm run test:cljs`, none in either JVM lane.
;;
;; Nothing is lost by scoping it. The CLJS half of this claim is not a runtime
;; assertion at all but `scripts/check-elision.cjs`, which greps a real
;; `:advanced` + `goog.DEBUG=false` release bundle for this record's reason
;; string and proves it ABSENT — a stronger statement than "it did not fire",
;; because it shows the literal is not in the artefact.
#?(:clj
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
             reason there is nothing to report"))))))

;; PR2's production gate (rf2-vkn8), JVM-only for the same load-bearing reason
;; as the deftest above: `^:prod-gate` is a cognitect-test-runner VAR filter
;; and shadow-cljs `:node-test` honours no var tags, so on CLJS this would run
;; in an ordinary DEV build and correctly fail its own premise. The CLJS half
;; of the claim is `scripts/check-elision.cjs`, which greps a real `:advanced`
;; + `goog.DEBUG=false` bundle for all three of PR2's reason tails and proves
;; them ABSENT — a stronger statement than "they did not fire", because it
;; shows the literals are not in the artefact.
#?(:clj
   (deftest ^:prod-gate pr2-rejection-records-are-absent-under-the-production-gate
     (testing "Under `-Dre-frame.debug=false` none of PR2's three producers
            runs: `validate-machine-data!` returns true from inside its own
            gate without walking a snapshot, `validate-app-schema!` never
            reaches the per-entry malformed branch, and the router's backstop
            emit is behind an explicit `debug-enabled?` check of its own —
            which it needs, because `run-candidate-validation!` (unlike the
            two validators) runs in EVERY build. So all three records must be
            absent, and the candidates must INSTALL: that is the designed
            posture, and it is what makes this a negative control rather than
            a restatement."
       (is (false? rf.interop/debug-enabled?)
           "premise: this deftest is running under the real production gate")
       (when (and (schemas-present?) (machines-present?))
         (register-machine-app!)
         (bootstrap-machine!)
         (let [captured (capture #(rf/dispatch-sync [machine-id [:break]]
                                                    {:frame machine-frame-id}))]
           (is (empty? (machine-records captured))
               (str "the dev-gated :machine-data record survived into the "
                    "production posture: " (pr-str (machine-records captured))))
           (is (empty? (machine-traces captured))
               "and neither did its dev trace — both elide with the check")))
       (when (schemas-present?)
         (register-malformed-app!)
         (let [captured (capture #(rf/dispatch-sync [:vkn8/malformed-write]
                                                    {:frame malformed-frame-id}))]
           (is (empty? (malformed-records captured))
               (str "the dev-gated :rf.error/malformed-schema record survived: "
                    (pr-str (malformed-records captured))))
           (is (= {:broken [1 2 3] :note planted-value}
                  (rf/app-db-value malformed-frame-id))
               "the candidate INSTALLS under the gate — a malformed registration
                is never consulted, so there is nothing to reject"))))))
