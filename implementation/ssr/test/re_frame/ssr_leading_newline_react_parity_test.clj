(ns re-frame.ssr-leading-newline-react-parity-test
  "The SSR hiccup emitter writes react-dom/server's leading-LF compensation
  on every element and body channel React writes it on, pinned against
  react-dom ITSELF.

  The HTML parser drops the first LF after a `<pre>`, `<listing>` or
  `<textarea>` start tag, so React writes one compensating LF ahead of a body
  that begins with one. Which bodies get it is a fact of react-dom's source,
  per element and per channel: a string child, a textarea's `value` /
  `defaultValue`, and a `<pre>` / `<listing>` `dangerouslySetInnerHTML`
  string. `react_dom_probe/leading_newline.cjs` renders each through the
  INSTALLED react-dom and records the bytes, so this compares the emitter
  with React's output rather than with a restatement of the rule.
  `re-frame.ssr-hiccup-newline-test` shows such bytes parse back to the
  authored text."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]))

(def ^:private fixture-resource "react_dom_probe/leading_newline.edn")

(def ^:private react-evidence
  (delay
    (if-let [url (io/resource fixture-resource)]
      (edn/read-string (slurp url))
      (throw (ex-info (str "the react-dom evidence fixture is not on the test "
                           "classpath — without it every assertion below "
                           "iterates nothing and passes vacuously")
                      {:resource fixture-resource})))))

(defn- hiccup
  "The hiccup an author writes to hand `body` to `element` through `channel`."
  [{:keys [element channel body]}]
  (let [tag (keyword element)]
    (case channel
      "child"         [tag body]
      "inner-html"    [tag {:dangerouslySetInnerHTML {:__html body}}]
      "value"         [tag {:value body}]
      "default-value" [tag {:default-value body}])))

(deftest hiccup-emitter-writes-react-dom-bytes
  (let [{:keys [rows react-dom-version]} @react-evidence]
    (testing "the fixture carries every channel, so no row below is vacuous"
      (is (= #{"child" "inner-html" "value" "default-value"}
             (set (map :channel rows)))))
    (testing "for every row React renders, render-to-string writes React's
              bytes: the compensating LF where React writes it, none where it
              does not, and the body escaped or raw as React writes it"
      (doseq [{:keys [element channel label markup] :as row} rows]
        (is (= markup (rf.ssr.emit/render-to-string (hiccup row)))
            (str "render-to-string diverges from react-dom " react-dom-version
                 " for a " label " body on <" element "> through " channel))))))
