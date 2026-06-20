(ns re-frame.story.generate.test-check
  "OPTIONAL `clojure.test.check` adapter for the generator-agnostic property
  runner (spec/017-Testing-Story.md §Generator-agnostic — a
  generated run is driven by a caller-supplied `gen-fn` and `clojure.test.check`
  is named there as a fit 'by wrapping its draw in a `gen-fn`'). This namespace
  IS that wrapping, made concrete + reusable.

  ## Opt-in; the dependency-free PRNG stays the default

  `re-frame.story.generate/check-property!` is driven by a pure
  `(fn [seed] event-program)` over the self-contained splitmix64 PRNG
  (`seed-seq`) and bundles NO generator library — that dependency-free path is
  and remains the DEFAULT. This adapter is pure additive sugar:
  it lets a caller who ALREADY has `org.clojure/test.check` on the classpath
  hand a `test.check` generator of event programs and get back the same
  `gen-fn` shape `check-property!` already takes. Nothing about the seed-bearing
  artifact + promotion flow changes.

  `org.clojure/test.check` is NOT a hard Story dependency — it is wired into
  `tools/story/deps.edn`'s `:test` alias ONLY (so this adapter's own tests can
  exercise it). At runtime the test.check vars are late-bound via
  `requiring-resolve` so this namespace LOADS without test.check present;
  invoking `gen-fn` (or `gen->gen-fn` itself) without test.check on the
  classpath throws a clear, actionable error rather than failing at compile
  time. Callers who do not want the dependency simply keep using a hand-rolled
  `gen-fn` — this namespace need never be required.

  ## What the adapter does

  `gen->gen-fn` turns a `test.check` generator `g` (of an event PROGRAM — a
  vector of tagged steps, or bare event vectors the artifact coerces) into the
  pure `(fn [seed] event-program)` `check-property!` consumes. It seeds
  `test.check`'s own deterministic RNG from the integer seed and draws ONE
  program per seed via `clojure.test.check.generators/generate` (whose third
  arg is that numeric seed), at a configurable `:size`. Because
  the draw is fully determined by the integer seed, the existing reproducibility
  + seed-bearing-artifact guarantees hold UNCHANGED: `check-property!`'s
  `seed-seq` supplies the seeds, and each seed deterministically yields one
  program. Shrinking continues to use `check-property!`'s own deterministic
  program-prefix delta-debug over the concrete program — test.check's rose-tree
  shrinking is NOT engaged, keeping ONE shrink model + a host-portable shrunk
  artifact.

  ## JVM-scoped by design

  `requiring-resolve` is a JVM facility; CLJS cannot dynamically resolve an
  optional namespace at runtime. The `:test` alias (where test.check lives)
  runs on the JVM, so this adapter targets the JVM half of `clojure -M:test`.
  On CLJS the adapter throws a clear message — the dependency-free `gen-fn`
  path is the cross-host default and needs no library."
  (:require [re-frame.error :as error]))

(def ^:private absent-msg
  (str "re-frame.story.generate.test-check requires org.clojure/test.check on "
       "the classpath. It is an OPTIONAL adapter — add test.check to your "
       "deps (Story wires it into tools/story/deps.edn's :test alias only) or "
       "keep using a hand-rolled (fn [seed] event-program) gen-fn over the "
       "dependency-free splitmix64 PRNG, which is the default."))

#?(:clj
   (defn- resolve-or-throw
     "Late-bind a test.check var by fully-qualified symbol, or throw a clear
     opt-in error if test.check is not on the classpath. Pure deref of the
     classpath — no test.check require at this namespace's load time."
     [sym]
     (or (try (requiring-resolve sym)
              (catch java.io.FileNotFoundException _ nil)
              (catch Throwable _ nil))
         (error/throw-error!
           :rf.error/story-test-check-absent
           'rf.story.generate/gen->gen-fn
           absent-msg
           {:recovery :add-test-check-or-use-a-gen-fn
            :extra    {:missing-var sym}}))))

(defn available?
  "True iff `org.clojure/test.check` is resolvable on the classpath right now.
  Lets a caller branch to the dependency-free `gen-fn` path when the optional
  adapter is absent, without catching an exception."
  []
  #?(:clj  (boolean (try (requiring-resolve 'clojure.test.check.generators/generate)
                         (catch Throwable _ nil)))
     :cljs false))

(defn gen->gen-fn
  "Turn a `clojure.test.check` generator `g` (of an event PROGRAM) into the
  pure `(fn [seed] event-program)` `re-frame.story.generate/check-property!`
  consumes. `opts`:

  - `:size` — the test.check generation size handed to every draw (default 30).
              Constant across seeds so the program-size distribution is the
              seed's, not a growing test.check `:num-tests` ramp; vary it per
              call for larger/smaller programs.

  The returned fn is pure + deterministic in its `seed` argument: it draws one
  program from `g` seeding test.check's own deterministic RNG with the integer
  seed, so the SAME seed always yields the SAME program (the reproducibility
  `check-property!` relies on). Throws the opt-in error if test.check is absent
  — resolution is deferred to first call so requiring this namespace never hard-
  depends on test.check."
  ([g] (gen->gen-fn g {}))
  ([g {:keys [size] :or {size 30}}]
   #?(:clj
      ;; `clojure.test.check.generators/generate` is `(generate g size seed)` —
      ;; its THIRD arg is a numeric SEED, which it feeds to `make-random`
      ;; internally. Hand it the integer seed directly so the draw is fully
      ;; determined by that seed (same seed → same program).
      (let [generate (resolve-or-throw 'clojure.test.check.generators/generate)]
        (fn [seed]
          (vec (generate g size seed))))
      :cljs
      (error/throw-error!
        :rf.error/story-test-check-unsupported-host
        'rf.story.generate/gen->gen-fn
        (str "re-frame.story.generate.test-check is JVM-only — CLJS cannot "
             "dynamically resolve the optional test.check namespace. Use the "
             "dependency-free (fn [seed] event-program) gen-fn over the "
             "splitmix64 PRNG, which is the cross-host default.")
        {:recovery :use-a-gen-fn-on-cljs
         :extra    {:host :cljs}}))))

(defn check-property-gen!
  "Sugar: run `re-frame.story.generate/check-property!` driven by a
  `clojure.test.check` generator `g` of event programs. Identical to calling
  `check-property!` with `(gen->gen-fn g opts)` — the `:size` opt (default 30)
  is consumed here for the draw; all OTHER opts (`:seed` / `:num-tests` /
  `:shrink?` / `:fx-decisions` / `:hooks` / `:frame-config`) pass through to
  `check-property!` UNCHANGED. The seed-bearing artifact + promotion flow is the
  same one the dependency-free path produces. Takes `check-property!` as
  `check-property-fn` so this namespace carries no require on the runner (and so
  the caller's own runner var binds at the call site)."
  [check-property-fn g {:keys [size] :as opts}]
  (let [gen-fn (gen->gen-fn g (cond-> {} (some? size) (assoc :size size)))]
    (check-property-fn gen-fn (dissoc opts :size))))
