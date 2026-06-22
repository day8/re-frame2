(ns re-frame.frame-classification
  "Frame-owned observability sink policy per EP-0015 §9 (Frame-Owned
  Observability Sink Policy), graduated into
  [`spec/015-Data-Classification.md`] and the `reg-frame` grammar in
  [`spec/002-Frames.md`].

  A frame's `reg-frame` metadata MAY carry the `:observability` policy key:

      (rf/reg-frame :app/main
        {:observability
         {:handled-events [{:sink :my-app.sinks/datadog
                            :rf.egress/profile :rf.egress/off-box-observability
                            :opts {:service \"checkout-spa\"}}]
          :errors         [{:sink :my-app.sinks/sentry
                            :rf.egress/profile :rf.egress/off-box-observability}]}

         :initial-events [[:app/init]]})

  ## EP-0025 — the durable app-db classification annotation is REMOVED

  Durable app-db data classification is NO LONGER a `reg-frame` annotation.
  Per [EP-0025 §What is removed], the frame `:sensitive` / `:large {:app-db
  [[path] …]}` durable annotation is GONE — a frame is not app-db's
  definition site. The replacement is the B3 commit-plane classification
  effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`,
  `re-frame.elision/apply-classification-effects`): a `reg-event` handler
  declares the paths it classifies alongside its `:db` write, applied at the
  same commit boundary, tagged `:source :effect` in the SAME per-frame
  elision registry (`[:rf.runtime/elision :sensitive-declarations]` /
  `[:rf.runtime/elision :declarations]`, Conventions §Reserved runtime-db
  keys). The overlap interceptor (`re-frame.privacy`) and the egress walker
  (`re-frame.elision`) read that registry unchanged — only the WRITER moved
  off the frame annotation onto the effect. `reg-flow` output declarations
  (`:source :flow`) and subsystem projection-relative declarations
  (resources / routing) are the other sources; all sources union at lookup.

  ## EP-0025 — the frame `:sensitive {:http …}` HTTP carrier is REMOVED

  HTTP carrier classification (app-specific secret-bearing header /
  query-param NAMES that union onto the immutable built-in denylists) is NO
  LONGER a `reg-frame` annotation either. Per [EP-0025 §HTTP carriers] the
  carrier capability is the TRANSIENT-payload case: it lives on the
  `:rf.http/managed` `reg-fx` registration metadata (the
  `:carriers {:headers […] :query-params …}` block), resolved + unioned onto
  the built-in denylists by the http artefact itself (`re-frame.http.privacy`
  reads `re-frame.registrar/handler-meta :fx :rf.http/managed`). A `reg-frame`
  carrying a `:sensitive` key now FAILS LOUD (clean break) — there is no
  valid `:sensitive` content left on a frame (the durable `:app-db` block was
  already removed, and `:http` moved to `:rf.http/managed`). The diagnostic
  directs the author to the `:rf.http/managed` registration.

  ## What this namespace owns

  This namespace is the frame metadata **schema + registry** for the one
  surviving frame-owned classification key (`:observability`). It is the
  validation seam `reg-frame` calls, atomically as part of frame creation,
  BEFORE `:initial-events` run.

  - **`:observability`** (`:handled-events` / `:errors`) is durable frame
    sink policy, retained verbatim on the frame's `:config`. This slice
    validates its shape (each entry a map naming a `:sink` keyword);
    ROUTING production records through the declared sinks — the EP-0015 §9
    central claim — lives in `re-frame.observability`: the router fires
    `:observability/route-handled-event` once per processed event and
    `error-emit/dispatch-on-error!` fires `:observability/route-error` per
    `:rf.error/*` site, each projecting the record through `project-egress`
    under THIS frame's classification + the entry's `:rf.egress/profile`
    before the declared sink (registered via `register-observability-sink!`)
    sees it. This slice owns the `:config` shape the router reads.

  ## Fail loud at registration

  Per EP-0015 §9 / EP-0025, unknown classification keys, a retired
  `:sensitive` / `:large` frame key, and malformed observability entries
  FAIL LOUDLY at frame registration — BEFORE any state mutates and before
  `:initial-events` run. The thrown ex-info carries the canonical
  thrown-error shape (Spec 009 §The thrown-error shape) with
  `:rf.error/id :rf.error/bad-frame-classification`.

  ## Keyword namespacing (EP-0015 §2)

  The frame-local grammar keys (`:observability`, `:handled-events`,
  `:errors`, `:sink`, `:opts`) stay BARE — a `reg-frame` metadata map is a
  framework-owned grammar. The cross-surface egress key `:rf.egress/profile`
  is namespaced (it means the same thing across `project-egress`, sink
  policy, MCP, SSR, and tool options). User/library-owned sink ids
  (`:my-app.sinks/datadog`) are NOT framework-claimed."
  (:require [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.projection :as projection]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the classification keys --------------------------------------------

(def ^:const classification-keys
  "The frame-owned policy metadata key this ns validates (EP-0015 §9
  observability). A `reg-frame` metadata map carrying it triggers
  validation; every OTHER key is ordinary frame config and is passed
  through untouched.

  EP-0025: durable app-db classification (`:sensitive` / `:large {:app-db
  …}`) is NO LONGER a frame annotation — it moved to the commit-plane
  classification effects (`re-frame.elision`). HTTP carrier classification
  (`:sensitive {:http …}`) is NO LONGER a frame annotation either — it moved
  onto the `:rf.http/managed` `reg-fx` registration (`:carriers` block). So
  the only surviving frame-owned classification key is `:observability`;
  `:sensitive` and `:large` are both retired frame keys that fail loud."
  #{:observability})

(def ^:const retired-frame-keys
  "Retired top-level `reg-frame` classification keys that no longer name a
  frame annotation and FAIL LOUD on sight (EP-0025 clean break). A config
  carrying any of these is rejected before frame registration mutates state.

  - `:large` — durable app-db classification is now the commit-plane effects
    (`re-frame.elision`), so a frame carrying `:large {:app-db …}` is a
    removed-annotation footgun, not inert config.
  - `:sensitive` — the durable `:app-db` block moved to the commit-plane
    effects AND the `:http` carrier block moved onto the `:rf.http/managed`
    `reg-fx` registration (`:carriers`). With no valid content left, the
    whole `:sensitive` frame key is retired; a frame carrying it must not
    silently register and install nothing — fail it loud, directing the
    author to the `:rf.http/managed` registration."
  #{:sensitive :large})

;; ---- fail-loud error shape ----------------------------------------------

(defn- classification-error
  "Build the `:rf.error/bad-frame-classification` ex-info with the canonical
  thrown-error shape (Spec 009 §The thrown-error shape). `reason` is the
  human-facing message; `extras` names the offending slot (`:bad-key`,
  `:bad-carrier`, `:bad-entry`, `:bad-value`)."
  [frame-id reason extras]
  (error/thrown-ex-info
    :rf.error/bad-frame-classification
    'rf/reg-frame
    reason
    {:recovery :fix-frame-classification
     :extra    (merge {:frame frame-id} extras)}))

;; ---- observability sink-policy validation --------------------------------
;;
;; `:observability {:handled-events [<entry>...] :errors [<entry>...]}`.
;; Each entry is a map naming a `:sink` (a keyword sink id) and optionally
;; an `:rf.egress/profile` (a member of the closed EP-0015 §10 profile
;; enum) and an `:opts` map. This slice validates the SHAPE; routing
;; records through the sinks is the EP-0015 observability slice. An unknown
;; top-level `:observability` key fails loudly.

;; ---- retired top-level frame key rejection (EP-0025 clean break) ---------
;;
;; Both `:sensitive` and `:large` are retired `reg-frame` classification keys
;; (EP-0025 §What is removed). The durable app-db `:sensitive` / `:large
;; {:app-db …}` annotations were removed (a frame is not app-db's definition
;; site — durable classification is the commit-plane effects), AND the
;; `:sensitive {:http …}` HTTP carrier block moved onto the `:rf.http/managed`
;; `reg-fx` registration (`:carriers`). With no valid content left on either
;; key, a frame carrying one must NOT silently register and install nothing
;; (a removed-annotation footgun) — reject it fail-loud with the canonical
;; `:rf.error/bad-frame-classification` shape, directing the author to the
;; right home for each.

(defn- retired-frame-key-reason
  "Human-facing diagnostic for a retired frame classification key — directs
  the author to the surviving home (`k` is `:sensitive` or `:large`)."
  [k]
  (case k
    :sensitive
    (str "retired frame key :sensitive (EP-0025). A frame no longer carries a "
         ":sensitive block: durable app-db classification moved to the "
         "commit-plane `:sensitive` / `:large` effects a handler returns "
         "alongside its `:db` write, and HTTP carrier names moved onto the "
         "managed-HTTP `reg-fx` registration metadata (its `:carriers "
         "{:headers […] :query-params …}` block) — see Spec 014 §HTTP "
         "carriers.")
    :large
    (str "retired frame key :large — durable app-db classification is no "
         "longer a frame annotation (EP-0025). Declare it from an event "
         "handler via the `:sensitive` / `:large` commit-plane effects "
         "(alongside `:db`); a frame is not app-db's definition site.")
    (str "retired frame classification key " k " (EP-0025).")))

(defn- reject-retired-frame-keys!
  "Fail loud on any retired top-level `reg-frame` classification key
  (`retired-frame-keys` — `:sensitive` / `:large`):
  `:rf.error/bad-frame-classification`, thrown before frame registration
  mutates state, naming the offending key. No-op when `config` carries no
  retired key (the common case)."
  [frame-id config]
  (doseq [k retired-frame-keys
          :when (contains? config k)]
    (throw (classification-error
             frame-id
             (retired-frame-key-reason k)
             {:bad-key k :bad-value (get config k)}))))

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
  ;; `:rf.egress/profile`, when present, must name a member
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

;; ---- validation seam ----------------------------------------------------

(defn validate!
  "Validate the frame-owned policy keys of a `reg-frame` `config` map — the
  surviving `:observability` sink policy. Throws
  `:rf.error/bad-frame-classification` (canonical thrown-error shape) on ANY
  defect — a retired `:sensitive` / `:large` frame key, an unknown
  observability key, a malformed observability entry — so the failure fires
  at `reg-frame` time, before any state mutates and before `:initial-events`
  run.

  EP-0025: there is no durable app-db classification install here anymore —
  the frame `:sensitive` / `:large {:app-db …}` annotation was removed in
  favour of the commit-plane classification effects, and the `:sensitive
  {:http …}` HTTP carrier block moved onto the `:rf.http/managed` `reg-fx`
  registration (`:carriers`). Both retired frame keys (`:sensitive` /
  `:large`) FAIL LOUD here. `:observability` rides the frame's `:config`
  verbatim for the observability slice to consume — nothing is installed
  into the elision registry from here.

  No-op when `config` carries no policy key and no retired frame key (the
  common case)."
  [frame-id config]
  ;; Retired top-level frame keys (`:sensitive` / `:large`) fail loud
  ;; independently of the surviving-policy trigger — neither is in
  ;; `classification-keys`, so a config carrying ONLY a retired key would
  ;; otherwise never reach validation.
  (reject-retired-frame-keys! frame-id config)
  (when (some #(contains? config %) classification-keys)
    ;; Observability sink policy (shape only).
    (validate-observability! frame-id (:observability config)))
  nil)

;; Published so the frame-registration path (`re-frame.frame/reg-frame`)
;; can reach validation without a static require (frame.cljc sits below this
;; ns in the load order). `re-frame.core` requires this ns at boot, so the
;; hook is always published before any runtime `reg-frame` call.
;;
;; EP-0025: there is no `install!` / `install-from-config!` / `validate+extract`
;; hook anymore — durable app-db classification moved off the frame annotation
;; onto the commit-plane effects, so frame registration only VALIDATES the
;; surviving `:observability` policy (it installs nothing into the elision
;; registry). The `:frame-classification/http-carriers` resolver hook is also
;; GONE — HTTP carrier classification moved onto the `:rf.http/managed`
;; `reg-fx` registration, resolved by the http artefact itself.
(late-bind/set-fn! :frame-classification/validate! validate!)
