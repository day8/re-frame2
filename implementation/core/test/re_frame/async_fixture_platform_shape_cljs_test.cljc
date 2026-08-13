(ns re-frame.async-fixture-platform-shape-cljs-test
  "Platform-shape contract for `make-reset-runtime-fixture`'s `:async?` option
  (rf2-e8ea), per Spec 008 §Test-support.

  `:async?` declares the suite ASYNC-CAPABLE. Which SHAPE delivers that is
  decided by the factory, per host, because the two runners disagree about
  what a fixture is:

    • `cljs.test` classifies an ns's fixtures (`{:map :async :fn :sync}`) and
      a `:sync` ns whose body returns an async object throws \"Async tests
      require fixtures to be specified as maps.  Testing aborted.\" from
      OUTSIDE per-test accounting — it unwinds the whole bundle, so every
      namespace after it silently never runs. Async on CLJS needs the map.
    • `clojure.test` has NO map-fixture support. `compose-fixtures` INVOKES
      each fixture — `(fn [g] (f1 (fn [] (f2 g))))` — and a Clojure map is
      `IFn`, so a `{:before …}` fixture composes to a key lookup returning
      nil and the test body NEVER RUNS. The namespace reports \"Ran 0 tests\"
      and reads GREEN. And `clojure.test` has no async tests to be capable
      OF, so the fn-form IS the correct async-capable JVM shape.

  WHY THIS NAMESPACE DOES NOT REGISTER THE `:async?` FIXTURE ITSELF. The JVM
  failure it guards is a SILENT SKIP, so a suite that registered the shape
  under test would, on regression, skip these very assertions and report
  green with a smaller count. Its own `:each` fixture is therefore the plain
  default fn-form, and every shape claim below is made by DRIVING a
  separately-built fixture through `clojure.test`'s own composition path.
  Regressing the guard reds this file loudly rather than emptying it.

  The end-to-end CLJS proof — a real `(async done …)` row draining a bare
  `dispatch-sync` under the map shape — lives in
  `re-frame.async-reset-fixture-cljs-test` and is unaffected by this file.

  Named `*-cljs-test` so the shadow-cljs `:node-test` build (ns-regexp
  `cljs-test$`) discovers it; the `-test` suffix also satisfies the JVM
  cognitect test-runner, so this one `.cljc` file runs on both runtimes —
  which is the point, since the contract IS the difference between them."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures join-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- async-capable-fixture []
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :async?  true}))

;; ---- 1. `:async? true` returns the shape THIS host can actually run -------

(deftest async-opt-in-returns-the-hosts-usable-shape
  (testing ":async? true selects the shape the running host's test runner can use"
    (let [fx (async-capable-fixture)]
      #?(:clj
         (is (fn? fx)
             "on the JVM :async? true returns the fn-form — clojure.test has no async tests and no map-fixture support, so the map would be an IFn key lookup that silently skips every test")
         :cljs
         (do
           (is (map? fx)
               "on CLJS :async? true returns the {:before :after} map — the only shape cljs.test will run an (async done …) row under")
           (is (fn? (:before fx)) "…with a zero-arg :before")
           (is (fn? (:after fx))  "…and a zero-arg :after"))))))

;; ---- 2. the JVM anti-silent-swallow control ------------------------------
;;
;; Composed through `clojure.test/join-fixtures` — the exact call
;; `clojure.test/test-vars` makes — so this asserts against the runner's own
;; behaviour rather than a restatement of it. Under a map-on-JVM regression
;; `join-fixtures` composes to a key lookup, the body never runs, `ran?`
;; stays false, and this test fails LOUDLY.

#?(:clj
   (deftest jvm-async-capable-fixture-actually-runs-the-test-body
     (testing "clojure.test's own join-fixtures runs the body under an :async? true fixture"
       (let [ran?   (atom false)
             landed (atom nil)]
         ((join-fixtures [(async-capable-fixture)])
          (fn []
            (reset! ran? true)
            (rf/reg-event :afps/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
            (rf/dispatch-sync [:afps/inc])
            (reset! landed (:n (rf/app-db-value :rf/default)))))
         (is (true? @ran?)
             "the test body RAN — a map fixture here would compose to a nil-returning key lookup and skip it, leaving the namespace at \"Ran 0 tests\" and GREEN")
         (is (= 1 @landed)
             "and it ran with the fixture's ambient scope established: a bare dispatch-sync drained into :rf/default")))))

;; ---- 3. the rf2-pn3d ruling is not disturbed ------------------------------
;;
;; rf2-pn3d ruled that the DEFAULT stays the fn-form on both hosts (~479 CLJS
;; suites compose it alongside sibling fn fixtures, and three call sites
;; invoke it as a function). rf2-e8ea changes only what `:async? true` means.

(deftest default-shape-is-still-the-fn-form-on-both-hosts
  (testing "omitting :async? still yields a callable fn-form fixture on every host (rf2-pn3d)"
    (let [fx (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})]
      (is (fn? fx)
          "the default shape did not flip — sibling fn-fixture composition and fixture-as-a-function call sites still hold")
      (let [ran? (atom false)]
        (fx (fn [] (reset! ran? true)))
        (is (true? @ran?) "and invoking it as a function runs the body")))))
