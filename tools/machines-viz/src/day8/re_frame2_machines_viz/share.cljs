(ns day8.re-frame2-machines-viz.share
  "Share-URL encode / decode for re-frame2 machine charts
  (rf2-8d7w1 · v1.0).

  A share-URL is a **viewer-side artefact**: its only job is to let a
  remote recipient render a chart from a pasted link, with no running
  app and no trace bus. The payload carries the machine topology plus
  the active state's CONFIGURATION (its name/address) — and nothing
  else. Two classes of data are structurally excluded (per
  `Principles.md` §No session data in shares):

  - **Runtime `:data`** off the snapshot — a machine's `:data` value
    may carry tokens, form contents, or request payloads. The
    `:snapshot` map is `{:closed true}` and carries `:state` only; the
    encoder neither reads nor serialises `:data`. The `:state` value
    is a state CONFIGURATION (the three Spec 005 §Snapshot-shape arms:
    a flat keyword, a compound vector-path, or a parallel region-map);
    vector paths and region-maps are state names/addresses, NOT runtime
    data.
  - **Local-filesystem `:source-coords`** — they reveal usernames /
    workstation layout / repo structure and are useless to a viewer
    with no editor handler wired. The encoder strips definition
    metadata (which carries macro-captured source coords per Spec 001)
    before serialising, and `:source-coords` is not a `ChartState`
    key.

  ## Pipeline (Lock #3, Principles §EDN-first on the wire)

  ```
  chart-state  →  validate + canonicalise  →  envelope wrap
               →  transit-write (json)  →  base64url  →  URL fragment
  ```

  Transit handles the binary-compactness; base64url is URL-safe (no
  `+` `/` `=`); the fragment (`#machine=...`) is never sent in an HTTP
  request, so the chart never traverses a server by accident.

  ## Versioned envelope

  ```clojure
  {:rf.machines-viz.share/v       \"1\"   ;; encoding version
   :rf.machines-viz.share/chart   { ... } ;; the ChartState payload
   :rf.machines-viz.share/created <ms>}   ;; encode-time wall-clock
  ```

  A decoder reading a newer-than-known `:v` refuses to render and
  surfaces `:unknown-version` rather than rendering garbage.

  Per [`API.md`](../../spec/API.md) §Share-URL encoding +
  §Read-only viewer, [`Principles.md`](../../spec/Principles.md), and
  [`DESIGN-RATIONALE.md`](../../spec/DESIGN-RATIONALE.md) Lock #3 +
  Lock #5."
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]))

;; ---------------------------------------------------------------------------
;; Constants

(def current-version
  "The encoding version emitted by this build, and the newest version
  the decoder accepts. Bumping the payload schema bumps this; older
  decoders refuse newer payloads with `:unknown-version`."
  "1")

(def default-host
  "The canonical hosted viewer instance. Per Lock #7 this is a
  convenience, not a contract — every consumer may self-host and pass
  `{:host ...}` to `encode-share-url`."
  "https://day8.github.io/re-frame2-machines-viz/viewer.html")

(def fragment-key
  "The URL-fragment key the viewer reads off `location.hash`."
  "machine")

;; ---------------------------------------------------------------------------
;; base64url — URL-safe alphabet, no padding

(defn- bytes->base64url
  "Encode a UTF-8 string `s` to base64url (RFC 4648 §5): `+`→`-`,
  `/`→`_`, padding `=` stripped. Uses the browser's `btoa` over a
  Latin-1 view of the UTF-8 bytes so multibyte content round-trips."
  [^string s]
  (let [;; transit-json emits ASCII-safe JSON, but be defensive about
        ;; multibyte by routing through encodeURIComponent → unescape
        ;; (the canonical JS UTF-8 → Latin-1 trick btoa needs).
        latin1 (js/unescape (js/encodeURIComponent s))
        b64    (js/btoa latin1)]
    (-> b64
        (str/replace "+" "-")
        (str/replace "/" "_")
        (str/replace "=" ""))))

(defn- base64url->str
  "Inverse of `bytes->base64url`. Throws on invalid base64url input."
  [^string b64url]
  (let [b64     (-> b64url
                    (str/replace "-" "+")
                    (str/replace "_" "/"))
        pad     (mod (count b64) 4)
        padded  (if (zero? pad) b64 (str b64 (apply str (repeat (- 4 pad) "="))))
        latin1  (js/atob padded)]
    (js/decodeURIComponent (js/escape latin1))))

;; ---------------------------------------------------------------------------
;; Canonicalisation — reproducible-from-the-registry-alone
;;
;; Map keys + set members sort deterministically (by name, then
;; namespace) before serialisation so two consumers with the same
;; reg-machine encode byte-for-byte identically (Principles
;; §Reproducible from the registry alone). transit's writer does NOT
;; guarantee key order, so we walk the structure into sorted-maps and
;; sorted vectors-of-set-members ourselves.

(defn- sort-key
  "Total order over EDN map keys / set members: by (name, namespace),
  falling back to `pr-str` for non-named values so the comparator is
  total over heterogeneous keys."
  [k]
  (cond
    (keyword? k) [0 (name k) (or (namespace k) "")]
    (symbol? k)  [1 (name k) (or (namespace k) "")]
    (string? k)  [2 k ""]
    :else        [3 (pr-str k) ""]))

(defn- canonicalise
  "Recursively rewrite `x` so every map becomes a `sorted-map-by`
  `sort-key`, every set becomes a sorted vector tagged for transit
  round-trip via `#{}` reconstruction on decode, and every collection
  is walked. Sets are kept as sets (transit round-trips them) but their
  encode-time iteration order is made deterministic by re-building them
  from a sorted seq — transit emits set members in iteration order, so
  a deterministic order yields a canonical encoding."
  [x]
  (cond
    (map? x)
    (into (sorted-map-by (fn [a b] (compare (sort-key a) (sort-key b))))
          (map (fn [[k v]] [k (canonicalise v)]) x))

    (set? x)
    ;; A PersistentTreeSet (sorted) gives transit a deterministic
    ;; iteration order; decode reads it back as a plain set, and value
    ;; equality with the original (unordered) set holds.
    (apply sorted-set-by
           (fn [a b] (compare (sort-key a) (sort-key b)))
           (map canonicalise x))

    (vector? x)
    (mapv canonicalise x)

    (seq? x)
    (mapv canonicalise x)

    :else
    x))

(defn- strip-meta
  "Recursively drop metadata off `x`. Registered definitions carry
  source-coord meta (Spec 001) that must not propagate into a share
  payload (Principles §No session data in shares)."
  [x]
  (let [bare (cond
               (map? x)    (into (empty x) (map (fn [[k v]] [(strip-meta k) (strip-meta v)]) x))
               (set? x)    (into #{} (map strip-meta x))
               (vector? x) (mapv strip-meta x)
               (seq? x)    (map strip-meta x)
               :else       x)]
    (if (and (satisfies? IMeta bare) (meta bare))
      (with-meta bare nil)
      bare)))

;; ---------------------------------------------------------------------------
;; Schema validation — narrow allowlist
;;
;; ChartState:
;;   {:machine-id keyword?
;;    :frame-id   keyword?
;;    :definition <MachineDefinition>
;;    :snapshot   {:state <state-configuration>}}  ;; OPTIONAL, {:closed true}, :state only
;;
;; The `:snapshot` `:state` is a STATE CONFIGURATION — one of the three
;; Spec 005 §Snapshot-shape arms that `MachineChart` `:current-state`
;; (via `chart.layout/highlight-ids`) already accepts:
;;
;;   - flat keyword       `:idle`
;;   - compound path      `[:auth :authing]`              (vector of keywords)
;;   - parallel region-map `{:data :loading :form :neutral}`
;;                          (region-name keyword → flat-or-compound arm)
;;
;; Vector paths + region-maps are state names/ADDRESSES, not runtime
;; data — they are allowed. Runtime `:data` is NOT.
;;
;; Anything outside the allowlist is silently dropped at encode time;
;; the decoder REJECTS extra keys on :snapshot (the security-relevant
;; closed map) with :invalid-chart-state, but tolerates unknown
;; top-level keys on inbound payloads only insofar as they were never
;; emitted (a hand-edited URL adding :snapshot {:data ...} must fail).
;;
;; Encode + decode validate the SAME `valid-chart-state?` (incl. the
;; snapshot shape) so the two are SYMMETRIC: the encoder never emits a
;; payload the decoder would reject (the rf2-9l8h8 bug was an encoder
;; that accepted compound/parallel snapshots a keyword-only decoder
;; then refused — an undecodable URL).

(defn- valid-definition?
  "A MachineDefinition is either a flat/compound spec (`:initial` +
  non-empty `:states`) or a parallel spec (`:type :parallel` +
  non-empty `:regions`)."
  [d]
  (and (map? d)
       (or (and (:initial d) (map? (:states d)) (seq (:states d)))
           (and (= :parallel (:type d)) (map? (:regions d)) (seq (:regions d))))))

(defn- valid-state-path?
  "A compound `:state` arm: a non-empty vector of keywords naming the
  path from the root state-node to the active leaf (`[:auth :authing]`).
  Mirrors the vector arm `chart.layout/highlight-ids` resolves."
  [v]
  (and (vector? v)
       (seq v)
       (every? keyword? v)))

(defn- valid-state-configuration?
  "A snapshot `:state` value is a STATE CONFIGURATION — one of the three
  Spec 005 §Snapshot-shape arms (the same arms `MachineChart`
  `:current-state` accepts via `chart.layout/highlight-ids`):

  - **flat keyword** — `:idle`.
  - **compound path** — a non-empty vector of keywords (`[:auth :authing]`).
  - **parallel region-map** — a non-empty map of region-name keyword →
    that region's own flat-or-compound arm (keyword or vector path),
    e.g. `{:data :loading :form [:edit :dirty]}`.

  Vector paths + region-maps are state names/addresses, NOT runtime
  data."
  [state]
  (cond
    (keyword? state) true
    (vector? state)  (valid-state-path? state)
    (map? state)     (and (seq state)
                          (every? (fn [[region region-state]]
                                    (and (keyword? region)
                                         (or (keyword? region-state)
                                             (valid-state-path? region-state))))
                                  state))
    :else            false))

(defn- valid-snapshot?
  "A :snapshot (when present) is a closed map carrying :state only,
  where `:state` is a valid STATE CONFIGURATION (one of the three
  Spec 005 §Snapshot-shape arms — see `valid-state-configuration?`)."
  [s]
  (and (map? s)
       (= #{:state} (set (keys s)))
       (valid-state-configuration? (:state s))))

(defn- valid-core-chart-state?
  "Validate the load-bearing ChartState slots (`:machine-id`,
  `:frame-id`, `:definition`). `:snapshot` is NOT checked here — see
  `valid-chart-state?` for the whole-shape check used at BOTH encode and
  decode time."
  [cs]
  (and (map? cs)
       (keyword? (:machine-id cs))
       (keyword? (:frame-id cs))
       (valid-definition? (:definition cs))))

(defn- valid-chart-state?
  "Validate a fully-allowlisted ChartState shape. `:snapshot` is
  optional; when present it must be `{:state <state-configuration>}`
  EXACTLY (closed; `:state` only). Used on BOTH sides so encode/decode
  stay symmetric — the encoder validates the allowlisted chart state
  with this same predicate before serialising, so it never emits a
  payload the decoder would reject (a hand-edited URL smuggling `:data`
  onto `:snapshot`, or a malformed `:state` that is none of the three
  arms, is rejected at decode; an in-process caller passing the same is
  rejected at encode)."
  [cs]
  (and (valid-core-chart-state? cs)
       (or (not (contains? cs :snapshot))
           (valid-snapshot? (:snapshot cs)))))

(defn- allowlist-chart-state
  "Project `chart-state` onto the ChartState allowlist, dropping every
  key outside it — including any runtime `:data` riding on `:snapshot`
  and any `:source-coords` the caller passed. The definition is
  metadata-stripped here (macro-captured source coords must not leak)."
  [chart-state]
  (let [{:keys [machine-id frame-id definition snapshot]} chart-state]
    (cond-> {:machine-id machine-id
             :frame-id   frame-id
             :definition (strip-meta definition)}
      ;; :snapshot is allowlisted to :state ONLY — runtime :data and any
      ;; other snapshot key are dropped here structurally even if the
      ;; caller passed a full snapshot. The :state VALUE (a flat keyword,
      ;; a compound vector-path, or a parallel region-map) is preserved
      ;; intact — it is a state name/address, not runtime data.
      (and (map? snapshot) (contains? snapshot :state))
      (assoc :snapshot {:state (:state snapshot)}))))

;; ---------------------------------------------------------------------------
;; Errors

(defn- decode-error
  "Throw the documented `:rf.machines-viz.share/decode-failed`
  ex-info with a programmatic `:reason`."
  [reason msg & [extra]]
  (throw (ex-info (str ":rf.machines-viz.share/decode-failed — " (name reason))
                  (merge {:rf.error/id :rf.machines-viz.share/decode-failed
                          :where       'machines-viz.share/decode-share-url
                          :recovery    :no-recovery
                          :reason      reason
                          :message     msg}
                         extra))))

;; ---------------------------------------------------------------------------
;; Encoder

(defn encode-share-url
  "Encode a `chart-state` map into a share-URL string.

  ```clojure
  (encode-share-url chart-state)
  ;; => \"https://day8.github.io/re-frame2-machines-viz/viewer.html#machine=...\"

  (encode-share-url chart-state {:host \"https://acme.example.com/viewer.html\"})
  ;; => \"https://acme.example.com/viewer.html#machine=...\"
  ```

  `chart-state` is a `ChartState` map (per `API.md` §Share-URL payload
  schema):

  ```clojure
  {:machine-id :auth/login-flow
   :frame-id   :app/main
   :definition {:initial :idle :states {...}}
   :snapshot   {:state :loading}}   ;; optional; :state ONLY (a state
                                    ;; CONFIGURATION — flat keyword,
                                    ;; compound vector-path, or parallel
                                    ;; region-map)
  ```

  The encoder (1) allowlists `chart-state` (dropping runtime `:data` +
  `:source-coords` + extra `:snapshot` keys) and strips definition
  metadata, (2) VALIDATES the allowlisted result against the SAME
  `valid-chart-state?` the decoder uses — so encode/decode stay
  symmetric and the encoder never emits a payload the decoder would
  reject, (3) canonicalises map / set ordering, (4) wraps in the
  versioned envelope, (5) transit-writes (json) → base64url-encodes,
  (6) wraps the fragment into `:host`.

  Throws `:rf.machines-viz.share/encode-failed` (an ex-info with
  `:reason :invalid-chart-state`) when the chart state does not validate
  — including a `:snapshot` `:state` that is none of the three allowed
  configuration arms (a malformed `:state` would yield an undecodable
  URL, so it is rejected at encode)."
  ([chart-state] (encode-share-url chart-state nil))
  ([chart-state {:keys [host] :or {host default-host}}]
   (let [allowlisted (allowlist-chart-state chart-state)]
     (when-not (valid-chart-state? allowlisted)
       (throw (ex-info ":rf.machines-viz.share/encode-failed — invalid-chart-state"
                       {:rf.error/id :rf.machines-viz.share/encode-failed
                        :where       'machines-viz.share/encode-share-url
                        :recovery    :no-recovery
                        :reason      :invalid-chart-state
                        :chart-state chart-state})))
     (let [envelope    (canonicalise
                         {:rf.machines-viz.share/v       current-version
                          :rf.machines-viz.share/chart   allowlisted
                          :rf.machines-viz.share/created (js/Date.now)})
           writer      (transit/writer :json)
           transit-str (transit/write writer envelope)
           fragment    (bytes->base64url transit-str)]
       (str host "#" fragment-key "=" fragment)))))

;; ---------------------------------------------------------------------------
;; Decoder

(defn- extract-fragment
  "Pull the base64url payload out of a share-URL's `#machine=...`
  fragment. Accepts a full URL or a bare fragment (`#machine=...` /
  `machine=...`). Returns the payload string or nil when the URL is
  not a share-URL."
  [url]
  (when (string? url)
    (let [hash-idx (str/index-of url "#")
          frag     (cond
                     hash-idx                       (subs url (inc hash-idx))
                     (str/starts-with? url (str fragment-key "=")) url
                     :else                          nil)]
      (when frag
        (let [prefix (str fragment-key "=")]
          (when (str/starts-with? frag prefix)
            (subs frag (count prefix))))))))

(defn decode-share-url
  "Decode a share-URL string into the versioned envelope:

  ```clojure
  (decode-share-url url)
  ;; => {:rf.machines-viz.share/v       \"1\"
  ;;     :rf.machines-viz.share/chart   {:machine-id :auth/login-flow ...}
  ;;     :rf.machines-viz.share/created 1736000000000}
  ```

  Throws `:rf.machines-viz.share/decode-failed` (an ex-info) with a
  programmatic `:reason`:

  | `:reason`              | Meaning |
  |---|---|
  | `:malformed-fragment`  | The `#machine=` fragment isn't valid base64url. |
  | `:malformed-payload`   | Decoded bytes aren't valid transit. |
  | `:missing-envelope`    | Missing `:rf.machines-viz.share/v` or `…/chart`. |
  | `:unknown-version`     | `:v` is newer than this decoder knows. |
  | `:invalid-chart-state` | `…/chart` doesn't validate (or carries forbidden keys). |"
  [url]
  (let [fragment (extract-fragment url)]
    (when-not fragment
      (decode-error :malformed-fragment
                    "URL carries no #machine= share fragment"
                    {:url url}))
    (let [transit-str (try
                        (base64url->str fragment)
                        (catch :default e
                          (decode-error :malformed-fragment
                                        "fragment is not valid base64url"
                                        {:cause (.-message e)})))
          envelope    (try
                        (transit/read (transit/reader :json) transit-str)
                        (catch :default e
                          (decode-error :malformed-payload
                                        "decoded bytes are not valid transit"
                                        {:cause (.-message e)})))]
      (when-not (and (map? envelope)
                     (contains? envelope :rf.machines-viz.share/v)
                     (contains? envelope :rf.machines-viz.share/chart))
        (decode-error :missing-envelope
                      "payload missing :rf.machines-viz.share/v or …/chart"
                      {:envelope envelope}))
      (let [v     (:rf.machines-viz.share/v envelope)
            chart (:rf.machines-viz.share/chart envelope)
            ;; Versions are integer-valued strings ("1" at v1.0).
            ;; Compare numerically so "10" > "9" holds; fall back to a
            ;; string compare for any non-integer version.
            v-num (js/parseInt v 10)
            cur-num (js/parseInt current-version 10)
            newer? (if (and (not (js/isNaN v-num)) (not (js/isNaN cur-num)))
                     (> v-num cur-num)
                     (pos? (compare (str v) (str current-version))))]
        (when newer?
          (decode-error :unknown-version
                        "share-URL was produced by a newer Machines-Viz"
                        {:payload-version v :decoder-version current-version}))
        ;; Reject any :snapshot carrying keys beyond :state (a
        ;; hand-edited URL trying to smuggle :data), and validate the
        ;; whole ChartState.
        (when-not (valid-chart-state? chart)
          (decode-error :invalid-chart-state
                        "…/chart does not validate against the ChartState schema"
                        {:chart chart}))
        envelope))))

(defn decode-share-url-safe
  "Like `decode-share-url` but returns `{:ok envelope}` or
  `{:error {:reason … :message …}}` instead of throwing — the shape the
  viewer page reaches for so a malformed link renders a banner rather
  than crashing the mount."
  [url]
  (try
    {:ok (decode-share-url url)}
    (catch :default e
      (let [d (ex-data e)]
        {:error (select-keys d [:reason :message])}))))

(defn chart-state->props
  "Project a decoded envelope's `…/chart` ChartState into the
  `MachineChart` prop map the viewer page mounts (per `API.md`
  §Read-only viewer): `:machine-id` + `:definition` from the payload,
  `:current-state` from `:snapshot`'s `:state` (nil → no highlight),
  and `:read-only? true`. The `:state` value is a state configuration —
  a flat keyword, a compound vector-path, or a parallel region-map — and
  is passed through verbatim; `MachineChart` `:current-state` accepts all
  three arms. `:frame-id` is payload provenance, not a chart prop, so it
  is not threaded onto the props."
  [envelope]
  (let [chart (:rf.machines-viz.share/chart envelope)]
    (cond-> {:machine-id (:machine-id chart)
             :definition (:definition chart)
             :read-only? true}
      (get-in chart [:snapshot :state])
      (assoc :current-state (get-in chart [:snapshot :state])))))
