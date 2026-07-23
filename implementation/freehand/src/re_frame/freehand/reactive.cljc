(ns re-frame.freehand.reactive
  "The COMPILED tier's render-time reactive runtime — the namespace the
  `:re-frame.freehand/v1` lowering resolves an authored `(v/sub …)`
  against.

  A compiled body never carries the authoring verb into its output. The
  analyzer recognises the site, gives it a stable LEXICAL id, records it
  on the manifest's subscription roster, and rewrites the call to
  [[sub-read]] carrying that id — so what runs is a read that already
  knows which proven site it is.

  ## Why this is a doorway and not a system

  There is exactly ONE reactive path in Freehand: the atomic shell in
  [[re-frame.freehand.cell]]. A read resolves and probes against the
  render's own candidate, owns nothing, and becomes an owned dependency
  only when the SELECTED commit publishes the whole bundle — frame,
  dependencies, event sites and evidence — in one write. The compiled
  tier does not get its own version of that, a second cache, or a second
  commit discipline; it gets the same [[re-frame.freehand.cell/observe-site!]]
  the interpreted `v/sub` reaches through
  [[re-frame.freehand.cell/observe!]], with a proven site key in place of
  a counted one.

  That is the whole difference between the modes, and it is why adding
  `{:compiled true}` to a declaration cannot change what its reads mean:
  the same query resolves the same way, stabilizes the same way, fails
  the same way outside a render, and is owned by the same commit. A
  compiled view and its interpreted twin observe the same values in the
  same commit discipline because they observe through the same function.

  `.cljc`, because the lowering is host-neutral: the JVM structural host
  runs a compiled body directly, and the browser runs it inside the React
  shell. Both drive the same cell.

  INTERNAL. Nothing here is application API — the authoring surface is
  `v/sub`, and this is what the compiler lowers it to.

  Normative owner:
  [`spec/006-ReactiveSubstrate.md`](../../../../../spec/006-ReactiveSubstrate.md)
  §The Freehand atomic shell; the grammar that admits the site is
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require [re-frame.freehand.cell :as cell]))

#?(:clj (set! *warn-on-reflection* true))

(defn sub-read
  "The compiled lowering of `(v/sub query)`: read `query` at the proven
  lexical site `site-id`, and answer the observed value.

  `site-id` is the analyzer's own id for this read — the same id the
  declaration's manifest carries on its `:subscriptions` roster — so the
  committed dependency the shell installs is indexed by the coordinate a
  reader, a tool and the build all already have a name for. It is stable
  across re-renders and across body edits that move the site, which is
  what an interpreted body's document ordinal cannot promise.

  Everything a read means is [[re-frame.freehand.cell/observe-site!]]'s:
  ownership-free at render, owned by the SELECTED commit, stabilized
  against the site's prior committed observation, and fail-loud outside
  an active declared render."
  [site-id query]
  (cell/observe-site! site-id query))
