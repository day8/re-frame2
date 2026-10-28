(ns re-frame.routing.classification
  "Route-owned data classification, specified in
  [`spec/012-Routing.md` §Route data classification](../../../../../../spec/012-Routing.md).

  ## The subsystem-declaration rule

  Each runtime subsystem classifies its own instance data projection-relative,
  and egress applies the declaration to the instances present in the
  runtime-db being projected, through one late-bound hook per runtime-db root.
  The route arm is `runtime-classification` below, the
  `:routing/runtime-classification` hook for `:rf.runtime/routing`: its
  collect gathers every route's declaration, and its pure apply re-roots the
  declaration of the route the current slice names. The `reg-route` row of the
  subsystem matrix:

  | Projection root | Classified while | Named by |
  |---|---|---|
  | the current route's `:query` / `:params` | the route is current | the slice's `:route-id` |

  A route is effectively a SINGLETON current-route — only one route is active
  in a frame at a time, sitting at `[:rf.runtime/routing :current]`. So a
  navigation needs no classification step: the entering slice names the
  entering route, whose declaration then classifies it, and the leaving
  route's classification goes with its slice. Spec 015 §Subsystem
  projection-relative classification.

  ## Projection-relative authoring

  A route declares `:sensitive` / `:large` paths RELATIVE to the route's
  current-state projection — the `{:query … :params …}` shape the route slice
  carries:

      (rf/reg-route :route/oauth-callback
        {:sensitive [[:query :token] [:query :code]]
         :large     [[:params :payload]]}
        \"/oauth/callback\")

  At egress the projection-relative paths are RE-ROOTED to wherever the
  route's current state lives in runtime-db — under
  `[:rf.runtime/routing :current …]` — so the declared `[:query :token]`
  classifies the runtime path `[:rf.runtime/routing :current :query :token]`.
  The author never names the absolute runtime-db storage position; they name
  the path inside the route's own projection, and the hook re-roots it.

  ## Validation is fail-loud at registration

  A malformed declaration (a bad path,
  a wrong shape, a non-EDN-identity segment) FAILS LOUDLY at `reg-route` time —
  before any state mutates and before the route can ever activate — with the
  canonical thrown-error shape (Spec 009 §The thrown-error shape) carrying
  `:rf.error/id :rf.error/invalid-route-classification`. This mirrors the
  frame-config classification's `:rf.error/bad-frame-classification` and
  `reg-classification`'s `:rf.error/bad-classification`. A FORGOTTEN
  classification is fail-open (the value ships raw).

  ## Sensitive wins over large

  A path declared BOTH `:sensitive` and `:large` classifies as sensitive ONLY —
  its large entry is dropped when the declaration is extracted, so no
  `:rf.size/large-elided` marker (which would leak path / byte size / digest)
  is ever emitted for it. This is the declaration-time complement of the
  elision walker's sensitive-before-large ordering (`re-frame.elision/walk`),
  the final egress enforcement point.

  ## The query-key promotion advisory

  A route declares classification paths PROJECTION-RELATIVE to its
  `{:query … :params …}` shape, so a `:sensitive [[:query :token]]` names the
  KEYWORD key `:token`. But the runtime query slice keys a query key as a
  KEYWORD only when the route PROMOTES it via its `:query` schema or
  `:query-defaults` (`re-frame.routing.registry/coerce-query`);
  an undeclared key stays a string. So a `:sensitive [[:query
  :token]]` path on a route that does NOT name `:token` in its query vocabulary
  SILENTLY FAILS OPEN — the re-rooted keyword decl never matches the runtime
  string key, and the value ships raw with zero signal.

  EP-0025 (Spec 012 §319) blesses fail-open as the hygiene bargain, so this is
  an AUTHORING footgun, not a contract break — it must not throw. Instead a
  reg-route-time ADVISORY warning (`:rf.warning/route-classification-query-key-
  unpromoted`) fires when a `[:query k]` classification path names a query key
  the route's vocabulary does not promote to a keyword, so the author sees the
  pairing they forgot. PARAMS are immune (path captures are always keyword-keyed,
  `match.cljc`), so the advisory is query-axis-only. The `[]` whole-projection
  path and a deeper `[:query :token …]` path are advised on their `[:query k]`
  HEAD (the head key is what must promote).

  Internal namespace; the public facade is `re-frame.routing`."
  (:require [re-frame.error :as rf.error]
            [re-frame.path :as rf.path]
            [re-frame.registrar :as rf.registrar]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the classification keys --------------------------------------------

(def ^:const classification-keys
  "The two route-owned classification metadata keys. A `reg-route` metadata
  map carrying either triggers classification validation at registration;
  both are projection-relative `[[path] …]` declarations. Joined onto the
  routing-owned reserved
  metadata keys (`re-frame.routing.registry/reserved-route-keys`)."
  #{:sensitive :large})

;; The runtime-db root the route's current state projects onto: a route's
;; declared `[:query :token]` classifies the runtime path
;; `[:rf.runtime/routing :current :query :token]`. Re-rooting here keeps the
;; author's declaration projection-relative (`{:query … :params …}`) — they
;; never name the `[:rf.runtime/routing :current]` storage position.
(def ^:private current-route-root [:rf.runtime/routing :current])

;; ---- fail-loud error shape ----------------------------------------------

(defn- classification-error
  "Build the `:rf.error/invalid-route-classification` ex-info with the
  canonical thrown-error shape (Spec 009 §The thrown-error shape). `reason`
  is the human-facing message; `extras` names the offending slot
  (`:axis`, `:bad-path`, `:bad-value`) and MAY carry `:rf.error/cause` (the
  inner `:rf.error/id` of a wrapped path error — kept distinct so it never
  clobbers this error's own id)."
  [route-id reason extras]
  (rf.error/thrown-ex-info
    :rf.error/invalid-route-classification
    'rf/reg-route
    reason
    {:recovery :fix-route-classification
     :extra    (merge {:route-id route-id} extras)}))

;; ---- path validation ------------------------------------------------------
;;
;; A `:sensitive` / `:large` declaration is a VECTOR of projection-relative
;; paths; each path is a sequential collection of CONCRETE EDN segments (the
;; empty path `[]` is legal — it marks the whole route projection). The author
;; declares them relative to the route's `{:query … :params …}` projection;
;; the hook re-roots them under `current-route-root`. `rf.path/normalize-concrete`
;; is the EP-0012 VALIDATED concrete boundary: it canonicalises a sequential
;; path to a vector AND fails closed with `:rf.error/bad-path` on a
;; non-sequential path OR any segment outside the concrete EDN domain. This is
;; a concrete declaration boundary, so it MUST use `normalize-concrete`, never
;; bare `normalize`.
;;
;; `[]` is deliberately legal and re-roots to `current-route-root`, thereby
;; classifying the complete current-route slice rather than one query/params
;; descendant.

(defn- normalize-axis-paths
  "Validate + normalise the projection-relative paths of one classification
  `axis` (`:sensitive` / `:large`) to a vector of canonical `:rf/path` vectors.

  `paths` must be a vector (the declaration is a vector of paths); a non-vector
  whole, or any entry that is not a sequential collection of concrete segments,
  fails loudly. Returns `[]` for a nil/absent axis. `axis` names the offending
  key on failure."
  [route-id axis paths]
  (cond
    (nil? paths) []

    (not (vector? paths))
    (throw (classification-error
             route-id
             (str axis ", when present, must be a vector of projection-relative "
                  ":rf/path values (each a vector of segments rooted at the "
                  "route's {:query … :params …} projection; [] marks the whole "
                  "projection)")
             {:axis axis :bad-value paths}))

    :else
    (mapv (fn [p]
            (when-not (sequential? p)
              (throw (classification-error
                       route-id
                       (str axis " entries must each be an :rf/path (a sequential "
                            "collection of segments)")
                       {:axis axis :bad-path p})))
            (try
              (rf.path/normalize-concrete p)
              (catch #?(:clj Exception :cljs :default) e
                (throw (classification-error
                         route-id
                         (str axis " carries a malformed :rf/path: "
                              #?(:clj (.getMessage ^Exception e) :cljs (ex-message e)))
                         ;; Surface the offending segment + the inner path-error
                         ;; cause WITHOUT clobbering this error's own
                         ;; `:rf.error/id :rf.error/invalid-route-classification`
                         ;; (the inner cause is `:rf.error/bad-path`).
                         (merge {:axis axis :bad-path p}
                                (when-some [cause (:rf.error/id (ex-data e))]
                                  {:rf.error/cause cause})
                                (select-keys (ex-data e) [:bad-segment])))))))
          paths)))

;; ---- validation + extraction --------------------------------------------

(defn validate+extract
  "Validate the classification keys of a `reg-route` `metadata` map and return
  the extracted, normalised projection-relative classification:

      {:sensitive [<:rf/path>…]   ;; sensitive-wins: NEVER overlaps
       :large     [<:rf/path>…]}  ;; a sensitive path (dropped here)

  Throws `:rf.error/invalid-route-classification` (canonical thrown-error
  shape) on ANY defect — malformed path, wrong shape, non-EDN segment — so the
  failure fires at `reg-route` time, before any state mutates and before the
  route can ever activate. Paths are PROJECTION-RELATIVE (rooted at the route's
  `{:query … :params …}` projection); `runtime-classification` re-roots them
  under `[:rf.runtime/routing :current …]`.

  Returns nil when `metadata` carries no classification key (the common case —
  no work, no allocation)."
  [route-id metadata]
  (when (some #(contains? metadata %) classification-keys)
    (let [sens-paths  (normalize-axis-paths route-id :sensitive (:sensitive metadata))
          large-paths (normalize-axis-paths route-id :large     (:large metadata))
          sens-set    (set sens-paths)
          ;; Sensitive wins over large: a path that is
          ;; BOTH classifies as sensitive ONLY — drop it from large so no
          ;; `:rf.size/large-elided` marker (path / bytes / digest) can ever
          ;; leak for it. The walker's sensitive-before-large ordering is the
          ;; authoritative runtime enforcement; this declaration-time
          ;; drop is a belt-and-braces complement for route declarations.
          large-only  (into [] (remove sens-set) large-paths)]
      {:sensitive sens-paths
       :large     large-only})))

;; ---- the `:rf.runtime/routing` root hook ---------------------------------

(defn runtime-classification
  "The `:routing/runtime-classification` hook for the `:rf.runtime/routing`
  root. Collect, `[]`: each registered route's validated classification
  (`validate+extract`), keyed by route id, through the bound frame view.
  Apply, `[routing decls]`: the current slice under `routing` (the root's
  value) names its route by `:route-id`, and that route's declaration is
  re-rooted under `[:rf.runtime/routing :current …]`. Pure. Spec 015
  §Subsystem projection-relative classification."
  ([]
   (into {}
         (keep (fn [[route-id route-meta]]
                 (when-let [{:keys [sensitive large] :as decl}
                            (validate+extract route-id route-meta)]
                   (when (or (seq sensitive) (seq large))
                     [route-id decl]))))
         (rf.registrar/registrations :route)))
  ([routing decls]
   (let [{:keys [sensitive large]} (get decls (get-in routing [:current :route-id]))
         re-root (fn [paths] (mapv #(into current-route-root %) paths))]
     {:sensitive (re-root sensitive)
      :large     (re-root large)})))

;; ---- re-rooting onto a route read sub's value -----------------------------

(defn- relative-path
  "Runtime-db-absolute `path` relative to the value stored at `seed`: the
  suffix below `seed` when `path` lies at or under it, `[]` when `path` is an
  ancestor of `seed` (it then governs the whole value), else nil."
  [seed path]
  (let [n (count seed)
        m (count path)]
    (cond
      (and (>= m n) (= seed (subvec path 0 n))) (subvec path n)
      (and (< m n) (= path (subvec seed 0 m)))  []
      :else                                     nil)))

(defn classification-at
  "A route's projection-relative `classification` (the `validate+extract`
  shape), re-rooted onto the value stored at runtime-db `seed` — the storage
  position a route read sub's value projects onto. Returns
  `{:sensitive [paths] :large [paths]}` relative to that value, or nil when
  nothing the classification declares reaches it. A path declared at or above
  the projection the value holds (`[]`, or `[:params]` for the params map)
  governs the whole value.

  The route-sub egress projector classifies a sub's PRIOR value with it: a
  navigation replaces the current slice with the entering route's, so the
  leaving route's slice is classified by its own route's declaration."
  [classification seed]
  (when-some [{:keys [sensitive large]} classification]
    (let [rebase (fn [paths]
                   (into [] (keep #(relative-path seed (into current-route-root %))) paths))
          sens   (rebase sensitive)
          large  (rebase large)]
      (when (or (seq sens) (seq large))
        {:sensitive sens :large large}))))

;; ---- query-key promotion advisory ----------------------------------------
;;
;; A reg-route-time WARNING (never a throw) that closes the EP-0025 query-key
;; footgun: a `:sensitive` / `:large` `[:query k]` path on a route that does NOT
;; promote `k` to a keyword (via `:query` / `:query-defaults`)
;; silently fails open at egress — the keyword decl never matches the runtime
;; STRING key. PARAMS are immune (always keyword-keyed), so this is query-only.

(defn unpromoted-query-keys
  "Return the set of query keys a route's `classification` (the
  `validate+extract` result) names under a `[:query k]` HEAD that the route's
  declared `promoted-keys` set does NOT promote to a keyword — the silent
  fail-open mismatch. Scans both axes' normalised paths; a path
  whose first two segments are `[:query k]` contributes `k` when `k` is a
  keyword absent from `promoted-keys`. The whole-projection path `[]` and a
  bare `[:query]` carry no query KEY to advise on (no second segment). A
  non-keyword head key (a string `[:query \"token\"]`) is also reported, since a
  string key can never be a keyword-promoted slot key. Pure; returns `#{}` for
  nil classification."
  [classification promoted-keys]
  (let [promoted (set promoted-keys)]
    (into #{}
          (comp (mapcat (fn [axis] (get classification axis)))
                (keep (fn [p]
                        (when (and (sequential? p)
                                   (= :query (first p))
                                   (>= (count p) 2))
                          (second p))))
                (remove (fn [k] (and (keyword? k) (contains? promoted k)))))
          [:sensitive :large])))

(defn advise-query-promotion!
  "Emit a `:rf.warning/route-classification-query-key-unpromoted` advisory
  when `route-meta`'s `:sensitive` / `:large` classification names
  a `[:query k]` path whose query key `k` the route does NOT promote to a
  keyword via `:query` / `:query-defaults` (`promoted-keys`).
  Such a path silently FAILS OPEN at egress — the keyword decl never matches the
  runtime string key, shipping the value raw with no signal. This is an
  authoring footgun, not a contract break (EP-0025 blesses fail-open as the
  hygiene bargain), so it WARNS, never throws.

  A no-op when the route declares no classification, names no `[:query k]` path,
  or promotes every classified query key. `route-id` names the route on the
  trace; `promoted-keys` is the route's declared query vocabulary (the keyword
  keys of `:query` / `:query-defaults`). Returns nil."
  [route-id route-meta promoted-keys]
  (when-some [classification (validate+extract route-id route-meta)]
    (let [missing (unpromoted-query-keys classification promoted-keys)]
      (when (seq missing)
        (rf.trace/emit! :warning :rf.warning/route-classification-query-key-unpromoted
                     {:route-id      route-id
                      :query-keys    (vec (sort-by str missing))
                      :promoted-keys (vec (sort-by str promoted-keys))
                      :advice        (str "route " route-id " declares a :sensitive / :large "
                                          "[:query k] path for query key(s) it does not promote "
                                          "to a keyword via :query / :query-defaults. "
                                          "An unpromoted query key stays a STRING in the route slice, "
                                          "so the keyword classification path never matches it and the "
                                          "value SILENTLY ships raw at egress (EP-0025 fail-open). Add "
                                          "the key to the route's :query schema (e.g. :query [:map "
                                          "[:token :string]]) so the slice carries the keyword key the "
                                          "path targets.")}))))
  nil)
