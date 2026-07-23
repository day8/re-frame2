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
  (:require [re-frame.freehand.cell :as cell]
            [re-frame.freehand.events :as events]))

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

(defn event-site
  "The compiled lowering of one `:on-*` position: record `value` as the
  proven lexical site `site-id`'s intent on THIS render's candidate, and
  answer the site's stable proxy.

  It is [[re-frame.freehand.events/site]] — the one constructor, the one
  classification, the one committed-proxy scheme the interpreted walk
  reaches — with two differences that are both about WHEN a fact was
  learned, never about what a site means:

  - the site key is the analyzer's stable LEXICAL id rather than the
    walk's document ordinal, exactly as [[sub-read]]'s is;
  - the candidate is read from the shell's own ambient capture
    ([[re-frame.freehand.cell/current-candidate]]) rather than threaded,
    because a compiled body is straight-line code with no walk between
    the shell and the site to carry it.

  `element` is the CONTROLLED-INPUT door's element half — `{:tag :input
  :controlled? true :slot \"onInput\"}` — and it is a compile-time
  CONSTANT here, because the whole props map of a compiled element is
  lexically visible. That is the resolution of the ABI question the
  donor's emitter answered with a pre-encoded flag: Freehand does not
  encode the synchronous-lane verdict in the emission at all. It emits
  the element facts, and [[re-frame.freehand.controlled/door?]] decides —
  the same predicate, on the same facts, at the same commit, as the
  interpreted walk. A field cannot change lanes by gaining
  `{:compiled true}`, because there is no second decision to diverge.

  `nil` outside a render — an elided ViewCell has no candidate, so
  declarative intent under it has no commit to belong to and a plain
  function is returned unchanged, which is the sentence the interpreted
  walk already writes for markup with no boundary above it."
  [site-id value element]
  (if-some [cand (cell/current-candidate)]
    (events/site (cell/candidate-events cand)
                 site-id
                 value
                 events/default-payload
                 element)
    (when (fn? value) value)))
