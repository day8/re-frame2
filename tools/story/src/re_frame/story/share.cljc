(ns re-frame.story.share
  "Per-variant share affordance — sharable URL + QR code. Per
  IMPL-SPEC §2.8.5 + Stage 6 (rf2-zhwd). Phase-2 §5.2 #6.

  Each variant gets a small share button that pops a URL + QR code
  linking to that variant's URL. The URL encodes the active modes and
  any cell-overrides so a scan-and-share session reproduces the exact
  cell the author is looking at.

  ## URL scheme

  Per IMPL-SPEC §2.8.5 the scheme is:

      <base>?variant=<id>&modes=<list>&overrides=<list>&substrate=<s>

  - `<id>` — the variant id as `:story.foo/bar`
  - `<list>` for modes — comma-separated mode ids
  - `<list>` for overrides — comma-separated `arg-key:EDN-value` pairs
  - `<s>` — substrate id (omitted when `:reagent` default)

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

(defn build-params
  "Pure: build the ordered vector of `k=v` URL params for a variant
  share URL. Keys are stable across calls so the QR encoding is
  deterministic.

  - `:variant` — the variant id (always present)
  - `:modes`   — comma-separated stable list of active mode ids (when present)
  - `:overrides` — comma-separated `arg-key:EDN-value` pairs (when present)
  - `:substrate` — substrate id (when not :reagent)

  Returns a vector of `k=v` URL fragments. Pure data → data;
  JVM-testable."
  [{:keys [variant-id active-modes cell-overrides substrate]}]
  (cond-> []
    variant-id
    (conj (kv-pair :variant (url-encode (kw->str variant-id))))

    (seq active-modes)
    (conj (kv-pair :modes
                   (url-encode
                     (str/join ","
                       (map kw->str (sort-by kw->str active-modes))))))

    (seq cell-overrides)
    (conj (kv-pair :overrides
                   (url-encode
                     (str/join ","
                       (for [[k v] (sort-by (comp kw->str key) cell-overrides)]
                         (str (kw->str k) ":" (pr-str v)))))))

    (and substrate (not= substrate :reagent))
    (conj (kv-pair :substrate (url-encode (name substrate))))))

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
