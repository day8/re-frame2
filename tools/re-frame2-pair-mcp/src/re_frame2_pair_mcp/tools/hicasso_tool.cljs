(ns re-frame2-pair-mcp.tools.hicasso-tool
  "Tools: read-mounted-boundaries / read-read-attribution / explain-render —
  the reads of the ADAPTER-NEUTRAL Hicasso evidence door
  (`re-frame.hicasso.tool`).

  These expose, from a RUNNING re-frame2 app, the same evidence Xray's Hicasso
  tab reads — so a pairing agent inspects what is mounted, who reads what, and
  which reads moved, WITHOUT reaching a private React / cell / scheduler
  object. Each tool ships ONE self-describing CLJS form that calls a
  `re-frame.hicasso.tool` read and forwards the bounded, serializable,
  VERSIONED result verbatim; every read answers inside the four-axis evidence
  projection (`:scope`, `:basis`, `:complete?`, `:loss`) stamped with `:schema`
  and `:read`, and egresses only plain data.

    | MCP wire tool             | `re-frame.hicasso.tool` read | arg |
    |---------------------------|------------------------------|-----|
    | `read-mounted-boundaries` | `read-mounted-boundaries`    | —   |
    | `read-read-attribution`   | `read-read-attribution`      | —   |
    | `explain-render`          | `explain-render`             | —   |

  The wire names and the framework fn names AGREE, exactly, and
  [[hicasso-wire-test]] is what keeps them agreeing: the coupling here is a
  STRING interpolated into an nREPL form, so nothing a compiler, a classpath
  scan or a `:require` grep can see stands between a rename on the provider
  and a runtime failure on the wire.

  ## Three reads, not five — the door answers a different question set

  This namespace replaced a five-tool family aimed at
  `re-frame.freehand.tool` (rf2-n3mb). That was not a rename, and the
  difference is worth stating rather than papering over, because it is a fact
  about the PROVIDER and not a shortfall here.

  Freehand published a view REGISTRY and a compiler MANIFEST, so it could
  answer `read-view-manifest`, `read-view-dependencies` and
  `read-view-event-sites` — static questions about a view named by its
  declared id, answerable BEFORE mount. **Hicasso mints no boundary identity
  and keeps no registry.** A registration is its read set, React's notifier
  and the acquired cells; `:view` and `:source` are stated as
  `re-frame.hicasso.evidence/unknown` under an `:opaque` naming projection,
  permanently and by design. There is no id to ask about and no manifest to
  read, so those three questions have no counterpart on this door and are not
  shipped as tools that would answer them with a fabricated emptiness.

  What replaces them is a different way in. A boundary is keyed by its
  EDGE SET, so `read-read-attribution` — *which boundaries read this
  subscription* — is how an agent gets from a subscription it can name to a
  boundary it cannot, and `:reads` on every mounted row is the forward
  direction of the same edge. That is the honest analogue of *what feeds this
  view*, asked of a runtime that keeps edges rather than declarations.

  `re-frame.hicasso.tool/read-intents` is deliberately NOT shipped as a fourth
  tool: it folds Spec 009's retained event ring, which is the question Pair
  already answers with `trace-window` — under richer projection, with cursor
  pagination and the elision walker. A second, thinner window read under a new
  name would be surface without a question of its own.

  ## Tier presence — direct eval, no preload coupling (deliberate divergence)

  Unlike the `read-ui`/`read-dom` wrapper pattern, these tools DO NOT route
  through a `re-frame2-pair.runtime` fn. `re-frame.hicasso.tool` lives in
  `day8/re-frame2-hicasso`, and **nothing in `re-frame.hicasso` requires it** —
  that is how the substrate keeps the door out of a production build entirely.
  An app on the Reagent or UIx adapter never has it at all. Requiring it in the
  generic preload would make the preload uncompilable in those apps. So each
  tool evals a self-contained form guarded by `cljs.core/exists?`: the door is
  called only when present, and its absence is surfaced HONESTLY as
  `:reason :evidence-tier-unavailable` — 'tolerate absent evidence explicitly',
  not a fabricated emptiness.

  ## Absent / older evidence + schema gate

  The eval form resolves to a `{:ok? …}` envelope, which the shared
  `versioned-envelope-result` `on-value` GATES against
  `consumed-evidence-schema` — the evidence-schema version THIS Pair build
  understands — before it reaches the wire:

    - `{:ok? true …projection…}`      — the projection, `:schema` MATCHING
                                        `consumed-evidence-schema`, forwarded
                                        verbatim;
    - `{:ok? false :reason :evidence-tier-version-mismatch :expected … :actual …}`
                                      — the projection stamped a schema this
                                        build was NOT written against. Pair
                                        connects to an arbitrary running app, so
                                        the producer's stamp cannot define
                                        support: an unrecognized schema is a
                                        typed mismatch, never forwarded as
                                        success. `re-frame.hicasso.evidence`
                                        states there is no v1 acceptance path
                                        and no compatibility adapter, so this
                                        gate is the consumer half of a boundary
                                        the producer means literally;
    - `{:ok? false :reason :evidence-tier-unavailable}` — `re-frame.hicasso.tool`
                                        is not loaded (a non-Hicasso app, or a
                                        Hicasso app nothing has loaded the door
                                        into);
    - `{:ok? false :reason :evidence-tier-inactive}` — every read answers `nil`
                                        under `:advanced` with `goog.DEBUG`
                                        false. The door is dev-only;
    - `{:ok? false :reason :evidence-tier-error :message …}` — the read threw.

  Every `:ok? false` rides `isError:true` via `versioned-envelope-result` (which
  wraps `probe/map-envelope-result`; spec/003 §'Every :ok? false response is
  isError: true'), so a degraded read — an unavailable door, a production build,
  OR a schema mismatch — is never cached and never masquerades as a successful
  answer.

  ## Sensitive-data projection

  These reads carry NO read value and NO event vector, at any classification —
  the door's own docstring calls that a promise it makes uniformly rather than
  by declaration, because EP-0025's model is fail-open and a door that shipped
  an undeclared event's arguments while promising *no application data* would be
  making the promise falsely. Query VECTORS ride pre-projected through
  `re-frame.elision/elide-wire-value` on the producer side, including inside
  every exported boundary KEY. So this tool needs no elision walker of its own:
  the projection is done before the value reaches the wire, and adding a second
  walk here would imply the first is not trusted."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(def tier-ns
  "The fully-qualified reader door the eval forms call.

  PUBLIC, and that is the point. This string IS the coupling — it is
  interpolated into every form Pair emits, so a classpath scan, a `:require`
  grep and a compiler all report this namespace as independent of the provider
  while every tool calls it. Naming it here, beside [[tier-reads]] and
  [[consumed-evidence-schema]], gives the wire witness the three halves of the
  contract to assert against the provider's own source instead of leaving them
  as literals only a running app can falsify."
  "re-frame.hicasso.tool")

(def tier-reads
  "The reader fns of [[tier-ns]] this build calls, in registration order.

  The witness iterates this vector rather than a hand-copied list, so a read
  added here without a counterpart on the provider fails the gate rather than
  the wire."
  ["read-mounted-boundaries" "read-read-attribution" "explain-render"])

(def ^:private unavailable-hint
  (str "the re-frame.hicasso.tool evidence door is not loaded in this app. It "
       "lives in day8/re-frame2-hicasso and NOTHING in re-frame.hicasso "
       "requires it — that is how a production build never loads it — so a "
       "Hicasso app has it only once something pulls it in (Xray does), and an "
       "app on the Reagent or UIx adapter does not have it at all. Load "
       "re-frame.hicasso.tool into the running build and retry."))

(def ^:private inactive-hint
  (str "every re-frame.hicasso.tool read answers nil under :advanced with "
       "goog.DEBUG false — the evidence door is DEV-ONLY. Read from a "
       "development build."))

(def consumed-evidence-schema
  "The `re-frame.hicasso.tool` evidence-schema version THIS Pair build was
  written to forward — a CONSUMER-OWNED literal. Pair connects to an ARBITRARY
  running app that may ship an older or newer door, so it CANNOT trust the
  producer's own `:schema` stamp to define support: a projection stamped a
  schema this build does not understand is reported as a typed `:ok? false`
  mismatch, never forwarded as a successful read of a shape it cannot parse.
  Currently `:re-frame.hicasso.evidence/v2`, matching
  `re-frame.hicasso.evidence/schema`; bump ONLY when Pair is taught the new
  shape."
  :re-frame.hicasso.evidence/v2)

(defn versioned-envelope-result
  "The `on-value` projection for the door reads: `probe/map-envelope-result`
  PLUS a consumer-owned schema GATE. A `:ok? true` projection stamped a
  `:schema` this build was not written against is converted to a typed
  `{:ok? false :reason :evidence-tier-version-mismatch}` (isError) rather than
  forwarded as success — Pair may reach an arbitrarily old/new app, so the
  producer's stamp does not define support. Every other value (a `:ok? false`
  absence/error envelope, a genuinely-empty `:ok? true` schema-matched read, or
  a blank/non-map result) defers UNCHANGED to `map-envelope-result`, so the
  isError contract and honest-emptiness handling are unchanged."
  [v]
  (if (and (map? v)
           (true? (:ok? v))
           (not= consumed-evidence-schema (:schema v)))
    (wire/err-text {:ok?      false
                    :reason   :evidence-tier-version-mismatch
                    :expected consumed-evidence-schema
                    :actual   (:schema v)
                    :hint     (str "the running app's re-frame.hicasso.tool door stamped "
                                   "evidence schema "
                                   (pr-str (:schema v))
                                   " but this pair build understands "
                                   (pr-str consumed-evidence-schema)
                                   " — the projection shape has evolved. There is "
                                   "no compatibility adapter on either side, by "
                                   "design. Align the app's day8/re-frame2-hicasso "
                                   "with this tool build (or update the tool).")})
    (probe/map-envelope-result v)))

(defn projection-form
  "Build the `cljs.core/exists?`-guarded, self-describing eval form for a
  `re-frame.hicasso.tool` read. Pure string → string, so the form composition
  is unit-checkable off the wire.

    `read-fn`  the reader fn name (e.g. \"read-mounted-boundaries\").

  Every read on this door is NULLARY — the runtime has no id to narrow by — so
  there is one form shape and one nil reason. A `nil` read means exactly one
  thing here: `interop/debug-enabled?` is false, i.e. a production build, which
  is `:evidence-tier-inactive`. (The donor door also nil-returned for an
  undeclared VIEW ID; there is no such case to distinguish, because there is no
  such argument.)

  The whole form is one `try` so a throwing read degrades to
  `:evidence-tier-error` rather than rejecting the eval."
  [read-fn]
  (let [fq (str tier-ns "/" read-fn)]
    (str "(try"
         " (if (cljs.core/exists? " fq ")"
         " (let [p (" fq ")]"
         " (if (cljs.core/nil? p)"
         " {:ok? false, :reason :evidence-tier-inactive, :hint " (pr-str inactive-hint) "}"
         " (cljs.core/assoc p :ok? true)))"
         " {:ok? false, :reason :evidence-tier-unavailable, :hint " (pr-str unavailable-hint) "})"
         " (catch :default e {:ok? false, :reason :evidence-tier-error, :message (cljs.core/str e)}))")))

(defn- door-read-tool
  "The shared handler for the three nullary door reads."
  [conn raw-args read-fn fail-reason]
  (probe/eval-after-runtime!
    conn (wire/arg-build conn raw-args) (projection-form read-fn)
    fail-reason versioned-envelope-result))

(defn read-mounted-boundaries-tool
  "MCP `read-mounted-boundaries` — every Hicasso boundary MOUNTED RIGHT NOW: its
  key, the number of `:instances` holding that key, the frame, and the read
  edges it holds (each with its `:sub-id`, its projected `:query` and the cell's
  `:epoch` — never the value the read returned). No arg, deliberately: the
  question is *what is mounted*.

  A boundary's KEY is its read set, because that is the only identity the
  runtime retains — two boundaries reading the same set are indistinguishable to
  it, and `:instances` is how many hold the key. `:view` and `:source` are
  `:unknown` under the `:naming` projection, permanently.

  `:complete? true` is a claim about UNDER-reporting and it is exact: a boundary
  whose body read nothing still claims an entry, so it is counted. An empty
  roster says *no boundary holds a live read edge right now* — it does NOT say
  nothing is retained above, because an Activity-hidden subtree that released
  its reads leaves the same empty census as an unmounted one. Read the other
  way, a row is not proof the boundary is on SCREEN: a Suspense-fallback-hidden
  subtree stays subscribed. The `:host` projection states both."
  [conn raw-args]
  (door-read-tool conn raw-args "read-mounted-boundaries" :read-mounted-boundaries-failed))

(defn read-read-attribution-tool
  "MCP `read-read-attribution` — which boundaries read each subscription: the
  reverse edge, exactly. Per subscription its `:sub-id`, projected `:query`,
  `:frame-id`, the cell's `:epoch`, the `:fan-out` (one slot per reading
  boundary) and the distinct `:readers` holding them. No arg.

  THIS IS THE READ THAT IS EXACT WITHOUT QUALIFICATION — it prints a table
  rather than folding a window or naming what it cannot see. It is also the way
  IN: a boundary here is keyed by an edge set and carries no name, so an agent
  that can name a subscription uses this read to reach the boundary it cannot
  name. `:readers` are the same keys `read-mounted-boundaries` states, derived
  by the same total order, so the two rosters join without a correlation step.

  A key nothing holds is absent rather than present with zero readers —
  correctly: it is not a subscription with no readers, it is a subscription this
  runtime is not holding."
  [conn raw-args]
  (door-read-tool conn raw-args "read-read-attribution" :read-read-attribution-failed))

(defn explain-render-tool
  "MCP `explain-render` — which of a boundary's reads moved most recently, and
  which retained runs COULD have driven it. No arg: it spans every mounted
  boundary.

  Two halves, never blended. PROVEN: `:latest-reads` names the boundary's reads
  standing at its own maximum `:peak-epoch`, read off the cells' own stamps, and
  `:snapshot` is the exact sum React compares. UNCORRELATED: Hicasso's commit
  seam records no cascade id, so `:cause` is `:unknown` STRUCTURALLY — not
  occasionally, and not fixable with a bigger ring — and `:candidates` are the
  retained runs that recomputed a subscription this boundary reads, offered as
  LEADS. The two loss reasons are drivable: `{:reason :uncorrelated}` means the
  lead search really ran and `:candidates []` is an honest survey result, while
  `{:reason :cap}` means the window was empty so `:candidates` is `:unknown`.

  Whether the boundary then RAN is `:host-opaque` — a notification delivered is
  not a render performed, and React's memo comparator and scheduler sit above
  this runtime."
  [conn raw-args]
  (door-read-tool conn raw-args "explain-render" :explain-render-failed))
