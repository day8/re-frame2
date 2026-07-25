(ns re-frame2-pair-mcp.tools.view-tool
  "Tools: read-view-manifest / read-view-dependencies / read-view-event-sites /
  read-mounted-views / explain-render — the FIVE read-only projections of the
  Freehand TOOL-TIER reader door (`re-frame.freehand.tool`).

  These expose, from a RUNNING re-frame2 app, the same view evidence Xray and
  Story read — so a pairing agent inspects a Freehand view WITHOUT reaching a
  private React / cell / scheduler object. Each tool ships ONE self-describing
  CLJS form that calls a `re-frame.freehand.tool` read and forwards the bounded,
  serializable, VERSIONED result verbatim; every read answers inside the
  four-axis evidence projection (`:scope`, `:basis`, `:complete?`, `:loss`)
  stamped with `:schema` and `:read`, and egresses only plain data (no cell /
  React object), so the wire carries no raw internal handle.

    | MCP wire tool           | `re-frame.freehand.tool` read | arg      |
    |-------------------------|-------------------------------|----------|
    | `read-view-manifest`    | `read-view-manifest`          | view-id  |
    | `read-view-dependencies`| `read-view-dependencies`      | view-id  |
    | `read-view-event-sites` | `read-view-event-sites`       | view-id  |
    | `read-mounted-views`    | `read-mounted-views`          | —        |
    | `explain-render`        | `explain-render`              | view-id? |

  The wire names and the framework fn names now AGREE, because Freehand's
  id-taking reads carry their own `read-` prefix: `view-manifest` is the
  value-taking sweep door and could not be the id-taking one's arity, so the
  reads an inspector calls are spelled `read-view-manifest` on both sides of the
  wire. `explain-render` needs no prefix — it is already a verb.

  ## Four axes, not a lifetime summary — the fidelity this tier states

  Freehand's tool tier is deliberately THINNER than the donor's, and the
  difference is stated rather than papered over (rf2-drpa3.167 ruled the donor's
  cumulative per-occurrence evidence accumulator out permanently). What a
  pairing agent gets instead is honesty about the difference:

    - `read-mounted-views` answers what is CONNECTED RIGHT NOW — one row per
      connected occurrence, dropped at disconnect. It is not a lifetime roster,
      so there is no render total, no batch total, no hide-versus-unmount
      interval ledger and no accumulated union of every target ever observed.
      The row's `:occurrence-facts` set names exactly what a row does state, so
      an absent fact is visible as absent rather than inferred from silence.
    - `explain-render` folds Spec 009's RETAINED WINDOW at read time — it keeps
      nothing of its own. An explanation is `:complete? true` only when the run
      its commit was correlated to is still in the window; a disabled or empty
      ring, an evicted run, and a commit that was never correlated to any run
      are each reported as `:loss` with `:dropped :unknown`.
    - an INTERPRETED declaration answers the roster reads on an `:opaque` basis
      with `{:reason :no-static-analysis :dropped :unknown}` rather than an
      empty roster, because *unknown must not look like none*.

  A degraded read is therefore an explicit unknown, never a fabricated fact —
  which is the whole contract this tool forwards.

  ## Tier presence — direct eval, no preload coupling (deliberate divergence)

  Unlike the `read-ui`/`read-dom` wrapper pattern, these tools DO NOT route
  through a `re-frame2-pair.runtime` fn. `re-frame.freehand.tool` lives in
  `day8/re-frame2-freehand` — the OPTIONAL Freehand view substrate; a re-frame2
  app built on the Reagent or UIx adapter never has it on its classpath.
  Requiring it in the generic preload would make the preload uncompilable in
  those apps (the memory 'Skill content generic to any app'). So each tool evals
  a self-contained form guarded by `cljs.core/exists?`: the tier is called only
  when present, and its absence is surfaced HONESTLY as
  `:reason :view-tier-unavailable` — 'tolerate absent evidence explicitly', not
  a fabricated emptiness.

  ## Absent / older evidence + schema gate

  The eval form resolves to a `{:ok? …}` envelope, which the shared
  `versioned-envelope-result` `on-value` GATES against
  `consumed-evidence-schema` — the evidence-schema version THIS Pair build
  understands — before it reaches the wire:

    - `{:ok? true …projection…}`      — the projection, `:schema` MATCHING
                                        `consumed-evidence-schema`, forwarded
                                        verbatim;
    - `{:ok? false :reason :view-tier-version-mismatch :expected … :actual …}` —
                                        the projection stamped a schema this
                                        build was NOT written against. Pair
                                        connects to an arbitrary running app, so
                                        the producer's stamp cannot define
                                        support: an unrecognized schema is a
                                        typed mismatch, never forwarded as
                                        success (the version boundary the tier
                                        promises, made real on the consumer side);
    - `{:ok? false :reason :view-tier-unavailable}`  — `re-frame.freehand.tool`
                                        is not loaded (optional substrate absent);
    - `{:ok? false :reason :view-not-available :view-id …}` — no Freehand view
                                        is declared under that id (nil read);
    - `{:ok? false :reason :view-tier-inactive}`     — a production build
                                        nil-gates the whole tier;
    - `{:ok? false :reason :view-tier-error :message …}` — the read threw.

  Every `:ok? false` rides `isError:true` via `versioned-envelope-result` (which
  wraps `probe/map-envelope-result`; spec/003 §'Every :ok? false response is
  isError: true'), so a degraded read — an unavailable tier, an absent view, OR a
  schema mismatch — is never cached and never masquerades as a successful answer.

  ## Hot swap — the existing nREPL HMR path

  There is NO new reload protocol. A pairing agent edits a Freehand view, lets
  shadow-cljs recompile over its normal watch (the existing `tail-build` HMR
  path), and re-reads `read-mounted-views` / `explain-render`: a COMPATIBLE body
  swap RETAINS the connected occurrence (its `:generation` advances), an
  INCOMPATIBLE change REMOUNTS it (a fresh `:occurrence` key) — the honest
  retention-vs-remount evidence the current-occurrence index already records.

  ## Sensitive-data projection

  These reads project a view's compiler MANIFEST + bounded render EVIDENCE
  (structure, declared sites, occurrence identity, cause and loss accounting,
  authored event vectors) — NOT app-db values. A commit's `:reads` carry the
  QUERIES without the values they returned, deliberately: a value is application
  data, and an evidence read is not a second egress path for it. They carry no
  app-db-classified slot, so — like the sibling registrar reads `handler-meta` /
  `list-handlers` — they need no elision / sensitive walker: the 'normal
  projection' for a non-app-db read is verbatim bounded data. (An authored event
  vector shows its id + literal args, exactly as `handler-meta` surfaces a
  handler's source coordinate.)"
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(def ^:private tier-ns
  "The fully-qualified reader tier the eval forms call. Centralised so a
  framework rename is a single edit."
  "re-frame.freehand.tool")

(def ^:private unavailable-hint
  (str "the re-frame.freehand.tool reader tier is not loaded in this app — it "
       "lives in day8/re-frame2-freehand (the optional Freehand view "
       "substrate). It is present whenever Xray is active, or when the app "
       "itself loads it. Load re-frame.freehand.tool into the running build "
       "and retry."))

(def consumed-evidence-schema
  "The `re-frame.freehand.tool` evidence-schema version THIS Pair build was
  written to forward — a CONSUMER-OWNED literal. Pair connects to an ARBITRARY
  running app that may ship an older or newer tier, so it CANNOT trust the
  producer's own `:schema` stamp to define support: a projection stamped a
  schema this build does not understand is reported as a typed `:ok? false`
  mismatch, never forwarded as a successful read of a shape it cannot parse.
  Currently `:re-frame.freehand.evidence/v1`, matching
  `re-frame.freehand.evidence/schema`; bump ONLY when Pair is taught the new
  shape."
  :re-frame.freehand.evidence/v1)

(defn versioned-envelope-result
  "The `on-value` projection for the five view-tool reads: `probe/map-envelope-
  result` PLUS a consumer-owned schema GATE. A `:ok? true` projection stamped a
  `:schema` this build was not written against is converted to a typed
  `{:ok? false :reason :view-tier-version-mismatch}` (isError) rather than
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
                    :reason   :view-tier-version-mismatch
                    :expected consumed-evidence-schema
                    :actual   (:schema v)
                    :hint     (str "the running app's re-frame.freehand.tool tier stamped "
                                   "evidence schema "
                                   (pr-str (:schema v))
                                   " but this pair build understands "
                                   (pr-str consumed-evidence-schema)
                                   " — the projection shape may have evolved "
                                   "incompatibly. Align the app's "
                                   "day8/re-frame2-freehand with this tool build "
                                   "(or update the tool).")})
    (probe/map-envelope-result v)))

(defn projection-form
  "Build the `cljs.core/exists?`-guarded, self-describing eval form for a
  `re-frame.freehand.tool` read. Pure string → string, so the form composition
  is unit-checkable off the wire.

    `read-fn`    the reader fn name (e.g. \"read-view-manifest\").
    `call-args`  a vector of raw CLJS source arg strings (e.g. [\":my/view\"]),
                 empty for a no-arg read.
    `nil-reason` the `:reason` when the read returns nil — `:view-not-
                 available` (an undeclared view) or `:view-tier-inactive`
                 (a production build nil-gates the tier).
    `view-id`    echoed into the nil envelope when present (the view reads).

  The whole form is one `try` so a throwing read degrades to
  `:view-tier-error` rather than rejecting the eval."
  [read-fn call-args nil-reason view-id]
  (let [fq       (str tier-ns "/" read-fn)
        call     (str "(" fq (when (seq call-args)
                               (str " " (str/join " " call-args))) ")")
        nil-env  (str "{:ok? false, :reason " (pr-str nil-reason)
                      (when (some? view-id) (str ", :view-id " (pr-str view-id)))
                      ", :hint "
                      (pr-str (if (= :view-not-available nil-reason)
                                (str "no Freehand view is declared under "
                                     (pr-str view-id) " (nil read), or the "
                                     "view-tool tier is inactive (production build)")
                                "the re-frame.freehand.tool tier is inactive — a production build nil-gates it"))
                      "}")]
    (str "(try"
         " (if (cljs.core/exists? " fq ")"
         " (let [p " call "]"
         " (if (cljs.core/nil? p) " nil-env " (cljs.core/assoc p :ok? true)))"
         " {:ok? false, :reason :view-tier-unavailable, :hint " (pr-str unavailable-hint) "})"
         " (catch :default e {:ok? false, :reason :view-tier-error, :message (cljs.core/str e)}))")))

(defn- require-view-id
  "Coerce + require the `:view-id` arg (the Freehand view's declared id). Accepts
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
                            "[build :app]. view-id is the Freehand view's "
                            "declared id (a keyword) — read it from "
                            "read-mounted-views, read-ui, or the source defview.")}))])))

(defn- view-id-projection-tool
  "The shared handler for the three view-id-taking declaration reads
  (read-view-manifest / read-view-dependencies / read-view-event-sites)."
  [conn raw-args read-fn fail-reason tool-name]
  (let [[status v] (require-view-id raw-args tool-name)]
    (if (= :err status)
      v
      (let [build-id (wire/arg-build conn raw-args)
            form     (projection-form read-fn [(pr-str v)] :view-not-available v)]
        (probe/eval-after-runtime!
          conn build-id form fail-reason versioned-envelope-result)))))

(defn read-view-manifest-tool
  "MCP `read-view-manifest` — what the compiler statically knows about a Freehand
  view (what CAN happen): the manifest verbatim under `:manifest`, inside the
  projection that says how far to trust it. Read-only; available BEFORE mount.
  An interpreted declaration answers `:manifest nil` on an `:opaque` basis
  rather than an empty roster. Requires `:view-id`."
  [conn raw-args]
  (view-id-projection-tool conn raw-args "read-view-manifest"
                           :read-view-manifest-failed "read-view-manifest"))

(defn read-view-dependencies-tool
  "MCP `read-view-dependencies` — the subscription SITES a Freehand view
  declares, with literal-vs-`:dynamic` query-shape honesty. Read from the
  manifest; available before mount. Requires `:view-id`."
  [conn raw-args]
  (view-id-projection-tool conn raw-args "read-view-dependencies"
                           :read-view-dependencies-failed "read-view-dependencies"))

(defn read-view-event-sites-tool
  "MCP `read-view-event-sites` — the event-handler SITES a Freehand view declares,
  distinguishing literal / normalized event vectors (inspectable) from opaque
  `:dynamic` handlers (a raw callback is `:opaque`, never claimed inspectable).
  Requires `:view-id`."
  [conn raw-args]
  (view-id-projection-tool conn raw-args "read-view-event-sites"
                           :read-view-event-sites-failed "read-view-event-sites"))

(defn read-mounted-views-tool
  "MCP `read-mounted-views` — every Freehand occurrence CONNECTED RIGHT NOW: its
  view id, occurrence key, lowering, generation, live connection state and its
  selected commit (frame + the queries that commit read, without their values).
  No arg, deliberately — the question is *what is mounted*. CURRENT STATE, not a
  lifetime roster: the row's `:occurrence-facts` set names exactly what a row
  states, so a lifetime quantity is visibly not a fact it has."
  [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        form     (projection-form "read-mounted-views" [] :view-tier-inactive nil)]
    (probe/eval-after-runtime!
      conn build-id form :read-mounted-views-failed versioned-envelope-result)))

(defn explain-render-tool
  "MCP `explain-render` — why did a Freehand view's live occurrences render?
  Folded from Spec 009's RETAINED WINDOW at read time, so an explanation is
  `:complete? true` only when the run its commit was correlated to is still in
  the window; an empty ring, an evicted run and an uncorrelated commit are each
  reported as `:loss` with `:dropped :unknown`, and `:candidates` are offered as
  LEADS rather than as the answer. Optional `:view-id` narrows to one view;
  omitted, every current occurrence."
  [conn raw-args]
  (let [view-id  (some-> (wire/arg raw-args :view-id) args/->frame-keyword)
        build-id (wire/arg-build conn raw-args)
        form     (projection-form "explain-render"
                                  (when (some? view-id) [(pr-str view-id)])
                                  :view-tier-inactive nil)]
    (probe/eval-after-runtime!
      conn build-id form :explain-render-failed versioned-envelope-result)))
