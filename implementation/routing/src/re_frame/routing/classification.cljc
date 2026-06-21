(ns re-frame.routing.classification
  "Route OWNER data-classification — the EP-0025 routes follow-on (B5 routes
  arm, rf2-3r6k8i). Graduated normatively into
  [`spec/012-Routing.md` §Route data classification](../../../../../../spec/012-Routing.md).

  ## The subsystem-declaration rule (EP-0025 §Subsystem matrix, `reg-route` row)

  EP-0025 makes every runtime subsystem classify its own instance data
  PROJECTION-RELATIVE, applied per-instance at creation and dropped at
  teardown, lowered into the per-frame elision registry
  (`re-frame.elision/swap-elision-slot!`). The `reg-route` row of the
  subsystem matrix:

  | Projection root | Applied at | Dropped at | Generated key |
  |---|---|---|---|
  | the current route's `:query` / `:params` | route activation | route change / deactivation | current route (effectively a singleton) |

  A route is effectively a SINGLETON current-route — only one route is active
  in a frame at a time, sitting at `[:rf.runtime/routing :current]`. So unlike
  the machines / resources / mutations cases (one registry entry per spawned
  instance), the route's lowered classification is the WHOLE `:source :route`
  declaration set: route activation REPLACES it (dropping the previous route's
  entries), so a route change drops the leaving route's classification and a
  navigation to a route that declares none clears the registry of route-sourced
  entries entirely.

  ## Projection-relative authoring (EP-0025 §Subsystem declarations)

  A route declares `:sensitive` / `:large` paths RELATIVE to the route's
  current-state projection — the `{:query … :params …}` shape the route slice
  carries:

      (rf/reg-route :route/oauth-callback
        {:sensitive [[:query :token] [:query :code]]
         :large     [[:params :payload]]}
        \"/oauth/callback\")

  At route activation the projection-relative paths are RE-ROOTED to wherever
  the route's current state lives in runtime-db — under
  `[:rf.runtime/routing :current …]` — so the declared `[:query :token]`
  classifies the runtime path `[:rf.runtime/routing :current :query :token]`.
  The author never names the absolute runtime-db storage position (the
  storage-position problem EP-0025 §Rationale calls out); they name the path
  inside the route's own projection, and lowering re-roots it.

  ## Validation is fail-LOUD at registration (EP-0025 §Failure posture)

  Per EP-0025's three-way failure posture, a MALFORMED declaration (a bad path,
  a wrong shape, a non-EDN-identity segment) FAILS LOUDLY at `reg-route` time —
  before any state mutates and before the route can ever activate — with the
  canonical thrown-error shape (Spec 009 §The thrown-error shape) carrying
  `:rf.error/id :rf.error/invalid-route-classification`. This mirrors the
  `reg-frame` classification's `:rf.error/bad-frame-classification` and the
  commit-plane effect's `:rf.error/classification-effect-shape`. A FORGOTTEN
  classification is fail-open (the value ships raw — the hygiene bargain).

  ## Sensitive wins over large (EP-0025 §Egress rules)

  A path declared BOTH `:sensitive` and `:large` lowers as sensitive ONLY — its
  large entry is dropped at lowering time, so no `:rf.size/large-elided` marker
  (which would leak path / byte size / digest) is ever emitted for it. This is
  the install-time complement of the elision walker's sensitive-before-large
  ordering (`re-frame.elision/walk`), mirroring the same drop in
  `re-frame.frame-classification/validate+extract`.

  ## Lowering writes a runtime-db VALUE (not a live container swap)

  Lowering is PURE over a runtime-db value (`apply-route-classification`) so the
  navigation commit (`re-frame.routing.events/commit-navigation`) can fold the
  registry update into the SAME atomic `:rf.db/runtime` transition that
  publishes the route slice — the classification lands WITH the slice, exactly
  the way the commit-plane effects land with the `:db` write (EP-0025 §How it
  works §Same-event ordering). At frame teardown the whole runtime-db partition
  (including `[:rf.runtime/elision]`) is dropped, so the route-sourced entries
  vanish with the frame with no separate teardown hook.

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split convention: ROUTE-CLASSIFICATION seam."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.path :as path]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the classification keys --------------------------------------------

(def ^:const classification-keys
  "The two route-owned classification metadata keys (EP-0025, `reg-route`
  subsystem-matrix row). A `reg-route` metadata map carrying either triggers
  classification validation + lowering at activation; both are projection-
  relative `[[path] …]` declarations. Joined onto the routing-owned reserved
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
  (error/thrown-ex-info
    :rf.error/invalid-route-classification
    'rf/reg-route
    reason
    {:recovery :fix-route-classification
     :extra    (merge {:route-id route-id} extras)}))

;; ---- path validation (EP-0012 :rf/path) ----------------------------------
;;
;; A `:sensitive` / `:large` declaration is a VECTOR of projection-relative
;; paths; each path is a sequential collection of CONCRETE EDN segments (the
;; empty path `[]` is legal — it marks the whole route projection). The author
;; declares them relative to the route's `{:query … :params …}` projection;
;; lowering re-roots them under `current-route-root`. `path/normalize-concrete`
;; is the EP-0012 VALIDATED concrete boundary: it canonicalises a sequential
;; path to a vector AND fails closed with `:rf.error/bad-path` on a
;; non-sequential path OR any segment outside the concrete EDN domain. This is
;; a concrete declaration boundary, so it MUST use `normalize-concrete`, never
;; bare `normalize`.

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
              (path/normalize-concrete p)
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
  `{:query … :params …}` projection); lowering re-roots them under
  `[:rf.runtime/routing :current …]`.

  Returns nil when `metadata` carries no classification key (the common case —
  no work, no allocation)."
  [route-id metadata]
  (when (some #(contains? metadata %) classification-keys)
    (let [sens-paths  (normalize-axis-paths route-id :sensitive (:sensitive metadata))
          large-paths (normalize-axis-paths route-id :large     (:large metadata))
          sens-set    (set sens-paths)
          ;; Sensitive wins over large (EP-0025 §Egress rules): a path that is
          ;; BOTH lowers as sensitive ONLY — drop it from large so no
          ;; `:rf.size/large-elided` marker (path / bytes / digest) can ever
          ;; leak for it. The walker's sensitive-before-large ordering is the
          ;; runtime complement; this is the install-time guarantee (mirrors
          ;; `re-frame.frame-classification/validate+extract`).
          large-only  (into [] (remove sens-set) large-paths)]
      {:sensitive sens-paths
       :large     large-only})))

;; ---- lowering into the per-frame elision registry ------------------------
;;
;; A route is a SINGLETON current-route, so lowering REPLACES the whole
;; `:source :route` declaration set: route activation drops the leaving route's
;; route-sourced entries and installs the entering route's. Other sources
;; (`:source :frame` / `:source :effect` / `:source :flow`) survive untouched
;; and union at egress-lookup time (`re-frame.elision/elide-against-frame`
;; reads both `:sensitive-declarations` / `:declarations` sub-maps verbatim).
;; This mirrors how `re-frame.frame-classification/install!` replaces only its
;; own `:source :frame` entries.

(defn- without-route-sourced
  "Drop `:source :route` entries from a `{path decl}` declaration map,
  preserving any other-sourced entries (frame / marks / effect). Returns `{}`
  for nil."
  [decls]
  (reduce-kv (fn [acc p decl]
               (if (= :route (:source decl))
                 acc
                 (assoc acc p decl)))
             {}
             (or decls {})))

(defn- with-route-paths
  "Overlay `:source :route` declarations for `paths` (projection-relative)
  onto the carried (non-route-sourced) declaration map, RE-ROOTING each path
  under `[:rf.runtime/routing :current …]` so the stored decl key matches the
  wire walker's runtime-path lookup."
  [carried paths]
  (reduce (fn [acc p]
            (assoc acc (into current-route-root p) {:source :route}))
          carried
          paths))

(defn apply-route-classification
  "PURE lowering: given a base `runtime-db` value and a route's validated
  projection-relative `classification` (the `validate+extract` result, or
  nil), return the new runtime-db with the `[:rf.runtime/elision …]` registry's
  `:source :route` entries REPLACED by this route's (re-rooted under
  `[:rf.runtime/routing :current …]`).

  Operates on a VALUE (not a live container) so the navigation commit can fold
  it into the atomic `:rf.db/runtime` transition that publishes the slice — the
  classification lands WITH the slice (EP-0025 §Same-event ordering).

  Singleton semantics: a nil / empty `classification` (a route that declares no
  classification) still runs, clearing the prior route's `:source :route`
  entries — the leaving route's classification drops on every route change. The
  two axes write the elision registry's `:sensitive-declarations` (sensitive)
  and `:declarations` (large) sub-maps; an emptied axis slot is pruned
  (dissoc'd) rather than left as `{}`. Other-sourced entries (`:frame` /
  `:marks` / `:effect`) survive untouched.

  Reconcile-aware (router `re-frame.elision/reconcile-runtime-db-effect`): a
  whole-value `:rf.db/runtime` commit that OMITS the `:rf.runtime/elision` key
  has the prior registry CARRIED FORWARD (so an unrelated runtime-db write does
  not drop the durable registry). But a route change that clears the LAST
  route-sourced entry must DROP it — the leaving route's classification has to
  go. So whenever this lowering touched the registry (the base carried one, or
  this route declares some), it emits an EXPLICIT `:rf.runtime/elision` key
  (possibly `{}`) so reconcile honours the clear VERBATIM rather than resurrecting
  the leaving route's entries. The common no-classification-ever case (no prior
  slot, no new entries) emits no key — nothing to clear, no stray sub-tree."
  [runtime-db classification]
  (let [base  (or runtime-db {})
        sens  (:sensitive classification)
        large (:large classification)
        reg   (get base :rf.runtime/elision)
        carry-s (without-route-sourced (:sensitive-declarations reg))
        carry-l (without-route-sourced (:declarations reg))
        new-s   (with-route-paths carry-s sens)
        new-l   (with-route-paths carry-l large)
        new-reg (cond-> {}
                  (seq new-s) (assoc :sensitive-declarations new-s)
                  (seq new-l) (assoc :declarations new-l))]
    (cond
      ;; The lowering produced declarations → install the registry verbatim.
      (seq new-reg)
      (assoc base :rf.runtime/elision new-reg)

      ;; Empty result, but the base carried a registry → this is a CLEAR (the
      ;; leaving route's last route-sourced entry, or an effect/frame entry the
      ;; route doesn't touch but that resolved away). Emit the explicit key so
      ;; the router's reconcile honours the drop rather than carrying the prior
      ;; registry forward. `{}` is honoured verbatim and read as no declarations.
      (contains? base :rf.runtime/elision)
      (assoc base :rf.runtime/elision {})

      ;; No prior slot, no new entries → nothing to express; leave it absent
      ;; (the common route-without-classification case allocates no sub-tree).
      :else base)))

(defn lower-for-route
  "Resolve `route-meta`'s validated classification (`validate+extract`) and
  lower it onto `runtime-db` (`apply-route-classification`). THE single
  route-activation entry point the navigation commit calls — re-validating
  here keeps the stored route-meta a pure reflection map (no derived
  classification cached on it) and re-runs the fail-loud guard at the
  authoring boundary at registration, so a malformed declaration never reaches
  here. `route-meta` nil (an unregistered / not-found route) lowers an empty
  classification, which clears the prior route's `:source :route` entries — the
  leaving route's classification drops on a route-miss / not-found transition
  too. Pure over the runtime-db value."
  [runtime-db route-id route-meta]
  (apply-route-classification runtime-db (validate+extract route-id route-meta)))
