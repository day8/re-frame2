(ns re-frame.freehand.roster-cljs-test
  "The fixture roster, projected — rf2-drpa3.182.7 acceptances 1 and 2.

  The roster's claim is that a Freehand law has ONE identity across every
  projection that mentions it, and that a failure NAMES that identity. This
  file proves the halves of that which are pure data and therefore run
  unchanged on both hosts: the spine is enrolled, every record is
  well-formed against a closed vocabulary, the fixture and its index row
  state the same law, and every defect a malformed record can produce
  carries its `:fh/id`.

  The half that needs a filesystem — that each declared source and proof
  namespace is a file that exists — is `roster-jvm-test`'s. The half that
  needs a browser is the mounted tier's own suites, which the records name.

  ## Why the negative rows are here

  A validator that has only ever run against input it passes has not been
  tested, and a roster gate that could not fail is the most expensive kind
  of green: it makes a reader believe the spine is checked. So every defect
  shape below is DRIVEN — a record is broken one way at a time and the
  defect it produces is asserted, including that it names the right law."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.roster :as roster]))

;; ---------------------------------------------------------------------------
;; Enrolment — the spine is the three vertical fixtures, and it is complete
;; ---------------------------------------------------------------------------

(deftest the-initial-spine-is-enrolled-and-nothing-loaded-empty
  (testing "Per rf2-drpa3.182.7 §INITIAL SPINE: the declared host, the
            serious form and the deferred foreign-handle surrogate are all
            rostered, and each resolved to a real joined record.

            Membership is DISCOVERED — a law is rostered exactly when its
            fixture carries `:fh/record` — so this row states a floor and
            not a total: the control witnesses enrol by editing their own
            fixtures, and a row asserting `(= 3 (count …))` would have made
            each of them edit this file to land. What still has to hold is
            that discovery found something and found THESE: a roster that
            quietly shrank to the records it could resolve would let this
            suite pass over a law it stopped loading, which is the worst
            failure a table-driven gate has."
    (is (seq roster/records) "discovery enrolled something at all")
    (is (= (count roster/records) (count (set roster/ids)))
        "and every enrolled record has a distinct id")
    (doseq [id roster/initial-spine-ids]
      (is (some? (roster/by-id id))
          (str id " resolves to a rostered record")))))

(deftest enrolment-is-a-property-of-the-fixture
  (testing "Per rf2-drpa3.182.7 §INITIAL SPINE, and what makes the roster
            EXTENSIBLE rather than a list: `roster/ids` is derived from the
            fixtures that carry a record, not authored. So a witness enrols
            by writing its own index row and its own `:fh/record`, and the
            five control witnesses can land in parallel without queueing
            behind one line of a shared vector.

            The initial three are a SUBSET of the roster, and stating that
            as `every?` rather than as equality is the whole difference."
    (is (every? (set roster/ids) roster/initial-spine-ids)
        "the initial spine is enrolled")
    (is (= roster/ids (sort roster/ids))
        "and the roster is id-ordered, so its value is stable to read and diff"))

  (testing "NON-VACUITY for discovery itself, which is the row that matters
            most about it. The conformance index carries a hundred laws
            with fixtures; a reader that enrolled every fixture it could
            open would satisfy every membership assertion above and make
            the whole roster meaningless, and it would do it QUIETLY —
            each of those records would simply be reported missing a
            `:fh/record`. So the discriminating half is asserted directly:
            a law whose fixture carries no record is NOT rostered."
    (is (nil? (roster/by-id :FH-CALL-001))
        "FH-CALL-001 is an active law with a fixture and no roster record")
    (is (nil? (roster/by-id :FH-PROPS-001))
        "and so is FH-PROPS-001")
    (is (nil? (roster/by-id :FH-EVENT-005))
        "a retired law, whose row names no fixture at all, is not rostered either")))

(deftest every-rostered-record-carries-its-whole-identity
  (testing "Per rf2-drpa3.182.7 acceptance 1: one record answers every
            question a reader has about a law — what states it, where it is
            addressed, which modes and hosts it binds, which source
            implements it, where it executes, what evidence a run leaves,
            and whether the prose describing it is executable. Asserted
            field by field rather than by a schema call, so a record that
            lost one of them names WHICH."
    (doseq [{id :fh/id law :fh/law index-law :index/law record :fh/record
             :keys [paragraph modes hosts status]}
            roster/records]
      (is (seq law)          (str id " states its law"))
      (is (seq index-law)    (str id " is addressed by an index row"))
      (is (seq paragraph)    (str id " cites a canonical spec paragraph"))
      (is (seq modes)        (str id " binds at least one mode"))
      (is (seq hosts)        (str id " binds at least one host"))
      (is (= :active status) (str id " is an active law"))
      (is (seq (:source record))
          (str id " names the source it is a law about"))
      (is (seq (:evidence record))
          (str id " states what a run leaves behind"))
      (is (contains? roster/prose-statuses (:prose record))
          (str id " declares the status of the prose describing it")))))

(deftest the-roster-is-sound
  (testing "Per rf2-drpa3.182.7 acceptance 1: the whole roster validates —
            no malformed record, no duplicated id, and no record claiming a
            proof tier the index row's host axis rules out. The failure
            message is the defect list itself, so a red row reads as a
            sentence about a law.

            NOT among those, and stated here because a previous version of
            this narration claimed it (merged-PR audit #7098): the fixture's
            `:fh/law` and the index row's are never compared. They say the
            same law at different lengths by convention, so equality would
            be the wrong law — and a gate whose prose claims a check it does
            not perform is worse than no check, because a reader stops
            looking. The relationship the two files DO hold is the id, and
            it is held harder than a defect: a fixture whose `:fh/id`
            disagrees with the row that names its file fails the COMPILE."
    (let [ds (roster/roster-defects roster/records)]
      (is (= [] ds) (str "roster defects:\n" (pr-str ds))))))

;; ---------------------------------------------------------------------------
;; The spine members' own shapes — what each vertical actually claims
;; ---------------------------------------------------------------------------

(deftest each-spine-member-names-the-proofs-its-vertical-lives-on
  (testing "Per rf2-drpa3.182.7 acceptance 2: the three verticals run
            through the SAME roster rather than three pasted arrangements,
            which is only meaningful if each names its proofs as data. Every
            spine member declares at least one, every proof states the law
            it carries, and no two proofs of one law state the SAME law — a
            record that gave two projections one sentence would be
            describing neither."
    (doseq [{:keys [fh/id] :as record} roster/records]
      (let [ps (roster/proofs record)]
        (is (seq ps) (str id " names at least one proof"))
        (doseq [{:keys [tier ns law]} ps]
          (is (symbol? ns) (str id " names its " (name tier) " proof namespace"))
          (is (seq law)    (str id " states what " ns " proves")))
        (is (= (count ps) (count (distinct (map :law ps))))
            (str id " states a DIFFERENT law for each projection"))))))

(deftest a-browser-only-law-declares-no-structural-proof
  (testing "Per rf2-drpa3.182.7 acceptance 1: a record describes what is
            there. FH-CTRL-018 opens 'In a real browser' and FH-BEHAVIOR-005
            is only observable where a connection actually happened, so
            neither has a structural tier — and the honest record is the one
            that omits it. The kit's pure transitions and a behavior's inert
            structural marker are both proven headlessly, but under
            FH-CTRL-012..017 and FH-BEHAVIOR-003: different laws with their
            own ids. A record that named those suites here would claim a
            structural proof they never offer, which is the shape the JVM
            resolution gate caught when this roster first ran."
    (is (empty? (roster/tier (roster/by-id :FH-CTRL-018) :structural)))
    (is (empty? (roster/tier (roster/by-id :FH-BEHAVIOR-005) :structural)))
    (is (seq (roster/tier (roster/by-id :FH-REACT-007) :structural))
        "while the three-plane split IS a headless claim, and says so")))

(deftest one-law-may-carry-more-than-one-mounted-projection
  (testing "Per rf2-drpa3.182.7 §INITIAL SPINE and its terminology note: the
            deferred foreign-handle surrogate models a Promise-returning
            third-party initializer, adds no verb, no runtime, no scheduling
            policy and no contract — so it has no law of its own to address.
            It is the SECOND mounted projection of the total-release law,
            reached by the hardest route. A roster that could hold only one
            projection per tier would have forced it to mint an id, and
            splitting one law across two ids is exactly what this roster
            exists to prevent."
    (let [mounted (roster/tier (roster/by-id :FH-BEHAVIOR-005) :mounted)]
      ;; Both messages NAME the law, and that is not decoration here: a
      ;; deliberate break of this record reported a bare vector inequality
      ;; and left a reader to decode which law it was about, which is
      ;; precisely the failure acceptance 1 forbids. Found by breaking it.
      (is (= 2 (count mounted))
          "FH-BEHAVIOR-005 carries two mounted projections")
      (is (= '[re-frame.freehand.behaviors-dom-cljs-test
               re-frame.freehand.behavior-async-dom-cljs-test]
             (mapv :ns mounted))
          (str "FH-BEHAVIOR-005 — the ordinary synchronous path, then the "
               "same law under a Promise")))))

(deftest a-browser-only-law-does-not-claim-the-jvm
  (testing "Per the index's applicability grammar: the modes and hosts on a
            record are READ from its index row, so the roster cannot widen a
            claim the ledger narrowed. FH-CTRL-018 and FH-BEHAVIOR-005 are
            `interpreted browser` rows — caret behaviour and a real
            teardown are browser facts — while FH-REACT-007 is `common jvm
            browser`, because the three-plane split happens once for both
            emitters."
    (is (= {:modes #{:interpreted} :hosts #{:browser}}
           (select-keys (roster/by-id :FH-CTRL-018) [:modes :hosts])))
    (is (= {:modes #{:interpreted} :hosts #{:browser}}
           (select-keys (roster/by-id :FH-BEHAVIOR-005) [:modes :hosts])))
    (is (= {:modes #{:interpreted :compiled} :hosts #{:jvm :browser}}
           (select-keys (roster/by-id :FH-REACT-007) [:modes :hosts])))))

(deftest the-spine-holds-no-open-boundary
  (testing "`:open` is the record key for a boundary of a law that is NOT
            settled, and the spine currently holds none. FH-REACT-007 held
            the last one — the `:map-props` key-set law, equality or output
            subset, recorded as a question rather than pinned to the
            behaviour that shipped so that settling it would not mean
            fighting the roster. rf2-drpa3.182.3 settled it as key-set
            EQUALITY, enforced at the crossing where the authored plane and
            the adapter's answer both exist, and the answer moved into
            `:prop-names` §`:map-props-adapter` where the rows that run
            live.

            This row is the ratchet, in the direction that matters now: a
            NEW open note is a deliberate act with a bead behind it, and
            reads as a change to this list rather than as prose nobody
            diffs. It says nothing about which answers the settled rows
            reached — those rows assert themselves."
    (is (= [] (filterv #(seq (get-in (roster/by-id %) [:fh/record :open]))
                       roster/initial-spine-ids))
        "no initial-spine member is holding an open question")
    (testing "and whatever a FUTURE note says, its shape holds: keyed by the
              bead that owns the question, stated in words. Vacuous today by
              design — the row above is what keeps it that way — and it is
              the half that survives the list going empty, so a note added
              later cannot be an unattributable shrug."
      (doseq [id   roster/ids
              :let [open (get-in (roster/by-id id) [:fh/record :open])]]
        (is (every? keyword? (keys open))
            (str id " keys each open question by the bead that owns it"))
        (is (every? (every-pred string? seq) (vals open))
            (str id " states each open question in words"))))))

;; ---------------------------------------------------------------------------
;; Applicability parsing — the join's one piece of grammar
;; ---------------------------------------------------------------------------

(deftest applicability-parses-both-axes-and-refuses-a-cell-it-cannot-read
  (testing "The index cell is two axes in one string, and the roster reads
            it rather than restating it. An unreadable cell answers nil so a
            caller reports the cell verbatim — a parser that guessed would
            hand a suite a narrower claim than the ledger made and nothing
            would notice."
    (is (= {:modes #{:interpreted :compiled} :hosts #{:jvm :browser}}
           (roster/parse-applicability "common jvm browser")))
    (is (= {:modes #{:compiled} :hosts #{:browser}}
           (roster/parse-applicability "compiled browser")))
    (is (= {:modes #{:interpreted :compiled} :hosts #{[:host "vega"]}}
           (roster/parse-applicability "common host:vega"))
        "a qualified boundary keeps its name")
    (is (= {:modes #{:interpreted :compiled} :hosts #{:jvm :browser :ssr}}
           (roster/parse-applicability "  common   jvm browser ssr  ")))
    (testing "and the refusals"
      (is (nil? (roster/parse-applicability "jvm browser"))
          "no mode token")
      (is (nil? (roster/parse-applicability "common compiled jvm"))
          "two mode tokens — the axis takes exactly one")
      (is (nil? (roster/parse-applicability "common"))
          "no host token")
      (is (nil? (roster/parse-applicability "common jvm wombat"))
          "a token on neither axis")
      (is (nil? (roster/parse-applicability ""))))))

;; ---------------------------------------------------------------------------
;; FAILURES NAME THE LAW — acceptance 1's load-bearing half
;; ---------------------------------------------------------------------------
;;
;; A roster whose failing assertion reported a line number would not have
;; met acceptance 1. Each row below breaks a sound record ONE way and
;; asserts both that the defect is raised and that it names the law it is
;; about.

(def ^:private sound
  "A minimal record that validates — the control every negative row below
  mutates. Deliberately not one of the real three: a negative suite that
  edited a live record would prove things about that record's incidental
  shape rather than about the vocabulary."
  {:fh/id     "FH-PROBE-001"
   :fh/law    "a probe law"
   :index/law "a probe law"
   :fh/record {:source     '[re-frame.freehand.probe]
               :structural [{:ns 're-frame.freehand.probe-cljs-test :law "headlessly"}]
               ;; `:evidence` is on the CONTROL because it is required, and
               ;; it was not always: while it was optional this record
               ;; omitted it and was asserted valid, so a future member
               ;; could have declined to say what it left behind with the
               ;; whole roster gate green (merged-PR audit #7098). A control
               ;; that is missing a mandatory key is a control that proves
               ;; the key is not mandatory.
               :evidence   {:residue :unasserted}
               :prose      :executable}})

(defn- one-defect
  "The single defect `record` produces, or a marker naming what went wrong
  instead. Answering the whole entry rather than a boolean is what lets a
  row assert the id, the field AND the count in one place."
  [record]
  (let [ds (roster/defects record)]
    (if (= 1 (count ds))
      (first ds)
      {::unexpected (vec ds)})))

(deftest the-control-record-validates
  (testing "NON-VACUITY for every negative row below. If the control were
            already defective, each mutation would 'produce a defect'
            without the mutation having anything to do with it."
    (is (= [] (roster/defects sound)))))

(deftest every-defect-names-the-law-it-is-about
  (testing "Per rf2-drpa3.182.7 acceptance 1: a failing roster row is
            attributable to its law without a lookup. Every defect shape is
            driven, and each is asserted to carry the id — a roster that
            reported `:field :prose` and left a reader to work out WHICH
            law's prose is exactly the failure this acceptance names."
    (doseq [[note broken field]
            [["a key outside the closed record set"
              (assoc-in sound [:fh/record :wombat] 1)                       :fh/record]
             ["a missing required key"
              (update sound :fh/record dissoc :source)                      :fh/record]
             ["a record that declines to say what a run leaves behind"
              (update sound :fh/record dissoc :evidence)                    :fh/record]
             ["a source that is not a vector of namespace symbols"
              (assoc-in sound [:fh/record :source] "re-frame.freehand")     :source]
             ["an empty source vector — a law is about SOMETHING"
              (assoc-in sound [:fh/record :source] [])                      :source]
             ["a prose status outside the closed three"
              (assoc-in sound [:fh/record :prose] :probably)                :prose]
             ["an evidence expectation that is not a map"
              (assoc-in sound [:fh/record :evidence] [:reads])              :evidence]
             ["an empty evidence expectation — say nothing or say something"
              (assoc-in sound [:fh/record :evidence] {})                    :evidence]
             ["an evidence map that does not say what a run leaves behind"
              (assoc-in sound [:fh/record :evidence] {:reads [:dom/value]}) :evidence]
             ["a residue claim outside the closed two"
              (assoc-in sound [:fh/record :evidence]
                        {:reads [:dom/value] :residue :probably-fine})     :evidence]
             ["an :open entry not keyed by an owning bead"
              (assoc-in sound [:fh/record :open] {"someone" "one day"})     :open]
             ["an :open entry with no question stated"
              (assoc-in sound [:fh/record :open] {:rf2-abc ""})             :open]
             ["no proof tier at all — a law that executes nowhere is prose"
              (update sound :fh/record dissoc :structural)                  :fh/record]
             ["a tier given a bare entry rather than a vector of them"
              (assoc-in sound [:fh/record :structural]
                        {:ns 're-frame.freehand.probe-cljs-test :law "x"})  :structural]
             ["a tier declared and left empty"
              (assoc-in sound [:fh/record :structural] [])                  :structural]
             ["a tier naming its namespace as a string"
              (assoc-in sound [:fh/record :structural 0 :ns] "re-frame.x")  :structural]
             ["a tier entry that states no law"
              (update-in sound [:fh/record :structural 0] dissoc :law)      :structural]
             ["a tier entry carrying a key outside #{:ns :law}"
              (assoc-in sound [:fh/record :structural 0 :when] :tuesdays)   :structural]
             ["one tier naming the same namespace twice"
              (update-in sound [:fh/record :structural]
                         #(conj % (first %)))                               :structural]]]
      (let [d (one-defect broken)]
        (is (= "FH-PROBE-001" (:fh/id d)) (str note " — names the law"))
        (is (= field (:field d))          (str note " — names the field"))
        (is (seq (:detail d))             (str note " — says what is wrong"))))))

(deftest a-record-with-no-usable-identity-still-answers-a-defect
  (testing "Total over garbage. A validator that threw on the input it
            exists to reject would report the first defect and hide the
            rest — and the inputs here are exactly the ones a hand-edited
            fixture produces."
    (is (= [{:fh/id nil :field :record :detail "a roster record is a map; got 42"}]
           (roster/defects 42)))
    (is (= [:fh/id] (mapv :field (roster/defects {:fh/law "no id"}))))
    (is (= [:fh/id] (mapv :field (roster/defects {:fh/id "" :fh/law "blank"}))))
    (is (= [:fh/record] (mapv :field (roster/defects {:fh/id "FH-PROBE-002"}))))))

(deftest the-roster-level-defects-name-the-law-too
  (testing "Per rf2-drpa3.182.7 acceptance 1: a duplicated id is a property
            of the ROSTER rather than of one record, and it still names the
            law."
    (is (= [{:fh/id "FH-PROBE-001" :field :fh/id
             :detail "appears 2 times in the roster; an id addresses exactly one law"}]
           (roster/roster-defects [sound sound])))))

(deftest a-record-cannot-claim-a-tier-its-index-row-rules-out
  (testing "The one cross-file law the join can state: a record's proof
            tiers are checked against the host axis of the row that
            addresses it, so a record cannot advertise a browser proof for a
            law the ledger binds to the JVM alone. Before the join the two
            files described the same law with nothing comparing them, and a
            tier claim was a sentence nobody read.

            Deliberately NOT checked: that the fixture's `:fh/law` matches
            its index row word for word. The two state the same law at
            different lengths on purpose — the row is the addressed one-line
            statement, the fixture's is the header over the values below it
            — and every row in the ledger is written that way, so gating
            equality would force a hundred prose rewrites to satisfy a law
            the corpus never held."
    (let [jvm-only (assoc sound :hosts #{:jvm}
                               :fh/record (assoc (:fh/record sound)
                                            :mounted [{:ns 're-frame.freehand.probe-dom-cljs-test
                                                       :law "in a browser"}]))
          ds       (roster/roster-defects [jvm-only])]
      (is (= 1 (count ds)))
      (is (= "FH-PROBE-001" (:fh/id (first ds))) "the tier defect names the law")
      (is (= :mounted (:field (first ds))))
      (is (re-find #"can only run on" (:detail (first ds)))))
    (testing "and the same record on a browser row is sound — non-vacuity
              for the row above"
      (is (= [] (roster/roster-defects
                  [(assoc sound :hosts #{:browser}
                          :fh/record (assoc (:fh/record sound)
                                       :mounted [{:ns 're-frame.freehand.probe-dom-cljs-test
                                                  :law "in a browser"}]))]))))
    (testing "an :ssr tier on a row that names no SSR host"
      (is (= [:ssr] (mapv :field
                          (roster/roster-defects
                            [(assoc sound :hosts #{:jvm :browser}
                                    :fh/record (assoc (:fh/record sound)
                                                 :ssr [{:ns 're-frame.freehand.probe-ssr-jvm-test
                                                        :law "to HTML"}]))])))))
    (testing "and a QUALIFIED host — `host:google-maps`, `host:framer-motion`
              — reaches the browser, because a named third-party door exists
              nowhere else. Without this the ownership-routing witnesses
              could not declare the mounted tier they live on: their index
              rows are exactly the `host:<name>` ones, and a rule reading
              that axis literally would have called every one of their
              mounted proofs a defect."
      (is (= #{:browser} (roster/tier-hosts-of #{[:host "google-maps"]})))
      (is (= #{:jvm :browser} (roster/tier-hosts-of #{:jvm [:host "vega"]})))
      (is (= [] (roster/roster-defects
                  [(assoc sound :hosts #{[:host "google-maps"]}
                          :fh/record (-> (:fh/record sound)
                                         (dissoc :structural)
                                         (assoc :mounted
                                                [{:ns 're-frame.freehand.probe-dom-cljs-test
                                                  :law "against the real Maps door"}])))]))
          "a qualified-host law proves itself mounted")
      (is (= [:ssr] (mapv :field
                          (roster/roster-defects
                            [(assoc sound :hosts #{[:host "google-maps"]}
                                    :fh/record (assoc (:fh/record sound)
                                                 :ssr [{:ns 're-frame.freehand.probe-ssr-jvm-test
                                                        :law "to HTML"}]))])))
          "and reaching the browser is not reaching SSR"))))
