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
  register, so there is nothing to clear on frame teardown."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
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
  (let [frame    (rf.frame/require-frame-stamp!
                   frame :rf.ssr/render-head
                   {:where 'rf/render-head :event-id head-id})
        route    (if (contains? opts :route)
                   (:route opts)
                   (frame-current-route frame))
        ;; Resolve the `:head` registration from the process registrar
        ;; (rf2-afdlyr realm collapse: the realm substrate is a single default
        ;; realm, whose registrar IS the process-global table, so the former
        ;; per-frame realm-registrar binding was always the no-op default path).
        head-registration (rf.registrar/lookup :head head-id)]
    (when-not head-registration
      (rf.error/throw-error!
        :rf.error/no-such-head
        'rf/active-head
        (str "No head registered under " head-id
             "; register it with reg-head before rendering, or pass a "
             "head-id that has been registered.")
        {:recovery :register-the-head-id
         :extra    {:head-id head-id}}))
    (let [head-fn (:handler-fn head-registration)
          ;; `frame` is a required non-nil stamp here (require-frame-stamp!
          ;; above), so the app-db read is unconditional.
          app-db     (rf.frame/frame-app-db-value frame)
          head-model (head-fn app-db route)]
      head-model)))

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

  Resolves the `:route` registration from the process registrar (rf2-afdlyr
  realm collapse: the single default realm's registrar IS the process-global
  table, so the former per-frame realm-registrar binding was always the no-op
  default path)."
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
                   {:where 'rf/active-head})
        route    (frame-current-route frame-id)
        head-id  (route-head-id route)]
    (if head-id
      ;; The route declares an id but it may not be registered — surface
      ;; that as :rf.error/no-such-head per Spec 011, but only when the
      ;; route explicitly opts in. Routes without :head silently fall
      ;; back to the default per Spec 011 §Default head.
      (render-head head-id {:frame frame-id :route route})
      (default-head frame-id))))
