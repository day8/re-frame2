(ns re-frame.ssr.head.registry
  "Head/meta registry, rendering, and defaults.

  Public surface (re-exported from the `re-frame.ssr.head` façade):

    `reg-head`          — register a head-fragment producer
                          `(fn [db route] head-model)` keyed by id.
    `render-head`       — invoke a registered head fn against a frame's
                          app-db + active route and return the produced
                          model.
    `active-head`       — sugar — look up the active route's `:head`
                          metadata; render or fall back to `default-head`.
    `default-head`      — fallback head-model per Spec 011 §Default head.

  Reading a head is a pure read: the model a caller wants is the value
  `render-head` / `active-head` returned. There is no side-channel
  register, so there is nothing to clear on frame teardown.

  ## The frame target selects the REGISTRATIONS too (rf2-blpg)

  `render-head` and `active-head` are explicit-target reads — the frame is
  carried, and they are called OUTSIDE any `with-frame` binding (the
  public head query, a host that renders a head for a frame it is not
  running in, a preview inside frame A targeting frame B). They read the
  target frame's app-db and route slice by id, which needs no ambient
  scope; but the `:head` and `:route` lookups are ordinary
  `(kind, id)` resolutions, and those route through
  `re-frame.registrar/*generation*` — which nothing had bound here.

  So a head id registered from two namespaces, with two frames selecting
  one each (the image-isolation case
  `re-frame.source-store/descriptors-for` exists to preserve), resolved to
  whichever registration reached the registrar ATOM last — and then ran
  that other image's body against the REQUESTED frame's app-db. Silently:
  a plausible head model for the wrong page.

  Every read that participates in producing the model therefore runs
  inside `re-frame.live-frame/call-with-frame-resolution` on the target
  frame — route metadata selection, head lookup, and the head fn's own
  invocation, so a coherent generation covers the whole read rather than
  half of it. A target that names no image-loaded frame binds nothing and
  takes the registrar-atom path exactly as before, so this changes nothing
  for the ordinary single-image / default-image application."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-coords :as rf.source-coords]))

;; ---- reg-head -------------------------------------------------------------

(defn reg-head
  "Register a head-fragment producer under `id` (a namespaced keyword
  such as `:my.app/article` or `:rf.ssr/title`).

  `head-fn` signature is `(fn [db route] head-model)` — pure,
  deterministic, no side-effects. Same shape and discipline as a sub.
  `db` is the frame's app-db value (plain map, deref'd through the
  substrate adapter); `route` is the route slice from
  `[:rf.runtime/routing :current]` (or whatever the caller passed to
  `render-head`).

  Two arities:

    (reg-head id           head-fn)
    (reg-head id metadata  head-fn)

  Returns `id` per the family-wide `reg-*` return-value convention
  (Conventions.md §`reg-*` return-value convention).

  Re-registering an existing id replaces the slot atomically. Per
  Spec 011 §Mechanism — registered head function + route metadata."
  ([id head-fn]
   (reg-head id {} head-fn))
  ([id metadata head-fn]
   (rf.registrar/register! :head id
                        (assoc (rf.source-coords/merge-coords metadata)
                               :handler-fn head-fn))
   id))

;; ---- default head ---------------------------------------------------------

(defn default-head
  "The fallback head-model used when the active route does not declare
  `:head` (or there is no active route). Per Spec 011 §Default head.

  Always carries `:title` — defaulting to `\"\"` when the frame has no
  `:doc`. Empty-title and missing-title both emit no `<title>` tag (the
  emitter elides empty strings), but a programmatic consumer reading
  the model sees a stable key shape.

  Does not carry `{:charset \"utf-8\"}`. Charset is an
  envelope concern owned by the shell — the always-present document
  envelope (`re-frame.ssr.ring.shell/default-html-shell` and the
  streaming prefix) hardcodes `<meta charset=\"utf-8\">` as the first
  `<head>` byte. A route's `:head` declares page-specific metas; the
  baseline charset is not a per-route head concern. Carrying it here
  too produced two `<meta charset>` tags in the non-streaming default
  document."
  [frame-id]
  (let [doc (when frame-id (:doc (rf.frame/frame-meta frame-id)))]
    {:title (or doc "")
     :meta  [{:name "viewport" :content "width=device-width, initial-scale=1"}]}))

;; ---- render-head ----------------------------------------------------------

(defn- frame-current-route
  "Read the route slice from a frame's RUNTIME-DB at
  `[:rf.runtime/routing :current]`. Nil-safe: a frame whose runtime-db has
  never been written resolves to nil."
  [frame-id]
  (when frame-id
    (let [runtime-db (rf.frame/frame-runtime-db-value frame-id)]
      (when runtime-db
        (get-in runtime-db [:rf.runtime/routing :current])))))

(defn- render-head*
  "Resolve a normalised opts map and run the registered head fn. The
  caller-facing `render-head` carries the documented two-shape contract
  on its signature and delegates the work here.

  The head fn reads the frame's app-db and, unless
  `:route` is supplied, the frame's runtime-db route slice), so a frame is
  the carried target — an absent `:frame` stamp emits + throws
  `:rf.error/no-frame-context` (no `:rf/default`-against-absence
  rendering). Per Spec 002 §Frame target resolution."
  [head-id {:keys [frame] :as opts}]
  (let [frame (rf.frame/require-frame-stamp!
                frame :rf.ssr/render-head
                {:where 'head/render-head :event-id head-id})
        route (if (contains? opts :route)
                (:route opts)
                (frame-current-route frame))]
    ;; Resolve the `:head` registration THROUGH THE TARGET FRAME'S generation
    ;; (rf2-blpg). The explicit `:frame` selected the frame's DATA and not its
    ;; REGISTRATIONS, so a same-id head from another image ran against this
    ;; frame's app-db. The head fn's invocation is inside the extent too, for
    ;; the same reason `boot/hydrate!` calls `render-tree-fn` under the target
    ;; frame's scope: a model produced half in one generation and half in
    ;; another is not a model of either. A frame with no sealed generation
    ;; binds nothing — the unchanged registrar-atom path.
    (rf.live-frame/call-with-frame-resolution
      frame
      (fn []
        (let [head-registration (rf.registrar/lookup :head head-id)]
          (when-not head-registration
            (rf.error/throw-error!
              :rf.error/no-such-head
              'head/active-head
              (str "No head registered under " head-id
                   " for frame " frame
                   "; register it with reg-head before rendering, pass a "
                   "head-id that has been registered, or — if it is "
                   "registered elsewhere — check that this frame's image "
                   "selects the namespace it was registered from.")
              {:recovery :register-the-head-id
               :extra    {:head-id head-id :frame frame}}))
          (let [head-fn (:handler-fn head-registration)
                ;; `frame` is a required non-nil stamp here (require-frame-stamp!
                ;; above), so the app-db read is unconditional.
                app-db  (rf.frame/frame-app-db-value frame)]
            (head-fn app-db route)))))))

(defn render-head
  "Apply the head fn registered under `head-id` against a frame's
  app-db and active route, returning the produced `:rf/head-model`.

  The 2-arity form dispatches on its second argument's shape — a
  keyword is treated as a frame-id (shorthand for `{:frame keyword}`),
  a map carries the full `{:frame :route}` opts. Audit rf2-asmj1 H7 /
  cluster rf2-sljs1: the explicit dispatch lives at the documented
  surface (rather than in a deeper helper) so callers see the two
  shapes on the fn boundary:

    (render-head head-id frame-id)
    (render-head head-id {:frame frame-id :route route})

  When `:route` is absent, the active route slice (at
  `[:rf.runtime/routing :current]`) is read from the frame's runtime-db
  (via `frame-current-route` → `frame-runtime-db-value`; the head fn itself
  reads the frame's app-db for its model, but the route slice is a
  runtime-db read). The produced head model is the RETURN VALUE and is
  recorded nowhere — this is a pure read.

  Raises `:rf.error/no-such-head` when `head-id` is not registered.
  Per Spec 011 §`render-head`."
  [head-id opts-or-frame-id]
  (render-head* head-id
                (if (keyword? opts-or-frame-id)
                  {:frame opts-or-frame-id}
                  opts-or-frame-id)))

;; ---- active-head ----------------------------------------------------------

(defn- route-head-id
  "Read the `:head` route-metadata key for the route-id named in the
  active route slice at `[:rf.runtime/routing :current]`. Returns nil when there's no active route,
  no route registration, or no `:head` declared on the route.

  Contract — the slice's `(:route-id route)` IS the canonical registrar
  key under the `:route` kind. If the runtime ever introduces an
  indirection between the slice id and the registry key (route aliases,
  versioned routes, ...), this fn breaks and must learn the new mapping
  (audit rf2-asmj1 H6 / cluster rf2-sljs1).

  The `:route` lookup is an ordinary generation-routed resolution, and
  `active-head` calls this INSIDE the target frame's resolution extent
  (rf2-blpg) so the route metadata and the head it names come from the
  same image as the app-db they are read against."
  [route]
  (when-let [route-id (:route-id route)]
    (when-let [route-meta (rf.registrar/lookup :route route-id)]
      (:head route-meta))))

(defn active-head
  "Sugar — look up the active route's `:head` metadata; if set, call
  `render-head` and return the model. Otherwise return the `default-head`
  per Spec 011 §Default head.

    (active-head frame-id)   — explicit frame.

  EP-0002 (rf2-acjknb): head rendering is a frame-scoped read (it reads
  the frame's runtime-db route slice + app-db), so the frame target is
  CARRIED — supplied explicitly. The pre-EP no-arg form synthesised
  `:rf/default` from absence; that ambient floor is removed. A nil
  `frame-id` is an absent stamp — `require-frame-stamp!` emits + throws
  the always-on `:rf.error/no-frame-context` rather than rendering the
  head against a synthesised default frame. Per Spec 002 §Frame target
  resolution.

  Returns the resolved head-model. Per Spec 011 §`render-head`."
  [frame-id]
  (let [frame-id (rf.frame/require-frame-stamp!
                   frame-id :rf.ssr/active-head
                   {:where 'head/active-head})]
    ;; ONE resolution extent over the whole read (rf2-blpg): the route
    ;; registration that NAMES the head and the head registration itself must
    ;; come from the same image, or a route-declared head resolves in one
    ;; generation and executes in another. `render-head` re-establishes the
    ;; same binding inside — a no-op rebinding of the same generation, and
    ;; cheaper than a second entry point that assumes it is already bound.
    (rf.live-frame/call-with-frame-resolution
      frame-id
      (fn []
        (let [route   (frame-current-route frame-id)
              head-id (route-head-id route)]
          (if head-id
            ;; The route declares an id but it may not be registered — surface
            ;; that as :rf.error/no-such-head per Spec 011, but only when the
            ;; route explicitly opts in. Routes without :head silently fall
            ;; back to the default per Spec 011 §Default head.
            (render-head head-id {:frame frame-id :route route})
            (default-head frame-id)))))))
