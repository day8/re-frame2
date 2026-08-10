(ns re-frame.hicasso.test-kit-runtime-parity-cljs-test
  "THE PARITY TABLE — one corpus, driven through the RUNTIME and through
  the kit's L2 walk, so the two cannot disagree quietly (rf2-hic-020).

  `re-frame.hicasso.test` publishes a semantic tree and claims two things
  about it: that its child discrimination is *Hicasso's*, and that its
  node schema is *Spec 004B version 1*. A merged-PR audit found nine
  places where neither held — a keyword child the runtime renders as text
  and the kit refused, a `true` child the runtime makes a loud error and
  the kit dropped, a `:<>` the runtime makes a fragment and the kit made
  an element — and the kit's own 111-test suite missed every one, because
  it asked the kit what the kit does.

  **A suite that only asks one side cannot find a disagreement.** So this
  file states one table and drives BOTH sides of it:

      :form     the hiccup value under test
      :runtime  what `codec/as-element` — the runtime's own door — MAKES
                of it, as a comparable token
      :tree     the Spec 004B tree `ht/render` must answer for it, or
      :refuses  the refusal identity it must raise instead
      :why      the clause that says so

  Each row is one claim about one value, and the two columns are the two
  witnesses. A future change that teaches the runtime a new child kind
  and forgets the kit reds the `:tree` column against a `:runtime` column
  that moved — which is the failure the audit had to find by hand.

  **Refusals are asserted as identity, never as `thrown?`.** A bare
  `(is (thrown? …))` is green for a throw from any layer carrying any id,
  and two rows here are specifically about WHICH refusal is raised. The
  rows compare the ex-data map."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

(def ^:private frame-id ::parity)

;; The node lane's standard seating, as `test-kit-cljs-test` establishes
;; it: the UIx adapter because plain-atom never notifies, no ambient
;; frame because this suite seats its own, the collector emptied between
;; rows.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The two probes
;; ---------------------------------------------------------------------------

(defn- outcome
  "`{:returned v}` or `{:refused <ex-data>}` — which of the two things
  happened, distinguishably. A map rather than a predicate, because the
  two failure modes a refusal witness has (the thunk never ran; something
  else threw) both look like success to a bare `thrown?`."
  [thunk]
  (try {:returned (thunk)}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

(defn- runtime-kind
  "What the RUNTIME makes of one hiccup value, as a comparable token.

  `codec/as-element` is the runtime's own child door — the function every
  child on every page goes through — driven inside a boundary's ambient
  extent exactly as a body's children are. The token names the
  discrimination and not the React element, because the discrimination is
  what the kit's walk must agree with:

      [:nothing]      renders nothing
      [:text s]       is text, with the string the runtime produced
      [:element tag]  is a native element
      [:fragment]     is a fragment
      [:opaque]       is an element only React can interpret
      [:refused id]   is a loud error, with its id"
  [x]
  (let [o (outcome #(intent/with-frame frame-id (collector/frame-dispatch frame-id)
                      (fn [] (codec/as-element x))))]
    (if-some [d (:refused o)]
      [:refused (:rf.error/id d)]
      (let [v (:returned o)]
        (cond
          (nil? v)    [:nothing]
          (string? v) [:text v]
          (number? v) [:text (str v)]
          (react/isValidElement v)
          (let [t (.-type v)]
            (cond
              (identical? t (.-Fragment react)) [:fragment]
              (string? t)                       [:element t]
              :else                             [:opaque]))
          :else [:foreign (pr-str v)])))))

(defn- kit-outcome
  "What the KIT makes of the same value: the 004B tree `ht/render`
  answers for a body that returns it, or the identity of the refusal it
  raised instead.

  The body is `(fn [_] form)` — the ordinary L2 spelling — so the value
  travels the kit's real path rather than a walk reached behind its
  door."
  [form]
  (let [o (outcome #(ht/render [(fn [_] form)]))]
    (if-some [d (:refused o)]
      {:refused (select-keys d [:rf.error/id :where :recovery])}
      {:tree (:returned o)})))

;; ---------------------------------------------------------------------------
;; The table
;; ---------------------------------------------------------------------------

(h/defhost a-host js/Object {:ssr :client-only})

(defn- raw-component [] nil)

(def ^:private v ht/tree-version)

(def ^:private rows
  [{:case    "a body that renders nothing roots in an EMPTY FRAGMENT"
    :form    nil
    :runtime [:nothing]
    :tree    {:rf.ui/tree-version v :children []}
    :why     (str "004B §Versioning — a form that denotes nothing roots in a "
                  "fragment; §Canonical uniqueness — an empty fragment is "
                  "{:children []}, never {} (which is malformed).")}

   {:case    "`:<>` is a FRAGMENT node, not an element named `:<>`"
    :form    [:<> [:span "a"] [:i "b"]]
    :runtime [:fragment]
    :tree    {:rf.ui/tree-version v
              :children [{:tag :span :children ["a"]}
                         {:tag :i :children ["b"]}]}
    :why     "codec/fragment-head? — `:<>` is React's Fragment, not a tag."}

   {:case    "adjacent text is COALESCED into one string"
    :form    [:div "a" "b" 1 "" "c"]
    :runtime [:element "div"]
    :tree    {:rf.ui/tree-version v :tag :div :children ["ab1c"]}
    :why     (str "004B §Children — numeric children stringify, adjacent text "
                  "runs coalesce, empty strings drop AFTER coalescing.")}

   {:case    "a KEYWORD child is text, because the runtime renders it as text"
    :form    [:div :done]
    :runtime [:text "done"]
    :tree    {:rf.ui/tree-version v :tag :div :children ["done"]}
    :why     "codec/as-element — `(keyword? x) (name x)`."}

   {:case    "a SYMBOL child is text, for the same reason"
    :form    [:div 'done]
    :runtime [:text "done"]
    :tree    {:rf.ui/tree-version v :tag :div :children ["done"]}
    :why     "codec/as-element — `(symbol? x) (name x)`."}

   {:case    "a `true` child RAISES, and raises the runtime's own id"
    :form    [:div true]
    :runtime [:refused :rf.error/hicasso-true-child]
    :refuses {:rf.error/id :rf.error/hicasso-true-child}
    :why     (str "codec/as-element — nil and false render nothing; true is an "
                  "error (HD-016). Dropping it silently teaches a spelling the "
                  "runtime rejects.")}

   {:case    "a `false` child renders nothing — the true child's legal twin"
    :form    [:div false "x"]
    :runtime [:element "div"]
    :tree    {:rf.ui/tree-version v :tag :div :children ["x"]}
    :why     "codec/as-element — `(false? x) nil`."}

   {:case    "`[:> …]` is the raw-React escape, and refuses to L3"
    :form    [:div [:> raw-component]]
    :runtime [:opaque]
    :refuses {:rf.error/id :rf.error/hicasso-test-react-is-opaque}
    :why     (str "codec/raw-head? — `[:> C]` hands C to React untouched "
                  "(HD-011). The namespace docstring promises anything the "
                  "codec hands to React untouched refuses with a pointer to "
                  "L3; a generic malformed says the author wrote nonsense.")}

   {:case    "a `defhost` crossing refuses to L3 — the escape's neighbour"
    :form    [:div [a-host]]
    :runtime [:opaque]
    :refuses {:rf.error/id :rf.error/hicasso-test-host-is-opaque}
    :why     "The kit's stated opacity, and the row that keeps it distinct."}

   {:case    "an SVG subtree carries `:ns`, and `:foreignObject` reverts"
    :form    [:svg {:view-box "0 0 1 1"}
              [:path {:d "M0"}]
              [:foreignObject [:div "x"]]]
    :runtime [:element "svg"]
    :tree    {:rf.ui/tree-version v :tag :svg :ns :svg
              :attrs {:view-box "0 0 1 1"}
              :children [{:tag :path :ns :svg :attrs {:d "M0"}}
                         {:tag :foreignObject :ns :svg
                          :children [{:tag :div :children ["x"]}]}]}
    :why     (str "004B §Namespaces — an `:svg` element enters `:svg` and "
                  "descendants inherit; `:foreignObject`'s CHILDREN revert to "
                  "HTML; `:ns` is absent for HTML.")}

   {:case    "an HTML element omits `:ns` — the SVG row's control"
    :form    [:div [:span "x"]]
    :runtime [:element "div"]
    :tree    {:rf.ui/tree-version v :tag :div
              :children [{:tag :span :children ["x"]}]}
    :why     "004B §Element fields — `:ns` MUST be absent for HTML."}

   {:case    "a function in a map KEY may not be recorded"
    :form    [:div {:data-x {(fn [] 1) :v}}]
    :runtime [:element "div"]
    :refuses {:rf.error/id :rf.error/ui-tree-malformed}
    :why     (str "004B §The opaque marker — the marker occupies a SITE, never "
                  "a value inside one; a non-data value nested inside a "
                  "recorded value is rejected. A key is inside the value.")}

   {:case    "a JS host object may not be recorded"
    :form    [:div {:data-x #js {"a" 1}}]
    :runtime [:element "div"]
    :refuses {:rf.error/id :rf.error/ui-tree-malformed}
    :why     (str "004B §The node schema — the tree is plain, serialisable "
                  "Clojure data that EDN print/read round-trips; §Attr value "
                  "normalization — a host object is rejected.")}

   {:case    "ordinary nested EDN is untouched — the two rows above's control"
    :form    [:div {:data-x {:a [1 #{:b}] "k" 'sym}}]
    :runtime [:element "div"]
    :tree    {:rf.ui/tree-version v :tag :div
              :attrs {:data-x {:a [1 #{:b}] "k" 'sym}}}
    :why     "004B §The opaque marker — the check is read-only; EDN passes."}])

;; ---------------------------------------------------------------------------
;; The two witnesses
;; ---------------------------------------------------------------------------

(deftest the-runtime-answers-what-the-table-says
  (testing "every row's :runtime column is what codec/as-element really does"
    (doseq [{:keys [case form runtime why]} rows]
      (is (= runtime (runtime-kind form))
          (str case "\n  RUNTIME column — " why)))))

(deftest the-kit-answers-what-the-table-says
  (testing "every row's :tree / :refuses column is what ht/render really does"
    (doseq [{:keys [case form tree refuses why]} rows]
      (let [got (kit-outcome form)]
        (if refuses
          (is (= refuses (select-keys (:refused got) (keys refuses)))
              (str case "\n  REFUSAL identity — " why
                   "\n  got: " (pr-str got)))
          (is (= tree (:tree got))
              (str case "\n  TREE column — " why
                   "\n  got: " (pr-str got))))))))

(deftest the-table-covers-both-verbs
  (testing "the corpus holds rows that refuse and rows that answer a tree,
            so neither witness above is a helper that only knows one verb"
    (is (seq (filter :refuses rows)))
    (is (seq (filter :tree rows)))
    (is (every? (fn [r] (or (:refuses r) (contains? r :tree))) rows))))
