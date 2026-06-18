(ns re-frame.live-frame
  "EP-0023 image-loading + EP-0024 (rf2-tu2vr7) the unified frame collapse: the
  ONE public `rf/make-frame` constructor over the ONE `re-frame.frame/frames`
  registry. `make-frame` accepts `:images` (always a vector) — resolving those
  image values into ONE sealed image generation (via
  `re-frame.image-assembly/assemble`) — AND record-config opts in one call, and
  returns the frame VALUE (the lifecycle token). The resolved generation lives on
  the frame's record (the `:generation` slot), NOT in a second registry.

  > A frame id names a running context in a registry. A frame value is the
  > lifecycle token a creation API returns. (EP-0023 §Frame / EP-0024)

  ## What this ns OWNS

    * `make-frame` — the ONE public constructor (EP-0024 §One constructor).
      Resolves `:images` into a sealed generation, threads it + `:initial-db`
      through `frame/reg-frame` (installed BEFORE `:on-create` fires), and returns
      the frame VALUE. A duplicate `:id` is IDEMPOTENT REPLACEMENT (config +
      generation refresh, durable state preserved — EP-0024 §Duplicate id policy);
      it does NOT fail loud. Record-config keys are honoured in the same call
      (option-(a), reversing the rf2-32siq3.45 option-(b) record-only-key redirect).
    * the generation-resolution seam (`call-with-frame-resolution`) + the
      image hot-reload surface (`reload-images!`, source-store reprojection) —
      reading / swapping the generation ON the one `frames` record by id.
    * the dissolved second registry's reads as derived reads over `frames`
      (`live-frame` / `live-frame-ids` / `image-view-frames`).

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
            [re-frame.error          :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ===========================================================================
;; The frame VALUE + the ONE registry (EP-0024 §One live frame registry,
;; rf2-tu2vr7)
;; ===========================================================================
;;
;; EP-0024 collapsed the two-registry model: there is ONE registry,
;; `re-frame.frame/frames`, and the resolved image GENERATION lives ON the
;; unified frame value's record (the `:generation` slot), not in a second
;; live-frame registry. So the former `live-frames` index, the fail-loud
;; `register-live-frame!`, and the `:live-frame/forget!` teardown hook all
;; DISSOLVE: "is this frame live and image-loaded?" is now "does its `frames`
;; record carry a `:generation`?", a derived read over the one registry.
;;
;; The frame VALUE is the lifecycle token `make-frame` returns (EP-0024 Term:
;; Frame value). It carries the `:rf.frame/object` marker (the structural
;; discriminator), its `:rf.frame/runnable-id` (= the frame id its record is
;; keyed by), the public `:rf.frame/id`, and the creation inputs — but NOT the
;; generation, which is read from the record by id. The marker / discriminator /
;; id-accessor live in `re-frame.frame` (the registry owner); this ns aliases
;; them so the resolution / reload code reads naturally.

(def object-marker
  "Alias of `re-frame.frame/object-marker` — the reserved frame-value marker key
  (EP-0024). Held in `re-frame.frame` (the registry owner); aliased here so the
  resolution / reload code reads naturally without a back-require cycle."
  frame/object-marker)

(def frame-value?
  "Alias of `re-frame.frame/frame-value?` — true when `x` is a live frame VALUE
  (carries the `:rf.frame/object` marker) rather than a frame-id keyword
  (EP-0024 Term: Frame value)."
  frame/frame-value?)

;; Back-compat internal alias: the resolution seam below was written against
;; `frame-object?`; EP-0024 renames the concept to `frame-value?` (a value, not
;; an object) but keeps the predicate available under both spellings for the
;; internal callers (router / subs frame-resolution-target) until they migrate.
(def ^{:doc "Internal alias of `frame-value?` (EP-0024 renamed frame OBJECT →
  frame VALUE); the dispatch/subscribe resolution-target helpers still spell it
  `frame-object?`. Pure."}
  frame-object?
  frame/frame-value?)

(defn frame-generation
  "The resolved image GENERATION a frame target is running (EP-0024). Accepts
  EITHER a frame VALUE or a frame id: the generation lives on the record in the
  ONE registry, so this normalizes the target to its id (`frame/frame-value->id`)
  and reads the record's `:generation` slot through `frame/frame-generation`.
  nil when the target names no image-loaded frame (an ordinary configured frame,
  or an unknown/destroyed one) — the absence-is-default resolution signal. Pure."
  [frame-target]
  (frame/frame-generation (frame/frame-value->id frame-target)))

(defn live-frame
  "Return the live frame VALUE for a frame id when that frame is currently
  IMAGE-LOADED (its `frames` record carries a resolved generation), or nil
  otherwise (EP-0024, rf2-tu2vr7). The derived read that replaced the dissolved
  second registry's lookup: a frame is \"live + image-loaded\" iff its one-registry
  record carries a `:generation`. A passed-through frame VALUE is returned
  verbatim (so the resolution seam handles a direct value target). Pure."
  [frame-id]
  (cond
    (frame-value? frame-id) frame-id
    (some? (frame/frame-generation frame-id))
    (frame/make-frame-value {:id          frame-id
                             :runnable-id frame-id})
    :else nil))

(defn live-frame-ids
  "The set of PUBLIC frame ids currently image-loaded — a derived read over the
  ONE `frames` registry for records carrying a `:generation` (EP-0024,
  rf2-tu2vr7). Replaces the dissolved second registry's index; the reload /
  reprojection path enumerates these."
  []
  (frame/image-loaded-frame-ids))

(defn clear-live-frames!
  "Reset the image-loaded frames' generation tracking. EP-0024 (rf2-tu2vr7): the
  second live-frame registry dissolved into the ONE `frames` registry, so there
  is no separate index to reset — the test fixtures' `(reset! frame/frames {})`
  already clears every record (and thus every `:generation`). Retained as a
  no-op for the fixtures that still call it; returns nil."
  []
  nil)

(defn image-view-frames
  "Return `{frame-id frame-view}` for every image-loaded frame — the read
  inspection tooling (Xray's image/frame view) consumes (EP-0024, rf2-tu2vr7).
  With the registries collapsed, an image-loaded frame is a `frames` record
  carrying a `:generation`; this projects each into the inert map shape the
  tooling's pure projectors expect — `{:rf.frame/object true :rf.frame/id <id>
  :rf.frame/generation <gen> :rf.frame/capabilities <caps> :rf.frame/adapter
  <adapter>}` — reading the generation + capabilities from the record by id.
  Replaces the dissolved second registry's `@live-frames` snapshot. INTERNAL —
  a tooling read seam; pure read of the one registry."
  []
  (into {}
        (map (fn [id]
               [id (cond-> {object-marker         true
                            :rf.frame/id          id
                            :rf.frame/runnable-id id
                            :rf.frame/generation  (frame/frame-generation id)}
                     (some? (frame/frame-capabilities id))
                     (assoc :rf.frame/capabilities (frame/frame-capabilities id))
                     (some? (frame/frame-adapter id))
                     (assoc :rf.frame/adapter (frame/frame-adapter id)))]))
        (live-frame-ids)))

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
  through, or nil when the target names no image-loaded frame. EP-0024
  (rf2-tu2vr7): the generation lives on the record in the ONE registry, so
  `frame-target` may be a frame VALUE or a frame id — `frame-generation`
  normalizes either to its id and reads the `:generation` slot. A nil target, a
  non-frame value, or a frame whose record carries no generation yields nil —
  the absence-is-default signal that resolution falls through to the registrar
  atom path. Pure."
  [frame-target]
  (frame-generation frame-target))

(defn call-with-frame-resolution
  "Invoke `thunk` with `registrar/*generation*` bound to `frame-target`'s
  resolved image generation WHEN the target names an image-loaded frame;
  otherwise invoke `thunk` with NO binding (the absence-is-default
  registrar-atom path). Returns the thunk's value. This is the frame-derived
  resolution seam: every event / subscription / fx / cofx / view / resource
  `(kind, id)` lookup inside `thunk` resolves through the targeted frame's OWN
  image generation coherently (ALL-OR-NOTHING — `registrar/lookup` is the single
  chokepoint they all funnel through).

  EP-0024 (rf2-tu2vr7): `frame-target` may be a frame VALUE (`make-frame`'s
  return token) or a frame id — the generation is read from the record by id, so
  a value target and its id resolve the SAME generation, and a `reload-images!`
  swap onto the record is observed through either. A nil target, a non-frame
  value, or a frame whose record carries no generation binds nothing and runs
  `thunk` on the default registrar-atom path — byte-identical to every existing
  caller. DERIVED from the carried frame target, never an ambient binding
  (EP-0002 carried-invariant). The default (no-generation) path pays one
  normalize + one record read and then runs `thunk` with ZERO dynamic-binding
  cost."
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
;; Duplicate-id policy (EP-0024 §Duplicate id policy — idempotent replacement)
;; ===========================================================================
;;
;; EP-0024 (rf2-tu2vr7) replaced the old fail-loud-on-every-live-id refusal with
;; hot-reload-friendly IDEMPOTENT REPLACEMENT: re-`make-frame`-ing the same id
;; updates the frame's record-config + resolved generation WITHOUT destroying
;; durable state. That is exactly `frame/reg-frame`'s existing surgical-update
;; contract (app-db / sub-cache / queue preserved, config replaced), so the
;; unified constructor inherits it — no separate fail-loud registry. The
;; irreconcilable-conflict fail-loud path is the registry's own
;; cross-realm-duplicate-id assertion (`re-frame.migration`) plus the
;; `reg-frame` install-time validators (classification / schema), which still
;; throw; a benign same-shape re-mount just refreshes.

;; ===========================================================================
;; Opt partition (EP-0024 §One constructor — image-selection vs record-config)
;; ===========================================================================
;;
;; The unified `make-frame` accepts BOTH opt families in one call (EP-0024
;; adopts the deferred option-(a), reversing the rf2-32siq3.45 option-(b)
;; fail-loud redirect). The image-selection opts the constructor consumes
;; directly (`:images` → generation; `:id` / `:initial-db` / `:capabilities` /
;; `:adapter` → the frame value + seeding); EVERY OTHER opt is record-config
;; passed verbatim to `frame/reg-frame` (`:on-create` / `:fx-overrides` /
;; `:platform` / `:ssr` / `:doc` / `:preset` / `:tags` / classification keys /
;; …). So `(rf/make-frame {:id … :images [...] :fx-overrides {...}})` works in
;; one call — no record-only-key fail-loud redirect.

(def ^:private image-selection-opt-keys
  "The `make-frame` opts the unified constructor consumes directly (EP-0024) —
  resolved into the generation + the frame value, NOT passed to `reg-frame` as
  record config. Everything else in the opts map is record-config."
  #{:images :id :initial-db :capabilities :adapter})

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
;; make-frame — the ONE public constructor (EP-0024 §One constructor)
;; ===========================================================================

(defn make-frame
  "Create a live frame and return the frame VALUE — the ONE public constructor
  (EP-0024 §One constructor, `rf/make-frame`). It accepts BOTH image-selection
  options AND frame record-config options in one call, and returns the frame
  value (the live lifecycle token).

  `opts` is a map. The IMAGE-SELECTION + value opts the constructor consumes:

    :images        a VECTOR of image values (the ONLY spelling; always a vector,
                   even for a single image). Resolved into ONE sealed image
                   generation via `re-frame.image-assembly/assemble`; a
                   non-vector `:images` fails loud
                   (`:rf.error/make-frame-bad-images`). Optional — a frame with
                   NO `:images` (absent or `[]`) carries no generation and runs
                   on the shared registrar (an ordinary configured frame); a
                   frame with `[]` runs the DEFAULT IMAGE (the implicit selector
                   over the WHOLE source store), failing loud on a cross-namespace
                   same-`[kind id]` collision (`:rf.error/image-duplicate-id`).
    :id            the frame id (optional). When supplied, the frame is
                   registered under this id in the ONE `frames` registry;
                   re-`make-frame`-ing the same id is IDEMPOTENT REPLACEMENT —
                   the record-config + generation refresh while durable state
                   (app-db, sub-cache, queue) is preserved (EP-0024 §Duplicate id
                   policy; hot-reload / Story re-evaluation friendly). When
                   ABSENT, the frame is LOCAL-ONLY: the caller keeps the returned
                   value and passes it (or its id) to dispatch/subscribe/test
                   helpers.
    :initial-db    the frame's initial app-db value (optional). SEEDED into the
                   frame's app-db partition so an immediate
                   `(rf/subscribe frame [...])` / `(rf/app-db-value frame)` reads
                   it; absent ⇒ app-db `{}`. Frame STATE is a frame concern.
    :capabilities  the host capability map the image's `:rf.image/requires` is
                   checked against (optional). Checked UNCONDITIONALLY at the
                   frame boundary — a required capability the map does not provide
                   fails loud (`:rf.error/image-missing-capability`). A no-op when
                   the image requires nothing.
    :adapter       the active-substrate adapter binding/configuration (optional).
                   Carried on the frame value, not the frame-state.

  EVERY OTHER key is RECORD-CONFIG passed verbatim to `re-frame.frame/reg-frame`
  — `:on-create`, `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`,
  `:tags`, the EP-0015 classification keys, etc. So
  `(rf/make-frame {:id :todo/left :images [todo-image] :fx-overrides {...}})`
  configures the frame in ONE call. EP-0024 adopts this single-constructor shape
  (the previously-deferred option-(a)) and REVERSES the rf2-32siq3.45 option-(b)
  fail-loud redirect (`:rf.error/make-frame-record-only-key`): the unified frame
  value backed by the ONE registry removes the two-constructor split that
  motivated the redirect, so a record-config key on `make-frame` is honoured, not
  rejected.

  Returns the frame VALUE — the live lifecycle token (its representation is not
  an app-facing data contract; read its id with `frame/frame-value->id`). The
  frame is fully runnable: `rf/dispatch` / `rf/subscribe` / `rf/destroy-frame!` /
  `rf/app-db-value` accept the value OR its id, and the cascade resolves through
  the frame's resolved generation (stored on the record, read by id). The public
  routing address is the frame id; the value is accepted by internal
  normalization for tests/tools. `reload-images!` swaps the generation while
  preserving frame memory.

  The frame's runnable interior — app-db / runtime-db container, projection
  reactions, router queue, drain-lock, sub-cache, lifecycle, AND the resolved
  generation — is ONE `frames`-registry record (EP-0024 §One live frame
  registry): created/updated here via `frame/reg-frame` (idempotent), with the
  generation written onto it via `frame/set-generation!`.

  Two arities:
    (make-frame opts)            — resolve `:images` against the LIVE source store.
    (make-frame opts descriptors)— resolve against an explicit descriptor pool
                                   (tests / harnesses / a pre-snapshotted store),
                                   matching `image-assembly/assemble`'s 2-arity."
  ([opts] (make-frame opts nil))
  ([opts descriptors]
   (let [{:keys [images id initial-db capabilities adapter]} opts
         ;; The frame id the record is keyed by: the public `:id` when supplied
         ;; (so `(rf/dispatch [...] {:frame :counter/main})` finds the same
         ;; record), else a process-unique anonymous id so a no-id (direct) value
         ;; is still runnable while bypassing the PUBLIC frame-id space.
         runnable-id (if (some? id) id (frame/anon-frame-id))
         ;; Resolve the generation FIRST so a bad `:images` / missing capability
         ;; fails loud BEFORE any record is created — a conflict leaves no
         ;; half-created frame. An absent `:images` carries no generation (an
         ;; ordinary configured frame); an explicit / empty `:images` resolves the
         ;; (default or selected) generation.
         generation  (when (some? images)
                       (resolve-generation! images capabilities descriptors))
         ;; Everything outside the image-selection/value keys is record-config
         ;; passed verbatim to `reg-frame` — `:on-create` / `:fx-overrides` /
         ;; `:platform` / `:ssr` / `:doc` / `:preset` / `:tags` / classification.
         ;; EP-0024 option-(a): the unified constructor honours these, no
         ;; fail-loud record-only-key redirect. We ALSO thread three reserved
         ;; construction inputs through the config so `reg-frame` installs them
         ;; on the record BEFORE it fires `:on-create` (CRITICAL — an
         ;; `:on-create` cascade must resolve through the frame's OWN image
         ;; generation and observe the seeded app-db, not the global registrar /
         ;; an empty db):
         ;;   :rf.frame/generation   — the resolved generation (nil ⇒ ordinary
         ;;                            configured frame; cleared on a re-make that
         ;;                            drops `:images`). reg-frame seats it into
         ;;                            the `:generation` slot and strips it from
         ;;                            the stored config.
         ;;   :rf.frame/initial-db   — the app-db seed (construction-only; seeded
         ;;                            into the fresh frame-state on first create,
         ;;                            NOT re-applied on an idempotent re-mount —
         ;;                            durable state is preserved). Stripped from
         ;;                            the stored config.
         ;;   :rf.frame/capabilities — the host capability map (NOT an app config
         ;;                            field) so `reload-images!` / reprojection
         ;;                            re-check it by id; KEPT in the stored config.
         ;;   :rf.frame/adapter      — the active-substrate adapter binding, KEPT
         ;;                            in the stored config so tooling (Xray's
         ;;                            image/frame view) can read it by id.
         record-config (cond-> (apply dissoc opts image-selection-opt-keys)
                         true                 (assoc :rf.frame/generation generation)
                         (some? initial-db)   (assoc :rf.frame/initial-db initial-db)
                         (some? capabilities) (assoc :rf.frame/capabilities capabilities)
                         (some? adapter)      (assoc :rf.frame/adapter adapter))]
     ;; Create (or idempotently update) the ONE `frames`-registry record under
     ;; the id. `reg-frame` is idempotent on an existing id (surgical update
     ;; preserving runtime state — EP-0024 §Duplicate id policy), installs the
     ;; generation + (first-create only) the initial-db seed BEFORE running
     ;; `:on-create`, and applies the record-config (presets, classification,
     ;; fx-overrides, …). Default-realm (the collapse supersedes the public realm
     ;; dimension), so the record is keyed by the bare id.
     (frame/reg-frame runnable-id record-config)
     ;; Return the frame VALUE — the lifecycle token (generation not embedded;
     ;; read from the record by id).
     (frame/make-frame-value {:id           id
                              :runnable-id  runnable-id
                              :initial-db   initial-db
                              :capabilities capabilities
                              :adapter      adapter}))))

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

;; ---- target resolution (frame id OR frame value) ---------------------------

(defn- resolve-reload-id
  "Resolve a `reload-images!` `target` to the frame ID to reload (EP-0024,
  rf2-tu2vr7). `target` is EITHER a frame id or a frame VALUE; both normalize to
  the id its record is keyed by in the ONE registry. FAIL-LOUD when the id names
  no IMAGE-LOADED frame (`:rf.error/reload-no-such-frame`) — a frame carries a
  resolved generation iff it was created with `:images`. Returns the frame id."
  [target]
  (let [id (frame/frame-value->id target)]
    (if (some? (frame/frame-generation id))
      id
      (error/throw-error!
        :rf.error/reload-no-such-frame
        'rf/reload-images!
        (str "rf/reload-images!: target " (pr-str target) " names no image-loaded "
             "frame. Reload targets ONE frame, by a frame id or a frame value "
             "returned by rf/make-frame whose record carries a resolved image "
             "generation. Image-loaded frame ids: "
             (pr-str (vec (live-frame-ids))) ".")
        {:recovery :target-an-image-loaded-frame-id-or-value
         :extra    {:target target
                    :live-frame-ids (vec (live-frame-ids))}}))))

;; ---- the in-place generation swap (PRESERVES FRAME MEMORY) -----------------

(defn- swap-frame-generation!
  "Swap `new-generation` onto frame `id`'s record IN PLACE via
  `frame/set-generation!`, preserving every other (state-bearing) slot by
  identity, so frame memory continues across the swap (EP-0024 §One live frame
  registry; the EP-0023 §Hot Reload contract — \"Hot reload must not be
  implemented by tearing down and recreating the frame\"). The generation lives
  on the ONE record, so an `:id`-bearing frame and any holder of its frame value
  observe the swap through the same record. Returns nil."
  [id new-generation]
  (frame/set-generation! id new-generation))

;; ---- reload-images! — the public reload (EP-0023 §Public API) --------------

(defn reload-images!
  "Hot-reload ONE frame's whole image composition, PRESERVING FRAME MEMORY
  (EP-0023 §Hot Reload / §Public API — `rf/reload-images!`). PUBLIC.

  `target` is EITHER a frame id or a frame VALUE (`make-frame`'s return token),
  both naming an image-loaded frame in the ONE registry. `opts` is a map taking
  the SAME `:images` vector shape as `make-frame`:

    :images  a VECTOR of image values — the frame's NEW, COMPLETE image
             composition (the only spelling; always a vector). Reload is
             composition-REPLACING: it replaces the whole `:images` vector, not
             one member. A non-vector `:images` fails loud
             (`:rf.error/make-frame-bad-images`), exactly as in `make-frame`.

  Reload re-assembles `:images` into a FRESH sealed generation, then SWAPS that
  generation onto the frame's record — only the `:generation` slot moves; app-db,
  runtime-db, caches, lifecycle continue unchanged (EP-0023 §Hot Reload). It does
  NOT mutate the old generation and does NOT move sibling frames that previously
  shared it (reload is frame-targeted — EP-0023 §Image).

  Returns the reload REPORT:

    {:rf.frame/frame  <frame value for the reloaded frame>
     :rf.reload/diff  {:added #{[kind id] …} :changed #{…}
                       :removed #{…} :retained #{…}}}

  The generation lives on the ONE `frames` record, updated IN PLACE, so the id
  keeps naming the same live context (now running the new generation) and every
  holder of the frame value observes the new generation through the record. The
  returned `:rf.frame/frame` is a frame value for the reloaded frame.

  Two arities mirror `make-frame`:
    (reload-images! target opts)             — re-assemble against the LIVE source store.
    (reload-images! target opts descriptors) — against an explicit descriptor pool
                                               (tests / harnesses / a pre-snapshotted
                                               store), matching `assemble`'s 2-arity."
  ([target opts] (reload-images! target opts nil))
  ([target {:keys [images]} descriptors]
   (let [id             (resolve-reload-id target)
         old-generation (frame/frame-generation id)
         capabilities   (frame/frame-capabilities id)
         new-generation (resolve-generation! images capabilities descriptors)]
     (swap-frame-generation! id new-generation)
     {:rf.frame/frame (frame/make-frame-value {:id id :runnable-id id})
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
  frame-id whose record carries no generation (not image-loaded)."
  [frame-id]
  (when-let [old-generation (frame/frame-generation frame-id)]
    (let [images         (vec (:rf.gen/images old-generation))
          capabilities   (frame/frame-capabilities frame-id)
          new-generation (resolve-generation! images capabilities nil)]
      (when-not (= old-generation new-generation)
        (swap-frame-generation! frame-id new-generation)
        (generation-diff old-generation new-generation)))))

(defn reproject-live-frames!
  "Reproject EVERY image-loaded frame against the CURRENT live source store (the
  `reg-*` hot-reload path — EP-0023 §Default Image Semantics / §Hot Reload). For
  each frame whose `frames` record carries a `:generation`, re-resolve its OWN
  `:rf.gen/images` composition and swap the new generation on when it changed;
  this reprojects EXPLICIT-image frames (whose `:include-ns` selectors match a
  changed namespace) as well as default-image frames, not only the latter. A
  frame whose composition re-resolves byte-for-byte is left untouched.

  Returns `{frame-id reload-diff}` for every frame that MOVED (empty when none
  did). EP-0024 (rf2-tu2vr7): with the registries collapsed, an image-loaded
  frame is simply a `frames` record carrying a generation — `live-frame-ids`
  enumerates them all from the ONE registry."
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

;; EP-0024 (rf2-tu2vr7): the `reprojecting?` re-entrancy guard DISSOLVED. It
;; existed only because the EP-0023 two-registry `make-frame` created its backing
;; record via `frame/reg-frame` (a `register!`), raising the defensive worry that
;; a reproject flush might provoke a registration and re-arm itself. Under the
;; unified model reprojection swaps the generation onto the ONE record via
;; `frame/set-generation!` — a plain `swap!`, NOT a `register!` — so the flush can
;; never fire the registration hook and never schedule its own successor. The
;; guard's purpose is gone with the second registry.

(defn flush-pending-reprojection!
  "If a `reg-*` source-store change has marked the projection dirty, clear the
  flag and reproject EVERY image-loaded frame ONCE against the current source
  store (the coalesced batch boundary — EP-0023 §Default Image Semantics). A
  no-op when nothing is pending. Returns the `{frame-id reload-diff}` map of
  frames that MOVED (empty when none did or when nothing was pending).

  The synchronous counterpart of the `next-tick`-scheduled flush
  `mark-dirty-and-schedule!` arms: tests (and any caller that needs the
  reprojection to have happened by a known point) invoke this to force the
  pending reprojection through deterministically rather than awaiting the
  deferred tick. The dirty-flag clear is read-then-reset so a re-entrant
  `reg-*` during reprojection re-arms a fresh flush rather than being lost.

  EP-0024 (rf2-tu2vr7): the reproject swaps generations via
  `frame/set-generation!` (a plain `swap!`), never `reg-*`, so it cannot fire the
  registration hook or schedule its own successor — the former `reprojecting?`
  re-entrancy guard dissolved with the second registry."
  []
  (if @pending-reprojection?
    (do (reset! pending-reprojection? false)
        (reproject-live-frames!))
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

  The NO-IMAGE-LOADED-FRAME skip keeps this off the hot path so an ordinary
  registration burst with no image-loaded frames costs essentially nothing
  (rf2-h4q6cy fix): reprojection only ever touches image-loaded frames
  (`reproject-live-frames!` enumerates `live-frame-ids` = the `frames` records
  carrying a `:generation`). With none the flush would be a guaranteed no-op, so
  there is nothing to mark dirty or schedule. This is the dominant case: every
  `reg-event` / `reg-sub` / `reg-fx` — and the `reg-frame` of EVERY frame's
  record — funnels through `register!` and so fires this hook, but the
  overwhelming majority run while NO image-loaded frame exists (app boot, every
  handler-only test). Marking + scheduling on each would flood
  `interop/next-tick` with one no-op flush per registration; skipping when
  nothing is reprojectable removes the flood, while a `reg-*` issued while an
  image-loaded frame DOES exist (the headline-guarantee case) still marks +
  schedules.

  EP-0024 (rf2-tu2vr7): the former `reprojecting?` re-entrancy skip dissolved —
  reprojection swaps generations via `frame/set-generation!` (not `reg-*`), so a
  flush can never fire this hook or schedule its own successor."
  []
  (when (seq (live-frame-ids))
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
