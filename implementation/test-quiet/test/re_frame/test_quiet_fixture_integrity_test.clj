(ns re-frame.test-quiet-fixture-integrity-test
  "The rule `re-frame.test-quiet.runner/uncallable-fixtures` states, and the
  defect it exists to make impossible (rf2-4yw1).

  `cljs.test` accepts a MAP fixture — `(use-fixtures :each {:before f
  :after g})` — and this repo's `*_cljs_test.cljs` files use it throughout.
  `clojure.test` validates nothing: it folds whatever `use-fixtures` was
  handed into a chain and calls it with the test thunk.  A map called with
  one argument is a KEY LOOKUP, returns nil, and the thunk never runs.  Maps
  are `IFn`, so nothing throws; the namespace contributes zero tests and the
  lane exits 0.

  THE RULE IS ABOUT CALLING, NOT ABOUT `fn?` (rf2-4yw1).  `clojure.test`'s
  requirement is behavioural — the entry is applied to the thunk and must
  INVOKE it — and both cheap approximations of that are wrong in a different
  direction.  `ifn?` is too permissive: maps, sets, keywords and symbols are
  all `IFn` and every one of them treats the thunk as a KEY.  `fn?` is too
  restrictive: a Var, a multimethod and a `reify`d `IFn` each invoke the
  thunk correctly while carrying no `clojure.lang.Fn` marker, so
  `(use-fixtures :each #'lifecycle)` — ordinary, idiomatic, and working —
  was refused with the false claim that the namespace ran nothing.  Both
  directions are pinned below, and each is pinned against
  `clojure.test/join-fixtures` FIRST.

  WHY THE CALLABLE ROWS ARE NOT A LIST, AND MUST NOT BECOME ONE.  Two
  earlier rounds repaired this rule by ADDING an accepted type — `fn?`, then
  `fn?` or `MultiFn` or a Var resolving to one — and each list was refuted
  by the next valid callable somebody wrote.  The population of things that
  apply their argument is open; the population Clojure invokes as a LOOKUP
  is closed, so the rule now subtracts the second from `ifn?`.  The
  `reify` row below is therefore not a fourth entry on a list: it is the
  witness that no list is being kept.

  EVERY TEST HERE PINS THE DEFECT BEFORE IT PINS THE GUARD.  The defect is
  pinned against `clojure.test/join-fixtures` itself — the function
  `test-all-vars` builds its fixture chain with — so a guard that went on
  agreeing with itself after clojure.test changed underneath would be
  caught.  The end-to-end half (a real `deftest` that would FAIL, absent
  from the tally, under the real `-main`, for both the map literal and a
  symbol bound to a map) is pinned across a process boundary in
  `re-frame.test-quiet-runner-contract-test`.

  A NOTE ON THE PROBE NAMESPACES BELOW.  `uncallable-fixtures` reads
  `(all-ns)` in the shipped call, so a probe namespace left behind with a
  map fixture would red THIS artefact's own lane.  `with-probe-ns` removes
  it in a `finally` for that reason, not for tidiness."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.test-quiet.runner :as rf.test-quiet.runner]))

;; ----------------------------------------------------------------------
;; Fixtures

(def ^:private map-fixture
  "The cljs.test form. Legitimate there; uncallable on the JVM."
  {:before (fn [])
   :after  (fn [])})

(defn- lifecycle
  "An ordinary fixture function, referred to below as `#'lifecycle` — the
  idiomatic Var form the guard used to refuse (rf2-4yw1)."
  [t]
  (t))

(defmulti ^:private multi-lifecycle
  "A `defmulti` fixture: `clojure.lang.MultiFn` is not `fn?` either, and it
  invokes the thunk just as a plain fn does."
  (fn [_t] :only))

(defmethod multi-lifecycle :only [t] (t))

(def ^:private reified-lifecycle
  "A fixture that is nothing but the `IFn` contract — no `clojure.lang.Fn`
  marker, no `MultiFn`, no Var.  It is the shape that refuted the round-two
  repair (rf2-4yw1): a positive list of implementation classes cannot name
  it, because any `reify`, `deftype` or `proxy` produces a fresh class."
  (reify clojure.lang.IFn
    (invoke [_ t] (t))))

(defn- calls-thunk?
  "Whether `clojure.test/join-fixtures` — the chain `test-all-vars` builds —
  actually INVOKES the test thunk when handed `fixture`.  This is the real
  contract, and every assertion below is graded against it rather than
  against the guard's opinion of it."
  [fixture]
  (let [ran? (atom false)]
    ((clojure.test/join-fixtures [fixture]) #(reset! ran? true))
    @ran?))

(defn- with-probe-ns
  "Create `ns-sym`, give it `fixtures` under `meta-key` exactly as
  `clojure.test/use-fixtures` writes them (the ARGS seq, not the first of
  them), hand the namespace to `f`, then remove it."
  [ns-sym meta-key fixtures f]
  (let [ns-obj (doto (create-ns ns-sym)
                 (alter-meta! assoc meta-key fixtures))]
    (try
      (f ns-obj)
      (finally
        (remove-ns ns-sym)))))

;; ----------------------------------------------------------------------
;; The defect, pinned against clojure.test itself.

(deftest a-map-fixture-swallows-the-test-thunk
  (testing "THE DEFECT: `join-fixtures` — what `test-all-vars` builds its
            chain with — calls each fixture with the test thunk, and a map
            called at arity 1 is a key lookup that returns nil"
    (let [ran? (atom false)
          thunk #(reset! ran? true)]
      (is (nil? ((clojure.test/join-fixtures [map-fixture]) thunk))
          "the map returns nil rather than invoking the chain")
      (is (false? @ran?)
          (str "the whole bug: the test thunk is never called, nothing"
               " throws (maps are IFn), and the namespace reports zero"
               " tests. Measured on Clojure 1.12: a single `deftest`"
               " asserting (= 1 2) under this fixture reports `Ran 0 tests"
               " containing 0 assertions. / 0 failures, 0 errors.` and"
               " exits 0."))))

  (testing "the fn form runs the thunk, which is the only difference"
    (let [ran? (atom false)
          fn-fixture (fn [t] (t))]
      ((clojure.test/join-fixtures [fn-fixture]) #(reset! ran? true))
      (is (true? @ran?)))))

;; ----------------------------------------------------------------------
;; The rule.

(deftest a-map-fixture-is-named-under-either-key
  (doseq [meta-key [:clojure.test/each-fixtures :clojure.test/once-fixtures]]
    (with-probe-ns 'probe.map-fixture-ns meta-key (list map-fixture)
      (fn [ns-obj]
        (let [offenders (rf.test-quiet.runner/uncallable-fixtures [ns-obj])]
          (is (= [['probe.map-fixture-ns meta-key map-fixture]] offenders)
              (str "both `use-fixtures` metadata keys are read; got "
                   (pr-str offenders))))))))

(deftest a-symbol-bound-to-a-map-is-named-too
  (testing "the case a static scan cannot see: `use-fixtures` writes the
            VALUE it was handed, so a map reaching the metadata through a
            var is indistinguishable from a map literal here"
    (let [lifecycle map-fixture]
      (with-probe-ns 'probe.bound-fixture-ns :clojure.test/each-fixtures
        (list lifecycle)
        (fn [ns-obj]
          (is (= 1 (count (rf.test-quiet.runner/uncallable-fixtures
                            [ns-obj])))))))))

(deftest function-fixtures-are-not-offenders
  (testing "the guard must not red the honest form — a bare fn, several fns
            from one call, and a namespace with no fixtures at all"
    (with-probe-ns 'probe.fn-fixture-ns :clojure.test/each-fixtures
      (list (fn [t] (t)) (fn [t] (t)))
      (fn [ns-obj]
        (is (= [] (rf.test-quiet.runner/uncallable-fixtures [ns-obj])))))
    (is (= [] (rf.test-quiet.runner/uncallable-fixtures [(the-ns 'clojure.core)])))
    (is (= [] (rf.test-quiet.runner/uncallable-fixtures [])))))

;; ----------------------------------------------------------------------
;; The other direction: callable entries `fn?` does not recognise (rf2-4yw1).

(deftest callable-fixtures-that-are-not-fn-are-not-offenders
  (testing "THE DEFECT THIS TIME IS THE GUARD'S: a Var, a `defmulti` and a
            bare `reify`d `IFn` all RUN the test, and `fn?` says false of
            every one of them, so `fn?` cannot be the contract — nor can any
            longer list of classes, which is what the last row proves"
    (doseq [[label fixture] [["a Var referring to a function" #'lifecycle]
                             ["a multimethod"                 multi-lifecycle]
                             ["a Var referring to a multimethod"
                              #'multi-lifecycle]
                             ["a custom IFn implementation"   reified-lifecycle]
                             ["a Var referring to a custom IFn"
                              #'reified-lifecycle]]]
      (is (false? (fn? fixture))
          (str label " is not `fn?` — which is why the guard refused it"))
      (is (true? (calls-thunk? fixture))
          (str label " nevertheless invokes the test thunk, so"
               " `clojure.test` runs the namespace's tests normally"))
      (with-probe-ns 'probe.callable-fixture-ns :clojure.test/each-fixtures
        (list fixture)
        (fn [ns-obj]
          (is (= [] (rf.test-quiet.runner/uncallable-fixtures [ns-obj]))
              (str "and so the guard must accept " label)))))))

(deftest a-var-referring-to-a-map-is-still-an-offender
  (testing "a Var is an INDIRECTION, not a licence: `Var.invoke` forwards to
            its root, so a Var whose root is a map swallows the thunk exactly
            as the bare map does and must stay refused"
    (let [holder    (create-ns 'probe.var-to-map-holder)
          var-to-map (intern holder 'lifecycle map-fixture)]
      (try
        (is (false? (calls-thunk? var-to-map))
            "the thunk is swallowed through the Var just as it is directly")
        (with-probe-ns 'probe.var-to-map-ns :clojure.test/each-fixtures
          (list var-to-map)
          (fn [ns-obj]
            (is (= 1 (count (rf.test-quiet.runner/uncallable-fixtures
                              [ns-obj])))
                "accepting every Var would reopen the defect one level down")))
        (finally
          (remove-ns 'probe.var-to-map-holder))))))

(defn- never-calls-thunk?
  "Whether `join-fixtures` fails to run the thunk for `fixture` — either by
  looking it up and discarding it (a map, set, keyword or symbol returns
  nil) or by throwing on the lookup (a vector needs a numeric index).  The
  two paths differ; the property the rule is about is the one they share."
  [fixture]
  (try
    (not (calls-thunk? fixture))
    (catch Throwable _ true)))

(deftest every-lookup-value-is-refused-though-ifn?-is-true-of-each
  (testing "THE TRAP that makes bare `ifn?` the wrong repair, and the reason
            the rule subtracts a CLOSED set from it rather than widening to
            it: each of these five is `IFn`, each is invoked as a LOOKUP, and
            each must stay refused (rf2-4yw1)"
    (doseq [[label fixture] [["a map"     {:before (fn []) :after (fn [])}]
                             ["a set"     #{:before :after}]
                             ["a vector"  [(fn [t] (t))]]
                             ["a keyword" :before]
                             ["a symbol"  'lifecycle]]]
      (is (true? (ifn? fixture))
          (str label " is `IFn`, so `ifn?` would admit it"))
      (is (true? (never-calls-thunk? fixture))
          (str label " nevertheless never runs the test thunk"))
      (with-probe-ns 'probe.lookup-fixture-ns :clojure.test/each-fixtures
        (list fixture)
        (fn [ns-obj]
          (is (= 1 (count (rf.test-quiet.runner/uncallable-fixtures [ns-obj])))
              (str "and so the guard must keep refusing " label)))))))

(deftest this-lane-is-itself-clean
  (testing "the shipped call — every namespace this JVM has loaded — and so
            a live control that the rule holds on real code, not only on
            probes"
    (is (= [] (rf.test-quiet.runner/uncallable-fixtures (all-ns))))))

;; ----------------------------------------------------------------------
;; The complaint.

(deftest the-complaint-names-the-namespace-and-the-repair
  (with-probe-ns 'probe.complaint-ns :clojure.test/each-fixtures
    (list map-fixture)
    (fn [ns-obj]
      (let [[[ns-sym meta-key fixture]]
            (rf.test-quiet.runner/uncallable-fixtures [ns-obj])]
        (is (= 'probe.complaint-ns ns-sym))
        (is (= :clojure.test/each-fixtures meta-key))
        (is (map? fixture)
            "the offending value travels with the complaint, so the message
             can name what it is rather than only that it is wrong")))))
