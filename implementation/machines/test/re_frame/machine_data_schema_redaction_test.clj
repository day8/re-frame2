(ns re-frame.machine-data-schema-redaction-test
  "EP-0005 (rf2-w46fpt) — the per-slot `:data-schema` → snapshot-egress
  redaction bridge.

  Machine `:data` validation already shipped (rf2-jbbp7): a `:data-schema`
  Malli form validates the machine's `:data` slot, and the validation-failure
  trace already routes its value slots through the schema-aware redactor
  (rf2-o69h5). What this bead adds: a `:sensitive?` / `:large?` Malli marker on
  a `:data-schema` slot is ALSO honoured in *snapshot* egress — the `:before` /
  `:after` / `:snapshot` slots on every `:rf.machine/transition` /
  `:rf.machine/snapshot-updated` — exactly like an app-db schema's per-slot
  markers feed app-db elision (Spec 015 §6 State machines).

  The contract under test:

   1. **Per-slot extraction.** `reg-machine` extracts the marked per-slot
      paths from `:data-schema` (Malli `:sensitive?` / `:large?` props),
      roots them under `[:data …]` to match the snapshot shape, and stashes
      them in the machine's `:event`-keyed marks entry.

   2. **Egress redaction.** The egress chokepoint `project-trace-event`
      redacts a `:sensitive?` slot to `:rf/redacted` (and a `:large?` slot to
      the `:rf.size/large-elided` marker) inside `:before` / `:after` /
      `:snapshot` `:data` on a machine-snapshot trace.

   3. **UNION with manual register-marks! (Mike ruling #3).** A machine with
      BOTH a `:data-schema` and a manually-registered mark set gets the UNION
      of both — neither clobbers the other (Spec 015 §union-by-source).

   4. **Precision.** A machine with no `:data-schema` (or a schema with no
      marked slot) registers no schema marks; a non-sensitive sibling slot
      rides egress verbatim."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves.
            [re-frame.machines]
            [re-frame.marks :as marks]
            ;; The schemas artefact ships the walker the bridge consults via
            ;; late-bind (`extract-sensitive-paths-from-schema` /
            ;; `extract-large-paths-from-schema`); the `.malli` adapter ns
            ;; publishes the default validator.
            [re-frame.machines.test-support :as mtest]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- fixtures -------------------------------------------------------------

(def ^:private auth-id :rf.machine-redaction/auth)

(def ^:private auth-schema
  "A `:data-schema` with one sensitive slot and one large slot, plus a plain
  sibling that must ride egress verbatim."
  [:map
   [:retries :int]
   [:token   {:sensitive? true} [:maybe :string]]
   [:blob    {:large? true}     [:maybe :string]]])

(defn- reg-auth-machine!
  "Register a machine carrying `auth-schema` as its `:data-schema`."
  ([] (reg-auth-machine! auth-id))
  ([machine-id]
   (rf/reg-machine machine-id
     {:initial     :anon
      :data        {:retries 0 :token nil :blob nil}
      :data-schema auth-schema
      :states      {:anon  {:on {:login :authed}}
                    :authed {}}})))

(defn- machine-transition-event
  "Build a `:rf.machine/transition` trace event whose `:before` / `:after`
  snapshots carry a populated `:data` (token + blob + a plain sibling)."
  [machine-id]
  {:operation :rf.machine/transition
   :tags      {:machine-id machine-id
               :frame      :rf/default
               :before     {:state :anon
                            :data  {:retries 0
                                    :token   "secret-jwt-before"
                                    :blob    "huge-before"}}
               :after      {:state :authed
                            :data  {:retries 1
                                    :token   "secret-jwt-after"
                                    :blob    "huge-after"}}}})

;; ---- (1) per-slot extraction + rooting under [:data …] --------------------

(deftest schema-marks-rooted-under-data
  (testing "reg-machine extracts :data-schema :sensitive? / :large? slots and
            roots them under [:data …] in the :event-keyed marks entry"
    (reg-auth-machine!)
    (let [m (marks/marks-for :event auth-id)]
      (is (some? m) "a marks entry exists for the machine")
      (is (= #{[:data :token]} (set (:sensitive m)))
          ":sensitive? slot extracted + snapshot-rooted")
      (is (= #{[:data :blob]} (set (:large m)))
          ":large? slot extracted + snapshot-rooted"))))

(deftest no-schema-no-marks
  (testing "a machine without :data-schema registers no schema marks"
    (rf/reg-machine :rf.machine-redaction/plain
      {:initial :idle
       :data    {:token "secret"}
       :states  {:idle {}}})
    (is (nil? (marks/marks-for :event :rf.machine-redaction/plain))
        "no marks entry for a schemaless machine")))

(deftest schema-with-no-marked-slot-registers-nothing
  (testing "a :data-schema whose slots carry no :sensitive? / :large? prop
            registers no schema marks (precision)"
    (rf/reg-machine :rf.machine-redaction/unmarked
      {:initial     :idle
       :data        {:n 0}
       :data-schema [:map [:n :int]]
       :states      {:idle {}}})
    (is (nil? (marks/marks-for :event :rf.machine-redaction/unmarked))
        "no marks entry when no slot is marked")))

;; ---- (2) egress redaction at the chokepoint -------------------------------

(deftest sensitive-slot-redacted-in-egress
  (testing "a :sensitive? :data-schema slot is redacted to :rf/redacted in
            :before / :after snapshot egress; the plain sibling rides verbatim"
    (reg-auth-machine!)
    (let [out  (marks/project-trace-event (machine-transition-event auth-id))
          tags (:tags out)]
      ;; sensitive token redacted both sides
      (is (= :rf/redacted (get-in tags [:before :data :token])))
      (is (= :rf/redacted (get-in tags [:after :data :token])))
      ;; plain sibling untouched
      (is (= 0 (get-in tags [:before :data :retries])))
      (is (= 1 (get-in tags [:after :data :retries])))
      ;; the raw secret never survives anywhere in the projected event
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw token leaked into the projected trace"))))

(deftest large-slot-marked-in-egress
  (testing "a :large? :data-schema slot is replaced by the
            :rf.size/large-elided marker in snapshot egress"
    (reg-auth-machine!)
    (let [out  (marks/project-trace-event (machine-transition-event auth-id))
          tags (:tags out)]
      (is (contains? (get-in tags [:before :data :blob]) :rf.size/large-elided)
          ":large? slot replaced by the size marker (before)")
      (is (contains? (get-in tags [:after :data :blob]) :rf.size/large-elided)
          ":large? slot replaced by the size marker (after)")
      (is (not (.contains (pr-str out) "huge-before"))
          "no raw large value leaked"))))

(deftest snapshot-slot-redacted-in-egress
  (testing "the :snapshot slot (on :rf.machine/snapshot-updated) is redacted
            the same way as :before / :after"
    (reg-auth-machine!)
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
;;
;; The bridge originally protected only the snapshot-shaped slots (:before /
;; :after / :snapshot). But machine :data surfaces in several other trace
;; slots that carried it RAW: :rf.machine/started's direct :data; the
;; :input {:data …} of :rf.machine/guard-evaluated / :rf.machine/action-ran;
;; and the per-step :data-delta of a :rf.machine/transition's :cascade.
;; project-machine-tags now redacts every one against the SAME machine marks.

(deftest started-data-slot-redacted-in-egress
  (testing ":rf.machine/started carries the booted snapshot's :data MAP
            directly (one level shallower than a snapshot); the :sensitive?
            slot redacts and the :large? slot elides"
    (reg-auth-machine!)
    (let [ev   {:operation :rf.machine/started
                :tags      {:machine-id auth-id
                            :frame      :rf/default
                            :state      :anon
                            :data       {:retries 0
                                         :token   "secret-jwt-started"
                                         :blob    "huge-started"}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:data :token]))
          ":sensitive? :data slot redacts on :rf.machine/started")
      (is (contains? (get-in tags [:data :blob]) :rf.size/large-elided)
          ":large? :data slot elides on :rf.machine/started")
      (is (= 0 (get-in tags [:data :retries]))
          "plain sibling rides verbatim")
      (is (not (.contains (pr-str out) "secret-jwt-started"))
          "no raw token leaked from the :rf.machine/started :data slot"))))

(deftest guard-evaluated-input-data-redacted-in-egress
  (testing ":rf.machine/guard-evaluated carries :input {:data … :event …};
            the :data sub-slot redacts, :event is left to project-event-tags"
    (reg-auth-machine!)
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
      (is (= :rf/redacted (get-in tags [:input :data :token]))
          ":sensitive? slot inside :input :data redacts")
      (is (contains? (get-in tags [:input :data :blob]) :rf.size/large-elided)
          ":large? slot inside :input :data elides")
      (is (= [:login] (get-in tags [:input :event]))
          ":input :event passes through (not machine :data)")
      (is (not (.contains (pr-str out) "secret-jwt-guard"))
          "no raw token leaked from guard-evaluated :input :data"))))

(deftest action-ran-input-data-redacted-in-egress
  (testing ":rf.machine/action-ran carries :input {:data … :event …}; the
            :data sub-slot redacts"
    (reg-auth-machine!)
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
      (is (= :rf/redacted (get-in tags [:input :data :token]))
          ":sensitive? slot inside :input :data redacts on action-ran")
      (is (contains? (get-in tags [:input :data :blob]) :rf.size/large-elided)
          ":large? slot inside :input :data elides on action-ran")
      (is (not (.contains (pr-str out) "secret-jwt-action"))
          "no raw token leaked from action-ran :input :data"))))

(deftest transition-cascade-data-deltas-redacted-in-egress
  (testing "a :rf.machine/transition's :cascade carries per-step :data-delta
            maps keyed by :data keys directly; each delta redacts"
    (reg-auth-machine!)
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
                                          ;; the action wrote a sensitive token
                                          ;; + a large blob + a plain sibling
                                          :data-delta {:token "secret-jwt-delta"
                                                       :blob  "huge-delta"
                                                       :retries 1}}
                                         {:kind   :entry
                                          :state  [:authed]
                                          :region nil
                                          :action nil
                                          :data-delta {}}]}}
          out  (marks/project-trace-event ev)
          cascade (get-in out [:tags :cascade])
          step0   (first cascade)]
      (is (= :rf/redacted (get-in step0 [:data-delta :token]))
          "the cascade step's :sensitive? :data-delta key redacts")
      (is (contains? (get-in step0 [:data-delta :blob]) :rf.size/large-elided)
          "the cascade step's :large? :data-delta key elides")
      (is (= 1 (get-in step0 [:data-delta :retries]))
          "a plain :data-delta key rides verbatim")
      (is (= {} (get-in cascade [1 :data-delta]))
          "an empty :data-delta passes through unchanged")
      ;; The headline :after snapshot is also redacted (existing coverage).
      (is (= :rf/redacted (get-in out [:tags :after :data :token])))
      (is (not (.contains (pr-str out) "secret-jwt-delta"))
          "no raw token leaked from a cascade :data-delta")
      (is (not (.contains (pr-str out) "secret-jwt-after"))
          "no raw token leaked from the headline :after snapshot"))))

(deftest started-data-slot-untouched-for-schemaless-machine
  (testing "a machine with no marks rides every :data slot verbatim (the
            seam is precise — no blanket scrub)"
    (rf/reg-machine :rf.machine-redaction/plain2
      {:initial :idle :data {:token "plain"} :states {:idle {}}})
    (let [ev   {:operation :rf.machine/started
                :tags      {:machine-id :rf.machine-redaction/plain2
                            :frame      :rf/default
                            :state      :idle
                            :data       {:token "not-secret"}}}
          out  (marks/project-trace-event ev)]
      (is (= "not-secret" (get-in out [:tags :data :token]))
          "no marks → :data slot verbatim"))))

;; ---- (3) UNION with a manual register-marks! (Mike ruling #3) -------------

(deftest schema-marks-union-manual-register-marks
  (testing "a machine with BOTH a :data-schema AND a manual register-marks!
            gets the UNION of both mark sets — neither clobbers the other"
    ;; Manual marks first (the spec-realistic order: an author declares the
    ;; non-schema'd path manually, then the schema bridge unions its slots on
    ;; top — mirroring reg-app-schema + add-marks composition for app-db).
    (marks/register-marks! :event auth-id
                           {:sensitive [[:data :session-id]]})
    (reg-auth-machine!)
    (let [m (marks/marks-for :event auth-id)]
      (is (= #{[:data :session-id] [:data :token]} (set (:sensitive m)))
          "schema-sourced :token UNIONs with the manual :session-id")
      (is (= #{[:data :blob]} (set (:large m)))
          "schema-sourced :large slot present"))
    ;; And both redact at egress.
    (let [ev  {:operation :rf.machine/transition
               :tags      {:machine-id auth-id
                           :frame      :rf/default
                           :after      {:state :authed
                                        :data  {:retries    1
                                                :token      "secret-jwt"
                                                :session-id "sess-abc-123"
                                                :blob       nil}}}}
          out (marks/project-trace-event ev)]
      (is (= :rf/redacted (get-in out [:tags :after :data :token]))
          "schema-sourced path redacts")
      (is (= :rf/redacted (get-in out [:tags :after :data :session-id]))
          "manual-sourced path redacts")
      (is (not (.contains (pr-str out) "secret-jwt")))
      (is (not (.contains (pr-str out) "sess-abc-123"))))))

(deftest manual-marks-preserved-when-registered-after-schema
  (testing "the schema bridge is additive: re-running a manual register-marks!
            after registration replaces only that manual write — but the
            spec-realistic + bridge composition is the union-first order
            asserted above. Here we confirm union-marks! itself does not drop
            schema paths when invoked a second time."
    (reg-auth-machine!)
    ;; A second union (e.g. another feature contributing a mark) must keep the
    ;; schema-sourced :token.
    (marks/union-marks! :event auth-id {:sensitive [[:data :extra]]})
    (let [m (marks/marks-for :event auth-id)]
      (is (= #{[:data :token] [:data :extra]} (set (:sensitive m)))
          "union-marks! preserves the schema-sourced path"))))

;; ---- (3b) ORDER-INDEPENDENT union via the real reg-machine path (rf2-qpibk0)
;;
;; The bead's core failure: a `register-marks!` (not `union-marks!`) called
;; AFTER `reg-machine` previously REPLACED the `:event` entry and dropped the
;; schema-derived `[:data …]` marks. The separate schema-marks table + read-
;; time union (plus skipping `reg-event-fx`'s bare-meta clear for machines)
;; makes BOTH orders yield the identical union.

(deftest manual-register-marks-before-reg-machine-unions
  (testing "register-marks! BEFORE reg-machine: the manual path survives
            reg-machine (no bare-meta clobber) and unions with the schema marks"
    (marks/register-marks! :event auth-id {:sensitive [[:data :session-id]]})
    (reg-auth-machine!)
    (let [m (marks/marks-for :event auth-id)]
      (is (= #{[:data :session-id] [:data :token]} (set (:sensitive m)))
          "manual-before unions with schema-sourced :token")
      (is (= #{[:data :blob]} (set (:large m)))))))

(deftest manual-register-marks-after-reg-machine-unions
  (testing "register-marks! AFTER reg-machine: the schema marks are NOT
            dropped by the later full-replace register-marks! (the rf2-qpibk0
            leak) — they live in a separate table and union at read time"
    (reg-auth-machine!)
    ;; The harder case the bead names: register-marks! (full-replace), not
    ;; union-marks!, AFTER reg-machine. Previously this clobbered :token.
    (marks/register-marks! :event auth-id {:sensitive [[:data :session-id]]})
    (let [m (marks/marks-for :event auth-id)]
      (is (= #{[:data :session-id] [:data :token]} (set (:sensitive m)))
          "schema-sourced :token survives the later register-marks!")
      (is (= #{[:data :blob]} (set (:large m)))
          "schema-sourced :large slot survives too"))
    ;; And both redact at egress, regardless of order.
    (let [ev  {:operation :rf.machine/transition
               :tags      {:machine-id auth-id
                           :frame      :rf/default
                           :after      {:state :authed
                                        :data  {:retries    1
                                                :token      "secret-jwt"
                                                :session-id "sess-after"
                                                :blob       nil}}}}
          out (marks/project-trace-event ev)]
      (is (= :rf/redacted (get-in out [:tags :after :data :token])))
      (is (= :rf/redacted (get-in out [:tags :after :data :session-id])))
      (is (not (.contains (pr-str out) "secret-jwt")))
      (is (not (.contains (pr-str out) "sess-after"))))))

;; ---- (5) SPAWNED-INSTANCE egress redaction (rf2-fm1cpl) -------------------
;;
;; A spawned actor's `:rf.machine/transition` / `:rf.machine/snapshot-updated`
;; trace carries `:machine-id` = the INSTANCE id (`<type>#<n>` or the explicit
;; `:spawn-id`), NOT the type id. `project-machine-tags` resolves redaction
;; marks via `(marks-for :event <machine-id>)`, so the TYPE's `:data-schema`
;; marks (keyed under the type id at `reg-machine` time) do NOT cover an
;; instance-id trace. rf2-fm1cpl re-runs the schema bridge at SPAWN time keyed
;; under the spawned instance id so a spawned actor's `:sensitive?` `:data`
;; slot redacts in egress exactly like a singleton's.

(def ^:private spawn-type-id :rf.machine-redaction/spawn-worker)

;; runtime-db lookup via the shared machines test-support (rf2-3l8lqe finding #4).
(def ^:private frame-db mtest/runtime-db)

(deftest spawned-instance-gets-schema-marks-keyed-under-instance-id
  (testing "spawning an actor whose TYPE carries a :sensitive? / :large?
            :data-schema registers the SAME schema-derived marks under the
            SPAWNED INSTANCE id (the id its transition trace carries)"
    ;; The spawned worker type carries auth-schema (token :sensitive?, blob
    ;; :large?). A supervisor spawns one instance on entry to :working.
    (rf/reg-machine spawn-type-id
      {:initial     :anon
       :data        {:retries 0 :token nil :blob nil}
       :data-schema auth-schema
       :states      {:anon {} :authed {}}})
    (rf/reg-machine :rf.machine-redaction/sup
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id spawn-type-id}}}})
    (rf/dispatch-sync [:rf.machine-redaction/sup [:start]])
    (let [spawned-id (get-in (frame-db)
                             [:rf.runtime/machines :spawned
                              :rf.machine-redaction/sup [:working]])
          inst-marks (marks/marks-for :event spawned-id)]
      (is (some? spawned-id) "an actor instance was spawned")
      (is (some? inst-marks)
          "a marks entry exists keyed under the SPAWNED INSTANCE id")
      (is (= #{[:data :token]} (set (:sensitive inst-marks)))
          ":sensitive? slot bridged under the instance id, snapshot-rooted")
      (is (= #{[:data :blob]} (set (:large inst-marks)))
          ":large? slot bridged under the instance id"))))

(deftest spawned-instance-data-redacted-in-egress
  (testing "a :rf.machine/transition trace keyed by the SPAWNED INSTANCE id
            redacts the :sensitive? :data slot — the leak rf2-fm1cpl closes"
    (rf/reg-machine spawn-type-id
      {:initial     :anon
       :data        {:retries 0 :token nil :blob nil}
       :data-schema auth-schema
       :states      {:anon {} :authed {}}})
    (rf/reg-machine :rf.machine-redaction/sup
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id spawn-type-id}}}})
    (rf/dispatch-sync [:rf.machine-redaction/sup [:start]])
    (let [spawned-id (get-in (frame-db)
                             [:rf.runtime/machines :spawned
                              :rf.machine-redaction/sup [:working]])
          ;; Synthesise the instance's transition trace exactly as the
          ;; handler emits it: :machine-id = the instance id, :data carrying
          ;; a live secret token + large blob.
          ev   {:operation :rf.machine/transition
                :tags      {:machine-id spawned-id
                            :frame      :rf/default
                            :after      {:state :authed
                                         :data  {:retries 1
                                                 :token   "secret-jwt-spawned"
                                                 :blob    "huge-spawned"}}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:after :data :token]))
          "spawned instance's :sensitive? :data slot redacts at egress")
      (is (contains? (get-in tags [:after :data :blob]) :rf.size/large-elided)
          "spawned instance's :large? :data slot elides at egress")
      (is (= 1 (get-in tags [:after :data :retries]))
          "plain sibling rides verbatim")
      (is (not (.contains (pr-str out) "secret-jwt-spawned"))
          "no raw spawned-instance secret leaked into the projected trace"))))

(deftest inline-definition-spawn-also-bridges-schema-marks
  (testing "an inline-:definition spawn (no registered type) also bridges its
            :data-schema marks under the instance id"
    ;; The supported inline-:definition spawn form is the fx-emitted
    ;; `[:rf.machine/spawn {:spawn-id <id> :definition <spec>}]` from an
    ;; :entry action (mirrors machine_schema_test). The instance id is the
    ;; explicit :spawn-id; the resolved :definition carries the :data-schema.
    (let [inst-id    :rf.machine-redaction/inline-instance
          child-spec {:initial     :anon
                      :data        {:retries 0 :token nil :blob nil}
                      :data-schema auth-schema
                      :states      {:anon {} :authed {}}}]
      (rf/reg-machine :rf.machine-redaction/sup-inline
        {:initial :starting
         :states  {:starting {:on {:go :spawning}}
                   :spawning {:entry (fn [_]
                                       {:fx [[:rf.machine/spawn
                                              {:spawn-id   inst-id
                                               :definition child-spec}]]})}}})
      (rf/dispatch-sync [:rf.machine-redaction/sup-inline [:noop]])
      (rf/dispatch-sync [:rf.machine-redaction/sup-inline [:go]])
      (let [inst-marks (marks/marks-for :event inst-id)]
        (is (some? (get-in (frame-db) [:rf.runtime/machines :snapshots inst-id]))
            "an inline-definition actor was spawned")
        (is (= #{[:data :token]} (set (:sensitive inst-marks)))
            "inline-definition :data-schema :sensitive? slot bridged under the instance id")
        (is (= #{[:data :blob]} (set (:large inst-marks)))
            "inline-definition :data-schema :large? slot bridged under the instance id")))))
