(ns re-frame.freehand.guide.first-view-cljs-test
  "Executable fixtures for the Freehand guide's OPENING chapters —
  `docs/core/freehand/index.md`, `build-a-view.md`, `mental-model.md` and
  `vs-reagent.md`.

  Every var here is one fenced code block from those pages, transcribed. The
  roster in `re-frame.freehand.guide.samples-coverage-jvm-test` names it, so
  the link between a page's sample and the code that proves it is checked
  rather than remembered.

  ## What a fixture is for

  The guide is written against a deliberately UNFROZEN door. Its value
  depends on `v/defview`, `v/sub`, `::v/value` and the rest still spelling
  what the page says they spell — and prose cannot notice a rename. A
  fixture can: transcribing the sample onto the real classpath makes a moved
  verb a compile failure on both hosts, named at the line of the sample it
  broke.

  ## Two transcription conventions

  A fenced block is not always a top-level form, so two conventions carry the
  ones that are not, and neither invents API:

  - **A bare markup fragment is hosted in a one-line `v/defview`.** The
    guide often shows an element in isolation (`[:button {:on-click …} \"+\"]`).
    A `def` would not do: markup carrying `(v/sub …)` calls it at load time,
    outside any render. The declaration is the smallest honest host, and it
    makes the fragment renderable through `t/render` besides.
  - **Free names in a fragment become props.** `product-id` in the guide's
    `[:button {:on-click [:cart/add product-id]}]` is whatever the
    surrounding view had in scope; here it arrives as a prop, which is what
    the surrounding view would have had to do.

  Filed under rf2-qwsmv; the durability argument is that bead's."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; index.md — the headline view
;; ---------------------------------------------------------------------------

;; index.md block 1 — the page's one sample, and the whole pitch in three lines.
(v/defview cart-badge [_]
  [:button {:on-click [:cart/open]}
   "Cart (" (v/sub [:cart/count]) ")"])

;; ---------------------------------------------------------------------------
;; build-a-view.md — the counter, grown one step at a time
;; ---------------------------------------------------------------------------

;; build-a-view.md block 2 — the dataflow half, which is plain re-frame2 and
;; deliberately not a Freehand concern.
(defn register-counter-dataflow!
  []
  (rf/reg-event :app/init  (fn [_ _] {:db {:count 0 :note ""}}))
  (rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
  (rf/reg-event :note/set
    (fn [{:keys [db]} [_ text]]
      {:db (assoc db :note text)}))

  (rf/reg-sub :count (fn [db _] (:count db)))
  (rf/reg-sub :note  (fn [db _] (:note db))))

;; build-a-view.md block 3 — step one: structure and one read.
(v/defview counter-v1 [_]
  [:div.counter
   [:p "Count: " (sub [:count])]])

;; build-a-view.md block 4 — step two: intent as data on a button.
(v/defview counter-v2 [_]
  [:div.counter
   [:p "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "Increment"]])

;; build-a-view.md block 5 — step three: a controlled field and `::v/value`.
(v/defview counter-v3 [_]
  [:div.counter
   [:p "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "Increment"]
   [:input {:value       (sub [:note])
            :on-input    [:note/set ::v/value]
            :placeholder "A note…"}]])

;; build-a-view.md block 6 — a keyed child per row, each reading its own slice.
(v/defview note-row [{:keys [id]}]
  (let [text (sub [:notes/by-id id])]
    [:li
     [:input {:value    text
              :on-input [:notes/set id ::v/value]}]]))

(v/defview note-list [_]
  [:ul
   (for [id (sub [:notes/ids])]
     [note-row {:key id :id id}])])

;; ---------------------------------------------------------------------------
;; mental-model.md — the vocabulary, one form at a time
;; ---------------------------------------------------------------------------

;; mental-model.md block 1 (and vs-reagent.md block 1) — a declaration.
(v/defview greeting [{:keys [name]}]
  [:p "Hello, " name])

;; mental-model.md block 2 — a call site: a vector, never `(greeting …)`.
(def greeting-call
  [greeting {:name "Ada"}])

;; mental-model.md block 3 — a pure helper is fine in interpreted markup; a
;; declared child is what a boundary is for.
(defn- money [amount] (str "$" amount))

(defn price-line [{:keys [label amount]}]
  [:div.price-line [:span label] [:span (money amount)]])

(v/defview tax-summary [{:keys [invoice-id]}]
  [:div.tax (str "tax for " invoice-id)])

(v/defview invoice [{:keys [id]}]
  [:section
   (price-line {:label "Subtotal"
                :amount (v/sub [:invoice/subtotal id])})
   [tax-summary {:invoice-id id}]])

;; mental-model.md block 5 — a read, in place, with no deref.
(v/defview count-span [_]
  [:span "Count: " (v/sub [:count])])

;; mental-model.md block 6 — the one-shot read outside a render, which is
;; re-frame's, not Freehand's.
(defn basket-total-once
  []
  (rf/subscribe-once [:basket/total] {:frame :app/main}))

;; mental-model.md block 7 (and vs-reagent.md block 3) — intent as data.
(v/defview inc-button [_]
  [:button {:on-click [:count/inc]} "+"])

;; mental-model.md block 8 — the controlled door, with a projection marker.
(v/defview email-input [{:keys [email]}]
  [:input {:value email
           :on-input [:form/edit :email ::v/value]}])

;; ---------------------------------------------------------------------------
;; vs-reagent.md — the Freehand half of each comparison
;; ---------------------------------------------------------------------------
;;
;; Each block on that page shows a Reagent-ish spelling beside the Freehand
;; one. Only the Freehand half is transcribed: the Reagent half names an
;; artefact this one takes no dependency on, and deliberately so.

;; vs-reagent.md block 2 — the read, with no deref and no ceremony.
(v/defview count-span-bare [_]
  [:span (v/sub [:count])])

;; vs-reagent.md block 4 — the controlled field.
(v/defview email-input-vs-reagent [_]
  [:input {:value (v/sub [:email])
           :on-input [:form/set-email ::v/value]}])

;; vs-reagent.md block 5 — keys ride in props, not in metadata.
(v/defview row-view [{:keys [row]}]
  [:li (:label row)])

(v/defview keyed-rows [{:keys [rows]}]
  [:ul
   (for [r rows]
     [row-view {:key (:id r) :row r}])])

;; vs-reagent.md block 6 — the side-by-side counter, Freehand half.
(v/defview counter-vs-reagent [_]
  [:div
   [:p "Count: " (v/sub [:count])]
   [:button {:on-click [:count/inc]} "Inc"]
   [:input {:value (v/sub [:note])
            :on-input [:note/set ::v/value]}]])

;; ---------------------------------------------------------------------------
;; The samples, executed
;; ---------------------------------------------------------------------------

(defn- seed!
  "Run the guide's own dataflow, then set the state the page's prose assumes."
  [db]
  (register-counter-dataflow!)
  (rf/reg-sub :cart/count (fn [d _] (:cart-count d)))
  (rf/reg-sub :notes/ids (fn [d _] (:note-ids d)))
  (rf/reg-sub :notes/by-id (fn [d [_ id]] (get-in d [:notes id])))
  (rf/dispatch-sync [:rf/set-db db]))

(deftest the-counter-grows-exactly-as-the-page-says
  (testing "each step of build-a-view.md renders, and the step that adds a
            button adds the intent the page claims."
    (seed! {:count 7 :note "hi"})
    (is (= "Count: 7" (t/text (t/with-render (t/render [counter-v1 {}]))))
        "step one shows the read")
    (let [tree (t/with-render (t/render [counter-v2 {}]))]
      (is (= [:count/inc]
             (:on-click (t/attrs (t/find tree #(= :button (:tag %))))))
          "step two carries the increment intent as data"))
    (let [tree  (t/with-render (t/render [counter-v3 {}]))
          input (t/find tree #(= :input (:tag %)))]
      (is (= "hi" (:value (t/attrs input))) "step three is controlled")
      (is (= [:note/set ::v/value] (:on-input (t/attrs input)))
          "and carries the projection marker, unfilled, on the tree"))))

(deftest a-keyed-child-reads-its-own-row
  (testing "build-a-view.md's note list — one child per id, each subscribing
            for itself, which is the page's whole point about boundaries."
    (seed! {:note-ids [:a :b] :notes {:a "alpha" :b "beta"}})
    (let [tree   (t/with-render (t/render [note-list {}]))
          inputs (t/find-all tree #(= :input (:tag %)))]
      (is (= 2 (count inputs)) "one row per id")
      (is (= ["alpha" "beta"] (mapv #(:value (t/attrs %)) inputs))
          "each row read its own slice")
      (is (= [:notes/set :a ::v/value] (:on-input (t/attrs (first inputs))))
          "and carries its own id in the intent"))))

(deftest a-call-site-is-a-vector-and-a-declaration-is-a-descriptor
  (testing "mental-model.md's central claim: the var holds a descriptor, the
            call site is data, and `(greeting {})` is never written."
    (is (v/view? greeting) "the var holds a descriptor")
    (is (= [greeting {:name "Ada"}] greeting-call) "the call site is a vector")
    (is (= "Hello, Ada" (t/text (t/render greeting-call)))
        "and the vector renders")))

(deftest the-headline-badge-renders
  (testing "index.md's sample is a real view that reads real state."
    (seed! {:cart-count 3})
    (is (= "Cart (3)" (t/text (t/with-render (t/render [cart-badge {}])))))))

(deftest the-vs-reagent-half-is-the-paved-path
  (testing "vs-reagent.md contrasts spellings; the Freehand half is the
            ordinary one, and it renders with no adapter in sight."
    (seed! {:count 2 :note "n"})
    (is (= "2" (t/text (t/with-render (t/render [count-span-bare {}])))))
    (let [tree (t/with-render (t/render [keyed-rows {:rows [{:id 1 :label "one"}
                                                            {:id 2 :label "two"}]}]))]
      (is (= ["one" "two"] (mapv t/text (t/find-all tree #(= :li (:tag %)))))
          "keys ride in props and the rows still render in order"))))
