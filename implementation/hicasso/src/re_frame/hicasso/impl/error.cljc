(ns re-frame.hicasso.impl.error
  "The package's one refusal constructor, and the dev-only ledger that
  lets a refusal name the view it was raised from.

  `fail!` builds every Hicasso throw through
  `re-frame.error/ex-info-from-data`, so the ex-data is core's 4-slot
  shape — `:rf.error/id`, `:where`, `:reason`, `:recovery :no-recovery`
  — plus the refusal class's own slots from `extra` and, in dev builds,
  the ambient `:view` / `:source` pair. `defview` and `defhost` write
  that pair's source here at macro-expansion time, keyed by the same
  `\"<ns>/<sym>\"` string the macro stamps as `displayName`; the whole
  ledger is inside `debug-enabled?` gates, so under `:advanced` +
  `goog.DEBUG=false` it is never written and the two fields are ABSENT
  from the ex-data rather than nil.

  One constructor rather than one per lane, so a field of the shape is
  added in one place. Argument: docs/design/hicasso/product/specification.md
  §3.6 (Loud, stable failure)."
  (:require [re-frame.error :as rf.error]
            [re-frame.interop :as rf.interop]))

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
  view whose body is running (`traced-boundary`), or the declaration
  being minted (`declaring!`) — or nil, which is the honest answer for
  a refusal raised from an event handler, a callback, a timer or a
  top-level form.

  ONE slot rather than a stack, and legal for the reason
  `impl.collector`'s render context is: **boundary bodies do not nest.**
  A body returns hiccup and the codec turns a child boundary into an
  *element*, so React runs that child's body later, after this one has
  returned. `traced-boundary` saves and restores anyway — the cost is
  one local in a dev-only wrapper, and an invariant that is cheap to
  survive should be survived rather than relied upon."
  (volatile! nil))

(defn declaring!
  "Open a declaration's extent: remember `coord` under `decl-name` and
  make it the ambient origin. Called by the `defview` / `defhost`
  expansion, inside its own `debug-enabled?` gate, immediately before the
  mint — so a refusal the mint itself raises (`defhost`'s unknown option,
  its bad `:server` policy, a boundary head in a declared fallback) carries
  the coordinate of the declaration that is wrong.

  Returns nil. `coord` may be nil, and a refusal raised under a nil one
  simply carries no `:source` — the same ABSENCE a production build
  shows, and the same one an undeclared name shows. Nothing here
  distinguishes those two, because nothing needs to: the only reader is
  `with-origin`, and its question is *is there a coordinate to stamp*."
  [decl-name coord]
  (swap! !sources assoc decl-name coord)
  (vreset! !origin decl-name)
  nil)

(defn declared!
  "Close the declaration extent `declaring!` opened. Paired by the
  macro expansion in a `finally`, so the origin does not outlive the
  `def` and a later refusal from ordinary application code is not
  attributed to whichever view happened to be declared last.

  **A mint that THROWS closes the extent too**, and the `finally` is the
  reason. Skipping it on the argument that the namespace is not going to
  finish loading anyway does not hold: a declaration refusal is
  routinely CAUGHT, by a module loader or by an HMR runtime whose already
  mounted page keeps rendering, and the slot then named a `def` that
  never completed. Every refusal raised afterwards — from an event
  handler, a timer, ordinary code with no boundary anywhere on the stack
  — inherited that dead declaration's `:view` and `:source` and pointed
  its reader at a file that has nothing to do with the failure.

  Nothing is lost by closing it: `fail!` builds the whole ex-data
  before it throws, so the refusal on its way out already carries the
  coordinate of the declaration that is wrong."
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
  React calls is byte-for-byte the undecorated component.

  It wraps the COMPONENT rather than the body, which buys the shell's own
  refusal — `:rf.error/no-frame-context` is raised before the body runs,
  and a boundary that refuses to render at all should still be able to
  say which boundary it was."
  [view-name component]
  (fn hicasso-traced-boundary [js-props]
    (let [prev @!origin]
      (vreset! !origin view-name)
      (try
        (component js-props)
        (finally
          (vreset! !origin prev))))))

;; ---------------------------------------------------------------------------
;; The constructor
;; ---------------------------------------------------------------------------

(def ^:private ambient
  "The two keys `fail!` supplies from the ledger rather than from the
  call site. They are REMOVED from `extra` before the merge, because
  outside every declaration extent — and in production — there is no
  origin to overwrite a forged `:view` with, and the constructor's
  silence is the claim."
  [:source :view])

(defn- with-origin
  "Stamp the ambient `:view` and `:source` onto a refusal's ex-data.

  Under `:advanced` + `goog.DEBUG=false` the whole body folds to `m` —
  the ledger is empty there because nothing ever wrote it, and this gate
  is what makes that emptiness show up as an ABSENT field rather than a
  nil one."
  [m]
  (if rf.interop/debug-enabled?
    (if-some [view @!origin]
      (let [coord (source-of view)]
        (cond-> (assoc m :view view)
          (some? coord) (assoc :source coord)))
      m)
    m))

(defn fail!
  "Mint and throw a Hicasso refusal. Never returns.

  `id` is the stable `:rf.error/…` discriminator a test or a tool branches
  on, `where` the symbol of the fn that refused, `reason` the human
  sentence, and `extra` the refusal class's own slots — the offending
  value, the tree path or host-prop position, the frame where one is
  relevant. The throw is `re-frame.error/ex-info-from-data` over
  `{:rf.error/id id :where where :reason reason :recovery :no-recovery}`,
  so the message is the reason with the bracketed id appended; in dev
  builds `:view` and `:source` are stamped from the ambient ledger.
  `extra` merges UNDER those fields: a canonical key spelled in `extra`
  loses, and the ambient pair is removed from `extra` before the merge.

  `:recovery` is always `:no-recovery` because every Hicasso refusal is a
  throw the runtime does not recover from; the fix lives in `:reason`.
  Argument: docs/design/hicasso/product/specification.md §3.6."
  [id where reason extra]
  (throw (rf.error/ex-info-from-data
           (merge (apply dissoc extra ambient)
                  (with-origin {:rf.error/id id :where where
                                :reason reason :recovery :no-recovery})))))
