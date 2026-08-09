(ns re-frame.migration.hicasso.amendment-a-test
  "**AMENDMENT (A), pinned by EXECUTION rather than by text.**

  The golden corpus pins text and only text, and the design is candid
  about why (§9.7): the corpus is a JVM harness over source text while the
  destination is a browser runtime, so a corpus case cannot render.

  W4 is the one rewrite where that limit bites, because its correction is
  entirely about **when** something is evaluated. A text assertion can
  confirm the `let` was written; it cannot confirm the `let` does what a
  `let` is here to do. But W4's OUTPUT is plain Clojure — `let`, `fn`,
  `apply`, with no `r/partial` left in it — so this JVM can `eval` it and
  ask the runtime question directly.

  The callee and argument forms below are ordinary vars in this
  namespace, so the emitted text resolves against them exactly as a
  consumer's would resolve against theirs.

  Each test runs the emitted wrapper AND the design's stated wrapper on
  the same inputs. The second assertion in each pair is what makes the
  first mean something: it shows the landed design's shape actually
  diverges here, so the amendment is load-bearing rather than defensive."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.hicasso.rewrite :as rw]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;; ---------------------------------------------------------------------------
;; The rig — plain vars, so the emitted source resolves naturally
;; ---------------------------------------------------------------------------

(def cart (atom :v1))
(defn handler [snapshot & _] snapshot)

(def effects (atom []))
(defn make-handler! [] (swap! effects conj :make) (fn [& args] (vec args)))
(defn next-id!     [] (swap! effects conj :next) 7)
(defn log!         [_] (swap! effects conj :log) :logged)

(defn pair [a b] [a b])
(defn all-args [& xs] (vec xs))

(defn- eval-here
  "Evaluate an emitted form with this namespace's vars in scope."
  [form]
  (binding [*ns* (find-ns 're-frame.migration.hicasso.amendment-a-test)]
    (eval form)))

(defn- emitted
  "Run W4 over a literal `(r/partial …)` call; return `[wrapper text]`."
  [src]
  (let [node (:node (rw/w4-plan (p/parse-string src)))]
    [(eval-here (n/sexpr node)) (n/string node)]))

(defn- naive
  "The design's stated rewrite, `(fn [& args] (apply f a … args))`, built
  from the same call — the shape amendment (A) replaced."
  [src]
  (let [els  (rw/elements (p/parse-string src))
        body (str/join " " (map n/string (rest els)))]
    (eval-here (read-string (str "(fn [& args] (apply " body " args))")))))

;; ---------------------------------------------------------------------------
;; Witness 1 — the dereferenced snapshot
;; ---------------------------------------------------------------------------

(def ^:private snapshot-src "(r/partial handler @cart)")

(deftest a-dereferenced-argument-is-a-snapshot-not-a-live-read
  (testing "`(r/partial handler @cart)` read `@cart` ONCE, when the prop
            was built. `make-partial-fn` stored the evaluated arguments in
            a `PartialFn`; nothing re-read them per invocation."
    (reset! cart :v1)
    (let [[wrapper _] (emitted snapshot-src)]
      (is (= :v1 (wrapper)) "the snapshot taken at construction")
      (reset! cart :v2)
      (is (= :v1 (wrapper))
          "STILL the snapshot: the deref sits in the `let`, so changing the
           atom afterwards cannot reach through the wrapper")
      (is (= :v1 (wrapper)) "and it stays put across further invocations")))

  (testing "the design's stated shape diverges here, which is why the
            amendment exists"
    (reset! cart :v1)
    (let [w (naive snapshot-src)]
      (is (= :v1 (w)))
      (reset! cart :v2)
      (is (= :v2 (w))
          "the naive wrapper re-derefs on every invocation, silently
           turning a snapshot into a live read — the exact class of change
           this tool exists to delete"))))

;; ---------------------------------------------------------------------------
;; Witness 2 — effect count and ordering
;; ---------------------------------------------------------------------------

(def ^:private effectful-src "(r/partial (make-handler!) (next-id!) (log! \"x\"))")

(deftest every-argument-is-evaluated-once-left-to-right-at-construction
  (testing "the callee and both arguments run EXACTLY ONCE, in source
            order, when the prop is built"
    (reset! effects [])
    (let [[wrapper _] (emitted effectful-src)]
      (is (= [:make :next :log] @effects)
          "left to right, at prop-evaluation time — before the wrapper is
           minted, which is what `make-partial-fn` did")
      (wrapper) (wrapper) (wrapper)
      (is (= [:make :next :log] @effects)
          "and NOT AGAIN: three invocations added no effects")))

  (testing "the design's stated shape re-runs all three per invocation"
    (reset! effects [])
    (let [w (naive effectful-src)]
      (is (= [] @effects) "nothing has run yet — the first divergence")
      (w)
      (is (= [:make :next :log] @effects))
      (w)
      (is (= [:make :next :log :make :next :log] @effects)
          "a second click ran every effect a second time: a `next-id!`
           that minted one id per prop now mints one per click"))))

;; ---------------------------------------------------------------------------
;; Law 2 — return transparency
;; ---------------------------------------------------------------------------

(deftest the-wrapper-is-return-transparent
  (testing "whatever `f` returned before, it returns now (design Law 2).
            This is why W4 cannot blank a render prop even at a prop that
            IS a render prop — a `(r/partial render-cell ctx)` at Fluent's
            `onRenderCell` keeps returning its element."
    (let [[wrapper _] (emitted "(r/partial pair 1)")]
      (is (= [1 2] (wrapper 2)))))

  (testing "bound arguments come first and invocation arguments after, in
            that order — `apply f a … args`"
    (let [[wrapper _] (emitted "(r/partial all-args :a :b)")]
      (is (= [:a :b 1 2] (wrapper 1 2))))))

;; ---------------------------------------------------------------------------
;; The written shape
;; ---------------------------------------------------------------------------

(deftest the-emitted-shape-is-hygienic-and-minimal
  (testing "self-evaluating arguments are INLINED, not bound: their
            evaluation has nothing to observe and a binding for one is
            pure noise"
    (let [[_ text] (emitted "(r/partial handler :id 7 \"s\")")]
      (is (= "(let [f__rf2 handler] (fn [& args__rf2] (apply f__rf2 :id 7 \"s\" args__rf2)))"
             text))))

  (testing "a SYMBOL callee is bound even though it looks atomic:
            re-reading a var on each invocation picks up a redefinition
            the donor's captured value would never have seen"
    (let [[_ text] (emitted "(r/partial handler)")]
      (is (= "(let [f__rf2 handler] (fn [& args__rf2] (apply f__rf2 args__rf2)))" text))))

  (testing "every argument that is not self-evaluating is bound, in source
            order, and every binding carries the `__rf2` suffix"
    (let [[_ text] (emitted "(r/partial (make-handler!) (next-id!) (log! \"x\"))")]
      (is (= (str "(let [f__rf2 (make-handler!) a0__rf2 (next-id!) a1__rf2 (log! \"x\")] "
                  "(fn [& args__rf2] (apply f__rf2 a0__rf2 a1__rf2 args__rf2)))")
             text)))))

;; ---------------------------------------------------------------------------
;; Hygiene — the generated names are FRESH against the site
;; ---------------------------------------------------------------------------
;;
;; The `__rf2` suffix is a convention, not a guarantee, and the first shipped
;; version of this rewrite mistook one for the other. A consumer may already
;; have a local spelled `f__rf2`; `let` binds SEQUENTIALLY; so a generated
;; binding that shadows one silently rebinds every LATER initializer that
;; referred to it. That is a silent behaviour change — the only fatal class —
;; and it is what these witnesses pin.
;;
;; Each eval runs the emitted form inside an outer `let` binding the colliding
;; name, which is exactly the shape the consumer file presents.

(defn- emitted-under
  "W4's output for `src`, evaluated inside an outer `let` binding `outer`.
  Returns `[wrapper text]`."
  [outer src]
  (let [node (:node (rw/w4-plan (p/parse-string src)))]
    [(eval-here (list 'let outer (n/sexpr node))) (n/string node)]))

(defn- source-symbols
  "Every symbol in `src`, read with `read-string` rather than through the
  fixer — so the freshness pin cannot agree with the implementation by
  sharing its idea of what a symbol is."
  [src]
  (->> (read-string src) (tree-seq coll? seq) (filter symbol?) set))

(defn- generated-symbols
  "The names W4 minted for `src`: the `let`'s binding names, plus the
  wrapper's rest parameter."
  [src]
  (let [[_ binds body] (n/sexpr (:node (rw/w4-plan (p/parse-string src))))]
    (set (concat (take-nth 2 binds) (remove #{'&} (second body))))))

(defn- collisions
  "The generated names `src` re-uses from its own source. Empty is the
  whole requirement."
  [src]
  (sort (filter (source-symbols src) (generated-symbols src))))

(def ^:private callee-clash "(r/partial vector :first f__rf2)")
(def ^:private arg-clash    "(r/partial all-args (identity :zeroth) a0__rf2)")
(def ^:private rest-clash   "(r/partial all-args args__rf2)")
(def ^:private double-clash "(r/partial vector f__rf2 f__rf2__1)")

(deftest the-callee-name-never-shadows-a-local-the-site-already-uses
  (testing "THE AUDIT'S REPRODUCTION. Under the fixed-name scheme this
            emitted `(let [f__rf2 vector a1__rf2 f__rf2] …)`, and the
            second initializer read the callee that had just been bound
            over it — the wrapper answered `[:first vector]` where Reagent
            answered `[:first :outer]`."
    (let [[wrapper text] (emitted-under '[f__rf2 :outer] callee-clash)]
      (is (= [:first :outer] (wrapper))
          (str "the outer binding must reach the wrapper intact; emitted " text))
      (is (empty? (collisions callee-clash))
          (str "no generated name may be one the site already spells; emitted " text)))))

(deftest a-generated-argument-name-never-shadows-a-local-either
  (testing "`a0__rf2` bound over the site's own `a0__rf2` corrupts the
            NEXT argument's initializer, which is the same defect one
            binding along: the fixed-name scheme answered
            `[:zeroth :zeroth]`."
    (let [[wrapper text] (emitted-under '[a0__rf2 :outer] arg-clash)]
      (is (= [:zeroth :outer] (wrapper))
          (str "argument 1 must still read the site's `a0__rf2`; emitted " text))
      (is (empty? (collisions arg-clash)) text))))

(deftest the-rest-parameter-is-fresh-too
  (testing "The rest parameter scopes only over the `apply`, whose argument
            list holds nothing but generated names and inlined literals —
            so a shadow here is not observable TODAY, and this pin is
            honest about being structural rather than behavioural. It is
            here because emitting a binding the site already spells is a
            trap laid for the next change to this shape."
    (let [[wrapper text] (emitted-under '[args__rf2 :outer] rest-clash)]
      (is (= [:outer] (wrapper)) text)
      (is (empty? (collisions rest-clash))
          (str "the rest parameter must not re-use a site symbol; emitted " text)))))

(deftest the-whole-family-bumps-together-and-keeps-bumping
  (testing "a collision moves the callee, every argument name and the rest
            name to the same generation, so the emitted form reads as one
            family rather than a mix"
    (let [[_ text] (emitted-under '[f__rf2 :outer] callee-clash)]
      (is (= (str "(let [f__rf2__1 vector a1__rf2__1 f__rf2] "
                  "(fn [& args__rf2__1] (apply f__rf2__1 :first a1__rf2__1 args__rf2__1)))")
             text))))

  (testing "and a site that has taken generation 1 as well gets generation
            2 — the search walks until the whole family is fresh"
    (let [[wrapper text] (emitted-under '[f__rf2 :a f__rf2__1 :b] double-clash)]
      (is (= (str "(let [f__rf2__2 vector a0__rf2__2 f__rf2 a1__rf2__2 f__rf2__1] "
                  "(fn [& args__rf2__2] (apply f__rf2__2 a0__rf2__2 a1__rf2__2 args__rf2__2)))")
             text))
      (is (= [:a :b] (wrapper)))))

  (testing "a site that spells no `__rf2` symbol is untouched by any of
            this: generation 0 is the bare names, which is what every
            corpus case still asserts"
    (let [[_ text] (emitted "(r/partial handler @cart)")]
      (is (= "(let [f__rf2 handler a0__rf2 @cart] (fn [& args__rf2] (apply f__rf2 a0__rf2 args__rf2)))"
             text)))))
