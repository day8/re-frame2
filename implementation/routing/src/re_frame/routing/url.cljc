(ns re-frame.routing.url
  "URL encoding / decoding primitives for re-frame.routing.

  Per Spec 012 §Bidirectional URL ↔ params and §Routing failure
  semantics (rf2-wbvme + rf2-4ic0f). Internal namespace; the public
  facade is `re-frame.routing` — direct consumers should reach for that
  facade. This ns isolates the encode/decode/parse primitives so the
  routing facade can compose them without the implementations
  cluttering its cohesion surface.

  Per the rf2-icrxv cohesion-split audit (Phase-2 Option C): URL seam.")

;; ---- trailing-slash normalisation ----------------------------------------
;; Per Spec 012 trailing-slash equivalence: `/cart` and `/cart/` name the
;; same route; root stays `/`. The single shared loop strips trailing
;; slashes while length > 1. Both the author-pattern canonicaliser
;; (`match/canonical-route-pattern`) and the incoming-URL normaliser
;; (`routing/normalize-match-path`) are thin wrappers over this, so the
;; two surfaces cannot drift.

(defn strip-trailing-slashes
  "Strip trailing `/` characters from `s` while its length exceeds 1
  (so `/` itself is preserved). `s` must be a string."
  [^String s]
  (loop [p s]
    (if (and (< 1 (count p))
             (clojure.string/ends-with? p "/"))
      (recur (subs p 0 (dec (count p))))
      p)))

;; ---- url encoding / decoding ---------------------------------------------
;; Per Spec 012 §Bidirectional URL ↔ params. Splat values preserve
;; literal '/' between captured segments, so the splat encoder runs
;; per-segment.

(defn url-encode
  "Encode a single component (named param or query value). Uses
  encodeURIComponent semantics on CLJS; emulates it on JVM (URLEncoder
  + + → %20 swap)."
  [s]
  #?(:clj  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
               (.replace "+" "%20"))
     :cljs (js/encodeURIComponent (str s))))

(defn url-encode-splat
  "Encode a splat value — multi-segment, preserves literal '/'.
  Each segment is encoded individually."
  [s]
  (clojure.string/join "/" (map url-encode (clojure.string/split (str s) #"/"))))

(defn url-decode
  "Decode a percent-encoded string back to its raw form. Round-trip
  inverse of url-encode.

  HOST-SYMMETRIC: `+` stays a LITERAL `+` on BOTH hosts (it is NOT
  swapped to a space). `js/decodeURIComponent` (CLJS) leaves `+`
  untouched; `java.net.URLDecoder/decode` (JVM) is the
  `application/x-www-form-urlencoded` decoder, which would turn a bare
  `+` into a space — the wrong semantics here. We pre-escape every bare
  `+` to `%2B` before handing the string to URLDecoder, so the JVM path
  reproduces `decodeURIComponent` exactly: `+` → `+`, `%2B` → `+`,
  `%20` → space, real spaces → space.

  Per Spec 012 §Bidirectional URL ↔ params §`+` is a literal: re-frame2
  never emits a bare `+` (url-encode swaps `+` → `%20`), the de-facto
  browser reference is `decodeURIComponent` (which is `+`-literal), and
  RFC 3986 treats `+` in path segments as a literal. A host-divergent
  `+` would yield a different `:params` / `:query` slice for the same URL
  on JVM (SSR) vs CLJS (browser) — the exact Spec 011 hydration-mismatch
  class. Making JVM match CLJS closes that gap."
  [s]
  #?(:clj  (-> (str s)
               (.replace "+" "%2B")
               (java.net.URLDecoder/decode "UTF-8"))
     :cljs (js/decodeURIComponent (str s))))

(defn safe-url-decode
  "Wrap `url-decode` in try/catch; returns nil (sentinel for malformed
  `%`) on decode failure.

  Per Spec 012 §Routing failure semantics (rf2-wbvme + rf2-4ic0f): both
  `URLDecoder/decode` (JVM) and `decodeURIComponent` (CLJS) throw on
  malformed percent-encoded sequences (`%`, `%a`, `%XX`, …). Hostile
  URLs, partner integrations with broken escaping, or back-button to a
  malformed link must produce a **route-miss** (404 path), never a
  request-handler crash. Callers propagate the nil sentinel uniformly:
  `match-against` returns nil for the whole match (path captures);
  `match-url`'s query-parse loop short-circuits to a malformed sentinel
  the moment any key or value fails to decode (rf2-4ic0f — the prior
  rf2-wbvme branch dropped just the offending pair, which silently let
  hostile URLs into the routing slice when the host route had no
  required keys); `split-fragment` reports its decode result the same
  way so a malformed `#fragment` also fails closed."
  [s]
  (try (url-decode s)
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn malformed-url?
  "Public predicate: true when `url`'s percent-encoding is malformed in
  any of its decode'd portions — any non-empty path segment, any query
  key or value, or the `#fragment`. Used by `:rf.route/transitioned` /
  `:rf.route/handle-url-change` to discriminate the bare route-miss case
  (`{:url url}`) from the malformed-URL fail-closed case
  (`{:url url :reason :malformed-url}`) — both end up at
  `:rf.route/not-found` but the structured `:reason` lets per-route
  error UIs and SSR projections branch on the cause.

  Per Spec 012 §Routing failure semantics §Malformed percent-encoding
  (rf2-4ic0f). The scan is purely lexical and pattern-agnostic: the URL
  is split into path segments (on `/`), query pairs (on `&`, then `=`),
  and the `#fragment`, and each piece is run through `safe-url-decode`.
  Any piece that won't decode flips the predicate — no route table or
  compiled pattern is consulted, so a bare `%` in any path segment is
  flagged regardless of whether a route would have captured it.

  O(URL-pieces) — runs once per URL-driven nav alongside the regular
  `match-url` walk; the cost is bounded by the URL length, not the
  route-table size."
  [url]
  (let [hash-idx       (.indexOf #?(:clj  ^String url
                                    :cljs ^string url) "#")
        url-no-frag    (if (neg? hash-idx) url (subs url 0 hash-idx))
        fragment       (when (not (neg? hash-idx)) (subs url (inc hash-idx)))
        [path query]   (clojure.string/split url-no-frag #"\?" 2)
        path-bad?      (boolean
                         (when path
                           (some (fn [seg]
                                   (and (seq seg)
                                        (nil? (safe-url-decode seg))))
                                 (clojure.string/split path #"/"))))
        query-bad?     (boolean
                         (when query
                           (some (fn [pair]
                                   (let [[k v] (clojure.string/split pair #"=" 2)]
                                     (or (nil? (safe-url-decode k))
                                         (and v (nil? (safe-url-decode v))))))
                                 (clojure.string/split query #"&"))))
        fragment-bad?  (boolean
                         (when (and fragment (seq fragment))
                           (nil? (safe-url-decode fragment))))]
    (or path-bad? query-bad? fragment-bad?)))

;; ---- open-redirect classifier (rf2-3bv8o + rf2-cylse.4) ------------------
;; The shared lexical/origin gate that classifies a URL-string nav target
;; as same-origin in-app vs external. Hoisted here from
;; `re-frame.routing.can-leave` (rf2-cylse.4) so EVERY url-string nav sink
;; — the gated `:rf.route/url-requested` link-click path AND the previously
;; ungated `:rf.route/navigate {:url ...}` programmatic path — fails closed
;; through ONE classifier rather than each entry point re-deciding (and
;; only one of three being wired). Per Spec 012 §Target form — URL-string:
;; the `{:url ...}` escape hatch is precisely for untrusted input
;; (deep-link handlers, server-redirect targets), the same class
;; `safe-in-app-url?` exists to gate.

(defn safe-in-app-url?
  "Fail-CLOSED lexical classifier for the no-browser-origin path (JVM /
  SSR, and CLJS when `js/window` is unavailable). Returns true ONLY when
  `url` is PROVABLY a same-origin in-app reference; everything ambiguous
  or absolute returns false -> classed external (rf2-3bv8o).

  Without a browser `Location` to resolve and origin-compare against
  (the robust CLJS path in `external-url?`), the runtime cannot prove a
  URL is same-origin. The pre-rf2-3bv8o JVM path was fail-OPEN: it
  returned the URL verbatim and classed in-app anything that did not
  lexically look absolute -- a default-ALLOW that let open-redirect
  bypass vectors (leading whitespace before a scheme, backslash-prefixed
  authorities a browser normalises to `//`, embedded tab/newline in the
  scheme) slip through as in-app pushes. This flips to fail-CLOSED,
  consistent with the routing fail-closed posture established by
  rf2-6t1xb (a hostile or ambiguous URL resolves to the safe outcome,
  not the permissive one).

  A URL is accepted as in-app only when, after rejecting any whitespace
  or ASCII control character (browsers strip tab/CR/LF mid-URL before
  parsing -- a lexical check that ignores them is bypassable), it begins
  with a single `/` NOT followed by `/` or a backslash (a rooted
  same-origin path) -- OR is a pure query (`?...`) / fragment (`#...`)
  reference. A leading `//` or `/\\` (protocol-relative authority a
  browser reads as off-origin), a scheme (`name:`), a bare relative
  segment, the empty string, and any non-string all fail closed."
  [url]
  (boolean
    (and (string? url)
         (seq url)
         ;; Reject any whitespace or ASCII control char (incl. DEL)
         ;; anywhere: a leading space defeats a `^` scheme anchor, and
         ;; embedded tab/CR/LF are stripped by browsers before parsing --
         ;; a lexical check that ignores them is a bypass surface. The
         ;; `\s` + hex-range class is identical under Java (CLJ) and
         ;; JS (CLJS) regex.
         (not (re-find #"[\s\x00-\x1f\x7f]" url))
         (or
           ;; Pure query or fragment reference -- unambiguously same-doc.
           (clojure.string/starts-with? url "?")
           (clojure.string/starts-with? url "#")
           ;; Rooted path: a single leading `/` NOT followed by `/` or
           ;; `\` (which a browser would treat as a protocol-relative
           ;; authority -> off-origin).
           (and (clojure.string/starts-with? url "/")
                (not (clojure.string/starts-with? url "//"))
                (not (clojure.string/starts-with? url "/\\")))))))

(defn external-url?
  "Classify `url` as external (off-origin / non-http(s) / unsafe) vs an
  in-app same-origin reference. On CLJS with a live `js/window.location`
  the URL is resolved against the document origin and compared (the
  robust path); otherwise (JVM / SSR, or any resolution failure) it falls
  back to the fail-closed lexical `safe-in-app-url?`. rf2-3bv8o +
  rf2-cylse.4."
  [url]
  #?(:cljs
     (try
       ;; rf2-w3qgc: fail closed (classify EXTERNAL) for any non-string or
       ;; empty-string `url` BEFORE handing it to `js/URL`. JavaScript
       ;; stringifies non-strings — `new URL(null, base)` resolves to
       ;; `/null`, numbers to `/123`, objects via `toString` — so a nil /
       ;; number / boolean / object could otherwise resolve same-origin and
       ;; push a fabricated in-app URL. This is an untrusted-URL sink; the
       ;; JVM / no-window fallback already fails closed via
       ;; `safe-in-app-url?` (which rejects any non-string), so guarding
       ;; here removes the host divergence and keeps the contract uniform.
       (if-not (and (string? url) (seq url))
         true
         (if (and (exists? js/window) (.-location js/window))
           (let [loc      (.-location js/window)
                 parsed   (js/URL. url (.-href loc))
                 protocol (.-protocol parsed)]
             (or (not (#{"http:" "https:"} protocol))
                 (not= (.-origin parsed) (.-origin loc))))
           ;; No browser Location to origin-compare against — fail closed.
           (not (safe-in-app-url? url))))
       (catch :default _
         (not (safe-in-app-url? url))))
     :clj
     ;; JVM / SSR: no browser origin — fail closed (rf2-3bv8o).
     (not (safe-in-app-url? url))))

(defn request-url->app-url
  "Normalise an in-app `url` to its origin-relative form
  (`pathname + search + hash`) on CLJS when a browser Location is
  available and the URL is not external; otherwise return `url`
  unchanged. Callers must have already confirmed the URL is in-app via
  `external-url?` — this only canonicalises, it does NOT gate."
  [url]
  #?(:cljs
     (try
       (if (and (exists? js/window) (.-location js/window)
                (not (external-url? url)))
         (let [parsed (js/URL. url (.-href (.-location js/window)))]
           (str (.-pathname parsed) (.-search parsed) (.-hash parsed)))
         url)
       (catch :default _ url))
     :clj
     url))
