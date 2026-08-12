(ns re-frame.hicasso.examples.forms.l2-cljs-test
  "L2 — THE FORM'S BODIES, AS SEMANTIC TREES (rf2-hic-051).

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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.forms.db :as db]
            [re-frame.hicasso.examples.forms.events :as events]
            [re-frame.hicasso.examples.forms.subs :as subs]
            [re-frame.hicasso.examples.forms.views :as views]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(def ^:private ticket 7)

(defn- tagged [tree tag] (ht/find tree #(= tag (:tag %))))
(defn- classed [tree class] (ht/find tree #(= class (:class (ht/attrs %)))))

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
  (ht/tree [views/subject-field {:ikey ticket}]
           {:subs {[::subs/subject-shown ticket]    shown
                   [::subs/subject-revision ticket] revision}}))

(defn- hint-tree [editing?]
  (ht/tree [views/subject-hint {:ikey ticket}]
           {:subs {[::subs/editing? ticket] (boolean editing?)}}))

(defn- field-tree
  [{:keys [field label multiline? text problem save]}]
  (ht/tree [views/field-row (cond-> {:field field :label label}
                              multiline? (assoc :multiline? true))]
           {:subs {[::subs/field field]                              text
                   [::subs/shown-problem field]                      problem
                   [:rf/mutation {:instance events/save-instance}]   (or save idle)}}))

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered field's markup
;; ---------------------------------------------------------------------------

(deftest the-subject-field-is-value-revision-and-three-intents
  (let [tree  (subject-tree {:shown "Login page hangs" :revision 2})
        attrs (ht/attrs (tagged tree :input))]
    (is (= "Login page hangs" (:value attrs)))
    (is (= [db/subject-draft ticket ::h/value] (:on-input attrs))
        "typing writes h/reg-state's own setter, keyed by the ticket —
         POSITIONAL, because a marker inside a payload map is substituted
         nowhere and arrives as the keyword itself")
    (is (= [::events/commit-subject ticket] (:on-blur attrs)))
    (is (= {"Enter"  [::events/commit-subject ticket]
            "Escape" [::events/cancel-subject ticket]}
           (:on-key-down attrs))
        "the key MAP, not a callback reading `.key` — which is what keeps
         the composition gate, and why this file finds no function")))

(deftest the-hint-is-absent-until-a-session-is-open-and-is-its-own-boundary
  (is (nil? (classed (hint-tree false) "subject-hint")))
  (is (some? (classed (hint-tree true) "subject-hint")))
  (testing "and the FIELD does not read it — an empty fixture entry here
            would red, because `tree` refuses a read it was not given"
    (is (= "re-frame.hicasso.examples.forms.views/subject-hint"
           (:view-id (ht/find (subject-tree {:shown "s" :revision 0}) :view-id)))
        "the hint is a CALL from inside the field's markup, so its read
         belongs to its own body and not to the field's")))

(deftest the-buffered-field-is-controlled-and-carries-the-reset-trigger
  ;; `ht/controlled?` asks the RUNTIME which component the codec installs,
  ;; so this is the substrate's own answer rather than a re-reading of
  ;; what the author wrote.
  (let [form [:input {:type "text" :value "s"
                      ::h/revision 2
                      :on-input [db/subject-draft ticket ::h/value]}]]
    (is (true? (ht/controlled? form)))
    (is (= 2 (ht/revision form))
        "the trigger is read pre-merge, off the author's own map — it is
         never a DOM attribute and a remainder cannot arm it")))

(deftest the-two-form-fields-carry-no-reset-trigger
  ;; An absence, asserted. The three fields differ in exactly this, and a
  ;; revision that never fires is a prop a reader must reason about for
  ;; nothing — so its absence is part of the recipe rather than an
  ;; oversight.
  (doseq [form [[:input {:type "text" :value "ada"
                         :on-input [::events/edit :assignee ::h/value]}]
                [:textarea {:value "note"
                            :on-input [::events/edit :notes ::h/value]}]]]
    (is (true? (ht/controlled? form)))
    (is (nil? (ht/revision form)))))

;; ---------------------------------------------------------------------------
;; Recipe 2 — the gate, as an absence and a presence
;; ---------------------------------------------------------------------------

(deftest a-field-with-no-shown-problem-renders-no-problem-region
  (let [tree  (field-tree {:field :assignee :label "Assignee" :text "" :problem nil})
        attrs (ht/attrs (tagged tree :input))]
    (is (nil? (classed tree "field-problem"))
        "ABSENT rather than empty — an empty region is still a node, and
         `aria-describedby` pointing at one is a name that says nothing")
    (is (= "false" (:aria-invalid attrs)))
    (is (nil? (:aria-describedby attrs)))
    (is (= [::events/touch {:field :assignee}] (:on-blur attrs))
        "blur is the touch mark, and it names ONE field")))

(deftest a-shown-problem-is-rendered-announced-and-pointed-at
  (let [tree    (field-tree {:field :assignee :label "Assignee" :text ""
                             :problem :problem/assignee-blank})
        input   (ht/attrs (tagged tree :input))
        problem (classed tree "field-problem")]
    (is (= "An assignee is required." (ht/text problem)))
    (is (= :alert (ht/role problem))
        "the message announces itself when it appears")
    (is (= "true" (:aria-invalid input)))
    (is (= (:id (ht/attrs problem)) (:aria-describedby input))
        "and the control points at THAT node — the two ids are written
         from one expression, so they cannot drift apart")))

(deftest the-notes-field-is-a-textarea-and-nothing-else-changes
  (let [tree (field-tree {:field :notes :label "Notes" :multiline? true
                          :text "reproduced" :problem nil})]
    (is (some? (tagged tree :textarea)))
    (is (nil? (tagged tree :input)))
    (is (= [::events/edit :notes ::h/value] (:on-input (ht/attrs (tagged tree :textarea))))
        "one props map serves both spellings, so a change to the contract
         cannot reach one field and miss the other")))

;; ---------------------------------------------------------------------------
;; Recipe 3 — the status the write owns
;; ---------------------------------------------------------------------------

(defn- button-tree [{:keys [can? save]}]
  (ht/tree [views/save-button {}]
           {:subs {[:rf/mutation {:instance events/save-instance}] (or save idle)
                   [::subs/can-submit?]                            (boolean can?)}}))

(deftest an-invalid-form-leaves-the-button-operable-and-says-so
  (let [attrs (ht/attrs (button-tree {:can? false}))]
    (is (false? (:disabled attrs))
        "operable — a disabled submit is out of the tab order and explains
         nothing, and it is also what would make `:attempted?` unreachable
         and the whole submit-attempt gate dead code")
    (is (= "true" (:aria-disabled attrs)))
    (is (= "Save ticket" (ht/text (button-tree {:can? false}))))))

(deftest a-write-in-flight-disables-the-button-from-the-writes-own-status
  (let [attrs (ht/attrs (button-tree {:can? true :save (assoc idle :pending? true)}))]
    (is (true? (:disabled attrs))
        "busy is a projection of the instance. There is no `:saving?` key
         in this application's app-db for a failure branch to forget")
    (is (= "Saving…" (ht/text (button-tree {:can? true :save (assoc idle :pending? true)}))))))

(deftest a-field-in-flight-is-disabled-and-recovers-on-failure
  (is (true? (:disabled (ht/attrs (tagged (field-tree {:field :assignee :label "Assignee"
                                                       :text "ada" :problem nil
                                                       :save (assoc idle :pending? true)})
                                          :input)))))
  (testing "and a settled failure re-enables it — the trap a hand-kept flag
            falls into is exactly this branch"
    (is (false? (:disabled (ht/attrs (tagged (field-tree {:field :assignee :label "Assignee"
                                                          :text "ada" :problem nil
                                                          :save (assoc idle :error? true
                                                                            :settled? true)})
                                             :input)))))))

(deftest the-failure-region-is-present-only-while-the-instance-says-so
  (is (nil? (classed (ht/tree [views/save-failure {}]
                              {:subs {[:rf/mutation {:instance events/save-instance}] idle}})
                     "save-failure"))
      "no write has failed, so the region is not in the tree at all")
  (let [tree (ht/tree [views/save-failure {}]
                      {:subs {[:rf/mutation {:instance events/save-instance}]
                              (assoc idle :error? true :settled? true)}})]
    (is (= :alert (ht/role tree)))
    (is (= "Saving failed. Nothing was lost — try again." (ht/text tree)))))

;; ---------------------------------------------------------------------------
;; The shells — what they read, which is nothing
;; ---------------------------------------------------------------------------

(deftest the-form-shell-reads-nothing-and-so-never-re-renders
  ;; The row the per-keystroke claim rests on. An empty fixture map is a
  ;; LOAD-BEARING empty: `tree` refuses any read no fixture answers, so a
  ;; sub added to this body reds here before it can reach a keystroke's
  ;; path.
  (let [tree (ht/tree [views/details-form {}] {})
        kids (ht/find-all tree :view-id)]
    (is (= [::events/submit] (:on-submit (ht/attrs tree)))
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
  (let [tree (ht/tree [views/screen {:ikey ticket}] {})]
    (is (= ticket (:ikey (ht/attrs (ht/find tree #(= "re-frame.hicasso.examples.forms.views/subject-field"
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
     (button-tree {:can? false})
     (ht/tree [views/save-failure {}]
              {:subs {[:rf/mutation {:instance events/save-instance}]
                      (assoc idle :error? true :settled? true)}})
     (ht/tree [views/details-form {}] {})
     (ht/tree [views/screen {:ikey ticket}] {})]))

(deftest every-handler-site-is-data
  ;; TRAP: the arity-sniffed done-fn, and the `onChange` closure a
  ;; twin-atom stack needs. Spec 004B records a function-valued handler as
  ;; the opaque marker, so one anywhere in the application would show up
  ;; here by name.
  (let [opaque (for [tree (every-tree)
                     node (ht/find-all tree map?)
                     [k v] (ht/attrs node)
                     :when (= {:rf.ui/opaque :fn} v)]
                 [(:tag node) k])]
    (is (= [] (vec opaque))
        "not one function-valued prop in the whole rendering. Give any
         handler a `(h/hfn …)` or a plain `fn` and this row names the tag
         and the prop it appeared on")))

(deftest every-control-has-an-accessible-name
  (doseq [tree (every-tree)]
    (is (= [] (ht/unnamed-controls tree))
        "the a11y projection ranges over what a user can OPERATE — the
         three fields and the button — and each is named by its own
         `<label for=…>` or by its text")))

(h/defview counter-example
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
     :on-input (h/hfn [e] [::events/edit :assignee (.. e -target -value)])}]
   [:button.unnamed {:type "button" :on-click [::events/submit]}]])

(deftest the-two-structural-rows-catch-what-they-claim-to-and-no-more
  (let [tree (ht/tree [counter-example {}] {})]
    (is (= [[:input :on-input]]
           (vec (for [node  (ht/find-all tree map?)
                      [k v] (ht/attrs node)
                      :when (= {:rf.ui/opaque :fn} v)]
                  [(:tag node) k])))
        "the opaque-marker walk finds the hand-rolled callback, and finds
         the ordinary `[::events/submit]` beside it acceptable")
    (is (= [:button] (mapv :tag (ht/unnamed-controls tree)))
        "and the a11y projection reports the unnamed button and NOT the
         labelled field — the direction an over-eager guard gets wrong,
         and the one nothing else here would catch")))

(deftest the-fields-are-named-by-their-labels-and-not-by-a-placeholder
  (let [tree  (subject-tree {:shown "s" :revision 0})
        input (tagged tree :input)]
    (is (= "Subject" (ht/accessible-name tree input))
        "from the `<label for=…>` beside it — a name that survives the
         first keystroke, which a placeholder does not"))
  (let [tree (field-tree {:field :notes :label "Notes" :multiline? true
                          :text "" :problem nil})]
    (is (= "Notes" (ht/accessible-name tree (tagged tree :textarea))))))
