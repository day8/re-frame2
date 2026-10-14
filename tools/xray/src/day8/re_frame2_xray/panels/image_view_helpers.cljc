(ns day8.re-frame2-xray.panels.image-view-helpers
  "Pure-data helpers for Xray's EP-0023 IMAGE / FRAME inspection — the
  `image -> frame -> event stream` public model.

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

  ## What this powers — the Module-view tab

  This helper powers the Module-view tab's EP-0023 PUBLIC model: the
  image/frame projection reads the EP-0023 live-frame registry + sealed
  generations, and the frames made without `:images`, which resolve the
  registration store live. It is fail-soft and demand-gated — a process with
  no frame to show renders the calm empty caption.

  ## Inert-data, fail-soft, JVM-testable

  Every fn here is pure `data -> data`. The live reads (the EP-0023
  registry/generation touches) live in `image_view_reads.cljc` and are
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

      {:kind :ns        :ns \"docs.counter.v2\"}
      {:kind :inline    :image :test/small :inline [:reg-event :counter/inc]}
      {:kind :standard}
      {:kind :unknown}

  The three provenance slots are MUTUALLY EXCLUSIVE on a well-formed
  descriptor, so this `cond`'s branch order tracks core's
  `re-frame.image-assembly/descriptor-coordinate` (the ground-truth
  source-coordinate fn) — `:standard` first, then the inline image coordinate,
  then the registered `:rf.provenance/ns` — rather than introducing a different
  precedence the projection would have to defend. (`descriptor-coordinate`'s
  final `:else` is the registered-ns case; here that leg is explicit so an
  unrecognized descriptor falls through to `:unknown` instead of
  `{:ns nil}`.) Pure `data -> data`; JVM-testable."
  [descriptor]
  (cond
    (:standard descriptor)
    {:kind :standard}

    (:rf.provenance/image descriptor)
    {:kind   :inline
     :image  (:rf.provenance/image descriptor)
     :inline (:rf.provenance/inline descriptor)}

    (:rf.provenance/ns descriptor)
    {:kind :ns :ns (:rf.provenance/ns descriptor)}

    :else
    {:kind :unknown}))

(defn project-generation
  "Project a sealed image GENERATION into the image-view's image-row shape —
  an image presented as its set of `[kind id]` descriptors (EP-0023 §Image —
  \"which registrations are visible to this frame?\"). Returns

      {:images          [<:rf.image/id> …]   ;; the image ids composed (nil entry
                                              ;; for an anonymous image)
       :kinds           [<kind> …]            ;; sorted kinds present
       :descriptor-count <int>                ;; total [kind id] entries resolved
       :descriptors     [{:kind … :id … :provenance {…}} …]}
                                              ;; one row per resolved [kind id],
                                              ;; sorted by (kind id) str

  `generation` is the inert sealed value `assemble` returns
  (`:rf.gen/resolver` / `:rf.gen/images` / `:rf.gen/kinds`). A nil generation
  projects to the empty image-row (no descriptors). Pure `data -> data`;
  JVM-testable. A generation carries no image-declared host capabilities
  (EP-0026), so the projection has no `:requires` set."
  [generation]
  (let [resolver (:rf.gen/resolver generation)
        descs    (->> resolver
                      (sort-by (fn [[[kind id] _]] (str kind " " id)))
                      (mapv (fn [[[kind id] descriptor]]
                              {:kind       kind
                               :id         id
                               :provenance (descriptor-provenance descriptor)})))]
    {:images           (mapv :rf.image/id (:rf.gen/images generation))
     :kinds            (vec (sort-by str (:rf.gen/kinds generation)))
     :descriptor-count (count resolver)
     :descriptors      descs}))

(defn project-live-store-image
  "Project the registrations a frame made without `:images` resolves LIVE from
  the registration store (`{[kind id] descriptor}`) into the image-row shape
  `project-generation` returns — with no image ids, since the frame composes
  none — plus `:ambiguous`: one `{:kind :id :namespaces}` entry per id the
  frame refuses because more than one namespace registers it, sorted like the
  descriptors. Pure `data -> data`; JVM-testable."
  [registrations ambiguous]
  (assoc (project-generation {:rf.gen/resolver registrations
                              :rf.gen/kinds    (into #{} (map first) (keys registrations))})
         :ambiguous (vec (sort-by (fn [{:keys [kind id]}] (str kind " " id)) ambiguous))))

;; ===========================================================================
;; Frame projection — a frame as an execution context (EP-0023 §Frame)
;; ===========================================================================

(defn project-frame-row
  "Project ONE live frame OBJECT into the image-view's frame-row shape — a
  frame presented as an EXECUTION CONTEXT that POINTS AT what it resolves
  registrations through (EP-0023 §Frame). Returns

      {:frame-id     <:rf.frame/id> | nil    ;; nil for a direct (no-id) frame
       :anonymous?   <bool>                  ;; true iff no public frame id
       :has-adapter? <bool>                  ;; carries an active-substrate binding?
       :live-store?  <bool>                  ;; resolves the store live (no :images)?
       :image        {<project-generation> …}};; the registrations this frame runs

  `frame-id` is the registry key; `frame-object` is the inert FRAME VIEW the
  read seam supplies. An image-loaded frame's view carries its sealed
  `:rf.frame/generation` (the `image-view-frames` projection, EP-0024), and
  its IMAGE is that generation projected via `project-generation` — the
  `frame -> resolved image generation` half of the EP-0023 resolution path.
  A frame made without `:images` carries `:rf.frame/live-store? true`, its
  `:rf.frame/registrations` and its `:rf.frame/ambiguous` ids instead, and its
  IMAGE is `project-live-store-image` over them. Pure `data -> data`;
  JVM-testable. Frame views carry no image capabilities (EP-0026), so the row
  has no `:capabilities`."
  [frame-id frame-object]
  (let [live-store? (true? (:rf.frame/live-store? frame-object))]
    {:frame-id     frame-id
     :anonymous?   (nil? frame-id)
     :has-adapter? (boolean (:rf.frame/adapter frame-object))
     :live-store?  live-store?
     :image        (if live-store?
                     (project-live-store-image (:rf.frame/registrations frame-object)
                                               (:rf.frame/ambiguous frame-object))
                     (project-generation (:rf.frame/generation frame-object)))}))

(defn project-frames
  "Project the frames `{frame-id frame-view}` — image-loaded and live-store
  alike — into sorted frame-rows. Returns a vector of frame-rows sorted by
  frame-id str. Only PUBLIC `:id`-bearing frames are enumerated; direct
  (no-id) frames bypass enumeration (their private `:rf.frame/<gensym>` id is
  excluded by the read seam). Pure `data -> data`; JVM-testable."
  [live-frames]
  (->> live-frames
       (sort-by (comp str key))
       (mapv (fn [[fid fobj]] (project-frame-row fid fobj)))))

;; ===========================================================================
;; Top-level projection
;; ===========================================================================

(defn project-image-view
  "Top-level EP-0023 image/frame projection — produce every slot the
  image/frame sections of the Module-view tab need. Pure
  `data -> data`; JVM-testable.

  `live-frames` is the frames snapshot `{frame-id frame-view}` read in
  `image_view_reads.cljc`: each image-loaded frame (EP-0024 §One live frame
  registry — the seam projects each one-registry record carrying a generation
  into an inert frame view), and each frame made without `:images`, which
  resolves the registration store live. Returns

      {:frames        [<frame-row> …]   ;; per frame, each carrying the
                                        ;; registrations it resolves
       :frame-count   <int>
       :images?       <bool>}           ;; true iff ANY frame has something to
                                        ;; show: it resolves the store live, or
                                        ;; its generation resolves descriptors.
                                        ;; Drives whether the frames section
                                        ;; materialises (demand-gated).

  A process with no such frame → `:images?` false → the section renders the
  calm empty caption, the honest \"no frame yet\" state, not a broken
  surface."
  [live-frames]
  (let [frames (project-frames live-frames)]
    {:frames      frames
     :frame-count (count frames)
     :images?     (boolean (some (fn [{:keys [live-store? image]}]
                                   (or live-store? (pos? (:descriptor-count image))))
                                 frames))}))

;; ===========================================================================
;; Display strings
;; ===========================================================================

(defn provenance-summary
  "A compact one-line rendering of a descriptor's provenance (from
  `descriptor-provenance`): the source namespace, the inline coordinate, or
  the standard/unknown marker. Pure `data -> string`; JVM-testable."
  [{:keys [kind ns image inline] :as _provenance}]
  (case kind
    :ns       (str ns)
    :inline   (str "inline " (pr-str image) " " (pr-str inline))
    :standard "framework standard"
    "—"))

(defn image-row-summary
  "A one-line plain-language summary for an image (a frame's resolved
  generation): `N descriptors · K kinds`. Pure `data -> string`;
  JVM-testable."
  [{:keys [descriptor-count kinds] :as _image}]
  (str descriptor-count " " (common/pluralize descriptor-count "descriptor")
       " · " (count kinds) " " (common/pluralize (count kinds) "kind")))

(defn ambiguous-summary
  "The one-line rendering of an ambiguous id's namespaces (an `:ambiguous`
  entry's `:namespaces`): the frame runs neither registration until one is
  cleared. Pure `data -> string`; JVM-testable."
  [namespaces]
  (str "ambiguous — " (str/join " " namespaces)))

(def no-images-caption
  "The calm empty-state caption the frames section renders when there is NO
  frame to show: no frame made with `rf/make-frame`, with or without
  `:images`. Names why the section is empty so the operator understands it is
  the honest no-frame-yet state, not a broken surface. Pure
  `data -> string`."
  (str "No frames yet. Make one with rf/make-frame to see the registrations it "
       "resolves here: the live registration store for a frame made without "
       ":images, or its image's [kind id] descriptors for a frame made with "
       ":images [...]."))
