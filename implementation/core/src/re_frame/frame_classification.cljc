(ns re-frame.frame-classification
  "Frame-owned durable data classification per EP-0015 §3 (Frame-Owned
  Durable Classification) + §9 (Frame-Owned Observability Sink Policy),
  graduated into [`spec/015-Data-Classification.md` §Frame-owned durable
  classification] and the `reg-frame` grammar in [`spec/002-Frames.md`].

  A frame's `reg-frame` metadata MAY carry three classification keys:

      (rf/reg-frame :app/main
        {:sensitive
         {:app-db [[:auth :token] [:tenant :partner-api-key]]
          :http   {:headers      [\"X-Honeycomb-Team\"]
                   :query-params [\"shop_token\"]}}

         :large
         {:app-db [[:documents :csv-upload]]}

         :observability
         {:handled-events [{:sink :my-app.sinks/datadog
                            :rf.egress/profile :rf.egress/off-box-observability
                            :opts {:service \"checkout-spa\"}}]
          :errors         [{:sink :my-app.sinks/sentry
                            :rf.egress/profile :rf.egress/off-box-observability}]}

         :on-create [:app/init]})

  ## What this slice owns (EP-0015 bead-plan item 3)

  This namespace is the frame metadata **schema + registry** for the three
  classification keys. It is the validation + install seam `reg-frame`
  calls, atomically as part of frame creation, BEFORE `:on-create` runs.

  - **`:sensitive :app-db` / `:large :app-db`** are vectors of `:rf/path`
    values (EP-0012). They are INSTALLED into the frame's durable elision
    registry (`[:rf.runtime/elision :sensitive-declarations]` /
    `[:rf.runtime/elision :declarations]`, Conventions §Reserved runtime-db
    keys) under `:source :frame` — the canonical durable app-db egress
    route post-EP-0015 §8 (the `reg-app-schema` schema→app-db-egress route
    is GONE; schemas describe shape, not durable app-db egress policy). The
    only other source that can live in this registry is the demoted
    imperative-mark route (`:source :marks` — internal / test /
    generated-code only, no longer public). A path declared by ANY source
    is classified: the sources union at lookup time. Re-registering a frame
    REPLACES the `:source :frame` entries (the declaration IS the frame's
    policy); any `:source :marks` entries survive untouched.

  - **`:sensitive :http :headers` / `:query-params`** are frame-local
    EXTENSIONS to the immutable built-in HTTP carrier denylist (Spec 014
    §Privacy). They are durable frame config facts — retained verbatim on
    the frame's `:config` (so `frame-meta` surfaces them and the HTTP
    layer can read them) — NOT elision-walker path declarations. This slice
    validates them (non-string carrier names fail loudly) and keeps them on
    the config; the `http-carriers` resolver (this ns) lowers them to
    lower-cased extension sets the HTTP privacy redactor unions onto the
    immutable built-in carrier denylist at trace-emit time, reached via the
    `:frame-classification/http-carriers` late-bind hook (EP-0015 HTTP
    slice, bead-plan item 8 — rf2-ppkh3v).

  - **`:observability`** (`:handled-events` / `:errors`) is durable frame
    sink policy, likewise retained on the frame's `:config`. This slice
    validates its shape (each entry a map naming a `:sink` keyword);
    ROUTING production records through the declared sinks — the EP-0015 §9
    central claim — is now LIVE in `re-frame.observability` (bead-plan
    item 7, rf2-t55hxg.7): the router fires
    `:observability/route-handled-event` once per processed event and
    `error-emit/dispatch-on-error!` fires `:observability/route-error` per
    `:rf.error/*` site, each projecting the record through `project-egress`
    under THIS frame's classification + the entry's `:rf.egress/profile`
    before the declared sink (registered via `register-observability-sink!`)
    sees it. This slice owns the `:config` shape the router reads.

  ## Sensitive wins over large

  A path declared BOTH `:sensitive :app-db` and `:large :app-db` installs
  as sensitive ONLY — its large declaration entry is dropped at install
  time, so no `:rf.size/large-elided` marker (which would leak path / byte
  size / digest / fetch-handle) is ever emitted for it. This is the
  install-time complement of the walker's sensitive-before-large ordering
  (`re-frame.elision/walk`); both hold the EP-0015 §3 rule.

  ## Fail loud at registration

  Per EP-0015 §3, malformed paths, unknown classification keys, and
  non-string HTTP carrier names FAIL LOUDLY at frame registration — BEFORE
  any state mutates and before `:on-create` runs. The thrown ex-info
  carries the canonical thrown-error shape (Spec 009 §The thrown-error
  shape) with `:rf.error/id :rf.error/bad-frame-classification`.

  ## Keyword namespacing (EP-0015 §2)

  The frame-local grammar keys (`:sensitive`, `:large`, `:observability`,
  `:app-db`, `:http`, `:headers`, `:query-params`, `:handled-events`,
  `:errors`, `:sink`, `:opts`) stay BARE — a `reg-frame` metadata map is a
  framework-owned grammar. The cross-surface egress key `:rf.egress/profile`
  is namespaced (it means the same thing across `project-egress`, sink
  policy, MCP, SSR, and tool options). User/library-owned sink ids
  (`:my-app.sinks/datadog`) are NOT framework-claimed."
  (:require [clojure.string :as str]
            [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.path :as path]
            [re-frame.projection :as projection]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the classification keys --------------------------------------------

(def ^:const classification-keys
  "The three frame-owned classification metadata keys (EP-0015 §3 + §9).
  A `reg-frame` metadata map carrying any of these triggers classification
  validation + install; every OTHER key is ordinary frame config and is
  passed through untouched."
  #{:sensitive :large :observability})

;; ---- fail-loud error shape ----------------------------------------------

(defn- classification-error
  "Build the `:rf.error/bad-frame-classification` ex-info with the canonical
  thrown-error shape (Spec 009 §The thrown-error shape). `reason` is the
  human-facing message; `extras` names the offending slot (`:bad-key`,
  `:bad-path`, `:bad-segment`, `:bad-carrier`, `:bad-entry`, `:bad-value`)
  and MAY carry `:rf.error/cause` (the inner `:rf.error/id` of a wrapped
  path error — kept distinct so it never clobbers this error's own id)."
  [frame-id reason extras]
  (error/thrown-ex-info
    :rf.error/bad-frame-classification
    'rf/reg-frame
    reason
    {:recovery :fix-frame-classification
     :extra    (merge {:frame frame-id} extras)}))

;; ---- path validation (EP-0012 :rf/path) ----------------------------------
;;
;; `:sensitive :app-db` / `:large :app-db` entries are concrete `:rf/path`
;; values. A well-formed declaration is a VECTOR of paths; each path is a
;; sequential collection of CONCRETE EDN segments (the empty path `[]` is
;; legal — it marks the whole app-db). `rf.path/normalize-concrete` is the
;; EP-0012 VALIDATED concrete boundary (rf2-w9x5fv item 2): it canonicalises
;; a sequential path to a vector AND fails closed with `:rf.error/bad-path`
;; on a non-sequential path OR any segment outside the concrete EDN domain
;; (an opaque host object, a function, a template-parameter segment). We
;; catch that and re-raise as the frame-classification error so the author
;; sees the frame + key. This is a concrete declaration boundary, so it MUST
;; use `normalize-concrete`, never bare `normalize` — a host/opaque segment
;; in a stored elision path is rejected here, not silently stored.

(defn- normalize-app-db-paths
  "Validate + normalise the `:app-db` paths of a `:sensitive` / `:large`
  classification block to a vector of canonical `:rf/path` vectors.

  `paths` must be a vector (the declaration is a vector of paths); a
  non-vector whole, or any entry that is not a sequential collection of
  scalar segments, fails loudly. Returns `[]` for a nil/absent block.
  `class-key` (`:sensitive` / `:large`) names the offending key on failure."
  [frame-id class-key paths]
  (cond
    (nil? paths) []

    (not (vector? paths))
    (throw (classification-error
             frame-id
             (str class-key " :app-db, when present, must be a vector of "
                  ":rf/path values (each a vector of segments; [] marks the "
                  "whole app-db)")
             {:bad-key [class-key :app-db] :bad-value paths}))

    :else
    (mapv (fn [p]
            (when-not (sequential? p)
              (throw (classification-error
                       frame-id
                       (str class-key " :app-db entries must each be an "
                            ":rf/path (a sequential collection of segments)")
                       {:bad-key [class-key :app-db] :bad-path p})))
            (try
              (path/normalize-concrete p)
              (catch #?(:clj Exception :cljs :default) e
                (throw (classification-error
                         frame-id
                         (str class-key " :app-db carries a malformed "
                              ":rf/path: " #?(:clj (.getMessage e)
                                              :cljs (ex-message e)))
                         ;; Surface the offending segment + the inner path
                         ;; error cause WITHOUT clobbering this error's own
                         ;; `:rf.error/id :rf.error/bad-frame-classification`
                         ;; (the inner cause is `:rf.error/bad-path`).
                         (merge {:bad-key [class-key :app-db] :bad-path p}
                                (when-some [cause (:rf.error/id (ex-data e))]
                                  {:rf.error/cause cause})
                                (select-keys (ex-data e) [:bad-segment])))))))
          paths)))

;; ---- HTTP carrier validation --------------------------------------------
;;
;; `:sensitive :http {:headers [..] :query-params [..]}` are frame-local
;; extensions to the immutable framework HTTP carrier denylist (Spec 014
;; §Privacy). Carrier names MUST be strings (header / query-param names are
;; strings on the wire); a non-string name fails loudly.

(defn- validate-carriers!
  "Validate one HTTP carrier-name vector (`:headers` or `:query-params`).
  Each name must be a string; a non-string carrier fails loudly. `carrier-key`
  names the slot for the error. Returns the (unchanged) vector, or `nil`."
  [frame-id carrier-key names]
  (cond
    (nil? names) nil

    (not (vector? names))
    (throw (classification-error
             frame-id
             (str ":sensitive :http " carrier-key ", when present, must be a "
                  "vector of carrier-name strings")
             {:bad-key [:sensitive :http carrier-key] :bad-value names}))

    :else
    (do
      (doseq [n names]
        (when-not (string? n)
          (throw (classification-error
                   frame-id
                   (str ":sensitive :http " carrier-key " names must be "
                        "strings (header / query-param names are strings)")
                   {:bad-key [:sensitive :http carrier-key] :bad-carrier n}))))
      names)))

(def ^:private http-carrier-keys #{:headers :query-params})

(defn- validate-http-block!
  "Validate the `:sensitive :http` block — a map that may carry `:headers`
  and/or `:query-params` carrier-name vectors. An unknown key inside `:http`
  fails loudly (closed grammar). Returns nil; throws on any defect."
  [frame-id http]
  (when (some? http)
    (when-not (map? http)
      (throw (classification-error
               frame-id
               ":sensitive :http, when present, must be a map of {:headers [..] :query-params [..]}"
               {:bad-key [:sensitive :http] :bad-value http})))
    (doseq [k (keys http)]
      (when-not (contains? http-carrier-keys k)
        (throw (classification-error
                 frame-id
                 (str "unknown :sensitive :http key " k "; valid keys are "
                      ":headers and :query-params")
                 {:bad-key [:sensitive :http k]
                  :valid   http-carrier-keys}))))
    (validate-carriers! frame-id :headers      (:headers http))
    (validate-carriers! frame-id :query-params (:query-params http))
    nil))

;; ---- classification-block grammar validation ----------------------------
;;
;; The `:sensitive` / `:large` blocks are maps with a closed key set. A
;; `:sensitive` block may carry `:app-db` and `:http`; a `:large` block may
;; carry only `:app-db` (HTTP carriers + observability are not "large"
;; surfaces). An unknown key fails loudly.

(def ^:private sensitive-block-keys #{:app-db :http})
(def ^:private large-block-keys     #{:app-db})

(defn- validate-block-keys!
  [frame-id class-key block valid-keys]
  (when (some? block)
    (when-not (map? block)
      (throw (classification-error
               frame-id
               (str class-key ", when present, must be a map (e.g. "
                    "{:app-db [..]" (when (= :sensitive class-key)
                                      " :http {:headers [..]}") "})")
               {:bad-key class-key :bad-value block})))
    (doseq [k (keys block)]
      (when-not (contains? valid-keys k)
        (throw (classification-error
                 frame-id
                 (str "unknown " class-key " key " k "; valid keys are "
                      valid-keys)
                 {:bad-key [class-key k] :valid valid-keys}))))))

;; ---- observability sink-policy validation --------------------------------
;;
;; `:observability {:handled-events [<entry>...] :errors [<entry>...]}`.
;; Each entry is a map naming a `:sink` (a keyword sink id) and optionally
;; an `:rf.egress/profile` (a member of the closed EP-0015 §10 profile
;; enum) and an `:opts` map. This slice validates the SHAPE; routing
;; records through the sinks is the EP-0015 observability slice. An unknown
;; top-level `:observability` key fails loudly.

(def ^:private observability-keys #{:handled-events :errors})

(defn- validate-sink-entry!
  [frame-id stream entry]
  (when-not (map? entry)
    (throw (classification-error
             frame-id
             (str ":observability " stream " entries must be maps naming a "
                  ":sink")
             {:bad-key [:observability stream] :bad-entry entry})))
  (when-not (keyword? (:sink entry))
    (throw (classification-error
             frame-id
             (str ":observability " stream " entries must carry a :sink "
                  "keyword id")
             {:bad-key [:observability stream :sink] :bad-entry entry})))
  ;; rf2-t55hxg.13 — `:rf.egress/profile`, when present, must name a member
  ;; of the closed EP-0015 §10 profile enum (`re-frame.projection/profiles`).
  ;; `projection.cljc`'s `resolve-elision-opts` already throws on an unknown
  ;; profile at egress time, but that is far downstream of registration — a
  ;; typo'd profile would install silently and only blow up when the sink
  ;; first fires. Validate at reg-frame so the enum is closed at the seam
  ;; that owns the policy (fail-closed, parity with the carrier/key grammar).
  (when (contains? entry :rf.egress/profile)
    (let [profile (:rf.egress/profile entry)]
      (when-not (contains? projection/profiles profile)
        (throw (classification-error
                 frame-id
                 (str ":observability " stream " entry has unknown "
                      ":rf.egress/profile " profile "; valid profiles are "
                      projection/profiles)
                 {:bad-key [:observability stream :rf.egress/profile]
                  :bad-value profile
                  :valid    projection/profiles
                  :bad-entry entry})))))
  ;; `:opts`, when present, must be a map (the sink's keyword-keyed option
  ;; bag). A non-map `:opts` is a malformed entry — fail loudly at
  ;; registration rather than handing junk to the sink at fire time.
  (when (contains? entry :opts)
    (let [opts (:opts entry)]
      (when-not (or (nil? opts) (map? opts))
        (throw (classification-error
                 frame-id
                 (str ":observability " stream " entry :opts, when present, "
                      "must be a map")
                 {:bad-key [:observability stream :opts]
                  :bad-value opts
                  :bad-entry entry}))))))

(defn- validate-observability!
  [frame-id observability]
  (when (some? observability)
    (when-not (map? observability)
      (throw (classification-error
               frame-id
               ":observability, when present, must be a map of {:handled-events [..] :errors [..]}"
               {:bad-key :observability :bad-value observability})))
    (doseq [k (keys observability)]
      (when-not (contains? observability-keys k)
        (throw (classification-error
                 frame-id
                 (str "unknown :observability key " k "; valid keys are "
                      observability-keys)
                 {:bad-key [:observability k] :valid observability-keys}))))
    (doseq [stream observability-keys
            :let [entries (get observability stream)]
            :when (some? entries)]
      (when-not (vector? entries)
        (throw (classification-error
                 frame-id
                 (str ":observability " stream ", when present, must be a "
                      "vector of sink entries")
                 {:bad-key [:observability stream] :bad-value entries})))
      (doseq [entry entries]
        (validate-sink-entry! frame-id stream entry)))
    nil))

;; ---- validation + extraction --------------------------------------------

(defn validate+extract
  "Validate the classification keys of a `reg-frame` `config` map and return
  the extracted, normalised classification:

      {:sensitive-app-db [<:rf/path>...]   ;; sensitive-wins: NEVER overlaps
       :large-app-db     [<:rf/path>...]}  ;; a sensitive path (dropped here)

  Throws `:rf.error/bad-frame-classification` (canonical thrown-error shape)
  on ANY defect — malformed path, unknown classification key, non-string
  carrier name — so the failure fires at `reg-frame` time, before any state
  mutates and before `:on-create` runs.

  HTTP carriers and `:observability` are validated for shape here but are
  NOT extracted into the elision-bound result — they ride the frame's
  `:config` verbatim for the HTTP / observability slices to consume.

  Returns nil when `config` carries no classification key (the common case
  — no work, no allocation)."
  [frame-id config]
  (when (some #(contains? config %) classification-keys)
    (let [sensitive     (:sensitive config)
          large         (:large config)
          observability (:observability config)]
      ;; Grammar: the blocks are maps with closed key sets.
      (validate-block-keys! frame-id :sensitive sensitive sensitive-block-keys)
      (validate-block-keys! frame-id :large     large     large-block-keys)
      ;; HTTP carriers (shape only — non-string names fail loudly).
      (validate-http-block! frame-id (:http sensitive))
      ;; Observability sink policy (shape only).
      (validate-observability! frame-id observability)
      ;; App-db paths → canonical :rf/path vectors.
      (let [sens-paths  (normalize-app-db-paths frame-id :sensitive (:app-db sensitive))
            large-paths (normalize-app-db-paths frame-id :large     (:app-db large))
            sens-set    (set sens-paths)
            ;; Sensitive wins over large (EP-0015 §3): a path that is BOTH
            ;; installs as sensitive ONLY — drop it from large so no
            ;; `:rf.size/large-elided` marker (path / bytes / digest /
            ;; handle) can ever leak for it. The walker's
            ;; sensitive-before-large ordering is the runtime complement;
            ;; this is the install-time guarantee.
            large-only  (into [] (remove sens-set) large-paths)]
        {:sensitive-app-db sens-paths
         :large-app-db     large-only}))))

;; ---- install into the durable elision registry --------------------------
;;
;; Frame-owned app-db classification installs into `[:rf.runtime/elision …]`
;; tagged `:source :frame`. Re-registration REPLACES only the `:source :frame`
;; entries; any `:source :marks` entries (the demoted imperative-mark route —
;; `re-frame.marks`, internal / test only) survive untouched, and the sources
;; union at lookup time. The schema→app-db-egress route is GONE (EP-0015 §8 —
;; no `:source :schema` installer feeds this registry; schemas describe shape,
;; not durable app-db egress policy). This mirrors how `re-frame.marks/set-marks`
;; replaces only its own `:source :marks` entries.

(defn- without-frame-sourced
  "Drop `:source :frame` entries from a `{path decl}` declaration map,
  preserving any other-sourced entries (the demoted `:source :marks` route).
  Returns `{}` for nil."
  [decls]
  (reduce-kv (fn [acc p decl]
               (if (= :frame (:source decl))
                 acc
                 (assoc acc p decl)))
             {}
             (or decls {})))

(defn- with-frame-paths
  "Overlay `:source :frame` declarations for `paths` onto the carried (non-
  frame-sourced) declaration map."
  [carried paths]
  (reduce (fn [acc p] (assoc acc (vec p) {:source :frame}))
          carried
          paths))

(defn install!
  "Install a frame's validated classification into its durable elision
  registry, REPLACING any prior `:source :frame` declarations (schema- and
  marks-sourced declarations survive). `classification` is the
  `validate+extract` result (`{:sensitive-app-db [..] :large-app-db [..]}`),
  or nil — a nil / empty classification still runs so a re-registration that
  DROPS its classification clears the prior frame-sourced entries (the
  declaration IS the frame's policy — absent-key clears, per Spec 002
  §Re-registration). Returns nil.

  Writes through `re-frame.elision/swap-elision-slot!` — the shared
  read-transform-write skeleton over the runtime-db elision slot."
  [frame-id classification]
  (let [sens  (:sensitive-app-db classification)
        large (:large-app-db classification)]
    (elision/swap-elision-slot! frame-id
      (fn [reg]
        (let [carry-s (without-frame-sourced (:sensitive-declarations reg))
              carry-l (without-frame-sourced (:declarations reg))
              new-s   (with-frame-paths carry-s sens)
              new-l   (with-frame-paths carry-l large)]
          (cond-> (or reg {})
            (seq new-s)    (assoc :sensitive-declarations new-s)
            (empty? new-s) (dissoc :sensitive-declarations)
            (seq new-l)    (assoc :declarations new-l)
            (empty? new-l) (dissoc :declarations))))))
  nil)

;; ---- frame-local HTTP carrier resolution (EP-0015 §3, HTTP slice) -------
;;
;; The `:sensitive :http {:headers [..] :query-params [..]}` carriers ride
;; the frame's `:config` verbatim (validated above, shape only). The HTTP
;; privacy redactor (`re-frame.http-privacy*`) consults them at trace-emit
;; time as frame-local EXTENSIONS to the immutable built-in carrier denylist
;; (Spec 014 §Privacy). HTTP sits BELOW core in artefact load order, so it
;; reaches this resolver via the `:frame-classification/http-carriers`
;; late-bind hook published at the foot of this ns — never a static require.
;;
;; Carrier names are matched case-insensitively on the wire, so the resolver
;; lower-cases them ONCE here (the redactor lower-cases the incoming header /
;; param name and does a set lookup). The resolver is the single normalisation
;; point so the HTTP layer never re-derives the lower-cased form per emit.

(defn http-carriers
  "Resolve `frame-id`'s frame-local HTTP carrier extension sets from its
  `reg-frame` `:sensitive {:http {:headers [..] :query-params [..]}}` config
  (EP-0015 §3). Returns

      {:headers      #{<lower-cased header name>...}
       :query-params #{<lower-cased query-param name>...}}

  — the EXTENSIONS only (the immutable built-in defaults are owned by the
  HTTP layer and unioned there). Returns `nil` when `frame-id` is nil, the
  frame is unregistered, or the frame declares no `:sensitive :http` block —
  the common case, so the redactor's per-frame lookup allocates nothing when
  a frame carries no carrier policy. Names are lower-cased for the
  case-insensitive wire match. Pure (modulo the registry read)."
  [frame-id]
  (when frame-id
    (when-let [f (frame/frame frame-id)]
      (let [http (get-in f [:config :sensitive :http])]
        (when (some? http)
          (let [->set (fn [names]
                        (when (seq names)
                          (into #{} (map str/lower-case) names)))
                hs (->set (:headers http))
                qs (->set (:query-params http))]
            (when (or hs qs)
              (cond-> {}
                hs (assoc :headers hs)
                qs (assoc :query-params qs)))))))))

(defn install-from-config!
  "The seam `reg-frame` calls: validate `config`'s classification keys
  (fail loud on any defect) and install the app-db paths into `frame-id`'s
  elision registry. Runs atomically as part of frame creation, BEFORE
  `:on-create`. No-op (after validation) when `config` carries no
  classification key. Returns nil.

  Both validation AND install happen here so a malformed declaration throws
  at `reg-frame` time before the frame's container is observable and before
  any `:on-create` cascade runs."
  [frame-id config]
  (when (some #(contains? config %) classification-keys)
    (install! frame-id (validate+extract frame-id config)))
  nil)

;; Published so the frame-registration path (`re-frame.frame/reg-frame`)
;; can reach validation + install without a static require (frame.cljc sits
;; below this ns in the load order — this ns requires elision which requires
;; frame). `re-frame.core` requires this ns at boot, so the hooks are always
;; published before any runtime `reg-frame` call.
;;
;; `reg-frame` splits the two phases to stay transactional: it
;; `validate+extract`s EARLY (pure — fails loud before the frame's container
;; is observable), then `install!`s into the elision slot AFTER the container
;; exists, before `:on-create`. `install-from-config!` is the combined form
;; the re-registration path uses (the container already exists there).
(late-bind/set-fn! :frame-classification/validate+extract    validate+extract)
(late-bind/set-fn! :frame-classification/install!            install!)
(late-bind/set-fn! :frame-classification/install-from-config! install-from-config!)
;; The HTTP privacy redactor reaches frame-local carrier policy through this
;; hook (HTTP sits below core in load order — a static require would not see
;; this ns when the http artefact is absent, and would cycle when present).
(late-bind/set-fn! :frame-classification/http-carriers       http-carriers)
