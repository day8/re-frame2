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
  a recovery cannot reach a user under any code path, tested or not.

  It reads the map it is ABOUT TO THROW rather than the arguments it was
  handed, and the distinction is the whole of the guarantee. A guard that
  validates its inputs and then merges a call site's payload over them
  has checked a map that no longer exists: `extra` carrying
  `{:recovery nil}` used to win outright, so the one field the guard
  exists to insist on could be erased immediately after it was insisted
  on. [[required]] and [[ambient]] are now the CONSTRUCTOR's — the
  required four merged over `extra` rather than under it, the ambient
  pair stripped from `extra` outright — and [[missing-fields]] reads the
  result."
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

  Returns nil. `coord` may be nil, and a refusal raised under a nil one
  simply carries no `:source` — the same ABSENCE a production build
  shows, and the same one an undeclared name shows. Nothing here
  distinguishes those two, because nothing needs to: the only reader is
  [[with-origin]], and its question is *is there a coordinate to stamp*."
  [decl-name coord]
  (swap! !sources assoc decl-name coord)
  (vreset! !origin decl-name)
  nil)

(defn declared!
  "Close the declaration extent [[declaring!]] opened. Paired by the
  macro expansion in a `finally`, so the origin does not outlive the
  `def` and a later refusal from ordinary application code is not
  attributed to whichever view happened to be declared last.

  **A mint that THROWS closes the extent too**, and the `finally` is the
  reason. It used to be skipped on the argument that the namespace was
  not going to finish loading anyway — but a declaration refusal is
  routinely CAUGHT, by a module loader or by an HMR runtime whose already
  mounted page keeps rendering, and the slot then named a `def` that
  never completed. Every refusal raised afterwards — from an event
  handler, a timer, ordinary code with no boundary anywhere on the stack
  — inherited that dead declaration's `:view` and `:source` and pointed
  its reader at a file that has nothing to do with the failure.

  Nothing is lost by closing it: [[fail!]] builds the whole ex-data
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

(def required
  "The four fields no refusal may omit — the caller's half of the shape,
  because only the caller knows them, and the half [[fail!]]'s guard
  enforces on the map it is about to throw.

  The ambient pair is deliberately not here: a refusal raised outside
  every declaration extent genuinely has no view, and demanding one would
  make the guard lie."
  [:rf.error/id :where :reason :recovery])

(def ambient
  "The two fields [[fail!]] supplies rather than the call site — which
  view was rendering, and where that view was written. Absent, never nil,
  in production and outside every declaration extent (see the ns
  docstring on why those are different claims).

  Named as data beside [[required]] because together the two lists are
  exactly the keys the CONSTRUCTOR owns — and because [[fail!]] READS
  this vector, to remove these two keys from `extra` before the merge.

  Removing rather than overwriting, and the difference is why this pair
  needs its own rule. A required field is always there to overwrite
  with; an ambient one exists only while an origin names a view, so
  outside every declaration extent — and in production, where
  [[with-origin]] folds to its input — there was nothing to overwrite
  and a forged `:view` in `extra` simply survived. The ABSENCE promised
  above is a claim about what the constructor knows, and it is only
  worth making if the constructor's silence beats a call site's
  invention."
  [:source :view])

(def shape
  "The fields the constructor puts on every refusal — [[required]] and
  [[ambient]], in the order spec SN §3.6 names them. Data rather than
  prose so a test can assert against the contract instead of against a
  copy of it.

  ## What this is six of, and where the rest lives

  §3.6 names EIGHT things: a stable error id, a source coordinate, a
  view, a frame where relevant, a tree path or host-prop position, the
  offending value, the expected shape, and an actionable recovery. Four
  of those are here (`:rf.error/id`, `:source`, `:view`, `:recovery`),
  and `:where` and `:reason` are the package's own two additions — the fn
  that refused, and the human sentence.

  The other four — frame, path or position, offending value, expected
  shape — are **per-CLASS, and not universal**, which is why they are not
  in this vector and are not enforceable at this door:

  - `:rf.error/hicasso-empty-vector` has no offending value. The offence
    IS the absence of a head, and `{:value []}` would repeat the id.
  - `:rf.error/hicasso-true-child` has none either, at the codec's arm:
    the refusal fires only when the child IS `true`, so the value is a
    constant the id already names.
  - `:rf.error/no-frame-context`, `:rf.error/no-frame-prop` and
    `:rf.error/hicasso-frame-outside-boundary` have no frame, because the
    offence is that there is none to have.

  So the situational half rides [[fail!]]'s `extra` under the spelling
  its class gives it — `:child`, `:head`, `:query-v`, `:option`,
  `:declared`, `:instance-key` — and is enumerated PER ID in the Hicasso
  section of `spec/009-Instrumentation.md`, sixth column, with `(none)`
  where a class genuinely carries nothing beyond the six here. That
  column is the situational half's stable representation; the complaint
  catalogue (rf2-hic-021) owns its prose and its per-id round trips.

  [[missing-fields]] is the rule [[fail!]] enforces, and the shape test
  calls that fn rather than restating it."
  [:rf.error/id :source :view :where :reason :recovery])

(defn missing-fields
  "Which of [[required]] the refusal ex-data `data` fails to supply, in
  [[required]] order. Empty when the refusal is complete.

  It reads the EMITTED MAP rather than [[fail!]]'s arguments, and that is
  what makes completeness a property of the thing a catch site receives:
  whatever the constructor merges — today's `extra`, or a field the shape
  grows later — is inside the check rather than beside it. Public because
  the shape test asserts on it directly, which keeps the test and the
  guard reading one rule rather than two copies of it."
  [data]
  (let [{id :rf.error/id :keys [where reason recovery]} data]
    (cond-> []
      (not (qualified-keyword? id))                     (conj :rf.error/id)
      (nil? where)                                      (conj :where)
      (or (not (string? reason)) (str/blank? reason))   (conj :reason)
      (nil? recovery)                                   (conj :recovery))))

(defn- check-complete!
  "Refuse to mint a refusal that does not carry [[required]].

  This is the meta-refusal, and it is deliberately the loudest thing in
  the file: a diagnostic that reaches a user without a recovery has spent
  the user's attention and given nothing back, which is worse than the
  original defect. Dev builds only — in production the incomplete refusal
  is thrown as-is rather than replaced, because a guard that swaps one
  exception for another in the field would hide the very failure the user
  is trying to report.

  `data` is the ex-data [[fail!]] is about to throw, not the arguments it
  was handed — see [[missing-fields]]."
  [data]
  (let [missing (missing-fields data)]
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
                       :refusal     (:rf.error/id data)
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
  which is `re-frame.error`'s contract reproduced rather than delegated.
  Whether to route the complaint TEXT through core's builder instead was
  the complaint catalogue's to rule on, and it ruled that the text stays
  here (rf2-hic-021); the reasons are recorded in
  `docs/design/hicasso/product/complaints.md`, §Rulings this catalogue
  owns.

  **`extra` merges UNDER the shape, never over it.** It carries the
  refusal CLASS's own slots and only those; [[required]] and [[ambient]]
  are the constructor's, and a spelling of one in `extra` loses — the
  required four by being overwritten, the ambient pair by being REMOVED
  before the merge. Two mechanisms rather than one because overwriting
  only reaches a field the constructor has a value for, and the ambient
  pair is absent exactly where a forgery would matter most: outside
  every declaration extent, and in every production build. A forged
  `:view` there had nothing to lose to.

  The merge used to run the other way, on the reading that a call site might
  know better than the ambient ledger — but no call site in the package
  ever did, and the cost of the option was that the four fields the guard
  had just insisted on could be erased one line later. An `extra` of
  `{:rf.error/id :other :where nil :reason \"replaced\" :recovery nil}`
  emitted exactly that map. The guard now reads the merged result, so its
  claim is about the refusal a catch site receives."
  [id where reason recovery extra]
  (let [data (merge (apply dissoc extra ambient)
                    (with-origin {:rf.error/id id :where where
                                  :reason reason :recovery recovery}))]
    (when interop/debug-enabled?
      (check-complete! data))
    ;; rf2:builder-bypass-ok - `id` is a PARAMETER here, so the runtime
    ;; message carries the `[:rf.error/...]` token the source cannot show
    ;; (the gate's own "computed discriminator" case). Re-routing the
    ;; complaint text through `re-frame.error` was ruled out by
    ;; rf2-hic-021 — see complaints.md, §Rulings this catalogue owns.
    (throw (ex-info (str reason " [" id "]") data))))
