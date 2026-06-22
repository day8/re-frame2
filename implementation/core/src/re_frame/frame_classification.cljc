(ns re-frame.frame-classification
  "Frame-owned HTTP-carrier policy + observability sink policy per EP-0015
  §3 (HTTP carriers) + §9 (Frame-Owned Observability Sink Policy), graduated
  into [`spec/015-Data-Classification.md`] and the `reg-frame` grammar in
  [`spec/002-Frames.md`].

  A frame's `reg-frame` metadata MAY carry two policy keys:

      (rf/reg-frame :app/main
        {:sensitive
         {:http   {:headers      [\"X-Honeycomb-Team\"]
                   :query-params [\"shop_token\"]}}

         :observability
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

  ## What this namespace owns

  This namespace is the frame metadata **schema + registry** for the two
  surviving classification keys. It is the validation seam `reg-frame`
  calls, atomically as part of frame creation, BEFORE `:initial-events` run.

  - **`:sensitive :http :headers` / `:query-params`** are frame-local
    EXTENSIONS to the immutable built-in HTTP carrier denylist (Spec 014
    §Privacy). They are durable frame config facts — retained verbatim on
    the frame's `:config` (so `frame-meta` surfaces them and the HTTP
    layer can read them) — NOT elision-walker path declarations. This slice
    validates them (non-string carrier names fail loudly) and keeps them on
    the config; the `http-carriers` resolver (this ns) lowers them to
    lower-cased extension sets the HTTP privacy redactor unions onto the
    immutable built-in carrier denylist at trace-emit time, reached via the
    `:frame-classification/http-carriers` late-bind hook (EP-0015 §3, HTTP
    carriers).

    `:headers` is vector-only — the header denylist is immutable (a
    default-off header would be a real leak). `:query-params` additionally
    accepts a `{:include [..] :except [..]}` policy map:
    `:include` extends the defaults (as the plain vector form does), and
    `:except` SUBTRACTS denylisted names from the built-in defaults for
    THIS frame's own dev trace — effective policy `(defaults − except) ∪
    include`. All redaction is debug-gated trace surface (elided in
    production), so `:except` only relaxes dev-trace friction over a
    harmless routing/pagination key; the query defaults stay on-by-default
    and subtractable per name. A name in both `:include` and `:except`
    stays sensitive (`:include` wins).

  - **`:observability`** (`:handled-events` / `:errors`) is durable frame
    sink policy, likewise retained on the frame's `:config`. This slice
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

  Per EP-0015 §3, unknown classification keys and non-string HTTP carrier
  names FAIL LOUDLY at frame registration — BEFORE any state mutates and
  before `:initial-events` run. The thrown ex-info carries the canonical
  thrown-error shape (Spec 009 §The thrown-error shape) with
  `:rf.error/id :rf.error/bad-frame-classification`.

  ## Keyword namespacing (EP-0015 §2)

  The frame-local grammar keys (`:sensitive`, `:observability`, `:http`,
  `:headers`, `:query-params`, `:handled-events`, `:errors`, `:sink`,
  `:opts`) stay BARE — a `reg-frame` metadata map is a framework-owned
  grammar. The cross-surface egress key `:rf.egress/profile` is namespaced
  (it means the same thing across `project-egress`, sink policy, MCP, SSR,
  and tool options). User/library-owned sink ids (`:my-app.sinks/datadog`)
  are NOT framework-claimed."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.projection :as projection]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the classification keys --------------------------------------------

(def ^:const classification-keys
  "The two frame-owned policy metadata keys this ns validates (EP-0015 §3
  HTTP carriers + §9 observability). A `reg-frame` metadata map carrying
  either triggers validation; every OTHER key is ordinary frame config and
  is passed through untouched.

  EP-0025: durable app-db classification (`:sensitive` / `:large {:app-db
  …}`) is NO LONGER a frame annotation — it moved to the commit-plane
  classification effects (`re-frame.elision`). `:sensitive` survives ONLY
  for its `:http` carrier block; `:large` is no longer a frame key."
  #{:sensitive :observability})

(def ^:const retired-frame-keys
  "Retired top-level `reg-frame` classification keys that no longer name a
  frame annotation and FAIL LOUD on sight (EP-0025 clean break). A config
  carrying any of these is rejected before frame registration mutates state —
  the symmetric guard to the `:sensitive {:app-db …}` rejection (a retired
  `:sensitive` SUB-key) for the retired top-level `:large` frame key. Durable
  app-db classification is now the commit-plane effects (`re-frame.elision`),
  so a frame carrying `:large {:app-db …}` is a removed-annotation footgun,
  not inert config — fail it loud, exactly like `:sensitive {:app-db …}`."
  #{:large})

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

;; ---- HTTP carrier validation --------------------------------------------
;;
;; `:sensitive :http {:headers [..] :query-params [..]}` are frame-local
;; extensions to the immutable framework HTTP carrier denylist (Spec 014
;; §Privacy). Carrier names MUST be strings (header / query-param names are
;; strings on the wire); a non-string name fails loudly.

(defn- validate-carrier-name-vector!
  "Validate a vector of carrier names — each must be a string; a non-string
  name fails loudly. `bad-key` locates the slot for the error message.
  Returns the (unchanged) vector."
  [frame-id carrier-key bad-key names]
  (when-not (vector? names)
    (throw (classification-error
             frame-id
             (str ":sensitive :http " carrier-key ", when present, must be a "
                  "vector of carrier-name strings")
             {:bad-key bad-key :bad-value names})))
  (doseq [n names]
    (when-not (string? n)
      (throw (classification-error
               frame-id
               (str ":sensitive :http " carrier-key " names must be "
                    "strings (header / query-param names are strings)")
               {:bad-key bad-key :bad-carrier n}))))
  names)

(def ^:private query-param-policy-keys #{:include :except})

(defn- validate-carriers!
  "Validate one HTTP carrier slot (`:headers` or `:query-params`).

  `:headers` accepts only a vector of carrier-name strings — the header
  denylist is immutable (no `:except` subtraction; a default-off header
  would be a real leak).

  `:query-params` accepts EITHER:
   - a vector of carrier-name strings — the include-only form, OR
   - a map `{:include [..] :except [..]}` — `:include` extends the defaults,
     `:except` subtracts from the built-in defaults for this frame's own dev
     trace. Both keys are optional; each (when present) is a vector of
     strings; an unknown key fails loudly (closed grammar).

  Each carrier name must be a string; a non-string carrier fails loudly.
  Returns the (unchanged) value, or `nil`."
  [frame-id carrier-key names]
  (cond
    (nil? names) nil

    ;; query-params may carry a {:include :except} policy map.
    (and (= :query-params carrier-key) (map? names))
    (do
      (doseq [k (keys names)]
        (when-not (contains? query-param-policy-keys k)
          (throw (classification-error
                   frame-id
                   (str "unknown :sensitive :http :query-params key " k
                        "; valid keys are :include and :except")
                   {:bad-key [:sensitive :http :query-params k]
                    :valid   query-param-policy-keys}))))
      (when-some [inc (:include names)]
        (validate-carrier-name-vector!
          frame-id :query-params [:sensitive :http :query-params :include] inc))
      (when-some [exc (:except names)]
        (validate-carrier-name-vector!
          frame-id :query-params [:sensitive :http :query-params :except] exc))
      names)

    :else
    (validate-carrier-name-vector!
      frame-id carrier-key [:sensitive :http carrier-key] names)))

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

;; ---- :sensitive block grammar validation --------------------------------
;;
;; EP-0025: the `:sensitive` block is now an HTTP-carriers-only block — the
;; durable `:app-db` path declaration moved to the commit-plane effects, and
;; `:large` is no longer a frame key. A `:sensitive` block may carry only
;; `:http`; any other key (including the retired `:app-db`) fails loudly.

(def ^:private sensitive-block-keys #{:http})

(defn- validate-sensitive-block-keys!
  [frame-id block]
  (when (some? block)
    (when-not (map? block)
      (throw (classification-error
               frame-id
               (str ":sensitive, when present, must be a map "
                    "(e.g. {:http {:headers [..]}})")
               {:bad-key :sensitive :bad-value block})))
    (doseq [k (keys block)]
      (when-not (contains? sensitive-block-keys k)
        (throw (classification-error
                 frame-id
                 (str "unknown :sensitive key " k "; the only valid :sensitive "
                      "key is :http. Durable app-db classification is no longer "
                      "a frame annotation (EP-0025) — declare it from an event "
                      "handler via the `:sensitive` / `:large` commit-plane "
                      "effects (alongside `:db`).")
                 {:bad-key [:sensitive k] :valid sensitive-block-keys}))))))

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
;; The durable app-db `:large {:app-db …}` annotation was REMOVED with the
;; rest of the frame egress-policy annotation (EP-0025 §What is removed) — a
;; frame is not app-db's definition site. `:sensitive {:app-db …}` already
;; fails loud (a retired SUB-key, caught by `validate-sensitive-block-keys!`);
;; `:large` is a retired TOP-LEVEL key, so it needs its own symmetric guard —
;; otherwise a frame carrying `:large {:app-db …}` registers and silently
;; installs nothing, a removed-annotation footgun. Reject it fail-loud, with
;; the same `:rf.error/bad-frame-classification` shape the `:sensitive`
;; rejection raises.

(defn- reject-retired-frame-keys!
  "Fail loud on any retired top-level `reg-frame` classification key
  (`retired-frame-keys`, currently `:large`). Mirrors the
  `:sensitive {:app-db …}` rejection: `:rf.error/bad-frame-classification`,
  thrown before frame registration mutates state, naming the offending key.
  No-op when `config` carries no retired key (the common case)."
  [frame-id config]
  (doseq [k retired-frame-keys
          :when (contains? config k)]
    (throw (classification-error
             frame-id
             (str "retired frame key " k " — durable app-db classification is "
                  "no longer a frame annotation (EP-0025). Declare it from an "
                  "event handler via the `:sensitive` / `:large` commit-plane "
                  "effects (alongside `:db`); a frame is not app-db's "
                  "definition site.")
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
  "Validate the frame-owned policy keys of a `reg-frame` `config` map —
  the `:sensitive {:http …}` HTTP carriers and the `:observability` sink
  policy. Throws `:rf.error/bad-frame-classification` (canonical thrown-error
  shape) on ANY defect — unknown classification key, non-string carrier name,
  malformed observability entry — so the failure fires at `reg-frame` time,
  before any state mutates and before `:initial-events` run.

  EP-0025: there is no durable app-db classification install here anymore —
  the frame `:sensitive` / `:large {:app-db …}` annotation was removed in
  favour of the commit-plane classification effects. The retired `:app-db`
  key inside `:sensitive` (a retired SUB-key) and the retired top-level
  `:large` frame key BOTH fail loud — `:sensitive {:app-db …}` through the
  closed-grammar key check, `:large` through the symmetric retired-key
  rejection. HTTP carriers and `:observability` ride the frame's `:config`
  verbatim for the HTTP / observability slices to consume — nothing is
  installed into the elision registry from here.

  No-op when `config` carries no policy key and no retired frame key (the
  common case)."
  [frame-id config]
  ;; Retired top-level frame keys (`:large`) fail loud independently of the
  ;; surviving-policy trigger — `:large` is not in `classification-keys`, so a
  ;; config carrying ONLY `:large` would otherwise never reach validation.
  (reject-retired-frame-keys! frame-id config)
  (when (some #(contains? config %) classification-keys)
    (let [sensitive     (:sensitive config)
          observability (:observability config)]
      ;; Grammar: `:sensitive` is an HTTP-carriers-only block (closed key set);
      ;; a retired `:app-db` SUB-key fails loud here.
      (validate-sensitive-block-keys! frame-id sensitive)
      ;; HTTP carriers (shape only — non-string names fail loudly).
      (validate-http-block! frame-id (:http sensitive))
      ;; Observability sink policy (shape only).
      (validate-observability! frame-id observability)))
  nil)

;; ---- frame-local HTTP carrier resolution (EP-0015 §3, HTTP slice) -------
;;
;; The `:sensitive :http {:headers [..] :query-params [..]}` carriers ride
;; the frame's `:config` verbatim (validated above, shape only). The HTTP
;; privacy redactor (`re-frame.http.privacy*`) consults them at trace-emit
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
  "Resolve `frame-id`'s frame-local HTTP carrier policy from its `reg-frame`
  `:sensitive {:http {:headers [..] :query-params ..}}` config (EP-0015 §3).
  Returns

      {:headers      #{<lower-cased header name>...}
       :query-params <query-param policy>}

  where the `:query-params` policy is:
   - a `#{<lower-cased name>...}` SET for the include-only vector form
     (`:query-params [\"shop_token\"]`) — names EXTEND the built-in defaults; OR
   - a `{:include #{..} :except #{..}}` MAP for the `{:include [..] :except [..]}`
     form — `:include` extends the defaults, `:except` subtracts from them for
     this frame's own dev trace (`(defaults − except) ∪ include`). Empty
     include/except sub-sets are dropped; a policy that resolves to nothing
     (e.g. all-empty vectors) yields `nil` for `:query-params`.

  `:headers` are EXTENSIONS only (the immutable built-in defaults are owned by
  the HTTP layer and unioned there). Returns `nil` when `frame-id` is nil, the
  frame is unregistered, or the frame declares no `:sensitive :http` block —
  the common case, so the redactor's per-frame lookup allocates nothing when a
  frame carries no carrier policy. Names are lower-cased for the
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
                raw-qs (:query-params http)
                qs (if (map? raw-qs)
                     ;; {:include :except} policy map — lower-case both sub-sets,
                     ;; drop empties, and collapse to nil when nothing remains.
                     (let [inc (->set (:include raw-qs))
                           exc (->set (:except raw-qs))]
                       (when (or inc exc)
                         (cond-> {}
                           inc (assoc :include inc)
                           exc (assoc :except exc))))
                     ;; include-only vector → plain extension set
                     (->set raw-qs))]
            (when (or hs qs)
              (cond-> {}
                hs (assoc :headers hs)
                qs (assoc :query-params qs)))))))))

;; Published so the frame-registration path (`re-frame.frame/reg-frame`)
;; can reach validation without a static require (frame.cljc sits below this
;; ns in the load order — this ns requires frame). `re-frame.core` requires
;; this ns at boot, so the hook is always published before any runtime
;; `reg-frame` call.
;;
;; EP-0025: there is no `install!` / `install-from-config!` / `validate+extract`
;; hook anymore — durable app-db classification moved off the frame annotation
;; onto the commit-plane effects, so frame registration only VALIDATES the
;; surviving HTTP-carrier + observability policy (it installs nothing into the
;; elision registry).
(late-bind/set-fn! :frame-classification/validate! validate!)
;; The HTTP privacy redactor reaches frame-local carrier policy through this
;; hook (HTTP sits below core in load order — a static require would not see
;; this ns when the http artefact is absent, and would cycle when present).
(late-bind/set-fn! :frame-classification/http-carriers http-carriers)
