(ns re-frame.hicasso.test-kit-a11y-cljs-test
  "THE ACCESSIBILITY TRIO'S OWN WITNESSES — `ht/role`,
  `ht/accessible-name`, `ht/unnamed-controls` (rf2-hic-043).

  An accessibility assertion that passes when the accessibility is
  absent is worth nothing, and the same is true one level down: a helper
  whose witness only ever asserts the good case is a helper nobody has
  measured. So every row here is written to be able to go the other way,
  and the file is arranged around the three shapes of control
  `test-kit-cljs-test` established for the kit's other doors.

  ## 1. Discrimination, on both sides of every conditional arm

  `ht/role`'s table has four tags whose answer is CONDITIONAL — an `<a>`
  is a link only with an `:href`, an `<input>` answers by `:type`, a
  `<select>` is a listbox only when it takes more than one, an `<img>` is
  presentational only when its `:alt` is empty — and each is driven on
  BOTH sides of its condition. A row that drove only the positive side
  would be satisfied by `(constantly :link)`.

  ## 2. Precedence is asserted where the sources DISAGREE

  `ht/accessible-name` is an ordered fallback of five steps, and an
  ordered fallback is exactly the shape whose test can be green while the
  order is wrong: markup with one name source present passes under any
  ordering that reaches it at all. So each precedence row supplies TWO
  sources carrying DIFFERENT strings and asserts which one won. Removing
  a step then changes an answer rather than merely removing one.

  ## 3. The legal twin, and the other direction

  Every sabotage row has a sibling asserting that the *legal* variant it
  was derived from still passes — a button named by `aria-label` alone,
  an input named by a wrapping `<label>` alone, a nameless element that
  is not interactive. A guard wrong by being too eager reports defects
  that are not there, and nothing but a legal twin catches it.

  ## The lane

  Nothing here mounts and nothing here needs a document: a role and a
  name are facts about markup, so the node lane is where they are
  decided. Whether a browser AGREES is
  `re-frame.hicasso.examples.slice.a11y-focus-dom-cljs-test`'s question,
  and it is a different one."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; The two helpers this file writes
;; ---------------------------------------------------------------------------

(defn- markup
  "The semantic tree of a literal piece of hiccup — no reads, no props,
  no view. The trio's whole domain is a tree, and this is the shortest
  honest way to get one."
  [form]
  (ht/tree [(fn [_] form) {}]))

(defn- outcome
  "`{:returned v}` or `{:refused <ex-data>}` — the same discriminator the
  kit's other witnesses use, and for the same reason: a bare `thrown?`
  is green for a throw from any layer carrying any id."
  [thunk]
  (try {:returned (thunk)}
       (catch :default e {:refused (ex-data e)})))

(defn- refusal
  [outcome']
  (some-> (:refused outcome') (select-keys [:rf.error/id :where :recovery])))

(h/defview a-child
  "A minted boundary, so the boundary row below records a CALL rather
  than the refusal a plain function in head position earns."
  [_]
  [:p "child"])

(defn- tagged [tree tag] (ht/find tree #(= tag (:tag %))))

(defn- classed [tree class] (ht/find tree #(= class (:class (ht/attrs %)))))

;; ---------------------------------------------------------------------------
;; role — every arm of the table, and both sides of every condition
;; ---------------------------------------------------------------------------

(deftest an-explicit-role-is-read-as-a-keyword-from-either-spelling
  (let [t (markup [:div
                   [:p.s {:role "status"} "saved"]
                   [:p.k {:role :alert} "no"]])]
    (is (= :status (ht/role (classed t "s"))) "the string spelling")
    (is (= :alert (ht/role (classed t "k"))) "and the keyword spelling")))

(deftest an-explicit-role-is-a-token-list-and-the-first-token-wins
  (testing "a fallback chain is read the way a user agent reads it — the
            first token, not the last and not the whole string"
    (is (= :doc-subtitle
           (ht/role (tagged (markup [:p {:role "doc-subtitle heading"}]) :p))))
    (is (= :heading
           (ht/role (tagged (markup [:p {:role "heading doc-subtitle"}]) :p)))
        "and the SAME two tokens the other way round answer the other
         one, so this is about order rather than about which of the two
         names the reader happens to know")))

(deftest an-explicit-role-beats-the-tag
  (let [t (markup [:div [:button {:role "link"} "go"] [:button "press"]])]
    (is (= :link (ht/role (ht/find t #(= "link" (:role (ht/attrs %))))))
        "the author's answer")
    (is (= :button (ht/role (ht/find t #(and (= :button (:tag %))
                                             (nil? (:role (ht/attrs %)))))))
        "and the tag's, unaltered beside it")))

(deftest an-anchor-is-a-link-only-when-it-has-somewhere-to-go
  (let [t (markup [:div
                   [:a.linked {:href "/x"} "x"]
                   [:a.bare "y"]])]
    (is (= :link (ht/role (classed t "linked"))))
    (is (nil? (ht/role (classed t "bare")))
        "an `<a>` with no href is not a link and never was — this is the
         false positive the clj-kondo export was reopened to remove
         (rf2-hic-022, audit #7791), and the kit must not re-introduce it")))

(deftest an-input-answers-by-type-including-the-types-that-have-no-role
  (let [t (markup [:form
                   [:input.a {:type "text"}]
                   [:input.b]
                   [:input.c {:type "search"}]
                   [:input.d {:type "checkbox"}]
                   [:input.e {:type "radio"}]
                   [:input.f {:type "submit"}]
                   [:input.g {:type "range"}]
                   [:input.h {:type "number"}]
                   [:input.i {:type "password"}]
                   [:input.j {:type "hidden"}]
                   [:input.k {:type :email}]])]
    (is (= :textbox (ht/role (classed t "a"))))
    (is (= :textbox (ht/role (classed t "b")))
        "no :type at all is `text`, which is HTML's own default and not a
         miss")
    (is (= :searchbox (ht/role (classed t "c"))))
    (is (= :checkbox (ht/role (classed t "d"))))
    (is (= :radio (ht/role (classed t "e"))))
    (is (= :button (ht/role (classed t "f"))))
    (is (= :slider (ht/role (classed t "g"))))
    (is (= :spinbutton (ht/role (classed t "h"))))
    (is (= :textbox (ht/role (classed t "k"))) "a keyword :type reads the same")
    (testing "and the two types ARIA gives no role — the other side of the
              same table, without which the table could answer :textbox
              for everything it did not recognise"
      (is (nil? (ht/role (classed t "i"))))
      (is (nil? (ht/role (classed t "j")))))))

(deftest a-select-is-a-combobox-until-it-takes-more-than-one
  (let [t (markup [:div
                   [:select.one]
                   [:select.many {:multiple true}]
                   [:select.tall {:size 4}]
                   [:select.short {:size 1}]])]
    (is (= :combobox (ht/role (classed t "one"))))
    (is (= :listbox (ht/role (classed t "many"))))
    (is (= :listbox (ht/role (classed t "tall"))))
    (is (= :combobox (ht/role (classed t "short")))
        "`:size 1` is one visible row and still a combobox — the boundary
         of the condition rather than a value far from it")))

(deftest an-image-with-an-empty-alt-is-presentational
  (let [t (markup [:div
                   [:img.described {:src "a.png" :alt "a chart"}]
                   [:img.decorative {:src "b.png" :alt ""}]
                   [:img.silent {:src "c.png"}]])]
    (is (= :img (ht/role (classed t "described"))))
    (is (= :presentation (ht/role (classed t "decorative")))
        "an empty alt is the author saying `ignore this` and is the ONE
         attribute value that changes an element's role")
    (is (= :img (ht/role (classed t "silent")))
        "a MISSING alt is not an empty one — the image is still an image,
         it is merely unnamed, which is what unnamed-controls is for")))

(deftest the-unconditional-rows-of-the-table
  (let [t (markup [:main
                   [:nav [:ul [:li "a"]]]
                   [:h3 "h"]
                   [:textarea]
                   [:select [:option "o"]]
                   [:progress]
                   [:hr]
                   [:dialog]])]
    (is (= :main (ht/role t)))
    (is (= :navigation (ht/role (tagged t :nav))))
    (is (= :list (ht/role (tagged t :ul))))
    (is (= :listitem (ht/role (tagged t :li))))
    (is (= :heading (ht/role (tagged t :h3))))
    (is (= :textbox (ht/role (tagged t :textarea))))
    (is (= :option (ht/role (tagged t :option))))
    (is (= :progressbar (ht/role (tagged t :progress))))
    (is (= :separator (ht/role (tagged t :hr))))
    (is (= :dialog (ht/role (tagged t :dialog))))))

(deftest the-tags-whose-role-depends-on-context-answer-nothing
  (testing "`<header>`, `<section>`, `<form>` and `<td>` have roles that
            depend on an ancestor, on a heading or on whether the element
            is named. Answering one from the node alone would be a guess,
            and a guess is the failure this trio exists to avoid — so the
            omission is asserted rather than left to be noticed"
    (let [t (markup [:div [:header] [:section] [:form] [:table [:tr [:td]]]])]
      (is (nil? (ht/role (tagged t :header))))
      (is (nil? (ht/role (tagged t :section))))
      (is (nil? (ht/role (tagged t :form))))
      (is (nil? (ht/role (tagged t :td)))))))

(deftest role-is-total-over-the-node-set
  (let [t (markup [:<> [:div] "text"])]
    (is (nil? (ht/role nil)) "nil in, nil out — so it threads through a miss")
    (is (nil? (ht/role t)) "a fragment is not an element")
    (is (nil? (ht/role (tagged t :div))) "and a div has no role of its own"))
  (testing "text content is REFUSED rather than answered nil, because a
            string reaching a projection is a caller reading the wrong
            thing and a nil would let it read on"
    (is (= {:rf.error/id :rf.error/ui-tree-malformed
            :where       're-frame.hicasso.test
            :recovery    :no-recovery}
           (refusal (outcome #(ht/role "milk")))))))

(deftest a-boundary-node-has-no-role-and-that-is-not-an-error
  (let [t (markup [:div [a-child {}]])]
    (is (nil? (ht/role (ht/find t #(some? (:view-id %)))))
        "L2 records a child boundary as the CALL it is; what it renders —
         and therefore what role it presents — is the child's own tree to
         answer")))

;; ---------------------------------------------------------------------------
;; accessible-name — the five steps, each shown to WIN over the next
;; ---------------------------------------------------------------------------

(deftest aria-labelledby-resolves-through-the-tree-in-the-order-written
  (let [t (markup [:div
                   [:span {:id "verb"} "Delete"]
                   [:span {:id "noun"} "draft"]
                   [:button {:aria-labelledby "verb noun"}]])]
    (is (= "Delete draft" (ht/accessible-name t (tagged t :button)))))
  (testing "the ORDER is the attribute's and not the document's"
    (let [t (markup [:div
                     [:span {:id "verb"} "Delete"]
                     [:span {:id "noun"} "draft"]
                     [:button {:aria-labelledby "noun verb"}]])]
      (is (= "draft Delete" (ht/accessible-name t (tagged t :button))))))
  (testing "an id that resolves to nothing contributes nothing, and does
            not take the rest of the name down with it"
    (let [t (markup [:div
                     [:span {:id "verb"} "Delete"]
                     [:button {:aria-labelledby "verb absent"}]])]
      (is (= "Delete" (ht/accessible-name t (tagged t :button)))))))

(deftest aria-labelledby-beats-aria-label-beats-the-content
  (let [t (markup [:div
                   [:span {:id "ref"} "from the reference"]
                   [:button {:aria-labelledby "ref" :aria-label "from the label"}
                    "from the content"]])]
    (is (= "from the reference" (ht/accessible-name t (tagged t :button)))
        "three sources, three DIFFERENT strings, so the winner identifies
         the step — a row whose sources agreed would be green under any
         ordering"))
  (let [t (markup [:div [:button {:aria-label "from the label"} "from the content"]])]
    (is (= "from the label" (ht/accessible-name t (tagged t :button)))))
  (let [t (markup [:div [:button "from the content"]])]
    (is (= "from the content" (ht/accessible-name t (tagged t :button))))))

(deftest a-blank-aria-label-does-not-count-as-a-name
  (let [t (markup [:div [:button {:aria-label "   "} "Save"]])]
    (is (= "Save" (ht/accessible-name t (tagged t :button)))
        "whitespace is not a name, so the fallback continues past it —
         and the content is what a screen reader would announce")))

(deftest a-name-is-flattened
  (let [t (markup [:div [:button "  Save \n   draft "]])]
    (is (= "Save draft" (ht/accessible-name t (tagged t :button)))
        "the platform's own flat-string step: runs collapse, the ends go")))

(deftest a-control-is-named-by-its-label-either-way-round
  (testing "`<label for=…>` pointing at an id"
    (let [t (markup [:form
                     [:label {:for "title"} "Title"]
                     [:input {:id "title" :type "text"}]])]
      (is (= "Title" (ht/accessible-name t (tagged t :input))))))
  (testing "and a `<label>` that simply contains the control — the wrapping
            form, which no attribute records"
    (let [t (markup [:form
                     [:label [:input {:type "checkbox"}] "Published"]])]
      (is (= "Published" (ht/accessible-name t (tagged t :input))))))
  (testing "both at once concatenate in DOCUMENT order"
    (let [t (markup [:form
                     [:label {:for "p"} "Publish"]
                     [:label [:input {:id "p" :type "checkbox"}] "immediately"]])]
      (is (= "Publish immediately" (ht/accessible-name t (tagged t :input)))))))

(deftest a-label-names-the-control-it-points-at-and-no-other
  (testing "the discrimination the `for`/`id` pair exists for: two labels,
            two controls, and a helper that merely found `the nearest
            label` would cross them"
    (let [t (markup [:form
                     [:label {:for "body"} "Body"]
                     [:label {:for "title"} "Title"]
                     [:input.title {:id "title" :type "text"}]
                     [:textarea.body {:id "body"}]])]
      (is (= "Title" (ht/accessible-name t (classed t "title"))))
      (is (= "Body" (ht/accessible-name t (classed t "body"))))))
  (testing "and a `for` that points nowhere leaves its control unnamed
            rather than borrowing the label beside it"
    (let [t (markup [:form
                     [:label {:for "typo"} "Title"]
                     [:input {:id "title" :type "text"}]])]
      (is (nil? (ht/accessible-name t (tagged t :input)))))))

(deftest a-label-beats-the-controls-own-text-and-aria-label-beats-the-label
  (let [t (markup [:form
                   [:label {:for "x"} "from the label"]
                   [:input {:id "x" :type "text" :aria-label "from aria"}]])]
    (is (= "from aria" (ht/accessible-name t (tagged t :input)))))
  (let [t (markup [:form
                   [:label {:for "x"} "from the label"]
                   [:input {:id "x" :type "text" :title "from the title"}]])]
    (is (= "from the label" (ht/accessible-name t (tagged t :input)))
        "and `:title` is the LAST resort, below the label rather than
         above it")))

(deftest the-host-languages-own-labelling-per-element
  (testing "a submit button is named by its :value, which is markup no
            label and no content supplies"
    (let [t (markup [:form [:input {:type "submit" :value "Send"}]])]
      (is (= "Send" (ht/accessible-name t (tagged t :input))))))
  (testing "an image by its alt"
    (let [t (markup [:div [:img {:src "a.png" :alt "A bar chart"}]])]
      (is (= "A bar chart" (ht/accessible-name t (tagged t :img))))))
  (testing "and a fieldset by its FIRST legend child, so a nested
            fieldset's legend cannot be borrowed by the one above it"
    (let [t (markup [:fieldset
                     [:legend "Outer"]
                     [:fieldset [:legend "Inner"]]])]
      (is (= "Outer" (ht/accessible-name t t)))
      (is (= "Inner" (ht/accessible-name
                       t
                       (ht/find t #(and (= :fieldset (:tag %))
                                        (not (identical? % t))))))
          "read off the outer tree, so the two fieldsets are one
           document and the inner one still answers its own legend"))))

(deftest only-the-roles-that-take-a-name-from-content-take-one
  (let [t (markup [:div
                   [:p.alert {:role "alert"} "Could not save"]
                   [:p.plain "ordinary prose"]
                   [:li.item "an item"]
                   [:a.link {:href "/x"} "a link"]
                   [:h2.head "a heading"]])]
    (is (= "an item" (ht/accessible-name t (classed t "item"))))
    (is (= "a link" (ht/accessible-name t (classed t "link"))))
    (is (= "a heading" (ht/accessible-name t (classed t "head"))))
    (testing "an alert is announced when it appears and is not NAMED by
              what it says — answering `Could not save` here would be the
              kit inventing a name the platform does not give"
      (is (nil? (ht/accessible-name t (classed t "alert")))))
    (testing "and ordinary prose has no role, so no name"
      (is (nil? (ht/accessible-name t (classed t "plain")))))))

(deftest title-is-the-last-resort-and-is-still-a-name
  (let [t (markup [:div [:input {:type "text" :title "Search"}]])]
    (is (= "Search" (ht/accessible-name t (tagged t :input))))))

(deftest accessible-name-refuses-a-node-from-somewhere-else
  (let [labelled (markup [:form
                          [:label {:for "x"} "Title"]
                          [:input {:id "x" :type "text"}]])
        elsewhere (markup [:div [:p "unrelated"]])]
    (testing "the legal twin first: read against its OWN tree the control
              is named, so the refusal below is about the pairing and not
              about markup that was broken anyway"
      (is (= "Title" (ht/accessible-name labelled (tagged labelled :input)))))
    (testing "and against a tree it is not in, a REFUSAL rather than nil —
              nil would report a labelled control as unlabelled, which is
              the wrong answer in the one direction that matters"
      (is (= {:rf.error/id :rf.error/ui-tree-malformed
              :where       're-frame.hicasso.test
              :recovery    :pass-the-tree-the-node-came-from}
             (refusal (outcome #(ht/accessible-name elsewhere
                                                    (tagged labelled :input)))))))
    (testing "the discriminator reports :returned for the legal pairing,
              so the refusal row is not a helper that only knows one verb"
      (is (= {:returned "Title"}
             (outcome #(ht/accessible-name labelled (tagged labelled :input))))))))

(deftest accessible-name-nil-puns-and-refuses-text
  (is (nil? (ht/accessible-name (markup [:div]) nil)))
  (is (= {:rf.error/id :rf.error/ui-tree-malformed
          :where       're-frame.hicasso.test
          :recovery    :no-recovery}
         (refusal (outcome #(ht/accessible-name (markup [:div]) "milk"))))))

;; ---------------------------------------------------------------------------
;; unnamed-controls — the auditor, sabotaged in both directions
;; ---------------------------------------------------------------------------

(def ^:private a-named-form
  "One small form in which every operable control is named, and named a
  DIFFERENT legal way each time — by content, by `aria-label`, by
  `<label for=…>`, by a wrapping `<label>`, by `aria-labelledby`, and by
  `:value`. The sabotage rows below each remove exactly one of them."
  [:form
   [:button {:type "button"} "Named by content"]
   [:button.aria {:type "button" :aria-label "Named by aria-label"}]
   [:label {:for "by-for"} "Named by for"]
   [:input {:id "by-for" :type "text"}]
   [:label [:input.wrapped {:type "checkbox"}] "Named by wrapping"]
   [:span {:id "ref"} "Named by reference"]
   [:select.referenced {:aria-labelledby "ref"}]
   [:input.submit {:type "submit" :value "Named by value"}]
   [:a {:href "/x"} "Named link"]])

(deftest a-fully-named-form-reports-nothing
  (is (= [] (ht/unnamed-controls (markup a-named-form)))
      "the legal side. Six naming mechanisms, one assertion — and an
       auditor that reported any of them would be a nag rather than a
       gate"))

(deftest removing-any-one-name-reports-that-one-control-and-only-it
  (testing "the content off a button"
    (let [t (markup (assoc a-named-form 1 [:button {:type "button"}]))
          found (ht/unnamed-controls t)]
      (is (= 1 (count found)))
      (is (= :button (:tag (first found))))))
  (testing "the aria-label off the second button"
    (let [t (markup (assoc a-named-form 2 [:button.aria {:type "button"}]))
          found (ht/unnamed-controls t)]
      (is (= 1 (count found)))
      (is (= "aria" (:class (ht/attrs (first found)))))))
  (testing "the `for` off the label — the control keeps its id and the
            label keeps its text, so nothing about the markup LOOKS
            different except the one attribute that binds them"
    (let [t (markup (assoc a-named-form 3 [:label "Named by for"]))
          found (ht/unnamed-controls t)]
      (is (= 1 (count found)))
      (is (= "by-for" (:id (ht/attrs (first found)))))))
  (testing "the TEXT out of the wrapping label, keeping the containment —
            so what this row measures is the label's contribution and not
            merely whether a label is nearby"
    (let [t (markup (assoc a-named-form 5
                           [:label [:input.wrapped {:type "checkbox"}]]))
          found (ht/unnamed-controls t)]
      (is (= 1 (count found)))
      (is (= "wrapped" (:class (ht/attrs (first found)))))))
  (testing "the referent of aria-labelledby"
    (let [t (markup (assoc a-named-form 6 [:span {:id "other"} "Named by reference"]))
          found (ht/unnamed-controls t)]
      (is (= 1 (count found)))
      (is (= "referenced" (:class (ht/attrs (first found)))))))
  (testing "the :value off the submit button"
    (let [t (markup (assoc a-named-form 8 [:input.submit {:type "submit"}]))
          found (ht/unnamed-controls t)]
      (is (= 1 (count found)))
      (is (= "submit" (:class (ht/attrs (first found)))))))
  (testing "the text out of the link"
    (let [t (markup (assoc a-named-form 9 [:a {:href "/x"}]))
          found (ht/unnamed-controls t)]
      (is (= 1 (count found)))
      (is (= :a (:tag (first found)))))))

(deftest moving-a-control-out-of-its-wrapping-label-reports-it
  (testing "the sabotage the row above deliberately did not do: the input
            leaves the label entirely. Two rows, one attribute apart, and
            they must disagree — which is what says `contains` is really
            being computed rather than `there is a label somewhere`"
    (let [t (markup [:form
                     [:label "Published"]
                     [:input.wrapped {:type "checkbox"}]])]
      (is (= ["wrapped"] (mapv #(:class (ht/attrs %)) (ht/unnamed-controls t)))))))

(deftest what-the-auditor-must-not-report
  (testing "a nameless element that is not operable is not a finding —
            an auditor that reported every unnamed node would report the
            whole page, and a gate nobody can satisfy is turned off"
    (let [t (markup [:div
                     [:p "prose"]
                     [:p {:role "alert"} "Could not save"]
                     [:span]
                     [:ul [:li]]
                     [:img {:src "a.png" :alt ""}]
                     [:a "a bare anchor, which is not a link"]])]
      (is (= [] (ht/unnamed-controls t)))))
  (testing "and a control that is DISABLED is still a control — it is
            announced, it can be enabled, and it needs its name"
    (let [t (markup [:form [:button {:type "button" :disabled true}]])]
      (is (= 1 (count (ht/unnamed-controls t)))))))

(deftest the-auditor-reports-in-document-order-and-reports-every-offender
  (let [t (markup [:form
                   [:button.one {:type "button"}]
                   [:button.two {:type "button"} "named"]
                   [:input.three {:type "text"}]])]
    (is (= ["one" "three"] (mapv #(:class (ht/attrs %)) (ht/unnamed-controls t)))
        "both offenders and the named one skipped — a helper that
         short-circuited at the first finding would answer `[one]` and
         look right")))
