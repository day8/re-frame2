(ns ^:no-doc re-frame.freehand.registry
  "INTERNAL, DEV-ONLY. The index that answers a view ID with the declaration
  carrying it.

  ## Why an index has to exist at all

  A Freehand declaration is a VALUE held in a Var, and every reader beneath
  the tool tier takes that value: `v/manifest` and
  [[re-frame.freehand.tool/view-manifest]] are asked about a view by code
  that is holding one.

  An MCP inspector is holding nothing. It arrives over a wire with a keyword
  — `:app.people/people-list` — and asks what that view declares, which is a
  question no value-taking reader can answer. So the id has to resolve to
  something, and there are exactly two ways to arrange that: resolve the Var
  by name at read time, or record the declaration at the moment it is made.

  Var resolution is unavailable precisely where it is needed. An `:advanced`
  build has no Vars at all, and a dev build's munged module objects are the
  private host state a tool read exists to avoid reaching into. So the
  declaration records itself, once, as it is declared — which also means an
  inspector that ATTACHES LATE, to an already-running application, sees every
  view the application declared before it arrived.

  ## What this is not

  One row per DECLARATION, replaced when that declaration is re-evaluated,
  and nothing else. No occurrence, no mount, no generation, no counter, no
  ordering and no history: two live occurrences of one view are ONE row here,
  because a declaration is not a mount. The per-occurrence cumulative
  evidence accumulator was ruled out permanently (rf2-drpa3.167, REPLACE),
  and the live-occurrence index that answers \"what is mounted right now\" is
  a separate structure keyed by runtime occurrence, with its own release
  seam, under its own owner (rf2-xftdv).

  A hot reload of a declaring namespace re-evaluates its declarations and so
  replaces their rows. A reload that DELETES a declaration leaves that view's
  last row behind: nothing runs to retract it. That is a known and bounded
  staleness — one row per id, never a growing log — and it is stated here
  rather than papered over, because the alternative is a per-namespace
  eviction protocol with no caller asking for one.

  ## DEV-ONLY, gated at the one call site

  The gate lives in the `v/defview` EXPANSION
  ([[re-frame.freehand/expand-defview]]), not in [[register!]]. That is where
  `re-frame.interop/debug-enabled?` folds to `false` under `:advanced`,
  taking the call, its argument and every path into this namespace with it —
  so a production build carries no tool registry, because nothing reaches the
  atom for Closure to keep it alive for. The declaration's `:source` entry is
  gated in the same expansion for the same reason, and this namespace holds
  no second gate of its own so there is exactly one place to read the policy
  from.

  Normative owner: [`spec/004-Views.md`](../../../../spec/004-Views.md); the
  instrumentation contract the reads over this index serve is
  [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md).")

#?(:clj (set! *warn-on-reflection* true))

(defonce ^:private declared
  ;; view-id -> the declared view value. An atom because declaring is a
  ;; RUNTIME act (a namespace loading), never a compile-time one, and
  ;; `defonce` so reloading THIS namespace does not drop the rows the
  ;; application's own namespaces put here.
  (atom {}))

(defn register!
  "Record `view` as the declaration of `view-id`, answering `view`.

  Called from the `v/defview` expansion under the dev gate — see the
  namespace docstring for why the gate is there and not here. Answering
  `view` rather than the index keeps the call a pass-through, so the
  expansion can register a declaration on its way into its own Var without
  naming the value twice.

  Re-registration REPLACES: a reloaded declaration is the same view, and an
  index that appended would be a history store wearing an index's name."
  [view-id view]
  (swap! declared assoc view-id view)
  view)

(defn lookup
  "The view declared under `view-id`, or `nil` — including in a production
  build, where nothing was ever recorded.

  `nil` says one thing and says it whether the id was never declared, was
  declared in a namespace this build never loaded, or was declared in a
  build that elided the index entirely: this index knows of no such
  declaration. A caller that needs to tell those apart is asking a question
  about the BUILD, not about a view."
  [view-id]
  (get @declared view-id))

(defn view-ids
  "Every view id currently indexed, sorted — the roster a sweep enumerates
  when it has no ids of its own to start from.

  Sorted because the order of a hash map is not a fact about an
  application, and a tool that printed one would invite a reader to believe
  in it."
  []
  (into (sorted-set) (keys @declared)))
