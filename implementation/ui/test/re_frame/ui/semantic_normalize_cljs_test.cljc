(ns re-frame.ui.semantic-normalize-cljs-test
  "Adversarial coverage for `re-frame.ui.semantic/normalize` (N) — the
  parity/fingerprint INPUT boundary (rf2-vxgfnd.20).

  Two fail-loud contracts pinned here (jvm-tree contract §Versioning +
  §Node discrimination):

    1. ROOT VERSION GATE — `normalize` validates `:rf.ui/tree-version`
       BEFORE any traversal; a missing / non-integer / unsupported
       version throws `:rf.error/ui-tree-malformed` with ex-data
       `{:got … :supported #{1}}`. An unknown version is NEVER silently
       reinterpreted under v1's rules.

    2. NODE DISCRIMINATION — every map node carries EXACTLY ONE of
       `:tag` / `:view-id` / `:html`, or none plus `:children` (a
       fragment). Zero discriminators with no `:children`, or two-plus
       discriminators, throws `:rf.error/ui-tree-malformed` — it is
       never guessed by branch order nor silently dropped as `[]`.

  Runs on BOTH hosts (`.cljc`): the discrimination + version rules are
  pure and host-identical."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.semantic :as semantic]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ex-of
  "Run `f`; return the thrown ExceptionInfo, or nil when it returns."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo
                 :cljs cljs.core/ExceptionInfo) e e)))

(defn- v1
  "Stamp a node map as a version-1 tree root."
  [node]
  (assoc node :rf.ui/tree-version 1))

;; ---------------------------------------------------------------------------
;; The five valid variants still normalize (regression floor)
;; ---------------------------------------------------------------------------

(deftest valid-variants-normalize
  (testing "element root -> element semantic node"
    (is (= [{:tag :div :children ["hi"]}]
           (semantic/normalize (v1 {:tag :div :children ["hi"]})))))

  (testing "trusted-HTML root -> opaque raw-markup leaf"
    (is (= [{:html "<b>x</b>"}]
           (semantic/normalize (v1 {:html "<b>x</b>"})))))

  (testing "view-boundary root SPLICES its children (boundary erased in N)"
    (is (= [{:tag :p :children ["y"]}]
           (semantic/normalize
            (v1 {:view-id :app/v :children [{:tag :p :children ["y"]}]})))))

  (testing "fragment root SPLICES its children in document order"
    (is (= [{:tag :p :children ["a"]} {:tag :p :children ["b"]}]
           (semantic/normalize
            (v1 {:children [{:tag :p :children ["a"]}
                            {:tag :p :children ["b"]}]})))))

  (testing "text is the fifth variant — carried verbatim as a child"
    (is (= [{:tag :div :children ["hello" {:tag :span :children ["world"]}]}]
           (semantic/normalize
            (v1 {:tag :div
                 :children ["hello" {:tag :span :children ["world"]}]})))))

  (testing "empty valid fragment remains legal -> []"
    (is (= [] (semantic/normalize (v1 {:children []})))))

  (testing "nil-rooted view boundary (no :children) -> []"
    (is (= [] (semantic/normalize (v1 {:view-id :app/empty}))))))

;; ---------------------------------------------------------------------------
;; Version gate — missing / non-integer / unsupported all fail loud
;; ---------------------------------------------------------------------------

(def ^:private bad-version-trees
  "[label root expected-:got] — each must throw the typed error with
  `{:got expected :supported #{1}}` BEFORE any traversal."
  [["missing version"        {:tag :div :children ["x"]}          nil]
   ["nil version"            {:rf.ui/tree-version nil :tag :div}   nil]
   ["string version"        {:rf.ui/tree-version "1" :tag :div}   "1"]
   ["non-integer version"   {:rf.ui/tree-version 1.5 :tag :div}   1.5]
   ["keyword version"       {:rf.ui/tree-version :v1 :tag :div}   :v1]
   ["unsupported version 2" {:rf.ui/tree-version 2 :tag :div}     2]
   ["unsupported version 0" {:rf.ui/tree-version 0 :tag :div}     0]])

(deftest version-gate-rejects-bad-versions
  (doseq [[label root got] bad-version-trees]
    (testing label
      (let [e    (ex-of #(semantic/normalize root))
            data (some-> e ex-data)]
        (is (some? e) (str label " must throw"))
        (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
            (str label " carries the typed node-malformation id"))
        (is (= got (:got data)) (str label " reports the offending :got"))
        (is (= #{1} (:supported data))
            (str label " reports :supported #{1}"))))))

(deftest version-gate-fires-before-traversal
  ;; An unsupported version on a root whose BODY is also malformed must
  ;; surface the VERSION failure (the gate runs first), not a node error.
  (let [data (-> (ex-of #(semantic/normalize
                          {:rf.ui/tree-version 2 :tag :div :html "both"}))
                 ex-data)]
    (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
    (is (= 2 (:got data)) "version gate reports the version, not the node")
    (is (= #{1} (:supported data)))))

;; ---------------------------------------------------------------------------
;; Discriminator ambiguity — every pair (and the triple) is rejected
;; ---------------------------------------------------------------------------

(def ^:private ambiguous-nodes
  "[label extra-node-keys expected-:got] — a v1 root whose single node
  carries MULTIPLE discriminators is ambiguous and must fail loud
  instead of being interpreted by branch order."
  [["tag + html"          {:tag :div :html "trusted"}            [:tag :html]]
   ["tag + view-id"       {:tag :div :view-id :app/v}            [:tag :view-id]]
   ["view-id + html"      {:view-id :app/v :html "trusted"}      [:view-id :html]]
   ["tag + view-id + html" {:tag :div :view-id :app/v :html "h"} [:tag :view-id :html]]])

(deftest ambiguous-discriminators-rejected
  (doseq [[label node got] ambiguous-nodes]
    (testing label
      (let [data (-> (ex-of #(semantic/normalize (v1 node))) ex-data)]
        (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
            (str label " throws the typed error"))
        (is (= got (:got data))
            (str label " enumerates the colliding discriminators in :got"))
        (is (= (v1 node) (:value data))
            (str label " carries the offending node as :value"))))))

;; ---------------------------------------------------------------------------
;; Discriminator-less maps — must NOT silently disappear as []
;; ---------------------------------------------------------------------------

(deftest discriminatorless-root-rejected
  (testing "a root with only the version key (no discriminator, no :children)"
    ;; the bug's case 4: {:rf.ui/tree-version 1} -> [] previously
    (let [data (-> (ex-of #(semantic/normalize {:rf.ui/tree-version 1}))
                   ex-data)]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
      (is (= [] (:got data)) "reports zero discriminators")))

  (testing "an empty map past the version gate still fails as a lost node"
    (let [data (-> (ex-of #(semantic/normalize
                            (v1 {:rf.ui/presence {:phase :present}})))
                   ex-data)]
      ;; a reserved diagnostic key is NOT a discriminator and does not
      ;; rescue a node that carries neither a primary nor :children
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
      (is (= [] (:got data))))))

(deftest nested-malformed-node-rejected
  (testing "a discriminator-less MAP nested as a child fails loud (AC 6)"
    (let [data (-> (ex-of #(semantic/normalize
                            (v1 {:tag :ul
                                 :children [{:tag :li :children ["ok"]}
                                            {:unrelated :junk}]})))
                   ex-data)]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
      (is (= {:unrelated :junk} (:value data))
          "the offending nested node is named")))

  (testing "an ambiguous node nested inside a fragment fails loud"
    (let [data (-> (ex-of #(semantic/normalize
                            (v1 {:children [{:tag :p :html "x"}]})))
                   ex-data)]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
      (is (= [:tag :html] (:got data))))))

;; ---------------------------------------------------------------------------
;; Forward-compat: unknown reserved :rf.ui/* keys stay ignored (AC 5)
;; ---------------------------------------------------------------------------

(deftest unknown-reserved-keys-ignored-after-validation
  (testing "an unknown :rf.ui/* diagnostic key on an element is stripped"
    (is (= [{:tag :div :children ["hi"]}]
           (semantic/normalize
            (v1 {:tag :div :children ["hi"]
                 :rf.ui/some-future-diag {:whatever true}})))))

  (testing "a fragment's reserved presence/boundary markers are ignored"
    (is (= [{:tag :p :children ["a"]}]
           (semantic/normalize
            (v1 {:rf.ui/presence {:phase :present}
                 :rf.ui/boundary :client-only
                 :children [{:tag :p :children ["a"]}]}))))))
