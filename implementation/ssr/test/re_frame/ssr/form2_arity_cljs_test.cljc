(ns re-frame.ssr.form2-arity-cljs-test
  "The CROSS-HOST arity contract for a Form-2 inner render fn, as one table
  asserted on BOTH hosts (`.cljc`, `-cljs-test` ns).

  A shared `.cljc` Form-2 component must resolve the same way on the JVM
  server and in the browser, and compiled ClojureScript is the reference:

    - A fn with a SINGLE fixed arity and no variadic tail compiles to a bare
      JavaScript function. JS drops extra arguments, so it renders.
    - Anything with more than one arm compiles to a dispatcher that switches
      on `arguments.length` and throws `Invalid arity: n` when no arm matches.

  So `(fn ([x] …) ([x y] …))` applied to three args is REJECTED on the
  client, where a JVM that took the longest accepted prefix would render it
  — a component that server-renders and then fails on hydration.

  The hosts agree on arm SELECTION and on EXCESS arguments, and DIVERGE on
  MISSING ones: JavaScript binds an absent parameter to `undefined` and CLJS
  renders, while the JVM raises `ArityException` and SSR fails, on purpose
  (`re-frame.ssr.emit/invoke-form-2-render-fn`, THE SUPPORTED CONTRACT). The
  divergence rows carry a per-host expectation, so the table shows where
  agreement stops."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]))

;; ---------------------------------------------------------------------------
;; Outcome helper
;; ---------------------------------------------------------------------------

(defn- arity-rejection?
  "Is `e` the host's own refusal to invoke a fn at the requested arity?

  Deliberately host-specific and deliberately NARROW. The JVM raises
  `clojure.lang.ArityException`; the CLJS multi-arity dispatcher raises a
  plain `js/Error` whose message is `Invalid arity: n`. Anything else — a
  malformed-hiccup `ExceptionInfo`, a NullPointerException from a typo in a
  fixture — must NOT read as the expected rejection, which is why `outcome`
  rethrows what this rejects."
  [e]
  #?(:clj  (instance? clojure.lang.ArityException e)
     :cljs (and (instance? js/Error e)
                (string? (.-message e))
                (str/includes? (.-message e) "Invalid arity"))))

(defn- outcome
  "Render `el` through the sync emitter; return the HTML, or
  `::arity-rejected` when the HOST refused the inner's invocation arity.
  Any other throwable propagates — a row must never pass on the wrong
  failure."
  [el]
  (try
    (rf.ssr.emit/emit-element el)
    (catch #?(:clj Throwable :cljs :default) e
      (if (arity-rejection? e)
        ::arity-rejected
        (throw e)))))

;; ---------------------------------------------------------------------------
;; Fixtures — Form-2 components whose OUTER is variadic (it is not under
;; test) and whose INNER carries the arity shape each row exercises.
;; ---------------------------------------------------------------------------

(defn- form-2
  "A Form-2 component: a variadic outer returning `inner`."
  [inner]
  (fn [& _] inner))

(def ^:private single      (form-2 (fn [x] [:p (str "single|" x)])))
(def ^:private multi-1-2   (form-2 (fn ([x]   [:p (str "m1|" x)])
                                        ([x y] [:p (str "m2|" x "|" y)]))))
(def ^:private multi-0-1   (form-2 (fn ([]  [:p "m0"])
                                        ([x] [:p (str "m1|" x)]))))
(def ^:private var-only    (form-2 (fn [& xs] [:p (str "v|" (str/join "," xs))])))
(def ^:private mixed-1-var (form-2 (fn ([a] [:p (str "mx1|" a)])
                                        ([a b & r]
                                         [:p (str "mxv|" a "|" b "|"
                                                  (str/join "," r))]))))

;; The two MISSING-argument shapes, per the contract: a single fixed arity of
;; two, and a fixed+variadic arm requiring two with no shorter arm to fall
;; back on. Both are invoked below their required count by the rows that use
;; them.
(def ^:private fixed-2   (form-2 (fn [a b] [:p (str "fixed2|" a "|" b)])))
(def ^:private fixed-var (form-2 (fn [a b & r]
                                   [:p (str "fv|" a "|" b "|"
                                            (str/join "," r))])))

;; ---------------------------------------------------------------------------
;; The table
;; ---------------------------------------------------------------------------

(deftest form-2-inner-arity-selection-agrees-across-hosts
  (testing "a SINGLE-fixed-arity inner is the only lenient shape:
            it compiles to a bare JS function, so extra args are dropped"
    (is (= "<p>single|a</p>" (outcome [single "a" "b" "c"]))
        "an inner taking a PREFIX of the outer's args renders on both hosts"))

  (testing "a MULTI-arity inner dispatches on the argument count
            and REJECTS a count no arm declares — a JVM that selected the
            longest prefix would render instead"
    ;; Compiled CLJS: `Invalid arity: 3`. A prefix walk would choose arity 2
    ;; and return `<p>m2|a|b</p>` — a server render the client cannot reproduce.
    (is (= ::arity-rejected (outcome [multi-1-2 "a" "b" "c"]))
        "a 1-or-2-arity inner handed 3 args is refused on both hosts")
    ;; Compiled CLJS: `Invalid arity: 2`. A prefix walk would choose arity 1.
    (is (= ::arity-rejected (outcome [multi-0-1 "a" "b"]))
        "a 0-or-1-arity inner handed 2 args is refused on both hosts"))

  (testing "non-vacuity: a multi-arity inner renders through an arm it
            DOES declare, so the rows above pin the rejection and not a
            blanket refusal of multi-arity inners"
    (is (= "<p>m2|a|b</p>" (outcome [multi-1-2 "a" "b"]))
        "the exact 2-arity arm is selected"))

  (testing "a satisfied VARIADIC arm receives the whole arg list,
            never a truncated prefix and never a fabricated zero-arity call"
    (is (= "<p>v|a,b,c</p>" (outcome [var-only "a" "b" "c"]))
        "a purely variadic inner is handed every arg")
    (is (= "<p>mxv|a|b|c</p>" (outcome [mixed-1-var "a" "b" "c"]))
        "a fixed+variadic inner routes an over-fixed count to the variadic arm")))

(deftest form-2-inner-missing-arguments-diverge-across-hosts
  (testing "MISSING arguments are the
            ONE place the hosts disagree, and the rows record what each host
            actually does rather than a parity the code does not hold.
            JavaScript binds an absent parameter to `undefined` and the CLJS
            render proceeds; the JVM raises `ArityException` and SSR fails.
            The JVM is the STRICTER host here, deliberately — a fabricated
            nil prop ships an author's arity mistake as production HTML,
            where a loud server failure shows it. Do not `fix` either side
            to make these rows agree; that would change the supported
            contract"
    ;; Case 1 — a SINGLE fixed arity of two, handed one arg.
    (is (= #?(:clj ::arity-rejected :cljs "<p>fixed2|a|</p>")
           (outcome [fixed-2 "a"]))
        "a single-fixed-arity-2 inner at 1 arg: CLJS renders with the second
         slot missing; the JVM refuses")
    ;; Case 2 — a fixed+variadic arm requiring two, handed one arg. CLJS
    ;; routes to the variadic arm regardless, leaving `b` missing and the
    ;; rest seq empty.
    (is (= #?(:clj ::arity-rejected :cljs "<p>fv|a||</p>")
           (outcome [fixed-var "a"]))
        "a fixed+variadic inner below its required count: CLJS routes to the
         variadic arm with the missing slot absent; the JVM refuses"))

  (testing "non-vacuity: the fixed+variadic inner renders identically on
            both hosts at exactly its required count, so the rows above pin
            the missing-argument divergence and not a JVM that has stopped
            rendering the shape"
    (is (= "<p>fv|a|b|</p>" (outcome [fixed-var "a" "b"]))
        "the fixed+variadic inner at exactly its required count agrees")))
