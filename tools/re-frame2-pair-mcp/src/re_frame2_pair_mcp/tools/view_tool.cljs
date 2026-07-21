(ns re-frame2-pair-mcp.tools.view-tool
  "Tools: read-view-manifest / read-view-dependencies / read-view-event-sites /
  read-mounted-views / explain-render — the FIVE read-only projections of the
  compiled-view tool tier (`re-frame.ui.tool`, framework rf2-vxgfnd.95.6; bead
  rf2-vxgfnd.95.8 — the Pair half of the S3 view-evidence consumers).

  These expose, from a RUNNING re-frame2 app, the same S3 view evidence Xray and
  Story read — so a pairing agent inspects a compiled view WITHOUT reaching a
  private React / observation-handle / scheduler object. Each tool ships ONE
  self-describing CLJS form that calls a `re-frame.ui.tool` projection and
  forwards the bounded, serializable, VERSIONED result verbatim; the projection
  stamps `:rf.ui.tool/version` and egresses only plain data (no ViewCell /
  observation-handle / React object), so the wire carries no raw internal handle.

    | MCP wire tool           | `re-frame.ui.tool` projection | arg      |
    |-------------------------|-------------------------------|----------|
    | `read-view-manifest`    | `view-manifest`               | view-id  |
    | `read-view-dependencies`| `view-dependencies`           | view-id  |
    | `read-view-event-sites` | `view-event-sites`            | view-id  |
    | `read-mounted-views`    | `mounted-views`               | —        |
    | `explain-render`        | `explain-render`              | view-id? |

  The wire names carry a NAMING.md-conformant verb prefix (`read-` / `explain-`);
  the framework fn names (`view-manifest`, …) do not, so the four manifest reads
  take the `read-<thing>` idiom `read-ui`/`read-dom`/`read-sub` already use.

  ## Tier presence — direct eval, no preload coupling (deliberate divergence)

  Unlike the `read-ui`/`read-dom` wrapper pattern, these tools DO NOT route
  through a `re-frame2-pair.runtime` fn. `re-frame.ui.tool` lives in
  `day8/re-frame2-ui` — the OPTIONAL compiled-view substrate; a re-frame2 app
  built on plain Reagent never has it on its classpath. Requiring it in the
  generic preload would make the preload uncompilable in those apps (the memory
  'Skill content generic to any app'). So each tool evals a self-contained form
  guarded by `cljs.core/exists?`: the tier is called only when present, and its
  absence is surfaced HONESTLY as `:reason :view-tier-unavailable` — the S3
  'tolerate absent evidence explicitly' discipline, not a fabricated emptiness.

  ## Absent / older evidence + version gate (S3 tolerance)

  The eval form resolves to a `{:ok? …}` envelope, which the shared
  `versioned-envelope-result` `on-value` GATES against `consumed-schema-version`
  — the evidence-schema version THIS Pair build understands — before it reaches
  the wire:

    - `{:ok? true …projection…}`      — the projection, `:rf.ui.tool/version`
                                        MATCHING `consumed-schema-version`,
                                        forwarded verbatim;
    - `{:ok? false :reason :view-tier-version-mismatch :expected N :actual M}` —
                                        the projection stamped a version this
                                        build was NOT written against. Pair
                                        connects to an arbitrary running app, so
                                        the producer's stamp cannot define
                                        support: an unrecognized version is a
                                        typed mismatch, never forwarded as
                                        success (the version boundary the tier
                                        promises, made real on the consumer side);
    - `{:ok? false :reason :view-tier-unavailable}`  — `re-frame.ui.tool` is not
                                        loaded (optional substrate absent);
    - `{:ok? false :reason :view-not-available :view-id …}` — no compiled view
                                        is registered under that id (nil manifest);
    - `{:ok? false :reason :view-tier-inactive}`     — a production build
                                        nil-gates the whole tier;
    - `{:ok? false :reason :view-tier-error :message …}` — the projection threw.

  Every `:ok? false` rides `isError:true` via `versioned-envelope-result` (which
  wraps `probe/map-envelope-result`; spec/003 §'Every :ok? false response is
  isError: true'), so a degraded read — an unavailable tier, an absent view, OR a
  version mismatch — is never cached and never masquerades as a successful
  answer.

  ## Hot swap — the existing nREPL HMR path

  There is NO new reload protocol. A pairing agent edits a compiled view, lets
  shadow-cljs recompile over its normal watch (the existing `tail-build` HMR
  path), and re-reads `read-mounted-views` / `explain-render`: a COMPATIBLE body
  swap RETAINS the connected incarnation (the occurrence identity + lifecycle log
  survive), an INCOMPATIBLE change REMOUNTS it (a fresh occurrence) — the honest
  retention-vs-remount evidence the tier already records.

  ## Sensitive-data projection

  These reads project a view's compiler MANIFEST + render EVIDENCE (structure,
  declared sites, occurrence/cause/loss counts, authored event vectors) — NOT
  app-db values. They carry no app-db-classified slot, so — like the sibling
  registrar reads `handler-meta` / `list-handlers` — they need no elision /
  sensitive walker: the 'normal projection' for a non-app-db read is verbatim
  bounded data. (An authored event vector shows its id + literal args, exactly
  as `handler-meta` surfaces a handler's source coordinate.)"
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(def ^:private tier-ns
  "The fully-qualified projection tier the eval forms call. Centralised so a
  framework rename is a single edit."
  "re-frame.ui.tool")

(def ^:private unavailable-hint
  (str "the re-frame.ui.tool projection tier is not loaded in this app — it "
       "lives in day8/re-frame2-ui (the optional compiled-view substrate). It "
       "is present whenever Xray is active, or when the app itself loads it. "
       "Load re-frame.ui.tool into the running build and retry."))

(def consumed-schema-version
  "The `re-frame.ui.tool` evidence-schema version THIS Pair build was written to
  forward — a CONSUMER-OWNED literal. Pair connects to an ARBITRARY running app
  that may ship an older or newer tier, so it CANNOT trust the producer's own
  `:rf.ui.tool/version` stamp to define support: a projection stamped a version
  this build does not understand is reported as a typed `:ok? false` mismatch,
  never forwarded as a successful read of a shape it cannot parse. Currently 3,
  matching `re-frame.ui.tool/schema-version` 3; bump ONLY when Pair is taught the
  new shape."
  3)

(defn versioned-envelope-result
  "The `on-value` projection for the five view-tool reads: `probe/map-envelope-
  result` PLUS a consumer-owned version GATE. A `:ok? true` projection stamped a
  `:rf.ui.tool/version` this build was not written against is converted to a
  typed `{:ok? false :reason :view-tier-version-mismatch}` (isError) rather than
  forwarded as success — Pair may reach an arbitrarily old/new app, so the
  producer's stamp does not define support. Every other value (a `:ok? false`
  absence/error envelope, a genuinely-empty `:ok? true` version-matched read, or
  a blank/non-map result) defers UNCHANGED to `map-envelope-result`, so the
  isError contract and honest-emptiness handling are unchanged."
  [v]
  (if (and (map? v)
           (true? (:ok? v))
           (not= consumed-schema-version (:rf.ui.tool/version v)))
    (wire/err-text {:ok?      false
                    :reason   :view-tier-version-mismatch
                    :expected consumed-schema-version
                    :actual   (:rf.ui.tool/version v)
                    :hint     (str "the running app's re-frame.ui.tool tier stamped "
                                   "evidence-schema version "
                                   (pr-str (:rf.ui.tool/version v))
                                   " but this pair build understands version "
                                   consumed-schema-version
                                   " — the projection shape may have evolved "
                                   "incompatibly. Align the app's day8/re-frame2-ui "
                                   "with this tool build (or update the tool).")})
    (probe/map-envelope-result v)))

(defn projection-form
  "Build the `cljs.core/exists?`-guarded, self-describing eval form for a
  `re-frame.ui.tool` projection. Pure string → string, so the form composition
  is unit-checkable off the wire.

    `proj-fn`    the projection fn name (e.g. \"view-manifest\").
    `call-args`  a vector of raw CLJS source arg strings (e.g. [\":my/view\"]),
                 empty for a no-arg projection.
    `nil-reason` the `:reason` when the projection returns nil — `:view-not-
                 available` (an unregistered view) or `:view-tier-inactive`
                 (a production build nil-gates the tier).
    `view-id`    echoed into the nil envelope when present (the view reads).

  The whole form is one `try` so a throwing projection degrades to
  `:view-tier-error` rather than rejecting the eval."
  [proj-fn call-args nil-reason view-id]
  (let [fq       (str tier-ns "/" proj-fn)
        call     (str "(" fq (when (seq call-args)
                               (str " " (str/join " " call-args))) ")")
        nil-env  (str "{:ok? false, :reason " (pr-str nil-reason)
                      (when (some? view-id) (str ", :view-id " (pr-str view-id)))
                      ", :hint "
                      (pr-str (if (= :view-not-available nil-reason)
                                (str "no compiled re-frame.ui view is registered under "
                                     (pr-str view-id) " (nil manifest), or the "
                                     "view-tool tier is inactive (production build)")
                                "the re-frame.ui.tool tier is inactive — a production build nil-gates it"))
                      "}")]
    (str "(try"
         " (if (cljs.core/exists? " fq ")"
         " (let [p " call "]"
         " (if (cljs.core/nil? p) " nil-env " (cljs.core/assoc p :ok? true)))"
         " {:ok? false, :reason :view-tier-unavailable, :hint " (pr-str unavailable-hint) "})"
         " (catch :default e {:ok? false, :reason :view-tier-error, :message (cljs.core/str e)}))")))

(defn- require-view-id
  "Coerce + require the `:view-id` arg (the compiled view's registry id). Accepts
  a bare or colon-shaped string / keyword via the shared `->frame-keyword`
  coercer (nil for a non-string), returning `[:ok view-id-kw]` or an `[:err
  promise]` short-circuit envelope."
  [raw-args tool-name]
  (let [view-id (some-> (wire/arg raw-args :view-id) args/->frame-keyword)]
    (if (some? view-id)
      [:ok view-id]
      [:err (js/Promise.resolve
              (wire/err-text
                {:ok?  false :reason :missing-view-id
                 :hint (str "usage: " tool-name " {view-id \":my.app/header\"} "
                            "[build :app]. view-id is the compiled view's "
                            "registry id (a keyword) — read it from "
                            "read-mounted-views, read-ui, or the source defview.")}))])))

(defn- view-id-projection-tool
  "The shared handler for the three view-id-taking manifest reads
  (read-view-manifest / read-view-dependencies / read-view-event-sites)."
  [conn raw-args proj-fn fail-reason tool-name]
  (let [[status v] (require-view-id raw-args tool-name)]
    (if (= :err status)
      v
      (let [build-id (wire/arg-build conn raw-args)
            form     (projection-form proj-fn [(pr-str v)] :view-not-available v)]
        (probe/eval-after-runtime!
          conn build-id form fail-reason versioned-envelope-result)))))

(defn read-view-manifest-tool
  "MCP `read-view-manifest` — the versioned public projection of a compiled
  view's MANIFEST (what CAN happen): source coord, per-prop docs/defaults/schema,
  render-slot + interop sites, capability bits, fingerprints, site counts.
  Read-only; available BEFORE mount. Requires `:view-id`."
  [conn raw-args]
  (view-id-projection-tool conn raw-args "view-manifest"
                           :read-view-manifest-failed "read-view-manifest"))

(defn read-view-dependencies-tool
  "MCP `read-view-dependencies` — the subscription SITES a compiled view
  declares, with literal-vs-`:dynamic` query-shape honesty. Read from the
  manifest; available before mount. Requires `:view-id`."
  [conn raw-args]
  (view-id-projection-tool conn raw-args "view-dependencies"
                           :read-view-dependencies-failed "read-view-dependencies"))

(defn read-view-event-sites-tool
  "MCP `read-view-event-sites` — the event-handler SITES a compiled view declares,
  distinguishing literal / normalized event vectors (inspectable) from opaque
  `:dynamic` handlers (a raw callback is `:opaque`, never claimed inspectable).
  Requires `:view-id`."
  [conn raw-args]
  (view-id-projection-tool conn raw-args "view-event-sites"
                           :read-view-event-sites-failed "read-view-event-sites"))

(defn read-mounted-views-tool
  "MCP `read-mounted-views` — the committed CONNECTED-INSTANCE records for every
  incarnation the evidence tier currently retains: occurrence identity, owning
  root, live connection state, honest hide-vs-unmount lifecycle labels, and the
  bounded render evidence per incarnation. No arg (spans every retained
  incarnation). An empty-but-versioned `:views []` when no evidence tool is
  installed — never a fabricated instance."
  [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        form     (projection-form "mounted-views" [] :view-tier-inactive nil)]
    (probe/eval-after-runtime!
      conn build-id form :read-mounted-views-failed versioned-envelope-result)))

(defn explain-render-tool
  "MCP `explain-render` — why did a compiled view's live incarnations render? The
  bounded render CAUSES, occurrence/batch counters, first/latest movement epochs,
  the moving observation targets, and EXPLICIT loss accounting (`:dropped` with
  an `:exact?` flag — a count is a completeness claim only when exact). Optional
  `:view-id` narrows to one view; omitted, every retained incarnation. An
  empty-but-versioned `:occurrences []` with no evidence."
  [conn raw-args]
  (let [view-id  (some-> (wire/arg raw-args :view-id) args/->frame-keyword)
        build-id (wire/arg-build conn raw-args)
        form     (projection-form "explain-render"
                                  (when (some? view-id) [(pr-str view-id)])
                                  :view-tier-inactive nil)]
    (probe/eval-after-runtime!
      conn build-id form :explain-render-failed versioned-envelope-result)))
