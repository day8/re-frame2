(ns re-frame.freehand.guide.composition-cljs-test
  "Executable fixtures for the Freehand guide's COMPOSITION chapters —
  `docs/core/freehand/composition.md`, `state.md` and
  `reactivity-and-ownership.md`.

  These pages carry the vocabulary a component library lives on: trailing
  children and `:children-policy`, `v/slot` / `v/render-fn`, and the two
  props-forwarding forms `v/spread-safe` and `v/spread` with their asymmetric
  bargains. Those are exactly the verbs a refactor moves and prose cannot
  notice — so each sample is transcribed here, where a move is a compile
  failure.

  The two transcription conventions are the ones stated in
  `re-frame.freehand.guide.first-view-cljs-test`: a bare markup fragment is
  hosted in a one-line `v/defview`, and a fragment's free names arrive as
  props. Where two blocks on one page declare the same name with different
  bodies, the later fixture var is suffixed — the roster names the fixture
  var, and the comment above it names the block.

  Filed under rf2-qwsmv."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(declare app-page workspace)

;; ---------------------------------------------------------------------------
;; composition.md — the children ladder
;; ---------------------------------------------------------------------------

(v/defview details [{:keys [id]}]
  [:p.details (str "details for " id)])

;; composition.md block 1 (and state.md block 3) — trailing children, and the
;; call site that supplies them.
(v/defview panel [{:keys [title children]}]
  [:section.panel
   [:h2 title]
   children])

(def panel-call
  (let [panel-id :p1]
    [panel {:key panel-id :title "Details"}
     [details {:id panel-id}]]))

;; composition.md block 2 — the three children policies, declared.
(v/defview icon-glyph [{:keys [name]}]
  [:i.icon {:data-name name}])

(v/defview icon-button
  {:children-policy :none}
  [{:keys [event label]}]
  [:button {:on-click event :aria-label label}
   [icon-glyph {:name label}]])

(v/defview card-optional-children
  {:children-policy :optional}
  [{:keys [title children]}]
  [:article.card
   (when title [:header title])
   (when (seq children) [:div.card-body children])])

(v/defview dialog-shell
  {:children-policy :required}
  [{:keys [title children]}]
  [:section.dialog
   [:h2 title]
   [:div.dialog-body children]])

;; composition.md block 3 — a default region, plus a caller passing two
;; children into it.
(v/defview line-items [{:keys [invoice-id]}]
  [:ul.line-items [:li (str "lines for " invoice-id)]])

(v/defview totals [{:keys [invoice-id]}]
  [:p.totals (str "total for " invoice-id)])

(v/defview card [{:keys [title children]}]
  [:article.card
   (when title [:header title])
   [:div.card-body children]])

(def card-call
  (let [id 7]
    [card {:title "Invoice"}
     [line-items {:invoice-id id}]
     [totals {:invoice-id id}]]))

;; composition.md block 4 — the library shape: a fixed region carried as
;; children, placed where the component decides.
(v/defview disclosure [{:keys [id label children]}]
  (let [open? (v/sub [:disclosure/open? id])]
    [:div.disclosure
     [:button {:aria-expanded open?
               :on-click      [:disclosure/toggled id]}
      label]
     (when open? [:div.body children])]))

;; composition.md block 5 — parameterized rows through `v/render-fn` /
;; `v/slot`, interpreted.
(v/defview data-table [{:keys [rows row]}]
  [:table
   [:tbody
    (for [item rows]
      [:tr {:key (:id item)}
       (v/slot row item)])]])

(def data-table-call
  (let [people [{:id 1 :name "Ada" :email "ada@example.com"}]]
    [data-table
     {:rows people
      :row  (v/render-fn [person]
              [:<>
               [:td (:name person)]
               [:td (:email person)]])}]))

;; composition.md block 6 — a keyed child that subscribes for one row.
(v/defview person-row [{:keys [id]}]
  (let [person (v/sub [:person/by-id id])]
    [:tr
     [:td (:name person)]
     [:td [:button {:on-click [:person/edit id]} "Edit"]]]))

(v/defview person-rows [{:keys [ids]}]
  [:tbody
   (for [id ids]
     [person-row {:key id :id id}])])

;; composition.md block 7 (and reactivity-and-ownership.md block 1) — the
;; canonical keyed list.
(v/defview todo-row [{:keys [id]}]
  [:li.todo (str "todo " id)])

(v/defview todo-list [_]
  [:ul.todo-list
   (for [id (v/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])

;; composition.md block 8 — the two forwarding forms, side by side. Not a
;; declaration, so it is hosted in a function: the block's claim is the
;; SHAPE of the two calls, and a function is the smallest thing that holds
;; the free names the page left in scope.
(defn spread-forms
  [{:keys [value on-change caller-attrs date props]}]
  [;; owned literals first, caller (or part) map second
   (v/spread-safe
     {:data-part "control"
      :value value
      :on-input (conj on-change ::v/value)}
     caller-attrs)

   ;; foreign leaf — open remainder, owned contract wins
   (v/spread
     {:selected date
      :on-change (v/event [v] [:picker/picked v])}
     (dissoc props :date :on-pick))])

;; composition.md blocks 9 and 12 — the worked library field. The two blocks
;; show the same declaration (block 9 with its caller, block 12 alone), so
;; both roster rows name this one var.
(v/defview field
  [{:keys [label value on-change parts]}]
  [:label {:data-component "my.lib/field"
           :data-part "root"
           :class "my-lib-field"}
   [:span (v/spread-safe
           {:data-part "label"}
           (get parts :label))
    label]
   [:input
    (v/spread-safe
     {:data-part "control"
      :value value
      :on-input (conj on-change ::v/value)}
     (get parts :control))]])

(def field-call
  (let [email "ada@example.com"]
    [field {:label "Email"
            :value email
            :on-change [:account/email-changed]
            :parts {:control {:class "wide-control"
                              :data-analytics "signup-email"}}}]))

;; composition.md block 10 — the foreign-widget sketch. `DatePicker` stands
;; in for whatever npm component the consumer imported: the block is a
;; sketch the guide never renders, and what it pins here is `v/spread` and
;; `v/event` at a foreign head.
(def DatePicker "some-date-picker/DatePicker")

(v/defview date-field [{:keys [date on-pick] :as props}]
  [DatePicker
   (v/spread {:selected date
              :on-change (v/event [v] (on-pick v))}
             (dissoc props :date :on-pick))])

;; composition.md block 13 — the caller reaching both parts.
(def field-call-with-parts
  (let [email "ada@example.com"]
    [field {:label "Email"
            :value email
            :on-input [:account/email-changed ::v/value]
            :parts {:label   {:class "quiet-label"}
                    :control {:class "wide-control"
                              :data-analytics "signup-email"}}}]))

;; ---------------------------------------------------------------------------
;; state.md — where state lives, and how far down it travels
;; ---------------------------------------------------------------------------

;; state.md block 1 — read narrowly, at the boundary that needs it.
(v/defview order-summary [{:keys [order-id]}]
  (let [order (v/sub [:orders/by-id order-id])
        total (v/sub [:orders/total order-id])]
    [:div
     [:h3 (:title order)]
     [:strong (str "$" total)]]))

;; state.md block 2 — the opposite arrangement: one read at the top, props
;; the rest of the way down.
(v/defview app-root [_]
  [app-page {:model (v/sub [:app/view-model])}])

(v/defview app-page [{:keys [model]}]
  [workspace {:model model}])   ; no sub below if the model is complete

(v/defview workspace [{:keys [model]}]
  [:div.workspace (:title model)])

;; state.md block 4 — the live domain field.
(v/defview email-controlled-input [_]
  [:input {:value    (v/sub [:form/email])
           :on-input [:form/set-email ::v/value]}])

;; state.md block 5 — the app-owned draft, committed on blur or Enter.
(v/defview email-draft-input [_]
  [:input {:value       (v/sub [:form/email-draft])
           :on-input    [:form/email-drafted ::v/value]
           :on-blur     [:form/email-committed]
           :on-key-down [:form/email-key ::v/key]}])

;; ---------------------------------------------------------------------------
;; reactivity-and-ownership.md — windowing before compiling
;; ---------------------------------------------------------------------------

;; reactivity-and-ownership.md block 2 — the parent reads the window, each
;; row reads itself.
(v/defview people-page [_]
  (let [ids (v/sub [:people/visible-window-ids])]  ; e.g. ~40 rows for the viewport
    [:ul.people
     (for [id ids]
       [person-row {:key id :id id}])]))

;; ---------------------------------------------------------------------------
;; The samples, executed
;; ---------------------------------------------------------------------------

(defn- seed!
  [db]
  (rf/reg-sub :disclosure/open? (fn [d [_ id]] (get-in d [:open id])))
  (rf/reg-sub :person/by-id (fn [d [_ id]] (get-in d [:people id])))
  (rf/reg-sub :todo/visible-ids (fn [d _] (:todo-ids d)))
  (rf/reg-sub :people/visible-window-ids (fn [d _] (:window d)))
  (rf/reg-sub :orders/by-id (fn [d [_ id]] (get-in d [:orders id])))
  (rf/reg-sub :orders/total (fn [d [_ id]] (get-in d [:totals id])))
  (rf/reg-sub :app/view-model (fn [d _] (:view-model d)))
  (rf/reg-sub :form/email (fn [d _] (:email d)))
  (rf/reg-sub :form/email-draft (fn [d _] (:email-draft d)))
  (rf/dispatch-sync [:rf/set-db db]))

(deftest trailing-children-reach-the-region-the-parent-placed
  (testing "composition.md's ladder rung one — children are content the
            caller supplies and the parent positions."
    (let [tree (t/render panel-call)]
      (is (= "Details" (t/text (t/find tree #(= :h2 (:tag %)))))
          "the parent's own markup renders")
      (is (some? (t/find tree #(= :p (:tag %))))
          "and the caller's child renders inside it"))
    (let [tree (t/render card-call)]
      (is (= 2 (count (t/find-all tree #(#{:ul :p} (:tag %)))))
          "two trailing children, both placed in the body region"))))

(deftest a-render-fn-fills-the-slot-the-table-owns
  (testing "composition.md's parameterized rows — the caller supplies a pure
            body, the component decides where it runs."
    (let [tree (t/render data-table-call)
          tds  (t/find-all tree #(= :td (:tag %)))]
      (is (= ["Ada" "ada@example.com"] (mapv t/text tds))
          "both cells of the caller's row body rendered, in order"))))

(deftest spread-safe-keeps-the-owned-contract-and-composes-class
  (testing "composition.md's merge law, executed: the caller restyles and
            annotates, and cannot reach the controlled keys."
    (let [tree  (t/render field-call)
          input (t/find tree #(= :input (:tag %)))
          attrs (t/attrs input)]
      (is (= "ada@example.com" (:value attrs)) "the owned :value survives")
      (is (= [:account/email-changed ::v/value] (:on-input attrs))
          "and so does the owned handler, with the marker conj'd on")
      (is (= "signup-email" (:data-analytics attrs))
          "the caller's annotation landed")
      (is (= "control" (:data-part attrs))
          "under the component's own part name"))))

(deftest a-narrow-read-stays-at-the-boundary-that-needs-it
  (testing "state.md's first claim — a view reads what it shows, and the
            page's two arrangements both render."
    (seed! {:orders {7 {:title "Widgets"}} :totals {7 42}
            :view-model {:title "W"}})
    (let [tree (t/with-render (t/render [order-summary {:order-id 7}]))]
      (is (= "Widgets" (t/text (t/find tree #(= :h3 (:tag %))))))
      (is (= "$42" (t/text (t/find tree #(= :strong (:tag %)))))))
    (is (= "W" (t/text (t/with-render (t/render [app-root {}]))))
        "one read at the top, props the rest of the way down")))

(deftest a-keyed-list-mounts-one-child-per-id
  (testing "composition.md and reactivity-and-ownership.md share this claim:
            the parent reads the ids, each child reads itself."
    (seed! {:todo-ids [1 2 3]
            :window [:a :b]
            :people {:a {:name "Ada"} :b {:name "Bob"}}})
    (is (= 3 (count (t/find-all (t/with-render (t/render [todo-list {}]))
                                #(= :li (:tag %)))))
        "one row per visible id")
    (let [tree (t/with-render (t/render [people-page {}]))]
      (is (= ["Ada" "Bob"]
             (mapv t/text (t/find-all tree #(and (= :td (:tag %))
                                                 (seq (t/text %))
                                                 (not= "Edit" (t/text %))))))
          "and the windowed page mounts only the window"))))
