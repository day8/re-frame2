(ns re-frame.mcp-base.args-test
  "Tests for the shared MCP argument-coercion helpers."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.args :as rf.mcp-base.args]))

(deftest parse-boolean-coerces-wire-values
  ;; Booleans pass through; the recognised string spellings (any case)
  ;; and the keywords `:true` / `:false` coerce; nil and anything
  ;; unrecognised take the default.
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
  ;; Both hosts share one domain, the JS safe-integer window. A value
  ;; outside it, a non-finite number, or a string with anything but an
  ;; optional sign and digits takes the default instead of throwing or
  ;; truncating to a real value. The CLJS mirror is in cljs_branches_cljs_test.
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
  ;; A keyword passes through, a string (bare or namespaced, with or
  ;; without a leading colon) becomes a keyword, and nil, a blank string
  ;; or any other shape returns nil.
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
  (let [shape-ok? (constantly true)]
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked nil shape-ok?)))
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked 42 shape-ok?)))
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked [:foo] shape-ok?)))))

(deftest parse-mode-resolves-against-the-allowlist
  ;; A keyword or string naming an allowed mode resolves to it, with or
  ;; without a leading colon; nil and anything outside the allowlist take
  ;; the default.
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
  ;; The DoS gate: a string an allowlist or a grammar rejects must not cost
  ;; a JVM keyword intern. Each spelling below appears nowhere else.
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
