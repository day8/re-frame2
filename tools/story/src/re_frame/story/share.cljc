(ns re-frame.story.share
  "Per-variant share affordance — sharable URL + QR code. Per
  IMPL-SPEC §2.8.5 + Stage 6 (rf2-zhwd). Phase-2 §5.2 #6.

  Each variant gets a small share button that pops a URL + QR code
  linking to that variant's URL. The URL encodes the active modes and
  any cell-overrides so a scan-and-share session reproduces the exact
  cell the author is looking at.

  ## URL scheme

  Per IMPL-SPEC §2.8.5 + rf2-o4u18 the scheme is:

      <base>?variant=<id>
            &workspace=<id>
            &mode-tab=<id>
            &modes=<list>
            &viewport=<id-or-WxH>
            &background=<id-or-#rrggbb>
            &tag-filter=<list>
            &overrides=<list>
            &substrate=<s>

  - `<id>` for variant / workspace — the id as `:story.foo/bar`
  - `<id>` for mode-tab — one of `dev` / `docs` / `test`
  - `<list>` for modes / tag-filter — comma-separated keyword ids
  - `<list>` for overrides — comma-separated `arg-key:EDN-value` pairs
  - `<id-or-WxH>` for viewport — preset keyword (`tablet`) or
                                  `WxH` custom (e.g. `800x600`)
  - `<id-or-#rrggbb>` for background — preset keyword (`dark`) or a
                                       hex colour string (`#abc123`)
  - `<s>` — substrate id (omitted when `:reagent` default)

  Per rf2-o4u18 the URL is the sharability surface of the testbed: a
  teammate pasting a URL must land on the exact same view (workspace
  + mode-tab + viewport + background + tag-filter + modes + overrides
  + substrate). The chrome's `re-frame.story.ui.url-state` wires
  pushState / popstate against this encoder so back-button + bookmark
  preserves state.

  ## Modules in this ns

  - Pure URL-building (`variant-share-url` and friends) — JVM-testable.
  - QR rendering lives in `re-frame.story.qr` (CLJS-only) and runs
    locally via the vendored `qrcode-generator` npm package. The
    popover splices an inline SVG; no network request fires when the
    share popover opens. Pre-fix (per rf2-20w5i §High) the QR was
    fetched from a third-party QR-image service, which carried every
    author-typed `:cell-overrides` value off-box — local generation
    eliminates that egress entirely.

  ## Bundle isolation

  Share is part of the Story bundle but DCE'd under `:advanced` with
  `:rf.story/enabled?` false (per IMPL-SPEC §6.2). The QR encoder is
  only reachable from the share UI; under disabled builds Closure DCE
  drops both the UI shell and the qrcode-generator wrapper."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---- pure: URL encoding -------------------------------------------------

(defn- ^:no-doc url-encode
  "Percent-encode `s` for use in a URL query parameter. Round-trip-safe
  with the host platform's URL decoder."
  [s]
  #?(:clj  (java.net.URLEncoder/encode (str s) "UTF-8")
     :cljs (js/encodeURIComponent (str s))))

(defn- ^:no-doc kw->str
  "Render a keyword as a stable string `prefix:name` or `name`. Used so
  the URL params don't carry the `:` colon prefix Clojure keywords
  serialise to via `str` — that prefix doesn't survive URL parsing in
  every client."
  [k]
  (if (keyword? k)
    (str (when-let [n (namespace k)] (str n "/")) (name k))
    (str k)))

(defn parse-keyword-token
  "Parse the keyword token emitted by `kw->str`.

  Accepts both the wire form (`\"story.counter/loaded\"`) and the
  printed keyword form (`\":story.counter/loaded\"`) so old manually-
  copied URLs remain usable. Returns nil for blank / malformed input."
  [s]
  (when (string? s)
    (let [trimmed (str/trim s)
          token   (if (str/starts-with? trimmed ":")
                    (subs trimmed 1)
                    trimmed)]
      (when (seq token)
        (if-let [slash (str/index-of token "/")]
          (let [ns-part (subs token 0 slash)
                name-part (subs token (inc slash))]
            (when (and (seq ns-part) (seq name-part))
              (keyword ns-part name-part)))
          (keyword token))))))

(defn parse-modes-param
  "Parse a `modes=` URLSearchParams value into a vector of mode ids.
  The wire shape is the same comma-separated token list `build-params`
  emits."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (->> (str/split s #",")
         (map parse-keyword-token)
         (remove nil?)
         vec)))

(defn parse-substrate-param
  "Parse the optional `substrate=` URLSearchParams value. Returns a
  keyword or nil."
  [s]
  (parse-keyword-token s))

;; ---- rf2-o4u18: workspace / mode-tab / viewport / background / tag-filter

(defn parse-workspace-param
  "Parse the `workspace=` URLSearchParams value as a workspace id.
  Returns a qualified keyword or nil. Same shape as variant ids; the
  caller verifies registration."
  [s]
  (parse-keyword-token s))

(def ^:private mode-tab-tokens
  "Canonical mode-tab tokens emitted on the wire. Mirrors
  `re-frame.story.ui.state/mode-tabs` — kept here as a literal set
  rather than required to avoid a CLJS→CLJC dependency (the canonical
  tabs are stable; if a new tab ships the set is updated here)."
  #{:dev :docs :test})

(defn parse-mode-tab-param
  "Parse the `mode-tab=` URLSearchParams value into one of `:dev` /
  `:docs` / `:test`. Returns nil for unknown values so a stale URL
  degrades to the default tab rather than poisoning the slot."
  [s]
  (when-let [kw (parse-keyword-token s)]
    (when (contains? mode-tab-tokens kw) kw)))

(def ^:private viewport-wxh-re
  "WxH custom viewport on the wire — two positive integers joined by
  a literal `x`. Trailing whitespace is tolerated."
  #"(?i)^\s*(\d+)x(\d+)\s*$")

(defn parse-viewport-param
  "Parse the `viewport=` URLSearchParams value into one of:

  - a preset keyword (`:tablet` / `:desktop` / ...) when the value
    matches a recognised preset token,
  - a `{:width :height}` map when the value is the `WxH` custom shape,
  - nil otherwise.

  Validation of the preset keyword against the actual `viewport/presets`
  table happens at hydrate time — keeping this fn pure (no registrar
  dependency) means JVM tests can round-trip the wire shape without
  pulling the viewport ns in."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (let [trimmed (str/trim s)]
      (if-let [[_ w h] (re-matches viewport-wxh-re trimmed)]
        (try
          (let [wi #?(:clj  (Integer/parseInt w)
                      :cljs (js/parseInt w 10))
                hi #?(:clj  (Integer/parseInt h)
                      :cljs (js/parseInt h 10))]
            (when (and (pos? wi) (pos? hi))
              {:width wi :height hi}))
          (catch #?(:clj Exception :cljs :default) _ nil))
        (parse-keyword-token trimmed)))))

(def ^:private hex-colour-re
  "Hex colour on the wire — 3, 6, or 8 hex digits prefixed with `#`."
  #"^#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

(defn parse-background-param
  "Parse the `background=` URLSearchParams value into one of:

  - a preset keyword (`:dark` / `:light` / ...),
  - a hex colour string (`#abc123`),
  - nil otherwise.

  Like `parse-viewport-param`, preset validation against
  `backgrounds/presets` is the hydrator's job."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (let [trimmed (str/trim s)]
      (if (re-matches hex-colour-re trimmed)
        trimmed
        (parse-keyword-token trimmed)))))

(defn parse-tag-filter-param
  "Parse the `tag-filter=` URLSearchParams value into a set of tag
  keywords. The wire shape is a comma-separated list of `(kw->str)`
  tokens — the same shape `parse-modes-param` consumes. Returns a
  (possibly empty) set, or nil when the input is blank."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (let [tokens (->> (str/split s #",")
                      (map parse-keyword-token)
                      (remove nil?))]
      (when (seq tokens)
        (into #{} tokens)))))

(defn- ^:no-doc read-edn
  [s]
  (try
    [true (edn/read-string s)]
    (catch #?(:clj Exception :cljs :default) _
      [false nil])))

(defn- ^:no-doc parse-override-entry
  [entry]
  (when-let [idx (and (string? entry) (str/index-of entry ":"))]
    (let [k-token (subs entry 0 idx)
          v-token (subs entry (inc idx))
          k       (parse-keyword-token k-token)
          [ok? v] (read-edn v-token)]
      (when (and k ok?)
        [k v]))))

(defn parse-overrides-param
  "Parse an `overrides=` URLSearchParams value into a cell-overrides
  map. The v1 wire shape is a comma-separated list of
  `<arg-token>:<edn-value>` pairs; malformed entries are ignored so a
  stale share URL degrades to the valid subset instead of poisoning the
  shell state."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (not-empty
      (into {}
            (keep parse-override-entry)
            (str/split s #",")))))

(defn parse-overrides-param*
  "Like `parse-overrides-param` but also reports any entries that were
  dropped (malformed token, unparseable EDN, etc.). Returns a map
  `{:overrides ... :dropped [<entry-string> ...]}` — both keys always
  present so callers can pattern-match without an else-branch.

  Powers the share-import hint (rf2-9jthx): the share UI surfaces a
  non-blocking note when N>0 overrides from a stale share URL no
  longer apply (variant args refactored, renamed, removed). Pure;
  JVM-testable. The legacy `parse-overrides-param` keeps its silent-
  drop signature for the param-parsing call sites that don't care
  about the dropped set."
  [s]
  (if (and (string? s) (seq (str/trim s)))
    (let [entries  (str/split s #",")
          parsed   (mapv (fn [e] [e (parse-override-entry e)]) entries)
          kept     (into {} (keep (fn [[_ kv]] kv) parsed))
          dropped  (->> parsed
                        (remove (fn [[_ kv]] kv))
                        (mapv first))]
      {:overrides (not-empty kept)
       :dropped   dropped})
    {:overrides nil :dropped []}))

(defn- ^:no-doc kv-pair
  "Build a `k=v` URL parameter fragment. `k` is a keyword;
  `v-encoded` is the already-percent-encoded value string."
  [k v-encoded]
  (str (url-encode (name k)) "=" v-encoded))

(defn- ^:no-doc split-fragment
  "Split `url` into `[base fragment]`, preserving the fragment without
  its leading `#`. Share links must append query params before the hash
  route so browsers send `variant=` through `location.search` instead
  of burying it inside `location.hash`."
  [url]
  (if-let [idx (str/index-of (or url "") "#")]
    [(subs url 0 idx) (subs url (inc idx))]
    [(or url "") nil]))

(defn- ^:no-doc append-query-params
  "Append already-encoded query `params` to `base-url`, inserting them
  before any hash fragment."
  [base-url params]
  (let [[path fragment] (split-fragment base-url)
        query           (str/join "&" params)
        sep             (cond
                          (str/blank? path)        ""
                          (str/includes? path "?") "&"
                          :else                    "?")
        with-query      (str path sep query)]
    (if (some? fragment)
      (str with-query "#" fragment)
      with-query)))

;; ---- pure: param building -----------------------------------------------

(defn- ^:no-doc encode-viewport
  "Encode a viewport slot value into the wire form. Accepts the same
  shapes the encoder takes off the shell state: a preset keyword or a
  `{:width :height}` custom map. Returns the URL-encoded token (no `&`
  / `=` glue), or nil when the input is unusable."
  [v]
  (cond
    (keyword? v)
    (url-encode (kw->str v))

    (and (map? v) (some? (:width v)) (some? (:height v)))
    (url-encode (str (:width v) "x" (:height v)))

    :else nil))

(defn- ^:no-doc encode-background
  "Encode a background slot value into the wire form. Accepts a preset
  keyword or a colour string. Returns the URL-encoded token, or nil
  when unusable."
  [v]
  (cond
    (keyword? v) (url-encode (kw->str v))
    (string? v)  (url-encode v)
    :else        nil))

(defn build-params
  "Pure: build the ordered vector of `k=v` URL params for a share URL.
  Keys are stable across calls so the QR encoding is deterministic.

  Per rf2-o4u18 the param set covers the full sharability surface:

  - `:variant`    — the focused variant id (when set)
  - `:workspace`  — the focused workspace id (when set)
  - `:mode-tab`   — the focused variant's mode-tab when not the default
                    `:dev`
  - `:modes`      — comma-separated stable list of active mode ids
  - `:viewport`   — chrome-wide viewport selection (preset kw or `WxH`)
  - `:background` — chrome-wide background selection (preset kw or hex)
  - `:tag-filter` — comma-separated stable list of active tag-filter keys
  - `:overrides`  — comma-separated `arg-key:EDN-value` pairs
  - `:substrate`  — substrate id (omitted when `:reagent` default)

  Returns a vector of `k=v` URL fragments. Pure data → data;
  JVM-testable. Slots whose value is empty/nil/default are omitted so
  the URL is the minimal canonical form."
  [{:keys [variant-id workspace-id mode-tab
           active-modes viewport background tag-filter
           cell-overrides substrate]}]
  (cond-> []
    variant-id
    (conj (kv-pair :variant (url-encode (kw->str variant-id))))

    workspace-id
    (conj (kv-pair :workspace (url-encode (kw->str workspace-id))))

    (and mode-tab (contains? mode-tab-tokens mode-tab) (not= mode-tab :dev))
    (conj (kv-pair :mode-tab (url-encode (name mode-tab))))

    (seq active-modes)
    (conj (kv-pair :modes
                   (url-encode
                     (str/join ","
                       (map kw->str (sort-by kw->str active-modes))))))

    (some? (encode-viewport viewport))
    (conj (kv-pair :viewport (encode-viewport viewport)))

    (some? (encode-background background))
    (conj (kv-pair :background (encode-background background)))

    (seq tag-filter)
    (conj (kv-pair :tag-filter
                   (url-encode
                     (str/join ","
                       (map kw->str (sort-by kw->str tag-filter))))))

    (seq cell-overrides)
    (conj (kv-pair :overrides
                   (url-encode
                     (str/join ","
                       (for [[k v] (sort-by (comp kw->str key) cell-overrides)]
                         (str (kw->str k) ":" (pr-str v)))))))

    (and substrate (not= substrate :reagent))
    (conj (kv-pair :substrate (url-encode (name substrate))))))

(defn parse-params
  "Pure inverse of `build-params` — given a `{param-name → string}`
  getter map (e.g. the canonicalised output of `URLSearchParams`), return
  the parsed shape:

      {:variant-id    nil-or-kw
       :workspace-id  nil-or-kw
       :mode-tab      nil-or-kw  (one of :dev :docs :test)
       :active-modes  nil-or-vec
       :viewport      nil-or-kw-or-{:width :height}
       :background    nil-or-kw-or-string
       :tag-filter    nil-or-set
       :cell-overrides nil-or-map  (uses silent-drop `parse-overrides-param`)
       :substrate     nil-or-kw}

  Slot values that fail to parse degrade to nil — the caller decides
  whether to default or skip. Used by the URL-state hydrator and by
  tests for round-trip assertions. Pure data → data; JVM-testable."
  [params]
  (let [g (fn [k] (get params k))]
    {:variant-id     (parse-keyword-token   (g "variant"))
     :workspace-id   (parse-workspace-param (g "workspace"))
     :mode-tab       (parse-mode-tab-param  (g "mode-tab"))
     :active-modes   (parse-modes-param     (g "modes"))
     :viewport       (parse-viewport-param  (g "viewport"))
     :background     (parse-background-param (g "background"))
     :tag-filter     (parse-tag-filter-param (g "tag-filter"))
     :cell-overrides (parse-overrides-param  (g "overrides"))
     :substrate      (parse-substrate-param  (g "substrate"))}))

(defn variant-share-url
  "Build a sharable URL for `variant-id` against `base-url`.

  `opts`:
    :active-modes    coll of registered mode ids
    :cell-overrides  {arg-key → value} runtime overrides
    :substrate       active substrate

  Returns a string of the form
  `<base-url>?variant=<id>&modes=<list>&overrides=<list>&substrate=<s>`.
  Empty / nil parts are omitted (e.g. no modes → no `modes=` param).
  If `base-url` contains a hash route, params are inserted before `#`
  so the query remains available via `window.location.search`.
  Pure data → data; JVM + CLJS-portable."
  ([variant-id]                (variant-share-url variant-id "" nil))
  ([variant-id opts]           (variant-share-url variant-id "" opts))
  ([variant-id base-url opts]
   (append-query-params
     (or base-url "")
     (build-params (assoc opts :variant-id variant-id)))))

;; QR rendering lives in re-frame.story.qr (CLJS-only). The vendored
;; `qrcode-generator` npm package produces an inline SVG string locally;
;; the share UI splices it into the popover via React's
;; `:dangerouslySetInnerHTML`. No third-party network fetch fires when
;; the popover opens. Pre-fix (per rf2-20w5i §High) the QR was sourced
;; from a third-party QR-image service with the share URL embedded as
;; a query param, which leaked every author-typed `:cell-overrides`
;; value to that service; local generation removes that egress
;; entirely.
