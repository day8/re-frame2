(ns re-frame.ssr.ring.facade-examples-test
  "The façade docstring examples stay copyable: the `ssr-handler` example's
  opts pass construction validation, and the `ssr-middleware` example is
  curried and carries the required `:payload`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.lifecycle :as rf.ssr.ring.lifecycle]))

(defn- example-forms
  "The forms read from the text after `Example:` in `v`'s docstring."
  [v]
  (let [doc (:doc (meta v))
        rdr (java.io.PushbackReader.
              (java.io.StringReader.
                (subs doc (+ (str/index-of doc "Example:") (count "Example:")))))]
    (into []
          (take-while #(not= ::eof %))
          (repeatedly #(try (read rdr false ::eof) (catch Exception _ ::eof))))))

(defn- some-form
  "The first form, depth-first through `forms`, satisfying `pred`."
  [pred forms]
  (some (fn walk [form]
          (or (when (pred form) form)
              (when (coll? form) (some walk form))))
        forms))

(defn- call-to? [fn-name]
  #(and (seq? %) (symbol? (first %)) (= fn-name (name (first %)))))

(deftest ssr-handler-docstring-example-includes-payload-and-constructs
  (let [opts (second (some-form (call-to? "ssr-handler")
                                (example-forms #'rf.ssr.ring/ssr-handler)))]
    (is (= opts (rf.ssr.ring.lifecycle/validate-construction-opts! opts)))))

(deftest ssr-middleware-docstring-example-is-curried-and-includes-payload
  (let [forms (example-forms #'rf.ssr.ring/ssr-middleware)
        call  (some-form (call-to? "ssr-middleware") forms)]
    (is (contains? (second call) :payload))
    (is (some-form #(and (seq? %) (= call (first %))) forms)
        "`(ssr-middleware opts)` is applied to a handler: `((ssr-middleware opts) h)`")))
