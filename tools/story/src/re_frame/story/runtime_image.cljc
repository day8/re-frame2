(ns re-frame.story.runtime-image
  "The canonical Story RUNTIME IMAGE — the one reusable `rf/image` value that
  selects Story's own runtime registrations so every variant frame can see the
  Story machinery (the lifecycle machine, the `:rf.assert/*` handlers, the
  fx-stub redirects, the runtime helper events, the toolbar cofx) regardless of
  which application image the variant resolves its behaviour against.

  ## Why this exists (EP-0026 §Default Image)

  Under EP-0026 a frame created with `rf/make-frame` ALWAYS carries a resolved
  image generation: a PRESENT `:images` resolves the selected generation; an
  ABSENT `:images` resolves the DEFAULT image — the implicit selector over the
  WHOLE source store, FAILING LOUD on a cross-namespace same-`[kind id]`
  collision. A variant frame is created by `re-frame.story.frames/allocate!`, and
  the frame's cascade (event-handler lookup, cofx, fx) resolves through the
  frame's OWN generation (EP-0023 §Frame-derived live registration resolution).

  Before EP-0026 an image-less variant frame carried NO generation and resolved
  against the global registrar (absence-is-default), so it transparently saw the
  Story runtime registrations co-loaded into the process. Under EP-0026 that
  whole-store fallback is the DEFAULT image, which (a) collides when several
  apps co-load same-`[kind id]` registrations into one test process, and (b)
  scopes the frame to a single SELECTED projection that must EXPLICITLY include
  the Story machinery — otherwise the frame's dispatches resolve no
  `:rf.assert/*` handler / no lifecycle machine and the play-runner reports
  `:cannot-run`.

  The runtime image is the library's contract: Story authors NEVER hand-write it.
  `allocate!` composes it LAST into every variant frame's `:images` vector
  (later image wins — EP-0026 §Layered Resolution), so the Story machinery is
  always live in the frame no matter what application image the author selected.

  ## What it selects

  A single namespace selector over `re-frame.story.**`. Every canonical
  installer (`re-frame.story.canonical/canonical-installers`) registers from
  within a `re-frame.story.*` namespace — the lifecycle machine + transition
  events (`re-frame.story.loaders`), the seven `:rf.assert/*` handlers + the
  `record!` append event (`re-frame.story.assertions`), the runtime helper
  events (`re-frame.story.runtime`), the per-variant frame helpers
  (`re-frame.story.frames`), the fx-stub redirects (`re-frame.story.fx-stubs`),
  the save-variant handlers (`re-frame.story.save-variant`), the toolbar cofx
  (`re-frame.story.ui.cofx`), and the `:rf.story/force-fx-stub` decorator's
  runtime support — so this glob selects the whole Story runtime set by
  PROVENANCE (`:rf.provenance/ns`). Selection is by provenance, never by the
  registration-id's namespace, so the reserved `:rf.assert/*` ids (registered
  FROM `re-frame.story.assertions`) are selected too — no separate
  `:rf.assert.*` clause is needed.

  Note the lifecycle machine event (`re-frame.story.loaders/install!`) is
  registered with an EXPLICIT `re-frame.story.loaders` provenance, because it
  goes through the `reg-machine*` runtime FN (not the macro that would stamp
  provenance from `*pending-coords*`) — so this glob selects it too.

  ## What it does NOT need to select

  The framework MACHINE runtime (`:rf.machine/spawn` / `:rf.machine/destroy` /
  `:rf/machine` …) is NOT selected here. The Story lifecycle is a spec/005 state
  machine, and a variant frame's hosted machines need the machine runtime in
  their generation — but those reserved `:rf.machine/*` / `:rf/machine*`
  registrations are FRAMEWORK STANDARDS (registered as standards by
  `re-frame.machines/install-machine-runtime!`, EP-0026 §Framework
  Standard Registrations), so they are unioned into EVERY generation
  automatically. Selecting their namespace here would be both redundant and a
  fail-loud `:rf.error/image-standard-replacement-forbidden` collision against
  the protected standards.

  Selection SELECTS, it does not LOAD: the glob filters registrations the
  runtime already knows about, so an app that never loads Story pays nothing and
  Closure DCE removes the unused path.

  ## Image id

  The image carries a stable `:id` (`:rf.story/runtime`) so the cross-image
  shadow report (the generation's `:rf.gen/shadows`) and tooling name it
  unambiguously. The id
  is library-owned under the `:rf.story/*` namespace (spec/Conventions.md).

  ## Composition position

  `allocate!` composes the variant frame's `:images` as
  `[<story-images…> <variant-images…> runtime-image]`. The runtime image is
  LAST, so on the (rare, app-author-error) overlap of an app registration with a
  Story-runtime `[kind id]`, the Story runtime wins — the frame's own lifecycle
  + assertion machinery is never shadowed out by an app. App images and the
  runtime image normally select DISJOINT namespaces, so no shadow arises at all."
  (:require [re-frame.core :as rf]))

(def runtime-image-id
  "The stable id stamped on the Story runtime image (`:rf.image/id`). Library-
  owned under the `:rf.story/*` reserved namespace."
  :rf.story/runtime)

(def runtime-select-globs
  "The `:select-ns :include` globs the runtime image projects — every Story
  runtime registration is authored from a `re-frame.story.*` namespace, so one
  recursive glob selects the whole set by `:rf.provenance/ns` (including the
  reserved `:rf.assert/*` handlers registered from `re-frame.story.assertions`
  and the lifecycle machine event registered with an explicit
  `re-frame.story.loaders` provenance). The framework MACHINE runtime is NOT
  here — it is unioned into every generation as framework STANDARDS (see the ns
  docstring §What it does NOT need to select)."
  ["re-frame.story.**"])

(defn runtime-image
  "Return the canonical Story RUNTIME IMAGE value (built via `rf/image`). PURE —
  the image is inert data; building it triggers no registration. Composed LAST
  into every variant frame's `:images` by `re-frame.story.frames/allocate!` so
  the Story machinery (lifecycle machine, `:rf.assert/*` handlers, fx-stub
  redirects, runtime helpers, toolbar cofx) is live in the frame regardless of
  the application image the variant selects.

  Recomputed per call (a cheap `rf/image` map build); callers that thread it
  into a hot path may hold the returned value. The selection is by namespace
  glob (see `runtime-select-globs`) over `:rf.provenance/ns`, so it tracks
  whatever Story runtime registrations are installed in the current generation
  without enumeration."
  []
  (rf/image
    {:id        runtime-image-id
     :select-ns {:include runtime-select-globs}}))
