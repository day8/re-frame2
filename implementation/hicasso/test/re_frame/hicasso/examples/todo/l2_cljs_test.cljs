(ns re-frame.hicasso.examples.todo.l2-cljs-test
  "L2 — THE BODIES, AS SEMANTIC TREES (rf2-hic-086).

  `re-frame.hicasso.test/tree` runs one hook-free body under injected
  read fixtures and answers the Spec 004B structural tree it returned. No
  React, no element, no hook, no DOM — so a row here proves what a body
  MEANS, and nothing about what a user sees. The mounted suite is the
  other half and says so.

  ## Why the fixtures are exhaustive rather than convenient

  `:subs` refuses a read no fixture answers. That is the tier's sharpest
  instrument and this file leans on it: the fixture map for each body is
  that body's read set, written out, so **adding a read to a view reds
  this file** rather than passing quietly. A view's edge set is what
  decides when it re-renders, and a change to it should have to be
  acknowledged.

  ## A child boundary is a CALL, not a rendering

  `[todo-row {…}]` inside `todo-list` records a view-boundary node
  carrying the props the call site passed; its body does not run. So the
  list rows below assert the CALL — the props and, above all, the `:key`
  — and the row's own contents are asserted by running the row's own
  body."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.examples.todo.db :as db]
            [re-frame.hicasso.examples.todo.events :as events]
            [re-frame.hicasso.examples.todo.routes :as routes]
            [re-frame.hicasso.examples.todo.subs :as subs]
            [re-frame.hicasso.examples.todo.views :as views]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       routes/register!}))

(defn- tagged [tree tag] (ht/find tree #(= tag (:tag %))))

(defn- classed
  "The first node carrying exactly `class` — the same hook the mounted
  suite selects on, so the two tiers talk about the same parts of the
  page."
  [tree class]
  (ht/find tree #(= class (:class (ht/attrs %)))))

;; ---------------------------------------------------------------------------
;; The header — a form, and the whole of "Enter adds a to-do"
;; ---------------------------------------------------------------------------

(deftest the-new-todo-box-commits-through-the-form
  (let [tree  (ht/tree [views/new-todo-box {}] {:subs {[::subs/new-todo] "eg"}})
        form  (tagged tree :form)
        field (tagged tree :input)]
    (is (= [::events/add] (:on-submit (ht/attrs form)))
        "Enter in a text field submits its form, and :on-submit
         AUTO-PREVENTS — so there is no key test and no .preventDefault
         anywhere in this application's header")
    (is (= "eg" (:value (ht/attrs field)))
        "controlled: the text is the subscription's, not the element's")
    (is (= [::events/typed :re-frame.hicasso/value] (:on-input (ht/attrs field)))
        "and the write is an intent vector with the marker at its TOP
         LEVEL — the only position the materializer substitutes at")))

;; ---------------------------------------------------------------------------
;; One row, not editing
;; ---------------------------------------------------------------------------

(def ^:private row-props {:id 7 :title "milk" :done? false})

(defn- row-tree [props draft]
  (ht/tree [views/todo-row props] {:subs {[db/draft (:id props)] draft}}))

(deftest a-row-carries-its-three-intents-and-no-editor
  (let [tree (row-tree row-props nil)]
    (is (= "milk" (ht/text (classed tree "todo-title"))))
    (is (= [::events/toggle 7 :re-frame.hicasso/checked]
           (:on-change (ht/attrs (classed tree "toggle"))))
        "::h/checked hands the handler what the box IS, so the model and
         the checkbox cannot drift apart through a negation")
    (is (= [::events/destroy 7] (:on-click (ht/attrs (classed tree "destroy")))))
    (is (= [db/draft 7 "milk"] (:on-double-click (ht/attrs (classed tree "todo-title"))))
        "opening the editor and filling it are ONE write — h/reg-state's
         setter, with the row's own id as the instance key")
    (is (nil? (classed tree "edit"))
        "and with no draft there is no field in the tree at all")))

(deftest a-done-row-says-so-in-its-class
  ;; `.todo-row` is the tag's own sugar and the computed string is the
  ;; `:class` prop; L2 folds the two, in that order, because the author
  ;; cannot see the sugar otherwise. Everything after the first name is
  ;; this body's.
  (is (= "todo-row" (:class (ht/attrs (row-tree row-props nil)))))
  (is (= "todo-row completed"
         (:class (ht/attrs (row-tree (assoc row-props :done? true) nil)))))
  (is (= "todo-row completed editing"
         (:class (ht/attrs (row-tree (assoc row-props :done? true) "milk"))))
      "a completed to-do can still be edited"))

;; ---------------------------------------------------------------------------
;; One row, editing — the key-map, and the ref
;; ---------------------------------------------------------------------------

(deftest the-editor-is-a-key-map-and-a-stable-ref
  (let [tree  (row-tree row-props "sourdough")
        field (classed tree "edit")
        attrs (ht/attrs field)]
    (is (some? field) "a draft, so the editor is on the page")
    (is (= "sourdough" (:value attrs)))
    (is (= [db/draft 7 :re-frame.hicasso/value] (:on-input attrs))
        "every keystroke writes h/reg-state's own slot; there is no
         bespoke typing event in this application")

    (testing "Enter and Escape are DATA — the map shape at an :on-* prop"
      (is (= {"Enter"  [::events/commit-edit 7]
              "Escape" [:re-frame.hicasso/clear db/draft 7]}
             (:on-key-down attrs))
          "one .key lookup per event, composition-gated centrally so an
           IME's Enter commits nothing — and no callback anywhere"))

    (testing "blur commits too, and is safe after Escape"
      (is (= [::events/commit-edit 7] (:on-blur attrs))
          "the same intent as Enter: the handler reads the draft from
           app-db, so a blur that lands after Escape finds nothing"))

    (testing "the ref is opaque to this tier, and that is the honest answer"
      (is (= {:rf.ui/opaque :fn} (:ref attrs))
          "L2 records that a function site EXISTS and cannot say what it
           does; that it is the same function on every render is a fact
           about identity, which only a mounted tier can observe"))))

(deftest the-rows-intent-inventory-includes-both-key-branches
  ;; `ht/intents` unpacks a key-map into its branches, which is the one
  ;; place the tier knows the shape is not an ordinary vector.
  (let [offered (set (ht/intents (row-tree row-props "sourdough")))]
    (is (contains? offered [::events/commit-edit 7]))
    (is (contains? offered [:re-frame.hicasso/clear db/draft 7]))
    (is (contains? offered [::events/destroy 7]))))

;; ---------------------------------------------------------------------------
;; The list — the keyed identity claim, at the tier where the key is data
;; ---------------------------------------------------------------------------

(defn- list-tree [visible all-done?]
  (ht/tree [views/todo-list {}]
           {:subs {[::subs/visible]   visible
                   [::subs/all-done?] all-done?}}))

(def ^:private three
  [{:id 1 :title "milk" :done? false}
   {:id 2 :title "bread" :done? true}
   {:id 3 :title "jam" :done? false}])

(deftest every-row-is-keyed-by-its-own-id
  (let [kids (:children (tagged (list-tree three false) :ul))]
    (is (= [1 2 3] (mapv :key kids))
        "the domain id, never the position")
    (is (= [1 2 3] (mapv (comp :id :props) kids))
        "and each row is a CALL carrying its props — L2 records the call
         and does not run the child's body")))

(deftest the-key-survives-a-filter-flip
  ;; The structural half of the bead's keyed-identity acceptance: under
  ;; :active the middle row is gone and the two survivors keep the keys
  ;; they had. React reconciles by key, so the same keys mean the same
  ;; DOM nodes — which the mounted suite then asserts by IDENTITY.
  (let [all    (:children (tagged (list-tree three false) :ul))
        active (:children (tagged (list-tree [(nth three 0) (nth three 2)] false) :ul))]
    (is (= [1 2 3] (mapv :key all)))
    (is (= [1 3] (mapv :key active))
        "the surviving rows keep their keys across the flip; an
         index-keyed list would renumber them to [0 1] and hand row 3 the
         node, the caret and the open editor belonging to row 2")))

(deftest toggle-all-reads-and-writes-the-same-fact
  (let [tree (list-tree three true)
        box  (ht/attrs (classed tree "toggle-all"))]
    (is (true? (:checked box)))
    (is (= [::events/toggle-all :re-frame.hicasso/checked] (:on-change box)))))

;; ---------------------------------------------------------------------------
;; The footer — three routed tabs, a count, and conditional chrome
;; ---------------------------------------------------------------------------

(defn- footer-tree [left done showing]
  (ht/tree [views/footer {}]
           {:subs {[::subs/active-count]    left
                   [::subs/completed-count] done
                   [::subs/showing]         showing}}))

(deftest the-tabs-are-links-routing-built-and-this-file-names-no-url
  (let [tree (footer-tree 2 1 :active)
        as   (ht/find-all tree #(= :a (:tag %)))]
    (is (= ["/hicasso-todo" "/hicasso-todo/active" "/hicasso-todo/completed"]
           (mapv (comp :href ht/attrs) as))
        "the href is the routing artefact's own synthesis — `views` names
         no URL anywhere, and every path is under this application's own
         prefix because route paths are a PROCESS-GLOBAL registry")
    (is (= ["All" "Active" "Completed"] (mapv ht/text as)))
    (is (= [nil "selected" nil] (mapv (comp :class ht/attrs) as))
        "the highlighted tab is derived from the URL, so it cannot
         disagree with the address bar")
    (is (every? #(= :re-frame.hicasso/navigate (first (:on-click (ht/attrs %)))) as)
        "the click decision is DATA — a namespaced-keyword-headed vector
         `=` can see, which is what makes two renders of one link equal")))

(deftest the-count-is-pluralised-and-the-clear-button-is-conditional
  (testing "one left"
    (let [tree (footer-tree 1 0 :all)]
      (is (= "1 item left" (ht/text (classed tree "todo-count"))))
      (is (nil? (classed tree "clear-completed"))
          "nothing completed, so there is nothing to clear and no button
           to explain")))

  (testing "none left, two done"
    (let [tree (footer-tree 0 2 :all)]
      (is (= "0 items left" (ht/text (classed tree "todo-count"))))
      (is (= "Clear completed (2)" (ht/text (classed tree "clear-completed"))))
      (is (= [::events/clear-completed]
             (:on-click (ht/attrs (classed tree "clear-completed"))))))))

;; ---------------------------------------------------------------------------
;; The page — the conditional chrome
;; ---------------------------------------------------------------------------

(deftest an-empty-page-is-the-header-and-nothing-else
  (let [tree (ht/tree [views/app {}] {:subs {[::subs/any?] false}})
        kids (:children tree)]
    (is (= ["re-frame.hicasso.examples.todo.views/new-todo-box"]
           (into [] (comp (filter :view-id) (map :view-id)) kids))
        "no list and no footer: the whole of the Todo class's conditional
         chrome, decided by one boolean read rather than by a count")))

(deftest a-populated-page-carries-the-list-and-the-footer
  (let [tree  (ht/tree [views/app {}] {:subs {[::subs/any?] true}})
        views (into [] (comp (filter :view-id) (map :view-id))
                    (tree-seq map? :children tree))]
    (is (= ["re-frame.hicasso.examples.todo.views/new-todo-box"
            "re-frame.hicasso.examples.todo.views/todo-list"
            "re-frame.hicasso.examples.todo.views/footer"]
           views)
        "the two conditional boundaries sit inside a fragment, which is
         React's own Fragment there and carries no element of its own")))
