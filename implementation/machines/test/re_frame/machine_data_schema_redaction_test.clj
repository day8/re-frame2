(ns re-frame.machine-data-schema-redaction-test
  "EP-0025 (rf2-398kql) — machine `:data` trace-egress redaction via FRAME-
  DECLARED classification (the frame-owned sole app-db mechanism).

  Machine `:data` validation ships separately (rf2-jbbp7): a `:data-schema`
  Malli form validates the machine's `:data` slot, and the validation-failure
  trace routes its value slots through the schema-aware redactor — UNCHANGED by
  EP-0025. What this file pins is *snapshot* egress: a machine `:data` slot is
  redacted in the `:before` / `:after` / `:snapshot` / `:data` / `:input` /
  `:cascade` slots of `:rf.machine/*` traces when the FRAME declares its
  runtime-db snapshot path sensitive / large.

  EP-0025 REVERSES the EP-0005 schema→marks bridge: a `:sensitive?` / `:large?`
  Malli prop on a `:data-schema` slot NO LONGER classifies durable `:data` for
  egress. Instead the frame declares the machine snapshot's `:data` path via
  `reg-frame` `:sensitive` / `:large {:app-db …}` (the absolute runtime-db path
  `[:rf.runtime/machines :snapshots <actor-id> :data …]`); the trace egress
  chokepoint re-roots that snapshot-relative (`marks/frame-snapshot-marks`) and
  redacts the matching slot.

  The contract under test:

   1. **Frame-declared redaction.** `project-trace-event` redacts a frame-
      declared `:data` path to `:rf/redacted` (sensitive) / the
      `:rf.size/large-elided` marker (large) inside every machine `:data` slot.

   2. **Precision.** An undeclared sibling slot rides egress verbatim; a
      machine whose frame declares nothing rides every `:data` slot raw.

   3. **Schema props do NOT classify (EP-0025 reversal).** A `:data-schema`
      `:sensitive?` slot does NOT, by itself, redact durable `:data` at
      snapshot egress — only the frame-declared path does.

   4. **No top-level machine classification key.** A top-level `:sensitive` /
      `:large` key on a `reg-machine` spec is ignored (rf2-0k5ubx)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves.
            [re-frame.machines :as machines]
            [re-frame.marks :as marks]
            [re-frame.machines.test-support :as mtest]
            ;; The schemas artefact + Malli adapter — `:data-schema` VALIDATION
            ;; still runs; the props just no longer classify durable :data.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- fixtures -------------------------------------------------------------

(def ^:private auth-id :rf.machine-redaction/auth)

(def ^:private auth-schema
  "A `:data-schema` — VALIDATION ONLY (EP-0025: per-slot props no longer
  classify). It still carries `:sensitive?` / `:large?` props to PROVE they
  do not classify durable `:data` at snapshot egress (test (3))."
  [:map
   [:retries :int]
   [:token   {:sensitive? true} [:maybe :string]]
   [:blob    {:large? true}     [:maybe :string]]])

(defn- reg-auth-machine!
  "Register the auth machine carrying `auth-schema` as its `:data-schema`."
  ([] (reg-auth-machine! auth-id))
  ([machine-id]
   (machines/reg-machine* machine-id
     {:initial     :anon
      :data        {:retries 0 :token nil :blob nil}
      :data-schema auth-schema
      :states      {:anon  {:on {:login :authed}}
                    :authed {}}})))

(defn- declare-frame-marks!
  "Declare the machine snapshot's `:data` token slot SENSITIVE and the blob slot
  LARGE on `:rf/default` — the frame-owned classification (the sole app-db
  mechanism), keyed by the absolute runtime-db snapshot path. `marks/add-marks`
  writes the `:rf/default` frame's elision registry that `frame-snapshot-marks`
  reads at trace egress."
  ([] (declare-frame-marks! auth-id))
  ([actor-id]
   (marks/add-marks :rf/default
     {[:rf.runtime/machines :snapshots actor-id :data :token] :sensitive
      [:rf.runtime/machines :snapshots actor-id :data :blob]  :large})))

(defn- machine-transition-event*
  "Build a `:rf.machine/transition` trace event whose `:before` / `:after`
  snapshots carry a populated `:data` (token + blob + a plain sibling), with the
  addressed id keyed under `id-key` and the frame stamped `:rf/default`."
  [id-key machine-id]
  {:operation :rf.machine/transition
   :tags      {id-key      machine-id
               :frame      :rf/default
               :before     {:state :anon
                            :data  {:retries 0
                                    :token   "secret-jwt-before"
                                    :blob    "huge-before"}}
               :after      {:state :authed
                            :data  {:retries 1
                                    :token   "secret-jwt-after"
                                    :blob    "huge-after"}}}})

(defn- machine-transition-event
  "The `:machine-id`-keyed transition event (the FALLBACK lookup branch)."
  [machine-id]
  (machine-transition-event* :machine-id machine-id))

;; ---- (1) frame-snapshot-marks re-rooting ----------------------------------

(deftest frame-snapshot-marks-rooted-under-data
  (testing "the frame's absolute snapshot-path declarations re-root snapshot-
            relative under [:data …] via marks/frame-snapshot-marks"
    (declare-frame-marks!)
    (let [m (marks/frame-snapshot-marks :rf/default auth-id)]
      (is (some? m) "a frame-declared marks set exists for the snapshot")
      (is (= #{[:data :token]} (set (:sensitive m)))
          "sensitive path re-rooted snapshot-relative")
      (is (= #{[:data :blob]} (set (:large m)))
          "large path re-rooted snapshot-relative"))))

(deftest no-frame-declaration-no-marks
  (testing "a machine whose frame declares nothing has no snapshot marks"
    (reg-auth-machine!)
    (is (nil? (marks/frame-snapshot-marks :rf/default auth-id))
        "no frame declaration → no snapshot marks")))

;; ---- (2) egress redaction at the chokepoint -------------------------------

(deftest sensitive-slot-redacted-in-egress
  (testing "a frame-declared sensitive :data path is redacted to :rf/redacted in
            :before / :after snapshot egress; the plain sibling rides verbatim"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [out  (marks/project-trace-event (machine-transition-event auth-id))
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:before :data :token])))
      (is (= :rf/redacted (get-in tags [:after :data :token])))
      (is (= 0 (get-in tags [:before :data :retries])))
      (is (= 1 (get-in tags [:after :data :retries])))
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw token leaked into the projected trace"))))

(deftest large-slot-marked-in-egress
  (testing "a frame-declared large :data path is replaced by the
            :rf.size/large-elided marker in snapshot egress"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [out  (marks/project-trace-event (machine-transition-event auth-id))
          tags (:tags out)]
      (is (contains? (get-in tags [:before :data :blob]) :rf.size/large-elided))
      (is (contains? (get-in tags [:after :data :blob]) :rf.size/large-elided))
      (is (not (.contains (pr-str out) "huge-before"))))))

(deftest snapshot-slot-redacted-in-egress
  (testing "the :snapshot slot (on :rf.machine/snapshot-updated) redacts the
            same way as :before / :after"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [ev   {:operation :rf.machine/snapshot-updated
                :tags      {:machine-id auth-id
                            :frame      :rf/default
                            :snapshot   {:state :authed
                                         :data  {:retries 2
                                                 :token   "secret-jwt-snap"
                                                 :blob    nil}}}}
          out  (marks/project-trace-event ev)]
      (is (= :rf/redacted (get-in out [:tags :snapshot :data :token])))
      (is (not (.contains (pr-str out) "secret-jwt-snap"))))))

;; ---- (2b) FULL machine :data slot coverage (rf2-20d6k2) -------------------

(deftest started-data-slot-redacted-in-egress
  (testing ":rf.machine/started carries the booted snapshot's :data MAP
            directly (one level shallower than a snapshot); a frame-declared
            slot redacts / elides"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [ev   {:operation :rf.machine/started
                :tags      {:machine-id auth-id
                            :frame      :rf/default
                            :state      :anon
                            :data       {:retries 0
                                         :token   "secret-jwt-started"
                                         :blob    "huge-started"}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:data :token])))
      (is (contains? (get-in tags [:data :blob]) :rf.size/large-elided))
      (is (= 0 (get-in tags [:data :retries])) "plain sibling rides verbatim")
      (is (not (.contains (pr-str out) "secret-jwt-started"))))))

(deftest guard-evaluated-input-data-redacted-in-egress
  (testing ":rf.machine/guard-evaluated carries :input {:data … :event …}; the
            :data sub-slot redacts, :event is left to project-event-tags"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [ev   {:operation :rf.machine/guard-evaluated
                :tags      {:machine-id auth-id
                            :frame      :rf/default
                            :guard-id   :ready?
                            :state      :anon
                            :outcome    :pass
                            :input      {:data  {:retries 0
                                                 :token   "secret-jwt-guard"
                                                 :blob    "huge-guard"}
                                         :event [:login]}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:input :data :token])))
      (is (contains? (get-in tags [:input :data :blob]) :rf.size/large-elided))
      (is (= [:login] (get-in tags [:input :event]))
          ":input :event passes through (not machine :data)")
      (is (not (.contains (pr-str out) "secret-jwt-guard"))))))

(deftest action-ran-input-data-redacted-in-egress
  (testing ":rf.machine/action-ran carries :input {:data … :event …}; the :data
            sub-slot redacts"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [ev   {:operation :rf.machine/action-ran
                :tags      {:machine-id auth-id
                            :frame      :rf/default
                            :action-id  :tap
                            :phase      :transition
                            :outcome    :ok
                            :input      {:data  {:retries 1
                                                 :token   "secret-jwt-action"
                                                 :blob    "huge-action"}
                                         :event [:login]}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:input :data :token])))
      (is (contains? (get-in tags [:input :data :blob]) :rf.size/large-elided))
      (is (not (.contains (pr-str out) "secret-jwt-action"))))))

(deftest transition-cascade-data-deltas-redacted-in-egress
  (testing "a :rf.machine/transition's :cascade carries per-step :data-delta
            maps keyed by :data keys directly; each delta redacts"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [ev   {:operation :rf.machine/transition
                :tags      {:machine-id auth-id
                            :frame      :rf/default
                            :before     {:state :anon  :data {:retries 0 :token nil :blob nil}}
                            :after      {:state :authed :data {:retries 1
                                                               :token "secret-jwt-after"
                                                               :blob "huge-after"}}
                            :microsteps 0
                            :cascade    [{:kind   :action
                                          :state  []
                                          :region nil
                                          :action :authenticate
                                          :data-delta {:token "secret-jwt-delta"
                                                       :blob  "huge-delta"
                                                       :retries 1}}
                                         {:kind   :entry
                                          :state  [:authed]
                                          :region nil
                                          :action nil
                                          :data-delta {}}]}}
          out     (marks/project-trace-event ev)
          cascade (get-in out [:tags :cascade])
          step0   (first cascade)]
      (is (= :rf/redacted (get-in step0 [:data-delta :token])))
      (is (contains? (get-in step0 [:data-delta :blob]) :rf.size/large-elided))
      (is (= 1 (get-in step0 [:data-delta :retries])) "plain key rides verbatim")
      (is (= {} (get-in cascade [1 :data-delta])) "empty :data-delta unchanged")
      (is (= :rf/redacted (get-in out [:tags :after :data :token])))
      (is (not (.contains (pr-str out) "secret-jwt-delta")))
      (is (not (.contains (pr-str out) "secret-jwt-after"))))))

(deftest data-slot-untouched-for-undeclared-machine
  (testing "a machine whose frame declares nothing rides every :data slot
            verbatim (the seam is precise — no blanket scrub)"
    (rf/reg-machine :rf.machine-redaction/plain2
      {:initial :idle :data {:token "plain"} :states {:idle {}}})
    (let [ev   {:operation :rf.machine/started
                :tags      {:machine-id :rf.machine-redaction/plain2
                            :frame      :rf/default
                            :state      :idle
                            :data       {:token "not-secret"}}}
          out  (marks/project-trace-event ev)]
      (is (= "not-secret" (get-in out [:tags :data :token]))
          "no frame declaration → :data slot verbatim"))))

;; ---- (2c) :actor-id PREFERRED-branch coverage (rf2-sxmeqs) ----------------

(deftest sensitive-slot-redacted-in-egress-actor-id
  (testing "an :actor-id-keyed transition (the PREFERRED lookup branch
            production emits) redacts the frame-declared slot in :before /
            :after exactly like the :machine-id case"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [out  (marks/project-trace-event
                 (machine-transition-event* :actor-id auth-id))
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:before :data :token])))
      (is (= :rf/redacted (get-in tags [:after :data :token])))
      (is (= 0 (get-in tags [:before :data :retries])) "plain sibling untouched")
      (is (= 1 (get-in tags [:after :data :retries])))
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw token leaked via the :actor-id branch"))))

(deftest actor-id-takes-precedence-over-machine-id-in-snapshot-egress
  (testing "when a transition carries BOTH ids the lookup PREFERS :actor-id:
            a frame declaration on the :actor-id snapshot path redacts even
            though :machine-id points at an UNDECLARED machine"
    (reg-auth-machine!)
    (rf/reg-machine :rf.machine-redaction/undeclared-sibling
      {:initial :idle :data {:n 0} :states {:idle {}}})
    (declare-frame-marks! auth-id)
    (let [ev   (-> (machine-transition-event* :actor-id auth-id)
                   (assoc-in [:tags :machine-id] :rf.machine-redaction/undeclared-sibling))
          out  (marks/project-trace-event ev)]
      (is (= :rf/redacted (get-in out [:tags :after :data :token]))
          "redacted via the PREFERRED :actor-id (declared), not the :machine-id sibling")
      (is (not (.contains (pr-str out) "secret-jwt"))))))

;; ---- (3) EP-0025 reversal: a :data-schema :sensitive? prop does NOT --------
;;          classify durable :data at snapshot egress.

(deftest schema-sensitive-prop-does-not-classify-durable-data
  (testing "EP-0025 reversal — a :data-schema :sensitive? / :large? slot prop
            does NOT, by itself, redact durable :data at snapshot egress; with
            NO frame declaration the marked slot rides RAW (the schema→marks
            bridge is removed; frame-declared paths are the sole mechanism)"
    ;; auth-schema carries :token {:sensitive? true} + :blob {:large? true},
    ;; but we declare NOTHING on the frame.
    (reg-auth-machine!)
    (let [out  (marks/project-trace-event (machine-transition-event auth-id))
          tags (:tags out)]
      (is (= "secret-jwt-after" (get-in tags [:after :data :token]))
          "a :sensitive? :data-schema slot does NOT redact without a frame declaration")
      (is (= "huge-after" (get-in tags [:after :data :blob]))
          "a :large? :data-schema slot does NOT elide without a frame declaration"))))

;; ---- (4) NEGATIVE: a top-level machine :sensitive / :large key is NOT a ----
;;          classification route (rf2-0k5ubx 2026-06-09; EP-0025).
;;
;; A considered proposal to add TOP-LEVEL machine `:sensitive` / `:large` keys
;; (the spelling frames DO take) was REJECTED — `reg-machine` carries no such
;; key. Per EP-0025 machine :data classification belongs on the FRAME. The
;; validator walks only the grammar keys, so a top-level key is IGNORED (not
;; rejected), and it feeds NO classification — a token under such a spec rides
;; RAW at snapshot egress unless the FRAME declares it.

(deftest top-level-machine-sensitive-key-is-not-honoured
  (testing "a TOP-LEVEL :sensitive / :large key on a reg-machine spec is NOT a
            classification route (rf2-0k5ubx): registration does not throw, and
            a token written into :data under such a spec rides RAW at snapshot
            egress — classification belongs on the FRAME (EP-0025)"
    (let [neg-id :rf.machine-redaction/top-level-sensitive
          registered?
          (try
            (rf/reg-machine neg-id
              {:sensitive [[:data :token]]    ;; NOT a route — the rejected spelling
               :large     [[:data :blob]]      ;; NOT a route
               :initial   :anon
               :data      {:token nil :blob nil}
               :states    {:anon {} :authed {}}})
            true
            (catch clojure.lang.ExceptionInfo _ false)
            (catch Throwable _ false))]
      (is (true? registered?)
          "reg-machine does not throw on a top-level :sensitive / :large key
           — the key is ignored, never honoured as a classification route")
      ;; Provably a no-op at egress: a snapshot-updated trace carrying a live
      ;; token in :data rides RAW — nothing classified it (no frame declaration,
      ;; and the top-level machine key is inert).
      (let [ev   {:operation :rf.machine/snapshot-updated
                  :tags      {:machine-id neg-id
                              :frame      :rf/default
                              :snapshot   {:state :authed
                                           :data  {:token "rides-raw-jwt"
                                                   :blob  "rides-raw-blob"}}}}
            out  (marks/project-trace-event ev)
            tags (:tags out)]
        (is (= "rides-raw-jwt" (get-in tags [:snapshot :data :token]))
            "the token rides RAW — a top-level machine :sensitive key did NOT classify it")
        (is (= "rides-raw-blob" (get-in tags [:snapshot :data :blob]))
            "the blob rides RAW — a top-level machine :large key is inert")))))
