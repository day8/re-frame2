(ns day8.re-frame2-xray.panels.image-view-reads
  "Live read seam + Xray-as-its-own-image constructor for the EP-0023
  IMAGE / FRAME inspection (rf2-32siq3.12).

  ## The two jobs

  1. **Live reads (fail-soft).** The EP-0023 public model — the live-frame
     registry + sealed image generations — is read here, kept OUT of the pure
     `image_view_helpers.cljc` algebra so that algebra stays JVM-testable
     `data -> data`. Every read is `try`-guarded and degrades to the empty
     value, so an image/frame browse never throws on a core too old to expose
     the EP-0023 surfaces (the same fail-soft discipline as
     `static/shared/realm.cljs`).

  2. **Xray-as-its-own-image (the dogfooding — EP-0023 §Xray Beside The
     Target).** Xray is itself a running surface with registrations, state,
     views, subscriptions, and effects. EP-0023's headline isolation case is
     that the inspector MUST NOT share the target's registration set: Xray
     runs in its OWN image/frame and inspects the target frame as DATA.

         > Xray runs in its own frame. Xray inspects the target frame.
         > That keeps the inspection tool from becoming part of the thing
         > being inspected. (EP-0023 §Xray Beside The Target)

     This ns constructs Xray's own registration set as an EP-0023 `rf/image`
     — selected by the `:include-ns` glob over Xray's OWN source namespaces
     (`day8.re-frame2-xray.**`), under which every `:rf.xray/*` registration
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
            [day8.re-frame2-xray.panels.image-view-helpers :as h]))

;; ===========================================================================
;; Live reads of the EP-0023 public model (fail-soft)
;; ===========================================================================

(defn live-frames
  "The EP-0023 process-local live-frame registry snapshot
  `{frame-id frame-object}` (EP-0023 §Frame / §Id Spaces), read via
  `re-frame.live-frame/live-frames`. Fail-soft: a core too old to expose the
  EP-0023 registry (or any throw) degrades to `{}`, so an image/frame browse
  always has a value. Pure-read."
  []
  (try
    (let [reg @live-frame/live-frames]
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
  "The `:include-ns` glob selecting Xray's OWN registrations by their source
  namespace (EP-0023 §Namespace-Selected Images). Every `:rf.xray/*`
  registration is authored under `day8.re-frame2-xray.*` and stamped
  `:rf.provenance/ns` in the source store (which survives production elision),
  so this glob selects Xray's instruction set and ONLY Xray's — a target
  frame's image selects the target's own namespaces, never this one."
  "day8.re-frame2-xray.**")

(defn xray-image
  "Construct XRAY'S OWN EP-0023 image VALUE — the inspector's registration set
  as inert data, selected by the `:include-ns` glob over Xray's own source
  namespaces (EP-0023 §Xray Beside The Target / §Namespace-Selected Images).
  PURE: `rf/image` is data, not registration (no realm, no registrar, no side
  effect).

  This is the dogfooding the EP names: Xray models itself as a SEPARATE
  image/frame, not as shared registration state. The returned image value is
  Xray's instruction set; it never mixes with a target frame's image, and the
  target frame is inspected as DATA through the live-read fns. Construct an
  Xray frame with this image (`(rf/make-frame {:id :rf.xray/main :images
  [(xray-image)] :initial-db {:rf.xray/target <target-frame-id>}})`) to run
  Xray beside the target without the two sharing a registration set.

  Returns the normalized inert image value (`:rf.image/id` /
  `:rf.image/include-ns` / …)."
  []
  (image/image {:id         xray-image-id
                :include-ns [xray-source-glob]}))

(defn xray-image-isolated-from?
  "True iff XRAY'S OWN image and a `target-image` are REGISTRATION-DISJOINT —
  the EP-0023 §Xray Beside The Target invariant that the inspector's
  registrations do not leak into / from the target frame's image. Compares the
  `:rf.image/include-ns` selectors: Xray selects `day8.re-frame2-xray.**`; a
  target frame's image selects the target's OWN namespaces. Isolation holds
  when no selector is shared (the two images select disjoint source
  namespaces). Pure `data -> bool`; the assertion the .29 dogfooding review
  verifies.

  `target-image` is a normalized image value (`rf/image`'s return). Returns
  true when the two images share no `:include-ns` selector — i.e. neither
  selects the other's source namespaces — so a frame built from one cannot see
  the other's registrations."
  [target-image]
  (let [xray-sel   (set (:rf.image/include-ns (xray-image)))
        target-sel (set (:rf.image/include-ns target-image))]
    (empty? (set/intersection xray-sel target-sel))))
