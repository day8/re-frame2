(ns day8.re-frame2-causa.panels.overflow-indicator-cljs-test
  "Tests for the shared overflow-indicator hiccup (rf2-1k5r1).

  The overflow indicator is the contract surface the user sees when
  the 200-row panel-cap drops rows. Tests pin: nil-when-not-over-cap,
  the testid pattern (`rf-causa-<panel-id>-overflow-indicator`), and
  that the hidden-count is rendered in the row."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]))

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
                                    :hidden-count 0})))
  (testing "nil over-cap? also yields nil — no indicator rendered"
    (is (nil? (overflow/overflow-row {:panel-id     "trace"
                                      :over-cap?    nil
                                      :hidden-count 5})))))

(deftest overflow-row-renders-when-over-cap
  (let [node (overflow/overflow-row {:panel-id     "trace"
                                     :over-cap?    true
                                     :hidden-count 42})]
    (is (vector? node))
    (is (= :li (first node)))
    (is (= "rf-causa-trace-overflow-indicator" (testid node)))))

(deftest overflow-row-renders-hidden-count
  (let [node (overflow/overflow-row {:panel-id     "issues"
                                     :over-cap?    true
                                     :hidden-count 137})
        text (hiccup-text node)]
    (is (re-find #"\+137" text)
        "the indicator surfaces the hidden count as +N")
    (is (re-find #"rows hidden" text)
        "pluralised when >1 hidden")))

(deftest overflow-row-singularises-for-one-hidden
  (let [node (overflow/overflow-row {:panel-id     "subs"
                                     :over-cap?    true
                                     :hidden-count 1})
        text (hiccup-text node)]
    (is (re-find #"row hidden" text)
        "single-row case avoids the trailing 's'")
    (is (not (re-find #"rows hidden" text)))))

(deftest overflow-row-panel-id-flows-into-testid
  (let [ids ["trace" "issues" "mcp" "performance" "event-detail"
             "subscriptions" "effects" "flows"]]
    (doseq [pid ids]
      (let [node (overflow/overflow-row {:panel-id     pid
                                         :over-cap?    true
                                         :hidden-count 1})]
        (is (= (str "rf-causa-" pid "-overflow-indicator")
               (testid node))
            (str "panel-id " pid " produces matching testid"))))))

;; ---- capped-list --------------------------------------------------------

(defn- ul-children
  "Skip the `[:ul attrs ...]` head and return the row sequence."
  [ul]
  (drop 2 ul))

(deftest capped-list-under-cap-renders-no-overflow-indicator
  (let [rows (mapv (fn [i] {:id i}) (range 50))
        ul   (overflow/capped-list
               rows
               {:panel-id "trace"
                :ul-attrs {:data-testid "rf-causa-trace-feed"}
                :row-fn   (fn [{:keys [id]}]
                            [:li {:data-testid (str "row-" id)} id])})]
    (is (= :ul (first ul)))
    (is (= "rf-causa-trace-feed" (testid ul)))
    (is (= 50 (count (ul-children ul)))
        "all rows render under the cap; no overflow row appended")
    (testing "no overflow indicator slot"
      (let [rendered (ul-children ul)]
        (is (every? (fn [n] (not= "rf-causa-trace-overflow-indicator"
                                  (testid n)))
                    rendered))))))

(deftest capped-list-over-cap-appends-overflow-indicator
  (let [n    (+ 50 250)
        rows (mapv (fn [i] {:id i}) (range n))
        ul   (overflow/capped-list
               rows
               {:panel-id "issues"
                :ul-attrs {:data-testid "rf-causa-issues-feed"}
                :row-fn   (fn [{:keys [id]}]
                            [:li {:data-testid (str "row-" id)} id])})
        children (ul-children ul)]
    (is (= 200 (dec (count children)))
        "200 capped rows + 1 overflow indicator (cap drops to 200)")
    (is (= "rf-causa-issues-overflow-indicator"
           (testid (last children)))
        "overflow indicator is the last child of the [:ul]")))

(deftest capped-list-passes-ul-attrs-verbatim
  (let [attrs  {:data-testid "rf-causa-mcp-feed"
                :style       {:background "purple"}
                :class       "custom-class"}
        ul     (overflow/capped-list
                 [] {:panel-id "mcp"
                     :ul-attrs attrs
                     :row-fn   identity})]
    (is (= attrs (second ul))
        "the caller's :ul-attrs reach the rendered [:ul] unchanged")))
