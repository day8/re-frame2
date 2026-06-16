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
  with zero binding cost — see [[call-with-frame-resolution]]). The requires are
  `re-frame.image-assembly` (the merged assembly entry point),
  `re-frame.registrar` (the `*generation*` resolution seam + the closed kind
  set), and `re-frame.error` (fail-loud diagnostics) — all already in the core
  spine."
  (:require [re-frame.image-assembly :as asm]
            [re-frame.registrar      :as registrar]
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

(defonce ^{:doc "Process-local live-frame registry: `{frame-id → frame-object}`.
  An `:id`-bearing `make-frame` registers its object here; a duplicate live id
  fails loud. Direct (no-id) frame objects never enter this map. INTERNAL — the
  registry the public query/lookup helpers read."}
  live-frames
  (atom {}))

(defn live-frame
  "Return the live frame OBJECT registered under `frame-id`, or nil when no live
  frame carries that id. The process-local frame-id lookup (EP-0023 §Frame — \"a
  frame id names a running context in a registry\"). Pure read of the registry
  snapshot."
  [frame-id]
  (get @live-frames frame-id))

(defn live-frame-ids
  "The set of frame ids currently live in the process-local registry. Tools and
  the later reload slice use this to enumerate the public frame-id space."
  []
  (set (keys @live-frames)))

(defn forget-live-frame!
  "Remove `frame-id` from the live-frame registry, returning the registry's new
  value. The teardown counterpart to `make-frame`'s registration; the later
  frame-lifecycle slice fires this from the frame's destroy boundary. INTERNAL —
  registry mutation only; it does not run any frame teardown of its own."
  [frame-id]
  (swap! live-frames dissoc frame-id))

(defn clear-live-frames!
  "Reset the live-frame registry. Test fixtures use this between cases so a
  registered `:id` from one case does not collide with the next."
  []
  (reset! live-frames {})
  nil)

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
                   frame created with no `:images` resolves the framework
                   standard set alone (the default-image path is a later slice).
    :id            the frame id (optional). When supplied, the returned object is
                   registered in the PROCESS-LOCAL LIVE-FRAME REGISTRY under this
                   id; a duplicate live id fails loud
                   (`:rf.error/live-frame-id-conflict`). When ABSENT, the frame is
                   LOCAL-ONLY: the caller keeps the returned object and passes it
                   directly to dispatch/subscribe/test helpers. The absence of
                   `:id` is NOT a default-id path (EP-0023 §Public API).
    :initial-db    the frame's initial app-db value (optional). Carried on the
                   object for the later state-container slice; frame STATE is a
                   frame concern, image is a behaviour concern (EP-0023 §Image
                   Patching And Overrides).
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

  Returns the live frame OBJECT — a map carrying `:rf.frame/object true`, the
  resolved `:rf.frame/generation`, the frame `:rf.frame/id` (when supplied), and
  the creation inputs. The later slices wire frame-derived live resolution (.9)
  against `:rf.frame/generation` and reload (.10) against the whole composition.

  Two arities:
    (make-frame opts)            — resolve `:images` against the LIVE source store.
    (make-frame opts descriptors)— resolve against an explicit descriptor pool
                                   (tests / harnesses / a pre-snapshotted store),
                                   matching `image-assembly/assemble`'s 2-arity."
  ([opts] (make-frame opts nil))
  ([{:keys [images id initial-db capabilities adapter]} descriptors]
   (let [images     (if (some? images) (validate-images! images) [])
         generation (if (nil? descriptors)
                      (asm/assemble images)
                      (asm/assemble images descriptors))]
     ;; Capability check at the FRAME boundary (EP-0023 §Public API): a required
     ;; capability absent from the frame's `:capabilities` fails BEFORE the
     ;; generation is runnable. The check runs UNCONDITIONALLY so a frame that
     ;; supplies NO `:capabilities` map still fails any non-empty
     ;; `:rf.image/requires` — an absent map provides nothing, exactly as
     ;; `{}` does. This preserves EP-0013's fail-loud install-time capability
     ;; check (`app_value/check-capabilities!`, which reads an absent realm
     ;; capability map as `#{}` and fails any unmet requirement) at the new
     ;; frame boundary; guarding the call on `(some? capabilities)` would let a
     ;; frame run an image whose requirements were never satisfied. A no-op when
     ;; the union `:rf.gen/requires` is empty (the common no-requirements path).
     (asm/check-capabilities! (:rf.gen/requires generation) capabilities)
     (let [frame-object
           (cond-> {object-marker  true
                    generation-key generation}
             (some? id)           (assoc :rf.frame/id id)
             (some? initial-db)   (assoc :rf.frame/initial-db initial-db)
             (some? capabilities) (assoc :rf.frame/capabilities capabilities)
             (some? adapter)      (assoc :rf.frame/adapter adapter))]
       ;; An :id-bearing frame registers in the process-local live-frame
       ;; registry (fail-loud on a duplicate). A direct (no-id) frame object
       ;; BYPASSES the registry — the caller holds the returned object directly
       ;; (EP-0023 §Frame / §Public API).
       (if (some? id)
         (register-live-frame! id frame-object)
         frame-object)))))
