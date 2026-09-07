(ns re-frame.spawn-all-authority-catalogue-test
  "The AUTHORITATIVE catalogue/schema proof for `:spawn-all` exact-attempt folding
  (rf2-p53o47, completed rf2-2ai15g).

  TERMINOLOGY (rf2-wlqfq census) — `authority` in this namespace name means
  CANONICAL DOCUMENT authority (`spec/Spec-Schemas.md` + `spec/009-Instrumentation.md`
  are the authorities these assertions read from), NOT the join coordinate.
  It therefore keeps the word. Fixtures naming the caller-suppliable join
  coordinate use attempt/fence language instead.

  This pins the normative facts the spec/catalogue reconciliation landed so a
  regression FAILS here rather than drifting the spec silently. Every schema
  assertion validates runtime-produced records against the EXECUTABLE canonical
  schema EXTRACTED from `spec/Spec-Schemas.md` (via
  `re-frame.spawn-all-schema-extract`), and every catalogue assertion is pinned
  to a NARROW read of the actual `spec/009-Instrumentation.md` row — so
  weakening the authoritative document is what turns a test red:

    1. SCHEMA — the `:spawned` slot union has THREE legal runtime arms
       (Spec-Schemas §`Machines`): the `:spawn` leaf keyword, the LIVE
       child-bearing `InvokeAllJoinState` (with `:rf/attempt` AND the
       `:cancelled` tombstone set REQUIRED — a token-less or tombstone-less join
       is not a valid live shape; `join.cljc`'s fold gate requires a non-nil
       exact match and consults `:cancelled` to suppress a resurrected
       completion), and the childless REJECT sentinel `InvokeAllRejectedState`
       (`{:closed true}`, only `:rf/spawn-all-rejected? true`). A real
       unregistered-child `:spawn-all` fixture validates the COMPLETE
       `:rf.runtime/machines` runtime-db against the extracted `Machines` form
       during the legal pre-parent-exit interval; a sentinel carrying
       `:children`/coordinate, and a token-less live join, both fail. Key
       completeness derives from ONE producer/consumer boundary (rf2-64uoa): the
       real runtime-produced join's COMPLETE key set equals the extracted schema's
       REQUIRED set (the consumer contract) unioned with an explicit
       open-bookkeeping classification (`:invoke-id`), so a newly seeded-and-
       consumed runtime-owned key FAILS until it is intentionally classified or
       promoted into the schema — the open map can no longer hide a false-green.
       Each required key is additionally guarded by targeted mutation, and the
       `:cancelled` tombstone is proven end-to-end: an explicitly cancelled child
       leaves a tombstone the schema REQUIRES and the runtime HONOURS, so a late
       coordinate cannot resurrect it (rf2-y7venl).

    2. CATALOGUE — `:rf.machine.spawn-all/child-completed` emits
       `:rf.reply/correlation` (Spec 009 detailed row + `join.cljc`), carrying
       the fold's parent/invoke/child/spawned identity.

    3. CATALOGUE — the spawn-all completion ops + their classifier key do not
       drift: the POST-resolution straggler fires
       `:rf.machine.spawn-all/late-completion` (Spec 009 quick index + detailed
       row) classified by `:rf.reply/stale-reason`
       `:rf.machine.spawn-all/join-resolved` (a stale-reason VALUE, never an
       operation); the PRE-resolution suppression fires
       `:rf.machine.spawn-all/stale-completion` also classified by
       `:rf.reply/stale-reason` (NOT the bare `:reason`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            ;; load the machines artefact so its fx handlers + late-bind
            ;; hooks are installed when this ns runs in isolation.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.spawn-all-schema-extract :as rf.spawn-all-schema-extract]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; the reject fixture fans an always-on `:rf.error/machine-spawn-
     ;; unregistered-type` record; clear the always-on listener registry so a
     ;; leaked listener from an earlier test cannot see it.
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))})
  rf.machines.test-support/trace-capture-fixture)

;; The canonical `:spawn-all` runtime-db schema forms, EXTRACTED from
;; spec/Spec-Schemas.md (rf2-2ai15g) — NOT hand-copied. Removing / renaming a
;; def, or weakening `:rf/attempt` to `{:optional true}`, or dropping the
;; `InvokeAllRejectedState` arm from the markdown is a test-build / assertion
;; failure here, so the schema surface and the document cannot drift silently.
(def ^:private InvokeAllJoinState     (rf.spawn-all-schema-extract/canonical-invoke-all-join-schema))
(def ^:private InvokeAllRejectedState (rf.spawn-all-schema-extract/canonical-invoke-all-rejected-schema))
(def ^:private SpawnedSlot            (rf.spawn-all-schema-extract/canonical-spawned-slot-schema))
(def ^:private Machines               (rf.spawn-all-schema-extract/canonical-machines-schema))

(defn- machines-runtime-db
  "The frame's live `:rf.runtime/machines` runtime-db partition value."
  []
  (get (rf.machines.test-support/runtime-db) :rf.runtime/machines))

(defn- join-state [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- mk-child
  "A dispatching child: on `:go` / `:fail` it transitions to a PLAIN
  (non-`:final?`) terminal and dispatches its completion back to
  `parent-id` THROUGH its own handler boundary, so `stamp-join-completion-fx`
  rewrites the completion into the `:rf.machine/join-dispatch` transport and
  the runtime stamps its exact-attempt coordinate on the recordable `:rf.cofx`
  fact `:rf.machine/join-attempt` (the metadata slot is not read)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}]
                              {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})
             :dispatch-err  (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/failed (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-err}}}
             :done   {}
             :failed {}}})

(defn- reg-join-parent!
  "Register a two-child join parent (mode + resolution keys via
  `spawn-all-extra`) + dispatching children and start it. The parent stays
  on `:racing` at resolution (no `:on` for the resolution events), so the
  join slot survives for post-resolution probes. Returns the seeded join
  state."
  [parent-kw child-a-kw child-b-kw spawn-all-extra]
  (rf/reg-machine child-a-kw (mk-child parent-kw))
  (rf/reg-machine child-b-kw (mk-child parent-kw))
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        (merge
                          {:children       [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                            {:id :b :machine-id child-b-kw :start [:set-id :b]}]}
                          spawn-all-extra)}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw [:racing]))

(defn- reg-destroyable-join-parent!
  "Like `reg-join-parent!` for an `:all` join, but the parent exposes a `:kill`
  event that imperatively tears down a named spawned child WHILE IT IS STILL
  IN-PROGRESS — a membership-verified explicit teardown, so the runtime records a
  cancellation TOMBSTONE. Returns the seeded live join state."
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine child-a-kw (mk-child parent-kw))
  (rf/reg-machine child-b-kw (mk-child parent-kw))
  (rf/reg-machine parent-kw
    {:initial :idle
     :actions {:kill-child (fn [{ev :event}]
                             {:fx [[:rf.machine/destroy (second ev)]]})}
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                           {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                         :join            :all
                         :on-all-complete [:all/done]}
                        ;; runs after the join is seeded; internal transition
                        ;; (action only, no target) so the join slot survives.
                        :on {:kill {:action :kill-child}}}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw [:racing]))

;; The SINGLE non-schema authority in the completeness proof: the explicit,
;; reviewed classification of runtime-produced join keys that are NOT
;; schema-required consumer inputs — the "additional bookkeeping keys" the
;; intentionally OPEN `InvokeAllJoinState` legally carries (Spec-Schemas
;; §InvokeAllJoinState). Everything else the proof needs is DERIVED: the required
;; set is read straight off the extracted schema (`schema-required-keys`), and the
;; produced set is read straight off a real runtime join. The completeness proof
;; asserts `produced = required ∪ this-roster`, so a NEW runtime-owned key fails
;; until it is intentionally classified here (bookkeeping) OR promoted into the
;; authoritative schema (a required consumer input) — no hand-copied mirror of the
;; required set to drift, and the open map can no longer hide a false-green.
;;
;; `:invoke-id` is classified bookkeeping, NOT a required consumer input: the seed
;; writes it (`transition.cljc` seeds `:invoke-id invoke-id`) REDUNDANT with the
;; slot key `[parent-id invoke-id]`, and every consumer resolves `invoke-id` from
;; the state-path prefix (`join.cljc` `find-active-spawn-all-in-tree`), reading the
;; fetched join-state value only for `:rf/attempt` / `:children` — never for
;; `:invoke-id`. `cancelled-child-tombstone-is-required-and-honoured` proves the
;; required keys' consumer contract end-to-end; the completeness proof below proves
;; `:invoke-id` remains OPTIONAL (dropping it still validates the open schema).
(def ^:private classified-open-bookkeeping-join-keys
  #{:invoke-id})

(defn- schema-required-keys
  "The set of REQUIRED (non-`{:optional true}`) map keys of an extracted malli
  `:map` schema form — read straight off the compiled schema so the check
  tracks the authoritative document, not a hand-copied key list."
  [schema]
  (->> (m/children (m/schema schema))
       (remove #(:optional (second %)))
       (map first)
       set))

;; ---------------------------------------------------------------------------
;; 1. SCHEMA — `:rf/attempt` is REQUIRED on a live child-bearing join
;; ---------------------------------------------------------------------------

(deftest live-join-requires-the-attempt-token
  (testing "rf2-2ai15g — a runtime-produced `:spawn-all` join validates
            against the EXTRACTED `InvokeAllJoinState` (token REQUIRED per the
            authoritative Spec-Schemas document), and dissociating `:rf/attempt`
            FAILS the schema — the proof a child-bearing join can never be
            token-less (no optional arm). Guards Spec-009 AC: `:rf/attempt`
            going `{:optional true}` in the authoritative document turns this
            red, because the extracted schema would then accept the dissoc."
    (let [j (reg-join-parent! :sac/p1 :sac/p1a :sac/p1b
                              {:join :all :on-all-complete [:all/done]})]
      (is (int? (:rf/attempt j))
          "the live seed always mints an int per-attempt token")
      (is (m/validate InvokeAllJoinState j)
          (str "the runtime join validates against the extracted required-token "
               "schema; explain: " (m/explain InvokeAllJoinState j)))
      (is (not (m/validate InvokeAllJoinState (dissoc j :rf/attempt)))
          "dissociating the token FAILS the extracted schema — the token is
           required in the authoritative document, not optional"))))

;; ---------------------------------------------------------------------------
;; 1a. SCHEMA — EVERY runtime-owned join key is REQUIRED (key-completeness +
;;     targeted mutation), while the map stays intentionally OPEN
;; ---------------------------------------------------------------------------

(deftest live-join-guards-every-runtime-owned-required-key
  (testing "rf2-64uoa — the completeness proof derives from ONE producer/consumer
            boundary: the REAL runtime-produced join's COMPLETE key set equals the
            union of the extracted schema's REQUIRED keys (the consumer contract)
            and the explicit `classified-open-bookkeeping-join-keys` roster. There
            is no hand-copied mirror of the required set (the pre-rf2-64uoa
            `expected-runtime-owned-join-keys` compared the schema only to a second
            hand-written copy AND drove the mutation off that same copy, so a
            newly-seeded-and-consumed required key omitted from both copies stayed
            green under the intentionally OPEN map — a dual-authority false-green).
            Now: a new runtime-owned key must be classified (bookkeeping) or
            promoted into the schema (a required consumer input) or this FAILS;
            dropping ANY required key FAILS the extracted schema (targeted
            mutation); a classified bookkeeping key is present yet OPTIONAL; and an
            unknown extra key still validates — the join stays OPEN, not
            `{:closed true}`."
    (let [j        (reg-join-parent! :sac/pk :sac/pka :sac/pkb
                                     {:join :all :on-all-complete [:all/done]})
          required (schema-required-keys InvokeAllJoinState)
          produced (set (keys j))]
      ;; SINGLE-AUTHORITY completeness: the real produced key set IS the ground
      ;; truth, checked against required ∪ classified-open-bookkeeping. A new
      ;; seeded-and-consumed key that is neither schema-required nor classified
      ;; bookkeeping makes `produced` exceed the union and FAILS here — the gate
      ;; can no longer be satisfied by two hand-copies agreeing while the open map
      ;; silently absorbs an unclassified runtime-owned key.
      (is (= produced (set/union required classified-open-bookkeeping-join-keys))
          (str "the real spawn-all-produced join's COMPLETE key set must equal "
               "schema-required ∪ classified-open-bookkeeping — a new runtime-owned "
               "key must be classified or promoted into the schema. produced=" produced
               " required=" required
               " classified=" classified-open-bookkeeping-join-keys
               " unclassified=" (set/difference produced required
                                                 classified-open-bookkeeping-join-keys)))
      (is (m/validate InvokeAllJoinState j)
          (str "the runtime join validates; explain: " (m/explain InvokeAllJoinState j)))
      ;; targeted mutation: the runtime seed carries every REQUIRED consumer-input
      ;; key, and dropping any single one FAILS the extracted schema.
      (doseq [k required]
        (is (contains? j k) (str "the runtime seed carries the required key " k))
        (is (not (m/validate InvokeAllJoinState (dissoc j k)))
            (str "dropping the required key " k " FAILS the extracted schema")))
      ;; classification proof: every classified bookkeeping key IS produced by the
      ;; runtime, and dropping it STILL validates — it is genuinely OPTIONAL under
      ;; the open schema, not a required consumer input smuggled in as bookkeeping.
      (doseq [k classified-open-bookkeeping-join-keys]
        (is (contains? j k)
            (str "the runtime seed carries the classified bookkeeping key " k))
        (is (m/validate InvokeAllJoinState (dissoc j k))
            (str "dropping the classified bookkeeping key " k " STILL validates — "
                 "it is open bookkeeping, not a required consumer input")))
      ;; intentional open-map extensibility preserved.
      (is (m/validate InvokeAllJoinState (assoc j :rf/future-bookkeeping 1))
          "an extra runtime bookkeeping key still validates — the join stays open"))))

;; ---------------------------------------------------------------------------
;; 1b. SCHEMA + BEHAVIOUR — a cancelled child leaves a `:cancelled` TOMBSTONE the
;;     schema REQUIRES and the runtime HONOURS (late coordinate cannot resurrect)
;; ---------------------------------------------------------------------------

(deftest cancelled-child-tombstone-is-required-and-honoured
  (testing "rf2-y7venl — a membership-verified IN-PROGRESS explicit teardown of a
            spawned child durably records a `:cancelled` tombstone: the live
            tombstoned join validates against the extracted `InvokeAllJoinState`,
            dissociating `:cancelled` FAILS it (the tombstone set is REQUIRED,
            not optional — an open map would otherwise accept the dissoc), and a
            LATE exact-current completion carrier for the cancelled child
            is SUPPRESSED as a duplicate terminal. A late/rejoining coordinate can
            never resurrect or mis-attribute a cancelled child."
    (let [j         (reg-destroyable-join-parent! :sac/tomb :sac/tomba :sac/tombb)
          spawned-a (get-in j [:children :a])
          ;; the exact-attempt coordinate a late carrier would present — captured
          ;; from the LIVE attempt so it is genuinely exact-current, not forged.
          attempt   {:parent-id  :sac/tomb
                     :invoke-id  [:racing]
                     :child-id   :a
                     :spawned-id spawned-a
                     :attempt    (:rf/attempt j)}]
      (is (= #{} (:cancelled j)) "the live seed carries an empty tombstone set")
      ;; Tear child :a down while it is still IN-PROGRESS; :b keeps running so
      ;; the `:all` join is unresolved and the slot stays live for probing.
      (rf/dispatch-sync [:sac/tomb [:kill spawned-a]])
      (let [tombstoned (join-state :sac/tomb [:racing])]
        (is (= #{:a} (:cancelled tombstoned))
            "the membership-verified in-progress teardown durably tombstones child :a")
        (is (false? (:resolved? tombstoned))
            "the :all join is NOT resolved — :b is still live")
        ;; the live TOMBSTONED join validates against the extracted schema ...
        (is (m/validate InvokeAllJoinState tombstoned)
            (str "the tombstoned join validates; explain: "
                 (m/explain InvokeAllJoinState tombstoned)))
        ;; ... and dissociating the tombstone FAILS it — the key is REQUIRED, so
        ;; a runtime that stopped seeding `:cancelled` would be caught here.
        (is (not (m/validate InvokeAllJoinState (dissoc tombstoned :cancelled)))
            "dissociating :cancelled FAILS the extracted schema — the tombstone is required")
        ;; a LATE exact-current completion carrier for :a cannot resurrect
        ;; it. The coordinate rides the recordable `:rf.cofx` slot
        ;; (rf2-nsbwft — the metadata slot is not read).
        (rf.machines.test-support/reset-captured!)
        (rf/dispatch-sync [:sac/tomb [:child/done :a]]
                          {:rf.cofx {:rf.machine/join-attempt attempt}})
        (let [after (join-state :sac/tomb [:racing])
              stale (first (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion))]
          (is (= #{} (:done after))
              "the late exact-attempt carrier did NOT fold — :a is never marked done")
          (is (= #{:a} (:cancelled after))
              "the tombstone is unchanged — the cancelled child is not resurrected")
          (is (some? stale) "a stale-completion suppression fired for the late carrier")
          (is (= :rf.machine.spawn-all/duplicate-completion
                 (:rf.reply/stale-reason (:tags stale)))
              "the late carrier is classified a duplicate terminal against the tombstone"))))))

;; ---------------------------------------------------------------------------
;; 1bb. NARROWING — the join-completion coordinate is read ONLY from the
;;      recordable `:rf.cofx` slot; the event-vector metadata slot is not read
;;      (rf2-nsbwft). This is a slot narrowing, not a secrecy boundary
;;      (rf2-cpbjfp).
;; ---------------------------------------------------------------------------

(deftest exact-current-metadata-tuple-folds-nothing-slot-not-read
  (testing "rf2-nsbwft / rf2-cpbjfp — a public `rf/dispatch-sync` carrying an
            EXACT-CURRENT `:rf/join-attempt` coordinate on event-vector METADATA
            — every field read straight off live runtime state (parent/invoke
            identity, logical child id, current spawned instance address, current
            attempt token) — folds NOTHING: no `:done` fold, no resolution. Not
            because the coordinate is a forgery (it is exact-current, and the
            fence accepts exact-current coordinates on the cofx slot regardless
            of source), but because the METADATA SLOT IS NOT READ — a pure
            narrowing. `:attempt-unverified` is the fail-closed classification.
            Pre-fix `carrier-attempt`'s metadata fallback read it and flipped the
            unresolved join from `:done #{}` to `:done #{:a}` without child `:a`
            ever completing."
    (let [j (reg-join-parent! :sac/forge :sac/forgea :sac/forgeb
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])
          ;; An exact-current coordinate — every field matches live state — but
          ;; presented on the metadata slot, which is not read.
          exact-current {:parent-id  :sac/forge
                         :invoke-id  [:racing]
                         :child-id   :a
                         :spawned-id a
                         :attempt    (:rf/attempt j)}]
      (is (= #{} (:done j)) "precondition: child :a has not completed")
      (rf.machines.test-support/reset-captured!)
      ;; Present the exact-current coordinate on event-vector metadata — the
      ;; pre-fix metadata fallback read it; the fence no longer does.
      (rf/dispatch-sync [:sac/forge (with-meta [:child/done :a]
                                      {:rf/join-attempt exact-current})])
      (let [after (join-state :sac/forge [:racing])
            stale (first (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion))]
        (is (= #{} (:done after))
            "the metadata-borne coordinate folded NOTHING (slot not read)")
        (is (false? (:resolved? after))
            "the join did not resolve on the metadata carrier")
        (is (some? stale)
            "a stale-completion suppression fired for the coordinate-less carrier")
        (is (= :rf.machine.spawn-all/attempt-unverified
               (:rf.reply/stale-reason (:tags stale)))
            "the metadata slot is not read — classified :attempt-unverified"))
      ;; And a REAL child completion (through its own handler boundary, so the
      ;; coordinate rides the recordable `:rf.cofx` slot) still folds — the fix
      ;; removes ONLY the metadata read, not the framework-produced path.
      (rf/dispatch-sync [a [:go]])
      (is (= #{:a} (:done (join-state :sac/forge [:racing])))
          "a real child completion still folds once through the recordable transport"))))

;; ---------------------------------------------------------------------------
;; 1c. SCHEMA — the childless REJECT sentinel is a THIRD legal `:spawned` arm,
;;     and the COMPLETE Machines runtime-db validates with it present
;; ---------------------------------------------------------------------------

(deftest reject-sentinel-is-a-legal-spawned-arm
  (testing "rf2-2ai15g — an UNREGISTERED child TYPE in a `:spawn-all` set seeds
            the childless `InvokeAllRejectedState` sentinel; the COMPLETE
            `:rf.runtime/machines` runtime-db validates against the extracted
            `Machines` form during the legal pre-parent-exit interval, the
            sentinel validates as the reject arm, and both a sentinel carrying
            coordinate keys and a token-less live join FAIL the extracted
            `:spawned` union."
    (let [ok-child {:initial :running :data {} :states {:running {}}}
          ;; :sac/missing is NEVER reg-machine'd — the unregistered child TYPE.
          parent   {:initial :idle
                    :states
                    {:idle    {:on {:start :forking}}
                     :forking {:spawn-all
                               {:children        [{:id :ok      :machine-id :sac/rok}
                                                  {:id :missing :machine-id :sac/missing}]
                                :join            :all
                                :on-all-complete [:all/done]}}
                     :ready   {}}}]
      (rf/reg-machine :sac/rok ok-child)
      (rf/reg-machine :sac/rparent parent)
      ;; ALSO stand up a genuine LIVE join in the same frame, so the complete
      ;; runtime-db carries BOTH a reject sentinel and a live child-bearing
      ;; join at once — the mixed real value the `Machines` form must admit.
      (reg-join-parent! :sac/live :sac/livea :sac/liveb
                        {:join :all :on-all-complete [:all/done]})
      (rf/dispatch-sync [:sac/rparent [:start]])
      (let [sentinel (join-state :sac/rparent [:forking])]
        ;; the runtime seeded the childless reject sentinel (pre-parent-exit).
        (is (= {:rf/spawn-all-rejected? true} sentinel)
            "the reject sentinel (no :children) is seeded")
        ;; COMPLETE Machines runtime-db validates WITH the sentinel present.
        (is (m/validate Machines (machines-runtime-db))
            (str "the complete :rf.runtime/machines validates against the "
                 "extracted Machines form; explain: "
                 (m/explain Machines (machines-runtime-db))))
        ;; the sentinel is a legal reject arm ...
        (is (m/validate InvokeAllRejectedState sentinel)
            "the real sentinel validates against the extracted InvokeAllRejectedState")
        (is (m/validate SpawnedSlot {:sac/rparent {[:forking] sentinel}})
            "the sentinel is accepted by the extracted :spawned union")
        ;; ... but a sentinel that ALSO carries a coordinate is NOT a clean reject:
        ;; the closed map rejects the extra keys, and it is not a live join.
        (is (not (m/validate InvokeAllRejectedState (assoc sentinel :children {:a :a#1})))
            "a sentinel carrying :children FAILS the closed reject schema")
        (is (not (m/validate SpawnedSlot {:sac/rparent {[:forking] (assoc sentinel :rf/attempt 9)}}))
            "a sentinel carrying an attempt token FAILS the :spawned union")
        ;; a token-less live join fails BOTH arms → fails the union.
        (let [live (join-state :sac/live [:racing])]
          (is (m/validate SpawnedSlot {:sac/live {[:racing] live}})
              "the real live join is accepted by the :spawned union")
          (is (not (m/validate SpawnedSlot {:sac/live {[:racing] (dissoc live :rf/attempt)}}))
              "a token-less live join FAILS the :spawned union — not a valid live shape"))))))

;; ---------------------------------------------------------------------------
;; 2. CATALOGUE — `child-completed` emits `:rf.reply/correlation`
;; ---------------------------------------------------------------------------

(deftest child-completed-trace-emits-reply-correlation
  (testing "rf2-p53o47 — a NON-DECISIVE fold's
            `:rf.machine.spawn-all/child-completed` trace carries the
            emitted `:rf.reply/correlation` with the fold's parent/invoke
            identity, logical child id, and spawned instance address."
    (let [j (reg-join-parent! :sac/p2 :sac/p2a :sac/p2b
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])]
      ;; A folds first — non-decisive in a two-child :all join.
      (rf/dispatch-sync [a [:go]])
      (let [traces (rf.machines.test-support/events-of :rf.machine.spawn-all/child-completed)]
        (is (= 1 (count traces)) "one child-completed fold trace for A")
        (let [tags        (:tags (first traces))
              correlation (:rf.reply/correlation tags)]
          (is (some? correlation)
              "the child-completed trace emits :rf.reply/correlation")
          (is (= :sac/p2 (:parent-id correlation)) "correlation parent identity")
          (is (= [:racing] (:invoke-id correlation)) "correlation invoke identity")
          (is (= :a (:child-id correlation)) "correlation logical child id")
          (is (= a (:spawned-id correlation))
              "correlation spawned instance address"))))))

;; ---------------------------------------------------------------------------
;; 3. CATALOGUE — the spawn-all completion ops + `:rf.reply/stale-reason`
;;    classifier do not drift
;; ---------------------------------------------------------------------------

(deftest late-completion-op-classifies-by-stale-reason
  (testing "rf2-p53o47 — a POST-resolution straggler fires
            `:rf.machine.spawn-all/late-completion` classified by
            `:rf.reply/stale-reason` :rf.machine.spawn-all/join-resolved
            (the op the quick index must list; the classifier key is
            `:rf.reply/stale-reason`, not the bare `:reason`)."
    (let [j (reg-join-parent! :sac/p3 :sac/p3a :sac/p3b
                              {:join :any :on-some-complete [:any/done]})
          a (get-in j [:children :a])]
      ;; A resolves the :any join; the parent stays on :racing.
      (rf/dispatch-sync [a [:go]])
      (is (true? (:resolved? (join-state :sac/p3 [:racing]))) "the :any join resolved")
      (rf.machines.test-support/reset-captured!)
      ;; :a's EXACT-CURRENT completion re-completes AFTER the :resolved? latch
      ;; flipped — the late-completion path is gated on the exact-attempt fence
      ;; (rf2-ixjd48), so the carrier presents the current attempt's coordinate
      ;; on the recordable `:rf.cofx` slot (rf2-nsbwft).
      (rf/dispatch-sync
        [:sac/p3 [:child/done :a]]
        {:rf.cofx {:rf.machine/join-attempt {:parent-id  :sac/p3
                                          :invoke-id  [:racing]
                                          :child-id   :a
                                          :spawned-id a
                                          :attempt    (:rf/attempt (join-state :sac/p3 [:racing]))}}})
      (let [late (first (rf.machines.test-support/events-of :rf.machine.spawn-all/late-completion))]
        (is (some? late) "the late-completion op fired")
        (is (= :stale (:rf.reply/status (:tags late))))
        (is (= :suppressed (:rf.reply/work-status (:tags late))))
        (is (= :rf.machine.spawn-all/join-resolved
               (:rf.reply/stale-reason (:tags late)))
            "classified by :rf.reply/stale-reason (post-resolution)")))))

(deftest stale-completion-op-classifies-by-stale-reason
  (testing "rf2-p53o47 — a PRE-resolution unstamped carrier fires
            `:rf.machine.spawn-all/stale-completion` classified by
            `:rf.reply/stale-reason` :rf.machine.spawn-all/attempt-unverified
            — the classifier key is `:rf.reply/stale-reason`, guarding
            against a drift back to the bare `:reason`."
    (reg-join-parent! :sac/p4 :sac/p4a :sac/p4b
                      {:join :all :on-all-complete [:all/done]})
    (rf.machines.test-support/reset-captured!)
    ;; A bare hand-dispatched completion never flowed through the member
    ;; child's boundary, so it carries no runtime `:rf/join-attempt` stamp.
    (rf/dispatch-sync [:sac/p4 [:child/done :a]])
    (let [stale (first (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion))]
      (is (some? stale) "the stale-completion op fired")
      (is (= :stale (:rf.reply/status (:tags stale))))
      (is (= :rf.machine.spawn-all/attempt-unverified
             (:rf.reply/stale-reason (:tags stale)))
          "classified by :rf.reply/stale-reason (pre-resolution)"))))

;; ---------------------------------------------------------------------------
;; 4. CATALOGUE — the Spec-009 rows themselves carry the facts (narrow reads of
;;    the AUTHORITATIVE document; each assertion fails independently)
;; ---------------------------------------------------------------------------

(defn- quick-index-row
  "The ONE quick-index table row that pipe-lists the spawn-all lifecycle ops —
  the row containing both `started` and `child-completed`."
  [text]
  (->> (str/split-lines text)
       (filter #(and (str/starts-with? (str/triml %) "|")
                     (str/includes? % ":rf.machine.spawn-all/started")
                     (str/includes? % ":rf.machine.spawn-all/child-completed")))
       first))

(defn- detailed-row
  "The detailed-catalogue prose bullet that OPENS with `- `<op>`…`."
  [text op-kw]
  (->> (str/split-lines text)
       (filter #(str/starts-with? (str/triml %) (str "- `" op-kw "`")))
       first))

(defn- line-with [text needle]
  (->> (str/split-lines text) (filter #(str/includes? % needle)) first))

(deftest spec-009-catalogue-pins-the-spawn-all-ops
  (testing "rf2-2ai15g — the authoritative Spec-009 rows carry the exact
            vocabulary the runtime emits; each fact fails independently."
    (let [text (rf.spawn-all-schema-extract/spec-009-text)
          qidx (quick-index-row text)
          cc   (detailed-row text :rf.machine.spawn-all/child-completed)
          sc   (detailed-row text :rf.machine.spawn-all/stale-completion)]
      (is (some? qidx) "the spawn-all quick-index row exists")
      ;; late-completion must stay in the QUICK INDEX (not only the detailed row).
      (is (str/includes? qidx ":rf.machine.spawn-all/late-completion")
          "the quick index lists :rf.machine.spawn-all/late-completion")
      ;; the classifier note stays `:rf.reply/stale-reason`, not bare `:reason`.
      (is (str/includes? qidx ":rf.reply/stale-reason")
          "the quick index classifies the stale/late ops by :rf.reply/stale-reason")
      ;; child-completed correlation must stay documented.
      (is (some? cc) "the child-completed detailed row exists")
      (is (str/includes? cc ":rf.reply/correlation")
          "the child-completed row documents :rf.reply/correlation")
      ;; stale-completion classifier key stays `:rf.reply/stale-reason`.
      (is (some? sc) "the stale-completion detailed row exists")
      (is (str/includes? sc ":rf.reply/stale-reason")
          "the stale-completion row classifies by :rf.reply/stale-reason")
      ;; join-resolved is a stale-reason VALUE, never an operation — it appears
      ;; ONLY as `:rf.reply/stale-reason :rf.machine.spawn-all/join-resolved`.
      (let [jr (line-with text ":rf.machine.spawn-all/join-resolved")]
        (is (some? jr) "the join-resolved stale-reason value is documented")
        (is (str/includes? jr ":rf.reply/stale-reason :rf.machine.spawn-all/join-resolved")
            "join-resolved is presented as a :rf.reply/stale-reason VALUE, not an op")))))
