(ns re-frame.ssr.head.registry
  "Head/meta contract — registry, render, active, default, per-frame
  snapshot. Per Spec 011 §Head/meta contract (rf2-4dra9) and the
  rf2-x7g10 split of `re-frame.ssr.head`.

  Public surface (re-exported from the `re-frame.ssr.head` façade):

    `reg-head`          — register a head-fragment producer
                          `(fn [db route] head-model)` keyed by id.
    `render-head`       — invoke a registered head fn against a frame's
                          app-db + active route, record the produced
                          model in the per-frame snapshot, return it.
    `active-head`       — sugar — look up the active route's `:head`
                          metadata; render or fall back to `default-head`.
    `default-head`      — fallback head-model per Spec 011 §Default head.
    `head-snapshot`     — read the per-frame `{head-id → head-model}`
                          snapshot.
    `head-snapshots`    — the side-channel atom (consumed by the
                          test-fixture reset).
    `on-frame-destroyed!` — clear the per-frame snapshot entry. Wired
                          into the `:ssr.head/on-frame-destroyed` hook
                          chained from `re-frame.ssr`'s teardown."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]))

;; ---- per-frame snapshot ---------------------------------------------------
;;
;; A side-channel atom keyed by frame-id, mapping `head-id → last-produced
;; head-model`. Cleared on per-request frame destroy via the
;; `:ssr/on-frame-destroyed` hook (rf2-fcj33). Storage shape mirrors the
;; pattern used by `re-frame.ssr/pending-error-traces` and
;; `re-frame.ssr/request-slots` — the data is per-request bookkeeping
;; that has no place in app-db (and must not ride the hydration payload
;; to the client).

(defonce
  ^{:doc "Per-frame head snapshot. Keys are frame-ids; values are
  `{head-id → head-model}` maps recording each render-head call's
  output. Cleared on frame destroy. Inspectable via `head-snapshot`."}
  head-snapshots
  (atom {}))

(defn- record-fragment!
  "Stash the just-produced head-model under ((realm, frame) address, head-id)
  so `head-snapshot` reflects the most recent render-head output. Keyed by the
  frame ADDRESS (rf2-bzw8gd) so the same frame id in two realms records
  independent head snapshots — `frame-address` collapses to the bare
  `frame-id` for the default realm (byte-identical single-realm path). The
  (realm, frame) address is the EP-0023 retained-INTERNAL routing seam
  (`re-frame.frame/frame-address`), not current public vocabulary — see
  `re-frame.realm`'s EP-0023 banner."
  [frame-id head-id head-model]
  (when frame-id
    (swap! head-snapshots assoc-in [(frame/frame-address frame-id) head-id]
           head-model))
  head-model)

(defn head-snapshot
  "Read the per-frame `{head-id → last-produced head-model}` snapshot.
  Useful for tests and introspection. Returns `{}` for a frame that has
  never seen a `render-head` call (or whose snapshot has been cleared
  via the per-request frame teardown hook). Keyed by the (realm, frame)
  ADDRESS (rf2-bzw8gd)."
  [frame-id]
  (get @head-snapshots (frame/frame-address frame-id) {}))

(defn on-frame-destroyed!
  "Clear the head-snapshot entry for `frame-id`. Wired into the
  `:ssr/on-frame-destroyed` late-bind hook chain so per-request frames
  release their head bookkeeping on destroy. Idempotent. Keyed by the
  (realm, frame) ADDRESS (rf2-bzw8gd) so clearing one realm's frame leaves
  another realm's same-id head snapshot intact."
  [frame-id]
  (swap! head-snapshots dissoc (frame/frame-address frame-id))
  nil)

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
   (registrar/register! :head id
                        (assoc (source-coords/merge-coords metadata)
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

  Does NOT carry `{:charset \"utf-8\"}` (rf2-q78s1). Charset is an
  envelope concern owned by the shell — the always-present document
  envelope (`re-frame.ssr.ring.shell/default-html-shell` and the
  streaming prefix) hardcodes `<meta charset=\"utf-8\">` as the first
  `<head>` byte. A route's `:head` declares page-specific metas; the
  baseline charset is not a per-route head concern. Carrying it here
  too produced two `<meta charset>` tags in the non-streaming default
  document."
  [frame-id]
  (let [doc (when frame-id (:doc (frame/frame-meta frame-id)))]
    {:title (or doc "")
     :meta  [{:name "viewport" :content "width=device-width, initial-scale=1"}]}))

;; ---- render-head ----------------------------------------------------------

(defn- frame-route
  "Read the route slice from a frame's RUNTIME-DB at
  `[:rf.runtime/routing :current]` (EP-0001 rf2-vzld77 — the route slice is
  durable routing runtime-db state). nil-safe — a frame whose runtime-db has
  never been written resolves to nil."
  [frame-id]
  (when frame-id
    (let [rt (frame/frame-runtime-db-value frame-id)]
      (when rt (get-in rt [:rf.runtime/routing :current])))))

(defn- render-head*
  "Resolve a normalised opts map and run the registered head fn. The
  caller-facing `render-head` carries the documented two-shape contract
  on its signature and delegates the work here.

  EP-0002 (rf2-acjknb): the head fn reads the frame's app-db (and, unless
  `:route` is supplied, the frame's runtime-db route slice), so a frame is
  the carried target — an absent `:frame` stamp emits + throws
  `:rf.error/no-frame-context` (no `:rf/default`-against-absence
  rendering). Per Spec 002 §Frame target resolution."
  [head-id {:keys [frame] :as opts}]
  (let [frame    (frame/require-frame-stamp!
                   frame :rf.ssr/render-head
                   {:where 'rf/render-head :event-id head-id})
        route    (if (contains? opts :route)
                   (:route opts)
                   (frame-route frame))
        ;; Resolve the `:head` registration through the carried frame's OWN
        ;; realm registrar (rf2-bzw8gd / EP-0013 §Realm Conformance) — a
        ;; non-default-realm frame's head is registered in that realm's table,
        ;; not the process-global default. `call-with-frame-realm-registrar`
        ;; binds nothing for a default-realm frame (byte-identical path).
        head-reg (frame/call-with-frame-realm-registrar
                   (frame/frame frame)
                   (fn [] (registrar/lookup :head head-id)))]
    (when-not head-reg
      (error/throw-error!
        :rf.error/no-such-head
        'rf/active-head
        (str "No head registered under " head-id
             "; register it with reg-head before rendering, or pass a "
             "head-id that has been registered.")
        {:recovery :register-the-head-id
         :extra    {:head-id head-id}}))
    (let [head-fn (:handler-fn head-reg)
          ;; `frame` is a required non-nil stamp here (require-frame-stamp!
          ;; above), so the app-db read is unconditional.
          db      (frame/frame-app-db-value frame)
          model   (head-fn db route)]
      (record-fragment! frame head-id model)
      model)))

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
  `[:rf.runtime/routing :current]`) is read from the frame's app-db.
  The produced fragment is recorded in
  the per-frame snapshot so `head-snapshot` reflects the most recent
  render-head output.

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

  Resolves the `:route` registration through the carried `frame-id`'s OWN
  realm registrar (rf2-bzw8gd / EP-0013 §Realm Conformance) — a
  non-default-realm frame's route metadata is registered in that realm's
  table. `call-with-frame-realm-registrar` binds nothing for a default-realm
  frame (byte-identical path)."
  [frame-id route]
  (when-let [route-id (:route-id route)]
    (when-let [route-meta (frame/call-with-frame-realm-registrar
                            (frame/frame frame-id)
                            (fn [] (registrar/lookup :route route-id)))]
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
  (let [frame-id (frame/require-frame-stamp!
                   frame-id :rf.ssr/active-head
                   {:where 'rf/active-head})
        route    (frame-route frame-id)
        head-id  (route-head-id frame-id route)]
    (if head-id
      ;; The route declares an id but it may not be registered — surface
      ;; that as :rf.error/no-such-head per Spec 011, but only when the
      ;; route explicitly opts in. Routes without :head silently fall
      ;; back to the default per Spec 011 §Default head.
      (render-head head-id {:frame frame-id :route route})
      (default-head frame-id))))
