(ns day8.re-frame2-xray.panels.overflow-indicator-cljs-test
  "Tests for the shared overflow-indicator hiccup.

  The overflow indicator is the contract surface the user sees when
  the 200-row panel-cap drops rows. Tests pin: nil-when-not-over-cap,
  the testid pattern (`rf-xray-<panel-id>-overflow-indicator`), and
  that the hidden-count is rendered in the row."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-xray.panels.overflow-indicator :as overflow]))

(defn- testid
  "Pull the data-testid out of a hiccup node — first attribute map."
  [hiccup]
  (some-> hiccup second :data-testid))

(defn- hiccup-text
  "Walk a hiccup vector collecting strings — handy for asserting the
  user-visible text without caring about layout."
  [node]
  (cond
    (string? node) node
    (vector? node) (apply str (map hiccup-text (rest node)))
    (sequential? node) (apply str (map hiccup-text node))
    :else ""))

(deftest overflow-row-returns-nil-when-not-over-cap
  (is (nil? (overflow/overflow-row {:panel-id     "trace"
                                    :over-cap?    false
                                    :hidden-count 0}))))

(deftest overflow-row-renders-when-over-cap
  (let [node (overflow/overflow-row {:panel-id     "trace"
                                     :over-cap?    true
                                     :hidden-count 137})]
    (is (= [:li "rf-xray-trace-overflow-indicator"] [(first node) (testid node)]))
    (is (re-find #"\+137 rows hidden" (hiccup-text node))
        "the indicator surfaces the hidden count as +N")))
