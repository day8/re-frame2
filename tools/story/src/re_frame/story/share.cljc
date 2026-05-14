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
  (:require [clojure.string :as str]))

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

(defn- ^:no-doc kv-pair
  "Build a `k=v` URL parameter fragment. `k` is a keyword;
  `v-encoded` is the already-percent-encoded value string."
  [k v-encoded]
  (str (url-encode (name k)) "=" v-encoded))

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
  Pure data → data; JVM + CLJS-portable."
  ([variant-id]                (variant-share-url variant-id "" nil))
  ([variant-id opts]           (variant-share-url variant-id "" opts))
  ([variant-id base-url opts]
   (let [params (build-params (assoc opts :variant-id variant-id))
         sep    (cond
                  (str/blank? base-url)        ""
                  (str/includes? base-url "?") "&"
                  :else                        "?")]
     (str base-url sep (str/join "&" params)))))

;; QR rendering lives in re-frame.story.qr (CLJS-only). The vendored
;; `qrcode-generator` npm package produces an inline SVG string locally;
;; the share UI splices it into the popover via React's
;; `:dangerouslySetInnerHTML`. No third-party network fetch fires when
;; the popover opens. Pre-fix (per rf2-20w5i §High) the QR was sourced
;; from a third-party QR-image service with the share URL embedded as
;; a query param, which leaked every author-typed `:cell-overrides`
;; value to that service; local generation removes that egress
;; entirely.
