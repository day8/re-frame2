(ns re-frame.hicasso.impl.error
  "**THE ONE REFUSAL SHAPE** (rf2-hic-007) — spec SN §3.6, *Loud, stable
  failure*:

  > Every refusal has a stable error id, source coordinate, view, frame
  > where relevant, tree path or host-prop position, offending value,
  > expected shape, and actionable recovery.

  Before this namespace there were SIX copies of `fail!` — codec,
  collector, intent, presence, route-link and state each carried the same
  eight lines, because the prototype was one file per lane and the copy
  moved them verbatim. Six copies is not six risks of divergence; it is
  six places where a NEW field of the shape has to be added, which is the
  same as saying the shape cannot grow. It grew here, once.

  ## What the constructor knows that a call site does not

  A refusal names itself — its `id`, the fn that raised it, why, and what
  to do instead — and those four are the caller's, because only the
  caller knows them. The other half of the shape is AMBIENT: *which view
  was rendering* and *where that view was written*. No call site can
  supply it — `codec/vec->element` refuses a bad head with no idea whose
  body produced the hiccup — so the constructor reads it, from the
  ledger below, and every refusal gains two fields nobody has to
  remember to pass.

  ## The ledger, and why a name is enough

  `defview` and `defhost` capture `:ns` / `:file` / `:line` / `:column`
  at MACRO-EXPANSION time and hand them here through [[declaring!]],
  keyed by the same `\"<ns>/<sym>\"` string the macro stamps as
  `displayName`. That key is the whole of the coupling: the mint doors
  need no new parameter, the minted head carries no new property, and
  [[traced-boundary]] — which already has the view name in hand — can
  resolve the coordinate lazily, at the moment a refusal is raised and
  never before.

  ## Dev only, and erased rather than emptied

  Every coordinate is emitted by the macro inside
  `(when re-frame.interop/debug-enabled? …)`, so under `:advanced` +
  `goog.DEBUG=false` the Closure compiler removes the map literal, its
  file string and the [[declaring!]] call together. Nothing here degrades
  to an empty map in production: the ledger is simply never written, and
  [[fail!]]'s own gate means the two ambient fields are ABSENT from the
  ex-data rather than present and nil. An absent field says *this build
  does not carry coordinates*; a nil one would say *this refusal has no
  coordinate*, and those are different claims.

  ## The completeness guard is a runtime law, not a review note

  [[fail!]] refuses to mint an incomplete refusal (dev builds). The
  alternative — asserting completeness in a test per refusal id — checks
  the refusals somebody remembered to enumerate and nothing else, which
  is precisely how the shape drifted into six copies. The guard is on the
  one door every refusal now passes through, so a refusal minted without
  a recovery cannot reach a user under any code path, tested or not."
  (:require [clojure.string :as str]
            [re-frame.interop :as interop]))

;; ---------------------------------------------------------------------------
;; The declaration ledger — dev only
;; ---------------------------------------------------------------------------

(def ^:private !sources
  "`\"<ns>/<sym>\"` → the coordinate map its `defview` / `defhost`
  captured. Written once per declaration at namespace load, read only
  when a refusal is being minted, so an ordinary map in an atom is the
  right shape: there is no hot path here to pay for.

  Empty in production, and empty for a boundary minted by calling
  `impl.collector/mint-view!` directly — a test harness, a tool, an HMR
  re-registration. A missing coordinate is reported by its ABSENCE from
  the refusal, never by a placeholder."
  (atom {}))

(def ^:private !origin
  "The declaration whose extent the runtime is inside right now — the
  view whose body is running ([[traced-boundary]]), or the declaration
  being minted ([[declaring!]]) — or nil, which is the honest answer for
  a refusal raised from an event handler, a callback, a timer or a
  top-level form.

  ONE slot rather than a stack, and legal for the reason
  `impl.collector`'s render context is: **boundary bodies do not nest.**
  A body returns hiccup and the codec turns a child boundary into an
  *element*, so React runs that child's body later, after this one has
  returned. [[traced-boundary]] saves and restores anyway — the cost is
  one local in a dev-only wrapper, and an invariant that is cheap to
  survive should be survived rather than relied upon."
  (volatile! nil))

(defn declaring!
  "Open a declaration's extent: remember `coord` under `decl-name` and
  make it the ambient origin. Called by the `defview` / `defhost`
  expansion, inside its own `debug-enabled?` gate, immediately before the
  mint — so a refusal the mint itself raises (`defhost`'s unknown option,
  its bad `:ssr` policy, a boundary head in a declared fallback) carries
  the coordinate of the declaration that is wrong.

  Returns nil. `coord` may be nil; the name is still registered, which is
  what keeps [[source-of]]'s answer for a coordinate-less declaration
  distinguishable from its answer for an unknown one."
  [decl-name coord]
  (swap! !sources assoc decl-name coord)
  (vreset! !origin decl-name)
  nil)

(defn declared!
  "Close the declaration extent [[declaring!]] opened. Paired by the
  macro expansion, so the origin does not outlive the `def` and a later
  refusal from ordinary application code is not attributed to whichever
  view happened to be declared last.

  A mint that THROWS skips this, deliberately: the refusal has already
  been minted with the right origin, the namespace is not going to finish
  loading, and the next [[declaring!]] overwrites the slot."
  []
  (vreset! !origin nil)
  nil)

(defn source-of
  "The coordinate captured for `decl-name`, or nil — for a name never
  declared through the macros, or in a production build where the whole
  ledger is unwritten."
  [decl-name]
  (get @!sources decl-name))

(defn traced-boundary
  "Wrap a minted boundary's component fn so that the view it belongs to is
  the ambient origin for the duration of its render.

  **Dev only, and applied by the mint door under its own gate**, so
  `(if interop/debug-enabled? (traced-boundary …) component)` folds to
  `component` under `:advanced` + `goog.DEBUG=false` and the boundary
  React calls is byte-for-byte the one that existed before this bead.

  It wraps the COMPONENT rather than the body, which buys the shell's own
  two refusals — `:rf.error/no-frame-context` and
  `:rf.error/no-frame-prop` are raised before the body runs, and a
  boundary that refuses to render at all should still be able to say
  which boundary it was."
  [view-name component]
  (fn hicasso-traced-boundary [js-props]
    (let [prev @!origin]
      (vreset! !origin view-name)
      (try
        (component js-props)
        (finally
          (vreset! !origin prev))))))

;; ---------------------------------------------------------------------------
;; The shape
;; ---------------------------------------------------------------------------

(def shape
  "The fields every refusal carries, in the order spec SN §3.6 names them.
  Data rather than prose so a test can assert against the contract instead
  of against a copy of it.

  `:view` and `:source` are AMBIENT — supplied by [[fail!]] from the
  ledger, absent in production and absent outside any declaration extent.
  `:frame`, `:position` / `:path`, the offending value and the expected
  shape are situational: they belong to the refusal CLASS, ride in
  [[fail!]]'s `extra`, and are the complaint catalogue's to enumerate
  (rf2-hic-021). The four this namespace can guarantee for every refusal
  are the four [[complete?]] checks."
  [:rf.error/id :source :view :where :reason :recovery])

(def required
  "The subset of [[shape]] no refusal may omit — the ones a caller must
  pass, and the ones [[fail!]]'s guard enforces. The ambient pair is not
  here: a refusal raised outside every declaration extent genuinely has
  no view, and demanding one would make the guard lie."
  [:rf.error/id :where :reason :recovery])

(defn missing-fields
  "Which of [[required]] this refusal fails to supply, in [[required]]
  order. Empty when the refusal is complete. Public because the shape
  test asserts on it directly, which keeps the test and the guard reading
  the same rule rather than two copies of it."
  [id where reason recovery]
  (cond-> []
    (not (qualified-keyword? id))                     (conj :rf.error/id)
    (nil? where)                                      (conj :where)
    (or (not (string? reason)) (str/blank? reason))   (conj :reason)
    (nil? recovery)                                   (conj :recovery)))

(defn- check-complete!
  "Refuse to mint a refusal that does not carry [[required]].

  This is the meta-refusal, and it is deliberately the loudest thing in
  the file: a diagnostic that reaches a user without a recovery has spent
  the user's attention and given nothing back, which is worse than the
  original defect. Dev builds only — in production the incomplete refusal
  is thrown as-is rather than replaced, because a guard that swaps one
  exception for another in the field would hide the very failure the user
  is trying to report."
  [id where reason recovery]
  (let [missing (missing-fields id where reason recovery)]
    (when (seq missing)
      (throw (ex-info (str "A refusal was minted without " (pr-str missing)
                           ". Every refusal carries an id, the fn that raised it, "
                           "a reason and a concrete recovery (spec SN §3.6); this "
                           "one carries " (pr-str (vec (remove (set missing) required)))
                           ". [:rf.error/hicasso-refusal-incomplete]")
                      {:rf.error/id :rf.error/hicasso-refusal-incomplete
                       :where       're-frame.hicasso.impl.error/fail!
                       :reason      "A refusal must carry every required field of the stable error shape."
                       :recovery    :give-the-refusal-every-required-field
                       :refusal     id
                       :missing     missing})))))

(defn- with-origin
  "Stamp the ambient `:view` and `:source` onto a refusal's ex-data.

  Under `:advanced` + `goog.DEBUG=false` the whole body folds to `m` —
  the ledger is empty there because nothing ever wrote it, and this gate
  is what makes that emptiness show up as an ABSENT field rather than a
  nil one."
  [m]
  (if interop/debug-enabled?
    (if-some [view @!origin]
      (let [coord (source-of view)]
        (cond-> (assoc m :view view)
          (some? coord) (assoc :source coord)))
      m)
    m))

(defn fail!
  "**Mint and throw a refusal.** The one constructor; every refusal in the
  package routes through it.

  `id` is the stable `:rf.error/…` discriminator a test or a tool
  branches on, `where` the symbol naming the fn that refused, `reason` the
  human sentence, `recovery` the keyword naming the concrete fix, and
  `extra` the refusal class's own situational fields — the offending
  value, the tree path or host-prop position, the frame where one is
  relevant.

  The message is the reason with the id's greppability token appended,
  which is `re-frame.error`'s contract reproduced rather than delegated:
  routing the complaint TEXT through core's builder would change every
  sentence in the package, and that is the complaint catalogue's ruling
  to make (rf2-hic-021), not this bead's.

  `extra` merges LAST, so a call site that knows better than the ambient
  ledger can say so — and, as before this bead, so can a call site that
  wants to name its own frame."
  [id where reason recovery extra]
  (when interop/debug-enabled?
    (check-complete! id where reason recovery))
  ;; rf2:builder-bypass-ok - `id` is a PARAMETER here, so the runtime
  ;; message carries the `[:rf.error/...]` token the source cannot show
  ;; (the gate's own "computed discriminator" case). Re-routing the
  ;; complaint text through `re-frame.error` is rf2-hic-021's ruling.
  (throw (ex-info (str reason " [" id "]")
                  (merge (with-origin {:rf.error/id id :where where
                                       :reason reason :recovery recovery})
                         extra))))
