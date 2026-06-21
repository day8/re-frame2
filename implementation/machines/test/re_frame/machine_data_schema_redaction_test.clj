(ns re-frame.machine-data-schema-redaction-test
  "Machine `:data` trace-egress redaction via FRAME-DECLARED classification
  (the frame-owned sole app-db mechanism).

  Machine `:data` validation ships separately: a `:data-schema` Malli form
  validates the machine's `:data` slot, and the validation-failure trace
  routes its value slots through the schema-aware redactor. What this file
  pins is *snapshot* egress: a machine `:data` slot is redacted in the
  `:before` / `:after` / `:snapshot` / `:data` / `:input` / `:cascade` slots
  of `:rf.machine/*` traces when the FRAME declares its runtime-db snapshot
  path sensitive / large.

  Classification of durable `:data` for egress is frame-owned: the frame
  declares the machine snapshot's `:data` path via `reg-frame`
  `:sensitive` / `:large {:app-db …}` (the absolute runtime-db path
  `[:rf.runtime/machines :snapshots <actor-id> :data …]`); the trace egress
  chokepoint re-roots that snapshot-relative (`marks/frame-snapshot-marks`)
  and redacts the matching slot. A `:sensitive?` / `:large?` Malli prop on a
  `:data-schema` slot is VALIDATION ONLY and does not classify durable
  `:data` for egress.

  The contract under test:

   1. **Frame-declared redaction.** `project-trace-event` redacts a frame-
      declared `:data` path to `:rf/redacted` (sensitive) / the
      `:rf.size/large-elided` marker (large) inside every machine `:data` slot.

   2. **Precision.** An undeclared sibling slot rides egress verbatim; a
      machine whose frame declares nothing rides every `:data` slot raw.

   3. **Schema props do NOT classify.** A `:data-schema` `:sensitive?` slot
      does NOT, by itself, redact durable `:data` at snapshot egress — only
      a declared path (frame OR machine) does.

   4. **EP-0025 §subsystems (rf2-h3d8tf) — the rf2-398kql REVERSAL.** A
      top-level projection-relative `:sensitive` / `:large` key on a
      `reg-machine` spec IS the canonical classification route: it travels
      with the machine def and is LOWERED per actor instance at spawn /
      first-boot into the per-frame elision registry, redacts at snapshot
      egress through the SAME registry read path the frame mechanism uses, and
      is DROPPED at the instance's destroy (no leak). This supersedes the prior
      rf2-0k5ubx \"top-level key is not honoured\" disposition. The frame-owned
      absolute-path mechanism (tests 1-2) still functions — the registry read
      unions every source — but the machine declaration is the projection-
      relative, per-instance-applied canonical surface."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves.
            [re-frame.machines :as machines]
            [re-frame.marks :as marks]
            [re-frame.machines.test-support :as mtest]
            ;; The schemas artefact + Malli adapter — `:data-schema` VALIDATION
            ;; runs; the props do not classify durable :data.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- fixtures -------------------------------------------------------------

(def ^:private auth-id :rf.machine-redaction/auth)

(def ^:private auth-schema
  "A `:data-schema` is VALIDATION ONLY: per-slot props do not classify durable
  `:data` for snapshot egress; frame-declared paths are the sole egress
  mechanism. It carries `:sensitive?` / `:large?` props to PROVE they do not
  classify durable `:data` at snapshot egress (test (3))."
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

;; ---- (2b) FULL machine :data slot coverage -------------------------------

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

;; ---- (2c) :actor-id PREFERRED-branch coverage ----------------------------

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

;; ---- (3) a :data-schema :sensitive? prop does NOT classify durable :data --
;;          at snapshot egress.

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

;; ---- (4) EP-0025 §subsystems (rf2-h3d8tf) — the rf2-398kql REVERSAL: -------
;;          a top-level projection-relative `:sensitive` / `:large` key on
;;          `reg-machine` IS the canonical classification route, lowered per
;;          actor instance into the per-frame elision registry at spawn /
;;          first-boot and dropped at destroy. This SUPERSEDES the prior
;;          rf2-0k5ubx "top-level key is not honoured" disposition (authorised
;;          by Mike's B0 ruling).
;;
;; These tests drive LIVE machines (singleton + spawned) through the runtime so
;; the spawn / first-boot lowering + the destroy drop are exercised end-to-end:
;; the declaration travels with the machine def, the registry entry is added at
;; the instance's birth + DROPPED at its death (no leak), and the egress
;; chokepoint redacts the declared slot via the SAME registry read path the
;; frame-declared mechanism uses (`frame-snapshot-marks`).

(defn- snapshot-elision-reg
  "The `:rf/default` frame's elision registry, read off runtime-db. A nil
  registry (a frame that never classified) reads as `{}`."
  []
  (or (get (rf/runtime-db-value :rf/default) :rf.runtime/elision) {}))

(deftest machine-declared-classification-lowers-at-singleton-boot
  (testing "EP-0025 reversal — a SINGLETON's projection-relative :sensitive /
            :large `:data` declaration lowers into the per-frame elision registry
            at first-boot, redacts at snapshot egress, and is DROPPED at destroy"
    (let [mid :rf.machine-redaction/declared-singleton
          abs-token [:rf.runtime/machines :snapshots mid :data :token]
          abs-blob  [:rf.runtime/machines :snapshots mid :data :blob]]
      (rf/reg-machine mid
        {:sensitive [[:data :token]]
         :large     [[:data :blob]]
         :initial   :anon
         :data      {:token nil :blob nil :retries 0}
         :states    {:anon {:on {:login :authed}} :authed {}}})
      ;; registry empty before any instance is born
      (is (not (contains? (:sensitive-declarations (snapshot-elision-reg)) abs-token))
          "no registry entry before the singleton boots")
      ;; first dispatch boots the singleton → lowering fires
      (rf/dispatch-sync [mid [:rf.machine/noop]])
      (let [reg (snapshot-elision-reg)]
        (is (contains? (:sensitive-declarations reg) abs-token)
            "sensitive `:data` path lowered to the ABSOLUTE snapshot path at boot")
        (is (= :machine (get-in reg [:sensitive-declarations abs-token :source]))
            "lowered under :source :machine")
        (is (contains? (:declarations reg) abs-blob)
            "large `:data` path lowered at boot"))
      ;; the lowered registry entry redacts at the egress chokepoint, via the
      ;; SAME read path (frame-snapshot-marks) the frame-declared mechanism uses
      (let [out  (marks/project-trace-event (machine-transition-event mid))
            tags (:tags out)]
        (is (= :rf/redacted (get-in tags [:after :data :token]))
            "machine-declared sensitive slot redacts at snapshot egress")
        (is (contains? (get-in tags [:after :data :blob]) :rf.size/large-elided)
            "machine-declared large slot elides at snapshot egress")
        (is (= 1 (get-in tags [:after :data :retries])) "plain sibling rides verbatim")
        (is (not (.contains (pr-str out) "secret-jwt"))))
      ;; DESTROY drops the registry entry — no leak. The imperative
      ;; `[:rf.machine/destroy <id>]` teardown is an FX returned from a
      ;; handler (the XState-v5 `stopChild` spelling), not a directly
      ;; dispatched event.
      (rf/reg-event :rf.machine-redaction/destroy-singleton
        (fn [_ _] {:fx [[:rf.machine/destroy mid]]}))
      (rf/dispatch-sync [:rf.machine-redaction/destroy-singleton])
      (let [reg (snapshot-elision-reg)]
        (is (not (contains? (:sensitive-declarations reg) abs-token))
            "sensitive entry DROPPED at destroy (no leak)")
        (is (not (contains? (:declarations reg) abs-blob))
            "large entry DROPPED at destroy (no leak)")))))

(defn- live-instance-ids
  "The live spawned-actor instance ids under `:rf/default`'s machines
  snapshots map whose name starts with `prefix` (the `<type>#n` ids)."
  [prefix]
  (->> (get-in (rf/runtime-db-value :rf/default)
               [:rf.runtime/machines :snapshots])
       keys
       (filter #(clojure.string/starts-with? (name %) prefix))))

(deftest machine-declared-classification-lowers-per-spawned-instance
  (testing "EP-0025 reversal — a SPAWNED actor's :data declaration travels with
            the machine def and lowers PER INSTANCE at spawn (the generated
            <type>#n is classified with no per-instance author code), dropped at
            the actor's destroy"
    (let [child-type :rf.machine-redaction/charge]
      (rf/reg-machine child-type
        {:sensitive [[:data :token]]
         :initial   :charging
         :data      {:token nil}
         :states    {:charging {:on {:done :done}} :done {}}})
      (rf/reg-machine :rf.machine-redaction/supervisor
        {:initial :idle
         :data    {}
         :states  {:idle    {:on {:go :working}}
                   :working {:spawn {:machine-id child-type}
                             :on    {:stop :idle}}}})
      (rf/dispatch-sync [:rf.machine-redaction/supervisor [:go]])
      (let [spawned-id (first (live-instance-ids "charge"))]
        (is (some? spawned-id) "a child actor instance was spawned")
        (let [abs-token [:rf.runtime/machines :snapshots spawned-id :data :token]
              reg       (snapshot-elision-reg)]
          (is (contains? (:sensitive-declarations reg) abs-token)
              "the spawned instance's :data :token lowered at spawn (per-instance)")
          (is (= :machine (get-in reg [:sensitive-declarations abs-token :source])))
          ;; egress redacts the spawned instance's declared slot
          (let [out (marks/project-trace-event
                      {:operation :rf.machine/snapshot-updated
                       :tags      {:actor-id spawned-id
                                   :frame    :rf/default
                                   :snapshot {:state :charging
                                              :data  {:token "secret-child-jwt"}}}})]
            (is (= :rf/redacted (get-in out [:tags :snapshot :data :token]))
                "spawned instance's declared slot redacts at egress")
            (is (not (.contains (pr-str out) "secret-child-jwt"))))
          ;; the parent exits :working → the spawned child is destroyed
          (rf/dispatch-sync [:rf.machine-redaction/supervisor [:stop]])
          (is (not (contains? (:sensitive-declarations (snapshot-elision-reg)) abs-token))
              "spawned instance's entry DROPPED at its destroy (no leak)"))))))

(deftest machine-classification-malformed-declaration-fails-loud
  (testing "EP-0025 fail-loud-input — a malformed :sensitive / :large machine
            declaration is rejected at reg-machine with
            :rf.error/invalid-machine-classification"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"invalid-machine-classification|malformed"
          (rf/reg-machine :rf.machine-redaction/bad-decl
            {:sensitive {:data [:token]}   ;; a MAP, not a vector-of-paths
             :initial   :idle
             :data      {}
             :states    {:idle {}}}))
        "a non-vector :sensitive axis is rejected at registration")))
