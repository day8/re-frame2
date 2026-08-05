(ns re-frame.ui.semantic-cycle-cljs-test
  "THE DIAGNOSTIC PATH ITSELF THREW — the `re-frame.ui` site (rf2-1aj9s).

  `re-frame.ui.semantic/normalize` builds its rejection messages with
  `(pr-str node)` over the offending runtime tree node, and `cljs.core`'s
  printer descends into a plain JS object (`#js {…}`, over `js-keys`) and a
  JS array (`#js […]`, over elements) with no seen-set. A foreign object
  graph may be CYCLIC — React 19's `createContext` returns an object whose
  `Provider` key points back at the context itself — so a tree carrying one
  anywhere inside a malformed node raised `RangeError: Maximum call stack
  size exceeded` instead of `:rf.error/ui-tree-malformed`.

  This is the THIRD and last site named by rf2-9s68n. The other two
  (`re-frame.ssr.emit`, `re-frame.ssr.ui-tree`) are pinned by
  `re-frame.ssr.diagnostic-cycle-cljs-test`, which also carries the unit
  rows for the shared helper. `re-frame.ui` and `re-frame.ssr` are SIBLING
  artefacts — both depend on core, neither may `:require` the other — so
  the helper they share lives in `re-frame.error`, beneath both, rather
  than being implemented twice.

  ## How these rows OBSERVE a stack overflow

  A `RangeError` raised inside `normalize` is an ordinary synchronous JS
  throw — nothing routes it to `reportError`, so no row here depends on the
  runner noticing an exception it never saw. Every row calls `normalize`
  through [[outcome]], which returns a MAP — `{:returned …}` or
  `{:threw <name> :error-id … :message … :ex-data-printable? …}` — and
  asserts on that map. A regression therefore fails with `\"RangeError\"`
  in its own failure text rather than aborting the var.

  ## The four sites, and what each has to prove

  Three `malformed-node!` arms of `norm-node` print the offending NODE, and
  `validate-tree-version!` prints the caller-supplied `:rf.ui/tree-version`
  — the same four crossings the SSR sibling makes, at the same two halves
  (message via `error/pr-form`, ex-data via `error/safe-form`). Each has to
  prove:

  1. a CYCLIC input produces `:rf.error/ui-tree-malformed`, not
     `RangeError`;
  2. the thrown `ex-data` is `pr-str`-able, so the cyclic value cannot ride
     out and explode at a downstream logger / projector / trace sink;
  3. an ACYCLIC input's message is BYTE-IDENTICAL to the one `pr-str`
     produces — asserted by embedding `cljs.core/pr-str`'s own output in
     the expectation, not by eyeballing a literal.

  [[the-fixtures-are-genuinely-cyclic]] is the non-vacuity control. Without
  it every row below could pass on an acyclic fixture and prove nothing."
  (:require ["react" :as react]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.error :as error]
            [re-frame.ui.semantic :as semantic]))

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
  "`ctx.Provider` — the value an author most plausibly leaks into a tree."
  (.-Provider corpus-context))

(def ^:private acyclic-object
  "The ACYCLIC control. A plain JS object `pr-str` renders in full and
  terminates on: every row that pins byte-identity uses this."
  #js {"theme" "dark" "level" 3})

(defn- tree
  "A version-1 structural tree wrapping `children` in a root fragment."
  [& children]
  {:rf.ui/tree-version 1 :children (vec children)})

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

;; ---------------------------------------------------------------------------
;; The control
;; ---------------------------------------------------------------------------

(deftest the-fixtures-are-genuinely-cyclic
  (testing "THE NON-VACUITY CONTROL. `pr-str` is what every message site
           below called, and on these values it recurs until the stack
           blows. If any row here ever goes green-by-termination, the
           fixture has stopped being cyclic and the whole file is
           measuring nothing."
    (is (= "RangeError" (:threw (outcome #(pr-str (self-referential-object)))))
        "a hand-built self-referential JS object defeats cljs.core/pr-str")
    (is (= "RangeError" (:threw (outcome #(pr-str (self-referential-array)))))
        "and so does a cycle reached through a JS array")
    (is (= "RangeError" (:threw (outcome #(pr-str provider))))
        "and so does a real React 19 context provider")
    (is (= "RangeError" (:threw (outcome #(pr-str {:attrs {:ctx provider}}))))
        "and so does an ordinary tree node holding one in its :attrs")
    (is (= "RangeError" (:threw (outcome #(pr-str #js {"held" [:p provider]}))))
        "and so does a MIXED chain — foreign object, persistent vector,
         foreign object — because the printer crosses between the two
         freely, which is why the detector has to as well")
    (is (identical? provider (.-Provider provider))
        "because React 19's ctx.Provider IS the context object, and that
         object carries a Provider key pointing back at itself — the cycle
         in one line")
    (is (string? (pr-str {:attrs {:ctx acyclic-object}}))
        "while the ACYCLIC control prints fine, which is what makes it a
         control for the byte-identity rows")))

;; ---------------------------------------------------------------------------
;; norm-node — the three malformed-node! arms
;; ---------------------------------------------------------------------------

(deftest normalize-rejects-a-cyclic-malformed-node-with-its-own-error
  (testing "Each `malformed-node!` arm prints the offending node. Reverting
           `error/pr-form` at any one of them reds its row here with
           `{:threw \"RangeError\"}`, and reverting `error/safe-form` in
           `malformed-node!` itself reds every `:ex-data-printable?`
           assertion at once."
    (doseq [[label t]
            [;; the :else arm — a non-map, non-string node
             ["a foreign value in child position"
              (tree provider)]
             ["a cyclic array in child position"
              (tree (self-referential-array))]
             ["a hand-built cyclic object in child position"
              (tree (self-referential-object))]
             ;; the multiple-discriminators arm
             ["a node with two discriminators, cycle in :attrs"
              (tree {:tag :div :view-id :v :attrs {:ctx provider}})]
             ["a node with two discriminators, cycle in :html"
              (tree {:tag :div :html provider})]
             ;; the no-discriminator arm
             ["a node with no discriminator and no children"
              (tree {:attrs {:ctx provider}})]
             ["a no-discriminator node whose cycle is a MIXED chain"
              (tree {:attrs #js {"held" [:p provider]}})]
             ;; nested deep, so the path threading runs too
             ["a cyclic malformed node nested under two elements"
              (tree {:tag :section
                     :children [{:tag :div :children [{:attrs {:ctx provider}}]}]})]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(semantic/normalize t)))))

(deftest normalize-still-locates-a-cyclic-node-by-path
  (testing "The crossing must not cost the diagnostic its POSITION — the
           `:path` slot is what a tool uses to `get-in` straight to the
           offending node, and it is framework-built so it never crosses."
    (let [t (tree {:tag :div :children [{:attrs {:ctx provider}}]})
          o (outcome #(semantic/normalize t))]
      (is (= :rf.error/ui-tree-malformed (:error-id o))
          (str "must reject rather than overflow; got " (pr-str o)))
      (is (str/includes? (:message o) "(at tree path [:children 0 :children 0])")
          (str "the path still rides the message; got " (pr-str (:message o)))))))

;; ---------------------------------------------------------------------------
;; validate-tree-version! — the version gate
;; ---------------------------------------------------------------------------

(deftest normalize-version-gate-rejects-a-cyclic-version-with-its-own-error
  (testing "The version gate runs FIRST and prints the version it got, so a
           foreign value in that slot reached `pr-str` before any node did.
           The SSR sibling crosses the same field at the same two halves;
           leaving one crossed and the other not is the drift these paired
           gates exist to avoid."
    (doseq [[label t] [["a provider as :rf.ui/tree-version"
                        {:rf.ui/tree-version provider}]
                       ["a hand-built cycle as :rf.ui/tree-version"
                        {:rf.ui/tree-version (self-referential-object)}]
                       ["a cyclic array as :rf.ui/tree-version"
                        {:rf.ui/tree-version (self-referential-array)}]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(semantic/normalize t)))))

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
    ;; the no-discriminator arm
    (let [node {:attrs {:ctx acyclic-object}}
          o    (outcome #(semantic/normalize (tree node)))]
      (is (= :rf.error/ui-tree-malformed (:error-id o))
          (str "the acyclic control still produces the correct error; got " (pr-str o)))
      (is (str/includes? (:message o) (str "is not a renderable tree node: " (pr-str node)))
          (str "node printed byte-identically to pr-str; got " (pr-str (:message o)))))

    ;; the multiple-discriminators arm
    (let [node {:tag :div :view-id :v :attrs {:ctx acyclic-object}}
          o    (outcome #(semantic/normalize (tree node)))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str "branch order: " (pr-str node)))
          (str "node printed byte-identically to pr-str; got " (pr-str (:message o)))))

    ;; the :else arm
    (let [o (outcome #(semantic/normalize (tree acyclic-object)))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o)
                         (str "malformed tree node in normalization N: "
                              (pr-str acyclic-object)))
          (str "node printed byte-identically to pr-str; got " (pr-str (:message o)))))

    ;; the version gate
    (let [o (outcome #(semantic/normalize {:rf.ui/tree-version acyclic-object}))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str " — got " (pr-str acyclic-object)))
          (str "version printed byte-identically to pr-str; got "
               (pr-str (:message o)))))))

(deftest the-ex-data-value-slot-survives-a-downstream-sink
  (testing "`:value` is the slot a tool reads to see the offending node. It
           must still BE the node when the node is printable, and must be
           printable when the node is not — the two halves of the ex-data
           crossing."
    (let [node {:attrs {:ctx acyclic-object}}
          data (try (semantic/normalize (tree node)) nil
                    (catch :default e (ex-data e)))]
      (is (identical? node (:value data))
          "an acyclic node rides out IDENTICAL — safe-form returned its input")
      (is (= [:children 0] (:path data))))

    (let [data (try (semantic/normalize (tree {:attrs {:ctx provider}})) nil
                    (catch :default e (ex-data e)))]
      (is (string? (pr-str data))
          "a cyclic node's ex-data is printable at a downstream sink")
      (is (str/includes? (pr-str (:value data)) "#js {…cyclic…}")
          "and the cycle inside it was replaced by the fixed token"))))

;; ---------------------------------------------------------------------------
;; The success path is untouched
;; ---------------------------------------------------------------------------

(deftest a-well-formed-tree-still-normalizes
  (testing "The crossing is on the FAILURE path only — nothing about a valid
           normalization moves."
    (is (= [{:tag :div :attrs {"class" "x"}
             :children [{:tag :p :children ["hi"]}]}]
           (semantic/normalize
             {:rf.ui/tree-version 1
              :tag                :div
              :attrs              {:class "x"}
              :children           [{:tag :p :children ["hi"]}]})))))
