(ns re-frame.conformance-dsl-cljs-test
  "CLJS-side coverage for the conformance handler-body DSL evaluator.
  Mirrors the JVM conformance-dsl-test — the conformance ns is .cljc so
  resolve-value* runs on both hosts; the regression-guard for the
  rf2-xb5o :event-arg / :get-event-arg split must hold on both."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.conformance :as conformance]))

(deftest event-arg-no-key-access-overload
  (testing ":event-arg's 3rd element is unconditionally default-for-nil"
    (let [ctx {:event [:some-id {:foo 99}]}]
      (testing "with a non-nil map arg, returns the arg verbatim"
        (is (= {:foo 99}
               (conformance/resolve-value* [:event-arg 1 :foo] ctx))
            "[:event-arg n :foo] with a map arg must NOT do key-access")))

    (testing "with nil arg, default-val is returned (keyword default works)"
      (let [ctx {:event [:some-id]}]
        (is (= :foo
               (conformance/resolve-value* [:event-arg 1 :foo] ctx)))))

    (testing "with nil arg, non-keyword default-val is returned"
      (let [ctx {:event [:some-id]}]
        (is (= {} (conformance/resolve-value* [:event-arg 1 {}] ctx)))
        (is (= 0  (conformance/resolve-value* [:event-arg 1 0] ctx)))))

    (testing "two-element form [:event-arg n] returns the n-th arg unchanged"
      (let [ctx {:event [:some-id {:foo 99}]}]
        (is (= {:foo 99}
               (conformance/resolve-value* [:event-arg 1] ctx)))))))

(deftest get-event-arg-key-access
  (testing ":get-event-arg extracts a key from a map arg"
    (let [ctx {:event [:some-id {:foo 99 :bar "x"}]}]
      (is (= 99  (conformance/resolve-value* [:get-event-arg 1 :foo] ctx)))
      (is (= "x" (conformance/resolve-value* [:get-event-arg 1 :bar] ctx)))
      (is (= nil (conformance/resolve-value* [:get-event-arg 1 :missing] ctx)))))

  (testing "[:get-event-arg n :key default] uses default for missing/nil"
    (let [ctx {:event [:some-id {:foo 99 :nilval nil}]}]
      (is (= 99       (conformance/resolve-value* [:get-event-arg 1 :foo :fallback] ctx)))
      (is (= :default (conformance/resolve-value* [:get-event-arg 1 :missing :default] ctx)))
      (is (= :default (conformance/resolve-value* [:get-event-arg 1 :nilval :default] ctx))))))
