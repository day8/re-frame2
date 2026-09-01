(ns re-frame.ssr.form2-arity-cljs-test
  "rf2-mocn3 (audit) — the CROSS-HOST arity contract for a Form-2 inner
  render fn, as a single table asserted on BOTH hosts.

  The whole reason `re-frame.ssr.emit/invoke-form-2-render-fn` selects an
  arity at all is that a shared `.cljc` Form-2 component must resolve the
  same way on the JVM server and in the browser. A JVM-only proof cannot
  see the defect this namespace exists to pin, because the defect IS a
  disagreement between the hosts: the JVM helper used to walk the inner's
  declared arities downward and take the longest accepted PREFIX, which is
  not what a compiled ClojureScript fn does.

    - A fn with a SINGLE fixed arity and no variadic tail compiles to a bare
      JavaScript function. JS drops extra arguments, so it renders.
    - Anything with more than one arm compiles to a dispatcher that switches
      on `arguments.length` and throws `Invalid arity: n` when no arm matches.

  So `(fn ([x] …) ([x y] …))` applied to three args, and `(fn ([] …) ([x] …))`
  applied to two, are REJECTED on the client while the prefix walk selected
  arity 2 and arity 1 for them and rendered happily. A component like that
  server-rendered fine and blew up on hydration.

  Every row below therefore asserts the SAME expectation on both hosts —
  either exact rendered bytes or `::arity-rejected`. That is the contract:
  not \"the JVM is lenient\", but \"the JVM agrees with CLJS\".

  Runs on BOTH hosts (`.cljc`, `-cljs-test` ns): `clojure -M:test` from
  `implementation/ssr` (JVM) and `npm run test:cljs` (node). The streaming
  consumer of the same shared resolver is asserted alongside the sync one in
  the JVM-only `re-frame.ssr-emit-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as emit]))

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
    (emit/emit-element el)
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

;; ---------------------------------------------------------------------------
;; The table
;; ---------------------------------------------------------------------------

(deftest form-2-inner-arity-selection-agrees-across-hosts
  (testing "rf2-mocn3 — a SINGLE-fixed-arity inner is the only lenient shape:
            it compiles to a bare JS function, so extra args are dropped"
    (is (= "<p>single|a</p>" (outcome [single "a" "b" "c"]))
        "an inner taking a PREFIX of the outer's args renders on both hosts")
    (is (= "<p>single|a</p>" (outcome [single "a"]))
        "the same inner at its exact arity renders on both hosts"))

  (testing "rf2-mocn3 — a MULTI-arity inner dispatches on the argument count
            and REJECTS a count no arm declares. This is the audit finding:
            the JVM used to select the longest prefix and render"
    ;; Compiled CLJS: `Invalid arity: 3`. The old JVM walk chose arity 2 and
    ;; returned `<p>m2|a|b</p>` — a server render the client cannot reproduce.
    (is (= ::arity-rejected (outcome [multi-1-2 "a" "b" "c"]))
        "a 1-or-2-arity inner handed 3 args is refused on both hosts")
    ;; Compiled CLJS: `Invalid arity: 2`. The old JVM walk chose arity 1.
    (is (= ::arity-rejected (outcome [multi-0-1 "a" "b"]))
        "a 0-or-1-arity inner handed 2 args is refused on both hosts"))

  (testing "rf2-mocn3 — non-vacuity: the SAME multi-arity inners still render
            through every arm they DO declare, so the rows above pin the
            rejection and not a blanket refusal of multi-arity inners"
    (is (= "<p>m2|a|b</p>" (outcome [multi-1-2 "a" "b"]))
        "the exact 2-arity arm is selected")
    (is (= "<p>m1|a</p>" (outcome [multi-1-2 "a"]))
        "the exact 1-arity arm is selected")
    (is (= "<p>m1|a</p>" (outcome [multi-0-1 "a"]))
        "the exact 1-arity arm is selected")
    (is (= "<p>m0</p>" (outcome [multi-0-1]))
        "the exact 0-arity arm is selected"))

  (testing "rf2-mocn3 — a satisfied VARIADIC arm receives the whole arg list,
            never a truncated prefix and never a fabricated zero-arity call"
    (is (= "<p>v|a,b,c</p>" (outcome [var-only "a" "b" "c"]))
        "a purely variadic inner is handed every arg")
    (is (= "<p>v|</p>" (outcome [var-only]))
        "a purely variadic inner handed nothing renders its empty case")
    (is (= "<p>mxv|a|b|c</p>" (outcome [mixed-1-var "a" "b" "c"]))
        "a fixed+variadic inner routes an over-fixed count to the variadic arm")
    (is (= "<p>mx1|a</p>" (outcome [mixed-1-var "a"]))
        "the same inner routes an exactly-matching count to its fixed arm")))
