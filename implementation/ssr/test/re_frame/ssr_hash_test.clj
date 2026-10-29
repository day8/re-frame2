(ns re-frame.ssr-hash-test
  "`re-frame.ssr.hash` — the install ledger's data digest and the numeric
  print form the emitter shares with it — and how the emitter writes a
  raw-HTML value and whole-valued doubles. The digest literals here are the
  same ones `re-frame.hash-check-cljs-test` pins on CLJS, so the two hosts
  agree on them."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.hash :as rf.ssr.hash]))

(deftest render-to-string-doctype-opt
  (testing "the doctype prefixes the body"
    (is (= "<!DOCTYPE html><div><h1>Hello</h1></div>"
           (rf.ssr.emit/render-to-string [:div [:h1 "Hello"]] {:doctype? true})))))

(deftest fnv-1a-32-hashes-utf-8-bytes
  (testing "the hash runs over UTF-8 bytes, so multi-byte content agrees with CLJS"
    (is (= "a82b5049" (rf.ssr.hash/fnv-1a-32 "café")))))

(deftest data-hash-preserves-nil-and-ignores-order
  (testing "the canonical form sorts map keys and set members and keeps nil"
    (is (= "{:a #{1 2},:b [nil 7]}"
           (rf.ssr.hash/canonical-data-edn {:b [nil 7.0] :a #{2 1}}))))
  (testing "the literal CLJS pins for the same value"
    (is (= "0f358b10" (rf.ssr.hash/data-hash {:b [nil 7.0] :a #{2 1}}))))
  (testing "nil is content: each pair differs"
    (is (= [false false false]
           [(= (rf.ssr.hash/data-hash {:x nil}) (rf.ssr.hash/data-hash {}))
            (= (rf.ssr.hash/data-hash [nil 7]) (rf.ssr.hash/data-hash [7]))
            (= (rf.ssr.hash/data-hash #{nil 1}) (rf.ssr.hash/data-hash #{1}))]))))

;; A `.cljc` view writes a raw-HTML prop as React's `{:__html s}` map on the JVM
;; and as the Reagent bridge's tagged value on the client. The tag is CLJS-only,
;; so a value answering `:__html` through `ILookup` stands in for it here.

(defn- lookup-html
  [s]
  (reify clojure.lang.ILookup
    (valAt [this k] (.valAt this k nil))
    (valAt [_ k not-found] (if (= :__html k) s not-found))))

(deftest a-raw-html-value-renders-as-the-map-it-answers-for
  (let [as-map   (fn [s] [:div {:dangerouslySetInnerHTML {:__html s}}])
        as-value (fn [s] [:div {:dangerouslySetInnerHTML (lookup-html s)}])]
    (testing "the emitter writes the same body for both forms"
      (is (= "<div><b>x</b></div>"
             (rf.ssr.emit/render-to-string (as-map "<b>x</b>") {})
             (rf.ssr.emit/render-to-string (as-value "<b>x</b>") {}))))))

(deftest whole-valued-doubles-render-as-cljs-prints-them
  (testing "the emitter drops a whole-valued double's trailing .0 in child and
            attribute position, so the JVM's 9.0 renders as CLJS's 9"
    (is (= "<span>9</span>" (rf.ssr.emit/render-to-string [:span 9.0] {})))
    (is (= "<progress value=\"0\" max=\"1\"></progress>"
           (rf.ssr.emit/render-to-string [:progress {:value 0.0 :max 1.0}] {}))))
  (testing "a non-whole double is unchanged and a ratio prints as its double"
    (is (= "<span>3.14</span>" (rf.ssr.emit/render-to-string [:span 3.14] {})))
    (is (= "0.5" (rf.ssr.hash/canonical-number 1/2)))))

(deftest doubles-spell-as-javascript-prints-them
  (testing "every finite double takes JavaScript's `String(n)` spelling, the
            JVM's scientific notation included (expected values from node)"
    (doseq [[d expected] [[0.0005 "0.0005"]
                          [-1.0E-6 "-0.000001"]
                          [1.0E-7 "1e-7"]
                          [1.2345E-10 "1.2345e-10"]
                          [12345678.5 "12345678.5"]
                          [1.730000000123E9 "1730000000.123"]
                          [1.0E20 "100000000000000000000"]
                          [(Math/pow 2 60) "1152921504606847000"]
                          [1.0E21 "1e+21"]
                          [1.5E300 "1.5e+300"]
                          [(+ 0.1 0.2) "0.30000000000000004"]
                          [-0.0 "0"]]]
      (is (= expected (rf.ssr.hash/canonical-number d)) (pr-str d))))
  (testing "in text and in a style value alike"
    (is (= "<div style=\"width:12500000.5px\">0.0005</div>"
           (rf.ssr.emit/render-to-string [:div {:style {:width 12500000.5}} 0.0005] {})))))

(deftest jvm-only-numbers-render-their-exact-digits
  (testing "a BigDecimal renders its exact digits and a BigInt its plain
            digits, never the reader's M / N suffix; a double and a long are
            the controls"
    (doseq [[n expected] [[19.99M "19.99"]
                          [10.50M "10.50"]
                          [1E+3M "1000"]
                          [42N "42"]
                          [19.99 "19.99"]
                          [42 "42"]]]
      (is (= (str "<span>" expected "</span>")
             (rf.ssr.emit/render-to-string [:span n] {}))
          (pr-str n))))
  (testing "in every position a number reaches: an attribute, a style value,
            and the option match that decides `selected`"
    (is (= "<input value=\"19.99\">"
           (rf.ssr.emit/render-to-string [:input {:value 19.99M}] {})))
    (is (= "<div data-price=\"19.99\">x</div>"
           (rf.ssr.emit/render-to-string [:div {:data-price 19.99M} "x"] {})))
    (is (= "<div style=\"width:10.5px\">x</div>"
           (rf.ssr.emit/render-to-string [:div {:style {:width 10.5M}} "x"] {})))
    (is (= "<select><option value=\"2\" selected>two</option></select>"
           (rf.ssr.emit/render-to-string [:select {:value 2N} [:option {:value 2} "two"]] {})))))
