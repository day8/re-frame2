(ns re-frame.ui.tree-jvm-test
  "JVM emitter goldens: compiled views return the versioned public
  structural tree (node schema v1) in CANONICAL form — the Tier-1
  rendering surface `ui.test` (S1d) consumes. Full-tree equality goldens
  plus the canonical-form laws, the events-as-data law, duplicate-key
  diagnosis, host-op errors, and the Q2/Q4/Q5 pins as they read on this
  host."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.registrar :as registrar]
            [re-frame.ui :as ui :refer [defview sub]]
            [re-frame.ui.tree :as tree]))

(defn- boom! [] (throw (ex-info "must never evaluate" {})))

(def ForeignThing ::not-a-view) ; resolvable var without view meta -> foreign

(ui/custom-element :tree-fancy-el {:properties #{:help-text}})

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defview row
  [{:keys [t]}]
  [:li.row {:data-priority (:p t)
            :on-click [:row/open :rf.ui/key]
            :on-hover {:event [:row/hover (:id t)] :prevent-default true}}
   (:label t)])

(defview rows
  [{:keys [ts]}]
  [:ul (for [t ts] [row {:key (:id t) :t t}])])

(defview dup-keys [{:keys [ks]}]
  [:ul (for [k ks] [:li {:key k} "x"])])

(defview fragment-rooted []
  [:<> [:i "a"] [:i "b"]])

(defview nil-rooted [{:keys [show?]}]
  (when show? [:p "here"]))

(defview wraps-fragment []
  [:div [fragment-rooted {}]])

(defview accepts-children [{:keys [title children]}]
  [:section [:h3 title] [:div.body children]])

(defview uses-children []
  [accepts-children {:title "T"} [:p "one"] [:p "two"]])

(defview raw-child [{:keys [go?]}]
  [:div (when go? (ui/raw (boom!)))])

(defview raw-prop []
  [accepts-children {:title "T" :el (ui/raw (boom!)) :cb (ui/raw-fn boom!)}])

(defview foreign-user [{:keys [go?]}]
  [:div (when go? [ForeignThing {:x 1}])])

(defview fn-handler []
  [:button {:on-click (fn [_] :never)} "x"])

(defview dyn-handler [{:keys [h]}]
  [:button {:on-click h} "x"])

(defview subby [{:keys [read?]}]
  [:div (when read? [:span (sub [:the/query])])])

(declare ^:rf.ui/view pong)

(defview ping [{:keys [n]}]
  (if (pos? n) [pong {:n (dec n)}] [:span "ping-base"]))

(defview pong [{:keys [n]}]
  (if (pos? n) [ping {:n (dec n)}] [:span "pong-base"]))

(defview defaults-view [{:keys [a b] :or {a "A" b "B"}}]
  [:div [:span a] [:span (str b)]])

(defview as-view [{:keys [x] :as all}]
  [:div (str x "/" (count all))])

(defview custom-el-user []
  [:tree-fancy-el {:help-text "h" :data-x "d"}])

(defview spread-el [{:keys [m]}]
  [:div.card (ui/spread {:tab-index 3 :class "base"} m)])

(defview trusted []
  [:div.content (ui/html "<b>raw & bold</b>")])

(defview mathy []
  [:math [:annotation-xml {:encoding "text/html"} [:div "island"]]])

(defview texts []
  [:p "n=" 2 " of " 10.0])

;; ---------------------------------------------------------------------------
;; Goldens + canonical form
;; ---------------------------------------------------------------------------

(deftest full-tree-golden-with-events-as-data
  (is (= {:view-id ::rows
          :props {:ts [{:id 1 :label "a" :p :hi}]}
          :children
          [{:tag :ul
            :children
            [{:view-id ::row
              :props {:t {:id 1 :label "a" :p :hi}}
              :key 1
              :children
              [{:tag :li
                :attrs {:class "row" :data-priority "hi"}
                :events {:on-click [:row/open :rf.ui/key]
                         :on-hover {:event [:row/hover 1] :prevent-default true}}
                :children ["a"]}]}]}]
          :rf.ui/tree-version 1}
         (tree/render rows {:ts [{:id 1 :label "a" :p :hi}]}))
      "event vectors + options maps retained verbatim as data; placeholder
       keywords stay keywords; boundaries nest; keys ride the boundary"))

(deftest canonical-form-laws
  (let [t (tree/render nil-rooted {:show? false})]
    (is (= {:view-id ::nil-rooted :props {:show? false} :rf.ui/tree-version 1} t)
        "absent-when-empty: no :children key, no :attrs key"))
  (let [t (tree/render texts {})]
    (is (= ["n=2 of 10"] (:children (first (:children t))))
        "numeric children via JS ToString; adjacent text runs coalesce")))

(deftest fragment-and-nil-rooted-views
  (is (= {:view-id ::fragment-rooted
          :children [{:tag :i :children ["a"]} {:tag :i :children ["b"]}]
          :rf.ui/tree-version 1}
         (tree/render fragment-rooted {}))
      "a fragment-rooted view is a boundary with several children (Q12)")
  (is (= [{:view-id ::fragment-rooted
           :children [{:tag :i :children ["a"]} {:tag :i :children ["b"]}]}]
         (get-in (tree/render wraps-fragment {})
                 [:children 0 :children]))
      "nested boundaries survive"))

(deftest q4-children-forwarding
  (let [t (tree/render uses-children {})
        boundary (get-in t [:children 0])]
    (is (= ::accepts-children (:view-id boundary)))
    (is (= {:title "T"} (:props boundary))
        "boundary :props excludes :children — children are structural")
    (is (= [{:tag :p :children ["one"]} {:tag :p :children ["two"]}]
           (get-in boundary [:children 0 :children 1 :children]))
        "forwarded children splice into the child view's placement")))

(deftest q2-defaults-and-as-on-jvm
  (is (= [{:tag :span :children ["A"]} {:tag :span :children ["B"]}]
         (:children (first (:children (tree/render defaults-view {})))))
      ":or defaults apply when the key is ABSENT")
  (is (= [{:tag :span :children ["x"]} {:tag :span}]
         (:children (first (:children (tree/render defaults-view {:a "x" :b nil})))))
      "a present-nil slot stays nil (Clojure map-destructuring semantics)")
  (is (= ["1/2"]
         (:children (first (:children (tree/render as-view {:x 1 :other 2})))))
      ":as binds the full props map"))

(deftest duplicate-keys-diagnosed-with-string-coercion
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"ui-duplicate-key"
       (tree/render dup-keys {:ks [1 2 1]})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"ui-duplicate-key"
       (tree/render dup-keys {:ks [1 "1"]}))
      "[S1-CONFIRM] row 11: key 1 collides with key \"1\" after coercion")
  (is (some? (tree/render dup-keys {:ks [1 "a" :a]}))
      "distinct keys after coercion pass (keywords carry their colon)"))

(deftest host-ops-raise-typed-errors-lazily
  (is (some? (tree/render raw-child {:go? false}))
      "untaken ui/raw branches never evaluate")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"jvm-host-op"
                        (tree/render raw-child {:go? true})))
  (is (some? (tree/render foreign-user {:go? false})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"jvm-host-op"
                        (tree/render foreign-user {:go? true}))
      "foreign components never appear in the JVM tree"))

(deftest opaque-markers-in-boundary-props
  (let [t (tree/render raw-prop {})
        props (:props (first (:children t)))]
    (is (= {:rf.ui/opaque :foreign} (:el props))
        "(ui/raw x) prop -> opaque :foreign marker, x NOT evaluated")
    (is (= {:rf.ui/opaque :ui/raw-fn} (:cb props))
        "(ui/raw-fn f) prop -> opaque :ui/raw-fn marker")))

(deftest fn-and-dynamic-handlers-classify
  (is (= {:on-click {:rf.ui/opaque :fn}}
         (get-in (tree/render fn-handler {}) [:children 0 :events]))
      "fn-carried sites are the opaque marker — existence testable, behaviour Tier-3")
  (testing "runtime classification of dynamic handler values"
    (is (= {:on-click [:a/b 1]}
           (get-in (tree/render dyn-handler {:h [:a/b 1]}) [:children 0 :events])))
    (is (= {:on-click {:rf.ui/opaque :fn}}
           (get-in (tree/render dyn-handler {:h (fn [_] nil)}) [:children 0 :events])))
    (is (nil? (get-in (tree/render dyn-handler {:h nil}) [:children 0 :events]))
        "nil -> the entry is dropped")))

(deftest sub-grammar-compiles-untaken-branch-renders
  (is (= {:view-id ::subby :props {:read? false} :rf.ui/tree-version 1
          :children [{:tag :div}]}
         (tree/render subby {:read? false}))
      "sub GRAMMAR compiles at S1; an untaken branch renders with no read")
  ;; S2b: reads LANDED — the staging :rf.error/ui-sub-unavailable is retired.
  ;; tree/render is frameless, so a taken read resolves the ambient frame
  ;; and raises honestly (a real frame-scoped read is covered in
  ;; test-render-jvm-test via ui.test/render + a registered sub).
  (is (= :rf.error/no-frame-context
         (try (tree/render subby {:read? true})
              nil
              (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
      "a live sub read with no ambient frame raises :rf.error/no-frame-context"))

(deftest q5-mutual-recursion-via-declare-hint
  (is (= "pong-base"
         (loop [n (tree/render ping {:n 3})]
           (let [child (first (:children n))]
             (if (:view-id child) (recur child) (first (:children child))))))
      "(declare ^:rf.ui/view pong) makes mutual view recursion expressible"))

(deftest custom-element-and-spread-on-jvm
  (let [el (first (:children (tree/render custom-el-user {})))]
    (is (= {:tag :tree-fancy-el
            :attrs {:help-text "h" :data-x "d"}
            :rf.ui/property-props #{:help-text}}
           el)
        "property-classified props stay in :attrs, named by the reserved key"))
  (let [el (first (:children (tree/render spread-el {:m {:class "x" :on-click [:a/b]}})))]
    (is (= "card x" (get-in el [:attrs :class])) "sugar-first + spread merge")
    (is (= "3" (get-in el [:attrs :tab-index])) "author-space names in the tree")
    (is (= {:on-click [:a/b]} (:events el)) "spread on-* routes to :events")))

(deftest trusted-html-node
  (is (= {:tag :div :attrs {:class "content"}
          :children [{:html "<b>raw & bold</b>"}]}
         (first (:children (tree/render trusted {}))))
      "the trusted-HTML node variant — the single escaping bypass"))

(deftest namespace-context-rows-on-jvm
  (let [math (first (:children (tree/render mathy {})))]
    (is (= :mathml (:ns math)))
    (is (= :mathml (get-in math [:children 0 :ns])) "annotation-xml itself is MathML")
    (is (nil? (get-in math [:children 0 :children 0 :ns]))
        "text/html annotation-xml children revert to HTML — [S1-CONFIRM] row 2")))

(deftest tree-serialisation-boundary
  (let [t (tree/render rows {:ts [{:id 1 :label "a" :p :hi}]})]
    (is (= t (edn/read-string (pr-str t)))
        "plain serialisable data — EDN print/read round-trips losslessly")))

(deftest registrar-entries-on-jvm
  (let [m (registrar/handler-meta :view ::rows)]
    (is (true? (:rf.ui/compiled? m)))
    (is (= ::rows (get-in m [:rf.ui/manifest :view-id])))
    (is (str/starts-with?
         (get-in m [:rf.ui/manifest :template-fingerprint]) "tf1-"))
    (is (str/starts-with?
         (get-in m [:rf.ui/manifest :hook-signature]) "hs1-"))
    (is (= [:ts] (mapv :key (get-in m [:rf.ui/manifest :prop-slots]))))
    (is (zero? (count (get-in m [:rf.ui/manifest :sites :events])))
        "rows has no event sites of its own — row's belong to row's manifest"))
  (let [m (registrar/handler-meta :view ::row)]
    (is (= [[:row/open :rf.ui/key]
            {:event [:row/hover (list :id 't)] :prevent-default true}]
           (mapv :handler (get-in m [:rf.ui/manifest :sites :events])))
        "event sites carry their handler FORMS as data in the manifest")
    (is (= [true true]
           (mapv :serializable? (get-in m [:rf.ui/manifest :sites :events]))))))
