(ns re-frame.hicasso.expansion-probe
  "**WHAT ONLY THE COMPILER KNOWS, HANDED TO THE SUITE AS DATA**.

  This artefact has no JVM test lane and that is the CORRECT outcome —
  `implementation/hicasso/deps.edn` says so and waives the runner's
  coverage floor for it, because every namespace the package ships needs
  React and every suite it owns is therefore ClojureScript on the Node
  lane. Two facts about the native tier are nevertheless facts about
  MACRO EXPANSION, which happens on the JVM before that lane exists:

  1. `n/$` calls `check-child!` on child FORMS and `prop-slots` on a
     literal props map, so a literal hiccup child or a literal
     canonical-slot collision is a BUILD FAILURE and not a runtime
     refusal. Every landed refusal row uses a `let`-bound local to force
     the runtime path instead, and the compile-time half of the grammar
     was witnessed by nothing this repository runs.
  2. A namespace's public var list — which a Phase 3 freeze is a
     deterministic act OVER — is the analyser's, and ClojureScript has no
     `ns-publics` at runtime.

  Both are read here, at expansion, and emitted as ordinary quoted data
  into the compiled test. The suite then asserts on them with `is` like
  any other value, in the one lane this repository actually runs — no JVM
  lane, no golden snapshot, and no build that is expected to fail.

  ## Why a probe rather than `macroexpand`

  `cljs.core/macroexpand-1` is itself a macro: it runs at compile time,
  and a macro that THROWS while it runs takes the build down with it.
  There is no `try` a compiled test can put around an expansion that has
  already happened — which is exactly why the compile-time refusals were
  unwitnessed rather than merely untested. [[expansion]] puts the `try`
  where it has to be, on the JVM, around the macro's own invocation, and
  turns the throw into a value.

  ## Not a snapshot, deliberately

  `public_door_macros_cljs_test.cljs` records why byte-for-byte expansion
  snapshots were considered and rejected, and nothing here reopens that.
  [[expansion]] answers the refusal's ex-data; the expanded FORM is
  reduced to its head symbol and the set of qualified symbols it names —
  the first as the control proving the probe expanded the real macro
  rather than merely failing to throw, the second because *which vars an
  expansion names* is the classification claim rf2-e0d2 makes about half
  the namespace's public surface.

  ## It carries no `deftest`, and the JVM probe lane still finds zero

  The artefact's `clojure -M:test` alias is a classpath probe whose
  correct outcome is zero tests, and this file does not disturb it: the
  runner discovers by namespace NAME and requires only what its test
  pattern matches, which `re-frame.hicasso.expansion-probe` deliberately
  does not. That was measured rather than assumed — a deliberately
  unresolvable `:require` planted at the top of this file leaves the
  lane green, which is the only proof that the lane never reads it. So
  the artefact's `deps.edn` note stands unchanged: if a JVM-runnable
  SUITE ever lands in `test/`, that is when `--probe` comes off."
  (:require [cljs.analyzer.api :as ana-api]
            [re-frame.hicasso.native]))

(defn- macro-var
  "The var `sym` names, resolved through its OWN namespace rather than
  through `*ns*`. During ClojureScript compilation `*ns*` is whatever the
  compiler has bound at that moment, so `resolve` is not a stable answer
  here; a fully-qualified head deserves a lookup that never consults it."
  [sym]
  (or (when (qualified-symbol? sym)
        (ns-resolve (symbol (namespace sym)) (symbol (name sym))))
      (throw (ex-info (str "expansion-probe: no macro var named " (pr-str sym)
                           " — write the head fully qualified")
                      {:sym sym}))))

(defn- named-vars
  "Every qualified symbol anywhere in `form` — the vars its expansion
  NAMES, which for a macro is the set that cannot be made private
  without breaking the consumer's compile."
  [form]
  (into #{} (filter qualified-symbol?) (tree-seq coll? seq form)))

(defmacro expansion
  "Expand `form` on the JVM and answer WHAT HAPPENED, as data:

      {:outcome :expanded :head re-frame.hicasso.native/el :names #{…}}
      {:outcome :refused  :data {:rf.error/id … :where … :recovery …}}
      {:outcome :threw    :message \"…\"}

  `form`'s head is written fully qualified, because the compiler's alias
  map is a ClojureScript fact and this runs as Clojure — and because
  spelling the namespace out is also what makes each row say which macro
  it drove. `&env` is forwarded verbatim, so the expansion is the one the
  compiler would have produced at this very site rather than a
  context-free approximation of it.

  `:threw` is a third outcome rather than a variant of `:refused`,
  because a macro that dies without minting a refusal is a different
  defect from one that refuses — and a probe that folded the two would
  let the first pass as the second."
  [form]
  (let [v       (macro-var (first form))
        outcome (try
                  (let [expanded (apply @v form &env (rest form))]
                    {:outcome :expanded
                     :head    (when (seq? expanded) (first expanded))
                     :names   (named-vars expanded)})
                  (catch clojure.lang.ExceptionInfo e
                    {:outcome :refused :data (ex-data e)})
                  (catch Throwable t
                    {:outcome :threw :message (str (.getMessage t))}))]
    `(quote ~outcome)))

(defmacro public-vars
  "The public var names of `ns-sym`, as a sorted vector of strings.

  Two halves, because a self-requiring `.cljc` namespace HAS two. The
  ClojureScript analyser owns the runtime defs and is asked through
  `cljs.analyzer.api/ns-publics`; the macros are ordinary Clojure vars
  and are asked of the loaded JVM namespace. A census taken from either
  half alone would miss the other, and `n/$` is precisely the var such a
  census exists to notice.

  `ns-sym` must already be ANALYSED when this expands, which the calling
  namespace guarantees by requiring it — an unanalysed namespace answers
  an empty map rather than an error, so the census's own non-vacuity row
  is what turns that silence into a red."
  [ns-sym]
  (let [analysed   (keys (ana-api/ns-publics ns-sym))
        on-the-jvm (keys (ns-publics ns-sym))]
    (vec (sort (distinct (map name (concat analysed on-the-jvm)))))))
