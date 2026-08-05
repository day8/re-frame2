(ns re-frame.ssr.diagnostic-cycle-cljs-test
  "THE DIAGNOSTIC PATH ITSELF THREW (rf2-9s68n).

  `re-frame.ssr.emit` and `re-frame.ssr.ui-tree` build their rejection
  messages with `(pr-str el)` / `(pr-str node)` over the offending runtime
  value, and `cljs.core`'s printer descends into a plain JS object
  (`#js {…}`, over `js-keys`) and a JS array (`#js […]`, over elements)
  with no seen-set. A foreign object graph may be CYCLIC — React 19's
  `createContext` returns an object whose `Provider` key points back at the
  context itself — so an author who wrote `[ThemeContext.Provider {…}]`, the
  ordinary mistake `:rf.error/invalid-hiccup-head` exists to catch, got
  `RangeError: Maximum call stack size exceeded` and NO message at all.
  Unlike the sibling (rf2-56iys, the render-tree hash) this is reachable
  from the shipped `re-frame.ssr/render-to-string`.

  ## How these rows OBSERVE a stack overflow

  A `RangeError` raised inside the emitter is an ordinary synchronous JS
  throw — nothing routes it to `reportError`, so no row here depends on the
  runner noticing an exception it never saw. Every row calls the emitter
  through [[outcome]], which returns a MAP — `{:returned …}` or
  `{:threw <name> :error-id … :message … :ex-data-printable? …}` — and
  asserts on that map. A regression therefore fails with `\"RangeError\"`
  in its own failure text rather than aborting the var.

  ## The three things each fixed site has to prove

  1. a CYCLIC input produces the site's OWN error id, not `RangeError`;
  2. the thrown `ex-data` is `pr-str`-able, so the cyclic value cannot
     ride out and explode at a downstream logger / projector / trace sink;
  3. an ACYCLIC input's message is BYTE-IDENTICAL to the one `pr-str`
     produces — asserted by embedding `cljs.core/pr-str`'s own output in
     the expectation, not by eyeballing a literal.

  [[the-fixtures-are-genuinely-cyclic]] is the non-vacuity control. Without
  it every row below could pass on an acyclic fixture and prove nothing."
  (:require ["react" :as react]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.ssr.diagnostic :as diagnostic]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.ui-tree :as ui-tree]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- self-referential-object
  "A plain JS object holding a reference to ITSELF. No React: the defect is
  a property of a foreign object graph, and this is the smallest value that
  has it."
  []
  (let [o #js {"tag" "cyclic"}]
    (unchecked-set o "self" o)
    o))

(defn- self-referential-array
  "The ARRAY half of the same crossing — `pr-str`'s other descending
  branch."
  []
  (let [a #js ["cyclic"]]
    (.push a a)
    a))

(def ^:private corpus-context
  "A real React context, so the rows below carry the shape that was
  reported rather than a model of it."
  (react/createContext "unset"))

(def ^:private provider
  "`ctx.Provider` — what an author writes in head position."
  (.-Provider corpus-context))

(def ^:private acyclic-object
  "The ACYCLIC control. A plain JS object `pr-str` renders in full and
  terminates on: every row that pins byte-identity uses this."
  #js {"theme" "dark" "level" 3})

;; ---------------------------------------------------------------------------
;; Observation
;; ---------------------------------------------------------------------------

(defn- outcome
  "Run `f` and describe what happened as DATA. On a throw the map carries
  the host error NAME (so a stack overflow shows up as `\"RangeError\"` in
  the failure text), the framework error id, the message, and whether the
  ex-data survives `pr-str` — which is the ex-data half of the defect."
  [f]
  (try
    {:returned (f)}
    (catch :default e
      (let [data (ex-data e)]
        {:threw    (.-name e)
         :error-id (:rf.error/id data)
         :message  (ex-message e)
         :ex-data-printable?
         (try (string? (pr-str data)) (catch :default _ false))}))))

(defn- rejected-with
  "Assert `f` threw the framework error `id` — with the whole outcome map in
  the failure text, and with the ex-data proven printable at the same time."
  [id label f]
  (let [o (outcome f)]
    (is (= id (:error-id o))
        (str label " must throw " id "; got " (pr-str o)))
    (is (true? (:ex-data-printable? o))
        (str label "'s ex-data must survive pr-str at a downstream sink; got "
             (pr-str (dissoc o :message))))
    o))

(defn- tree
  "A version-1 structural tree wrapping `children` in a root fragment."
  [& children]
  {:rf.ui/tree-version 1 :children (vec children)})

;; ---------------------------------------------------------------------------
;; The control
;; ---------------------------------------------------------------------------

(deftest the-fixtures-are-genuinely-cyclic
  (testing "THE NON-VACUITY CONTROL. `pr-str` is what every message site
           below calls, and on these values it recurs until the stack
           blows. If any row here ever goes green-by-termination, the
           fixture has stopped being cyclic and the whole file is
           measuring nothing."
    (is (= "RangeError" (:threw (outcome #(pr-str (self-referential-object)))))
        "a hand-built self-referential JS object defeats cljs.core/pr-str")
    (is (= "RangeError" (:threw (outcome #(pr-str (self-referential-array)))))
        "and so does a cycle reached through a JS array")
    (is (= "RangeError" (:threw (outcome #(pr-str provider))))
        "and so does a real React 19 context provider")
    (is (= "RangeError" (:threw (outcome #(pr-str [provider {:value "dark"}]))))
        "and so does an ordinary hiccup vector holding one")
    (is (= "RangeError" (:threw (outcome #(pr-str #js {"held" [:p provider]}))))
        "and so does a MIXED chain — foreign object, persistent vector,
         foreign object — because the printer crosses between the two
         freely, which is why the detector has to as well")
    (is (identical? provider (.-Provider provider))
        "because React 19's ctx.Provider IS the context object, and that
         object carries a Provider key pointing back at itself — the cycle
         in one line")
    (is (string? (pr-str [acyclic-object {:value "dark"}]))
        "while the ACYCLIC control prints fine, which is what makes it a
         control for the byte-identity rows")))

;; ---------------------------------------------------------------------------
;; The helper
;; ---------------------------------------------------------------------------

(deftest safe-form-returns-an-acyclic-input-identically
  (testing "The byte-identity guarantee is BY CONSTRUCTION, not by
           inspection: when no cycle is reachable `safe-form` hands back the
           very object it was given, so `pr-str` of the result cannot differ
           from `pr-str` of the input by any byte."
    (doseq [[label v] [["ordinary hiccup"      [:div {:class "x"} [:p "hi"]]]
                       ["an acyclic JS object" acyclic-object]
                       ["hiccup holding one"   [acyclic-object {:value "dark"}]]
                       ["an acyclic JS array"  #js [1 2 3]]
                       ["a nested acyclic obj" [:div {} [:span {:ctx acyclic-object}]]]
                       ["a string"             "text"]
                       ["nil"                  nil]]]
      (is (identical? v (diagnostic/safe-form v))
          (str label " must come back identical")))
    (is (= (pr-str [acyclic-object {:value "dark"}])
           (diagnostic/pr-form [acyclic-object {:value "dark"}]))
        "so pr-form IS pr-str wherever pr-str terminates — including on a
         foreign object, whose contents stay in the diagnostic")))

(deftest safe-form-elides-only-the-cyclic-foreign-value
  (testing "The elision is the strict minimum: the token replaces the value
           `pr-str` cannot survive and nothing else, so the props and
           children around it go on discriminating the diagnostic."
    (is (= "#js {…cyclic…}" (diagnostic/pr-form (self-referential-object))))
    (is (= "#js […cyclic…]" (diagnostic/pr-form (self-referential-array))))
    (is (= "#js {…cyclic…}" (diagnostic/pr-form provider)))
    (is (= "[#js {…cyclic…} {:value \"dark\"} [:p \"x\"]]"
           (diagnostic/pr-form [provider {:value "dark"} [:p "x"]])))
    (is (= "[:div {:ctx #js {…cyclic…}} \"x\"]"
           (diagnostic/pr-form [:div {:ctx provider} "x"]))
        "a cycle nested in an attrs map is elided in place, leaving the map
         around it intact")
    (is (= "[:div #js […cyclic…]]"
           (diagnostic/pr-form [:div #js [(self-referential-object)]]))
        "a foreign value from which a cycle is REACHABLE is elided whole —
         the array holding the cycle is itself unprintable")))

(deftest a-shared-subtree-is-not-a-cycle
  (testing "The detector is PATH-scoped, not global. A foreign value
           reachable twice by different paths is a DAG, `pr-str` prints it
           twice and terminates, and eliding it would be a diagnostic
           regression dressed up as a fix."
    (let [shared #js {"k" "v"}
          form   [:div {:a shared :b shared}]]
      (is (identical? form (diagnostic/safe-form form)))
      (is (= (pr-str form) (diagnostic/pr-form form)))))
  (testing "and a persistent collection nested INSIDE a foreign value is
           crossed, not treated as the end of the graph — acyclic here, so
           it must come back untouched."
    (let [form [:div #js {"held" [:p "x"]}]]
      (is (identical? form (diagnostic/safe-form form)))
      (is (= (pr-str form) (diagnostic/pr-form form))))))

(deftest a-cycle-through-a-mixed-chain-is-found
  (testing "`pr-str` alternates between foreign values and persistent
           collections as it descends, so the detector must too. A walk that
           stopped at the first persistent collection would report this form
           acyclic and hand `pr-str` the overflow — which is exactly what
           the control row above proves happens."
    (is (= "[:div #js {…cyclic…}]"
           (diagnostic/pr-form [:div #js {"held" [:p provider]}])))
    (is (= "#js {…cyclic…}"
           (diagnostic/pr-form #js {"held" #js [[:p provider]]}))
        "two collections deep and through an array as well")
    (rejected-with :rf.error/invalid-hiccup-head
                   "a mixed-chain cycle in head position"
                   #(emit/emit-element [#js {"held" [:p provider]} {}]))))

;; ---------------------------------------------------------------------------
;; re-frame.ssr.emit — four throw sites
;; ---------------------------------------------------------------------------

(deftest emit-rejects-a-cyclic-hiccup-head-with-its-own-error
  (testing "THE REPORTED DEFECT. A React provider is neither `keyword?` nor
           `ifn?`, so it falls to `reject-invalid-hiccup-head!` — which
           raised RangeError from its own message instead of the error it
           exists to produce. Reverting `diagnostic/safe-form` in that
           function reds every row here with `{:threw \"RangeError\"}`."
    (doseq [[label el] [["a provider in head position"  [provider {:value "dark"}]]
                        ["a hand-built cycle as head"   [(self-referential-object) {}]]
                        ["a cyclic array as head"       [(self-referential-array)]]
                        ["a provider nested in markup"  [:div.hosts [:h1 "hosts"]
                                                         [provider {:value "dark"} "x"]]]]]
      (rejected-with :rf.error/invalid-hiccup-head label
                     #(emit/emit-element el)))))

(deftest render-to-string-is-the-shipped-entry-point-that-reaches-it
  (testing "The severity claim, executed: this is not a test-only path. The
           public `render-to-string` reaches the same throw."
    (rejected-with :rf.error/invalid-hiccup-head "render-to-string on a provider head"
                   #(emit/render-to-string [:main [provider {:value "dark"} "x"]] nil))))

(deftest emit-rejects-a-cyclic-reserved-rf-head-with-its-own-error
  (testing "`reject-reserved-rf-hiccup-head!` prints the ELEMENT, so a
           cyclic value anywhere in the element blew it up even though the
           head itself is an ordinary keyword."
    (rejected-with :rf.error/invalid-hiccup-head "an unrecognised :rf/* head"
                   #(emit/emit-element [:rf/suspense-boundry {:ctx provider}]))))

(deftest emit-rejects-a-cyclic-reagent-native-head-with-its-own-error
  (testing "`[:> ctx.Provider …]` is what `:>` interop is FOR, so this arm
           is the one where a cyclic foreign value is not merely possible
           but EXPECTED."
    (rejected-with :rf.error/ssr-reagent-native-head "a :> provider element"
                   #(emit/emit-element [:> provider {:value "dark"}]))))

(deftest emit-rejects-a-cyclic-suspense-boundary-with-its-own-error
  (testing "A boundary's `:fallback` is ordinary hiccup and can carry a
           foreign value anywhere inside it."
    (rejected-with :rf.error/ssr-suspense-boundary-outside-stream
                   "a suspense boundary whose fallback holds a provider"
                   #(emit/emit-element
                      [:rf/suspense-boundary {:id :b :fallback [:div {:ctx provider}]}]))))

;; ---------------------------------------------------------------------------
;; re-frame.ssr.ui-tree — the malformed-node + version-gate throws
;; ---------------------------------------------------------------------------

(deftest ui-tree-rejects-a-cyclic-malformed-node-with-its-own-error
  (testing "Every `malformed-node!` arm prints the offending node or child.
           Reverting `diagnostic/pr-form` at any one of them reds its row
           here with `{:threw \"RangeError\"}`."
    (doseq [[label t]
            [["a foreign node in child position"
              (tree provider)]
             ["a cyclic array in child position"
              (tree (self-referential-array))]
             ["a node with two discriminators"
              (tree {:tag :div :view-id :v :attrs {:ctx provider}})]
             ["a node with no discriminator and no children"
              (tree {:attrs {:ctx provider}})]
             ["a trusted-HTML node whose :html is not a string"
              (tree {:html provider})]
             ["a raw-text element with a structural child"
              (tree {:tag :script :children [{:tag :b :children ["x"]} provider]})]
             ["a textarea given both a value and a child"
              (tree {:tag :textarea :attrs {:value "v"} :children [provider]})]
             ["a textarea given more than one child"
              (tree {:tag :textarea :children [provider provider]})]
             ["a textarea given a trusted-markup child"
              (tree {:tag :textarea :children [{:html "x" :ctx provider}]})]
             ["a textarea given a structural child"
              (tree {:tag :textarea :children [{:tag :b :attrs {:ctx provider}}]})]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(ui-tree/emit-ui-tree t)))))

(deftest ui-tree-version-gate-rejects-a-cyclic-version-with-its-own-error
  (testing "The version gate runs FIRST and prints the version it got, so a
           foreign value in that slot reached `pr-str` before any node did."
    (rejected-with :rf.error/ssr-ui-tree-version-unsupported
                   "a cyclic :rf.ui/tree-version"
                   #(ui-tree/emit-ui-tree {:rf.ui/tree-version provider}))))

;; ---------------------------------------------------------------------------
;; The acyclic path, byte for byte
;; ---------------------------------------------------------------------------

(deftest an-acyclic-diagnostic-is-byte-identical
  (testing "The expectation embeds `cljs.core/pr-str`'s OWN output, so these
           rows fail the moment a message stops being what `pr-str`
           produced before this fix existed. The acyclic foreign object
           keeps its CONTENTS in the message — eliding every foreign value
           (what the hash walk does, and rightly) would have cost the
           diagnostic exactly the information it exists to carry."
    (let [el [acyclic-object {:value "dark"}]
          o  (outcome #(emit/emit-element el))]
      (is (= :rf.error/invalid-hiccup-head (:error-id o))
          (str "the acyclic control still produces the correct error; got " (pr-str o)))
      (is (str/includes? (:message o) (str "hiccup vector head " (pr-str acyclic-object)))
          (str "head printed byte-identically to pr-str; got " (pr-str (:message o))))
      (is (str/includes? (:message o) (str "(in element " (pr-str el) ")"))
          (str "element printed byte-identically to pr-str; got " (pr-str (:message o)))))

    (let [el [:> acyclic-object {:value "dark"}]
          o  (outcome #(emit/emit-element el))]
      (is (= :rf.error/ssr-reagent-native-head (:error-id o)))
      (is (str/includes? (:message o) (str "(element " (pr-str el) ")"))))

    (let [node {:attrs {:ctx acyclic-object}}
          o    (outcome #(ui-tree/emit-ui-tree (tree node)))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str "renderable tree node: " (pr-str node)))))

    (let [o (outcome #(ui-tree/emit-ui-tree {:rf.ui/tree-version acyclic-object}))]
      (is (= :rf.error/ssr-ui-tree-version-unsupported (:error-id o)))
      (is (str/includes? (:message o) (str " — got " (pr-str acyclic-object)))))))

(deftest a-well-formed-tree-still-renders
  (testing "The crossing is on the FAILURE path only — nothing about a
           valid render moves."
    (is (= "<div class=\"x\"><p>hi</p></div>"
           (emit/render-to-string [:div {:class "x"} [:p "hi"]] nil)))
    (is (= "<div><p>hi</p></div>"
           (ui-tree/emit-ui-tree
             (tree {:tag :div :children [{:tag :p :children ["hi"]}]}))))))
