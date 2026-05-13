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
  (:require [re-frame.frame :as frame]
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
  "Stash the just-produced head-model under (frame-id, head-id) so
  `head-snapshot` reflects the most recent render-head output."
  [frame-id head-id head-model]
  (when frame-id
    (swap! head-snapshots assoc-in [frame-id head-id] head-model))
  head-model)

(defn head-snapshot
  "Read the per-frame `{head-id → last-produced head-model}` snapshot.
  Useful for tests and introspection. Returns `{}` for a frame that has
  never seen a `render-head` call (or whose snapshot has been cleared
  via the per-request frame teardown hook)."
  ([frame-id]
   (get @head-snapshots frame-id {})))

(defn on-frame-destroyed!
  "Clear the head-snapshot entry for `frame-id`. Wired into the
  `:ssr/on-frame-destroyed` late-bind hook chain so per-request frames
  release their head bookkeeping on destroy. Idempotent."
  [frame-id]
  (swap! head-snapshots dissoc frame-id)
  nil)

;; ---- reg-head -------------------------------------------------------------

(defn reg-head
  "Register a head-fragment producer under `id` (a namespaced keyword
  such as `:my.app/article` or `:rf.ssr/title`).

  `head-fn` signature is `(fn [db route] head-model)` — pure,
  deterministic, no side-effects. Same shape and discipline as a sub.
  `db` is the frame's app-db value (plain map, deref'd through the
  substrate adapter); `route` is the `:rf/route` slice (or whatever
  the caller passed to `render-head`).

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
  the model sees a stable key shape (audit rf2-asmj1 H5 / cluster
  rf2-sljs1)."
  [frame-id]
  (let [doc (when frame-id (:doc (frame/frame-meta frame-id)))]
    {:title (or doc "")
     :meta  [{:charset "utf-8"}
             {:name "viewport" :content "width=device-width, initial-scale=1"}]}))

;; ---- render-head ----------------------------------------------------------

(defn- frame-route
  "Read the `:rf/route` slice from a frame's app-db. nil-safe — a frame
  whose app-db has never been initialised resolves to nil."
  [frame-id]
  (when frame-id
    (let [db (frame/frame-app-db-value frame-id)]
      (when db (:rf/route db)))))

(defn- render-head*
  "Resolve a normalised opts map and run the registered head fn. Split
  from `render-head` per audit rf2-asmj1 H7 / cluster rf2-sljs1 so the
  caller-facing fn carries the documented two-shape contract on its
  signature and a private helper carries the work."
  [head-id {:keys [frame] :as opts}]
  (let [route (if (contains? opts :route)
                (:route opts)
                (frame-route frame))
        meta  (registrar/lookup :head head-id)]
    (when-not meta
      (throw (ex-info ":rf.error/no-such-head"
                      {:head-id  head-id
                       :reason   (str "No head registered under " head-id ".")
                       :recovery :no-recovery})))
    (let [head-fn (:handler-fn meta)
          db      (when frame (frame/frame-app-db-value frame))
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

  When `:route` is absent, the active route slice (`:rf/route`) is
  read from the frame's app-db. The produced fragment is recorded in
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
  active `:rf/route` slice. Returns nil when there's no active route,
  no route registration, or no `:head` declared on the route.

  Contract — the slice's `(:id route)` IS the canonical registrar key
  under the `:route` kind. If the runtime ever introduces an indirection
  between the slice id and the registry key (route aliases, versioned
  routes, ...), this fn breaks and must learn the new mapping (audit
  rf2-asmj1 H6 / cluster rf2-sljs1)."
  [route]
  (when-let [route-id (:id route)]
    (when-let [route-meta (registrar/lookup :route route-id)]
      (:head route-meta))))

(defn active-head
  "Sugar — look up the active route's `:head` metadata; if set, call
  `render-head` and return the model. Otherwise return the `default-head`
  per Spec 011 §Default head.

  Two arities:

    (active-head)            — uses the default frame `:rf/default`
                               (matches the call shape in tools / dev
                               consoles).
    (active-head frame-id)   — explicit frame.

  Returns the resolved head-model. Per Spec 011 §`render-head`."
  ([] (active-head :rf/default))
  ([frame-id]
   (let [route   (frame-route frame-id)
         head-id (route-head-id route)]
     (if head-id
       ;; The route declares an id but it may not be registered — surface
       ;; that as :rf.error/no-such-head per Spec 011, but only when the
       ;; route explicitly opts in. Routes without :head silently fall
       ;; back to the default per Spec 011 §Default head.
       (render-head head-id {:frame frame-id :route route})
       (default-head frame-id)))))
