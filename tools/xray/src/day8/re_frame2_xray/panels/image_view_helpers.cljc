(ns day8.re-frame2-xray.panels.image-view-helpers
  "Pure-data helpers for Xray's EP-0023 IMAGE / FRAME inspection — the
  `image -> frame -> event stream` public model (rf2-32siq3.12).

  ## The EP-0023 model this surface presents

  EP-0023 makes the public architecture `image -> frame -> event stream`
  (docs/EP/EP-0023-image-loaded-frames.md). This helper projects the three
  load-bearing nouns the operator inspects:

    - **image** — the SELECTED REGISTRATION-SET VALUE a frame resolves
      against. The inspectable, sealed form is the *resolved image
      generation*: a `{[kind id] descriptor}` resolver plus the union
      capability requirements and the kinds present (EP-0023 §Image /
      §Specification Summary). Tools present an image as that set of
      `[kind id]` descriptors — \"which registrations are visible to this
      frame?\". An image is NOT state; it is the instruction set.

    - **frame** — the live EXECUTION CONTEXT (EP-0023 §Frame). It owns
      app-db / runtime-db / queue / caches / traces / lifecycle and carries a
      reference to the ONE resolved image generation it runs. The frame is
      \"what has happened in this run?\"; the image is \"what can this frame
      run?\" (EP-0023 §Two Boundaries). A frame is presented as an execution
      context that points at its generation, not as registration state.

    - **frame-derived resolution** — the LOOKUP PATH
      `target frame -> resolved image generation -> registration resolution`
      (EP-0023 §Specification). What a given frame actually resolves a
      `(kind, id)` to depends on its generation, so two frames running
      DIFFERENT images resolve the same id to their OWN descriptor. This
      helper surfaces the resolution AS a path: given a frame and a
      `[kind id]`, what descriptor (and its provenance) does that frame's
      generation resolve it to?

  ## Why this lives beside the Module-view (EP-0013 substrate) tab

  EP-0023 partially supersedes EP-0013's public app/realm surface while
  RETAINING the realm machinery as an internal installation substrate
  (EP-0023 §Backwards Compatibility). The Module-view tab is already the
  cohesive home for the EP-0013 `(realm, frame)` / module inspection; this
  helper extends that same tab with the EP-0023 PUBLIC model. The two read
  DIFFERENT surfaces: the realm/module projection reads `rf/realm-ids` /
  `rf/installed-app` (the retained internal substrate); this image/frame
  projection reads the EP-0023 live-frame registry + sealed generations (the
  public model). A process using one and not the other renders only the
  section it has (each is fail-soft and demand-gated, like the realm
  dimension).

  ## Inert-data, fail-soft, JVM-testable

  Every fn here is pure `data -> data`. The live reads (the EP-0023
  registry/generation touches) live in `image_view_reads.cljs` and are
  passed IN as already-projected values, so this algebra runs under the JVM
  unit-test target (`clojure -M:test`) exactly like every other Xray panel
  helper. A frame object / generation / image is presented as the inert data
  it already is — `make-frame` returns an inert map and `assemble` seals an
  inert generation (EP-0023 §Image — \"an image is data, not registration\")."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.common-helpers :as common]))

;; ===========================================================================
;; Generation projection — an image as its `[kind id]` descriptor set
;; (EP-0023 §Image / §Specification Summary)
;; ===========================================================================

(defn descriptor-provenance
  "Project a resolved descriptor's PROVENANCE into a compact display value
  (EP-0023 §Namespace-Selected Images / §Image Fragments). A registered
  descriptor is identified by its source namespace string
  (`:rf.provenance/ns`); an inline descriptor by its containing image id +
  inline coordinate (`:rf.provenance/image` / `:rf.provenance/inline`); a
  framework standard by its `:standard true` marker. Returns

      {:kind :rf.prov/ns        :ns \"docs.counter.v2\"}
      {:kind :rf.prov/inline    :image :test/small :inline [:reg-event :counter/inc]}
      {:kind :rf.prov/standard}
      {:kind :rf.prov/unknown}

  The three provenance slots are MUTUALLY EXCLUSIVE on a well-formed
  descriptor, so this `cond`'s branch order tracks core's
  `re-frame.image-assembly/descriptor-coordinate` (the ground-truth
  source-coordinate fn) — `:standard` first, then the inline image coordinate,
  then the registered `:rf.provenance/ns` — rather than introducing a different
  precedence the projection would have to defend. (`descriptor-coordinate`'s
  final `:else` is the registered-ns case; here that leg is explicit so an
  unrecognized descriptor falls through to `:rf.prov/unknown` instead of
  `{:ns nil}`.) Pure `data -> data`; JVM-testable."
  [descriptor]
  (cond
    (:standard descriptor)
    {:kind :rf.prov/standard}

    (:rf.provenance/image descriptor)
    {:kind   :rf.prov/inline
     :image  (:rf.provenance/image descriptor)
     :inline (:rf.provenance/inline descriptor)}

    (:rf.provenance/ns descriptor)
    {:kind :rf.prov/ns :ns (:rf.provenance/ns descriptor)}

    :else
    {:kind :rf.prov/unknown}))

(defn project-generation
  "Project a sealed image GENERATION into the image-view's image-row shape —
  an image presented as its set of `[kind id]` descriptors (EP-0023 §Image —
  \"which registrations are visible to this frame?\"). Returns

      {:images          [<:rf.image/id> …]   ;; the image ids composed (nil entry
                                              ;; for an anonymous image)
       :requires        #{:rf.capability/* …};; the union :rf.gen/requires
       :kinds           [<kind> …]            ;; sorted kinds present
       :descriptor-count <int>                ;; total [kind id] entries resolved
       :descriptors     [{:kind … :id … :provenance {…}} …]}
                                              ;; one row per resolved [kind id],
                                              ;; sorted by (kind id) str

  `generation` is the inert sealed value `assemble` returns
  (`:rf.gen/resolver` / `:rf.gen/images` / `:rf.gen/requires` /
  `:rf.gen/kinds`). A nil generation projects to the empty image-row (no
  descriptors). Pure `data -> data`; JVM-testable."
  [generation]
  (let [resolver (:rf.gen/resolver generation)
        descs    (->> resolver
                      (sort-by (fn [[[kind id] _]] (str kind " " id)))
                      (mapv (fn [[[kind id] descriptor]]
                              {:kind       kind
                               :id         id
                               :provenance (descriptor-provenance descriptor)})))]
    {:images           (mapv :rf.image/id (:rf.gen/images generation))
     :requires         (set (:rf.gen/requires generation))
     :kinds            (vec (sort-by str (:rf.gen/kinds generation)))
     :descriptor-count (count resolver)
     :descriptors      descs}))

;; ===========================================================================
;; Frame projection — a frame as an execution context (EP-0023 §Frame)
;; ===========================================================================

(defn project-frame-row
  "Project ONE live frame OBJECT into the image-view's frame-row shape — a
  frame presented as an EXECUTION CONTEXT that POINTS AT its generation
  (EP-0023 §Frame). Returns

      {:frame-id     <:rf.frame/id> | nil    ;; nil for a direct (no-id) frame
       :anonymous?   <bool>                  ;; true iff no public frame id
       :has-adapter? <bool>                  ;; carries an active-substrate binding?
       :capabilities #{:rf.capability/* …}   ;; the host capability map's keys
       :image        {<project-generation> …}};; the resolved image this frame runs

  `frame-id` is the registry key (or nil for a direct frame object);
  `frame-object` is the inert map `make-frame` returns
  (`:rf.frame/object` / `:rf.frame/generation` / `:rf.frame/id` / …). The
  frame's IMAGE is its resolved generation projected via `project-generation`
  — that is the `frame -> resolved image generation` half of the EP-0023
  resolution path. Pure `data -> data`; JVM-testable."
  [frame-id frame-object]
  (let [gen (:rf.frame/generation frame-object)]
    {:frame-id     frame-id
     :anonymous?   (nil? frame-id)
     :has-adapter? (boolean (:rf.frame/adapter frame-object))
     :capabilities (set (keys (:rf.frame/capabilities frame-object)))
     :image        (project-generation gen)}))

(defn project-frames
  "Project the live-frame registry `{frame-id frame-object}` into sorted
  frame-rows (EP-0023 §Frame / §Id Spaces — the process-local live-frame
  registry). Returns a vector of frame-rows sorted by frame-id str. Only
  `:id`-bearing frames are in the registry; direct (no-id) frame objects
  bypass it (EP-0023 §Public API — \"the absence of `:id` is not a default-id
  path\"). Pure `data -> data`; JVM-testable."
  [live-frames]
  (->> live-frames
       (sort-by (comp str key))
       (mapv (fn [[fid fobj]] (project-frame-row fid fobj)))))

;; ===========================================================================
;; Frame-derived resolution — the lookup path (EP-0023 §Specification)
;; ===========================================================================

(defn resolve-in-frame
  "Project the FRAME-DERIVED RESOLUTION of one `[kind id]` through `frame`'s
  generation (EP-0023 §Specification — `target frame -> resolved image
  generation -> registration resolution`). `resolve-fn` is the
  generation-level resolver (`re-frame.image-assembly/resolve-descriptor`,
  passed in so this stays pure). Returns

      {:kind kind :id id
       :resolved? <bool>                    ;; did the frame's generation resolve it?
       :provenance {…}}                     ;; the resolving descriptor's provenance,
                                            ;; nil when unresolved

  This is what makes the image/frame split visible: the SAME `[kind id]`
  resolves to DIFFERENT descriptors (or to nothing) depending on which frame
  asks, because each frame runs its own image generation. Pure `data -> data`
  (given a pure `resolve-fn`); JVM-testable."
  [resolve-fn frame kind id]
  (let [gen        (:rf.frame/generation frame)
        descriptor (when gen (resolve-fn gen kind id))]
    {:kind       kind
     :id         id
     :resolved?  (some? descriptor)
     :provenance (when descriptor (descriptor-provenance descriptor))}))

;; ===========================================================================
;; Top-level projection
;; ===========================================================================

(defn project-image-view
  "Top-level EP-0023 image/frame projection — produce every slot the
  image/frame sections of the Module-view tab need (rf2-32siq3.12). Pure
  `data -> data`; JVM-testable.

  `live-frames` is the live-frame registry snapshot `{frame-id frame-object}`
  (from `re-frame.live-frame/live-frames`, read in `image_view_reads.cljs`).
  Returns

      {:frames        [<frame-row> …]   ;; per live frame, each carrying its
                                        ;; resolved image (its generation's
                                        ;; [kind id] descriptors)
       :frame-count   <int>
       :images?       <bool>}           ;; true iff ANY live frame runs a
                                        ;; generation — i.e. the EP-0023 public
                                        ;; image/frame model is in use. Drives
                                        ;; whether the image/frame sections
                                        ;; materialise (demand-gated, like the
                                        ;; realm dimension).

  A process that never calls `rf/make-frame` with `:images` has an empty
  registry → `:images?` false → the sections render the calm no-image
  caption. EP-0023's public model is OPT-IN over the retained EP-0013
  substrate, so this is the honest \"not using the image/frame model yet\"
  state, not a broken surface."
  [live-frames]
  (let [frames (project-frames live-frames)]
    {:frames      frames
     :frame-count (count frames)
     :images?     (boolean (some (comp pos? :descriptor-count :image) frames))}))

;; ===========================================================================
;; Display strings
;; ===========================================================================

(defn provenance-summary
  "A compact one-line rendering of a descriptor's provenance (from
  `descriptor-provenance`): the source namespace, the inline coordinate, or
  the standard/unknown marker. Pure `data -> string`; JVM-testable."
  [{:keys [kind ns image inline] :as _provenance}]
  (case kind
    :rf.prov/ns       (str ns)
    :rf.prov/inline   (str "inline " (pr-str image) " " (pr-str inline))
    :rf.prov/standard "framework standard"
    "—"))

(defn image-row-summary
  "A one-line plain-language summary for an image (a frame's resolved
  generation): `N descriptors · K kinds`. Pure `data -> string`;
  JVM-testable."
  [{:keys [descriptor-count kinds] :as _image}]
  (str descriptor-count " " (common/pluralize descriptor-count "descriptor")
       " · " (count kinds) " " (common/pluralize (count kinds) "kind")))

(def no-images-caption
  "The calm empty-state caption the image/frame sections render when NO live
  frame runs a resolved image generation (rf2-32siq3.12). EP-0023's public
  `image -> frame -> event stream` model is OPT-IN: a process using only the
  retained EP-0013 realm substrate (or the bare `reg-*` default path) creates
  no `rf/make-frame` image-loaded frames, so there is no image/frame model to
  show here yet. Names why the section is empty so the operator understands
  it is the honest not-using-images state, not a broken surface. Pure
  `data -> string`."
  (str "No image-loaded frames — this process is not using the EP-0023 "
       "image/frame model yet. Construct an image (rf/image {:include-ns [...]}) "
       "and load it into a frame (rf/make-frame {:images [...]}) to surface the "
       "resolved registration set (the [kind id] descriptors a frame sees) and "
       "each frame's execution context here. The image/frame model runs beside "
       "the retained EP-0013 realm substrate shown above."))
