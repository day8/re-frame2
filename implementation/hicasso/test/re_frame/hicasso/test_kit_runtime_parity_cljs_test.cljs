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
      :subject  the value the `:runtime` column is ABOUT, when that is a
                child of `:form` rather than `:form` itself
      :runtime  what `codec/as-element` — the runtime's own door — MAKES
                of the subject, as a comparable token
      :tree     the Spec 004B tree `ht/render` must answer for `:form`, or
      :refuses  the refusal identity it must raise instead
      :why      the clause that says so

  Each row is one claim about one value, and the two columns are the two
  witnesses. A future change that teaches the runtime a new child kind
  and forgets the kit reds the `:tree` column against a `:runtime` column
  that moved — which is the failure the audit had to find by hand.

  **Refusals are asserted as identity, never as `thrown?`.** A bare
  `(is (thrown? …))` is green for a throw from any layer carrying any id,
  and several rows here are specifically about WHICH refusal is raised.
  The rows compare the ex-data map — id, `:where` and `:recovery`.

  **Where a row's refusal is the RUNTIME's, the table says so in
  `:where`.** A second audit (PR #7796) found the covered corpus stopping
  one layer short: it held a VALID raw escape, for which opacity is the
  honest answer, and no malformed one — so the claim that the two sides
  cannot disagree quietly was not load-bearing on that arm, and in fact
  they did. An empty vector and a malformed `[:> …]` are refused by the
  runtime, and the kit now raises those refusals rather than its own by
  running the runtime's guards (`codec/vector-kind`). Asserting `:where`
  is what makes that structural rather than coincidental: a kit
  paraphrase would carry `re-frame.hicasso.test` and red."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
      [:refused id r] is a loud error, with its id and its recovery

  The refusal token carries the RECOVERY as well as the id, because the
  advice is half of what a refusal is: the audit that reopened this bead
  found the kit answering a malformed raw escape with a pointer to L3,
  which is not merely a different id but the wrong instruction. A column
  that named only the id would have called that a near miss."
  [x]
  (let [o (outcome #(intent/with-frame frame-id (collector/frame-dispatch frame-id)
                      (fn [] (codec/as-element x))))]
    (if-some [d (:refused o)]
      [:refused (:rf.error/id d) (:recovery d)]
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
    :subject :done
    :runtime [:text "done"]
    :tree    {:rf.ui/tree-version v :tag :div :children ["done"]}
    :why     "codec/as-element — `(keyword? x) (name x)`."}

   {:case    "a SYMBOL child is text, for the same reason"
    :form    [:div 'done]
    :subject 'done
    :runtime [:text "done"]
    :tree    {:rf.ui/tree-version v :tag :div :children ["done"]}
    :why     "codec/as-element — `(symbol? x) (name x)`."}

   {:case    "a `true` child RAISES, and raises the runtime's own id"
    :form    [:div true]
    :runtime [:refused :rf.error/hicasso-true-child :use-nil-or-false]
    :refuses {:rf.error/id :rf.error/hicasso-true-child
              :recovery    :use-nil-or-false}
    :why     (str "codec/as-element — nil and false render nothing; true is an "
                  "error (HD-016). Dropping it silently teaches a spelling the "
                  "runtime rejects. 004B §Child normalization drops a `true` "
                  "that reaches TREE BUILD; Hicasso's grammar refuses it "
                  "upstream of that, so one never arrives — see the namespace "
                  "docstring's §Scope of the 004B claim.")}

   {:case    "a `false` child renders nothing — the true child's legal twin"
    :form    [:div false "x"]
    :runtime [:element "div"]
    :tree    {:rf.ui/tree-version v :tag :div :children ["x"]}
    :why     "codec/as-element — `(false? x) nil`."}

   {:case    "a VALID `[:> …]` is the raw-React escape, and refuses to L3"
    :form    [:div [:> raw-component]]
    :subject [:> raw-component]
    :runtime [:opaque]
    :refuses {:rf.error/id :rf.error/hicasso-test-react-is-opaque
              :recovery    :assert-it-at-l3}
    :why     (str "codec/raw-head? — `[:> C]` hands C to React untouched "
                  "(HD-011). The namespace docstring promises anything the "
                  "codec hands to React untouched refuses with a pointer to "
                  "L3; a generic malformed says the author wrote nonsense. "
                  "This row is the CONTROL for the three below: opacity is "
                  "the answer only when the escape is well formed.")}

   ;; ---- The seam's own rows (rf2-hic-020, audit of PR #7796) --------------
   ;;
   ;; `:opaque` is a claim about a form the kit CANNOT read, and it is only
   ;; honest where the runtime can. For a MALFORMED escape the runtime cannot
   ;; either — it refuses — so a kit that answered `:assert-it-at-l3` sent the
   ;; programmer to write a browser test for a hiccup vector that will never
   ;; render anywhere. Same for an empty vector, which the runtime refuses
   ;; before it classifies a head at all. Both are the RUNTIME's refusal,
   ;; raised by the runtime's own guards through `codec/vector-kind`, so these
   ;; rows assert `:where` as well: a kit paraphrase would name
   ;; `re-frame.hicasso.test` and red here.

   {:case    "an EMPTY VECTOR raises the runtime's own empty-vector refusal"
    :form    [:div []]
    :subject []
    :runtime [:refused :rf.error/hicasso-empty-vector :supply-a-hiccup-head]
    :refuses {:rf.error/id :rf.error/hicasso-empty-vector
              :where       'front.codec/vec->element
              :recovery    :supply-a-hiccup-head}
    :why     (str "codec/vec->element refuses an empty vector AHEAD of any "
                  "head classification, because every branch below reads "
                  "position 0. A kit that asks `head-kind` about the nil it "
                  "found there answers `:invalid` and reports a generic "
                  "malformed head — a second identity for one fault.")}

   {:case    "`[:>]` with NO component raises the runtime's own refusal"
    :form    [:div [:>]]
    :subject [:>]
    :runtime [:refused :rf.error/hicasso-raw-no-component
              :hand-the-escape-a-real-component]
    :refuses {:rf.error/id :rf.error/hicasso-raw-no-component
              :where       'front.codec/raw-element
              :recovery    :hand-the-escape-a-real-component}
    :why     (str "codec/raw-component — the escape's Component slot is empty. "
                  "Opacity is not the answer: there is nothing for React to "
                  "interpret, so `:assert-it-at-l3` sends the programmer to "
                  "mount a form that cannot mount.")}

   {:case    "`[:> nil]` — the broken-import spelling — raises the same id"
    :form    [:div [:> nil]]
    :subject [:> nil]
    :runtime [:refused :rf.error/hicasso-raw-no-component
              :hand-the-escape-a-real-component]
    :refuses {:rf.error/id :rf.error/hicasso-raw-no-component
              :where       'front.codec/raw-element
              :recovery    :hand-the-escape-a-real-component}
    :why     (str "codec/raw-component — a `:default` import that resolved "
                  "nothing is the usual cause, and it is the case a test kit "
                  "is most likely to meet first. Distinct SPELLING from `[:>]` "
                  "(the ex-data's `:argv-count` differs), same fault.")}

   {:case    "`[:> :div]` — a keyword in the Component slot — raises the runtime's own refusal"
    :form    [:div [:> :div]]
    :subject [:> :div]
    :runtime [:refused :rf.error/hicasso-raw-not-a-component
              :hand-the-escape-a-component-react-accepts]
    :refuses {:rf.error/id :rf.error/hicasso-raw-not-a-component
              :where       'front.codec/raw-element
              :recovery    :hand-the-escape-a-component-react-accepts}
    :why     (str "codec/raw-component — the GRAMMAR owns tags, so a keyword "
                  "is one of the escape's three deliberate narrowings. The "
                  "second refusal id on this arm, which is why the arm needs "
                  "two rows and not one.")}

   {:case    "a `defhost` crossing refuses to L3 — the escape's neighbour"
    :form    [:div [a-host]]
    :subject [a-host]
    :runtime [:opaque]
    :refuses {:rf.error/id :rf.error/hicasso-test-host-is-opaque
              :where       're-frame.hicasso.test
              :recovery    :assert-it-at-l3}
    :why     (str "The kit's stated opacity, and the row that keeps it "
                  "distinct. `:where` is the KIT here, deliberately: L2's own "
                  "opacity is the kit's claim to make, and the rows above show "
                  "what it looks like when a refusal is the runtime's instead.")}

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
    (doseq [{:keys [case form subject runtime why] :as row} rows]
      (is (= runtime (runtime-kind (if (contains? row :subject) subject form)))
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

(deftest the-table-covers-forms-BOTH-sides-refuse
  (testing "the corpus holds rows where the RUNTIME refuses too, and the kit
            answers with the runtime's own id, recovery and raising site —
            the coverage PR #7796's audit found missing"
    (let [shared (filter (fn [{:keys [runtime refuses]}]
                           (and (= :refused (first runtime))
                                (= (second runtime) (:rf.error/id refuses))
                                (= (nth runtime 2) (:recovery refuses))))
                         rows)]
      (is (seq shared))
      (is (seq (filter #(str/starts-with? (str (:where (:refuses %))) "front.codec/")
                       shared))
          "at least one row's refusal is raised by the runtime's own guard"))))
