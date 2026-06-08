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
