(ns day8.re-frame2-xray.theme.section-cljs-test
  "Pure-data tests for the shared section-row primitive."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.theme.section :as section]))

(defn- nodes [tree] (filter vector? (tree-seq vector? seq tree)))

(defn- testids [tree] (set (keep (comp :data-testid second) (nodes tree))))

(defn- header-text [tree]
  (->> (nodes tree)
       (some #(when (= "t-header" (:data-testid (second %))) %))
       flatten
       (filter string?)))

(defn- render [opts body]
  (section/section-row (merge {:label "X" :testid "t"} opts) body))

(deftest header-and-body-follow-expanded-and-count
  (testing "`:expanded?` (default true) picks the glyph and whether the
            body mounts at all; `:count*` renders as (N) after the label."
    (doseq [[opts header ids]
            [[{}                 ["▼" "X"]       #{"t" "t-header" "t-body" "payload"}]
             [{:expanded? false} ["▶" "X"]       #{"t" "t-header"}]
             [{:count* 3}        ["▼" "X" "(3)"] #{"t" "t-header" "t-body" "payload"}]]]
      (let [out (render opts [:span {:data-testid "payload"} "p"])]
        (is (= [header ids] [(header-text out) (testids out)])
            (pr-str opts))))))

(deftest container-style-carries-padding-and-dotted-rule
  (testing "`:container-padding` defaults to 8px 12px and managed_fx_template
            overrides it; the dotted bottom rule separates stacked sections."
    (doseq [[opts padding] [[{} "8px 12px"]
                            [{:container-padding "8px 0"} "8px 0"]]]
      (is (= {:padding       padding
              :border-bottom "1px dotted var(--rf-xray-border-subtle)"}
             (:style (second (render opts "b"))))
          (pr-str opts)))))
