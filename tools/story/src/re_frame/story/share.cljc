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
  - QR rendering uses an external QR-server image at the share popover;
    avoiding a JS-side QR codec keeps the bundle small and the deps
    surface clean. The renderer (in `re-frame.story.ui.share`) constructs
    an SVG via `https://api.qrserver.com/v1/create-qr-code/?data=...&size=180x180`.
    Hosts on a corporate / offline network see the link fallback.

  ## Bundle isolation

  Share is part of the Story bundle but DCE'd under `:advanced` with
  `:rf.story/enabled?` false (per IMPL-SPEC §6.2). The QR image fetch
  only fires when the user opens the popover; it never loads on shell
  mount."
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

;; ---- QR endpoint --------------------------------------------------------
;;
;; Per rf2-su313 (pragmatic stance, 2026-05-14): the QR rendering is
;; deliberately a third-party fetch (user-triggered, off-canvas-mount).
;; Bundling a JS QR codec would balloon the bundle for the minority of
;; devs who use share popovers; hosts on strict-CSP / offline networks
;; see the URL fallback. The full share URL — variant id + active modes
;; + author-declared `cell-overrides` — is what travels to `api.qrserver.com`;
;; no app-db payload rides through. Documented at the v1 SOTA tier in
;; `tools/story/spec/005-SOTA-Features.md` §Third-party network egress.

(def ^:const qr-endpoint
  "External QR rendering endpoint. The share UI builds an `<img>`
  sourcing this URL with the share-URL embedded as `data=`.

  Hosts that block this endpoint (offline, air-gapped, strict-CSP) see
  the URL fallback; the share popover always shows the bare URL too.

  Per rf2-su313: kept in v1 as an explicit dev-session egress;
  alternatives (bundling a QR codec, self-hosting) would balloon the
  bundle for the minority of devs using share popovers."
  "https://api.qrserver.com/v1/create-qr-code/")

(defn qr-image-url
  "Return the URL of a QR-code image that encodes `share-url`. The
  resulting URL is suitable as the `:src` of an `<img>` tag. Pure data
  → data; JVM-testable.

  `size` is the requested square image size in pixels (default 180)."
  ([share-url] (qr-image-url share-url 180))
  ([share-url size]
   (str qr-endpoint
        "?size=" size "x" size
        "&data=" (url-encode share-url))))
