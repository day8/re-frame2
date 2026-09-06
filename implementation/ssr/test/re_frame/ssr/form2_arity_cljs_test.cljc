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

  Most rows below therefore assert the SAME expectation on both hosts —
  either exact rendered bytes or `::arity-rejected`.

  TWO ROWS DO NOT, AND THAT IS THE POINT OF THEM (rf2-mocn3, mayor ruling
  2026-09-01). The hosts agree on arm SELECTION and on EXCESS arguments, and
  they DIVERGE on MISSING ones: JavaScript binds an absent parameter to
  `undefined` and CLJS renders, while the JVM raises `ArityException` and SSR
  fails. That divergence is deliberate — see
  `re-frame.ssr.emit/invoke-form-2-render-fn`, THE SUPPORTED CONTRACT — so
  those two rows carry a per-host expectation and record what each host
  actually does. A table whose stated contract is host agreement has to show
  where agreement STOPS, or it advertises the same false promise the prose
  used to.

  So the contract this table pins is not \"the JVM is lenient\", and no
  longer the flat \"the JVM agrees with CLJS\" it once claimed: it is \"the
  JVM agrees with CLJS on which arm runs and on extra args, and refuses —
  loudly, on purpose — where CLJS would silently supply `undefined`\".

  Runs on BOTH hosts (`.cljc`, `-cljs-test` ns): `clojure -M:test` from
  `implementation/ssr` (JVM) and `npm run test:cljs` (node). The streaming
  consumer of the same shared resolver is asserted alongside the sync one in
  the JVM-only `re-frame.ssr-emit-test`."
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

;; The two MISSING-argument shapes, per the ruling: a single fixed arity of
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

(deftest form-2-inner-missing-arguments-diverge-across-hosts
  (testing "rf2-mocn3 (mayor ruling 2026-09-01) — MISSING arguments are the
            ONE place the hosts disagree, and the rows record what each host
            actually does rather than a parity the code does not hold.
            JavaScript binds an absent parameter to `undefined` and the CLJS
            render proceeds; the JVM raises `ArityException` and SSR fails.
            The JVM is the STRICTER host here, deliberately — a fabricated
            nil prop ships an author's arity mistake as production HTML,
            where a loud server failure shows it. Do not `fix` either side
            to make these rows agree; that would be the change the ruling
            refused"
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

  (testing "rf2-mocn3 — non-vacuity: the SAME two inners render identically
            on both hosts at every count they DO accept, so the rows above
            pin the missing-argument divergence and not a JVM that has
            simply stopped rendering these shapes"
    (is (= "<p>fixed2|a|b</p>" (outcome [fixed-2 "a" "b"]))
        "the fixed-arity-2 inner at its exact arity agrees across hosts")
    (is (= "<p>fv|a|b|</p>" (outcome [fixed-var "a" "b"]))
        "the fixed+variadic inner at exactly its required count agrees")
    (is (= "<p>fv|a|b|c</p>" (outcome [fixed-var "a" "b" "c"]))
        "the fixed+variadic inner above its required count agrees, whole
         arg list handed to the variadic arm"))

  ;; `undefined` is load-bearing in the contract wording and is NOT
  ;; interchangeable with nil, but the RENDERED BYTES above cannot tell them
  ;; apart: `(str x)` is "" for both, and `nil?` answers true for both
  ;; because it compiles to `== null`. `undefined?` is a `===` against
  ;; `void 0`, which is what discriminates — so pin the slot itself, on the
  ;; host where the call actually happens.
  #?(:cljs
     (testing "rf2-mocn3 — the client's missing slot is genuinely
               `js/undefined`, not a nil the client fabricated"
       (let [seen (atom nil)
             spy  (fn [a b]
                    (reset! seen {:a-undefined? (undefined? a)
                                  :b-undefined? (undefined? b)})
                    [:p "x"])]
         (apply spy ["a"])
         (is (false? (:a-undefined? @seen))
             "the argument that WAS passed is not undefined")
         (is (true? (:b-undefined? @seen))
             "the argument that was NOT passed is undefined")
         (is (false? (undefined? nil))
             "control: `undefined?` really discriminates — nil is not
              undefined under it, though `nil?` is true of both")))))
