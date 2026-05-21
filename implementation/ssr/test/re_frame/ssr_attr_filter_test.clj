(ns re-frame.ssr-attr-filter-test
  "Spec 011 §XSS at output boundaries — rule rf2-dwds9: the SSR static-
  markup emitter MUST strip, at attribute-emit time:

    - `on*` event-handler props (matched on the normalised/lower-cased
      name, so `:on-click` / `:onClick` / `:ONLOAD` all filter out),
    - function-valued prop values, and
    - reserved prototype-pollution keys (`__proto__` / `constructor` /
      `prototype`),

  matching react-dom/server behaviour. The filter is the per-attribute
  prop-name position in the locked emitter composition order, so it runs
  ahead of the attribute-name grammar gate (rf2-vl8ir) — a stripped prop
  never reaches `validate-attr-name!`.

  These exercise `re-frame.ssr.html-helpers/attr-string` directly (the
  single shared per-attribute emission point used by the main emitter,
  the head emitter, and the streaming emitter)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.ssr.html-helpers :as html]))

(deftest attr-string-strips-event-handler-props
  (testing "rf2-dwds9 — `on*` event-handler props are dropped at emit time.
            Matched forms are the two canonical handler-prop spellings the
            re-frame hiccup adapters and react-dom/server recognise:
            camelCase `on[A-Z]…` and kebab `on-…`."
    (testing ":on-click (kebab) is stripped"
      (is (= " id=\"x\""
             (html/attr-string {:on-click "alert(1)" :id "x"}))))

    (testing ":onClick (camelCase) is stripped"
      (is (= " id=\"x\""
             (html/attr-string {:onClick "alert(1)" :id "x"}))))

    (testing ":onMouseDown (camelCase, multi-word) is stripped"
      (is (= " id=\"x\""
             (html/attr-string {:onMouseDown "alert(1)" :id "x"}))))

    (testing "`true`-valued on* boolean prop is also stripped (no bare attr)"
      (is (= " id=\"x\""
             (html/attr-string {:on-load true :id "x"}))))

    (testing "a non-handler attribute starting with the letters `on`
              survives — only `on[A-Z]` / `on-` discriminate, so innocuous
              English-word keys like `one` / `once` / `online` are NOT eaten"
      (let [out (html/attr-string {:data-on "ok" :one "1" :once "2" :online "3"})]
        (is (str/includes? out "data-on=\"ok\""))
        (is (str/includes? out "one=\"1\""))
        (is (str/includes? out "once=\"2\""))
        (is (str/includes? out "online=\"3\""))))

    (testing "a map whose every entry is a stripped on* prop yields the
              empty string — no stray leading space"
      (is (= "" (html/attr-string {:on-click "f" :onScroll "g"}))))))

(deftest attr-string-strips-function-valued-props
  (testing "rf2-dwds9 — function-valued props have no HTML serialisation
            and are dropped (a fn can only be a handler/callback)"
    (is (= " id=\"x\""
           (html/attr-string {:title (fn [_] :handler) :id "x"})))

    (testing "fn value is stripped even when the key itself is innocuous"
      (is (= ""
             (html/attr-string {:data-cb (fn [] nil)}))))))

(deftest attr-string-drops-prototype-pollution-keys
  (testing "rf2-dwds9 — reserved prototype-pollution keys are dropped
            before they reach the host createElement-equivalent"
    (doseq [k ["__proto__" "constructor" "prototype"]]
      (testing (str "`" k "` is dropped")
        (is (= " id=\"x\""
               (html/attr-string {(keyword k) "polluted" :id "x"}))
            (str k " must not survive to wire output"))))

    (testing "the match is case-insensitive on the normalised name"
      (is (= " id=\"x\""
             (html/attr-string {(keyword "Constructor") "polluted" :id "x"}))))))

(deftest attr-string-normal-attrs-still-emit
  (testing "the filter does not over-reach — ordinary attrs round-trip"
    (let [out (html/attr-string {:id "main" :class "a b" :data-x "1"})]
      (is (str/includes? out "id=\"main\""))
      (is (str/includes? out "class=\"a b\""))
      (is (str/includes? out "data-x=\"1\""))))

  (testing "stripped props are filtered BEFORE the grammar gate — a prop
            that would otherwise throw `:rf.error/ssr-invalid-attribute-name`
            is silently dropped rather than raising"
    (is (= " id=\"x\""
           (html/attr-string {(keyword "onClick=alert(1) data-x") "v"
                              :id "x"})))))
