(ns re-frame.live-frame
  "EP-0023 §Frame / §Public API — the FRAME IMAGE-LOADING slice (rf2-32siq3.8):
  teach `rf/make-frame` to accept `:images` (always a vector), resolve those
  image values into ONE sealed image generation (via
  `re-frame.image-assembly/assemble`), and return the live frame object. When an
  `:id` is supplied, register that object in the PROCESS-LOCAL LIVE-FRAME
  REGISTRY; a duplicate live id fails loud.

  > A frame id names a running context in a registry. A frame object is a direct
  > local reference to one. (EP-0023 §Frame)

  ## What this slice OWNS

    * `make-frame` — the EP-0023 public constructor. Resolves `:images` into one
      sealed generation and returns the LIVE FRAME OBJECT in ALL cases
      (EP-0023 §Public API: \"`rf/make-frame` returns the live frame object\").
    * the PROCESS-LOCAL LIVE-FRAME REGISTRY — `{frame-id → frame-object}`. An
      `:id`-bearing frame is registered here and must be UNIQUE among live
      registered frames (EP-0023 §Id Spaces). A duplicate live id is
      `:rf.error/live-frame-id-conflict`, fail-loud.
    * the FRAME OBJECT shape — a live execution context that holds a reference
      to its resolved image generation, plus the initial-db / capability map /
      adapter binding the later live-resolution (.9) and reload (.10) slices
      build on (EP-0023 §Frame — \"a reference to the resolved image generation
      it is running\").

  ## Image hot reload (rf2-32siq3.10 — LANDED here)

  This ns also owns the EP-0023 HOT-RELOAD surface: `rf/reload-images!` plus
  the source-store reprojection path (EP-0023 §Hot Reload / §Tests, Stories,
  SSR, And Hot Reload).

    * `reload-images!` — the public frame-targeted, composition-REPLACING
      reload. It takes the SAME `:images` vector shape as `make-frame`, targets
      ONE frame (by id or direct frame object), re-assembles the new `:images`
      into a fresh sealed generation, and SWAPS that generation onto the frame
      WHILE PRESERVING FRAME MEMORY — app-db, runtime-db, caches, lifecycle, and
      every other frame-owned slot continue unchanged; ONLY `:rf.frame/generation`
      moves (EP-0023 §Hot Reload — \"Hot reload must not be implemented by
      tearing down and recreating the frame\"). It returns a reload REPORT naming
      the `:added` / `:changed` / `:removed` / `:retained` `[kind id]` sets so
      the runtime can invalidate only what changed. For an `:id`-bearing frame
      the registry slot is updated IN PLACE (the same id keeps naming the same
      live context); a direct (no-id) frame object's reloaded copy is returned
      for the caller to hold.

    * `reproject-live-frames!` — the source-store-change path (EP-0023 §Default
      Image Semantics / §Hot Reload: \"a `reg-*` re-eval in a namespace selected
      by an explicit `:include-ns` image reprojects and swaps affected
      explicit-image frames\"). It re-resolves EVERY registered live frame from
      its OWN `:rf.gen/images` composition against the CURRENT source store and
      swaps the freshly-assembled generation onto any frame whose resolution
      changed — EXPLICIT-image frames included, not only default-image frames. A
      frame whose composition re-resolves byte-for-byte is left untouched (no
      spurious swap). It is WIRED to fire AUTOMATICALLY on any `reg-*` via a
      `registrar/add-registration-hook!` seam (rf2-h4q6cy), coalesced across a
      hot-reload `reg-*` burst onto a single `interop/next-tick` flush — see the
      §Auto-reprojection section below. So an ordinary `reg-*` re-eval swaps the
      affected live frame's generation WITHOUT a manual `reload-images!` /
      `reproject-live-frames!` call.

  Reload is frame-targeted: reloading `:counter/left` swaps the generation THAT
  frame runs; it does not move `:counter/right` merely because the two frames
  previously shared a sealed generation object (EP-0023 §Image — \"Reload is
  frame-targeted\"). The generation is an immutable value, so the swap is a
  single `assoc` that preserves frame identity for every other slot; a future
  state-container slice that adds an app-db ATOM slot is preserved by identity
  through the same `assoc` (the atom — hence the memory — is carried through
  untouched).

  ## Id spaces — the heart of the same-id story (EP-0023 §Id Spaces)

  Two PUBLIC id spaces with DIFFERENT scopes meet here:

    Registration ids (`:counter/inc`) — scoped to the resolved image generation,
      REUSABLE across images. Within one sealed generation `(kind, id)` is
      unambiguous (that is `image-assembly`'s job; not re-checked here).
    Frame ids (`:counter/main`)        — scoped to the PROCESS-LOCAL live-frame
      registry, UNIQUE among live registered frames. Two images may both contain
      `:counter/inc`; two live frames may NOT both be `:counter/main`.

  Direct frame objects (no `:id`) BYPASS the public frame-id space entirely —
  the caller keeps the returned object and passes it directly (tests/harnesses).
  The absence of `:id` is NOT a default-id path (EP-0023 §Public API).

  ## Relationship to the EP-0013 `re-frame.frame` registry

  This is the EP-0023 public live-frame registry. It is DELIBERATELY SEPARATE
  from `re-frame.frame`'s EP-0013 `(realm, frame)`-addressed `frames` registry
  (the realm-routed substrate). EP-0023 partially supersedes EP-0013's public
  app/realm surface while RETAINING the realm machinery as an internal
  substrate; this slice introduces the image-loaded public frame object without
  disturbing the existing `reg-frame` / `re-frame.frame/make-frame` callers. The
  reload slice (.10) wires image replacement on the frame object this slice
  returns.

  ## Frame-derived live resolution (rf2-32siq3.9 — LANDED here)

  This ns also owns the EP-0023 resolution-routing SEAM
  ([[call-with-frame-resolution]]): bind `re-frame.registrar/*generation*` to a
  frame object's sealed generation around a thunk, and every `(kind, id)` lookup
  inside it — dispatch / subscribe / fx / cofx / view / resource all funnel
  through `registrar/lookup` — resolves through the TARGET frame's OWN image
  generation, not the global/default registrar (EP-0023 §Frame-derived live
  registration resolution). This is the same observable contract the a15n62
  slice shipped for the EP-0013 realm substrate (`registrar/*registrar*` bound
  to a realm's atom), restated for an EP-0023 frame OBJECT that carries its
  generation directly. Two frames running DIFFERENT images resolve the same
  `[kind id]` to their OWN image's descriptor; absence (a nil target / no
  generation) falls through to the registrar atom path, byte-identical for every
  existing caller (absence-is-default).

  ## Production elision

  The constructor runs on the runtime / SSR frame-creation path (not a per-event
  hot path, not a DEBUG-gated branch). It is pure map assembly over the result
  of `image-assembly/assemble` plus one registry `swap!`. An app that never
  calls `make-frame` with `:images` never reaches these fns (Closure DCE removes
  them). The resolution seam adds a single dynamic binding around the cascade
  (the no-generation default path pays one `frame-object?` predicate then runs
  with zero binding cost — see [[call-with-frame-resolution]]). Reload runs on
  the development hot-reload / explicit `reload-images!` path (not a per-event
  hot path, not a DEBUG-gated branch): pure re-assembly via
  `image-assembly/assemble` plus one registry `swap!` per swapped frame. An app
  that never hot-reloads never reaches them (Closure DCE removes them). The
  requires are
  `re-frame.image-assembly` (the merged assembly entry point),
  `re-frame.registrar` (the `*generation*` resolution seam + the closed kind
  set + the `add-registration-hook!` reprojection seam), `re-frame.interop`
  (the `next-tick` coalescing primitive + the `debug-enabled?` production gate),
  and `re-frame.error` (fail-loud diagnostics) — all already in the core spine."
  (:require [re-frame.image-assembly :as asm]
            [re-frame.registrar      :as registrar]
            [re-frame.frame          :as frame]
            [re-frame.interop        :as interop]
            [re-frame.late-bind      :as late-bind]
            [re-frame.error          :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ===========================================================================
;; The process-local live-frame registry (EP-0023 §Frame / §Id Spaces)
;; ===========================================================================
;;
;; `{frame-id → frame-object}`. A `defonce` atom so a hot reload of THIS ns
;; does not drop the live frames already registered. The PUBLIC uniqueness
;; contract (EP-0023 §Id Spaces — \"a frame id is unique in the process-local
;; live-frame registry\") is enforced on registration: a duplicate live id is a
;; fail-loud `:rf.error/live-frame-id-conflict`, NEVER a silent clobber (a
;; silent overwrite would orphan a live frame's generation + state under a name
;; the caller still believes points at the original — the realm-id-conflict
;; failure mode, here at the frame-id boundary).

(defonce ^{:doc "Process-local live-frame registry: `{runnable-id → frame-object}`.
  EVERY runnable frame object `make-frame` produces is registered here keyed by
  its `:rf.frame/runnable-id` ADDRESS (the same id its backing runnable record is
  keyed by in `re-frame.frame/frames`) — both `:id`-bearing objects (runnable-id
  = the public `:rf.frame/id`) AND no-id direct objects (runnable-id = a private
  `:rf.frame/<gensym>`). Keying by the runnable-id is what lets a frame-targeted
  dispatch / subscribe — including a CHILD dispatch that carries only the
  runnable-id keyword on its envelope — re-derive the frame's resolved image
  generation by id, so generation routing stays coherent across the whole
  cascade for a no-id object too (EP-0023 §Frame-derived live registration
  resolution).

  The PUBLIC frame-id uniqueness contract (EP-0023 §Id Spaces) is enforced
  SEPARATELY by `register-live-frame!` on the public `:rf.frame/id` BEFORE a slot
  is taken, so two live frames may not both claim `:counter/main`; no-id objects
  carry no public id and so bypass that contract (their gensym runnable-ids
  never collide). `live-frame-ids` enumerates only the PUBLIC ids. INTERNAL — the
  registry the query/lookup/reload helpers read."}
  live-frames
  (atom {}))

(defn live-frame
  "Return the live frame OBJECT registered under a frame target — its public
  `frame-id` KEYWORD or its private runnable-id (the same `runnable-id → object`
  key in either case), or nil when no live frame carries that id. The
  process-local id lookup (EP-0023 §Frame — \"a frame id names a running context
  in a registry\"). Pure read of the registry snapshot. The generation-resolution
  seam reads through this so an `:id`-targeted dispatch and a no-id object's
  child dispatch (carrying the runnable-id) BOTH resolve the frame's generation."
  [frame-id]
  (get @live-frames frame-id))

(defn live-frame-ids
  "The set of PUBLIC frame ids currently live in the process-local registry —
  the public frame-id space tools and the reload slice enumerate. Only objects
  created with an explicit `:id` contribute; no-id direct objects (keyed under a
  private runnable-id) are deliberately excluded, so reprojection /
  enumeration never touch a harness-local frame the spec says its owner reloads
  explicitly (EP-0023 §Frame — direct objects bypass the registry)."
  []
  (into #{} (keep #(:rf.frame/id %)) (vals @live-frames)))

(defn forget-live-frame!
  "Remove the frame keyed by `frame-id` (a public frame id or a runnable-id) from
  the live-frame registry, returning the registry's new value. The teardown
  counterpart to `make-frame`'s registration; `re-frame.frame/destroy-frame!`
  fires this through the `:live-frame/forget!` late-bind hook from the frame's
  destroy boundary. INTERNAL — registry mutation only; it does not run any frame
  teardown of its own (the record teardown is `destroy-frame!`'s job)."
  [frame-id]
  (swap! live-frames dissoc frame-id))

(defn clear-live-frames!
  "Reset the live-frame registry. Test fixtures use this between cases so a
  registered `:id` from one case does not collide with the next.

  EP-0023 (rf2-32siq3.32): this resets ONLY the live-frame registry index, not
  the backing runnable RECORDS in `re-frame.frame/frames` (the runtime fixture's
  registrar/`frames` reset owns those). The two are reset together by the test
  fixtures."
  []
  (reset! live-frames {})
  nil)

;; The destroy boundary (`re-frame.frame/destroy-frame!`) forgets a destroyed
;; frame's live-frame entry through this hook — `re-frame.frame` cannot require
;; THIS ns (it would cycle), so the forget is published as a late-bind hook
;; keyed by the destroyed frame's id (the public id == the runnable-id for an
;; id-bearing object; a no-id object's gensym runnable-id is keyed here too, so
;; destroying a direct object by handle also forgets it). No-op for an id never
;; registered (every EP-0013 realm-only frame).
(late-bind/set-fn! :live-frame/forget! forget-live-frame!)

;; ===========================================================================
;; The frame OBJECT (EP-0023 §Frame)
;; ===========================================================================
;;
;; A live frame OBJECT is the execution context EP-0023 §Frame describes. This
;; slice builds the part that frame loading owns: the resolved image generation
;; reference plus the creation-time inputs (initial-db, capability map, adapter
;; binding) that the live-resolution (.9) and reload (.10) slices read. The
;; reactive state container, queue/drain, sub-cache, traces, and lifecycle are
;; the existing frame substrate's concern and are wired by the later slices —
;; this slice deliberately does not duplicate them, so the object carries the
;; generation reference and the inputs and nothing it does not yet own.
;;
;; The object is identified by `:rf.frame/object true` so a target-resolution
;; site can tell a direct frame OBJECT from a frame-id KEYWORD without guessing
;; (EP-0023 §Frame — \"the public target is a frame: usually a frame id …
;; sometimes a direct frame object\"). The generation reference lives under
;; `:rf.frame/generation`.

(def ^:const object-marker
  "Reserved frame-object marker key. A `true` value at this key on a map means
  \"this is a live frame OBJECT\", distinguishing a direct frame object from a
  frame-id keyword at a target-resolution site (EP-0023 §Frame)."
  :rf.frame/object)

(def ^:const generation-key
  "Reserved key naming the frame object's resolved image GENERATION reference —
  the sealed `image-assembly` generation the frame resolves registration lookups
  against (EP-0023 §Frame — \"a reference to the resolved image generation it is
  running\")."
  :rf.frame/generation)

(defn frame-object?
  "True when `x` is a live frame OBJECT produced by `make-frame` (carries the
  `:rf.frame/object` marker), as opposed to a frame-id keyword. The discriminator
  a target-resolution site uses (EP-0023 §Frame). Pure."
  [x]
  (boolean (and (map? x) (get x object-marker))))

(defn frame-generation
  "The resolved image GENERATION a frame OBJECT is running — the sealed
  `image-assembly` generation it resolves `(kind, id)` lookups through (EP-0023
  §Frame). Pure read of the object's `:rf.frame/generation` slot. The read API
  the live-resolution (.9) slice resolves registrations against."
  [frame-object]
  (get frame-object generation-key))

;; ===========================================================================
;; Frame-derived live registration resolution (EP-0023 §Frame-derived live
;; registration resolution, rf2-32siq3.9)
;; ===========================================================================
;;
;; EP-0023 restates the a15n62 invariant in image/frame terms:
;;
;;     target frame -> resolved image generation -> registration resolution
;;
;; The headline use cases (same-id / different-image frames on one page, Xray
;; beside the target, progressive docs examples) all depend on ONE prerequisite:
;; live dispatch / subscribe / fx / cofx / view / resource lookups must resolve
;; through the TARGET frame's resolved image generation, not the global/default
;; registrar. The a15n62 slice already shipped this for the EP-0013 realm
;; substrate by binding `registrar/*registrar*` to a frame's REALM registrar
;; atom; this slice ships the same observable contract for an EP-0023 frame
;; OBJECT, which carries its generation directly under `:rf.frame/generation`.
;;
;; The mechanism is a SINGLE binding seam, mirroring
;; `re-frame.frame/call-with-frame-realm-registrar`: bind `registrar/*generation*`
;; to the frame object's sealed generation around a thunk, and EVERY `(kind, id)`
;; lookup inside it (`registrar/lookup` is the universal chokepoint dispatch /
;; subscribe / fx / cofx / view / resource all funnel through) resolves through
;; the frame's OWN image's resolver. Two frames running DIFFERENT images thus
;; resolve the same `[:event :boot/init]` to their OWN image's descriptor.
;;
;; ALL-OR-NOTHING (the a15n62 coherence rule, restated): the binding covers the
;; WHOLE cascade — event handler + every cofx injection + the whole fx walk +
;; view/resource lookup — so a frame-local handler's effects resolve in the same
;; image as the handler. Routing only some lookups would be an incoherent
;; half-dispatch.
;;
;; ABSENCE-IS-DEFAULT (the load-bearing fall-through): a nil frame object, or a
;; frame object carrying no generation, binds NOTHING — `registrar/*generation*`
;; stays nil and resolution falls through to the registrar atom path (the
;; single-realm default AND the a15n62 realm-routed path alike), byte-identical
;; for every existing caller. This slice is purely ADDITIVE: it introduces a new
;; resolution dimension for EP-0023 frame objects without disturbing any path
;; that does not target one.
;;
;; DERIVED from the carried frame object, never an ambient binding (EP-0002
;; carried-invariant): the generation is read off the frame the caller targets,
;; not inferred from process state. A plain fn (not a macro) so CLJS sibling-ns
;; callers use it with no `:require-macros` plumbing.

(defn frame-resolution-generation
  "Return the resolved image generation a `frame-target` resolves registrations
  through, or nil when the target carries none. `frame-target` is an EP-0023
  frame OBJECT (the only target this slice resolves a generation from); a nil
  target, a non-object value, or an object with no `:rf.frame/generation` slot
  yields nil — the absence-is-default signal that no generation routing is
  needed and resolution falls through to the registrar atom path. Pure."
  [frame-target]
  (when (frame-object? frame-target)
    (frame-generation frame-target)))

(defn call-with-frame-resolution
  "Invoke `thunk` with `registrar/*generation*` bound to `frame-target`'s
  resolved image generation WHEN the target carries one; otherwise invoke
  `thunk` with NO binding (the absence-is-default registrar-atom path). Returns
  the thunk's value. This is the EP-0023 frame-derived resolution seam
  (rf2-32siq3.9): every event / subscription / fx / cofx / view / resource
  `(kind, id)` lookup inside `thunk` resolves through the targeted frame's OWN
  image generation coherently (ALL-OR-NOTHING — `registrar/lookup` is the single
  chokepoint they all funnel through).

  `frame-target` is an EP-0023 frame OBJECT (`make-frame`'s return value). A nil
  target, a non-object value, or an object with no generation binds nothing and
  runs `thunk` on the default registrar-atom path — byte-identical to every
  existing caller. DERIVED from the carried frame object, never an ambient
  binding (EP-0002 carried-invariant). The default (no-generation) path pays
  one `frame-object?` predicate + one keyword read and then runs `thunk` with
  ZERO dynamic-binding cost."
  [frame-target thunk]
  (if-let [gen (frame-resolution-generation frame-target)]
    (binding [registrar/*generation* gen]
      (thunk))
    (thunk)))

;; ===========================================================================
;; :images validation (EP-0023 §Image Composition — \"`:images`, always a
;; vector\" / §Public API)
;; ===========================================================================
;;
;; `:images` is the ONLY spelling and it is ALWAYS a vector — even a single
;; image is a one-element vector (EP-0023 §Image Composition). A non-vector
;; `:images` is rejected loud rather than coerced, so the one-spelling contract
;; the EP fixes does not erode into \"a map or a vector or a bare image\".

(defn- validate-images!
  "Validate the `:images` opt is a VECTOR of image values (EP-0023 §Image
  Composition / §Public API — `:images` is always a vector). A non-vector
  `:images` (a bare image map, a seq, nil-when-required) throws
  `:rf.error/make-frame-bad-images`, fail-loud. Returns the vector unchanged."
  [images]
  (when-not (vector? images)
    (error/throw-error!
      :rf.error/make-frame-bad-images
      'rf/make-frame
      (str "rf/make-frame: :images must be a VECTOR of image values — got "
           (pr-str images) ". `:images` is the only spelling and it is always a "
           "vector; even a single image is supplied as a one-element vector, "
           "e.g. :images [my-image].")
      {:recovery :wrap-the-images-in-a-vector
       :extra    {:images images}}))
  images)

;; ===========================================================================
;; Live-frame-id uniqueness (EP-0023 §Id Spaces — \"a frame id is unique in the
;; process-local live-frame registry\")
;; ===========================================================================

(defn- register-live-frame!
  "Register `frame-object` under `frame-id` in the process-local live-frame
  registry, ATOMICALLY checking uniqueness. FAIL-LOUD: a `frame-id` already live
  throws `:rf.error/live-frame-id-conflict` — a frame id is unique among live
  registered frames (EP-0023 §Id Spaces); a silent clobber would orphan the
  existing frame's generation + state under a name the caller still trusts.

  The check + insert is one `swap!` so two registrations of the same id cannot
  both observe an empty slot and both win. Returns `frame-object`."
  [frame-id frame-object]
  (let [snapshot (swap! live-frames
                        (fn [m]
                          (if (contains? m frame-id)
                            ;; Leave the registry UNCHANGED on a conflict — the
                            ;; existing live frame keeps its slot; the throw
                            ;; below reports the conflict from the post-swap read.
                            m
                            (assoc m frame-id frame-object))))]
    ;; Identity check: the slot holds OUR object iff we won the insert. A
    ;; conflict left the existing (different) object in place.
    (when-not (identical? frame-object (get snapshot frame-id))
      (error/throw-error!
        :rf.error/live-frame-id-conflict
        'rf/make-frame
        (str "rf/make-frame: frame id " (pr-str frame-id) " is already live in "
             "the process-local live-frame registry. A frame id is unique among "
             "live registered frames (registration ids like :counter/inc may be "
             "reused across images; frame ids like :counter/main may not name two "
             "live frames). Destroy the existing frame first, pick a different "
             "frame id, or use a direct frame object (omit :id) for a local "
             "test/harness frame.")
        {:recovery :use-a-unique-frame-id-or-destroy-the-existing-frame
         :extra    {:frame-id frame-id}}))
    frame-object))

;; ===========================================================================
;; Generation resolution (shared by make-frame and reload-images!)
;; ===========================================================================
;;
;; `make-frame` (.8/.6) and `reload-images!` (.10) BOTH turn an `:images` vector
;; + a frame `:capabilities` map into ONE sealed, capability-checked generation.
;; Factor that out so reload resolves a new generation with byte-identical
;; semantics to creation: same `:images`-is-a-vector validation, same
;; `image-assembly/assemble` path (live source store OR an explicit descriptor
;; pool), same unconditional frame-boundary capability check.

(defn- resolve-generation!
  "Validate `images` (must be a vector — EP-0023 §Image Composition), assemble it
  into ONE sealed generation (against the live source store when `descriptors` is
  nil, else against the explicit pool — matching `assemble`'s two arities), and
  run the FRAME-BOUNDARY capability check against `capabilities` (unconditional:
  an absent map provides nothing, EP-0013 fail-loud parity). Returns the sealed
  generation. The shared resolution `make-frame` and `reload-images!` both use,
  so a reload resolves with byte-identical semantics to creation. Fail-loud on a
  non-vector `:images`, an assembly error, or a missing capability.

  An ABSENT or EMPTY `:images` is the DEFAULT-IMAGE path (EP-0023 §Default Image
  Semantics): nil `images` normalizes to `[]`, and `assemble` routes an empty
  `:images` vector through `assemble-default` — the implicit selector over the
  WHOLE source store (+ the framework standards), NOT the framework standards
  alone. So a frame created with no explicit images runs every `reg-*`-authored
  descriptor in the (live or supplied) source store, and a cross-namespace
  same-`[kind id]` collision in that default projection FAILS LOUD here at
  make-frame time (`:rf.error/image-duplicate-id`) exactly as an explicit
  image's collision does — load order never silently decides the survivor."
  [images capabilities descriptors]
  (let [images     (if (some? images) (validate-images! images) [])
        generation (if (nil? descriptors)
                     (asm/assemble images)
                     (asm/assemble images descriptors))]
    ;; Capability check at the FRAME boundary (EP-0023 §Public API): a required
    ;; capability absent from the frame's `:capabilities` fails BEFORE the
    ;; generation is runnable. UNCONDITIONAL so a frame that supplies NO
    ;; `:capabilities` map still fails any non-empty `:rf.image/requires` — an
    ;; absent map provides nothing, exactly as `{}` does (EP-0013 fail-loud
    ;; install-time parity). A no-op when the union `:rf.gen/requires` is empty.
    (asm/check-capabilities! (:rf.gen/requires generation) capabilities)
    generation))

;; ===========================================================================
;; make-frame — the public constructor (EP-0023 §Public API)
;; ===========================================================================

(defn make-frame
  "Create a live frame from one or more IMAGES and return the live frame OBJECT
  (EP-0023 §Public API — \"`rf/make-frame` returns the live frame object in all
  cases\"). PUBLIC (`rf/make-frame`, EP-0023 surface).

  `opts` is a map:

    :images        a VECTOR of image values (the ONLY spelling; always a vector,
                   even for a single image — EP-0023 §Image Composition). The
                   image values are resolved into ONE sealed image generation via
                   `re-frame.image-assembly/assemble`; a non-vector `:images`
                   fails loud (`:rf.error/make-frame-bad-images`). Optional — a
                   frame created with NO `:images` (absent or `[]`) runs the
                   DEFAULT IMAGE (EP-0023 §Default Image Semantics): the implicit
                   selector over the WHOLE source store (+ the framework
                   standards), so it resolves every `reg-*`-authored registration,
                   NOT the framework standards alone. The default projection
                   FAILS LOUD on a cross-namespace same-`[kind id]` collision
                   (`:rf.error/image-duplicate-id`) — load order never decides
                   the survivor (a product wanting same ids with different
                   meanings uses explicit images with disjoint selectors).
    :id            the frame id (optional). When supplied, the returned object is
                   registered in the PROCESS-LOCAL LIVE-FRAME REGISTRY under this
                   id; a duplicate live id fails loud
                   (`:rf.error/live-frame-id-conflict`). When ABSENT, the frame is
                   LOCAL-ONLY: the caller keeps the returned object and passes it
                   directly to dispatch/subscribe/test helpers. The absence of
                   `:id` is NOT a default-id path (EP-0023 §Public API).
    :initial-db    the frame's initial app-db value (optional). SEEDED into the
                   runnable frame's app-db partition (EP-0023 §Frame — the live
                   frame object owns app-db), so an immediate
                   `(rf/subscribe frame [...])` / `(rf/app-db-value frame)` reads
                   it; absent ⇒ the frame starts with app-db `{}` (the EP-0013
                   fresh-frame default). Frame STATE is a frame concern, image is
                   a behaviour concern (EP-0023 §Image Patching And Overrides).
    :capabilities  the host capability map the image's `:rf.image/requires` is
                   checked against (optional). The check runs UNCONDITIONALLY at
                   the frame boundary: any required capability the map does not
                   provide fails loud (`:rf.error/image-missing-capability`)
                   BEFORE the generation is returned — the EP-0023 §Public API
                   frame-boundary check. An ABSENT `:capabilities` map provides
                   nothing, so an image with a non-empty `:rf.image/requires`
                   fails just as it would against `{}` (EP-0013 fail-loud
                   parity); supplying the map is therefore mandatory for any
                   image that declares requirements. A no-op when the image
                   requires nothing.
    :adapter       the active-substrate adapter binding/configuration (optional).
                   Carried on the object; the adapter binding is part of the live
                   frame object, never the frame-state value (EP-0023 §Host
                   Boundary).

  Returns the live frame OBJECT — a single RUNNABLE map carrying
  `:rf.frame/object true`, the resolved `:rf.frame/generation`, the
  `:rf.frame/runnable-id` ADDRESS of its backing runnable record, the public
  frame `:rf.frame/id` (when supplied), and the creation inputs. The object is
  fully runnable: `rf/dispatch` / `rf/subscribe` / `rf/destroy-frame!` /
  `rf/app-db-value` accept it (or its id), and the cascade resolves through its
  `:rf.frame/generation` (EP-0023 §Frame — the live frame object owns app-db,
  runtime-db, queue, sub-cache, lifecycle, and a reference to the resolved image
  generation it is running). `reload-images!` swaps the generation while
  preserving the backing record's memory.

  EP-0023 collapse slice 1 (rf2-32siq3.32): the object's runnable interior —
  app-db / runtime-db container, projection reactions, router queue, drain-lock,
  sub-cache, lifecycle — is an EP-0013 runnable RECORD created here via
  `re-frame.frame/reg-frame` (the canonical create-and-register, idempotent on a
  re-make of a live id) and keyed in `re-frame.frame/frames` by the
  runnable-id. So one frame type carries BOTH the resolved image generation
  (here, on the object) AND the runnable state (the record reached by the
  runnable-id) — no separate `reg-frame` pairing is needed to run an event
  stream against an image.

  Two arities:
    (make-frame opts)            — resolve `:images` against the LIVE source store.
    (make-frame opts descriptors)— resolve against an explicit descriptor pool
                                   (tests / harnesses / a pre-snapshotted store),
                                   matching `image-assembly/assemble`'s 2-arity."
  ([opts] (make-frame opts nil))
  ([{:keys [images id initial-db capabilities adapter]} descriptors]
   (let [generation  (resolve-generation! images capabilities descriptors)
         ;; The runnable-id ADDRESS the backing record is keyed by: the public
         ;; `:id` when supplied (so `(rf/dispatch [...] {:frame :counter/main})`
         ;; finds the same record), else a process-unique anonymous id so a no-id
         ;; (direct) object is still runnable while bypassing the PUBLIC frame-id
         ;; space (EP-0023 §Frame).
         runnable-id (if (some? id) id (frame/anon-frame-id))
         frame-object
         (cond-> {object-marker          true
                  generation-key         generation
                  frame/runnable-id-key  runnable-id}
           (some? id)           (assoc :rf.frame/id id)
           (some? initial-db)   (assoc :rf.frame/initial-db initial-db)
           (some? capabilities) (assoc :rf.frame/capabilities capabilities)
           (some? adapter)      (assoc :rf.frame/adapter adapter))]
     ;; Register the object in the process-local live-frame registry under the
     ;; runnable-id FIRST. For an `:id`-bearing frame this is the PUBLIC-id
     ;; uniqueness gate (fail-loud on a duplicate, BEFORE any record is created
     ;; so a conflict leaves no half-created frame); a no-id frame's gensym
     ;; runnable-id never conflicts. The keyword-target generation-resolution
     ;; seam reads through this registry, so a child dispatch carrying only the
     ;; runnable-id keyword still re-derives the frame's generation.
     (register-live-frame! runnable-id frame-object)
     ;; Create the backing runnable RECORD (app-db / runtime-db / queue /
     ;; sub-cache / lifecycle) under the runnable-id via the canonical
     ;; create-and-register. `reg-frame` is idempotent on an existing id
     ;; (surgical update preserving runtime state), so pairing an EP-0023
     ;; `make-frame {:id …}` with a pre-existing `reg-frame` of the same id just
     ;; refreshes config. EP-0023 frames are default-realm (the collapse
     ;; supersedes the public realm dimension), so the record is keyed by the
     ;; bare runnable-id keyword. The config carries no `:on-create`, so no init
     ;; cascade runs here.
     (frame/reg-frame runnable-id {})
     ;; Seed `:initial-db` into the app-db partition (EP-0023 §Frame — the live
     ;; frame object owns app-db). A fresh record starts app-db `{}`; this writes
     ;; ONLY the app-db partition (runtime-db untouched) through the canonical
     ;; partition mutator, so an immediate subscribe/read observes the seed.
     (when (some? initial-db)
       (frame/replace-app-db! runnable-id initial-db))
     frame-object)))

;; ===========================================================================
;; Image hot reload (EP-0023 §Hot Reload, rf2-32siq3.10)
;; ===========================================================================
;;
;; The conceptual event (EP-0023 §Hot Reload):
;;
;;     registration set changed
;;     execution context continues
;;
;; Reload swaps the sealed generation a frame runs WHILE PRESERVING FRAME
;; MEMORY. Because the generation is an immutable VALUE the frame object carries
;; under `:rf.frame/generation`, and the .9 resolution seam derives resolution
;; SOLELY from that slot, swapping the generation is the whole job: a single
;; `assoc` that replaces `:rf.frame/generation` and PRESERVES every other slot
;; (id, initial-db, capabilities, adapter — and, once a later slice adds them,
;; the app-db / runtime-db / cache ATOMS, carried through by identity, hence the
;; memory). Reload must NOT tear down and recreate the frame (EP-0023 §Hot
;; Reload), so it never re-runs `make-frame`; it re-assembles a generation and
;; assocs it on.

;; ---- the reload diff (EP-0023 §Hot Reload — "a concrete diff") ------------

(defn generation-diff
  "Compute the reload DIFF between an `old` and a `new` sealed generation as four
  `[kind id]` sets (EP-0023 §Hot Reload — \"A good reload result should be a
  concrete diff\"):

    :added    keys present in `new` but not `old`
    :removed  keys present in `old` but not `new`
    :changed  keys present in BOTH whose resolved descriptor is not `=`
    :retained keys present in BOTH whose resolved descriptor IS `=`

  Keys are `[kind id]` pairs (the resolver's keys). \"changed\" is by descriptor
  value equality, so an unchanged registration selected by a different image
  composition is `:retained`, not `:changed` — that lets the runtime invalidate
  only what actually changed (changed subs clear caches; retained frame memory
  continues). Pure — a function of the two generations' resolvers."
  [old new]
  (let [old-res (:rf.gen/resolver old)
        new-res (:rf.gen/resolver new)
        old-ks  (set (keys old-res))
        new-ks  (set (keys new-res))
        both    (filter new-ks old-ks)
        changed (into #{} (remove #(= (get old-res %) (get new-res %))) both)
        retained (into #{} (filter #(= (get old-res %) (get new-res %))) both)]
    {:added    (into #{} (remove old-ks) new-ks)
     :removed  (into #{} (remove new-ks) old-ks)
     :changed  changed
     :retained retained}))

;; ---- target resolution (frame id OR direct frame object) -------------------

(defn- resolve-reload-target
  "Resolve a `reload-images!` `target` to the live frame OBJECT to reload.
  `target` is EITHER a frame id (looked up in the process-local live-frame
  registry) OR a direct frame object (used as-is). FAIL-LOUD when an id names no
  live frame (`:rf.error/reload-no-such-frame`) or `target` is neither a frame
  object nor a registered id. Returns the live frame object."
  [target]
  (cond
    (frame-object? target)
    target

    (some? (live-frame target))
    (live-frame target)

    :else
    (error/throw-error!
      :rf.error/reload-no-such-frame
      'rf/reload-images!
      (str "rf/reload-images!: target " (pr-str target) " names no live frame. "
           "Reload targets ONE frame, by a frame id registered in the "
           "process-local live-frame registry or a direct frame object returned "
           "by rf/make-frame. Live frame ids: " (pr-str (vec (live-frame-ids)))
           ".")
      {:recovery :target-a-live-frame-id-or-a-direct-frame-object
       :extra    {:target target
                  :live-frame-ids (vec (live-frame-ids))}})))

;; ---- the in-place generation swap (PRESERVES FRAME MEMORY) -----------------

(defn- swap-generation
  "Return `frame-object` with ONLY `:rf.frame/generation` replaced by
  `new-generation`. Every other slot — id, initial-db, capabilities, adapter,
  and any future state-container atoms — is preserved by `assoc` (atoms by
  identity), so frame memory continues across the swap (EP-0023 §Hot Reload).
  Pure."
  [frame-object new-generation]
  (assoc frame-object generation-key new-generation))

(defn- swap-frame-generation!
  "Swap `new-generation` onto `frame-object`, returning the RELOADED frame
  object. The live-frame registry slot is updated IN PLACE under the frame's
  `:rf.frame/runnable-id` ADDRESS — the SAME key `make-frame` registered the
  object under (the public `:rf.frame/id` for an id-bearing frame, a private
  gensym `:rf.frame/<gensym>` for a no-id direct object). Keying by the
  runnable-id is what keeps generation routing coherent for a NO-ID frame too:
  dispatch / subscribe collapse a frame object to its runnable-id and then
  re-resolve the generation from this registry, so the slot MUST point at the
  reloaded object or a reloaded no-id frame would resolve the stale OLD image
  (and a child dispatch carrying the runnable-id likewise). The update is
  guarded by an `identical?` stale-handle check — applied ONLY when the registry
  currently holds THIS object — so a reload of a stale object handle does not
  clobber a frame re-registered under the id since. Returns the reloaded frame
  object (the caller also holds it directly)."
  [frame-object new-generation]
  (let [reloaded    (swap-generation frame-object new-generation)
        runnable-id (get frame-object frame/runnable-id-key)]
    (when (some? runnable-id)
      ;; Update the registry slot in place, but only if it still holds THIS
      ;; object — guards against clobbering a frame re-registered under the
      ;; runnable-id. Applies to id-bearing AND no-id frames: the no-id frame's
      ;; gensym runnable-id slot is what dispatch/subscribe re-resolve through.
      (swap! live-frames
             (fn [m]
               (if (identical? frame-object (get m runnable-id))
                 (assoc m runnable-id reloaded)
                 m))))
    reloaded))

;; ---- reload-images! — the public reload (EP-0023 §Public API) --------------

(defn reload-images!
  "Hot-reload ONE frame's whole image composition, PRESERVING FRAME MEMORY
  (EP-0023 §Hot Reload / §Public API — `rf/reload-images!`). PUBLIC.

  `target` is EITHER a frame id (looked up in the process-local live-frame
  registry) OR a direct frame object (`make-frame`'s return value). `opts` is a
  map taking the SAME `:images` vector shape as `make-frame`:

    :images  a VECTOR of image values — the frame's NEW, COMPLETE image
             composition (the only spelling; always a vector — EP-0023 §Image
             Composition). Reload is composition-REPLACING: it replaces the whole
             `:images` vector, not one member (EP-0023 deliberately does not
             define a partial-image-member reload for v1). A non-vector `:images`
             fails loud (`:rf.error/make-frame-bad-images`), exactly as in
             `make-frame`.

  Reload re-assembles `:images` into a FRESH sealed generation (capability-
  checked against the frame's OWN `:rf.frame/capabilities`, byte-identical to
  creation), then SWAPS that generation onto the frame — only
  `:rf.frame/generation` moves; app-db, runtime-db, caches, lifecycle, and every
  other frame slot continue unchanged (EP-0023 §Hot Reload — \"Hot reload must
  not be implemented by tearing down and recreating the frame\"). It does NOT
  mutate the old generation and does NOT move sibling frames that previously
  shared it (reload is frame-targeted — EP-0023 §Image).

  Returns the reload REPORT:

    {:rf.frame/frame  <reloaded frame object>
     :rf.reload/diff  {:added #{[kind id] …} :changed #{…}
                       :removed #{…} :retained #{…}}}

  For an `:id`-bearing frame, the live-frame registry slot is updated IN PLACE,
  so the id keeps naming the same live context (now running the new generation);
  the reloaded object is returned under `:rf.frame/frame` for callers holding a
  direct reference. A direct (no-id) frame object's reloaded copy is returned the
  same way for the caller to hold.

  Two arities mirror `make-frame`:
    (reload-images! target opts)             — re-assemble against the LIVE source store.
    (reload-images! target opts descriptors) — against an explicit descriptor pool
                                               (tests / harnesses / a pre-snapshotted
                                               store), matching `assemble`'s 2-arity."
  ([target opts] (reload-images! target opts nil))
  ([target {:keys [images]} descriptors]
   (let [frame-object   (resolve-reload-target target)
         old-generation (frame-generation frame-object)
         capabilities   (:rf.frame/capabilities frame-object)
         new-generation (resolve-generation! images capabilities descriptors)
         reloaded       (swap-frame-generation! frame-object new-generation)]
     {:rf.frame/frame reloaded
      :rf.reload/diff (generation-diff old-generation new-generation)})))

;; ===========================================================================
;; Source-store reprojection (EP-0023 §Default Image Semantics / §Hot Reload,
;; rf2-32siq3.10)
;; ===========================================================================
;;
;; The dirtying rule is NOT default-image-only (EP-0023 §Default Image
;; Semantics): "Any source-store change invalidates resolved generations whose
;; image selectors might include the changed source slot: default images,
;; explicit `:include-ns` images, and composed images containing them." A
;; `reg-*` re-eval in a namespace an EXPLICIT `:include-ns` image selects must
;; reproject and swap THAT frame's generation too — not only default-image
;; frames.
;;
;; Implementation: re-resolve EVERY registered live frame from its OWN image
;; composition (the normalized `:rf.gen/images` carried on its generation)
;; against the CURRENT live source store, and swap the freshly-assembled
;; generation onto any frame whose resolution CHANGED. A frame that re-resolves
;; byte-for-byte (= old generation) is left untouched — no spurious swap, no
;; sibling movement. This is the `reg-*` hot-reload path the EP describes; the
;; explicit `reload-images!` above is the composition-REPLACING counterpart.

(defn reproject-live-frame!
  "Re-resolve ONE registered live `frame-id` from its current generation's OWN
  image composition (`:rf.gen/images`) against the CURRENT live source store,
  and swap the freshly-assembled generation onto the frame when it CHANGED
  (EP-0023 §Default Image Semantics — a source-store change reprojects affected
  EXPLICIT-image frames, not only default-image frames). Returns the reload diff
  `{:added … :changed … :removed … :retained …}` when the frame moved, or nil
  when its composition re-resolved byte-for-byte (no swap). A no-op for a
  frame-id not currently live."
  [frame-id]
  (when-let [frame-object (live-frame frame-id)]
    (let [old-generation (frame-generation frame-object)
          images         (vec (:rf.gen/images old-generation))
          capabilities   (:rf.frame/capabilities frame-object)
          new-generation (resolve-generation! images capabilities nil)]
      (when-not (= old-generation new-generation)
        (swap-frame-generation! frame-object new-generation)
        (generation-diff old-generation new-generation)))))

(defn reproject-live-frames!
  "Reproject EVERY registered live frame against the CURRENT live source store
  (the `reg-*` hot-reload path — EP-0023 §Default Image Semantics / §Hot Reload).
  For each live frame, re-resolve its OWN `:rf.gen/images` composition and swap
  the new generation on when it changed; this reprojects EXPLICIT-image frames
  (whose `:include-ns` selectors match a changed namespace) as well as
  default-image frames, not only the latter. A frame whose composition
  re-resolves byte-for-byte is left untouched.

  Returns `{frame-id reload-diff}` for every frame that MOVED (empty when none
  did). Direct (no-id) frame objects are not in the registry, so they are not
  reprojected here — their owners reload them explicitly via `reload-images!`."
  []
  (reduce (fn [moved frame-id]
            (if-let [diff (reproject-live-frame! frame-id)]
              (assoc moved frame-id diff)
              moved))
          {}
          (live-frame-ids)))

;; ===========================================================================
;; Auto-reprojection on `reg-*` source-store change (EP-0023 §Default Image
;; Semantics / §Hot Reload — the headline guarantee wired, rf2-h4q6cy)
;; ===========================================================================
;;
;; The EP-0023 headline guarantee (EP-0023:1497, §Default Image Semantics):
;;
;;     The `reg-*` path uses the same operation through dependency
;;     invalidation. A changed `reg-*` entry does not mutate any running
;;     generation. It MARKS every dependent generation DIRTY, including
;;     explicit images whose `:include-ns` selector matches the changed
;;     namespace, resolves new sealed generations, computes the diff, and
;;     swaps those generations into affected frames.
;;
;; `reproject-live-frames!` is that "resolve new generations + swap into
;; affected frames" operation. Before rf2-h4q6cy NOTHING called it in
;; production — no hook fired on `reg-*`, so the helper was dead outside tests
;; and a `reg-*` hot reload in an `:include-ns`-selected namespace left the
;; running frame on its STALE generation until some external `reload-images!`
;; or manual `reproject-live-frames!` ran. This slice wires the missing trigger.
;;
;; ## Trigger model — DIRTY-FLAG + `next-tick` COALESCING
;;
;; The dirtying rule (EP-0023:524) is "mark the projection DIRTY, then resolve a
;; new generation" — a TWO-PHASE operation, not reproject-per-reg. That split is
;; exactly the coalescing seam: a hot reload of one namespace re-evaluates the
;; whole namespace, firing a BURST of synchronous `register!` calls (one per
;; `reg-*` form). Reprojecting on EACH would re-resolve + re-diff every live
;; frame N times for an N-registration namespace — O(frames * regs) assembly
;; churn for a single conceptual reload, and intermediate frames would briefly
;; observe a HALF-RELOADED namespace (some new descriptors, some old).
;;
;; So `register!`'s hook does NOT reproject inline. It MARKS the projection dirty
;; (sets `pending-reprojection?`) and schedules ONE `interop/next-tick` flush
;; (only when none is already pending — `mark-dirty-and-schedule!` is the
;; coalescing gate). The whole synchronous `reg-*` burst sets the same flag and
;; schedules at most ONE flush; the flush runs AFTER the burst settles (the
;; macroscopic batch boundary), clears the flag, and reprojects ONCE against the
;; now-complete source store. `next-tick` is the right boundary: every
;; synchronous `reg-*` of a reloaded namespace happens before control returns to
;; the event loop, so the single deferred flush sees the FULL new registration
;; set, never a partial one. (CLJS: `goog.async.nextTick` microtask; JVM: the
;; single-thread executor — async there too, which the dev hot-reload semantic
;; tolerates; tests drive the synchronous `flush-pending-reprojection!`.)
;;
;; ## Production elision
;;
;; Reprojection is a DEV hot-reload concern: in production the source registrar
;; stops changing after boot (EP-0023:530 — "the default path feels sealed for
;; the lifetime of the frame"), so there is nothing to reproject. The whole
;; wiring is therefore gated on `interop/debug-enabled?` — under `:advanced` +
;; `goog.DEBUG=false` the gate constant-folds to `false`, the `defonce` install
;; body DCEs, and the registration path carries ZERO reprojection cost. The
;; hook adds NO new always-on error id: assembly errors flow through the
;; existing `resolve-generation!` diagnostics, and the registrar fires
;; registration hooks ISOLATED (a hook throw is swallowed, never blocking the
;; `reg-*`), so a reprojection-assembly failure during a dev hot reload cannot
;; break the underlying registration.

(defonce ^{:private true
           :doc "Process-local DIRTY flag: true when a `reg-*` source-store change
  has marked the live-frame projection dirty and a coalesced reprojection is
  pending (EP-0023:524 — \"default image projection is dirty\"). Set by the
  registration hook, cleared by the flush. A plain atom (not a ratom) — this is
  reload-bookkeeping, not reactive frame state."}
  pending-reprojection?
  (atom false))

(defonce ^{:private true
           :doc "Process-local RE-ENTRANCY guard: true WHILE a reprojection flush
  is running. `reproject-live-frames!` re-assembles + swaps generations only — it
  must never `reg-*`, so it must never re-arm the hook. But the EP-0023 collapse
  (rf2-32siq3.32) made `make-frame` create a backing runnable RECORD via
  `frame/reg-frame` (a `register!`), so the auto-reprojection hook now fires on
  `:frame` registrations too. Were a flush to ever provoke a `register!`
  (defensively — none is expected), this guard makes the hook a NO-OP during the
  flush so a flush can never schedule its own successor. A plain atom — flush
  bookkeeping, not reactive state."}
  reprojecting?
  (atom false))

(defn flush-pending-reprojection!
  "If a `reg-*` source-store change has marked the projection dirty, clear the
  flag and reproject EVERY registered live frame ONCE against the current source
  store (the coalesced batch boundary — EP-0023 §Default Image Semantics). A
  no-op when nothing is pending. Returns the `{frame-id reload-diff}` map of
  frames that MOVED (empty when none did or when nothing was pending).

  The synchronous counterpart of the `next-tick`-scheduled flush
  `mark-dirty-and-schedule!` arms: tests (and any caller that needs the
  reprojection to have happened by a known point) invoke this to force the
  pending reprojection through deterministically rather than awaiting the
  deferred tick. The dirty-flag clear is read-then-reset so a re-entrant
  `reg-*` during reprojection re-arms a fresh flush rather than being lost.

  The reproject runs under the `reprojecting?` RE-ENTRANCY guard: any
  `register!` the reproject provokes (the EP-0023 collapse made `make-frame`'s
  backing `reg-frame` fire the hook; reproject itself never `reg-*`s, but this
  is defensive) sees the guard set and is a NO-OP, so a flush can never schedule
  its own successor — bounding the work to ONE reprojection per pending mark."
  []
  (if @pending-reprojection?
    (do (reset! pending-reprojection? false)
        (reset! reprojecting? true)
        (try (reproject-live-frames!)
             (finally (reset! reprojecting? false))))
    {}))

(defn- deferred-flush!
  "The fire-and-forget tick body the coalescing scheduler arms on
  `interop/next-tick`. Runs `flush-pending-reprojection!` but SWALLOWS any throw:
  a deferred background dev-hot-reload reprojection has no caller to surface an
  exception to (it runs on a microtask / the JVM executor thread, OUTSIDE any
  `reg-*` call frame), so a throw there would become an unhandled rejection that
  pollutes unrelated work. Crucially the flush has ALREADY cleared the dirty flag
  before reprojecting, so a swallowed failure does not wedge the flag set (a
  subsequent `reg-*` re-arms a fresh tick). The SYNCHRONOUS
  `flush-pending-reprojection!` and the direct `reproject-live-frames!` keep
  surfacing throws — only this deferred path is defensive. Returns nil."
  []
  (try (flush-pending-reprojection!)
       (catch #?(:clj Throwable :cljs :default) _ nil))
  nil)

(defn- mark-dirty-and-schedule!
  "Mark the live-frame projection DIRTY and schedule ONE coalesced reprojection
  flush on `interop/next-tick` (EP-0023:524 — mark dirty, then resolve a new
  generation). The COALESCING GATE: schedule the flush ONLY when no flush is
  already pending, so a synchronous burst of `reg-*` (a hot-reloaded namespace
  re-evaluating all its registrations) sets the flag many times but schedules at
  most ONE deferred flush — reprojecting ONCE at the batch boundary, not per
  `reg-*`. `compare-and-set!` makes the schedule-decision atomic against the
  burst. The scheduled body is the error-swallowing `deferred-flush!` (a
  background tick has no caller to surface a throw to).

  TWO short-circuits keep this off the hot path so an ordinary registration
  burst with no live image frames costs essentially nothing (rf2-h4q6cy fix):

    * NO-LIVE-FRAME skip — reprojection only ever touches PUBLIC-id live frames
      (`reproject-live-frames!` enumerates `live-frame-ids`). With none live the
      flush would be a guaranteed no-op, so there is nothing to mark dirty or
      schedule. This is the dominant case: every `reg-event` / `reg-sub` /
      `reg-fx` — and the `reg-frame` of EVERY runnable frame's backing record
      since the EP-0023 collapse (rf2-32siq3.32) — funnels through `register!`
      and so fires this hook, but the overwhelming majority run while NO explicit
      `:id` frame is live (app boot, every handler-only test). Marking +
      scheduling on each would flood `interop/next-tick` with one no-op flush per
      registration — the bundle has thousands — interleaving expensive deferred
      callbacks with the async test runner's own scheduling and never settling
      (the observed CI node-test / browser hang). Skipping when nothing is
      reprojectable removes the flood; a `reg-*` while a live image frame DOES
      exist (the headline-guarantee case) still marks + schedules.

    * RE-ENTRANCY skip — a `register!` that fires WHILE a flush is running
      (`reprojecting?` set) does not re-arm: a flush can never schedule its own
      successor, so the work is bounded to ONE reprojection per pending mark
      regardless of any registration the reproject path provokes."
  []
  (when (and (not @reprojecting?)
             (seq (live-frame-ids)))
    ;; Only the transition false→true schedules — the burst's subsequent calls
    ;; observe the flag already true and add no second tick.
    (when (compare-and-set! pending-reprojection? false true)
      (interop/next-tick deferred-flush!)))
  nil)

(defn reproject-on-registration-change!
  "Registrar registration-hook (`registrar/add-registration-hook!`) body: any
  `reg-*` (first-time OR re-registration, any kind) is a source-store change, so
  it MARKS the live-frame projection dirty and schedules a coalesced reprojection
  (EP-0023 §Default Image Semantics / §Hot Reload — a `reg-*` re-eval in a
  namespace an explicit `:include-ns` image selects reprojects + swaps the
  affected explicit-image frames, automatically). Reprojection itself is
  per-frame conditional (`reproject-live-frame!` only swaps a frame whose
  composition actually re-resolved differently), so marking dirty on EVERY
  `reg-*` is safe — an unaffected frame is left untouched at flush time. Ignores
  its hook-event arg (the dirty mark is registration-set-wide, not per id).
  Returns nil."
  [_event]
  (mark-dirty-and-schedule!))

;; Install the reprojection hook ONCE per process. `defonce` over the install so
;; a dev `:reload` of THIS ns does not push a duplicate hook into the registrar's
;; `registration-hooks` vector (it dedupes none, and `clear-all!` does not clear
;; it) — the same "install a registrar hook once, survive hot-reload" pattern
;; `re-frame.flows.registry`'s `_hot-reload-hook` and `re-frame.routing`'s
;; `_url-bound-exclusivity-hook` use. Gated on `interop/debug-enabled?` so the
;; whole install (and thus any reprojection cost on the registration path) is
;; constant-folded away under `:advanced` + `goog.DEBUG=false`: production stops
;; `reg-*`-ing after boot, so there is nothing to reproject (EP-0023:530).
(defonce ^:private _auto-reprojection-hook
  (when interop/debug-enabled?
    (registrar/add-registration-hook! reproject-on-registration-change!)
    :installed))
