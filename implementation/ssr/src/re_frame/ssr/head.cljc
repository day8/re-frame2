(ns re-frame.ssr.head
  "Head/meta contract — `reg-head`, `render-head`, `active-head`. Per
  Spec 011 §Head/meta contract (rf2-4dra9).

  The server-rendered HTML must carry head metadata — `<title>`,
  `<meta>`, `<link>`, JSON-LD — on first byte; crawlers and link-
  unfurlers don't run JS. The pattern's commitment: **the head model
  is data derived from app-db**, not an imperative DOM API.

  Public surface:

    `reg-head`          — register a head-fragment producer `(fn [db
                          route] head-model)` keyed by id (e.g.
                          `:rf.ssr/title`, `:my.app/article`). New
                          registry kind `:head` (Spec 001 §Registry
                          model — already in the closed v1 set).
    `render-head`       — given a head-id and a frame (and optionally
                          an explicit route), invoke the head fn
                          against the frame's app-db and the active
                          route; record the produced model in the
                          per-frame snapshot map; return the model.
    `active-head`       — sugar — looks up the active route's `:head`
                          metadata in the registrar; if set, calls
                          `render-head`; otherwise returns the default
                          head per Spec 011 §Default head.
    `head-model->html`  — emit `<head>…</head>` (or the inner fragment)
                          from a `:rf/head-model`. Canonical order:
                          `<title>` first, then `<meta>` (declaration
                          order), then `<link>`, then `<script>`, then
                          JSON-LD `<script type=\"application/ld+json\">`.
                          `:html-attrs` / `:body-attrs` are exposed on
                          the returned record so a host shell can
                          stamp them onto `<html>` / `<body>`.
    `head-snapshot`     — read the per-frame `{head-id → last-produced
                          fragment}` snapshot. Useful for tests +
                          introspection. Cleared on frame destroy.

  Per-request frame lifecycle: the head-snapshot side-channel atom is
  keyed by frame-id and cleared via the `:ssr/on-frame-destroyed`
  late-bind hook (per Spec 011 §Per-request frame teardown / rf2-fcj33).
  Head registrations under `:head` themselves are process-wide registry
  entries (not per-frame state); cleanup applies only to the per-frame
  snapshot, not to the registered fns."
  (:require [clojure.string :as str]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]))

;; ---- HTML helpers (mirrors re-frame.ssr's escape/attr helpers) -------------
;;
;; head.cljc lives alongside ssr.cljc but is loaded after it; both produce
;; HTML and share the same escape rules. We re-derive the helpers here
;; (rather than depending on private symbols from ssr.cljc) so head.cljc
;; remains a self-contained namespace whose contracts are visible at the
;; public boundary.

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- escape-attr [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")))

(defn- attr-string
  "Render an attribute map as ` k1=\"v1\" k2=\"v2\"`. Boolean true → bare
  attribute name; false / nil → omitted. Mirrors the rules in
  `re-frame.ssr/-attr-string`."
  [attrs]
  (if (empty? attrs)
    ""
    (str " "
         (str/join " "
                   (keep (fn [[k v]]
                           (cond
                             (true? v)  (name k)
                             (false? v) nil
                             (nil? v)   nil
                             :else      (str (name k) "=\"" (escape-attr v) "\"")))
                         attrs)))))

;; ---- canonical-order emitter ----------------------------------------------
;;
;; Per Spec 011 §Default flow: emit `<title>` first, then `<meta>` (in
;; declaration order), then `<link>`, then `<script>`, then JSON-LD
;; `<script type=\"application/ld+json\">`. `:html-attrs` populate
;; `<html>`; `:body-attrs` populate `<body>` — those are emitted by the
;; host shell, not by this fn.
;;
;; The result is a *string* containing the inner-head fragment (no
;; surrounding `<head>` / `</head>` tags by default — host shells can
;; choose whether they wrap). For convenience the 2-arity form opts in
;; to the surrounding `<head>` tags.

(defn- emit-title [title]
  (when (and title (not= "" title))
    (str "<title>" (escape-html title) "</title>")))

(defn- emit-meta-tag [m]
  (str "<meta" (attr-string m) ">"))

(defn- emit-link-tag [m]
  (str "<link" (attr-string m) ">"))

(defn- emit-script-tag [m]
  (let [{:keys [src async defer type integrity crossorigin nomodule]} m
        attrs (cond-> {}
                src         (assoc :src src)
                type        (assoc :type type)
                async       (assoc :async async)
                defer       (assoc :defer defer)
                integrity   (assoc :integrity integrity)
                crossorigin (assoc :crossorigin crossorigin)
                nomodule    (assoc :nomodule nomodule))]
    (str "<script" (attr-string (merge attrs (dissoc m :src :async :defer :type :integrity :crossorigin :nomodule))) "></script>")))

(defn- ld-json-string
  "Serialise a JSON-LD object map to its `<script type=\"application/ld+json\">`
  body. CLJ uses a minimal printer (works for the spec's canonical shape:
  strings, numbers, booleans, vectors, maps with string keys). CLJS uses
  `js/JSON.stringify` via `clj->js`."
  [x]
  #?(:cljs (js/JSON.stringify (clj->js x))
     :clj  (letfn [(emit [v]
                     (cond
                       (nil? v)     "null"
                       (boolean? v) (if v "true" "false")
                       (number? v)  (str v)
                       (string? v)  (str "\""
                                         (-> v
                                             (str/replace "\\" "\\\\")
                                             (str/replace "\"" "\\\""))
                                         "\"")
                       (keyword? v) (emit (if-let [ns (namespace v)]
                                            (str ns "/" (name v))
                                            (name v)))
                       (map? v)     (str "{"
                                         (str/join "," (map (fn [[k val]]
                                                              (str (emit (if (keyword? k) (name k) k))
                                                                   ":"
                                                                   (emit val)))
                                                            v))
                                         "}")
                       (sequential? v) (str "[" (str/join "," (map emit v)) "]")
                       :else (emit (str v))))]
             (emit x))))

(defn- emit-json-ld [m]
  (str "<script type=\"application/ld+json\">"
       (ld-json-string m)
       "</script>"))

(defn head-model->html
  "Render a `:rf/head-model` map to its inner-head HTML fragment in
  canonical order — `<title>`, `<meta>` (declaration order), `<link>`,
  `<script>`, JSON-LD `<script type=\"application/ld+json\">`. The two
  attribute-bag keys (`:html-attrs`, `:body-attrs`) are NOT emitted by
  this fn — they're consumed by host shells that stamp them onto
  `<html>` / `<body>`.

  The 1-arity form returns the inner fragment (no surrounding `<head>`
  tags); the 2-arity form with `{:wrap? true}` wraps in `<head>…</head>`.

  Per Spec 011 §Default flow step 4."
  ([head-model] (head-model->html head-model {}))
  ([head-model {:keys [wrap?]}]
   (let [{:keys [title meta link script json-ld]} head-model
         parts (cond-> []
                 (and title (not= "" title))
                 (conj (emit-title title))

                 (seq meta)
                 (into (map emit-meta-tag) meta)

                 (seq link)
                 (into (map emit-link-tag) link)

                 (seq script)
                 (into (map emit-script-tag) script)

                 (seq json-ld)
                 (into (map emit-json-ld) json-ld))
         inner (apply str parts)]
     (if wrap?
       (str "<head>" inner "</head>")
       inner))))

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
;;
;; Per Spec 011 §Default head when no route declares `:head`:
;;
;;   {:title (or (:doc (frame-meta frame-id)) "")
;;    :meta [{:charset "utf-8"}
;;           {:name "viewport" :content "width=device-width, initial-scale=1"}]}
;;
;; No registered head is required. The default is silent — no warning.

(defn default-head
  "The fallback head-model used when the active route does not declare
  `:head` (or there is no active route). Per Spec 011 §Default head.

  Pulls `:title` from the frame's `:doc` if set, otherwise empty."
  [frame-id]
  (let [doc (when frame-id (:doc (frame/frame-meta frame-id)))]
    (cond-> {:meta [{:charset "utf-8"}
                    {:name "viewport" :content "width=device-width, initial-scale=1"}]}
      (and doc (not= "" doc)) (assoc :title doc))))

;; ---- render-head ----------------------------------------------------------

(defn- frame-route
  "Read the `:rf/route` slice from a frame's app-db. nil-safe — a frame
  whose app-db has never been initialised resolves to nil."
  [frame-id]
  (when frame-id
    (let [db (frame/frame-app-db-value frame-id)]
      (when db (:rf/route db)))))

(defn render-head
  "Apply the head fn registered under `head-id` against a frame's
  app-db and active route, returning the produced `:rf/head-model`.

  Two opt shapes are accepted:

    (render-head head-id frame-id)
    (render-head head-id {:frame frame-id :route route})

  When `:route` is absent, the active route slice (`:rf/route`) is
  read from the frame's app-db. The produced fragment is recorded in
  the per-frame snapshot so `head-snapshot` reflects the most recent
  render-head output.

  Raises `:rf.error/no-such-head` when `head-id` is not registered.
  Per Spec 011 §`render-head`."
  ([head-id opts]
   (let [opts'    (if (keyword? opts) {:frame opts} opts)
         frame-id (:frame opts')
         route    (if (contains? opts' :route)
                    (:route opts')
                    (frame-route frame-id))
         meta     (registrar/lookup :head head-id)]
     (when-not meta
       (throw (ex-info ":rf.error/no-such-head"
                       {:head-id  head-id
                        :reason   (str "No head registered under " head-id ".")
                        :recovery :no-recovery})))
     (let [head-fn (:handler-fn meta)
           db      (when frame-id (frame/frame-app-db-value frame-id))
           model   (head-fn db route)]
       (record-fragment! frame-id head-id model)
       model))))

;; ---- active-head ----------------------------------------------------------

(defn- route-head-id
  "Read the `:head` route-metadata key for the route-id named in the
  active `:rf/route` slice. Returns nil when there's no active route,
  no route registration, or no `:head` declared on the route."
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

;; ---- late-bind hook registration ------------------------------------------
;;
;; Per the optional-artefact wrapper convention (Conventions.md
;; §Optional-artefact wrapper convention), each public surface is
;; reachable via `re-frame.core` through a late-bind hook so core never
;; statically `:require`s `re-frame.ssr.head`. Apps that don't depend
;; on the SSR artefact see `:rf.error/ssr-artefact-missing` when they
;; call into these surfaces.

(late-bind/set-fn! :ssr/reg-head          reg-head)
(late-bind/set-fn! :ssr/render-head       render-head)
(late-bind/set-fn! :ssr/active-head       active-head)
(late-bind/set-fn! :ssr/head-snapshot     head-snapshot)
;; NB: late-bind keys conventionally use `-` only (the drift-detector
;; regex limits its grammar to alpha-numeric + standard symbol chars);
;; the user-facing fn is `head-model->html` but the hook key drops the
;; `->` decoration.
(late-bind/set-fn! :ssr/head-model-html   head-model->html)

;; ---- per-request frame teardown -------------------------------------------
;;
;; `re-frame.ssr/on-frame-destroyed!` (under the `:ssr/on-frame-destroyed`
;; late-bind hook) clears `pending-error-traces` and `request-slots` per
;; rf2-fcj33. The head ns adds per-frame head-snapshot cleanup; we
;; surface our cleanup via a separate `:ssr.head/on-frame-destroyed`
;; hook that `re-frame.ssr`'s teardown fn looks up and invokes.
;;
;; Why a separate hook rather than wrapping `:ssr/on-frame-destroyed`?
;; Load order is unpredictable under the optional-artefact wrapper
;; convention — `re-frame.ssr` `:require`s `re-frame.ssr.head` so the
;; head ns loads BEFORE ssr's body publishes its hook. Any chain we
;; install here would be overwritten when ssr runs its own
;; `(late-bind/set-fn! :ssr/on-frame-destroyed ...)`. The keyed hook
;; sidesteps the ordering issue: ssr's fn invokes ours by key,
;; regardless of which ns loaded first.
;;
;; Hook key naming: `:ssr/head-on-frame-destroyed` (not
;; `:ssr.head/on-frame-destroyed`) because the drift-detector regex
;; only matches single-segment namespace components on late-bind keys.

(late-bind/set-fn! :ssr/head-on-frame-destroyed on-frame-destroyed!)
