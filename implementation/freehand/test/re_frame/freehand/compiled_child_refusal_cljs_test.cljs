(ns re-frame.freehand.compiled-child-refusal-cljs-test
  "A compiled view's BROWSER lowering may not interpret a runtime markup
  value — the behavioural half of D010, in React.

  The compiled React emitter lowers every dynamic child expression to
  `re-frame.freehand.compiled-react/child`, which classifies one runtime
  value through `re-frame.freehand.react/child-elements`. D010 gives the
  compiled tier no dynamic-markup valve and no automatic fallback: a value
  that turns out to be a Hiccup vector has nowhere to go, because a
  compiled body carries no interpreter to walk one. So the seam REFUSES it
  by name — the same `re-frame.freehand.node/opaque-child!` refusal the
  structural children canonicalizer raises — rather than silently
  interpreting it inside compiled markup.

  The structural half of this claim is
  `compiled-grammar-jvm-test/compiled-markup-refuses-a-runtime-markup-value`,
  over `node/children`. That test stayed green while the React lowering
  gained the forbidden reachability, because it never exercised the browser
  seam; this file closes that gap on the seam a mounted compiled view
  actually calls for a dynamic child (rf2-drpa3.130).

  Normative owner:
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)
  §No interpreted fallback inside compiled markup, and
  [D010](../../../../../docs/design/freehand/decisions/D010-compiled-dynamic-markup-crossing.md)."
  (:require ["react" :as react]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.freehand.compiled-react :as cr]
            [re-frame.freehand.react :as fr]))

(defn- refusal
  "The ex-data of the D010 refusal `thunk` raises (with its message folded
  in), or nil when `thunk` does not throw one."
  [thunk]
  (try
    (thunk)
    nil
    (catch :default e
      (when-let [d (ex-data e)] (assoc d :message (ex-message e))))))

(deftest a-runtime-hiccup-vector-in-a-compiled-child-is-refused-not-interpreted
  (testing "rf2-drpa3.130 — the browser lowering sends every dynamic child
            through compiled-react/child. A value that turns out to be a
            Hiccup vector must be REFUSED by name (D010: no dynamic-markup
            valve, no interpreter inside a compiled view to walk one), the
            same typed refusal the structural children canonicalizer raises
            — never walked into React by the interpreted arm."
    (doseq [markup [[:span "hi"]
                    [:div.card {:id "x"} "y"]
                    ;; The original elided-boundary symptom: forwarded Hiccup
                    ;; carrying declarative event intent must fail LOUDLY here,
                    ;; not have its handler silently dropped by a walk that ran
                    ;; with no ambient candidate to own the site (acceptance 3).
                    [:button {:on-click [:app/go]} "go"]]]
      (let [d (refusal #(cr/child markup))]
        (is (some? d)
            (str (pr-str markup) " is refused, not interpreted"))
        (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
            (str (pr-str markup) " — the canonical typed D010 refusal id"))
        (is (str/includes? (or (:message d) "") "compiled, not interpreted")
            (str (pr-str markup) " — the diagnostic names the language boundary"))
        (is (str/includes? (or (:message d) "") "keep this view interpreted")
            (str (pr-str markup) " — and names the always-available recovery"))))))

(deftest the-compiled-child-seam-itself-refuses-a-runtime-vector
  (testing "compiled-react/child delegates to fr/child-elements, so the
            refusal lives at the seam: a compiled body cannot reach the
            interpreted walk through any caller of it (acceptance 2)."
    (let [d (refusal #(fr/child-elements [:span "x"]))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          "the seam raises the shared refusal")
      (is (str/includes? (or (:message d) "") "compiled, not interpreted")
          "with the D010 sentence"))))

(deftest a-legitimate-compiled-child-still-renders
  (testing "The refusal is exactly for runtime markup, and no wider
            (acceptance 4). Everything a compiled body legitimately produces
            in a child position still passes: nil/false render nothing, text
            and numbers become React text, an already-lowered React child
            passes through untouched, and a run of them splices in order."
    (is (nil? (cr/child nil)) "nil renders nothing")
    (is (nil? (cr/child false)) "false renders nothing")
    (is (= "text" (cr/child "text")) "a string is text")
    (is (= "3" (cr/child 3)) "a number takes React's own number formatting")
    (let [el (react/createElement "span" nil "x")]
      (is (react/isValidElement (cr/child el))
          "an already-lowered React child stays a React element")
      (is (identical? el (cr/child el))
          "…the very one it was handed, untouched"))
    (let [run (cr/child (list "a" "b"))]
      (is (array? run) "a seq of children splices into a run")
      (is (= ["a" "b"] (vec run)) "…in document order"))))
