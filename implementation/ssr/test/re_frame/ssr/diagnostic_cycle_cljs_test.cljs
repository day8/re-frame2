(ns re-frame.ssr.diagnostic-cycle-cljs-test
  "THE DIAGNOSTIC PATH MUST NOT ITSELF THROW.

  `re-frame.ssr.emit` and `re-frame.ssr.ui-tree` build rejection messages by
  printing the offending value, and `cljs.core`'s printer descends into a
  plain JS object or array with no seen-set. A foreign graph may be CYCLIC —
  React 19's `ctx.Provider` is the context, whose `Provider` key points back
  at itself — so `[ThemeContext.Provider {…}]`, the very mistake
  `:rf.error/invalid-hiccup-head` exists to catch, would raise `RangeError`
  instead of the error. Every site therefore crosses the value through
  `re-frame.error/safe-form` (or prints it with `re-frame.error/pr-form`).

  Each guarded site must show: a cyclic input yields the site's OWN error id;
  the thrown ex-data survives `pr-str` at a downstream sink; and an acyclic
  input's message is byte-identical to what `pr-str` produces. [[outcome]]
  returns a map rather than letting a throw abort the var, so a regression
  fails with `\"RangeError\"` in its own failure text.
  [[the-fixtures-are-genuinely-cyclic]] is the non-vacuity control.

  `safe-form` / `pr-form` live in core because a sibling artefact has a site
  of the same defect; their unit rows live here beside the SSR sites."
  (:require ["react" :as react]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.error :as rf.error]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- self-referential-object
  "A plain JS object holding a reference to ITSELF — the smallest value with
  the defect."
  []
  (let [o #js {"tag" "cyclic"}]
    (unchecked-set o "self" o)
    o))

(defn- self-referential-array
  "The ARRAY half — `pr-str`'s other descending branch."
  []
  (let [a #js ["cyclic"]]
    (.push a a)
    a))

(def ^:private corpus-context (react/createContext "unset"))

(def ^:private provider
  "`ctx.Provider` — what an author writes in head position."
  (.-Provider corpus-context))

(def ^:private acyclic-object
  "The ACYCLIC control, used by every byte-identity row."
  #js {"theme" "dark" "level" 3})

;; ---------------------------------------------------------------------------
;; Observation
;; ---------------------------------------------------------------------------

(defn- outcome
  "Run `f` and describe what happened as DATA: on a throw, the host error
  NAME, the framework error id, the message, and whether the ex-data
  survives `pr-str`."
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
  "Assert `f` threw the framework error `id` with printable ex-data."
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
  (testing "THE NON-VACUITY CONTROL: `pr-str` overflows on every cyclic
           fixture — a hand-built object, an array, a real React 19 provider,
           and a MIXED chain (foreign object, persistent vector, foreign
           object), which the printer crosses freely"
    (is (= ["RangeError" "RangeError" "RangeError" "RangeError"]
           (mapv #(:threw (outcome %))
                 [#(pr-str (self-referential-object))
                  #(pr-str (self-referential-array))
                  #(pr-str provider)
                  #(pr-str #js {"held" [:p provider]})])))))

;; ---------------------------------------------------------------------------
;; The helper
;; ---------------------------------------------------------------------------

(deftest safe-form-returns-an-acyclic-input-identically
  (testing "with no cycle reachable `safe-form` hands back the very object it
           was given, so `pr-form` (`pr-str` of it) is byte-identical to `pr-str`"
    (doseq [[label v] [["ordinary hiccup"      [:div {:class "x"} [:p "hi"]]]
                       ["an acyclic JS object" acyclic-object]
                       ["hiccup holding one"   [acyclic-object {:value "dark"}]]
                       ["an acyclic JS array"  #js [1 2 3]]
                       ["a nested acyclic obj" [:div {} [:span {:ctx acyclic-object}]]]
                       ["nil"                  nil]]]
      (is (identical? v (rf.error/safe-form v))
          (str label " must come back identical")))))

(deftest safe-form-elides-only-the-cyclic-foreign-value
  (testing "the token replaces only the value `pr-str` cannot survive, so the
           props and children around it stay in the diagnostic"
    (is (= "#js {…cyclic…}" (rf.error/pr-form (self-referential-object))))
    (is (= "#js […cyclic…]" (rf.error/pr-form (self-referential-array))))
    (is (= "[#js {…cyclic…} {:value \"dark\"} [:p \"x\"]]"
           (rf.error/pr-form [provider {:value "dark"} [:p "x"]])))
    (is (= "[:div {:ctx #js {…cyclic…}} \"x\"]"
           (rf.error/pr-form [:div {:ctx provider} "x"]))
        "a cycle nested in an attrs map is elided in place")
    (is (= "[:div #js […cyclic…]]"
           (rf.error/pr-form [:div #js [(self-referential-object)]]))
        "a foreign value from which a cycle is REACHABLE is elided whole")))

(deftest a-shared-subtree-is-not-a-cycle
  (testing "the detector is PATH-scoped: a foreign value reachable twice is a
           DAG that `pr-str` prints and terminates on"
    (let [shared #js {"k" "v"}
          form   [:div {:a shared :b shared}]]
      (is (identical? form (rf.error/safe-form form)))))
  (testing "a persistent collection inside a foreign value is crossed, not
           treated as the end of the graph"
    (let [form [:div #js {"held" [:p "x"]}]]
      (is (identical? form (rf.error/safe-form form))))))

(deftest a-cycle-through-a-mixed-chain-is-found
  (testing "the detector alternates between foreign values and persistent
           collections as `pr-str` does"
    (is (= "[:div #js {…cyclic…}]"
           (rf.error/pr-form [:div #js {"held" [:p provider]}])))
    (is (= "#js {…cyclic…}"
           (rf.error/pr-form #js {"held" #js [[:p provider]]}))
        "two collections deep and through an array as well")))

;; ---------------------------------------------------------------------------
;; re-frame.ssr.emit — three throw sites
;; ---------------------------------------------------------------------------

(deftest emit-rejects-a-cyclic-hiccup-head-with-its-own-error
  (testing "THE CENTRAL CASE: a provider is neither `keyword?` nor `ifn?`, so
           it falls to `reject-invalid-hiccup-head!`, at the top level or deep
           in markup (the path `render-to-string` takes)"
    (doseq [[label el] [["a provider in head position"  [provider {:value "dark"}]]
                        ["a provider nested in markup"  [:div.hosts [:h1 "hosts"]
                                                         [provider {:value "dark"} "x"]]]]]
      (rejected-with :rf.error/invalid-hiccup-head label
                     #(rf.ssr.emit/emit-element el)))))

(deftest emit-rejects-a-cyclic-reserved-rf-head-with-its-own-error
  (testing "`reject-reserved-rf-hiccup-head!` prints the whole ELEMENT"
    (rejected-with :rf.error/invalid-hiccup-head "an unrecognised :rf/* head"
                   #(rf.ssr.emit/emit-element [:rf/suspense-boundry {:ctx provider}]))))

(deftest emit-rejects-a-cyclic-reagent-native-head-with-its-own-error
  (testing "`[:> ctx.Provider …]` is what `:>` interop is FOR"
    (rejected-with :rf.error/ssr-reagent-native-head "a :> provider element"
                   #(rf.ssr.emit/emit-element [:> provider {:value "dark"}]))))

;; ---------------------------------------------------------------------------
;; re-frame.ssr.ui-tree — the malformed-node + version-gate throws
;; ---------------------------------------------------------------------------

(deftest ui-tree-rejects-a-cyclic-malformed-node-with-its-own-error
  (testing "every `malformed-node!` arm prints the offending node or child"
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
                     #(rf.ssr.ui-tree/emit-ui-tree t)))))

(deftest ui-tree-version-gate-rejects-a-cyclic-version-with-its-own-error
  (testing "the version gate runs FIRST and prints the version it got"
    (rejected-with :rf.error/ssr-ui-tree-version-unsupported
                   "a cyclic :rf.ui/tree-version"
                   #(rf.ssr.ui-tree/emit-ui-tree {:rf.ui/tree-version provider}))))

;; ---------------------------------------------------------------------------
;; The acyclic path, byte for byte
;; ---------------------------------------------------------------------------

(deftest an-acyclic-diagnostic-is-byte-identical
  (testing "each expectation embeds `cljs.core/pr-str`'s OWN output beside the
           site's own wording, and an acyclic foreign object keeps its
           CONTENTS in the message"
    (let [el [acyclic-object {:value "dark"}]
          o  (outcome #(rf.ssr.emit/emit-element el))]
      (is (str/includes? (:message o) (str "hiccup vector head " (pr-str acyclic-object)))
          (str "head printed byte-identically to pr-str; got " (pr-str o)))
      (is (str/includes? (:message o) (str "(in element " (pr-str el) ")"))
          (str "element printed byte-identically to pr-str; got " (pr-str o))))

    (let [el [:> acyclic-object {:value "dark"}]
          o  (outcome #(rf.ssr.emit/emit-element el))]
      (is (str/includes? (:message o) (str "(element " (pr-str el) ")"))))

    (let [node {:attrs {:ctx acyclic-object}}
          o    (outcome #(rf.ssr.ui-tree/emit-ui-tree (tree node)))]
      (is (str/includes? (:message o) (str "renderable tree node: " (pr-str node)))))

    (let [o (outcome #(rf.ssr.ui-tree/emit-ui-tree {:rf.ui/tree-version acyclic-object}))]
      (is (str/includes? (:message o) (str " — got " (pr-str acyclic-object)))))))
