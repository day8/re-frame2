(ns re-frame.ssr-hash-test
  "Spec 011 §Hydration-mismatch detection — canonical-edn / render-tree-hash
  contract. The hash crosses the wire between server and client of the same
  implementation; any structural-equivalence rule the spec pins MUST be honoured
  on both sides or apps see spurious `:rf.ssr/hydration-mismatch` traces.

  Per Spec 011 §Hydration-mismatch detection (line 295):
    'FNV-1a 32-bit over a canonical EDN serialisation of the render-tree
     (depth-first traversal; attribute maps in sorted-key order; nil pruned).'

  This file pins the **nil pruning** rule on `canonical-edn` and its
  recursion through a whole tree. Without it, the ubiquitous
  `{:class (when condition? :selected)}` shape produces `{:class nil}` on
  one side and `{}` on the other, and the trees hash differently despite
  being structurally equivalent. It also pins how raw-fn heads and
  whole-valued doubles canonicalise, and how `:doctype?` composes with the
  render-hash attribute.

  The hash-stability and order-sensitivity rules are pinned in
  smoke_test.clj `render-tree-hash-is-stable`; the cross-host literal hashes
  live in `re-frame.hash-parity-test` and its CLJS twin."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.hash :as rf.ssr.hash]))

;; ---- canonical-edn ---------------------------------------------------------

(deftest canonical-edn-prunes-nil-from-maps
  (testing "map entries with nil values are pruned — {:class nil} ≡ {}"
    (is (= (rf.ssr.hash/canonical-edn {})
           (rf.ssr.hash/canonical-edn {:class nil}))
        "lone nil-valued entry is pruned")
    (is (= (rf.ssr.hash/canonical-edn {:id "x"})
           (rf.ssr.hash/canonical-edn {:id "x" :class nil}))
        "nil-valued entry pruned alongside live entries")
    (is (= (rf.ssr.hash/canonical-edn {:id "x"})
           (rf.ssr.hash/canonical-edn {:id "x" :class nil :title nil :hidden nil}))
        "multiple nil-valued entries all pruned"))

  (testing "nil map keys ARE preserved (the spec prunes nil VALUES, not keys)"
    ;; Hiccup attribute maps don't have nil keys in practice, but the
    ;; spec is explicit about pruning nil values — leaves keys alone.
    (is (not= (rf.ssr.hash/canonical-edn {})
              (rf.ssr.hash/canonical-edn {nil "x"}))
        "nil-keyed entry is NOT pruned")))

(deftest canonical-edn-prunes-nil-from-sequences
  (testing "vectors: nil child elements are pruned"
    (is (= (rf.ssr.hash/canonical-edn [:p "text"])
           (rf.ssr.hash/canonical-edn [:p "text" nil]))
        "trailing nil element pruned")
    (is (= (rf.ssr.hash/canonical-edn [:p "text"])
           (rf.ssr.hash/canonical-edn [:p nil "text" nil]))
        "interior + trailing nil elements pruned"))

  (testing "lists: nil child elements are pruned"
    (is (= (rf.ssr.hash/canonical-edn '(:p "text"))
           (rf.ssr.hash/canonical-edn '(:p "text" nil)))
        "trailing nil element pruned in a list")))

(deftest canonical-edn-returns-nil-for-nil
  (testing "bare nil input — sentinel for parent pruning"
    (is (nil? (rf.ssr.hash/canonical-edn nil))
        "canonical-edn returns nil for nil input — parent's keep/remove prunes it")))

;; ---- render-tree-hash ------------------------------------------------------
;;
;; The shallow nil-attr / nil-child equivalences, the sorted-key order and the
;; total order over str-colliding keys (`:a` beside `":a"`) are pinned with
;; literal hashes on both hosts by `re-frame.hash-parity-test` and
;; `re-frame.ssr.hash-parity-cljs-test`.

(deftest render-tree-hash-prunes-nil-deeply
  (testing "pruning is recursive — nil-pruning applies at every level"
    (is (= (rf.ssr.hash/render-tree-hash
             [:section {:class "wrap"}
              [:header {:role "banner" :hidden nil}
               [:h1 "Title" nil]]
              [:article {:class nil}
               [:p "Body" nil]]])
           (rf.ssr.hash/render-tree-hash
             [:section {:class "wrap"}
              [:header {:role "banner"}
               [:h1 "Title"]]
              [:article {}
               [:p "Body"]]]))
        "a deeply nested tree with nils at multiple levels hashes identically
         to the same tree with all nils pruned")))

;; ===========================================================================
;; :doctype? + :render-hash composition
;; ===========================================================================
;;
;; The two opts interact subtly: hash injection runs on the hiccup root
;; BEFORE stringification, so the doctype prepend lands after
;; the hash attribute has been stamped — `data-rf-render-hash` rides on
;; the root DOM element, not on the doctype declaration. Pin the
;; composition.

(deftest doctype-and-render-hash-compose
  (testing "(render-to-string tree {:doctype? true :render-hash h}) emits
            <!DOCTYPE html> followed by <root data-rf-render-hash=\"...\"> —
            the hash rides on the root element, not on the doctype"
    (let [tree [:div {:class "page"} [:h1 "Hello"]]
          h    (rf.ssr.hash/render-tree-hash tree)
          html (rf.ssr.emit/render-to-string tree {:doctype? true :render-hash h})]
      (is (str/starts-with? html "<!DOCTYPE html>")
          ":doctype? prepended")
      (is (re-find (re-pattern (str "<!DOCTYPE html><div[^>]*data-rf-render-hash=\"" h "\""))
                   html)
          ":render-hash stamped on the root <div>, immediately after the doctype")
      (is (re-find #"data-rf-render-hash=\"[0-9a-f]{8}\"" html)
          "the caller-supplied hash is the 8-hex FNV-1a digest of the tree")
      (is (str/includes? html "<h1>Hello</h1>")
          "body content rendered"))))

(deftest render-hash-without-doctype-yields-bare-root
  (testing ":render-hash / :doctype? false → root element carries the hash;
            no doctype prefix"
    (let [tree [:section [:p "x"]]
          html (rf.ssr.emit/render-to-string tree {:render-hash (rf.ssr.hash/render-tree-hash tree)})]
      (is (not (str/starts-with? html "<!DOCTYPE"))
          "no doctype emitted")
      (is (re-find #"^<section[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                   html)
          "hash attribute on the root element"))))

(deftest doctype-without-render-hash-omits-hash-attribute
  (testing ":doctype? true, no :render-hash → doctype emitted; no hash attr"
    (let [tree [:div "no-hash"]
          html (rf.ssr.emit/render-to-string tree {:doctype? true})]
      (is (str/starts-with? html "<!DOCTYPE html>"))
      (is (not (str/includes? html "data-rf-render-hash"))
          "no hash attribute without :render-hash"))))

(deftest emit-hash-opt-is-inert
  (testing "there is no `:emit-hash?` opt; it is an ordinary unknown key —
            no marker, no throw (the emitter does not validate its opts)"
    (let [tree [:div "x"]]
      (is (= "<div>x</div>" (rf.ssr.emit/render-to-string tree {:emit-hash? true}))
          ":emit-hash? true stamps nothing")
      (is (= "<!DOCTYPE html><div>x</div>"
             (rf.ssr.emit/render-to-string tree {:doctype? true :emit-hash? true}))
          ":emit-hash? does not disturb the other opts"))))

;; ---- raw-fn hiccup heads hash identity-free -------------------------------

(deftest render-tree-hash-drops-raw-fn-identity
  (testing "a raw-fn hiccup head (`[my-component props]`, the
            deref'd defn VALUE idiomatic to Reagent/UIx SSR) has NO
            cross-runtime-stable identity: (.toString fn) is class +
            identity-hashcode on the JVM but the JS source on CLJS. The server
            hashes the raw render tree and the client re-hashes the SAME tree,
            so a fn `.toString` in the canonical EDN would make byte-identical
            HTML hash differently — a spurious :rf.ssr/hydration-mismatch that
            CRASHES under :on-mismatch :hard-error. So every raw fn head
            serialises to the fixed identity-free token `#fn[]`."
    (let [f1 (fn [_] [:span "a"])
          f2 (fn [_] [:span "a"])]
      (is (= "#fn[]" (rf.ssr.hash/canonical-edn f1))
          "a raw fn serialises to the identity-free token, not #fn[<toString>]")
      ;; The decisive property: two DISTINCT fn objects standing for the same
      ;; logical view (server's deref'd defn vs client's) hash identically.
      ;; f1/f2 have different identity-hashcodes in their toString, so a
      ;; toString-bearing canonical EDN → different hashes → false mismatch.
      (is (not= (.toString f1) (.toString f2))
          "sanity: the two fn objects DO have divergent toStrings (the trap)")
      (is (= (rf.ssr.hash/render-tree-hash [:div [f1 {:x 1}]])
             (rf.ssr.hash/render-tree-hash [:div [f2 {:x 1}]]))
          "distinct fn objects for the same tree hash identically — no spurious mismatch")
      ;; The fn's props still discriminate: a different prop → a different hash.
      (is (not= (rf.ssr.hash/render-tree-hash [:div [f1 {:x 1}]])
                (rf.ssr.hash/render-tree-hash [:div [f1 {:x 2}]]))
          "the head is identity-free but the props are still hashed")))
  (testing "a Var head stays identity-stable — a Var is NOT fn? on the JVM, so
            `[#'ns/view …]` falls to :else → pr-str → #'ns/name (identical
            both runtimes)"
    (is (= "#'clojure.core/identity"
           (rf.ssr.hash/canonical-edn #'clojure.core/identity))
        "a Var reference keeps its stable #'ns/name print form")))

;; ---- whole-valued doubles canonicalise cross-runtime ----------------------

(deftest whole-valued-doubles-canonicalise-hash-and-html-consistently
  (testing "a whole-valued double is serialised WITHOUT the
            trailing .0 in BOTH the render-tree hash AND the emitted HTML, so
            the JVM `9.0`/`0.0` match the CLJS `9`/`0` (CLJS unifies 1.0→1).
            The two surfaces MUST agree — child AND attribute position — or
            the hash passes while the server/client DOM diverges (and, under
            :on-mismatch :hard-error, hydration crashes)."
    ;; hash side — canonical EDN drops the .0 for a child AND an attr value.
    (is (= "[:span 9]" (rf.ssr.hash/canonical-edn [:span 9.0]))
        "whole-double child → `9` in the canonical EDN")
    (is (= "[:progress {:max 1,:value 0}]"
           (rf.ssr.hash/canonical-edn [:progress {:value 0.0 :max 1.0}]))
        "whole-double attr values → `0`/`1` in the canonical EDN")
    ;; HTML side — the emitter applies the SAME normalisation, child + attr.
    (is (= "<span>9</span>" (rf.ssr.emit/render-to-string [:span 9.0] {}))
        "whole-valued double CHILD renders `9`, not the JVM `9.0`")
    (let [html (rf.ssr.emit/render-to-string [:progress {:value 0.0 :max 1.0}] {})]
      (is (str/includes? html "value=\"0\"") "whole-double attr renders value=\"0\"")
      (is (str/includes? html "max=\"1\"")   "whole-double attr renders max=\"1\"")
      (is (not (str/includes? html ".0"))    "no trailing .0 leaks into the HTML"))
    ;; integers, non-whole doubles, and ratios behave as documented.
    (is (= "42"   (rf.ssr.hash/canonical-number 42))   "an integer is unchanged")
    (is (= "3.14" (rf.ssr.hash/canonical-number 3.14)) "a non-whole double is unchanged")
    (is (= "0.5"  (rf.ssr.hash/canonical-number 1/2))  "a ratio coerces to its IEEE-double form")
    (is (= "<span>3.14</span>" (rf.ssr.emit/render-to-string [:span 3.14] {}))
        "a non-whole double child is unchanged")))
