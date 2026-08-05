(ns re-frame.ssr.foreign-value-hash-cljs-test
  "THE FOREIGN CROSSING IN THE RENDER-TREE HASH (rf2-56iys).

  `canonical-edn-into`'s `:else` branch delegates to `pr-str`, and
  `cljs.core`'s printer descends into a plain JS object (`#js {…}`, over
  `js-keys`) and a JS array (`#js […]`, over elements) with no seen-set.
  A foreign object graph may be CYCLIC — React 19's `createContext`
  returns an object whose `Provider` key points back at the context
  itself — so hashing an ordinary provider-headed form recurred until the
  stack blew (`RangeError: Maximum call stack size exceeded`) instead of
  returning a hash. The walk now stops at those two predicates and emits
  one fixed token.

  ## How these rows OBSERVE a stack overflow

  A `RangeError` raised inside `render-tree-hash` is an ordinary
  synchronous JS throw — nothing routes it to `reportError`, so no row
  here depends on the runner noticing an exception it never saw. Every
  row calls the walk through [[outcome]], which returns a MAP — either
  `{:hash …}` or `{:threw \"RangeError\" :message …}` — and asserts on
  that map. A regression therefore fails with the error's own name in the
  failure text rather than aborting the var.

  [[the-fixtures-are-genuinely-cyclic]] is the non-vacuity control, and it
  is the row that makes the rest of the file mean something: it asserts
  that `cljs.core/pr-str` — the exact printer the `:else` branch would
  reach — DOES blow the stack on these same fixtures. Without it every
  row below could pass on an acyclic fixture and prove nothing."
  (:require ["react" :as react]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.ssr.hash :as h]))

;; ---------------------------------------------------------------------------
;; Fixtures — one hand-built cycle, one real React 19 context
;; ---------------------------------------------------------------------------

(defn- self-referential-object
  "A plain JS object holding a reference to ITSELF. No React: the defect
  is a property of a foreign object graph, and this is the smallest value
  that has it."
  []
  (let [o #js {"tag" "cyclic"}]
    (unchecked-set o "self" o)
    o))

(def ^:private corpus-context
  "A real React context, so the rows below carry the shape that was
  reported rather than a model of it."
  (react/createContext "unset"))

(def ^:private provider
  "`ctx.Provider` — what an author writes in head position."
  (.-Provider corpus-context))

(defn- outcome
  "Run `f` and describe what happened as DATA: `{:hash …}` on a return,
  `{:threw <error name> :message …}` on a throw. Assertions read this map,
  so a stack overflow shows up as a value in the failure text."
  [f]
  (try {:hash (f)}
       (catch :default e {:threw (.-name e) :message (ex-message e)})))

(defn- hex-hash?
  [x]
  (some? (re-matches #"[0-9a-f]{8}" (str x))))

;; ---------------------------------------------------------------------------
;; The control
;; ---------------------------------------------------------------------------

(deftest the-fixtures-are-genuinely-cyclic
  (testing "THE NON-VACUITY CONTROL. `pr-str` is what the walk's `:else`
           branch delegates to, and on these values it recurs until the
           stack blows. If either row here ever goes green-by-termination,
           the fixture has stopped being cyclic and every row below is
           measuring nothing."
    (is (= "RangeError" (:threw (outcome #(pr-str (self-referential-object)))))
        "a hand-built self-referential JS object defeats cljs.core/pr-str")
    (is (= "RangeError" (:threw (outcome #(pr-str provider))))
        "and so does a real React 19 context provider")
    (is (identical? provider (.-Provider provider))
        "because React 19's ctx.Provider IS the context object, and that
         object carries a Provider key pointing back at itself — the cycle
         in one line")))

;; ---------------------------------------------------------------------------
;; The defect, inverted
;; ---------------------------------------------------------------------------

(deftest a-cyclic-foreign-value-hashes-instead-of-blowing-the-stack
  (testing "THE REGRESSION GUARD. Revert the foreign branch in
           `canonical-edn-into` and every row here reports
           `{:threw \"RangeError\"}` where a hash was expected."
    (doseq [[label v] [["a self-referential object" (self-referential-object)]
                       ["a React 19 context"        corpus-context]
                       ["a React 19 provider"       provider]
                       ["a provider in head position"
                        [provider {:value "dark"} [:p.render-subtree "SUBTREE"]]]
                       ["a provider nested in markup"
                        [:div.hosts [:h1 "hosts"] [provider {:value "dark"} "x"]]]
                       ["a provider in an attribute value"
                        [:div {:ctx provider} "x"]]
                       ["a cycle reached through a JS array"
                        #js [(self-referential-object)]]]]
      (let [o (outcome #(h/render-tree-hash v))]
        (is (hex-hash? (:hash o))
            (str label " must hash to 8 lowercase hex chars; got "
                 (pr-str o)))))))

;; ---------------------------------------------------------------------------
;; What the branch emits, pinned
;; ---------------------------------------------------------------------------

(deftest a-foreign-value-serialises-to-one-fixed-token
  (testing "The token is identity-free, exactly as the `fn?` branch's
           `#fn[]` is (rf2-jsa2ml) — and for the second of that branch's
           reasons as well as the first: `#js {\"f\" some-fn}` used to
           print `#js {:f #object[f]}`, smuggling a JS function's `.name`
           — which the `:advanced` compiler renames — back into the hash
           the fn branch exists to keep identity out of."
    (is (= "#js{}" (h/canonical-edn #js {"a" 1 "b" "two"})))
    (is (= "#js{}" (h/canonical-edn #js {})))
    (is (= "#js{}" (h/canonical-edn #js {"f" (fn [] 1)}))
        "no #object[<munged name>] survives the crossing")
    (is (= "#js[]" (h/canonical-edn #js [1 2 3])))
    (is (= "#js[]" (h/canonical-edn #js [])))
    (is (= "#js{}" (h/canonical-edn provider)))))

(deftest the-form-around-a-foreign-value-still-hashes
  (testing "The token collapses the FOREIGN value and nothing else — the
           props and children of a provider-headed form go on
           discriminating, which is what keeps the hash a hash rather than
           a constant."
    (let [dark  (h/render-tree-hash [provider {:value "dark"} [:p "x"]])
          light (h/render-tree-hash [provider {:value "light"} [:p "x"]])
          other (h/render-tree-hash [provider {:value "dark"} [:p "y"]])]
      (is (not= dark light) "a different prop is a different hash")
      (is (not= dark other) "a different child is a different hash")
      (is (= "[#js{} {:value \"dark\"} [:p \"x\"]]"
             (h/canonical-edn [provider {:value "dark"} [:p "x"]]))))))

;; ---------------------------------------------------------------------------
;; The predicates, bounded
;; ---------------------------------------------------------------------------

(deftest only-the-two-descending-printer-branches-are-intercepted
  (testing "`object?` and `array?` are precisely the two `cljs.core`
           printer branches that DESCEND. Every other print form a
           foreign value can take is bounded and recurses into nothing,
           so it keeps the serialisation it has always had — this row is
           what stops the foreign branch from quietly widening."
    (is (= "#inst \"1970-01-01T00:00:00.000-00:00\"" (h/canonical-edn (js/Date. 0))))
    (is (= "#\"ab+c\"" (h/canonical-edn #"ab+c")))
    (is (= "#fn[]" (h/canonical-edn (fn [_] nil)))
        "and a raw fn still takes the fn branch, ahead of either of these")
    (is (= "[:div {:class \"x\"} [:p \"hi\"]]"
           (h/canonical-edn [:div {:class "x"} [:p "hi"]]))
        "ordinary CLJS data is untouched — the parity fixtures at
         re-frame.ssr.hash-parity-fixtures are the full statement of
         that, pinned on both runtimes")))
