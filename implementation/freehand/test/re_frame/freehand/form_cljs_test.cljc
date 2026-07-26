(ns re-frame.freehand.form-cljs-test
  "FH-CTRL-012 … FH-CTRL-015 — the pure form transitions.

  Every law here is proven by CALLING a function. There is no frame, no
  app-db, no registrar, no host and no fixture that starts a runtime:
  `re-frame.freehand.form` registers nothing and reads nothing, so the
  suite that proves it needs nothing either. That is not a convenience —
  it is the claim. A form module that could only be tested through a
  mounted view would be a form RUNTIME, which DC-03 rules out, and the
  absence of a `use-fixtures` line below is the most direct evidence of
  its absence available.

  ## What each row owns

  | | |
  |---|---|
  | FH-CTRL-012 | purity, and the split between `visited` and `edited` |
  | FH-CTRL-013 | the leafwise seed — the clobber bug, at every shape |
  | FH-CTRL-014 | `reset` and `rebase` are not each other |
  | FH-CTRL-015 | submit: attemptable, revealing, and fenced against a stale reply |

  ## Why the fixtures carry whole values

  Several assertions compare the ENTIRE draft, baseline or slot map rather
  than the one key under test. A key-by-key check is green when a
  transition also moved something nobody looked at, which is precisely
  what a leafwise walk gets wrong: the bug is never \"the leaf I asserted
  on is wrong\", it is \"the leaf beside it went too\"."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.form :as form]))

;; ===========================================================================
;; FH-CTRL-012 — the module is pure, and `visited` is not `edited`
;; ===========================================================================

(def ctrl-012 (conf/fixture :FH-CTRL-012))

(deftest fh-ctrl-012-the-form-module-is-functions-over-a-value
  (testing "Per FH-CTRL-012: `init` opens the draft ON the baseline and
            every other slot empty, and the whole value is ordinary EDN —
            no record, no atom, no identity, nothing that has to be
            constructed by the substrate."
    (let [{:keys [baseline initial]} ctrl-012
          f (form/init baseline)]
      (is (= initial f) "the empty form, slot for slot")
      (is (seq initial) "non-vacuous: the fixture really describes a shape")
      (is (= (:baseline f) (:draft f))
          "the draft opens on the baseline — an unedited leaf IS the baseline")
      (is (= (form/init) (form/init nil))
          "the nullary arity and an explicit nil are one form")
      (is (= {} (:baseline (form/init))) "and an empty form has an empty baseline"))))

(deftest fh-ctrl-012-edit-marks-the-leaf-edited-even-at-an-equal-value
  (testing "Per FH-CTRL-012: `edit` writes the draft leaf and marks it
            EDITED unconditionally. Edited means the user has been typing
            here, not that the value currently differs — a leaf typed and
            corrected back is still theirs, and a late seed must not
            overwrite it."
    (let [{:keys [baseline email-path typed draft-after edited-after baseline-after
                  typed-back edited-after-typeback]} ctrl-012
          f0 (form/init baseline)
          f1 (form/edit f0 email-path typed)]
      (is (= draft-after (:draft f1)) "the whole draft, so a neighbour cannot move unseen")
      (is (= edited-after (:edited f1)) "exactly the leaf the user typed in")
      (is (= baseline-after (:baseline f1))
          "and the BASELINE is untouched — an edit is not an acceptance")
      (is (not= draft-after baseline-after)
          "non-vacuous: the edit really moved the draft off the baseline")

      (testing "typed back to the baseline value, and STILL edited"
        (let [f2 (form/edit f1 email-path typed-back)]
          (is (= (get-in f2 (into [:draft] email-path))
                 (get-in f2 (into [:baseline] email-path)))
              "non-vacuous: the draft really did return to the baseline value")
          (is (= edited-after-typeback (:edited f2))
              "the mark stands — value equality is not the question"))))))

(deftest fh-ctrl-012-visiting-a-leaf-changes-no-value
  (testing "Per FH-CTRL-012: `visit` is the blur. It marks the leaf
            visited, which is what lets that leaf's error appear, and it
            touches no value — so a tab-through protects nothing from a
            seed."
    (let [{:keys [baseline phone-path visited-after-tab]} ctrl-012
          f0 (form/init baseline)
          f1 (form/visit f0 phone-path)]
      (is (= visited-after-tab (:visited f1)))
      (is (= #{} (:edited f1)) "visited and edited are two sets, and only one moved")
      (is (= (:draft f0) (:draft f1)) "the draft is byte-identical")
      (is (= (:baseline f0) (:baseline f1)) "and so is the baseline"))))

(deftest fh-ctrl-012-nothing-in-the-module-registers-anything
  (testing "Per FH-CTRL-012: the module registers no event and no
            subscription, dispatches nothing and installs nothing. The
            roster is checked by SUBSTRING over the public var names, so
            a differently-spelled sibling is caught by the same roster,
            and the roster ITSELF is pinned — a public var added without
            a decision reds this law rather than sliding in."
    (let [{:keys [registration-spellings public-vars]} ctrl-012
          published #?(:clj  (mapv (comp name key) (ns-publics 're-frame.freehand.form))
                       :cljs (vec public-vars))]
      (is (seq published) "non-vacuous: the module publishes vars to examine")
      (is (empty? (filter (fn [n] (some #(str/includes? n %) registration-spellings))
                          published))
          "no published var registers, dispatches or installs")
      (is (seq (filter (fn [n] (some #(str/includes? n %) registration-spellings))
                       (conj (vec published) "reg-form-events!")))
          "and the probe can SEE such a name when one is present")
      #?(:clj
         (is (= public-vars (set published))
             "the published roster is exactly the nine operations, the two
              derivations and the reserved key")))))

;; ===========================================================================
;; FH-CTRL-013 — the leafwise seed
;; ===========================================================================

(def ctrl-013 (conf/fixture :FH-CTRL-013))

(deftest fh-ctrl-013-a-late-load-seeds-leafwise-and-never-clobbers
  (testing "Per FH-CTRL-013: a slow reply arrives while the user is
            typing. Every leaf they edited survives in the draft AND in
            the baseline; every leaf they did not takes the server's
            value; a leaf the payload never mentions is untouched; and a
            leaf reached through a keyed row is addressed by its stable
            domain id."
    (let [{:keys [baseline email-path qty-path typed-email typed-qty payload
                  draft-after baseline-after edited-after visited-after
                  clobbered-draft]} ctrl-013
          f (-> (form/init baseline)
                (form/edit email-path typed-email)
                (form/edit qty-path typed-qty))
          seeded (form/seed f payload)]

      (is (= draft-after (:draft seeded))
          "the whole draft: two edited leaves held, everything else seeded")
      (is (= baseline-after (:baseline seeded))
          "and the whole baseline: an edited leaf's baseline is held too, so the
           user's work is not silently re-based under them")
      (is (= edited-after (:edited seeded)) "a seed marks nothing edited")
      (is (= visited-after (:visited seeded)) "and nothing visited")

      (testing "the CLOBBER control — what the same payload does under the
                whole-map assoc this law exists to replace. Without it,
                every assertion above could be green because the payload
                happened to agree with the draft."
        (is (= clobbered-draft (merge (:draft f) payload))
            "non-vacuous: the fixture's clobber really is what a merge produces")
        (is (not= clobbered-draft draft-after)
            "and the two outcomes genuinely differ")
        (is (not= (get-in clobbered-draft email-path)
                  (get-in draft-after email-path))
            "at the very leaf the user was typing in"))

      (testing "a sub-tree the payload omits is exactly as it was"
        (is (= (get-in baseline [:title]) (get-in (:draft seeded) [:title]))))

      (testing "and a leaf the form has never seen arrives"
        (is (= "from-the-server" (get-in (:draft seeded) [:slug])))
        (is (= "from-the-server" (get-in (:baseline seeded) [:slug])))))))

(deftest fh-ctrl-013-a-seeded-leaf-loses-the-error-its-old-value-carried
  (testing "Per FH-CTRL-013: the value an error was about is gone, so the
            error goes with it — but only where the seed actually landed.
            An EDITED leaf keeps its error, because the seed did not touch
            that value."
    (let [{:keys [baseline email-path typed-email payload errors-before errors-after]}
          ctrl-013
          f (-> (form/init baseline)
                (form/edit email-path typed-email)
                (form/set-errors errors-before))]
      (is (= errors-before (:errors f)) "non-vacuous: both errors really were set")
      (is (= errors-after (:errors (form/seed f payload))))
      (is (not= errors-before errors-after)
          "non-vacuous: the seed really removed one of them"))))

(deftest fh-ctrl-013-a-keyed-row-keeps-its-identity-across-a-re-sort
  (testing "Per FH-CTRL-013: rows are addressed by their STABLE DOMAIN ID.
            The payload returns them in the opposite order with a new row
            inserted ahead of them, and the path still means the same
            row — which an index-keyed path could not."
    (let [{:keys [baseline qty-path typed-qty resorted-payload
                  qty-after-resort other-qty-after-resort]} ctrl-013
          f      (-> (form/init baseline) (form/edit qty-path typed-qty))
          seeded (form/seed f resorted-payload)]
      (is (= qty-after-resort (get-in (:draft seeded) qty-path))
          "the edited row is still the edited row")
      (is (= other-qty-after-resort (get-in (:draft seeded) [:lines "line-9" :qty]))
          "and its neighbour took the server's value")
      (is (= 1 (get-in (:draft seeded) [:lines "line-0" :qty]))
          "the inserted row arrived")
      (is (not= qty-after-resort other-qty-after-resort)
          "non-vacuous: the two rows really do hold different values, so an
           index confusion would have been visible"))))

;; ===========================================================================
;; FH-CTRL-014 — reset and rebase are distinct
;; ===========================================================================

(def ctrl-014 (conf/fixture :FH-CTRL-014))

(defn- drafting
  "The fixture's form with both leaves under edit — the state both
  transitions are asked to resolve."
  [{:keys [baseline amount-path notes-path typed-amount typed-notes]}]
  (-> (form/init baseline)
      (form/edit amount-path typed-amount)
      (form/edit notes-path typed-notes)))

(deftest fh-ctrl-014-a-scoped-reset-discards-one-leaf-and-advances-its-generation
  (testing "Per FH-CTRL-014: reset discards the draft back to the
            baseline and ADVANCES that leaf's reset revision — the one
            signal a caller's same-value rejection can be seen through.
            Scoped to a leaf, it advances that leaf's generation and no
            other, so rejecting one field does not abandon what the user
            is typing in the next."
    (let [{:keys [amount-path notes-path initial-reset-key reasserted-value
                  amount-after-reset amount-reset-key-after notes-after-reset
                  notes-reset-key-after edited-after-reset]} ctrl-014
          f (drafting ctrl-014)]
      (is (= initial-reset-key (form/reset-key f amount-path))
          "every leaf is fenced from its first render")

      (let [before (get-in f (into [:baseline] amount-path))
            r      (form/reset f amount-path)]
        (is (= reasserted-value before)
            "non-vacuous: the caller really is standing by the value it had")
        (is (= before (get-in r (into [:baseline] amount-path)))
            "so nothing derived from the VALUE could observe this decision")
        (is (= amount-after-reset (get-in r (into [:draft] amount-path)))
            "the draft is back on the baseline")
        (is (= amount-reset-key-after (form/reset-key r amount-path))
            "and the generation advanced — the one thing equality cannot be blind to")
        (is (not= initial-reset-key amount-reset-key-after)
            "non-vacuous: the fixture's two generations really differ")

        (testing "and the neighbour is untouched, in value and in generation"
          (is (= notes-after-reset (get-in r (into [:draft] notes-path))))
          (is (= notes-reset-key-after (form/reset-key r notes-path)))
          (is (= edited-after-reset (:edited r))))))))

(deftest fh-ctrl-014-a-whole-form-reset-advances-every-leaf
  (testing "Per FH-CTRL-014: the whole-form arity discards everything and
            advances every leaf mentioned anywhere in the form, so a leaf
            the user typed into that the baseline never had is discarded
            too."
    (let [{:keys [amount-path whole-form-reset-keys draft-after-whole-reset
                  edited-after-whole-reset]} ctrl-014
          r (-> (drafting ctrl-014)
                (form/reset amount-path)          ; :amount is now at 1
                (form/reset))]                    ; …and every leaf advances
      (is (= draft-after-whole-reset (:draft r)))
      (is (= edited-after-whole-reset (:edited r)))
      (is (= #{} (:visited r)))
      (is (= {} (:errors r)))
      (is (= whole-form-reset-keys (:resets r))
          "each leaf's own generation, advanced from wherever it was")
      (is (apply not= (vals whole-form-reset-keys))
          "non-vacuous: the two generations differ, so a shared counter would
           have been visible"))))

(deftest fh-ctrl-014-rebase-moves-the-baseline-under-a-live-draft
  (testing "Per FH-CTRL-014: rebase is a save landing, not a rejection.
            The baseline moves, the draft STANDS — including on the very
            leaf that was rebased — and no generation advances, so a
            control holding a live buffer keeps it."
    (let [{:keys [amount-path rebase-values draft-after-rebase
                  amount-reset-key-after-rebase initial-reset-key
                  reset-discards-draft? rebase-keeps-draft?
                  reset-advances-generation? rebase-advances-generation?]} ctrl-014
          f (drafting ctrl-014)
          r (form/rebase f rebase-values)]
      (is (= draft-after-rebase (:draft r)) "the whole draft stands")
      (is (= "10" (get-in r (into [:baseline] amount-path))) "and the baseline moved")
      (is (= amount-reset-key-after-rebase (form/reset-key r amount-path))
          "no generation advanced")
      (is (= initial-reset-key amount-reset-key-after-rebase)
          "non-vacuous: the fixture says rebase leaves the generation where it was")

      (testing "the CONTRAST, as one table — the two transitions cannot be
                green for the same reason"
        (let [reset-r (form/reset f amount-path)]
          (is (= reset-discards-draft?
                 (= (get-in reset-r (into [:draft] amount-path))
                    (get-in f (into [:baseline] amount-path)))))
          (is (= rebase-keeps-draft?
                 (= (get-in r (into [:draft] amount-path))
                    (get-in f (into [:draft] amount-path)))))
          (is (= reset-advances-generation?
                 (< initial-reset-key (form/reset-key reset-r amount-path))))
          (is (= rebase-advances-generation?
                 (< initial-reset-key (form/reset-key r amount-path))))
          (is (not= reset-discards-draft? rebase-keeps-draft?
                    reset-advances-generation? rebase-advances-generation?)
              "non-vacuous: the four answers are not all the same value"))))))

(deftest fh-ctrl-014-rebase-clears-the-edited-mark-only-where-the-draft-agrees
  (testing "Per FH-CTRL-014: a leaf whose draft now EQUALS the new
            baseline has nothing left to protect, so it stops being
            edited and a later seed may move it again. A leaf that still
            differs keeps its mark."
    (let [{:keys [amount-path agreeing-rebase-values edited-after-agreeing-rebase]}
          ctrl-014
          f (drafting ctrl-014)
          r (form/rebase f agreeing-rebase-values)]
      (is (= edited-after-agreeing-rebase (:edited r))
          "the agreeing leaf is released; the differing one is not")
      (is (contains? (:edited f) amount-path)
          "non-vacuous: it really was edited before the rebase")

      (testing "and being released means a later seed reaches it"
        (let [seeded (form/seed r {:amount "from-server"})]
          (is (= "from-server" (get-in seeded (into [:draft] amount-path)))))))))

;; ===========================================================================
;; FH-CTRL-015 — submit, reveal, and the staleness fence
;; ===========================================================================

(def ctrl-015 (conf/fixture :FH-CTRL-015))

(deftest fh-ctrl-015-an-invalid-submit-is-attemptable-and-reveals
  (testing "Per FH-CTRL-015: attempting marks the form attempted, which
            reveals every error at once — including on a leaf the user
            never visited, which is exactly what a submit is for — and
            attempting AGAIN after a failure is accepted rather than
            refused."
    (let [{:keys [baseline title-path errors show-error-before?
                  attempted-after-invalid? attempts-after-invalid pending-after-invalid
                  show-error-after-invalid? attempts-after-second-invalid
                  pending-after-second-invalid]} ctrl-015
          f (form/set-errors (form/init baseline) errors)]
      (is (= show-error-before? (:show-error? (form/field f title-path)))
          "before any attempt the error exists and is not revealed")
      (is (some? (:error (form/field f title-path)))
          "non-vacuous: there really IS an error to reveal")

      (let [a1 (form/attempt-submit f)]
        (is (= attempted-after-invalid? (get-in a1 [:submit :attempted?])))
        (is (= attempts-after-invalid (get-in a1 [:submit :attempts])))
        (is (= pending-after-invalid (get-in a1 [:submit :pending]))
            "an invalid submit opens no save")
        (is (= show-error-after-invalid? (:show-error? (form/field a1 title-path)))
            "and the error is revealed on a leaf nobody visited")
        (is (not= show-error-before? show-error-after-invalid?)
            "non-vacuous: the attempt actually changed the reveal")

        (testing "a second attempt is an ordinary thing a user does"
          (let [a2 (form/attempt-submit a1)]
            (is (= attempts-after-second-invalid (get-in a2 [:submit :attempts])))
            (is (= pending-after-second-invalid (get-in a2 [:submit :pending])))))))))

(deftest fh-ctrl-015-a-stale-reply-is-inert
  (testing "Per FH-CTRL-015: a valid submit stamps the pending save with
            its attempt number, and that stamp is the staleness fence. A
            reply carrying a superseded number is not the reply this form
            is waiting for."
    (let [{:keys [baseline errors attempts-after-valid pending-after-valid
                  stale-token attempts-after-fourth pending-after-fourth
                  stale-reply-current? fresh-reply-current?]} ctrl-015
          valid (-> (form/init baseline)
                    (form/set-errors errors)
                    (form/attempt-submit)
                    (form/attempt-submit)
                    (form/set-errors {})
                    (form/attempt-submit))]
      (is (= attempts-after-valid (get-in valid [:submit :attempts])))
      (is (= pending-after-valid (get-in valid [:submit :pending]))
          "the pending token IS the attempt number, so it is monotone")

      (let [again (form/attempt-submit valid)]
        (is (= attempts-after-fourth (get-in again [:submit :attempts])))
        (is (= pending-after-fourth (get-in again [:submit :pending])))
        (is (= stale-reply-current?
               (= stale-token (get-in again [:submit :pending])))
            "the first reply's token is no longer the pending one")
        (is (= fresh-reply-current?
               (= pending-after-fourth (get-in again [:submit :pending])))
            "and the current one is")
        (is (not= stale-token pending-after-fourth)
            "non-vacuous: the two tokens really differ")))))

(deftest fh-ctrl-015-every-shape-a-reply-takes-settles-the-pending-submit
  (testing "Per FH-CTRL-015: a reply lands as a rebase (the domain
            accepted it), as errors (the domain refused it), or as a
            reset (the work was abandoned). Each settles the pending
            submit exactly once, so a form cannot be left saying it is
            saving after the save is over."
    (let [{:keys [baseline saved-values server-errors show-server-error? title-path
                  pending-after-rebase pending-after-set-errors pending-after-reset]}
          ctrl-015
          submitted (form/attempt-submit (form/init baseline))]
      (is (some? (get-in submitted [:submit :pending]))
          "non-vacuous: a save really was pending")

      (is (= pending-after-rebase (get-in (form/rebase submitted saved-values)
                                          [:submit :pending])))
      (is (= pending-after-set-errors (get-in (form/set-errors submitted server-errors)
                                              [:submit :pending])))
      (is (= pending-after-reset (get-in (form/reset submitted) [:submit :pending])))

      (testing "and a server error that arrives after a submit is REVEALED,
                because the form is still attempted"
        (let [rejected (form/set-errors submitted server-errors)]
          (is (= show-server-error? (:show-error? (form/field rejected title-path))))
          (is (true? (get-in rejected [:submit :attempted?]))
              "non-vacuous: the attempt mark is what does the revealing"))))))
