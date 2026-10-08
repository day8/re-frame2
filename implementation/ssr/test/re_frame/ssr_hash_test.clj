(ns re-frame.ssr-hash-test
  "Spec 011 §Hydration-mismatch detection — how `render-tree-hash` and the
  emitter treat a Var head, a raw-HTML value and whole-valued doubles, and how
  `:doctype?` and `:render-hash` compose. Nil pruning, key order and the
  cross-host literals are pinned on both hosts by `re-frame.hash-parity-test`
  and `re-frame.ssr.hash-parity-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.hash :as rf.ssr.hash]))

(deftest render-to-string-doctype-and-render-hash-opts
  (testing "the hash rides on the root element, after the doctype"
    (is (= "<!DOCTYPE html><div data-rf-render-hash=\"deadbeef\"><h1>Hello</h1></div>"
           (rf.ssr.emit/render-to-string [:div [:h1 "Hello"]]
                                         {:doctype? true :render-hash "deadbeef"})))))

(deftest a-var-head-keeps-its-stable-print-form
  (testing "a Var is not fn? on the JVM, so it keeps its #'ns/name print form
            (identical on both hosts) instead of collapsing to #fn[]"
    (is (= "#'clojure.core/identity"
           (rf.ssr.hash/canonical-edn #'clojure.core/identity)))))

;; A `.cljc` view writes a raw-HTML prop as React's `{:__html s}` map on the JVM
;; and as the Reagent bridge's tagged value on the client. The tag is CLJS-only,
;; so a value answering `:__html` through `ILookup` stands in for it here;
;; `re-frame.adapter-unsafe-html-ssr-cljs-test` pins the same literal with the
;; real one.

(defn- lookup-html
  [s]
  (reify clojure.lang.ILookup
    (valAt [this k] (.valAt this k nil))
    (valAt [_ k not-found] (if (= :__html k) s not-found))))

(deftest a-raw-html-value-hashes-as-the-map-it-answers-for
  (let [as-map   (fn [s] [:div {:dangerouslySetInnerHTML {:__html s}}])
        as-value (fn [s] [:div {:dangerouslySetInnerHTML (lookup-html s)}])]
    (testing "the map and a value answering :__html hash the same for the same markup"
      (is (= "2525560c"
             (rf.ssr.hash/render-tree-hash (as-map "<b>x</b>"))
             (rf.ssr.hash/render-tree-hash (as-value "<b>x</b>")))))
    (testing "no markup hashes as the map with a nil :__html"
      (is (= (rf.ssr.hash/render-tree-hash (as-map nil))
             (rf.ssr.hash/render-tree-hash (as-value nil)))))
    (testing "a lookup value with no :__html entry keeps its print form"
      (is (= "#foo [1]" (rf.ssr.hash/canonical-edn (tagged-literal 'foo [1])))))
    (testing "the emitter writes the same body for both forms"
      (is (= "<div><b>x</b></div>"
             (rf.ssr.emit/render-to-string (as-map "<b>x</b>") {})
             (rf.ssr.emit/render-to-string (as-value "<b>x</b>") {}))))))

(deftest whole-valued-doubles-canonicalise-hash-and-html-consistently
  (testing "the emitter drops a whole-valued double's trailing .0 in child and
            attribute position, as the hash does (the `whole-double` parity
            fixture), so the JVM's 9.0 renders as CLJS's 9"
    (is (= "<span>9</span>" (rf.ssr.emit/render-to-string [:span 9.0] {})))
    (is (= "<progress value=\"0\" max=\"1\"></progress>"
           (rf.ssr.emit/render-to-string [:progress {:value 0.0 :max 1.0}] {}))))
  (testing "a non-whole double is unchanged and a ratio prints as its double"
    (is (= "<span>3.14</span>" (rf.ssr.emit/render-to-string [:span 3.14] {})))
    (is (= "0.5" (rf.ssr.hash/canonical-number 1/2)))))
