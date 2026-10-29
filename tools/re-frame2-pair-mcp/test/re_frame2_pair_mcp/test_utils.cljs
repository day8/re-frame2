(ns re-frame2-pair-mcp.test-utils
  "Shared test helpers: wire-envelope extractors, async-safe stub
  installers for the nREPL eval globals, an interpreter for the source the
  tools emit, the dedup inverse, and a test-only handle on mcp-base's token
  rule."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.freshness :as freshness]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(defn args->js
  "Coerce a CLJS arg map into the `#js {}` object the tools read via
  `wire/arg`. Keyword keys are `name`d; string keys pass through."
  [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (if (keyword? k) (name k) k) v))
    o))

(defn extract-text
  "The first content item's `:text` string, or nil."
  [^js result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))]
    (when item (j/get item :text))))

(defn extract-edn
  [^js result]
  (some-> (extract-text result) edn/read-string))

(defn error?
  [^js result]
  (true? (j/get result :isError)))

;; The nREPL eval fns are shared mutable globals. A `.finally` restore can
;; land after cljs.test has moved on to a test that installed its own stub,
;; so every restore is identity-guarded: it puts `orig` back only while the
;; global is still this installer's `stub`.

(defn restore-eval!
  [stub orig]
  (when (identical? nrepl/cljs-eval-value stub)
    (set! nrepl/cljs-eval-value orig)))

(defn restore-jvm-eval!
  [stub orig]
  (when (identical? nrepl/jvm-eval stub)
    (set! nrepl/jvm-eval orig)))

(defn restore-cljs-eval!
  [stub orig]
  (when (identical? nrepl/cljs-eval stub)
    (set! nrepl/cljs-eval orig)))

(defn restore-freshness!
  [stub orig]
  (when (identical? freshness/jvm-build-freshness stub)
    (set! freshness/jvm-build-freshness orig)))

(defn with-stubbed-eval!
  "Stub `nrepl/cljs-eval-value` (both arities) to resolve to
  `canned-value` while the Promise from `body-fn` runs."
  [canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id _form-str]
                (js/Promise.resolve canned-value))
               ([_conn _build-id _form-str _opts]
                (js/Promise.resolve canned-value)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (restore-eval! stub orig))))))

(defn with-stubbed-freshness!
  "Stub `freshness/jvm-build-freshness` to resolve to `jvm-half` (a map, or
  nil for an unreadable JVM half) while the Promise from `body-fn` runs,
  so discover-app never opens a real socket."
  [jvm-half body-fn]
  (let [orig freshness/jvm-build-freshness
        stub (fn [_conn _bid] (js/Promise.resolve jvm-half))]
    (set! freshness/jvm-build-freshness stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (restore-freshness! stub orig))))))

(defn run-form
  "Evaluate `form`, read back from CLJS source a tool emits for app-side
  evaluation, with `locals` bound and `env` resolving every other symbol.
  The tools build that source by string concatenation, so reading it back
  and running it is the only way a test sees what the app would compute.
  Covers the forms the emitters write — `let`, single-arity `fn` / `fn*`,
  `if`, `and`, `quote`, keyword lookup and calls — and throws on an
  unresolved symbol, so a rewritten form fails loudly rather than passing
  unexercised."
  [form locals env]
  (let [run #(run-form % locals env)]
    (cond
      (symbol? form)
      (cond
        (contains? locals form) (get locals form)
        (contains? env form)    (get env form)
        :else (throw (ex-info "unresolved symbol in the emitted form"
                              {:symbol form})))

      (map? form)    (into {} (map (fn [[k v]] [(run k) (run v)])) form)
      (vector? form) (mapv run form)

      (seq? form)
      (let [[op & args] form]
        (cond
          (= 'let op)
          (let [[bindings & body] args
                scope (reduce (fn [l [s e]] (assoc l s (run-form e l env)))
                              locals
                              (partition 2 bindings))]
            (last (map #(run-form % scope env) body)))

          (contains? '#{fn fn*} op)
          (let [[params & body] args]
            (fn [& xs]
              (let [scope (merge locals (zipmap params xs))]
                (last (map #(run-form % scope env) body)))))

          (= 'if op)
          (let [[c t e] args]
            (if (run c) (run t) (run e)))

          (= 'and op)
          (reduce (fn [_ a] (let [v (run a)] (if v v (reduced v)))) true args)

          (= 'quote op) (first args)

          :else (apply (run op) (map run args))))

      :else form)))

(defn dedup-expand
  "Inverse of `re-frame.mcp-base.dedup/dedup-value`; returns `v` unchanged
  when it carries no dedup table."
  [v]
  (if (and (map? v) (contains? v rf.mcp-base.vocab/dedup-table-key))
    (rf.mcp-base.dedup/expand (get v rf.mcp-base.vocab/dedup-table-key))
    v))

(defn token-estimate
  [s]
  (rf.mcp-base.overflow/token-estimate s))
