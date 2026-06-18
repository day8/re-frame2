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

   3. **UNION with author marks on the machine's reg meta (Mike ruling #3).** A
      machine with BOTH a `:data-schema` and author marks on its `:event`
      registration meta (rf2-ehexnw — derived from the registrar, not a deleted
      `register-marks!`) gets the UNION of both — neither clobbers the other
      (Spec 015 §union-by-source).

   4. **Precision.** A machine with no `:data-schema` (or a schema with no
      marked slot) registers no schema marks; a non-sensitive sibling slot
      rides egress verbatim."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves;
            ;; aliased so the tests can carry author marks onto a machine's
            ;; `:event` reg meta via `reg-machine*`'s opts arity (rf2-ehexnw).
            [re-frame.machines :as machines]
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
  "Register a machine carrying `auth-schema` as its `:data-schema`.

  The 3-arity `opts` map (rf2-ehexnw) carries AUTHOR marks (`:sensitive` /
  `:large`) onto the machine's `:event` registration meta — the way a manual
  machine mark is now expressed (author marks are DERIVED from the registrar
  meta at `marks-for` read time, not stashed via a deleted `register-marks!`).
  `marks-for :event <id>` unions these registrar-derived author marks with the
  `:data-schema`-sourced marks held in the separate schema-marks table."
  ([] (reg-auth-machine! auth-id nil))
  ([machine-id] (reg-auth-machine! machine-id nil))
  ([machine-id opts]
   (machines/reg-machine* machine-id
     {:initial     :anon
      :data        {:retries 0 :token nil :blob nil}
      :data-schema auth-schema
      :states      {:anon  {:on {:login :authed}}
                    :authed {}}}
     opts)))

(defn- machine-transition-event*
  "Build a `:rf.machine/transition` trace event whose `:before` / `:after`
  snapshots carry a populated `:data` (token + blob + a plain sibling), with
  the addressed id keyed under `id-key`.

  `project-machine-tags` resolves marks via `(or (:actor-id tags)
  (:machine-id tags))` (marks.cljc:1341) — every LIVE-runtime snapshot row
  addresses the instance under `:actor-id` (transition.cljc:348/1681; the
  PREFERRED branch production hits), reserving `:machine-id` for the
  registered TYPE / birth signal (the FALLBACK). Parameterising `id-key`
  lets each redaction row be proven on BOTH branches (rf2-sxmeqs — the
  `:actor-id` branch previously had no synthesized-fixture coverage)."
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

;; ---- (2c) :actor-id PREFERRED-branch coverage (rf2-sxmeqs) ----------------
;;
;; Every fixture above keys the addressed id under :machine-id — the FALLBACK
;; branch of `(or (:actor-id tags) (:machine-id tags))`. But after the
;; :machine-id -> :actor-id rename, every LIVE snapshot/started/guard/action/
;; cascade emit addresses the running instance under :actor-id (the PREFERRED
;; branch production hits — transition.cljc:348/1681). These mirror the
;; redaction rows above on the :actor-id key so the privacy-critical preferred
;; branch is proven to redact identically. The :machine-id fallback cases above
;; are retained (the birth signal / registered-type rows still ride it).

(deftest sensitive-slot-redacted-in-egress-actor-id
  (testing "rf2-sxmeqs — an :actor-id-keyed transition (the PREFERRED lookup
            branch, the one production emits) redacts the :sensitive? slot in
            :before / :after exactly like the :machine-id case"
    (reg-auth-machine!)
    (let [out  (marks/project-trace-event
                 (machine-transition-event* :actor-id auth-id))
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:before :data :token])))
      (is (= :rf/redacted (get-in tags [:after :data :token])))
      (is (= 0 (get-in tags [:before :data :retries])) "plain sibling untouched")
      (is (= 1 (get-in tags [:after :data :retries])))
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw token leaked via the :actor-id branch"))))

(deftest large-slot-marked-in-egress-actor-id
  (testing "rf2-sxmeqs — an :actor-id-keyed transition elides the :large? slot
            to the size marker on the preferred branch"
    (reg-auth-machine!)
    (let [out  (marks/project-trace-event
                 (machine-transition-event* :actor-id auth-id))
          tags (:tags out)]
      (is (contains? (get-in tags [:before :data :blob]) :rf.size/large-elided))
      (is (contains? (get-in tags [:after :data :blob]) :rf.size/large-elided))
      (is (not (.contains (pr-str out) "huge-before"))))))

(deftest snapshot-slot-redacted-in-egress-actor-id
  (testing "rf2-sxmeqs — the :snapshot slot on an :actor-id-keyed
            :rf.machine/snapshot-updated redacts on the preferred branch"
    (reg-auth-machine!)
    (let [ev   {:operation :rf.machine/snapshot-updated
                :tags      {:actor-id auth-id
                            :frame    :rf/default
                            :snapshot {:state :authed
                                       :data  {:retries 2
                                               :token   "secret-jwt-snap"
                                               :blob    nil}}}}
          out  (marks/project-trace-event ev)]
      (is (= :rf/redacted (get-in out [:tags :snapshot :data :token])))
      (is (not (.contains (pr-str out) "secret-jwt-snap"))))))

(deftest started-data-slot-redacted-in-egress-actor-id
  (testing "rf2-sxmeqs — an :actor-id-keyed :rf.machine/started redacts the
            :sensitive? :data slot and elides the :large? slot on the preferred
            branch (NOTE: a real :rf.machine/started BIRTH row keys the TYPE id
            under :machine-id — the fallback case above pins that; this proves
            the lookup itself honours :actor-id when present)"
    (reg-auth-machine!)
    (let [ev   {:operation :rf.machine/started
                :tags      {:actor-id auth-id
                            :frame    :rf/default
                            :state    :anon
                            :data     {:retries 0
                                       :token   "secret-jwt-started"
                                       :blob    "huge-started"}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:data :token])))
      (is (contains? (get-in tags [:data :blob]) :rf.size/large-elided))
      (is (= 0 (get-in tags [:data :retries])) "plain sibling verbatim")
      (is (not (.contains (pr-str out) "secret-jwt-started"))))))

(deftest guard-evaluated-input-data-redacted-in-egress-actor-id
  (testing "rf2-sxmeqs — an :actor-id-keyed :rf.machine/guard-evaluated redacts
            the :input :data sub-slot on the preferred branch"
    (reg-auth-machine!)
    (let [ev   {:operation :rf.machine/guard-evaluated
                :tags      {:actor-id auth-id
                            :frame    :rf/default
                            :guard-id :ready?
                            :state    :anon
                            :outcome  :pass
                            :input    {:data  {:retries 0
                                               :token   "secret-jwt-guard"
                                               :blob    "huge-guard"}
                                       :event [:login]}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:input :data :token])))
      (is (contains? (get-in tags [:input :data :blob]) :rf.size/large-elided))
      (is (= [:login] (get-in tags [:input :event])) ":input :event passes through")
      (is (not (.contains (pr-str out) "secret-jwt-guard"))))))

(deftest action-ran-input-data-redacted-in-egress-actor-id
  (testing "rf2-sxmeqs — an :actor-id-keyed :rf.machine/action-ran redacts the
            :input :data sub-slot on the preferred branch"
    (reg-auth-machine!)
    (let [ev   {:operation :rf.machine/action-ran
                :tags      {:actor-id  auth-id
                            :frame     :rf/default
                            :action-id :tap
                            :phase     :transition
                            :outcome   :ok
                            :input     {:data  {:retries 1
                                                :token   "secret-jwt-action"
                                                :blob    "huge-action"}
                                        :event [:login]}}}
          out  (marks/project-trace-event ev)
          tags (:tags out)]
      (is (= :rf/redacted (get-in tags [:input :data :token])))
      (is (contains? (get-in tags [:input :data :blob]) :rf.size/large-elided))
      (is (not (.contains (pr-str out) "secret-jwt-action"))))))

(deftest transition-cascade-data-deltas-redacted-in-egress-actor-id
  (testing "rf2-sxmeqs — an :actor-id-keyed transition's :cascade redacts each
            step's :data-delta on the preferred branch"
    (reg-auth-machine!)
    (let [ev   {:operation :rf.machine/transition
                :tags      {:actor-id auth-id
                            :frame    :rf/default
                            :before   {:state :anon  :data {:retries 0 :token nil :blob nil}}
                            :after    {:state :authed :data {:retries 1
                                                             :token "secret-jwt-after"
                                                             :blob "huge-after"}}
                            :microsteps 0
                            :cascade  [{:kind   :action
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
      (is (= 1 (get-in step0 [:data-delta :retries])) "plain :data-delta key verbatim")
      (is (= {} (get-in cascade [1 :data-delta])) "empty :data-delta unchanged")
      (is (= :rf/redacted (get-in out [:tags :after :data :token])))
      (is (not (.contains (pr-str out) "secret-jwt-delta")))
      (is (not (.contains (pr-str out) "secret-jwt-after"))))))

(deftest actor-id-takes-precedence-over-machine-id-in-snapshot-egress
  (testing "rf2-sxmeqs — when a transition carries BOTH ids the lookup PREFERS
            :actor-id: :actor-id on the marked (auth) machine redacts even
            though :machine-id points at an UNMARKED machine (proves precedence,
            not the fallback)"
    (reg-auth-machine!)
    (rf/reg-machine :rf.machine-redaction/unmarked-sibling
      {:initial :idle :data {:n 0} :data-schema [:map [:n :int]] :states {:idle {}}})
    (let [ev   (-> (machine-transition-event* :actor-id auth-id)
                   (assoc-in [:tags :machine-id] :rf.machine-redaction/unmarked-sibling))
          out  (marks/project-trace-event ev)]
      (is (= :rf/redacted (get-in out [:tags :after :data :token]))
          "redacted via the PREFERRED :actor-id (marked), not the :machine-id sibling")
      (is (not (.contains (pr-str out) "secret-jwt"))))))

;; ---- (3) UNION with author marks on the machine's reg meta (Mike ruling #3)

(deftest schema-marks-union-manual-register-marks
  (testing "a machine with BOTH a :data-schema AND author marks on its :event
            reg meta gets the UNION of both mark sets — neither clobbers the
            other"
    ;; Author marks ride the machine's `:event` registration meta (rf2-ehexnw):
    ;; the schema-sourced slots (held in the separate schema-marks table) union
    ;; with the registrar-derived author marks at `marks-for` read time —
    ;; mirroring reg-app-schema + add-marks composition for app-db.
    (reg-auth-machine! auth-id {:sensitive [[:data :session-id]]})
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
  (testing "re-registering the machine with NEW author marks replaces the
            registrar :event slot but does NOT drop the schema-sourced marks
            (they live in the separate schema-marks table) — the schema-vs-
            author union holds across a re-registration (rf2-ehexnw)."
    (reg-auth-machine!)
    ;; Re-register the machine carrying an author mark on its reg meta (e.g.
    ;; another feature contributing a non-schema'd sensitive path). The schema
    ;; bridge re-runs (same schema → same :token), and the registrar-derived
    ;; author mark unions with it at read time.
    (reg-auth-machine! auth-id {:sensitive [[:data :extra]]})
    (let [m (marks/marks-for :event auth-id)]
      (is (= #{[:data :token] [:data :extra]} (set (:sensitive m)))
          "the schema-sourced path survives alongside the author mark"))))

;; ---- (3b) ORDER-INDEPENDENT union via the real reg-machine path (rf2-qpibk0)
;;
;; The bead's original failure: a separate marks write AFTER `reg-machine`
;; REPLACED the `:event` entry and dropped the schema-derived `[:data …]`
;; marks. Post-rf2-ehexnw the author marks are DERIVED from the registrar
;; `:event` meta (carried on the machine's reg opts) while the schema marks
;; live in the separate schema-marks table — `marks-for :event <id>` unions
;; the two at read time, so the union is order-independent regardless of
;; whether the author marks rode the FIRST or a LATER (re-)registration.

(deftest manual-register-marks-before-reg-machine-unions
  (testing "author marks on the machine's reg meta union with the schema marks
            — carried at registration, derived + unioned at read time"
    (reg-auth-machine! auth-id {:sensitive [[:data :session-id]]})
    (let [m (marks/marks-for :event auth-id)]
      (is (= #{[:data :session-id] [:data :token]} (set (:sensitive m)))
          "author marks union with schema-sourced :token")
      (is (= #{[:data :blob]} (set (:large m)))))))

(deftest manual-register-marks-after-reg-machine-unions
  (testing "a LATER re-registration carrying the author marks does NOT drop the
            schema marks (the rf2-qpibk0 leak) — they live in a separate table
            and re-union at read time after the registrar :event slot replace"
    (reg-auth-machine!)
    ;; The harder case the bead names: a LATER registrar :event write (full
    ;; slot replace), carrying the author marks, AFTER reg-machine. Previously
    ;; the separate-write path clobbered :token.
    (reg-auth-machine! auth-id {:sensitive [[:data :session-id]]})
    (let [m (marks/marks-for :event auth-id)]
      (is (= #{[:data :session-id] [:data :token]} (set (:sensitive m)))
          "schema-sourced :token survives the later re-registration")
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
;; trace carries `:actor-id` = the INSTANCE id (`<type>#<n>` or the explicit
;; `:fixed-actor-id`), NOT the type id. `project-machine-tags` resolves redaction
;; marks via `(marks-for :event <actor-id>)`, so the TYPE's `:data-schema`
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
          ;; handler emits it (rf2-ws5thu): :actor-id = the instance id, :data
          ;; carrying a live secret token + large blob.
          ev   {:operation :rf.machine/transition
                :tags      {:actor-id   spawned-id
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
    ;; `[:rf.machine/spawn {:fixed-actor-id <id> :definition <spec>}]` from an
    ;; :entry action (mirrors machine_schema_test). The instance id is the
    ;; explicit :fixed-actor-id; the resolved :definition carries the :data-schema.
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
                                              {:fixed-actor-id inst-id
                                               :definition     child-spec}]]})}}})
      (rf/dispatch-sync [:rf.machine-redaction/sup-inline [:noop]])
      (rf/dispatch-sync [:rf.machine-redaction/sup-inline [:go]])
      (let [inst-marks (marks/marks-for :event inst-id)]
        (is (some? (get-in (frame-db) [:rf.runtime/machines :snapshots inst-id]))
            "an inline-definition actor was spawned")
        (is (= #{[:data :token]} (set (:sensitive inst-marks)))
            "inline-definition :data-schema :sensitive? slot bridged under the instance id")
        (is (= #{[:data :blob]} (set (:large inst-marks)))
            "inline-definition :data-schema :large? slot bridged under the instance id")))))

;; ---- (6) NEGATIVE: a top-level machine :sensitive / :large key is NOT a -----
;;          classification route (EP-0015 issue 12, RULED 2026-06-11 /
;;          rf2-0k5ubx 2026-06-09; rf2-t55hxg.3)
;;
;; The schema-first machine surface STANDS — machine `:data` sensitivity is
;; declared by `:sensitive?` / `:large?` Malli PROPS on the `:data-schema`
;; slots (every positive test above). A considered proposal to add TOP-LEVEL
;; machine `:sensitive` / `:large` keys (the spelling frames DO take) was
;; explicitly REJECTED — `reg-machine` carries no such key. The positive
;; surface is pinned exhaustively above; this is the missing NEGATIVE guard
;; the rf2-edbj53 testing-coverage audit named (rf2-t55hxg.3): a future
;; refactor that silently started honouring a top-level machine `:sensitive`
;; key would otherwise pass unnoticed.
;;
;; ACTUAL ruled behaviour (confirmed against `validate-machine!` +
;; `reg-machine`): the validator walks only the grammar keys (`:states` /
;; `:initial` / `:guards` / `:actions` / `:on` / `:after` / `:always` /
;; `:spawn` / …), so a top-level `:sensitive` / `:large` key is NOT rejected —
;; it is IGNORED. And `reg-event`'s mark-stashing is SKIPPED for a machine
;; registration (`:rf/machine?` meta, rf2-qpibk0), so the top-level key feeds
;; NO marks entry. The disposition is therefore "provably a no-op": a token
;; written into `:data` under such a spec rides RAW at snapshot egress because
;; no schema prop classified it. We pin BOTH halves.

(deftest top-level-machine-sensitive-key-is-not-honoured
  (testing "a TOP-LEVEL :sensitive / :large key on a reg-machine spec is NOT a
            classification route (EP-0015 issue 12 / rf2-0k5ubx): registration
            does not throw, NO marks entry is registered, and a token written
            into :data under such a spec rides RAW at snapshot egress — the
            schema-first surface is the ONLY machine :data route"
    (let [neg-id :rf.machine-redaction/top-level-sensitive
          ;; The spec carries the frame-shaped TOP-LEVEL :sensitive / :large
          ;; keys (the rejected proposal) AND a benign grammar. It must
          ;; register cleanly and the top-level keys must be inert.
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
      ;; (a) Registration ACCEPTS the spec — the top-level key is ignored,
      ;; not rejected (the validator only knows the grammar keys).
      (is (true? registered?)
          "reg-machine does not throw on a top-level :sensitive / :large key
           — the key is ignored, never honoured as a classification route")
      ;; (b) NO marks entry is registered for the top-level key — it fed
      ;; nothing into the marks table (reg-event's mark-stash is skipped
      ;; for machines; the top-level key is not a :data-schema prop).
      (is (nil? (marks/marks-for :event neg-id))
          "the top-level :sensitive / :large key registers NO marks — only
           a :data-schema :sensitive? / :large? prop is a machine :data route")
      ;; (c) Provably a no-op at egress: a snapshot-updated trace carrying a
      ;; live token in :data rides RAW — nothing classified it, so the token
      ;; is NOT redacted. (Contrast `snapshot-slot-redacted-in-egress`, where
      ;; a :data-schema :sensitive? prop DOES redact the same slot.)
      (let [ev   {:operation :rf.machine/snapshot-updated
                  :tags      {:machine-id neg-id
                              :frame      :rf/default
                              :snapshot   {:state :authed
                                           :data  {:token "rides-raw-jwt"
                                                   :blob  "rides-raw-blob"}}}}
            out  (marks/project-trace-event ev)
            tags (:tags out)]
        (is (= "rides-raw-jwt" (get-in tags [:snapshot :data :token]))
            "the token rides RAW — a top-level machine :sensitive key did NOT
             classify it (the schema-first surface is the only route)")
        (is (= "rides-raw-blob" (get-in tags [:snapshot :data :blob]))
            "the blob rides RAW — a top-level machine :large key is inert")))))
