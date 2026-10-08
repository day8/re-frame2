(ns re-frame.async-fixture-platform-shape-cljs-test
  "On the JVM, `make-reset-runtime-fixture`'s `:async? true` still returns the
  fn-form (docs/api/re-frame.test-support.md §Async): `clojure.test` calls its
  fixtures and a map is callable, so a `{:before …}` fixture would compose to
  a key lookup, skip every test body, and leave the namespace reporting
  `Ran 0 tests` and green. That is why `.cljc` suites can write a plain
  `:async? true`.

  The check drives a separately built fixture through
  `clojure.test/join-fixtures`, the composition `test-vars` itself uses,
  rather than registering it on this namespace, where a regression would
  silently skip the very assertion that catches it. On CLJS the option
  returns the `{:before :after}` map every `(async done …)` suite needs in
  order to run at all, so this namespace has no CLJS test."
  #?(:clj (:require [clojure.test :refer [deftest is join-fixtures]]
                    [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
                    [re-frame.test-support :as rf.test-support])))

#?(:clj
   (deftest jvm-async-capable-fixture-actually-runs-the-test-body
     (let [ran? (atom false)]
       ((join-fixtures [(rf.test-support/make-reset-runtime-fixture
                          {:adapter rf.substrate.plain-atom/adapter
                           :async?  true})])
        #(reset! ran? true))
       (is (true? @ran?)
           "the body ran; a map fixture here would have skipped it"))))
