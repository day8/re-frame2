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
            order, and every binding carries the `__rf2` suffix that makes
            the `let` hygienic against the forms it captures"
    (let [[_ text] (emitted "(r/partial (make-handler!) (next-id!) (log! \"x\"))")]
      (is (= (str "(let [f__rf2 (make-handler!) a0__rf2 (next-id!) a1__rf2 (log! \"x\")] "
                  "(fn [& args__rf2] (apply f__rf2 a0__rf2 a1__rf2 args__rf2)))")
             text)))))
