(ns re-frame.freehand.evidence-cljs-test
  "The evidence schema, checked at the door it can only be entered by.

  Every claim here is about a REFUSAL. That is deliberate: a schema whose
  suite only ever builds well-formed values proves that the happy path
  exists, and the whole point of this one is the shapes it will not let
  you write down — a truncated roster reported as total, an `:opaque`
  basis claiming completeness, a loss with no count, a record with no
  version.

  The a11y half of the ruling — a finding is emitted only where it is
  statically PROVABLE, so a dynamic name or role produces none — is
  proved over real declarations in
  `re-frame.freehand.a11y-diagnostics-cljs-test`. What is proved here is
  the schema consequence of that: a static accessibility finding's basis
  can only be `:static-proof`, and the roster's scope is the sites this
  grammar can prove rather than \"accessibility\", so an empty roster is
  never readable as a clean bill of health."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.evidence :as ev]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(v/defview compiled-probe
  "A compiled declaration — analyzed, so its site roster is provable."
  {:compiled true}
  [{:keys [id]}]
  [:span.probe (v/sub [:probe/value id])])

(v/defview interpreted-probe
  "The same shape, interpreted — no analysis step, so nothing to report."
  [{:keys [id]}]
  [:span.probe (str id)])

(def ^:private well-formed
  {:scope :possible-sites :basis :static-proof :complete? true :loss nil})

(def ^:private a-finding
  {:id       :rf.ui.compile/a11y-missing-accessible-name
   :source   {:file "src/app/toolbar.cljc" :line 42}
   :recovery "give the control a visible label, an :aria-label, or an :aria-labelledby"})

(defn- refused
  "The defect data a refused value throws, or nil when it was accepted."
  [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) e (ex-data e))))

(defn- refused-keys
  [f]
  (set (keep :key (:defects (refused f)))))

;; ---------------------------------------------------------------------------
;; 1 — every projection states all four axes
;; ---------------------------------------------------------------------------

(deftest the-ledger-is-exactly-the-four-axes
  (testing "Scope, basis, completeness and loss — the four D012 names, and
            no fifth. Pinned as a set rather than described, so adding an
            axis is a deliberate edit here and not a silent widening of
            what a projection is allowed to leave unsaid."
    (is (= #{:scope :basis :complete? :loss}
           (set (map :key ev/projection-fields))))))

(deftest a-projection-missing-any-axis-is-refused
  (testing "Enumerated from the ledger rather than restated from it: drop
            each axis in turn and the door names exactly that one. A fifth
            axis added to the ledger is therefore covered without anyone
            remembering to cover it."
    (doseq [{:keys [key names]} ev/projection-fields]
      (let [without (dissoc well-formed key)
            data    (refused #(ev/projection without))]
        (is (some? data) (str "a projection with no " names " is refused"))
        (is (= #{key} (set (map :key (:defects data))))
            (str "and the refusal names " key))
        (is (= #{:missing} (set (map :defect (:defects data))))
            (str key " is reported missing, not merely invalid"))))))

(deftest a-nil-loss-is-a-statement-and-an-absent-loss-is-not
  (testing "The one axis whose legal value is nil, which is exactly why it
            has to be PRESENT. `:loss nil` says nothing was dropped; an
            absent `:loss` says nothing at all, and reads as the first."
    (is (= well-formed (ev/projection well-formed)) "a stated nil loss is accepted")
    (is (= #{:loss} (refused-keys #(ev/projection (dissoc well-formed :loss))))
        "an absent loss is refused")))

(deftest every-record-kind-freehand-can-state-today-states-all-four
  (testing "The schema validation over the record kinds that actually
            exist. Each is built by its own constructor and then re-read
            through the door, so a constructor that stopped stating an axis
            would fail here rather than in whatever tool read it next."
    (doseq [[label p] [["a compiled declaration's manifest" (ev/manifest-projection compiled-probe)]
                       ["an interpreted declaration's manifest" (ev/manifest-projection interpreted-probe)]
                       ["a static accessibility roster" (ev/a11y-projection [a-finding])]
                       ["an empty accessibility roster" (ev/a11y-projection [])]]]
      (doseq [{:keys [key]} ev/projection-fields]
        (is (contains? p key) (str label " states " key)))
      (is (= [] (ev/defects p)) (str label " is emittable")))))

;; ---------------------------------------------------------------------------
;; 2 — an incomplete projection says so, rather than reporting a partial set
;;     as total
;; ---------------------------------------------------------------------------

(deftest a-cap-turns-into-an-explicit-loss-and-cannot-not
  (testing "The fixture that deliberately loses information. Five entries
            capped to three: the payload really is three, the completeness
            claim really is false, and the two that were dropped are
            counted in the record rather than left to be inferred from a
            length."
      (let [full   (assoc well-formed :basis :observation :sites [1 2 3 4 5])
            capped (ev/capped full :sites 3)]
        (is (= [1 2 3] (:sites capped)) "the roster was truncated")
        (is (false? (:complete? capped)) "and it no longer claims to be complete")
        (is (= {:reason :cap :dropped 2} (:loss capped))
            "and the exact number dropped is in the record")))
  (testing "And a cap that dropped nothing changes nothing — the helper is
            not a way to mark everything incomplete."
    (let [under (assoc well-formed :basis :observation :sites [1 2])]
      (is (= under (ev/capped under :sites 3))
          "an under-cap roster is unchanged, and still validated"))))

(deftest a-truncated-roster-cannot-be-reported-as-total
  (testing "The failure this whole schema exists to prevent, refused at
            the door: a projection cannot claim completeness while
            reporting loss, whichever order a caller sets the two fields
            in."
    (let [data (refused #(ev/projection (assoc well-formed :loss {:reason :cap :dropped 17})))]
      (is (some? data) "complete-and-lossy is refused")
      (is (= [:incoherent] (map :defect (:defects data)))
          "as an incoherence between two fields, not a bad field"))))

(deftest a-loss-with-no-count-is-refused-and-unknown-is-how-you-say-so
  (testing "\"Unknown must not look like none\", mechanised. A loss without
            a `:dropped` is refused; a loss that cannot be counted says so
            with the explicit marker, and is accepted."
    (is (= #{:loss} (refused-keys #(ev/projection (assoc well-formed
                                                        :complete? false
                                                        :loss {:reason :cap}))))
        "a loss with no count is refused")
    (is (some? (ev/projection (assoc well-formed
                                     :complete? false
                                     :loss {:reason :host-opaque :dropped ev/unknown})))
        "and an uncountable loss is stated rather than omitted")))

(deftest an-interpreted-declaration-reports-a-loss-it-cannot-quantify
  (testing "The record kind where that matters most. An interpreted
            declaration has no analysis step, so the number of sites it
            could reach is not merely uncounted — it is unknowable without
            running it. The projection says `:opaque`, says incomplete, and
            says the loss is unknown; `:loss nil` there would be claiming
            it dropped nothing when it means it never looked."
    (let [p (ev/manifest-projection interpreted-probe)]
      (is (= :opaque (:basis p)))
      (is (false? (:complete? p)))
      (is (= {:reason :no-static-analysis :dropped :unknown} (:loss p)))
      (is (nil? (:manifest p)) "and there is no roster behind the claim")))
  (testing "beside the compiled arm, which really is complete and lossless
            — the discrimination that the honest answer above is a
            property of interpretation and not of this function"
    (let [p (ev/manifest-projection compiled-probe)]
      (is (= :static-proof (:basis p)))
      (is (true? (:complete? p)))
      (is (nil? (:loss p)))
      (is (= :re-frame.freehand.evidence-cljs-test/compiled-probe
             (:view-id (:manifest p)))
          "and the roster it is complete about is this declaration's"))))

;; ---------------------------------------------------------------------------
;; The other two shapes that claim more than they know
;; ---------------------------------------------------------------------------

(deftest an-opaque-basis-cannot-claim-completeness
  (testing "A projection that cannot see inside a boundary cannot be
            complete about what is inside it. Naming the absence is the
            supported move; claiming to have surveyed it is not."
    (is (some? (refused #(ev/projection {:scope :private-host-work
                                         :basis :opaque
                                         :complete? true
                                         :loss nil}))))
    (is (some? (ev/projection {:scope :private-host-work
                               :basis :opaque
                               :complete? false
                               :loss nil}))
        "the same projection, honestly incomplete, is fine")))

(deftest an-unenforced-declaration-is-a-claim-not-proof
  (testing "A `:declaration` basis earns completeness only when something
            enforces the declaration. Unenforced, it is an annotation
            wearing proof's clothes — so the door asks what enforces it."
    (is (some? (refused #(ev/projection {:scope :possible-sites
                                         :basis :declaration
                                         :complete? true
                                         :loss nil}))))
    (is (some? (ev/projection {:scope :possible-sites
                               :basis :declaration
                               :complete? true
                               :loss nil
                               :enforced-by :the-analyzer-rejects-an-undeclared-read}))
        "naming the enforcement is what makes the claim proof")
    (is (some? (ev/projection {:scope :possible-sites
                               :basis :declaration
                               :complete? false
                               :loss nil}))
        "and an unenforced declaration is perfectly emittable as incomplete")))

(deftest a-scope-that-says-nothing-is-refused
  (testing "Completeness is always RELATIVE to the scope, so a scope of
            `{}` would make every completeness claim a claim about
            everything."
    (is (= #{:scope} (refused-keys #(ev/projection (assoc well-formed :scope {})))))
    (is (= #{:scope} (refused-keys #(ev/projection (assoc well-formed :scope :everything)))))
    (is (some? (ev/projection (assoc well-formed
                                     :basis :observation
                                     :scope {:committed-generation 812})))
        "a generation is a scope")
    (is (some? (ev/projection (assoc well-formed
                                     :basis :observation
                                     :complete? false
                                     :scope {:corpus :component-gallery :runs 240})))
        "and so is a named corpus with its run count")))

;; ---------------------------------------------------------------------------
;; 5 — the schema is versioned, and the version is asserted
;; ---------------------------------------------------------------------------

(deftest the-schema-is-versioned-and-a-record-carries-the-version
  (testing "One version, on every record, validated FIRST — the same
            posture the structural tree takes with its own version. A
            consumer that recognises the version knows the shape; one that
            does not refuses rather than guessing from the fields present."
    (is (= :re-frame.freehand.evidence/v1 ev/schema))
    (let [r {:schema      ev/schema
             :view-id     :app.todo/todo-row
             :lowering    :compiled
             :occurrence  {:parent nil :key 17}
             :generation  9
             :projections {:reads (ev/manifest-projection compiled-probe)}}]
      (is (= r (ev/record r)) "a versioned record is emittable")
      (is (= #{:schema} (refused-keys #(ev/record (dissoc r :schema))))
          "an unversioned record is refused")
      (is (= #{:schema} (refused-keys #(ev/record (assoc r :schema :some.other/v3))))
          "and so is one carrying a version this schema does not own"))))

;; ---------------------------------------------------------------------------
;; Occurrence-keyed
;; ---------------------------------------------------------------------------

(deftest a-record-is-keyed-by-a-runtime-occurrence-and-a-generation
  (testing "Runtime identity, and both halves of it. An occurrence names
            its parent and its key — either may be nil, because a root has
            no parent and an unkeyed only-child has no key, and those are
            facts a record states rather than omits."
    (let [base {:schema ev/schema :view-id :app/row :lowering :interpreted
                :occurrence {:parent nil :key nil} :generation 0
                :projections {:reads (ev/manifest-projection interpreted-probe)}}]
      (is (= base (ev/record base)) "a root occurrence with no key is legal")
      (is (= #{:occurrence} (refused-keys #(ev/record (assoc base :occurrence {:key 3}))))
          "an occurrence that does not name its parent is refused")
      (is (= #{:occurrence} (refused-keys #(ev/record (assoc base :occurrence [:app/row 3]))))
          "and so is one that is not an occurrence map at all")
      (is (= #{:generation} (refused-keys #(ev/record (dissoc base :generation))))
          "a record with no generation is refused — two records about one occurrence must be orderable")
      (is (= #{:lowering} (refused-keys #(ev/record (assoc base :lowering :transpiled))))
          "and the lowering is one of the two, stated rather than inferred"))))

(deftest a-record-validates-every-projection-it-carries
  (testing "\"Every evidence record carries scope, basis, completeness and
            loss\" is a property of this door, not a convention record
            kinds are asked to follow: a malformed projection is refused
            through the record that holds it, named by its kind."
    (let [bad  (assoc well-formed :loss {:reason :cap :dropped 4})
          data (refused #(ev/record {:schema ev/schema :view-id :app/row :lowering :compiled
                                     :occurrence {:parent nil :key 0} :generation 1
                                     :projections {:event-sites bad}}))]
      (is (some? data) "the record is refused")
      (is (= :event-sites (:projection data)) "and the refusal names which projection")
      (is (= :app/row (:view-id data)) "and which view it was about"))
    (is (= #{:projections}
           (refused-keys #(ev/record {:schema ev/schema :view-id :app/row :lowering :compiled
                                      :occurrence {:parent nil :key 0} :generation 1
                                      :projections {}})))
        "and a record that projects nothing is not evidence")))

;; ---------------------------------------------------------------------------
;; 4 — accessibility findings are provable-only, in schema terms
;; ---------------------------------------------------------------------------

(deftest a-static-accessibility-finding-has-exactly-one-basis
  (testing "The compile-tier roster fires only where the defect is
            provable from the literal AST and goes silent the instant a
            dynamic child, a spread or a runtime-computed aria value
            appears. So there is no heuristic tier to be `:observation`
            about and no annotation to be `:declaration` about."
    (is (= :static-proof ev/a11y-basis))
    (is (= :static-proof (:basis (ev/a11y-projection [a-finding]))))))

(deftest an-empty-accessibility-roster-is-not-a-clean-bill-of-health
  (testing "Which is why the scope names the grammar rather than
            \"accessibility\". The roster is complete for what static proof
            reaches and says nothing whatever about what it does not — and
            a reader can see which of those they are holding, because the
            scope says so."
    (let [p (ev/a11y-projection [])]
      (is (= {:provable-in :re-frame.freehand/v1} (:scope p))
          "the scope is the sites this grammar can prove")
      (is (not= :possible-sites (:scope p))
          "and deliberately not the every-site-in-the-declaration scope, which would over-claim")
      (is (true? (:complete? p)) "complete for that scope")
      (is (= [] (:findings p)) "with nothing found"))))

(deftest a-finding-is-a-stable-id-a-coordinate-and-a-way-out
  (testing "A finding with an id and a coordinate is a search result. The
            third field is the one that makes it a diagnostic, so it is
            required rather than encouraged."
    (is (= a-finding (ev/diagnostic a-finding)))
    (doseq [{:keys [key names]} ev/diagnostic-fields]
      (is (= #{key} (refused-keys #(ev/diagnostic (dissoc a-finding key))))
          (str "a finding with no " names " is refused")))
    (is (= #{:source} (refused-keys #(ev/diagnostic (assoc a-finding :source {:file "x.cljc"}))))
        "a coordinate with no line is not a coordinate")
    (is (= #{:recovery} (refused-keys #(ev/diagnostic (assoc a-finding :recovery "   "))))
        "and a blank recovery is not a recovery"))
  (testing "and the roster refuses them one at a time, so a malformed
            finding cannot ride into a projection behind well-formed ones"
    (is (some? (refused #(ev/a11y-projection [a-finding (dissoc a-finding :recovery)]))))))

;; ---------------------------------------------------------------------------
;; 3 — retention rides the existing axis
;; ---------------------------------------------------------------------------

(deftest retention-names-the-existing-axis-and-adds-none
  (testing "One knob, and it is not Freehand's. The schema names where
            history lives rather than keeping any: a second retention
            mechanism would be a second thing to disable in production, a
            second place a payload could outlive a request, and a second
            answer to \"how far back does this go?\"."
    (is (= :spec-009 (:owner ev/retention)))
    (is (= :rf.trace/events-retained (:knob ev/retention)))
    (is (= [:trace-buffer :events-retained] (:configure-at ev/retention))
        "the configure! path is named, so the claim is checkable rather than asserted")))
