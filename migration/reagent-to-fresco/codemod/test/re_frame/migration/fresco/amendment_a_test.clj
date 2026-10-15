(ns re-frame.migration.fresco.amendment-a-test
  "Execute W4 output to check capture timing, return values and hygiene."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.rewrite :as rf.migration.fresco.rewrite]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def cart (atom :v1))
(defn handler [snapshot & _] snapshot)

(def effects (atom []))
(defn make-handler! [] (swap! effects conj :make) (fn [& args] (vec args)))
(defn next-id!     [] (swap! effects conj :next) 7)
(defn log!         [_] (swap! effects conj :log) :logged)

(defn all-args [& xs] (vec xs))

(defn- eval-here
  [form]
  (binding [*ns* (find-ns 're-frame.migration.fresco.amendment-a-test)]
    (eval form)))

(defn- emitted
  [src]
  (let [node (:node (rf.migration.fresco.rewrite/w4-plan (p/parse-string src)))]
    [(eval-here (n/sexpr node)) (n/string node)]))

(deftest a-dereferenced-argument-is-a-snapshot-not-a-live-read
  (testing "capture before invocation"
    (reset! cart :v1)
    (let [[wrapper _] (emitted "(r/partial handler @cart)")]
      (reset! cart :v2)
      (is (= :v1 (wrapper))))))

(deftest every-argument-is-evaluated-once-left-to-right-at-construction
  (testing "evaluate once, left to right"
    (reset! effects [])
    (let [[wrapper _] (emitted "(r/partial (make-handler!) (next-id!) (log! \"x\"))")]
      (is (= [:make :next :log] @effects))
      (wrapper) (wrapper)
      (is (= [:make :next :log] @effects)))))

(deftest the-wrapper-is-return-transparent
  (testing "preserve return value and argument order"
    (let [[wrapper _] (emitted "(r/partial all-args :a :b)")]
      (is (= [:a :b 1 2] (wrapper 1 2))))))

(defn- emitted-under
  [outer src]
  (let [node (:node (rf.migration.fresco.rewrite/w4-plan (p/parse-string src)))]
    [(eval-here (list 'let outer (n/sexpr node))) (n/string node)]))

(def ^:private callee-clash "(r/partial vector :first f__rf2)")
(def ^:private arg-clash    "(r/partial all-args (identity :zeroth) a0__rf2)")
(def ^:private double-clash "(r/partial vector f__rf2 f__rf2__1)")

(deftest a-generated-argument-name-never-shadows-a-local-either
  (testing "avoid shadowing a later initializer"
    (let [[wrapper text] (emitted-under '[a0__rf2 :outer] arg-clash)]
      (is (= [:zeroth :outer] (wrapper))
          (str "argument 1 must still read the site's `a0__rf2`; emitted " text)))))

(deftest the-whole-family-bumps-together-and-keeps-bumping
  (testing "bump the whole name family"
    (let [[_ text] (emitted-under '[f__rf2 :outer] callee-clash)]
      (is (= (str "(let [f__rf2__1 vector a1__rf2__1 f__rf2] "
                  "(fn [& args__rf2__1] (apply f__rf2__1 :first a1__rf2__1 args__rf2__1)))")
             text))))

  (testing "keep searching past an occupied generation"
    (let [[wrapper text] (emitted-under '[f__rf2 :a f__rf2__1 :b] double-clash)]
      (is (= (str "(let [f__rf2__2 vector a0__rf2__2 f__rf2 a1__rf2__2 f__rf2__1] "
                  "(fn [& args__rf2__2] (apply f__rf2__2 a0__rf2__2 a1__rf2__2 args__rf2__2)))")
             text))
      (is (= [:a :b] (wrapper))))))
