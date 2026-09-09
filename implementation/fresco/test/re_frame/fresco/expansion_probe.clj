(ns re-frame.hicasso.expansion-probe
  "**WHAT ONLY THE COMPILER KNOWS, HANDED TO THE SUITE AS DATA**.

  This artefact has no JVM test lane and that is the CORRECT outcome —
  `implementation/hicasso/deps.edn` says so and waives the runner's
  coverage floor for it, because every namespace the package ships needs
  React and every suite it owns is therefore ClojureScript on the Node
  lane. One fact about the hooks namespace is nevertheless a fact about
  the ANALYSER, which runs on the JVM before that lane exists: a
  namespace's public var list — the membership a census pin is a
  deterministic act OVER — is the analyser's, and ClojureScript has no
  `ns-publics` at runtime.

  It is read here, at expansion, and emitted as ordinary quoted data into
  the compiled test. The suite then asserts on it with `is` like any other
  value, in the one lane this repository actually runs — no JVM lane, no
  golden snapshot.

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

(defmacro public-vars
  "The public var names of `ns-sym`, as a sorted vector of strings.

  Two halves, because a `.cljc` namespace HAS two. The ClojureScript
  analyser owns the runtime defs and is asked through
  `cljs.analyzer.api/ns-publics`; any macros are ordinary Clojure vars
  and are asked of the loaded JVM namespace. A census taken from either
  half alone would miss a var added to the other, and a macro is
  precisely the kind of var such a census exists to notice.

  `ns-sym` must already be ANALYSED when this expands, which the calling
  namespace guarantees by requiring it — an unanalysed namespace answers
  an empty map rather than an error, so the census's own non-vacuity row
  is what turns that silence into a red."
  [ns-sym]
  (let [analysed   (keys (ana-api/ns-publics ns-sym))
        on-the-jvm (keys (ns-publics ns-sym))]
    (vec (sort (distinct (map name (concat analysed on-the-jvm)))))))
