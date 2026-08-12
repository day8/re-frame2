(ns re-frame.hicasso.examples.ledger.a11y-cljs-test
  "THE LEDGER'S STRUCTURAL ACCESSIBILITY (rf2-hic-047, on rf2-hic-043's
  helpers; specification §7's *Large collections* row, which requires
  *10K-row behavior, focus and accessibility*).

  Virtualization breaks accessibility in one specific way and it is worth
  naming before any row is read: **the document stops being the model**.
  Twenty-six rows exist; ten thousand records do. Every count a screen
  reader announces, every position it reads out, and every relationship
  it resolves has to come from the model, and the only thing carrying the
  model into the accessibility tree is a handful of attribute VALUES the
  author wrote. So the rows below are arranged around values, in
  rf2-hic-043's own terms: does the attribute carry the right value for
  the state the page is in, and is the name RESOLVED rather than merely
  present?

  ## What this file can see, and what it deliberately cannot

  `ht/tree` refuses a `h/defhost` crossing anywhere in the tree — an
  opaque node with a pointer to L3, and correctly so, since running a
  foreign component's hooks is not something a semantic harness can
  honestly do. `views/ledger` is that crossing's call site, so the GRID
  CONTAINER's own `aria-rowcount` cannot be asserted here.

  It is asserted in `ledger.virtualized-dom-cljs-test`, on a real engine,
  where it is a stronger claim anyway: ten thousand as the announced row
  count *beside a document holding twenty-six rows* is a fact about the
  rendered page and not about a hiccup vector. What is left here is the
  ROW, which is where the names, the roles, the positions and the moving
  state all live — and the row is the piece that repeats ten thousand
  times, so it is the piece worth holding to one assertion per rendering."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.examples.ledger.events :as events]
            [re-frame.hicasso.examples.ledger.subs :as subs]
            [re-frame.hicasso.examples.ledger.views :as views]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; The fixtures, generated from the application's OWN record function
;; ---------------------------------------------------------------------------
;;
;; `[::subs/record i]` is answered from `events/record` rather than from a
;; map typed here, so a row asserting the accessible name "Note for Record
;; 4136" is comparing the view's rendering against the model's naming and
;; not against two copies of one literal.

(defn- row-tree
  "The row at model index `i`, in the state the options describe."
  ([i] (row-tree i {}))
  ([i {:keys [note flagged?] :or {note "" flagged? false}}]
   (ht/tree [views/ledger-row {:index i :offset (* i views/row-height)}]
            {:subs {[::subs/record i]   (events/record i)
                    [::subs/note i]     note
                    [::subs/flagged? i] flagged?}})))

(defn- status-tree [window total]
  (ht/tree [views/status-line {}]
           {:subs {[::subs/window] window
                   [::subs/total]  total}}))

(defn- classed [tree class] (ht/find tree #(= class (:class (ht/attrs %)))))
(defn- named [tree node] (ht/accessible-name tree node))

(defn- rewrite
  "`tree` with every node matching `pred` replaced by `(f node)`.

  The sabotage instrument. A structural assertion that a control is named
  is worth nothing unless the same instrument can be shown finding an
  UNNAMED one, and the honest way to produce an unnamed control is to
  take a real rendering and remove the one attribute that names it —
  leaving everything else, including the id, the role and the position,
  exactly as the application wrote them."
  [tree pred f]
  (letfn [(walk [n]
            (cond
              (string? n) n
              (map? n)    (let [n' (if (pred n) (f n) n)]
                            (if-some [cs (:children n')]
                              (assoc n' :children (mapv walk cs))
                              n'))
              :else       n))]
    (walk tree)))

(defn- note-node [tree] (classed tree "ledger-note"))
(defn- flag-node [tree] (classed tree "ledger-flag"))

;; ---------------------------------------------------------------------------
;; Position — the model's, in a document that holds a window of it
;; ---------------------------------------------------------------------------

(deftest a-rows-announced-position-is-its-place-in-the-MODEL
  (testing "row 4,137 of ten thousand, from a row that will be rendered
            somewhere around the fourth of twenty-six mounted siblings"
    (is (= "4137" (:aria-rowindex (ht/attrs (row-tree 4136))))
        "`aria-rowindex` is one-based, and it is the MODEL index plus one.
         A window-relative implementation would write `4` here — a value
         that is correct about the document, wrong about the data, and
         indistinguishable from correct on screen. It is the single
         attribute a virtualized table gets wrong most often, and the
         reason `aria-rowindex` exists at all"))

  (testing "and the first row of the model still says 1, so the rule is a
            rule rather than a constant offset that happens to fit"
    (is (= "1" (:aria-rowindex (ht/attrs (row-tree 0))))))

  (testing "the columns are announced too — three cells, in order"
    (let [cells (ht/find-all (row-tree 4136) #(= :gridcell (ht/role %)))]
      (is (= 3 (count cells)))
      (is (= ["1" "2" "3"] (mapv #(:aria-colindex (ht/attrs %)) cells))))))

(deftest a-row-is-a-row-and-its-cells-are-cells
  (let [tree (row-tree 4136)]
    (is (= :row (ht/role tree))
        "the row's own role, written by hand: the virtualizer contributes
         two `role=\"presentation\"` wrappers between the grid and this
         node and knows nothing about tables, so the grid's semantics are
         the application's to write")
    (is (= :textbox (ht/role (note-node tree)))
        "an `<input type=\"text\">`, resolved through the implicit table
         rather than asserted as a tag")
    (is (= :button (ht/role (flag-node tree))))))

;; ---------------------------------------------------------------------------
;; Names — resolved, and following the record rather than the row
;; ---------------------------------------------------------------------------

(deftest every-control-is-named-by-the-record-it-belongs-to
  (let [tree (row-tree 4136)]
    (is (= "Note for Record 4136" (named tree (note-node tree)))
        "the field's name carries the RECORD's name. In a virtualized list
         that is not a nicety: twenty-six identically-shaped fields are in
         the document at once and the only thing telling a screen-reader
         user which record they are typing into is this string")
    (is (= "Flag Record 4136" (named tree (flag-node tree)))))

  (testing "a different record, a different pair of names — so the names
            are READ from the record and not baked into the row"
    (let [tree (row-tree 12)]
      (is (= "Note for Record 12" (named tree (note-node tree))))
      (is (= "Flag Record 12" (named tree (flag-node tree)))))))

(deftest the-sweep-finds-nothing-in-any-state-a-row-has
  ;; The bead's accessibility acceptance in the form the application can
  ;; be held to as it grows: one assertion per rendering, over every state
  ;; a row has. A fourth control added tomorrow without a name reds this,
  ;; and no row has to be written for it.
  (doseq [[what tree] [["an untouched row" (row-tree 4136)]
                       ["a row being typed into" (row-tree 4136 {:note "chase"})]
                       ["a flagged row" (row-tree 4136 {:flagged? true})]
                       ["a flagged row being typed into"
                        (row-tree 4136 {:note "chase" :flagged? true})]
                       ["the first row" (row-tree 0)]
                       ["the last row" (row-tree 9999)]]]
    (is (= [] (ht/unnamed-controls tree))
        (str what " — every field and button a user can operate must carry
             an accessible name, and this is the whole row asked at once"))))

(deftest the-sweep-would-notice
  ;; THE POSITIVE CONTROL. Without it the row above is green having proved
  ;; only that `unnamed-controls` can return an empty vector, which it
  ;; would do just as readily over a tree it could not read at all.
  (testing "the same rendering with the note field's `aria-label` removed
            — nothing else changes, and the sweep must find exactly one
            offender"
    (let [sabotaged (rewrite (row-tree 4136)
                             #(= "ledger-note" (:class (ht/attrs %)))
                             #(update % :attrs dissoc :aria-label))
          found     (ht/unnamed-controls sabotaged)]
      (is (= 1 (count found)))
      (is (= (events/note-id 4136) (:id (ht/attrs (first found))))
          "and it names the field, by the id the application gave it")))

  (testing "and the button, which is named by an attribute rather than by
            its content — `Flag` is the same word on all ten thousand of
            them, so content naming would be a name that identifies
            nothing"
    (let [sabotaged (rewrite (row-tree 4136)
                             #(= "ledger-flag" (:class (ht/attrs %)))
                             #(update % :attrs dissoc :aria-label))]
      (is (= "Flag" (named sabotaged (flag-node sabotaged)))
          "it falls back to its content, which is why the row above
           asserts the VALUE of the name and not merely that one exists")
      (is (= [] (ht/unnamed-controls sabotaged))
          "so this sabotage does NOT show up in the sweep, and saying so
           is the honest limit of a sweep: it catches an absent name, not
           an unhelpful one"))))

;; ---------------------------------------------------------------------------
;; State that moves, and a name that must not
;; ---------------------------------------------------------------------------

(deftest the-flags-pressed-state-moves-while-its-name-holds-still
  (let [off (row-tree 4136)
        on  (row-tree 4136 {:flagged? true})]
    (testing "the state, both ways"
      (is (= "false" (:aria-pressed (ht/attrs (flag-node off)))))
      (is (= "true" (:aria-pressed (ht/attrs (flag-node on))))))
    (testing "and the NAME, unchanged across the toggle — a control that
              renames itself as its state changes is a control a
              screen-reader user has to re-find, and these two halves are
              what separate a live `aria-pressed` from a label that
              encodes the state in its text"
      (is (= "Flag Record 4136" (named off (flag-node off))))
      (is (= "Flag Record 4136" (named on (flag-node on)))))))

(deftest the-note-field-echoes-the-committed-value
  (is (= "chase" (:value (ht/attrs (note-node (row-tree 4136 {:note "chase"}))))))
  (is (= "" (:value (ht/attrs (note-node (row-tree 4136)))))
      "and an untouched field is EMPTY rather than absent — a `nil` here
       is React's uncontrolled-input warning, not an empty field"))

;; ---------------------------------------------------------------------------
;; The status line — the model's size, announced politely
;; ---------------------------------------------------------------------------

(deftest the-status-line-is-a-status-and-counts-the-model
  (let [tree (status-tree {:from 40 :to 66} 10000)]
    (is (= :status (ht/role tree))
        "a STATUS and not an ALERT: the window moving is not an
         interruption, and the two roles are different promises about how
         urgently a screen reader speaks")
    (is (= "Showing rows 41–67 of 10000" (ht/text tree))
        "one-based, and the denominator is the MODEL's count — the number
         the document cannot supply")
    (is (nil? (ht/accessible-name tree tree))
        "a live region takes no name from its own text: it is announced
         BECAUSE it changed")))
