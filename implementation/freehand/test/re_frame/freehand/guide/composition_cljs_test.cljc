(ns re-frame.freehand.guide.composition-cljs-test
  "Executable fixtures for the Freehand guide's COMPOSITION chapters —
  `docs/core/freehand/authoring/composition.md`, `state.md` and
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
  [{:keys [value on-change caller-attrs submitting?]}]
  [;; owned literals first, caller (or part) map second
   (v/spread-safe
     {:data-part "control"
      :value value
      :on-input (conj on-change ::v/value)}
     caller-attrs)

   ;; an element of your own — caller's remainder first, owned literals second
   ;; so they win
   (v/spread
     caller-attrs
     {:data-part "root"
      :disabled  submitting?})])

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
;; in for whatever npm component the consumer imported, and on the JVM the
;; declaration drops it: a `v/defhost` expansion carries its component only
;; in ClojureScript, which is exactly what lets one `.cljc` declaration
;; answer a structural render here and a real mount in the browser.
(def DatePicker "some-date-picker/DatePicker")

(v/defhost date-picker
  "A third-party date picker."
  DatePicker
  {:callbacks {:onChange :event}
   :children  :none
   :ssr       :client-only})

(v/defview date-field [{:keys [date on-pick] :as props}]
  [date-picker
   (merge (dissoc props :date :on-pick)
          {:selected date
           :onChange (v/event [js-date] (conj on-pick js-date))})])

;; composition.md block 13 — the caller reaching both parts.
(def field-call-with-parts
  (let [email "ada@example.com"]
    [field {:label "Email"
            :value email
            :on-change [:account/email-changed]
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

(deftest the-foreign-widget-crosses-through-a-declared-host-head
  (testing "composition.md block 10 — the sketch that used to raise
            `:rf.error/view-bad-head` by putting the imported component at
            the head. `v/defhost` mints the third legal head, and the block
            is rendered here with a COLLIDING `:selected` because a merge-law
            assertion that never sees a collision asserts nothing: the owned
            literal is `merge`'s later argument, so it wins.

            The `:className` in the caller's remainder is the block's OTHER
            claim, and the reason the page forwards with `merge` rather than
            `v/spread`. A spread canonicalises every key onto the slot an
            element would write it into, so `:className` would reach the
            component as `class` — a different React prop, and the one a
            third-party library is most likely to read. `merge` leaves the
            name the caller wrote."
    (let [tree (t/render [date-field {:date      "2026-01-05"
                                      :on-pick   [:booking/date-picked]
                                      :class     "wide"
                                      :className "from-a-react-lib"
                                      :selected  "caller-tries-to-clobber"}])
          host (t/find tree #(contains? % :rf.ui/host))]
      (is (some? host)
          "the crossing is a node in the tree, not an exception")
      (is (= ::date-picker (:rf.ui/host host))
          "recorded under the declaration's own qualified id")
      (is (= :client-only (:rf.ui/host-ssr host))
          "carrying the declared SSR policy, and no server content")
      (is (= [] (:children host))
          "which is what `:client-only` projects")
      (is (= "2026-01-05" (:selected (:props host)))
          "the owned literal won the collision")
      (is (= "wide" (:class (:props host)))
          "and the caller's remainder still forwarded underneath it")
      (is (= "from-a-react-lib" (:className (:props host)))
          "`:className` crossed under its own name — `merge` renames nothing")
      (is (= {:rf.ui/opaque :v/event} (:onChange (:props host)))
          "the declared callback records as its opaque role marker"))))

(deftest the-two-forwarding-shapes-put-the-owned-map-where-each-form-wants-it
  (testing "composition.md block 8 — the page's two `Shape` calls differ in
            argument ORDER, and that is the whole claim. `v/spread-safe`
            takes the owned map FIRST and denies the caller the controlled
            keys; `v/spread` is later-arg-wins, so the owned map goes SECOND
            or the caller silently replaces it. Both fold onto an ELEMENT —
            the host head takes an ordinary `merge`, which is block 10."
    (let [[safe general] (spread-forms {:value        "ada@example.com"
                                        :on-change    [:account/email-changed]
                                        :caller-attrs {:class     "wide-control"
                                                       :disabled  false
                                                       :data-part "caller-tries-to-clobber"}
                                        :submitting?  true})]
      (is (= "ada@example.com" (:value safe))
          "spread-safe: the owned controlled value survives the caller map")
      (is (= true (:disabled general))
          "spread: the owned literal is the OVERRIDE, so it wins")
      (is (= "root" (:data-part general))
          "for every colliding key, not just the interesting one")
      (is (= "wide-control" (:class general))
          "and the caller's remainder is otherwise forwarded whole"))))

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

(deftest the-remaining-ladder-rungs-and-state-shapes-render
  (testing "composition.md's policy and library rungs, and state.md's two
            field shapes, each transcribed and then rendered — a fixture
            that only LOADS proves no render-time law (rf2-kem4o)."
    (seed! {:open {:d1 true} :email "ada@example.com" :email-draft "ada@"})

    (testing "block 2 — a `:children-policy :none` leaf takes its content as props"
      (let [attrs (t/attrs (t/find (t/render [icon-button {:event [:item/deleted 42]
                                                           :label "Delete"}])
                                   #(= :button (:tag %))))]
        (is (= "Delete" (:aria-label attrs)))
        (is (= [:item/deleted 42] (:on-click attrs))
            "the caller's intent reaches the child's own site as data")))

    (testing "block 4 — the fixed region the component places, when it decides to"
      (let [open (t/with-render (t/render [disclosure {:id :d1 :label "Why?"}
                                           [:p.body "because"]]))]
        (is (= "because" (t/text (t/find open #(= :p (:tag %)))))
            "open — the caller's children are placed in the body region")
        (is (true? (:aria-expanded (t/attrs (t/find open #(= :button (:tag %))))))))
      (let [shut (t/with-render (t/render [disclosure {:id :d2 :label "Why?"}
                                           [:p.body "because"]]))]
        (is (nil? (t/find shut #(= :p (:tag %))))
            "closed — the same children are simply not placed")))

    (testing "block 13 — the caller reaches BOTH declared parts"
      (let [tree (t/with-render (t/render field-call-with-parts))]
        (is (= "quiet-label"
               (:class (t/attrs (t/find tree #(= :span (:tag %))))))
            "the label part took the caller's class")
        (is (= "wide-control"
               (:class (t/attrs (t/find tree #(= :input (:tag %))))))
            "and so did the control part")))

    (testing "state.md blocks 4 and 5 — live domain value versus app-owned draft"
      (let [live (t/attrs (t/find (t/with-render (t/render [email-controlled-input {}]))
                                  #(= :input (:tag %))))]
        (is (= "ada@example.com" (:value live)))
        (is (= [:form/set-email ::v/value] (:on-input live)))
        (is (nil? (:on-blur live)) "the live field has no commit transition"))
      (let [draft (t/attrs (t/find (t/with-render (t/render [email-draft-input {}]))
                                   #(= :input (:tag %))))]
        (is (= "ada@" (:value draft)) "the draft field reads the draft key")
        (is (= [:form/email-committed] (:on-blur draft))
            "and commits on blur, which is the whole difference")))))

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
