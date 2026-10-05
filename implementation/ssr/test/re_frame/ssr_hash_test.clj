(ns re-frame.ssr-hash-test
  "Spec 011 §Hydration-mismatch detection — canonical-edn / render-tree-hash
  contract. The hash crosses the wire between server and client of the same
  implementation; any structural-equivalence rule the spec pins MUST be honoured
  on both sides or apps see spurious `:rf.ssr/hydration-mismatch` traces.

  Per Spec 011 §Hydration-mismatch detection (line 295):
    'FNV-1a 32-bit over a canonical EDN serialisation of the render-tree
     (depth-first traversal; attribute maps in sorted-key order; nil pruned).'

  This file pins the **nil pruning** rule on `canonical-edn`. Without it,
  the ubiquitous
  `{:class (when condition? :selected)}` shape produces `{:class nil}` on
  one side and `{}` on the other, and the trees hash differently despite
  being structurally equivalent. It also pins how a Var head and
  whole-valued doubles canonicalise, and how `:doctype?` and `:render-hash`
  compose in the emitted HTML.

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

;; ===========================================================================
;; :doctype? + :render-hash composition
;; ===========================================================================
;;
;; The two opts interact subtly: hash injection runs on the hiccup root
;; BEFORE stringification, so the doctype prepend lands after
;; the hash attribute has been stamped — `data-rf-render-hash` rides on
;; the root DOM element, not on the doctype declaration. Pin the
;; composition.

(deftest render-to-string-doctype-and-render-hash-opts
  (testing "each opts map emits exactly this HTML: `:doctype?` prefixes the
            document, `:render-hash` stamps `data-rf-render-hash` on the root
            element — never on the doctype — and `:emit-hash?` is an
            ordinary unknown key, which stamps nothing and throws nothing
            (the emitter does not validate its opts)"
    (doseq [[label tree opts expected]
            [[":doctype? with :render-hash — the hash rides on the root element, right after the doctype"
              [:div [:h1 "Hello"]] {:doctype? true :render-hash "deadbeef"}
              "<!DOCTYPE html><div data-rf-render-hash=\"deadbeef\"><h1>Hello</h1></div>"]
             [":render-hash alone — the root carries the hash and no doctype is emitted"
              [:section [:p "x"]] {:render-hash "deadbeef"}
              "<section data-rf-render-hash=\"deadbeef\"><p>x</p></section>"]
             [":doctype? alone — the doctype and no hash attribute"
              [:div "no-hash"] {:doctype? true}
              "<!DOCTYPE html><div>no-hash</div>"]
             [":emit-hash? true stamps nothing"
              [:div "x"] {:emit-hash? true}
              "<div>x</div>"]
             [":emit-hash? does not disturb the other opts"
              [:div "x"] {:doctype? true :emit-hash? true}
              "<!DOCTYPE html><div>x</div>"]]]
      (is (= expected (rf.ssr.emit/render-to-string tree opts))
          label))))

;; ---- a Var head keeps its print form ---------------------------------------
;;
;; A raw fn head serialises to the identity-free token `#fn[]`, and the props
;; beside it still hash. Both are pinned with literal hashes on both hosts by
;; the `fn-head-child` parity fixture and by
;; `re-frame.ssr.streaming-component-cljs-test/one-tree-hashes-identically-for-both-hosts`.

(deftest a-var-head-keeps-its-stable-print-form
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
    ;; hash side — canonical EDN drops the .0. The attr position is pinned on
    ;; both hosts by the `whole-double` parity fixture.
    (is (= "[:span 9]" (rf.ssr.hash/canonical-edn [:span 9.0]))
        "whole-double child → `9` in the canonical EDN")
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
