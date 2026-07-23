(ns re-frame.freehand.root
  "The interpreted client mount surface — `v/mount`, the minimal Root
  Descriptor, and HMR-stable root identity.

  This is the browser half of getting a Freehand tree onto a page. Its
  structural counterpart on the JVM is [[re-frame.freehand.tree/render]],
  which the same root form drives unchanged: `[app {…}]` mounts to real
  DOM here and answers a structural tree there, so the minimal one-root
  spelling is one spelling on both hosts.

  ## The minimal Root Descriptor

  A mount derives a **Root Descriptor** — the small, static value that
  names WHAT was mounted and gives the root its identity. The minimal
  single-root descriptor carries just enough to key idempotent re-mount
  and to be read back by a tool:

      {:rf.root/schema-version 1
       :root-id            :app.shop/app   ; derived from the mounted view
       :view-id            :app.shop/app
       :root-id-provenance :derived}

  Its identity — the `:root-id` — is **derived from the mounted view's
  registered id** (Spec 004C §1.2): the minimal spelling authors nothing
  and the single-root page needs no disambiguator. The richer descriptor
  surface (frame plans, authored `:root-id`, the manifest extension) lands
  with later slices; this slice pins only the derived single-root spelling.

  ## HMR identity — occurrence identity is the qualified view id

  A root's occurrence identity is its `:root-id`, and a `:root-id` is the
  view's QUALIFIED id — a namespaced keyword that a redefinition does not
  move. A declared view is a descriptor VALUE, so a hot reload mints a new
  descriptor object; but the value it holds carries the same `:view-id`,
  so the derived `:root-id` is unchanged. The body/generation churn is an
  internal fact of the descriptor object, never part of the identity.

  So `mount` is **idempotent per root** (Spec 004C §3): re-mounting the
  same root-id into the same container RE-RENDERS the existing host root
  rather than allocating a second one. A second `createRoot` on one
  container is a React error and would tear the whole tree down and
  re-seed it; the registry here is what turns the guide-01 reload path —
  re-run the mount, find the root live, re-render — into the default. The
  reloaded body renders; the host root, and everything downstream that
  hangs off it, is not reseeded.

  ## Not this slice

  Frame preflight, authored/`:disambiguator` identity, multi-root
  duplicate detection, failed-root isolation, total teardown and
  hydration are all later (Spec 004C §7, EP-0036 F5e); a bare declared
  view at the root head is the whole grammar here. Frame binding is F2a.

  INTERNAL. `v/mount` is the authoring surface over [[mount]]; nothing
  else here is application API.

  Normative owner:
  [`spec/004C-Roots-and-Mount.md`](../../../../../spec/004C-Roots-and-Mount.md)."
  (:require ["react-dom/client" :as rdc]
            [re-frame.error :as error]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.react :as fr]))

;; ---------------------------------------------------------------------------
;; The Root value
;; ---------------------------------------------------------------------------
;;
;; A small host handle, deliberately its OWN type rather than a map: a map
;; at the root head is exactly the shape a mistaken caller might pass to
;; `mount`, and a distinct type keeps a live host root from being confused
;; with a descriptor or a props map. It carries the static Root Descriptor
;; (the identity the registry keys on) and the two host handles a re-render
;; and a later teardown need. The react-dom root is reached with interop
;; from within the artefact; there is no public accessor because reading it
;; is not an application concern.

(deftype Root [descriptor container react-root])

(defn root?
  "True when `x` is a live [[mount]] handle."
  [x]
  (instance? Root x))

(defn root-descriptor
  "The static Root Descriptor `root` was mounted under — the minimal
  `:rf.root/*` identity value (see the namespace docstring)."
  [^Root root]
  (.-descriptor root))

;; ---------------------------------------------------------------------------
;; Root identity — derived from the mounted view
;; ---------------------------------------------------------------------------

(defn- mounted-view
  "The single declared view at `root-form`'s head — the derivation source
  for the root's identity (Spec 004C §1.3, the minimal single-root case).

  The minimal grammar is a bare declared view at the head: `[app {…}]`.
  Anything else — a bare DOM element, a fragment, a runtime value — has no
  single mounted view to derive a `:root-id` from, and rather than guess
  one this fails loud, naming the one-root spelling. The richer top-region
  walk that admits wrapper forms lands with the multi-root slice."
  [root-form]
  (let [head (and (vector? root-form) (first root-form))]
    (if (descriptor/view? head)
      head
      (error/throw-error!
        :rf.error/ui-tree-malformed
        'v/mount
        (str "v/mount takes a declared view at the root — write "
             "(v/mount [the-view {…}] dom-node), where the-view is a "
             "v/defview. The minimal one-root spelling derives the root's "
             "identity from that view's registered id, so a bare element, a "
             "fragment or a runtime value has no single mounted view to "
             "derive it from; richer root forms land with the multi-root "
             "slice.")
        {:recovery :no-recovery
         :extra    {:value (error/diag-value-summary head)}}))))

(defn descriptor-for
  "The minimal Root Descriptor for `root-form` (Spec 004C §2). Every field
  is a static fact of the mount site: the `:root-id` and `:view-id` are the
  mounted view's registered id, and the provenance records that the id was
  derived rather than authored."
  [root-form]
  (let [view-id (:view-id (descriptor/describe (mounted-view root-form)))]
    {:rf.root/schema-version 1
     :root-id                view-id
     :view-id                view-id
     :root-id-provenance     :derived}))

;; ---------------------------------------------------------------------------
;; The live-root registry — what makes re-mount idempotent
;; ---------------------------------------------------------------------------
;;
;; `defonce`, so a namespace reload hands the reloaded code the SAME
;; registry a live root was registered in — the reload re-runs `mount` and
;; must find that root, not a fresh empty map. Keyed by `:root-id`, which is
;; the qualified view id and therefore stable across the redefinition.

(defonce ^:private live-roots (atom {}))

(defn ^:no-doc reset-registry!
  "Drop every registry entry WITHOUT tearing its host root down — a
  test-isolation seam only. Total teardown is a later slice; a suite that
  mounts under an id another suite also uses calls this between runs so a
  stale entry cannot masquerade as a live root."
  []
  (reset! live-roots {})
  nil)

;; ---------------------------------------------------------------------------
;; mount
;; ---------------------------------------------------------------------------

(defn mount
  "Mount the declared view at `root-form`'s head into `dom-node`, and return
  the [[Root]] host handle.

      (v/mount [app {}] (js/document.getElementById \"app\"))

  IDEMPOTENT PER ROOT (Spec 004C §3): re-mounting the same `:root-id` into
  the same container re-renders the existing host root instead of allocating
  a second one. That is the reload path — a hot reload re-runs the mount,
  finds the root live under its qualified-view-id identity, and re-renders
  the new body without reseeding the host root.

  The minimal one-root spelling is a bare declared view at the head; `opts`
  is accepted and ignored at this slice (authored identity, frame plans and
  the host error callbacks land later, Spec 004C §3, §6, §7)."
  ([root-form dom-node] (mount root-form dom-node {}))
  ([root-form dom-node _opts]
   (let [descriptor (descriptor-for root-form)
         root-id    (:root-id descriptor)
         element    (fr/element root-form)
         existing   (get @live-roots root-id)]
     (if (and (some? existing) (identical? dom-node (.-container ^Root existing)))
       ;; The reload path: the same host root, re-rendered. No createRoot,
       ;; so nothing downstream is reseeded; the descriptor snapshot is
       ;; refreshed because the mounted view object may be a fresh
       ;; generation, but the identity it keys on is unchanged.
       (let [react-root (.-react-root ^Root existing)
             root       (->Root descriptor dom-node react-root)]
         (.render react-root element)
         (swap! live-roots assoc root-id root)
         root)
       (let [react-root (rdc/createRoot dom-node)
             root       (->Root descriptor dom-node react-root)]
         (.render react-root element)
         (swap! live-roots assoc root-id root)
         root)))))
