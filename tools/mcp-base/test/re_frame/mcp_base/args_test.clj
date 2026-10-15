(ns re-frame.mcp-base.args-test
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.args :as rf.mcp-base.args]))

(deftest parse-boolean-coerces-wire-values
  (are [raw default expected] (= expected (rf.mcp-base.args/parse-boolean raw default))
    true    false true
    false   true  false
    nil     true  true
    nil     false false
    "TRUE"  false true
    "false" true  false
    :true   false true
    :false  true  false
    "maybe" true  true
    "maybe" false false
    42      true  true))

(deftest parse-positive-int-coerces-within-the-safe-integer-window
  (are [raw expected] (= expected (rf.mcp-base.args/parse-positive-int raw 50))
    nil                          50
    "  12  "                     12
    "+12"                        12
    "-5"                         1
    "12abc"                      50 ; a raw js/parseInt would read 12
    "99999999999999999999999999" 50
    "9007199254740991"           9007199254740991
    "9007199254740992"           50
    2.9                          2
    0.5                          1
    9007199254740991             9007199254740991
    ##Inf                        50
    ##NaN                        50
    1.0E20                       50))

(deftest fresh-keyword-coerces-agent-ids
  (are [raw expected] (= expected (rf.mcp-base.args/fresh-keyword raw))
    :foo                    :foo
    ":foo"                  :foo
    "rf.assert/path-equals" :rf.assert/path-equals
    nil                     nil
    ""                      nil
    42                      nil))

(deftest fresh-keyword-checked-gates-on-shape-and-length
  (let [shape-ok? (fn [[ns _]] (= ns "story.x"))]
    (are [raw expected] (= expected (rf.mcp-base.args/fresh-keyword-checked raw shape-ok?))
      "story.x/ok"                            :story.x/ok
      "other/ok"                              nil
      :story.x/already                        :story.x/already
      :other/already                          nil
      (apply str "story.x/" (repeat 600 "z")) nil)))

(deftest fresh-keyword-checked-nil-and-non-string-return-nil
  (are [raw] (nil? (rf.mcp-base.args/fresh-keyword-checked raw (constantly true)))
    nil
    42))

(deftest parse-mode-resolves-against-the-allowlist
  (are [raw expected] (= expected (rf.mcp-base.args/parse-mode raw :diff #{:diff :full}))
    :full    :full
    "full"   :full
    ":full"  :full
    nil      :diff
    "maybe"  :diff
    :unknown :diff))

(deftest safe-keyword-admits-only-allowlisted-ids
  (are [raw allowed expected] (= expected (rf.mcp-base.args/safe-keyword raw allowed))
    :diff    #{:diff :full} :diff
    :other   #{:diff :full} nil
    "diff"   #{:diff :full} :diff
    ":diff"  #{:diff :full} :diff
    "rf/foo" #{:rf/foo}     :rf/foo
    nil      #{:diff :full} nil
    ""       #{:diff :full} nil
    42       #{:diff :full} nil))

(deftest rejected-agent-strings-intern-nothing
  ;; Rejected strings must not enter the JVM keyword table.
  (is (nil? (rf.mcp-base.args/safe-keyword "args-test-novel-bare" #{:diff :full})))
  (is (nil? (rf.mcp-base.args/safe-keyword "args-test-novel-ns/novel" #{:rf/foo})))
  (is (= :diff (rf.mcp-base.args/parse-mode "args-test-novel-mode" :diff #{:diff :full})))
  (is (nil? (rf.mcp-base.args/fresh-keyword-checked "args-test-other-ns/novel"
                                                     (fn [[ns _]] (= ns "story.x")))))
  (is (= [nil nil nil nil]
         [(find-keyword "args-test-novel-bare")
          (find-keyword "args-test-novel-ns" "novel")
          (find-keyword "args-test-novel-mode")
          (find-keyword "args-test-other-ns" "novel")])))
