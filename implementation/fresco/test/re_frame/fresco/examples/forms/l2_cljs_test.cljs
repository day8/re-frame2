(ns re-frame.fresco.examples.forms.l2-cljs-test
  "L2 — THE FORM'S BODIES, AS SEMANTIC TREES.

  `re-frame.fresco.test/tree` runs one hook-free body under injected
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
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.examples.forms.events :as rf.fresco.examples.forms.events]
            [re-frame.fresco.examples.forms.subs :as rf.fresco.examples.forms.subs]
            [re-frame.fresco.examples.forms.views :as rf.fresco.examples.forms.views]
            [re-frame.fresco.test :as rf.fresco.test]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

(def ^:private ticket 7)

(defn- tagged [tree tag] (rf.fresco.test/find tree #(= tag (:tag %))))
(defn- classed [tree class] (rf.fresco.test/find tree #(= class (:class (rf.fresco.test/attrs %)))))

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
  (rf.fresco.test/tree [rf.fresco.examples.forms.views/subject-field {:ikey ticket}]
           {:subs {[::rf.fresco.examples.forms.subs/subject-shown ticket]    shown
                   [::rf.fresco.examples.forms.subs/subject-revision ticket] revision}}))

(defn- hint-tree [editing?]
  (rf.fresco.test/tree [rf.fresco.examples.forms.views/subject-hint {:ikey ticket}]
           {:subs {[::rf.fresco.examples.forms.subs/editing? ticket] (boolean editing?)}}))

(defn- field-tree
  [{:keys [field label multiline? text problem save]}]
  (rf.fresco.test/tree [rf.fresco.examples.forms.views/field-row (cond-> {:field field :label label}
                              multiline? (assoc :multiline? true))]
           {:subs {[::rf.fresco.examples.forms.subs/field field]                              text
                   [::rf.fresco.examples.forms.subs/shown-problem field]                      problem
                   [:rf/mutation {:instance rf.fresco.examples.forms.events/save-instance}]   (or save idle)}}))

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered field's markup
;; ---------------------------------------------------------------------------

(deftest the-hint-is-absent-until-a-session-is-open-and-is-its-own-boundary
  (is (nil? (classed (hint-tree false) "subject-hint")))
  (is (some? (classed (hint-tree true) "subject-hint")))
  (testing "and the FIELD does not read it — an empty fixture entry here
            would red, because `tree` refuses a read it was not given"
    (is (= "re-frame.fresco.examples.forms.views/subject-hint"
           (:view-id (rf.fresco.test/find (subject-tree {:shown "s" :revision 0}) :view-id)))
        "the hint is a CALL from inside the field's markup, so its read
         belongs to its own body and not to the field's")))

;; ---------------------------------------------------------------------------
;; Recipe 2 — the gate, as an absence
;; ---------------------------------------------------------------------------

(deftest a-field-with-no-shown-problem-renders-no-problem-region
  (let [tree  (field-tree {:field :assignee :label "Assignee" :text "" :problem nil})
        attrs (rf.fresco.test/attrs (tagged tree :input))]
    (is (nil? (classed tree "field-problem"))
        "ABSENT rather than empty — an empty region is still a node, and
         `aria-describedby` pointing at one is a name that says nothing")
    (is (= "false" (:aria-invalid attrs)))
    (is (nil? (:aria-describedby attrs)))
    (is (= [::rf.fresco.examples.forms.events/touch {:field :assignee}] (:on-blur attrs))
        "blur is the touch mark, and it names ONE field")))

;; ---------------------------------------------------------------------------
;; Recipe 3 — the status the write owns
;; ---------------------------------------------------------------------------

(defn- button-tree
  "The button's ENTIRE read set is the write's instance. There is no
  `::subs/can-submit?` fixture here and that omission is load-bearing:
  `:subs` refuses a read it cannot answer, so the day this button reaches
  for the gate, this file reds and says so."
  [{:keys [save]}]
  (rf.fresco.test/tree [rf.fresco.examples.forms.views/save-button {}]
           {:subs {[:rf/mutation {:instance rf.fresco.examples.forms.events/save-instance}] (or save idle)}}))

(deftest a-field-in-flight-is-disabled-and-recovers-on-failure
  (is (true? (:disabled (rf.fresco.test/attrs (tagged (field-tree {:field :assignee :label "Assignee"
                                                       :text "ada" :problem nil
                                                       :save (assoc idle :pending? true)})
                                          :input)))))
  (testing "and a settled failure re-enables it — the trap a hand-kept flag
            falls into is exactly this branch"
    (is (false? (:disabled (rf.fresco.test/attrs (tagged (field-tree {:field :assignee :label "Assignee"
                                                          :text "ada" :problem nil
                                                          :save (assoc idle :error? true
                                                                            :settled? true)})
                                             :input)))))))

(deftest the-failure-region-is-present-only-while-the-instance-says-so
  (is (nil? (classed (rf.fresco.test/tree [rf.fresco.examples.forms.views/save-failure {}]
                              {:subs {[:rf/mutation {:instance rf.fresco.examples.forms.events/save-instance}] idle}})
                     "save-failure"))
      "no write has failed, so the region is not in the tree at all")
  (let [tree (rf.fresco.test/tree [rf.fresco.examples.forms.views/save-failure {}]
                      {:subs {[:rf/mutation {:instance rf.fresco.examples.forms.events/save-instance}]
                              (assoc idle :error? true :settled? true)}})]
    (is (= :alert (rf.fresco.test/role tree)))
    (is (= "Saving failed. Nothing was lost — try again." (rf.fresco.test/text tree)))))

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
     (rf.fresco.test/tree [rf.fresco.examples.forms.views/save-failure {}]
              {:subs {[:rf/mutation {:instance rf.fresco.examples.forms.events/save-instance}]
                      (assoc idle :error? true :settled? true)}})
     (rf.fresco.test/tree [rf.fresco.examples.forms.views/details-form {}] {})
     (rf.fresco.test/tree [rf.fresco.examples.forms.views/screen {:ikey ticket}] {})]))

(deftest every-handler-site-is-data
  ;; TRAP: the arity-sniffed done-fn, and the `onChange` closure a
  ;; twin-atom stack needs. Spec 004B records a function-valued handler as
  ;; the opaque marker, so one anywhere in the application would show up
  ;; here by name.
  (let [opaque (for [tree (every-tree)
                     node (rf.fresco.test/find-all tree map?)
                     [k v] (rf.fresco.test/attrs node)
                     :when (= {:rf.ui/opaque :fn} v)]
                 [(:tag node) k])]
    (is (= [] (vec opaque))
        "not one function-valued prop in the whole rendering. Give any
         handler a `(h/event …)` or a plain `fn` and this row names the tag
         and the prop it appeared on")))

(deftest every-control-has-an-accessible-name
  (doseq [tree (every-tree)]
    (is (= [] (rf.fresco.test/unnamed-controls tree))
        "the a11y projection ranges over what a user can OPERATE — the
         three fields and the button — and each is named by its own
         `<label for=…>` or by its text")))

(rf.fresco/defview counter-example
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
     :on-input (rf.fresco/event [e] [::rf.fresco.examples.forms.events/edit :assignee (.. e -target -value)])}]
   [:button.unnamed {:type "button" :on-click [::rf.fresco.examples.forms.events/submit]}]])

(deftest the-two-structural-rows-catch-what-they-claim-to-and-no-more
  (let [tree (rf.fresco.test/tree [counter-example {}] {})]
    (is (= [[:input :on-input]]
           (vec (for [node  (rf.fresco.test/find-all tree map?)
                      [k v] (rf.fresco.test/attrs node)
                      :when (= {:rf.ui/opaque :fn} v)]
                  [(:tag node) k])))
        "the opaque-marker walk finds the hand-rolled callback, and finds
         the ordinary `[::events/submit]` beside it acceptable")
    (is (= [:button] (mapv :tag (rf.fresco.test/unnamed-controls tree)))
        "and the a11y projection reports the unnamed button and NOT the
         labelled field — the direction an over-eager guard gets wrong,
         and the one nothing else here would catch")))
