(ns day8.re-frame2-xray.panels.image-view-reads
  "Live read seam + Xray-as-its-own-image constructor for the EP-0023
  IMAGE / FRAME inspection (rf2-32siq3.12).

  ## The two jobs

  1. **Live reads (READ-TIME fail-soft).** The EP-0023 public model — the
     live-frame registry + sealed image generations — is read here, kept OUT of
     the pure `image_view_helpers.cljc` algebra so that algebra stays
     JVM-testable `data -> data`. Every read is `try`-guarded and degrades to
     the empty value, so an image/frame browse never throws AT READ TIME on a
     malformed/absent runtime value or any read-path throw. (This is read-time
     robustness, NOT an absent-core-surface tolerance: the EP-0023 core
     namespaces are hard `:require`d below, so a core too old to expose them
     fails at LOAD, before any try/catch here runs — unlike a surface that
     reflectively probes for an optional API.)

  2. **Xray-as-its-own-image (the dogfooding — EP-0023 §Xray Beside The
     Target).** Xray is itself a running surface with registrations, state,
     views, subscriptions, and effects. EP-0023's headline isolation case is
     that the inspector MUST NOT share the target's registration set. This ns
     realizes the CONSTRUCT-and-PROVE half of that case: Xray builds its OWN
     real `rf/image` (a separate registration-set value) and PROVES it
     registration-disjoint from the target frame's image, inspecting the target
     frame as DATA.

         > Xray runs in its own frame. Xray inspects the target frame.
         > That keeps the inspection tool from becoming part of the thing
         > being inspected. (EP-0023 §Xray Beside The Target)

     SCOPE NOTE (honest claim): this ns CONSTRUCTS Xray's own image and proves
     the isolation invariant; it does NOT yet SEAT the running Xray shell in a
     frame built from that image via `rf/make-frame`. Xray today still runs on
     the ambient registrar like any other surface; actually seating Xray in its
     own frame is deferred follow-up (rf2-32siq3.36). What is realized here is
     the image VALUE + the proven disjointness — Xray's instruction set is a
     separate image, provably non-overlapping with the target's — not the
     full Xray-runs-in-its-own-frame runtime.

     This ns constructs Xray's own registration set as an EP-0023 `rf/image`
     — selected by the `:select-ns :include` glob over Xray's OWN source
     namespaces (`day8.re-frame2-xray.**`), under which every `:rf.xray/*`
     registration
     is authored (and stamped `:rf.provenance/ns` in the source store, which
     survives production elision). The result is an inert image VALUE: Xray's
     instruction set, separate from any target frame's image. The target
     frame is read as data through the live-read fns above; its generation
     and state never mix with Xray's image, and Xray's `:rf.xray/*`
     registrations never leak INTO the target frame's image (a target image
     selects the target's own namespaces, not Xray's).

  ## Why Xray may require the EP-0023 core surfaces directly

  Xray is a TOOL that consumes the framework's instrumentation + introspection
  surfaces; it already requires internal core namespaces (`re-frame.frame`,
  `re-frame.registrar`, `re-frame.machines`, …). Bundle isolation forbids
  `implementation/` requiring from `tools/`, NOT the reverse, so requiring
  `re-frame.live-frame` / `re-frame.image` / `re-frame.image-assembly` here is
  consistent with the established pattern. These EP-0023 read seams are not on
  the `re-frame.core` facade, so the tool reads the owning namespaces directly,
  exactly as it does for the realm substrate."
  (:require [clojure.set :as set]
            [re-frame.live-frame :as live-frame]
            [re-frame.image :as image]
            [re-frame.image-assembly :as image-assembly]
            [re-frame.trace :as trace]
            [day8.re-frame2-xray.panels.image-view-helpers :as h]))

;; ===========================================================================
;; Live reads of the EP-0023 public model (fail-soft)
;; ===========================================================================

(defn live-frames
  "The image-loaded frames as `{frame-id frame-view}` (EP-0024 §One live frame
  registry, rf2-tu2vr7), read via `re-frame.live-frame/image-view-frames`. With
  the registries collapsed an image-loaded frame is a single `frames` record
  carrying a resolved generation; the seam projects each into the inert frame
  view (`:rf.frame/object` / `:rf.frame/generation` / `:rf.frame/id` /
  `:rf.frame/adapter` / …) the pure projectors here consume. Fail-soft: a
  core too old to expose the seam (or any throw) degrades to `{}`, so an
  image/frame browse always has a value. Pure-read."
  []
  (try
    (let [reg (live-frame/image-view-frames)]
      (if (map? reg) reg {}))
    (catch :default _ {})))

(defn resolve-descriptor
  "Resolve `(kind, id)` against a sealed `generation` via
  `re-frame.image-assembly/resolve-descriptor` (the runtime registration-
  resolution read — EP-0023 §Specification). Fail-soft: a nil generation or
  any throw yields nil (unresolved). Pure-read."
  [generation kind id]
  (try
    (image-assembly/resolve-descriptor generation kind id)
    (catch :default _ nil)))

(defn image-view-data
  "Project the live EP-0023 image/frame model into the view-facing data
  (rf2-32siq3.12): the live-frame registry → frame-rows, each carrying its
  resolved image (its generation's `[kind id]` descriptors). Fail-soft
  composition of `live-frames` + the pure `h/project-image-view`. Pure-read
  `() -> data`."
  []
  (h/project-image-view (live-frames)))

;; ===========================================================================
;; Xray-as-its-own-image — the dogfooding (EP-0023 §Xray Beside The Target)
;; ===========================================================================

(def xray-image-id
  "The id of Xray's OWN image — the inspector's instruction set, separate from
  any target frame's image (EP-0023 §Xray Beside The Target). Namespaced under
  `:rf.xray/*` for the same isolation discipline as the rest of the Xray
  registry."
  :rf.xray/image)

(def xray-source-glob
  "The `:select-ns :include` glob selecting Xray's OWN registrations by their
  source namespace (EP-0026 §Namespace Selection). Every `:rf.xray/*`
  registration is authored under `day8.re-frame2-xray.*` and stamped
  `:rf.provenance/ns` in the source store (which survives production elision),
  so this glob selects Xray's instruction set and ONLY Xray's — a target
  frame's image selects the target's own namespaces, never this one."
  "day8.re-frame2-xray.**")

(def xray-exclude-globs
  "The `:select-ns :exclude` globs that SUBTRACT Xray's OWN test + test-support
  namespaces from the `xray-source-glob` selection (EP-0026 §Namespace
  Selection, EP-0023 §Xray Beside The Target).

  The headline-isolation `day8.re-frame2-xray.**` `:select-ns :include` glob
  sweeps in EVERY Xray-authored registration — including, in any dev/test build
  that loads them, Xray's OWN `*-cljs-test` namespaces. Those tests co-register
  the same `:rf.xray/*` / `:rf.*` ids the production sources do (e.g.
  `day8.re-frame2-xray.open-in-editor-cljs-test` registers `[:fx :rf.editor/open]`
  alongside the production `day8.re-frame2-xray.open-in-editor`). Within one image
  a `(kind, id)` collision is fail-loud (`:rf.error/image-duplicate-id`) — which
  blocked flipping the production singleton onto image-loaded seating.

  Two excludes cover the two shapes of Xray's non-production namespaces:

    - `day8.re-frame2-xray.**.*-cljs-test` — the `deftest` namespaces (every
      Xray test ns's leaf segment ends `-cljs-test`, at any depth). The `**`
      absorbs the intermediate segments (it matches the depth-1
      `…mount-cljs-test` and the deep `…panels.app-db-diff-cljs-test` alike) and
      the intra-segment `*-cljs-test` matches the leaf suffix WITHIN that one
      segment (the EP-0023 intra-segment glob — the `*` never crosses a `.`).
    - `day8.re-frame2-xray.test-helpers.**` — the test-support host fixtures
      (`…test-helpers.host-fixtures.counter` et al), a clean whole-segment
      subtree that registers APP fixture ids, not Xray's production set.

  Production builds never load these namespaces, so the excludes are no-ops
  there (a `:select-ns :exclude` is NOT zero-match fail-loud — a defensive
  guard); in dev/test they keep the production image registration-disjoint from
  Xray's own test registrations so the singleton seats without an assembly
  collision."
  ["day8.re-frame2-xray.**.*-cljs-test"
   "day8.re-frame2-xray.test-helpers.**"])

(defn xray-image
  "Construct XRAY'S OWN EP-0023 image VALUE — the inspector's registration set
  as inert data, selected by the `:select-ns :include` glob over Xray's own
  source namespaces (EP-0023 §Xray Beside The Target / EP-0026 §Namespace
  Selection). PURE: `rf/image` is data, not registration (no realm, no
  registrar, no side effect).

  This is the registration-set value the dogfooding seats: Xray builds its OWN
  registration-set value (a SEPARATE image, not shared registration state),
  `xray-image-isolated-from?` proves it registration-disjoint from a target
  frame's image, and `seat-xray-frame!` SEATS a running Xray frame on it (true
  runtime self-seating — Xray runs in its own image-loaded frame, not the legacy
  shared registrar). The returned image value is Xray's instruction set; it
  never mixes with a target frame's image, and the target frame is inspected as
  DATA through the live-read fns.

  The `:select-ns :include` glob is NARROWED by `:select-ns :exclude`
  (`xray-exclude-globs`) so Xray's OWN `*-cljs-test` + `test-helpers.**`
  namespaces are subtracted — in a dev/test build that loads them, those
  co-register the same `:rf.xray/*` ids the production sources do, and without
  the exclude the `(kind, id)` collision is a fail-loud
  `:rf.error/image-duplicate-id` at assembly (the blocker that gated the
  production-singleton flip). Production builds never load those namespaces, so
  the exclude is a no-op there.

  Returns the normalized inert image value (`:rf.image/id` /
  `:rf.image/include-ns` / `:rf.image/exclude-ns` / …)."
  []
  (image/image {:id        xray-image-id
                :select-ns {:include [xray-source-glob]
                            :exclude xray-exclude-globs}}))

(defn resolver-keyset
  "The set of `[kind id]` resolver keys a sealed image `generation` carries
  (`(keys (:rf.gen/resolver generation))` as a set) — the registration set a
  frame running that generation actually resolves (EP-0023 §Specification
  Summary). A nil generation has the empty keyset. Pure `data -> #{[kind id]}`."
  [generation]
  (set (keys (:rf.gen/resolver generation))))

(defn application-resolver-keyset
  "The set of `[kind id]` resolver keys a sealed `generation` carries that are
  APPLICATION-owned — i.e. excluding the FRAMEWORK-STANDARD registrations
  (descriptors stamped `:standard true`) the EP-0023 assembly unions into EVERY
  generation (EP-0023 §Image — \"+ framework standard registrations\", e.g.
  `[:interceptor :rf.interceptor/path]`; rf2-32siq3.41). A framework standard is
  present in every frame BY DEFINITION — it is the framework, not a leak between
  two application images — so the registration-isolation invariant
  (`xray-image-isolated-from?`) compares only the application-owned keysets. A
  nil generation has the empty keyset. Pure `data -> #{[kind id]}`."
  [generation]
  (into #{}
        (comp (remove (fn [[_kid descriptor]] (:standard descriptor)))
              (map key))
        (:rf.gen/resolver generation)))

(defn xray-image-isolated-from?
  "True iff XRAY'S OWN image and a `target-image` are REGISTRATION-DISJOINT —
  the EP-0023 §Xray Beside The Target invariant that the inspector's
  registrations do not leak into / from the target frame's image — that keeps
  the inspection tool from becoming part of the thing being inspected.

  The check is on the REAL non-leakage invariant, not a proxy: ASSEMBLE both
  images into sealed generations and compare their APPLICATION-OWNED
  `:rf.gen/resolver` KEYSETS (the `[kind id]` pairs each frame would resolve,
  EXCLUDING the framework-standard registrations the EP-0023 assembly unions
  into every generation — `[:interceptor :rf.interceptor/path]` et al, stamped
  `:standard true`; rf2-32siq3.41). Isolation holds iff the two
  application-owned keysets are DISJOINT — no APPLICATION `[kind id]` is resolved
  by BOTH a frame built from Xray's image and a frame built from the target's
  image, so neither frame can see the other's APPLICATION registrations. A
  framework standard is shared by every frame by construction (it is the
  framework, not a leak between two images), so it is excluded from the
  comparison rather than reported as a false-positive overlap. This is stronger
  than comparing the
  `:rf.image/include-ns` selector strings (the prior proxy): different globs can
  select OVERLAPPING namespaces, and inline `:registrations` carry no
  `:select-ns` selector at all, yet either can introduce a shared `[kind id]` —
  the keyset comparison catches both, the string comparison neither.

  Two arities:

    (xray-image-isolated-from? target-image)
      assemble BOTH images against the LIVE registration source store (the real
      runtime: where Xray's `:rf.xray/*` registrations and the target's
      registrations both live). The honest production-runtime check.

    (xray-image-isolated-from? target-image pool)
      assemble BOTH images against an EXPLICIT descriptor `pool` (a flat seq of
      descriptor maps, the source store's output shape) — the deterministic
      test/harness form that does not depend on what the live store carries.
      Both images select from the SAME pool, so a `[kind id]` authored under a
      namespace BOTH images select surfaces as a shared resolver key (the
      check FAILS, correctly), proving the predicate is a real disjointness
      test and not a constant true.

  Fail-soft: assembly is fail-loud (a zero-match `:select-ns :include`, a
  collision), and a core too old to expose the EP-0023 assembly surfaces would
  throw. A
  throw means isolation could NOT be assembled and PROVEN, so the predicate is
  CONSERVATIVE and returns `false` (not-proven-isolated) rather than a
  false-positive `true`. `target-image` is a normalized image value
  (`rf/image`'s return). Pure `data -> bool` (modulo the read of the live store
  in the single-arity form)."
  ([target-image]
   (try
     (let [xray-gen   (image-assembly/assemble [(xray-image)])
           target-gen (image-assembly/assemble [target-image])]
       (empty? (set/intersection (application-resolver-keyset xray-gen)
                                 (application-resolver-keyset target-gen))))
     (catch :default _ false)))
  ([target-image pool]
   (try
     (let [xray-gen   (image-assembly/assemble [(xray-image)] pool)
           target-gen (image-assembly/assemble [target-image] pool)]
       (empty? (set/intersection (application-resolver-keyset xray-gen)
                                 (application-resolver-keyset target-gen))))
     (catch :default _ false))))

(defn xray-frame-seated?
  "True iff an image-loaded frame is already registered under `frame-id` (its
  one-registry record carries a resolved generation) — the idempotency probe
  `seat-xray-frame!` reads to skip a redundant re-seat (re-open, hot-reload,
  repeated testbed mount). EP-0024 (rf2-tu2vr7): the registries collapsed and a
  duplicate `:id` is now idempotent replacement (no fail-loud gate), so this is
  a benign skip-optimisation rather than a guard against a throw. Pure read."
  [frame-id]
  (some? (live-frame/live-frame frame-id)))

(defn seat-xray-frame!
  "SEAT a running Xray frame in its OWN EP-0023 image-loaded frame — the TRUE
  runtime dogfood (EP-0023 §Xray Beside The Target, rf2-32siq3.36). This is the
  runtime counterpart to `xray-image` (the image VALUE) and
  `xray-image-isolated-from?` (the proven disjointness): Xray runs in genuine
  registration ISOLATION from the inspected target — its `:rf.xray/*`
  registrations are resolved through the frame's OWN sealed image generation
  (built from `(xray-image)`), never the shared default registrar.

  Replaces the legacy realm seating (`reg-frame frame-id {:rf.trace/frame-no-
  emit? true}`), which produced the shell frame against the process-global
  registrar and relied on id-collision-avoidance. Here the frame resolves ONLY
  Xray's image (plus the framework-standard registrations the assembly unions
  into every generation) — the EP's literal `(rf/make-frame {:id … :images
  [(xray-image)] …})` shape.

  ## Idempotency

  EP-0024 (rf2-tu2vr7): `make-frame {:id …}` is IDEMPOTENT REPLACEMENT on a
  duplicate `:id` (config + generation refresh, durable state preserved) — it no
  longer fails loud. The seating still runs `make-frame` ONLY when `frame-id` is
  not already image-loaded (`xray-frame-seated?`), now as a benign
  skip-optimisation rather than a guard against a throw: a re-open / hot-reload /
  repeated testbed mount that finds the frame already seated SKIPS the
  `make-frame` and just re-asserts the trace-emission gate below (the per-frame
  app-db seeding is the caller's idempotent first-mount-hook chain, not this
  fn's job).

  ## Trace-emission gate (rf2-2qaqh — preserved)

  `make-frame` is the EP-0023 OBJECT constructor: it honours only the
  frame-creation opts (`:images` / `:id` / `:initial-events` / …) and would reject
  the record-config `:rf.trace/frame-no-emit?` flag. That flag is frame-scoped
  trace state owned by `re-frame.trace`, keyed by frame-id independently of the
  generation, so it is set DIRECTLY via `trace/set-frame-no-emit!` — the same
  canonical seam `reg-frame` routes the flag through. Asserted on EVERY call
  (seat or re-seat) so a hot-reload never drops the gate that keeps Xray's own
  reactive substrate from flooding the trace ring it inspects.

  `frame-id` is the shell frame id (`:rf/xray` for the production singleton, a
  distinct id for a second testbed shell). Two arities mirror the other EP-0023
  seams here:

    (seat-xray-frame! frame-id)
      seat against the LIVE source store (the production runtime: where
      `register-xray-handlers!` registers Xray's `:rf.xray/*` instruction set).

    (seat-xray-frame! frame-id pool)
      seat against an EXPLICIT descriptor `pool` (the deterministic test/harness
      form `make-frame`'s 2-arity takes — does not depend on what the live store
      carries).

  Returns the live frame OBJECT on a fresh seat, or nil on a re-seat skip."
  ([frame-id] (seat-xray-frame! frame-id ::live))
  ([frame-id pool]
   (let [already? (xray-frame-seated? frame-id)
         opts {:id frame-id :images [(xray-image)]}
         obj (when-not already?
               (if (= pool ::live)
                 (live-frame/make-frame opts)
                 (live-frame/make-frame opts pool)))]
     ;; Frame-scoped trace gate (rf2-2qaqh): mark the shell frame a tool /
     ;; inspector frame so its own `:rf.sub/run` / `:rf.view/render` reactivity
     ;; does NOT flood the shared trace ring it inspects. Independent of the
     ;; image generation, so set on both fresh seat and re-seat.
     (trace/set-frame-no-emit! frame-id true)
     obj)))
