(ns re-frame.hicasso.examples.forms.l2-cljs-test
  "L2 — THE FORM'S BODIES, AS SEMANTIC TREES.

  `re-frame.hicasso.test/tree` runs one hook-free body under injected
  read fixtures and answers the Spec 004B structural tree it returned.
  No React, no element, no hook, no DOM — so a row here proves what a
  body MEANS. The mounted suite is the other half and says so.

  ## Two of the five trap classes are STRUCTURAL, and this is where they die

  **The twin-atom stack** — a draft held in a component beside the model,
  reconciled during render — cannot exist in a body that runs here at
  all. `tree` installs no React dispatcher, so a body holding local state
  would not run; and every one of this application's bodies runs. That is
  a stronger statement than *we did not write a hook*, because it is made
  by the instrument rather than by the author.

  **The arity-sniffed done-fn** dies with [[every-handler-site-is-data]]:
  the whole rendering is walked and no handler site anywhere carries a
  function. A completion protocol smuggled in as a callback would appear
  as `{:rf.ui/opaque :fn}` in one of these trees, and it does not.

  ## Why the fixtures are exhaustive rather than convenient

  `:subs` refuses a read no fixture answers, so each fixture map below is
  its body's read set written out. Adding a read to a view REDS this
  file. That is deliberate: a body's read set is what decides when it
  re-renders, and on a form every one of those decisions is paid per
  keystroke."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.forms.db :as rf.hicasso.examples.forms.db]
            [re-frame.hicasso.examples.forms.events :as rf.hicasso.examples.forms.events]
            [re-frame.hicasso.examples.forms.subs :as rf.hicasso.examples.forms.subs]
            [re-frame.hicasso.examples.forms.views :as rf.hicasso.examples.forms.views]
            [re-frame.hicasso.test :as rf.hicasso.test]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

(def ^:private ticket 7)

(defn- tagged [tree tag] (rf.hicasso.test/find tree #(= tag (:tag %))))
(defn- classed [tree class] (rf.hicasso.test/find tree #(= class (:class (rf.hicasso.test/attrs %)))))

(def ^:private idle
  "A mutation instance nobody has executed — the shape `:rf/mutation`
  projects when no write has ever run."
  {:pending? false :success? false :error? false :settled? false})

(defn- subject-tree
  "The field's ENTIRE read set is these two. The hint is a child boundary
  and reads `::subs/editing?` in its own body, so a session opening or
  closing does not re-render the field — see `views/subject-hint` on why
  that separation is what keeps `::h/revision` load-bearing."
  [{:keys [shown revision]}]
  (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/subject-field {:ikey ticket}]
           {:subs {[::rf.hicasso.examples.forms.subs/subject-shown ticket]    shown
                   [::rf.hicasso.examples.forms.subs/subject-revision ticket] revision}}))

(defn- hint-tree [editing?]
  (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/subject-hint {:ikey ticket}]
           {:subs {[::rf.hicasso.examples.forms.subs/editing? ticket] (boolean editing?)}}))

(defn- field-tree
  [{:keys [field label multiline? text problem save]}]
  (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/field-row (cond-> {:field field :label label}
                              multiline? (assoc :multiline? true))]
           {:subs {[::rf.hicasso.examples.forms.subs/field field]                              text
                   [::rf.hicasso.examples.forms.subs/shown-problem field]                      problem
                   [:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}]   (or save idle)}}))

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered field's markup
;; ---------------------------------------------------------------------------

(deftest the-subject-field-is-value-revision-and-three-intents
  (let [tree  (subject-tree {:shown "Login page hangs" :revision 2})
        attrs (rf.hicasso.test/attrs (tagged tree :input))]
    (is (= "Login page hangs" (:value attrs)))
    (is (= [rf.hicasso.examples.forms.db/subject-draft ticket ::rf.hicasso/value] (:on-input attrs))
        "typing writes h/reg-state's own setter, keyed by the ticket —
         POSITIONAL, because a marker inside a payload map is substituted
         nowhere and arrives as the keyword itself")
    (is (= [::rf.hicasso.examples.forms.events/commit-subject ticket] (:on-blur attrs)))
    (is (= {"Enter"  [::rf.hicasso.examples.forms.events/commit-subject ticket]
            "Escape" [::rf.hicasso.examples.forms.events/cancel-subject ticket]}
           (:on-key-down attrs))
        "the key MAP, not a callback reading `.key` — which is what keeps
         the composition gate, and why this file finds no function")))

(deftest the-hint-is-absent-until-a-session-is-open-and-is-its-own-boundary
  (is (nil? (classed (hint-tree false) "subject-hint")))
  (is (some? (classed (hint-tree true) "subject-hint")))
  (testing "and the FIELD does not read it — an empty fixture entry here
            would red, because `tree` refuses a read it was not given"
    (is (= "re-frame.hicasso.examples.forms.views/subject-hint"
           (:view-id (rf.hicasso.test/find (subject-tree {:shown "s" :revision 0}) :view-id)))
        "the hint is a CALL from inside the field's markup, so its read
         belongs to its own body and not to the field's")))

(deftest the-buffered-field-is-controlled-and-carries-the-reset-trigger
  ;; `ht/controlled?` asks the RUNTIME which component the codec installs,
  ;; so this is the substrate's own answer rather than a re-reading of
  ;; what the author wrote.
  (let [form [:input {:type "text" :value "s"
                      ::rf.hicasso/revision 2
                      :on-input [rf.hicasso.examples.forms.db/subject-draft ticket ::rf.hicasso/value]}]]
    (is (true? (rf.hicasso.test/controlled? form)))
    (is (= 2 (rf.hicasso.test/revision form))
        "the trigger is read pre-merge, off the author's own map — it is
         never a DOM attribute and a remainder cannot arm it")))

(deftest the-two-form-fields-carry-no-reset-trigger
  ;; An absence, asserted. The three fields differ in exactly this, and a
  ;; revision that never fires is a prop a reader must reason about for
  ;; nothing — so its absence is part of the recipe rather than an
  ;; oversight.
  (doseq [form [[:input {:type "text" :value "ada"
                         :on-input [::rf.hicasso.examples.forms.events/edit :assignee ::rf.hicasso/value]}]
                [:textarea {:value "note"
                            :on-input [::rf.hicasso.examples.forms.events/edit :notes ::rf.hicasso/value]}]]]
    (is (true? (rf.hicasso.test/controlled? form)))
    (is (nil? (rf.hicasso.test/revision form)))))

;; ---------------------------------------------------------------------------
;; Recipe 2 — the gate, as an absence and a presence
;; ---------------------------------------------------------------------------

(deftest a-field-with-no-shown-problem-renders-no-problem-region
  (let [tree  (field-tree {:field :assignee :label "Assignee" :text "" :problem nil})
        attrs (rf.hicasso.test/attrs (tagged tree :input))]
    (is (nil? (classed tree "field-problem"))
        "ABSENT rather than empty — an empty region is still a node, and
         `aria-describedby` pointing at one is a name that says nothing")
    (is (= "false" (:aria-invalid attrs)))
    (is (nil? (:aria-describedby attrs)))
    (is (= [::rf.hicasso.examples.forms.events/touch {:field :assignee}] (:on-blur attrs))
        "blur is the touch mark, and it names ONE field")))

(deftest a-shown-problem-is-rendered-announced-and-pointed-at
  (let [tree    (field-tree {:field :assignee :label "Assignee" :text ""
                             :problem :problem/assignee-blank})
        input   (rf.hicasso.test/attrs (tagged tree :input))
        problem (classed tree "field-problem")]
    (is (= "An assignee is required." (rf.hicasso.test/text problem)))
    (is (= :alert (rf.hicasso.test/role problem))
        "the message announces itself when it appears")
    (is (= "true" (:aria-invalid input)))
    (is (= (:id (rf.hicasso.test/attrs problem)) (:aria-describedby input))
        "and the control points at THAT node — the two ids are written
         from one expression, so they cannot drift apart")))

(deftest the-notes-field-is-a-textarea-and-nothing-else-changes
  (let [tree (field-tree {:field :notes :label "Notes" :multiline? true
                          :text "reproduced" :problem nil})]
    (is (some? (tagged tree :textarea)))
    (is (nil? (tagged tree :input)))
    (is (= [::rf.hicasso.examples.forms.events/edit :notes ::rf.hicasso/value] (:on-input (rf.hicasso.test/attrs (tagged tree :textarea))))
        "one props map serves both spellings, so a change to the contract
         cannot reach one field and miss the other")))

;; ---------------------------------------------------------------------------
;; Recipe 3 — the status the write owns
;; ---------------------------------------------------------------------------

(defn- button-tree
  "The button's ENTIRE read set is the write's instance. There is no
  `::subs/can-submit?` fixture here and that omission is load-bearing:
  `:subs` refuses a read it cannot answer, so the day this button reaches
  for the gate again, this file reds and says so."
  [{:keys [save]}]
  (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/save-button {}]
           {:subs {[:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}] (or save idle)}}))

(deftest an-invalid-form-leaves-the-button-operable-and-claims-nothing
  (let [attrs (rf.hicasso.test/attrs (button-tree {}))]
    (is (false? (:disabled attrs))
        "operable — a disabled submit is out of the tab order and explains
         nothing, and it is also what would make `:attempted?` unreachable
         and the whole submit-attempt gate dead code")
    (is (nil? (:aria-disabled attrs))
        "and it says nothing to assistive technology either. WAI-ARIA
         defines aria-disabled=true as perceivable but disabled — \"not
         editable or otherwise operable\" — the same claim `disabled`
         makes. Pressing this button is the ONLY way the form reveals
         what is wrong, so announcing it inoperable would be telling a
         screen reader user that the one instruction they have does not
         work. This row is the semantic-coherence half; an attribute pin
         alone could not see it")
    (is (= "Save ticket" (rf.hicasso.test/text (button-tree {}))))))

(deftest validity-reaches-the-user-through-the-fields-instead
  (testing "what the button gave up, the field carries — and carries
            better, because it names WHICH value is wrong"
    (let [tree    (field-tree {:field :assignee :label "Assignee"
                               :text "" :problem :problem/assignee-blank})
          input   (rf.hicasso.test/attrs (tagged tree :input))
          problem (classed tree "field-problem")]
      (is (= "true" (:aria-invalid input)))
      (is (= :alert (rf.hicasso.test/role problem))
          "a live region, so a refused submission is heard rather than
           found")
      (is (= (:id (rf.hicasso.test/attrs problem)) (:aria-describedby input))))))

(deftest a-write-in-flight-disables-the-button-from-the-writes-own-status
  (let [attrs (rf.hicasso.test/attrs (button-tree {:save (assoc idle :pending? true)}))]
    (is (true? (:disabled attrs))
        "busy is a projection of the instance. There is no `:saving?` key
         in this application's app-db for a failure branch to forget")
    (is (= "Saving…" (rf.hicasso.test/text (button-tree {:save (assoc idle :pending? true)}))))))

(deftest a-field-in-flight-is-disabled-and-recovers-on-failure
  (is (true? (:disabled (rf.hicasso.test/attrs (tagged (field-tree {:field :assignee :label "Assignee"
                                                       :text "ada" :problem nil
                                                       :save (assoc idle :pending? true)})
                                          :input)))))
  (testing "and a settled failure re-enables it — the trap a hand-kept flag
            falls into is exactly this branch"
    (is (false? (:disabled (rf.hicasso.test/attrs (tagged (field-tree {:field :assignee :label "Assignee"
                                                          :text "ada" :problem nil
                                                          :save (assoc idle :error? true
                                                                            :settled? true)})
                                             :input)))))))

(deftest the-failure-region-is-present-only-while-the-instance-says-so
  (is (nil? (classed (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/save-failure {}]
                              {:subs {[:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}] idle}})
                     "save-failure"))
      "no write has failed, so the region is not in the tree at all")
  (let [tree (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/save-failure {}]
                      {:subs {[:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}]
                              (assoc idle :error? true :settled? true)}})]
    (is (= :alert (rf.hicasso.test/role tree)))
    (is (= "Saving failed. Nothing was lost — try again." (rf.hicasso.test/text tree)))))

;; ---------------------------------------------------------------------------
;; The shells — what they read, which is nothing
;; ---------------------------------------------------------------------------

(deftest the-form-shell-reads-nothing-and-so-never-re-renders
  ;; The row the per-keystroke claim rests on. An empty fixture map is a
  ;; LOAD-BEARING empty: `tree` refuses any read no fixture answers, so a
  ;; sub added to this body reds here before it can reach a keystroke's
  ;; path.
  (let [tree (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/details-form {}] {})
        kids (rf.hicasso.test/find-all tree :view-id)]
    (is (= [::rf.hicasso.examples.forms.events/submit] (:on-submit (rf.hicasso.test/attrs tree)))
        "and submit is a plain intent — `:on-submit` auto-prevents, so
         there is no `.preventDefault` in the application at all")
    (is (= ["re-frame.hicasso.examples.forms.views/field-row"
            "re-frame.hicasso.examples.forms.views/field-row"
            "re-frame.hicasso.examples.forms.views/save-failure"
            "re-frame.hicasso.examples.forms.views/save-button"]
           (mapv :view-id kids))
        "four CALLS — the children's bodies did not run, which is why
         their reads are not on this fixture map")))

(deftest the-screen-reads-nothing-either
  (let [tree (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/screen {:ikey ticket}] {})]
    (is (= ticket (:ikey (rf.hicasso.test/attrs (rf.hicasso.test/find tree #(= "re-frame.hicasso.examples.forms.views/subject-field"
                                                     (:view-id %))))))
        "the screen hands the instance key down and reads nothing itself")))

;; ---------------------------------------------------------------------------
;; The two structural trap witnesses
;; ---------------------------------------------------------------------------

(defn- every-tree
  "Every body in the application, rendered once, as a vector of trees.
  A function rather than a value so each row builds its trees under its
  own fixture reset."
  []
  (vec
    [(subject-tree {:shown "Login page hangs" :revision 1})
     (hint-tree true)
     (field-tree {:field :assignee :label "Assignee" :text ""
                  :problem :problem/assignee-blank})
     (field-tree {:field :notes :label "Notes" :multiline? true
                  :text "note" :problem nil})
     (button-tree {})
     (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/save-failure {}]
              {:subs {[:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}]
                      (assoc idle :error? true :settled? true)}})
     (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/details-form {}] {})
     (rf.hicasso.test/tree [rf.hicasso.examples.forms.views/screen {:ikey ticket}] {})]))

(deftest every-handler-site-is-data
  ;; TRAP: the arity-sniffed done-fn, and the `onChange` closure a
  ;; twin-atom stack needs. Spec 004B records a function-valued handler as
  ;; the opaque marker, so one anywhere in the application would show up
  ;; here by name.
  (let [opaque (for [tree (every-tree)
                     node (rf.hicasso.test/find-all tree map?)
                     [k v] (rf.hicasso.test/attrs node)
                     :when (= {:rf.ui/opaque :fn} v)]
                 [(:tag node) k])]
    (is (= [] (vec opaque))
        "not one function-valued prop in the whole rendering. Give any
         handler a `(h/event …)` or a plain `fn` and this row names the tag
         and the prop it appeared on")))

(deftest every-control-has-an-accessible-name
  (doseq [tree (every-tree)]
    (is (= [] (rf.hicasso.test/unnamed-controls tree))
        "the a11y projection ranges over what a user can OPERATE — the
         three fields and the button — and each is named by its own
         `<label for=…>` or by its text")))

(rf.hicasso/defview counter-example
  "NOT part of the application, and never rendered by it: a body written
  the two ways the recipes say not to write one, so the two rows above
  are shown to BITE rather than merely to be green.

  It carries a hand-rolled callback where an intent vector belongs, and a
  button with no accessible name — beside a field that IS labelled, which
  is the near-miss half. A guard wrong by being too eager would report
  the labelled field too, and nothing else in this file would notice."
  [_]
  [:div.counter-example
   [:label {:for "hand-rolled"} "Hand rolled"]
   [:input#hand-rolled
    {:type     "text"
     :value    "x"
     :on-input (rf.hicasso/event [e] [::rf.hicasso.examples.forms.events/edit :assignee (.. e -target -value)])}]
   [:button.unnamed {:type "button" :on-click [::rf.hicasso.examples.forms.events/submit]}]])

(deftest the-two-structural-rows-catch-what-they-claim-to-and-no-more
  (let [tree (rf.hicasso.test/tree [counter-example {}] {})]
    (is (= [[:input :on-input]]
           (vec (for [node  (rf.hicasso.test/find-all tree map?)
                      [k v] (rf.hicasso.test/attrs node)
                      :when (= {:rf.ui/opaque :fn} v)]
                  [(:tag node) k])))
        "the opaque-marker walk finds the hand-rolled callback, and finds
         the ordinary `[::events/submit]` beside it acceptable")
    (is (= [:button] (mapv :tag (rf.hicasso.test/unnamed-controls tree)))
        "and the a11y projection reports the unnamed button and NOT the
         labelled field — the direction an over-eager guard gets wrong,
         and the one nothing else here would catch")))

(deftest the-fields-are-named-by-their-labels-and-not-by-a-placeholder
  (let [tree  (subject-tree {:shown "s" :revision 0})
        input (tagged tree :input)]
    (is (= "Subject" (rf.hicasso.test/accessible-name tree input))
        "from the `<label for=…>` beside it — a name that survives the
         first keystroke, which a placeholder does not"))
  (let [tree (field-tree {:field :notes :label "Notes" :multiline? true
                          :text "" :problem nil})]
    (is (= "Notes" (rf.hicasso.test/accessible-name tree (tagged tree :textarea))))))
