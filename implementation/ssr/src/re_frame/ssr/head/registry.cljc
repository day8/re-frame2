(ns re-frame.ssr.head.registry
  "Head/meta registry, rendering, and defaults.

  Public surface (re-exported from the `re-frame.ssr.head` façade, and
  from `re-frame.ssr` for the read):

    `reg-head`          — register a head-fragment producer
                          `(fn [db route] head-model)` keyed by id.
    `head-model`        — THE head read. Resolve a frame's head model,
                          optionally overriding the head id and/or the
                          route it is evaluated against.
    `default-head`      — fallback head-model per Spec 011 §Default head.

  Reading a head is a pure read: the model a caller wants is the value
  `head-model` returned. There is no side-channel register, so there is
  nothing to clear on frame teardown.

  ## The frame target selects the REGISTRATIONS too (rf2-blpg)

  `head-model` is an explicit-target read — the frame is carried, and it
  is called OUTSIDE any `with-frame` binding (the public head query, a
  host that renders a head for a frame it is not running in, a preview
  inside frame A targeting frame B). It reads the target frame's app-db
  and route slice by id, which needs no ambient scope; but the `:head`
  and `:route` lookups are ordinary `(kind, id)` resolutions, and those
  route through `re-frame.registrar/*generation*` — which nothing had
  bound here.

  So a head id registered from two namespaces, with two frames selecting
  one each (the image-isolation case
  `re-frame.source-store/descriptors-for` exists to preserve), resolved to
  whichever registration reached the registrar ATOM last — and then ran
  that other image's body against the REQUESTED frame's app-db. Silently:
  a plausible head model for the wrong page.

  Every read that participates in producing the model therefore runs
  inside ONE `re-frame.live-frame/call-with-frame-resolution` extent on
  the target frame — route metadata selection, head lookup, and the head
  fn's own invocation, so a coherent generation covers the whole read
  rather than half of it. A target that names no image-loaded frame binds
  nothing and takes the registrar-atom path exactly as before, so this
  changes nothing for the ordinary single-image / default-image
  application."
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
  `[:rf.runtime/routing :current]` (or whatever the caller passed as
  `head-model`'s `:route`).

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


;; ---- head-model -----------------------------------------------------------

(defn- frame-current-route
  "Read the route slice from a frame's RUNTIME-DB at
  `[:rf.runtime/routing :current]`. Nil-safe: a frame whose runtime-db has
  never been written resolves to nil."
  [frame-id]
  (when frame-id
    (let [runtime-db (rf.frame/frame-runtime-db-value frame-id)]
      (when runtime-db
        (get-in runtime-db [:rf.runtime/routing :current])))))

(defn- route-head-id
  "Read the `:head` route-metadata key for the route-id named in `route`
  (the effective route — see `head-model`). Returns nil when there's no
  route, no route registration, or no `:head` declared on the route.

  Contract — the slice's `(:route-id route)` IS the canonical registrar
  key under the `:route` kind. If the runtime ever introduces an
  indirection between the slice id and the registry key (route aliases,
  versioned routes, ...), this fn breaks and must learn the new mapping
  (audit rf2-asmj1 H6 / cluster rf2-sljs1).

  The `:route` lookup is an ordinary generation-routed resolution, and
  `head-model` calls this INSIDE the target frame's resolution extent
  (rf2-blpg) so the route metadata and the head it names come from the
  same image as the app-db they are read against."
  [route]
  (when-let [route-id (:route-id route)]
    (when-let [route-meta (rf.registrar/lookup :route route-id)]
      (:head route-meta))))

(defn- registered-head-model
  "Run the head fn registered under `head-id` against `frame-id`'s app-db
  and `route`, returning the produced model. Raises
  `:rf.error/no-such-head` when nothing is registered under `head-id`.

  Assumes the caller has already established the target frame's
  resolution extent — `head-model` is the only caller and does exactly
  that, so the `:head` lookup and the head fn's own body run in one
  generation (rf2-blpg)."
  [frame-id head-id route]
  (let [head-registration (rf.registrar/lookup :head head-id)]
    (when-not head-registration
      (rf.error/throw-error!
        :rf.error/no-such-head
        're-frame.ssr/head-model
        (str "No head registered under " head-id
             " for frame " frame-id
             "; register it with reg-head before rendering, pass a "
             "head-id that has been registered, or — if it is "
             "registered elsewhere — check that this frame's image "
             "selects the namespace it was registered from.")
        {:recovery :register-the-head-id
         :extra    {:head-id head-id :frame frame-id}}))
    ;; `frame-id` is a required non-nil stamp here (`require-frame-stamp!`
    ;; in `head-model`), so the app-db read is unconditional.
    ((:handler-fn head-registration)
     (rf.frame/frame-app-db-value frame-id)
     route)))

(defn head-model
  "THE head read — resolve `frame-id`'s `:rf/head-model` and return it.

    (head-model frame-id)
    (head-model frame-id {:head-id :head/article})
    (head-model frame-id {:route {:route-id :route/article :params {:id \"1\"}}})

  One read answers the whole question, and it answers it in one pass
  (rf2-kuky.44 / rf2-kuky.89 — this replaced a pair of reads whose second
  argument dispatched on its own type):

    1. `frame-id` is CARRIED, never ambient (EP-0002). A nil stamp emits
       + throws `:rf.error/no-frame-context` rather than resolving
       against a synthesised default frame. Per Spec 002 §Frame target
       resolution.
    2. The EFFECTIVE ROUTE is resolved once — `:route` in `opts` when the
       key is present (an explicit `{:route nil}` means \"no route\"),
       otherwise the frame's runtime-db route slice at
       `[:rf.runtime/routing :current]`.
    3. The HEAD is selected: `:head-id` in `opts` if supplied; else the
       effective route's `:head` metadata if it declares one; else
       `default-head`. A selected id that is not registered raises
       `:rf.error/no-such-head` — a route that declares no `:head` at all
       falls back silently, per Spec 011 §Default head.
    4. The head fn is evaluated against the SAME effective route, so
       `(head-model f {:route r})` selects `r`'s head AND runs it against
       `r` — a hypothetical-route preview is coherent end to end.

  The produced model is the RETURN VALUE and is recorded nowhere: this is
  a pure read with no side channel, no HTML mode and no cache. Pair it
  with `head-model->html` to emit the fragment.

  Per Spec 011 §`head-model`."
  ([frame-id] (head-model frame-id nil))
  ([frame-id opts]
   (let [frame-id (rf.frame/require-frame-stamp!
                    frame-id :rf.ssr/head-model
                    (cond-> {:where 're-frame.ssr/head-model}
                      (:head-id opts) (assoc :event-id (:head-id opts))))]
     ;; ONE resolution extent over the whole read (rf2-blpg): the route
     ;; registration that NAMES the head, the head registration itself and
     ;; the head fn's body must come from the same image, or a
     ;; route-declared head resolves in one generation and executes in
     ;; another. A frame with no sealed generation binds nothing — the
     ;; unchanged registrar-atom path.
     (rf.live-frame/call-with-frame-resolution
       frame-id
       (fn []
         (let [route   (if (contains? opts :route)
                         (:route opts)
                         (frame-current-route frame-id))
               ;; `:head-id` takes an id or nothing — unlike `:route`,
               ;; nil is not a meaningful value for it, so `or` rather
               ;; than `contains?`.
               head-id (or (:head-id opts) (route-head-id route))]
           (if head-id
             (registered-head-model frame-id head-id route)
             (default-head frame-id))))))))
