(ns re-frame.conformance-dsl-test
  "Focused unit tests for the conformance handler-body DSL evaluator.

  The conformance corpus runner (conformance-test) covers the DSL
  end-to-end via real fixtures. These tests target individual DSL
  shapes — specifically the rf2-xb5o split between :event-arg
  (default-for-nil only) and :get-event-arg (key-access)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.conformance :as conformance]))

;; ---- :event-arg / :get-event-arg split (rf2-xb5o / rf2-pz9f) -------------
;;
;; Per Mike's resolution of rf2-pz9f:
;;   [:event-arg n]                  — n-th event arg
;;   [:event-arg n default-val]      — n-th event arg, default-val if nil
;;                                     (UNCONDITIONAL: no type-dispatch even
;;                                     when default-val is a keyword and the
;;                                     n-th arg is a map.)
;;   [:get-event-arg n :key]         — key-access into n-th event arg
;;   [:get-event-arg n :key default] — key-access with default if missing/nil
;;
;; The regression-guard below ensures we never re-introduce the prior
;; type-dispatch overload where a keyword 3rd arg + map value silently
;; meant "(get value keyword)" instead of "default-for-nil".

(deftest event-arg-no-key-access-overload
  (testing ":event-arg's 3rd element is unconditionally default-for-nil"
    ;; Event: [:some-id {:foo 99}]. The map is the 1st event arg
    ;; (index 1; index 0 is the event-id).
    (let [ctx {:event [:some-id {:foo 99}]}]
      (testing "with a non-nil map arg, returns the arg verbatim"
        ;; Pre-rf2-xb5o: this returned 99 (key-access overload).
        ;; Post-rf2-xb5o: the keyword 3rd arg is a default-for-nil and
        ;; never fires because the value is non-nil — so the map is
        ;; returned as-is.
        (is (= {:foo 99}
               (conformance/resolve-value* [:event-arg 1 :foo] ctx))
            "[:event-arg n :foo] with a map arg must NOT do key-access")))

    (testing "with nil arg, default-val is returned (keyword default works)"
      ;; The arg at index 1 doesn't exist (event has only :some-id).
      ;; The keyword default-val IS returned because v is nil.
      (let [ctx {:event [:some-id]}]
        (is (= :foo
               (conformance/resolve-value* [:event-arg 1 :foo] ctx))
            "[:event-arg n :foo] with nil arg returns :foo as default-for-nil")))

    (testing "with nil arg, non-keyword default-val is returned"
      (let [ctx {:event [:some-id]}]
        (is (= {} (conformance/resolve-value* [:event-arg 1 {}] ctx)))
        (is (= 0  (conformance/resolve-value* [:event-arg 1 0] ctx)))
        (is (= [] (conformance/resolve-value* [:event-arg 1 []] ctx)))))

    (testing "two-element form [:event-arg n] returns the n-th arg unchanged"
      (let [ctx {:event [:some-id {:foo 99}]}]
        (is (= {:foo 99}
               (conformance/resolve-value* [:event-arg 1] ctx))))
      (let [ctx {:event [:some-id]}]
        (is (= nil (conformance/resolve-value* [:event-arg 1] ctx)))))))

(deftest get-event-arg-key-access
  (testing ":get-event-arg extracts a key from a map arg"
    (let [ctx {:event [:some-id {:foo 99 :bar "x"}]}]
      (is (= 99  (conformance/resolve-value* [:get-event-arg 1 :foo] ctx)))
      (is (= "x" (conformance/resolve-value* [:get-event-arg 1 :bar] ctx)))
      (is (= nil (conformance/resolve-value* [:get-event-arg 1 :missing] ctx)))))

  (testing "[:get-event-arg n :key default] uses default for missing/nil"
    (let [ctx {:event [:some-id {:foo 99 :nilval nil}]}]
      (is (= 99       (conformance/resolve-value* [:get-event-arg 1 :foo :fallback] ctx))
          "present non-nil value is returned (default ignored)")
      (is (= :default (conformance/resolve-value* [:get-event-arg 1 :missing :default] ctx))
          "missing key falls back to default")
      (is (= :default (conformance/resolve-value* [:get-event-arg 1 :nilval :default] ctx))
          "explicit nil value also falls back to default")))

  (testing "[:get-event-arg n :key] on a nil arg returns nil"
    (let [ctx {:event [:some-id]}]
      (is (= nil (conformance/resolve-value* [:get-event-arg 1 :foo] ctx))))))
