(ns re-frame.ssr-hash-test
  "Spec 011 §Hydration-mismatch detection — canonical-edn / render-tree-hash
  contract. The hash crosses the wire between server and client of the same
  implementation; any structural-equivalence rule the spec pins MUST be honoured
  on both sides or apps see spurious `:rf.ssr/hydration-mismatch` traces.

  Per Spec 011 §Hydration-mismatch detection (line 295):
    'FNV-1a 32-bit over a canonical EDN serialisation of the render-tree
     (depth-first traversal; attribute maps in sorted-key order; nil pruned).'

  This file pins the **nil pruning** rule (rf2-6djjl). Without it, the
  ubiquitous `{:class (when condition? :selected)}` shape produces
  `{:class nil}` on one side and `{}` on the other, and the trees hash
  differently despite being structurally equivalent.

  The hash-stability and order-sensitivity rules are pinned in
  smoke_test.clj `render-tree-hash-is-stable`; the JVM↔CLJS parity smoke
  lives in hash_check_cljs_test.cljs. This namespace focuses on
  pruning-equivalence."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.hash :as hash]))

;; ---- canonical-edn ---------------------------------------------------------

(deftest canonical-edn-prunes-nil-from-maps
  (testing "map entries with nil values are pruned — {:class nil} ≡ {}"
    (is (= (hash/canonical-edn {})
           (hash/canonical-edn {:class nil}))
        "lone nil-valued entry is pruned")
    (is (= (hash/canonical-edn {:id "x"})
           (hash/canonical-edn {:id "x" :class nil}))
        "nil-valued entry pruned alongside live entries")
    (is (= (hash/canonical-edn {:id "x"})
           (hash/canonical-edn {:id "x" :class nil :title nil :hidden nil}))
        "multiple nil-valued entries all pruned"))

  (testing "nil map keys ARE preserved (the spec prunes nil VALUES, not keys)"
    ;; Hiccup attribute maps don't have nil keys in practice, but the
    ;; spec is explicit about pruning nil values — leaves keys alone.
    (is (not= (hash/canonical-edn {})
              (hash/canonical-edn {nil "x"}))
        "nil-keyed entry is NOT pruned")))

(deftest canonical-edn-prunes-nil-from-sequences
  (testing "vectors: nil child elements are pruned"
    (is (= (hash/canonical-edn [:p "text"])
           (hash/canonical-edn [:p "text" nil]))
        "trailing nil element pruned")
    (is (= (hash/canonical-edn [:p "text"])
           (hash/canonical-edn [:p nil "text" nil]))
        "interior + trailing nil elements pruned"))

  (testing "lists: nil child elements are pruned"
    (is (= (hash/canonical-edn '(:p "text"))
           (hash/canonical-edn '(:p "text" nil)))
        "trailing nil element pruned in a list")))

(deftest canonical-edn-returns-nil-for-nil
  (testing "bare nil input — sentinel for parent pruning"
    (is (nil? (hash/canonical-edn nil))
        "canonical-edn returns nil for nil input — parent's keep/remove prunes it")))

(deftest canonical-edn-non-nil-fast-path-unchanged
  (testing "trees with no nil values produce byte-identical canonical EDN
            to the pre-fix behaviour — important because the JVM↔CLJS
            parity test (hash_check_cljs_test.cljs) pins '9d7457ef' for
            [:div {:class \"x\"} [:p \"hi\"]]."
    (is (= "[:div {:class \"x\"} [:p \"hi\"]]"
           (hash/canonical-edn [:div {:class "x"} [:p "hi"]]))
        "no-nil tree serialises to the pre-fix canonical form")))

;; ---- render-tree-hash ------------------------------------------------------

(deftest render-tree-hash-equates-nil-attr-with-absent-attr
  (testing "{:class nil} and {} hash identically per Spec 011 §Hydration-mismatch"
    (is (= (hash/render-tree-hash [:div {:class nil}])
           (hash/render-tree-hash [:div {}]))
        "the common (when condition? :class) shape no longer triggers spurious mismatch")
    (is (= (hash/render-tree-hash [:div {:id "x" :class nil}])
           (hash/render-tree-hash [:div {:id "x"}]))
        "nil-valued attr pruned alongside live attrs")))

(deftest render-tree-hash-equates-nil-child-with-absent-child
  (testing "nil children are pruned — [:p \"text\" nil] ≡ [:p \"text\"]"
    (is (= (hash/render-tree-hash [:p "text" nil])
           (hash/render-tree-hash [:p "text"]))
        "trailing nil child pruned")
    (is (= (hash/render-tree-hash [:div [:p "a"] nil [:p "b"]])
           (hash/render-tree-hash [:div [:p "a"] [:p "b"]]))
        "interior nil child pruned")))

;; ===========================================================================
;; rf2-mff1ht — map ordering must be a TOTAL order (str-colliding keys)
;;
;; `append-map!` sorted entries by `(comp str key)`, which is NOT a total
;; order: a keyword `:a` and a string `":a"` both `str` to `":a"`, so
;; `sort-by` falls back to source iteration/insertion order. Two maps that
;; are `=` in Clojure (`{:a 1 ":a" 2}`) could then canonicalise to
;; different EDN strings and hash differently depending only on how the
;; map was constructed — a false hydration mismatch. The fix sorts by the
;; canonical-EDN form of the key (`:a` vs the quoted `":a"`), a total
;; cross-runtime-stable order.
;; ===========================================================================

(deftest render-tree-hash-stable-for-str-colliding-keys
  (testing "rf2-mff1ht: a map mixing a keyword `:a` and a string `\":a\"`
            (whose `str` forms collide) hashes IDENTICALLY regardless of
            insertion/construction order"
    (let [a-first     (array-map :a 1 ":a" 2)
          colon-first (array-map ":a" 2 :a 1)]
      (is (= a-first colon-first)
          "the two maps are Clojure-= (sanity: same logical map)")
      (is (= (hash/render-tree-hash a-first)
             (hash/render-tree-hash colon-first))
          "render-tree-hash is insertion-order-independent for
           str-colliding keys")
      (is (= (hash/canonical-edn a-first)
             (hash/canonical-edn colon-first))
          "canonical EDN is byte-identical regardless of construction order"))

    (testing "the canonical form distinguishes the two key shapes (the
              keyword bare, the string quoted) so the order is total"
      ;; The keyword sorts before the quoted string (`:` 0x3A < `\"` 0x22?
      ;; no — `"` is 0x22, `:` is 0x3A, so the quoted string sorts first).
      ;; We assert byte-equality across orders, not the absolute position.
      (is (str/includes? (hash/canonical-edn (array-map :a 1 ":a" 2))
                         "\":a\"")
          "the string key keeps its quotes in canonical form")
      (is (str/includes? (hash/canonical-edn (array-map :a 1 ":a" 2))
                         ":a 1")
          "the keyword key is bare in canonical form"))))

(deftest render-tree-hash-prunes-nil-deeply
  (testing "pruning is recursive — nil-pruning applies at every level"
    (is (= (hash/render-tree-hash
             [:section {:class "wrap"}
              [:header {:role "banner" :hidden nil}
               [:h1 "Title" nil]]
              [:article {:class nil}
               [:p "Body" nil]]])
           (hash/render-tree-hash
             [:section {:class "wrap"}
              [:header {:role "banner"}
               [:h1 "Title"]]
              [:article {}
               [:p "Body"]]]))
        "a deeply nested tree with nils at multiple levels hashes identically
         to the same tree with all nils pruned")))

;; ===========================================================================
;; rf2-dl9yg TC-2 — :doctype? + :emit-hash? composition
;; ===========================================================================
;;
;; The two opts interact subtly: hash injection runs on the hiccup root
;; BEFORE stringification (rf2-lxwse), so the doctype prepend lands after
;; the hash attribute has been stamped — `data-rf-render-hash` rides on
;; the root DOM element, not on the doctype declaration. Pin the
;; composition.

(deftest doctype-and-emit-hash-compose
  (testing "(render-to-string tree {:doctype? true :emit-hash? true}) emits
            <!DOCTYPE html> followed by <root data-rf-render-hash=\"...\"> —
            the hash rides on the root element, not on the doctype"
    (let [tree [:div {:class "page"} [:h1 "Hello"]]
          html (emit/render-to-string tree {:doctype? true :emit-hash? true})]
      (is (str/starts-with? html "<!DOCTYPE html>")
          ":doctype? prepended")
      (is (re-find #"<!DOCTYPE html><div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                   html)
          ":emit-hash? stamped on the root <div>, immediately after the doctype")
      (is (str/includes? html "<h1>Hello</h1>")
          "body content rendered"))))

(deftest emit-hash-without-doctype-yields-bare-root
  (testing ":emit-hash? true / :doctype? false → root element carries the hash;
            no doctype prefix"
    (let [tree [:section [:p "x"]]
          html (emit/render-to-string tree {:emit-hash? true})]
      (is (not (str/starts-with? html "<!DOCTYPE"))
          "no doctype emitted")
      (is (re-find #"^<section[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                   html)
          "hash attribute on the root element"))))

(deftest doctype-without-emit-hash-omits-hash-attribute
  (testing ":doctype? true / :emit-hash? false → doctype emitted; no hash attr"
    (let [tree [:div "no-hash"]
          html (emit/render-to-string tree {:doctype? true :emit-hash? false})]
      (is (str/starts-with? html "<!DOCTYPE html>"))
      (is (not (str/includes? html "data-rf-render-hash"))
          "no hash attribute when :emit-hash? false"))))

;; ---- rf2-jsa2ml: raw-fn hiccup heads hash identity-free -------------------

(deftest render-tree-hash-drops-raw-fn-identity
  (testing "rf2-jsa2ml — a raw-fn hiccup head (`[my-component props]`, the
            deref'd defn VALUE idiomatic to Reagent/UIx/Helix SSR) has NO
            cross-runtime-stable identity: (.toString fn) is class +
            identity-hashcode on the JVM but the JS source on CLJS. The server
            hashes the raw render tree and the client re-hashes the SAME tree,
            so a fn `.toString` in the canonical EDN made byte-identical HTML
            hash differently — a spurious :rf.ssr/hydration-mismatch that
            CRASHES under :on-mismatch :hard-error. The fix serialises every
            raw fn head to the fixed identity-free token `#fn[]`."
    (let [f1 (fn [_] [:span "a"])
          f2 (fn [_] [:span "a"])]
      (is (= "#fn[]" (hash/canonical-edn f1))
          "a raw fn serialises to the identity-free token, not #fn[<toString>]")
      ;; The decisive property: two DISTINCT fn objects standing for the same
      ;; logical view (server's deref'd defn vs client's) hash identically.
      ;; Before the fix f1/f2 had different identity-hashcodes in their
      ;; toString → different canonical EDN → different hashes → false mismatch.
      (is (not= (.toString f1) (.toString f2))
          "sanity: the two fn objects DO have divergent toStrings (the trap)")
      (is (= (hash/render-tree-hash [:div [f1 {:x 1}]])
             (hash/render-tree-hash [:div [f2 {:x 1}]]))
          "distinct fn objects for the same tree hash identically — no spurious mismatch")
      ;; The fn's props still discriminate: a different prop → a different hash.
      (is (not= (hash/render-tree-hash [:div [f1 {:x 1}]])
                (hash/render-tree-hash [:div [f1 {:x 2}]]))
          "the head is identity-free but the props are still hashed")))
  (testing "a Var head stays identity-stable — a Var is NOT fn? on the JVM, so
            `[#'ns/view …]` falls to :else → pr-str → #'ns/name (identical
            both runtimes)"
    (is (= "#'clojure.core/identity"
           (hash/canonical-edn #'clojure.core/identity))
        "a Var reference keeps its stable #'ns/name print form")))

;; ---- rf2-0ypnnk: whole-valued doubles canonicalise cross-runtime ----------

(deftest whole-valued-doubles-canonicalise-hash-and-html-consistently
  (testing "rf2-0ypnnk — a whole-valued double is serialised WITHOUT the
            trailing .0 in BOTH the render-tree hash AND the emitted HTML, so
            the JVM `9.0`/`0.0` match the CLJS `9`/`0` (CLJS unifies 1.0→1).
            The two surfaces MUST agree — child AND attribute position — or
            the hash passes while the server/client DOM diverges (and, under
            :on-mismatch :hard-error, hydration crashes)."
    ;; hash side — canonical EDN drops the .0 for a child AND an attr value.
    (is (= "[:span 9]" (hash/canonical-edn [:span 9.0]))
        "whole-double child → `9` in the canonical EDN")
    (is (= "[:progress {:max 1,:value 0}]"
           (hash/canonical-edn [:progress {:value 0.0 :max 1.0}]))
        "whole-double attr values → `0`/`1` in the canonical EDN")
    ;; HTML side — the emitter applies the SAME normalisation, child + attr.
    (is (= "<span>9</span>" (emit/render-to-string [:span 9.0] {}))
        "whole-valued double CHILD renders `9`, not the JVM `9.0`")
    (let [html (emit/render-to-string [:progress {:value 0.0 :max 1.0}] {})]
      (is (str/includes? html "value=\"0\"") "whole-double attr renders value=\"0\"")
      (is (str/includes? html "max=\"1\"")   "whole-double attr renders max=\"1\"")
      (is (not (str/includes? html ".0"))    "no trailing .0 leaks into the HTML"))
    ;; integers, non-whole doubles, and ratios behave as documented.
    (is (= "42"   (hash/canonical-number 42))   "an integer is unchanged")
    (is (= "3.14" (hash/canonical-number 3.14)) "a non-whole double is unchanged")
    (is (= "0.5"  (hash/canonical-number 1/2))  "a ratio coerces to its IEEE-double form")
    (is (= "<span>3.14</span>" (emit/render-to-string [:span 3.14] {}))
        "a non-whole double child is unchanged")))
