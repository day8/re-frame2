(ns re-frame.schemas-sensitive-path-cljs-test
  "CLJS host-parity half of the sensitive closed-map extra-key scrub.
  `sanitize-sensitive-path` is pure and shared CLJC, so both
  runtimes assert the SAME corpus
  (`re-frame.schemas.walker-sanitize-path-fixtures`) against the SAME entry
  point — a CLJS divergence from the JVM privacy behaviour is
  caught here. The JVM half (`re-frame.schemas-sensitive-test`) additionally
  covers the end-to-end `validate-app-schema!` `:path` / `:reason` /
  whole-trace egress.

  The corpus is pure vector-form data (no compiled schemas), so it loads
  identically under `:node-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.schemas.walker :as rf.schemas.walker]
            [re-frame.schemas.walker-sanitize-path-fixtures :as rf.schemas.walker-sanitize-path-fixtures]))

(deftest cljs-sanitize-closed-map-extra-key-shared-corpus
  (testing "the shared sanitize-sensitive-path corpus holds on CLJS
            (closed-map extra keys scrub; declared locators survive;
            set / :map-of / ambiguous-tail cases scrub as on the JVM)"
    (doseq [{:keys [desc schema in expected]} rf.schemas.walker-sanitize-path-fixtures/cases]
      (is (= expected (rf.schemas.walker/sanitize-sensitive-path schema in))
          desc))))
