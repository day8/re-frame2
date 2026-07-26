(ns day8.re-frame2-machines-viz.share
  "Share-URL encoding and decoding for re-frame2 machine charts (v2).

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

  Transit preserves the data types; base64url is URL-safe (no `+` `/`
  `=`). The fragment (`#machine=...`) is not sent in HTTP requests, but
  browser history, extensions, screenshots, and recipients of the complete
  URL can retain it. The structural privacy filter is therefore required.

  ## Versioned envelope

  ```clojure
  {:rf.machines-viz.share/v       \"2\"   ;; encoding version
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
            [re-frame.error :as error]
            [cognitect.transit :as transit]
            [day8.re-frame2-machines-viz.grammar :as grammar]))

;; ---------------------------------------------------------------------------
;; Constants

(def current-version
  "The encoding version emitted by this build, and the newest version
  the decoder accepts. Bumping the payload schema bumps this; older
  decoders refuse newer payloads with `:unknown-version`.

  v2 (EP-0023): `:frame-id` became OPTIONAL — a machine topology +
  active-state configuration is shareable as pure data without naming a
  live frame. v1 always carried a required `:frame-id`; a v2 payload may
  omit it, so a v2 share-URL handed to a v1 decoder is correctly refused
  with `:unknown-version` (the version-bump guard) rather than silently
  mis-decoding."
  "2")

;; THERE IS NO DEFAULT HOST, and that is the contract rather than an
;; omission (rf2-8m344). This var used to read
;; `"https://day8.github.io/re-frame2-machines-viz/viewer.html"`, so
;; `(encode-share-url chart-state)` handed back a link that 404s: no
;; `day8/re-frame2-machines-viz` repository exists — machines-viz ships out
;; of the re-frame2 monorepo — and no workflow deploys the page anywhere.
;; A default that names a host nobody serves is worse than no default,
;; because the resulting URL looks correct and fails only for the person
;; you sent it to.
;;
;; Per DESIGN-RATIONALE Lock #7 the viewer is STATICALLY HOSTABLE and the
;; consumer hosts it. `:host` is therefore the one thing this library
;; cannot know and the caller always can, so it is required — see
;; `encode-share-url`.

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
;; Map keys + set members sort deterministically (by name, then namespace)
;; before serialisation. Canonical chart payloads are stable; complete share
;; URLs still differ because each envelope records its encode-time `:created`.
;; Transit's writer does not guarantee key order, so this layer walks the
;; structure into sorted maps and deterministically ordered sets.

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
;; Structural definition sanitisation (rf2-m285a)
;;
;; `strip-meta` drops Clojure METADATA only. But a macro-stamped machine
;; spec (Spec 005 §Source-coord stamping) carries debug/source as ordinary
;; DATA *values* inside `:states`, NOT as metadata:
;;
;;   - every `:states`-tree map node (state-node / transition map / `:spawn`
;;     map) carries `:source-coords {:ns … :file "…/login.cljs" :line … :column …}`;
;;   - `:guards` / `:actions` / `:on-spawn-actions` entries are
;;     `{<id> {:fn <compiled-fn> :source-coords … :source-code "(fn …)"}}`;
;;   - inline `:guard` / `:action` / `:entry` / `:exit` slots may hold a LIVE
;;     fn value.
;;
;; So `strip-meta` left local-FILESYSTEM paths + source snippets in the
;; payload (a privacy-contract leak — Principles §No session data in shares),
;; and a live `:fn` value can make Transit encoding FAIL outright. We sanitise
;; the definition STRUCTURALLY before validation / Transit:
;;
;;   - recursively DROP `:source-coords` / `:source-code` everywhere;
;;   - DROP executable `:fn` values (the `{<id> {:fn …}}` entry collapses to a
;;     names-only marker — the topology reference by id survives);
;;   - replace any remaining LIVE fn value (an inline `:guard` / `:action` /
;;     `:entry` / `:exit`) with an opaque `:fn` LABEL marker so the viewer-safe
;;     payload encodes (and shows the slot is fn-backed) without serialising a
;;     body Transit cannot encode.

(def ^:private source-debug-keys
  "Reference-site debug fields the macro co-locates inside `:states` /
  `:guards` / `:actions` / `:on-spawn-actions` (Spec 005 §Source-coord
  stamping). Stripped wholesale from a share payload."
  #{:source-coords :source-code})

(def ^:private fn-label-marker
  "rf2-m285a — opaque marker substituted for a LIVE fn value so the payload
  encodes (Transit cannot serialise an arbitrary fn) and the viewer still
  sees the slot is fn-backed. Names-only — no executable body, no source."
  :rf.machines-viz.share/fn)

(defn- fn-name-label
  "rf2-m285a — a names-only label for a fn ref: its `:name` meta when named,
  else the opaque `fn-label-marker`. Never serialises the body."
  [f]
  (or (when-let [n (some-> f meta :name)]
        (keyword "rf.machines-viz.share" (str "fn-" (name n))))
      fn-label-marker))

(defn- sanitise-definition
  "rf2-m285a — recursively rewrite a (possibly macro-stamped) machine
  definition into a viewer-safe topology payload: drop `:source-coords` /
  `:source-code`, drop executable `:fn` values, and replace any live fn slot
  with an opaque label. Preserves all topology references (state ids,
  targets, guard/action NAMES via their map keys). Pure structural walk."
  [x]
  (cond
    (fn? x) (fn-name-label x)

    (map? x)
    (into (empty x)
          (keep (fn [[k v]]
                  (cond
                    ;; Drop reference-site source/debug fields entirely.
                    (contains? source-debug-keys k) nil
                    ;; rf2-07gg7h — drop the EXECUTABLE fn off a co-located
                    ;; `{:fn <fn> …}` entry (the `:guards`/`:actions`/
                    ;; `:on-spawn-actions` slot form): the entry's KEY already
                    ;; carries the name the topology references; the body is
                    ;; lossy by contract. Gate on `(fn? v)` so a topology key
                    ;; that HAPPENS to be named `:fn` (a state id, event id,
                    ;; or region id whose VALUE is a topology submap, never a
                    ;; fn) is PRESERVED — only the executable slot is stripped.
                    (and (= :fn k) (fn? v))          nil
                    :else [k (sanitise-definition v)]))
                x))

    (set? x)    (into #{} (map sanitise-definition x))
    (vector? x) (mapv sanitise-definition x)
    (seq? x)    (map sanitise-definition x)
    :else       x))

;; ---------------------------------------------------------------------------
;; Schema validation — narrow allowlist
;;
;; ChartState:
;;   {:machine-id keyword?
;;    :frame-id   keyword?                          ;; OPTIONAL frame-target id (v2 / EP-0023)
;;    :definition <MachineDefinition>
;;    :snapshot   {:state <state-configuration>}}  ;; OPTIONAL, {:closed true}, :state only
;;
;; `:frame-id` (when present) is an EP-0023 frame-target id — the
;; process-local id of the live frame the machine was registered against,
;; captured at share time purely as payload PROVENANCE. It is decoupled
;; from any (realm, frame) pairing (the pre-EP-0023 model), and the viewer
;; never resolves a frame from it (it hands the chart the `:definition`
;; directly). A topology/snapshot is shareable without naming a live frame,
;; so `:frame-id` is OPTIONAL (v2): a presentation-only chart that does not
;; know its frame shares cleanly without fabricating one.
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

;; The machine-definition SHAPE gate is the canonical Machines-Viz grammar
;; predicate (rf2-3fc89f.18) — `grammar/valid-definition?`, the SAME gate the
;; AI-generate / Mermaid / SCXML emitters + the chart projector share
;; (rf2-egupfk unified them). The share boundary previously kept a PRIVATE,
;; WEAKER copy that accepted any truthy flat `:initial` (even a string) and
;; every non-empty parallel `:regions` map without validating region bodies,
;; so a forged-but-valid-Transit share URL decoded `:ok` and was handed to
;; `MachineChart` even though the definition is rejected everywhere else. The
;; boundary now desugars (`grammar/desugar-grammar`, the SAME lowering the
;; projectors apply — EP-0029 A4/A5) and routes the definition slot through
;; `grammar/valid-definition?` in `valid-core-chart-state?` below, so decode
;; FAILS CLOSED on a malformed definition. Only the definition SHAPE gate is
;; shared with grammar; the closed-map / snapshot / privacy-sanitisation /
;; value-free diagnostics stay LOCAL to share. `grammar` is the machines-viz-
;; local, engine-INDEPENDENT validator (it :requires only clojure.string), so
;; requiring it here does NOT pull the runtime machines artefact into this
;; bundle-isolated tool.

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

(defn- valid-definition?
  "True when `d` is a projectable machine definition. Holds NO shape logic of
  its own: it desugars `d` (`grammar/desugar-grammar` — the SAME EP-0029 A4/A5
  lowering the projectors apply) and delegates the shape check to the canonical
  `grammar/valid-definition?` gate — see the rationale comment above. Desugars
  only to CHECK; the stored payload keeps the AUTHORED form
  (`allowlist-chart-state` never desugars), so an authored `:timeout` /
  `:choice` definition round-trips byte-identically."
  [d]
  (grammar/valid-definition? (grammar/desugar-grammar d)))

(defn- valid-core-chart-state?
  "Validate the load-bearing ChartState slots (`:machine-id`,
  `:definition`). `:frame-id` is OPTIONAL (v2 / EP-0023) — when present
  it must be a keyword (a frame-target id), but a topology/snapshot is
  shareable without naming a live frame. `:snapshot` is NOT checked here
  — see `valid-chart-state?` for the whole-shape check used at BOTH
  encode and decode time."
  [cs]
  (and (map? cs)
       (keyword? (:machine-id cs))
       (or (not (contains? cs :frame-id))
           (keyword? (:frame-id cs)))
       (valid-definition? (:definition cs))))

(def ^:private chart-state-keys
  "The ONLY top-level keys a ChartState may carry (per API.md
  §Share-URL payload schema). `valid-chart-state?` is closed over this
  set on BOTH sides so a hand-edited URL cannot smuggle extra top-level
  keys (`:source-coords`, `:data`, future unreviewed fields) past
  decode. Widening this set requires an explicit schema change, privacy
  review, and corresponding encode/decode tests; there is no runtime bypass."
  #{:machine-id :frame-id :definition :snapshot})

(defn- valid-chart-state?
  "Validate a fully-allowlisted ChartState shape. The top-level map is
  CLOSED over `chart-state-keys` — any extra top-level key (e.g. a
  hand-edited `:source-coords` / `:data` smuggled past the encoder)
  fails validation. `:frame-id` is OPTIONAL (v2 / EP-0023); when present
  it must be a keyword. `:snapshot` is optional; when present it must be
  `{:state <state-configuration>}` EXACTLY (closed; `:state` only).
  Used on BOTH sides so encode/decode stay symmetric — the encoder
  validates the allowlisted chart state with this same predicate before
  serialising, so it never emits a payload the decoder would reject (a
  hand-edited URL adding an extra top-level key, smuggling `:data` onto
  `:snapshot`, or a malformed `:state` that is none of the three arms,
  is rejected at decode; an in-process caller passing the same is
  rejected at encode)."
  [cs]
  (and (valid-core-chart-state? cs)
       (every? chart-state-keys (keys cs))
       (or (not (contains? cs :snapshot))
           (valid-snapshot? (:snapshot cs)))))

(defn- allowlist-chart-state
  "Project `chart-state` onto the ChartState allowlist, dropping every
  key outside it — including any runtime `:data` riding on `:snapshot`
  and any `:source-coords` the caller passed. The definition is
  metadata-stripped AND structurally sanitised here (rf2-m285a): a
  macro-stamped spec carries `:source-coords` / `:source-code` and
  executable `:fn` values as ordinary DATA inside `:states` / `:guards` /
  `:actions` / `:on-spawn-actions` — `strip-meta` (metadata only) does not
  reach them, so `sanitise-definition` recursively drops the source/debug
  fields + executable fns (local-filesystem paths must not leak per
  Principles §No session data in shares, and a live fn would make Transit
  encoding fail)."
  [chart-state]
  (let [{:keys [machine-id frame-id definition snapshot]} chart-state]
    (cond-> {:machine-id machine-id
             :definition (-> definition strip-meta sanitise-definition)}
      ;; :frame-id is OPTIONAL (v2 / EP-0023): include it only when the
      ;; caller supplied one. A presentation-only chart that does not know
      ;; its frame shares cleanly without fabricating a provenance id.
      (some? frame-id)
      (assoc :frame-id frame-id)
      ;; :snapshot is allowlisted to :state ONLY — runtime :data and any
      ;; other snapshot key are dropped here structurally even if the
      ;; caller passed a full snapshot. The :state VALUE (a flat keyword,
      ;; a compound vector-path, or a parallel region-map) is preserved
      ;; intact — it is a state name/address, not runtime data.
      (and (map? snapshot) (contains? snapshot :state))
      (assoc :snapshot {:state (:state snapshot)}))))

;; ---------------------------------------------------------------------------
;; Errors

(defn- value-free-summary
  "EP-0015 / Spec 015 §Author guidance for the exception-path residual —
  a value-FREE diagnostic for a rejected payload `v`. Projection cannot
  walk `ex-data` after the fact, so a thrown error must NOT carry the raw
  payload (a forged share URL can smuggle `:snapshot {:data …}` /
  arbitrary runtime values). We name the SHAPE — type tag + (for maps)
  the key SET — never the values. The reader still recovers the category
  + structural diagnostics; no secret-bearing slot survives."
  [v]
  (cond
    (map? v)  {:type :map  :keys (vec (sort-by str (keys v))) :count (count v)}
    (vector? v) {:type :vector :count (count v)}
    (set? v)  {:type :set :count (count v)}
    (sequential? v) {:type :seq}
    (string? v) {:type :string :length (count v)}
    (keyword? v) {:type :keyword :value v}     ; a keyword IS a state name/address, not data
    (number? v) {:type :number}
    (boolean? v) {:type :boolean}
    (nil? v)  {:type :nil}
    :else     {:type :value}))

(defn- decode-error
  "Throw the documented `:rf.machines-viz.share/decode-failed` ex-info.

  The ex-MESSAGE is the human sentence `msg` + the trailing
  `[:rf.machines-viz.share/decode-failed]` token (Spec 009 §The
  thrown-error shape / rf2-vvixub) — no longer a bare keyword. The
  fine-grained `:reason` keyword is the documented machines-viz
  tool-classification slot (API.md §Share-URL decoding) — tools branch on
  it; `:message` carries the same human sentence for the viewer's
  `{:error {:reason … :message …}}` shape."
  [reason msg & [extra]]
  (throw (ex-info (error/human-message :rf.machines-viz.share/decode-failed msg)
                  (merge {:rf.error/id :rf.machines-viz.share/decode-failed
                          :where       'machines-viz.share/decode-share-url
                          :recovery    :fix-the-share-url
                          :reason      reason
                          :message     msg}
                         extra))))

;; ---------------------------------------------------------------------------
;; Encoder

(defn encode-share-url
  "Encode a `chart-state` map into a share-URL string.

  ```clojure
  (encode-share-url chart-state {:host \"https://acme.example.com/viewer.html\"})
  ;; => \"https://acme.example.com/viewer.html#machine=...\"
  ```

  `:host` is REQUIRED — the absolute URL of the viewer page YOU host (see
  `tools/machines-viz/README.md` §Building and hosting the viewer page).
  There is no default and no Day8-hosted instance; the arity that used to
  supply one emitted a URL that 404s (rf2-8m344). Calling without a
  non-blank `:host` throws `:reason :no-host` rather than guessing.

  `chart-state` is a `ChartState` map (per `API.md` §Share-URL payload
  schema):

  ```clojure
  {:machine-id :auth/login-flow
   :frame-id   :app/main           ;; OPTIONAL (v2 / EP-0023) — a
                                   ;; frame-target id captured as payload
                                   ;; provenance; omit it for a topology
                                   ;; that does not name a live frame
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

  Throws `:rf.machines-viz.share/encode-failed` (an ex-info) with:

  | `:reason`              | Meaning |
  |---|---|
  | `:no-host`             | No non-blank `:host` was supplied. |
  | `:invalid-chart-state` | The chart state does not validate — including a `:snapshot` `:state` that is none of the three allowed configuration arms (a malformed `:state` would yield an undecodable URL, so it is rejected at encode). |"
  [chart-state {:keys [host]}]
  (let [host (when (string? host) (str/trim host))]
    (when (str/blank? host)
      ;; rf2-8m344 — fail loud rather than fabricate. The library cannot
      ;; know where the caller's viewer page is served from, and the
      ;; hosted instance this used to default to never existed.
      (let [msg (str "cannot encode a share-URL: no :host was supplied, and "
                     "there is no default viewer host. A share-URL must name "
                     "the viewer page you host. Build it with `shadow-cljs "
                     "release machines-viz-viewer`, serve viewer.html + "
                     "viewer.js as static files, and pass "
                     "{:host \"https://your.example.com/viewer.html\"}.")]
        (throw (ex-info (error/human-message :rf.machines-viz.share/encode-failed msg)
                        {:rf.error/id :rf.machines-viz.share/encode-failed
                         :where       'machines-viz.share/encode-share-url
                         :recovery    :supply-the-url-of-a-viewer-page-you-host
                         :reason      :no-host
                         :message     msg}))))
    (let [allowlisted (allowlist-chart-state chart-state)]
      (when-not (valid-chart-state? allowlisted)
        ;; rf2-vvixub — the ex-message is the human sentence + the
        ;; [:rf.machines-viz.share/encode-failed] token; the fine `:reason`
        ;; keyword stays the documented tool-classification slot (API.md).
        (let [msg (str "cannot encode a share-URL: the chart state does not "
                       "validate against the share schema (a :machine-id or "
                       ":definition is missing/malformed, :frame-id (optional) "
                       "is non-keyword, or :snapshot :state is malformed). Pass "
                       "a valid ChartState.")]
          (throw (ex-info (error/human-message :rf.machines-viz.share/encode-failed msg)
                          {:rf.error/id :rf.machines-viz.share/encode-failed
                           :where       'machines-viz.share/encode-share-url
                           :recovery    :supply-a-valid-chart-state
                           :reason      :invalid-chart-state
                           :message     msg
                           ;; rf2-8nzxib — value-FREE summary, NOT the raw
                           ;; chart-state (which can carry runtime :data).
                           :chart-state-summary (value-free-summary chart-state)}))))
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

  Throws `:rf.machines-viz.share/decode-failed` (an ex-info). Per the
  thrown-error contract (Spec 009 §The thrown-error shape) the message is
  the human sentence + the `[:rf.machines-viz.share/decode-failed]` token,
  and `:rf.error/id` is the coarse machine discriminator. The fine-grained
  `:reason` keyword below is the documented machines-viz tool-classification
  slot (carried alongside the human `:message` in the safe-decode
  `{:error {:reason … :message …}}` shape):

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
                      ;; rf2-8nzxib — a forged payload's :envelope can carry
                      ;; raw runtime values; report only its value-free shape.
                      {:envelope-summary (value-free-summary envelope)}))
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
                        ;; rf2-8nzxib — a forged :chart can smuggle
                        ;; :snapshot {:data …}; report value-free shape only.
                        {:chart-summary (value-free-summary chart)}))
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
