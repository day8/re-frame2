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
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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

(defn- frame-db []
  (rf/app-db-value :rf/default))

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
                             [:rf/runtime :machines :spawned
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
                             [:rf/runtime :machines :spawned
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
        (is (some? (get-in (frame-db) [:rf/runtime :machines :snapshots inst-id]))
            "an inline-definition actor was spawned")
        (is (= #{[:data :token]} (set (:sensitive inst-marks)))
            "inline-definition :data-schema :sensitive? slot bridged under the instance id")
        (is (= #{[:data :blob]} (set (:large inst-marks)))
            "inline-definition :data-schema :large? slot bridged under the instance id")))))
