(ns re-frame.fresco.expansion-probe
  "**WHAT ONLY THE COMPILER KNOWS, HANDED TO THE SUITE AS DATA**.

  Every namespace the package ships needs React, so nearly every suite it
  owns is ClojureScript on the Node lane — the hooks-island suite that
  calls this macro among them. One fact about the hooks namespace is
  nevertheless a fact about the ANALYSER, which runs on the JVM before
  that lane exists: a namespace's public var list — the membership a
  census pin is a deterministic act OVER — is the analyser's, and
  ClojureScript has no `ns-publics` at runtime.

  It is read here, at expansion, and emitted as ordinary quoted data into
  the compiled test. The suite then asserts on it with `is` like any other
  value, on the Node lane — no JVM test, no golden snapshot.

  ## It carries no `deftest`, and the JVM lane never loads it

  The artefact's `clojure -M:test` lane runs the `.cljc` slot-rule
  equivalence pin and nothing else, and this file does not disturb it:
  the runner discovers by namespace NAME and requires only what its test
  pattern matches, which `re-frame.fresco.expansion-probe` deliberately
  does not. An unresolvable `:require` planted at the top of this file
  leaves the lane green, which is the proof that the lane never loads it."
  (:require [cljs.analyzer.api :as ana-api]
            [re-frame.fresco.native]))

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
