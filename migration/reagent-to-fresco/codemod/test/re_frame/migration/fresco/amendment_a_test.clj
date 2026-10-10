(ns re-frame.migration.fresco.amendment-a-test
  "**AMENDMENT (A), pinned by EXECUTION rather than by text.**

  The golden corpus pins text and only text, because it is a JVM harness
  over source text while the destination is a browser runtime (§9.7). W4 is
  the one rewrite where that limit bites, because its correction is entirely
  about **when** something is evaluated: a text assertion can confirm the
  `let` was written, not that it captures. W4's OUTPUT is plain Clojure —
  `let`, `fn`, `apply`, with no `r/partial` left in it — so this JVM can
  `eval` it and ask the runtime question directly.

  The callee and argument forms below are ordinary vars in this namespace,
  so the emitted text resolves against them exactly as a consumer's would
  resolve against theirs."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.rewrite :as rf.migration.fresco.rewrite]
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

(defn all-args [& xs] (vec xs))

(defn- eval-here
  "Evaluate an emitted form with this namespace's vars in scope."
  [form]
  (binding [*ns* (find-ns 're-frame.migration.fresco.amendment-a-test)]
    (eval form)))

(defn- emitted
  "Run W4 over a literal `(r/partial …)` call; return `[wrapper text]`."
  [src]
  (let [node (:node (rf.migration.fresco.rewrite/w4-plan (p/parse-string src)))]
    [(eval-here (n/sexpr node)) (n/string node)]))

;; ---------------------------------------------------------------------------
;; Evaluated once, at construction — as `make-partial-fn` did
;; ---------------------------------------------------------------------------

(deftest a-dereferenced-argument-is-a-snapshot-not-a-live-read
  (testing "`(r/partial handler @cart)` read `@cart` ONCE, when the prop was
            built; the wrapper must not re-read it per invocation"
    (reset! cart :v1)
    (let [[wrapper _] (emitted "(r/partial handler @cart)")]
      (is (= :v1 (wrapper)))
      (reset! cart :v2)
      (is (= :v1 (wrapper)) "still the snapshot after the atom moved"))))

(deftest every-argument-is-evaluated-once-left-to-right-at-construction
  (testing "the callee and both arguments run EXACTLY ONCE, in source order,
            when the prop is built — and never again per invocation, or a
            `next-id!` that minted one id per prop mints one per click"
    (reset! effects [])
    (let [[wrapper _] (emitted "(r/partial (make-handler!) (next-id!) (log! \"x\"))")]
      (is (= [:make :next :log] @effects))
      (wrapper) (wrapper) (wrapper)
      (is (= [:make :next :log] @effects)))))

(deftest the-wrapper-is-return-transparent
  (testing "whatever `f` returned before, it returns now (design Law 2), with
            the bound arguments first and the invocation's after —
            `apply f a … args`"
    (let [[wrapper _] (emitted "(r/partial all-args :a :b)")]
      (is (= [:a :b 1 2] (wrapper 1 2))))))

;; ---------------------------------------------------------------------------
;; Hygiene — the generated names are FRESH against the site
;; ---------------------------------------------------------------------------
;;
;; A consumer may already have a local spelled `f__rf2`, and `let` binds
;; SEQUENTIALLY, so a generated binding that shadows one silently rebinds
;; every LATER initializer that refers to it. Each eval runs the emitted form
;; inside an outer `let` binding the colliding name, which is the shape the
;; consumer file presents.

(defn- emitted-under
  "W4's output for `src`, evaluated inside an outer `let` binding `outer`.
  Returns `[wrapper text]`."
  [outer src]
  (let [node (:node (rf.migration.fresco.rewrite/w4-plan (p/parse-string src)))]
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
  (let [[_ binds body] (n/sexpr (:node (rf.migration.fresco.rewrite/w4-plan (p/parse-string src))))]
    (set (concat (take-nth 2 binds) (remove #{'&} (second body))))))

(defn- collisions
  "The generated names `src` re-uses from its own source. Empty is the
  whole requirement."
  [src]
  (sort (filter (source-symbols src) (generated-symbols src))))

(def ^:private callee-clash "(r/partial vector :first f__rf2)")
(def ^:private arg-clash    "(r/partial all-args (identity :zeroth) a0__rf2)")
(def ^:private double-clash "(r/partial vector f__rf2 f__rf2__1)")

(deftest a-generated-argument-name-never-shadows-a-local-either
  (testing "`a0__rf2` bound over the site's own `a0__rf2` would corrupt the
            NEXT argument's initializer: a fixed-name scheme answers
            `[:zeroth :zeroth]`"
    (let [[wrapper text] (emitted-under '[a0__rf2 :outer] arg-clash)]
      (is (= [:zeroth :outer] (wrapper))
          (str "argument 1 must still read the site's `a0__rf2`; emitted " text))
      (is (empty? (collisions arg-clash)) text))))

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
      (is (= [:a :b] (wrapper))))))
