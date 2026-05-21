(ns re-frame.routing.url
  "URL encoding / decoding primitives for re-frame.routing.

  Per Spec 012 §Bidirectional URL ↔ params and §Routing failure
  semantics (rf2-wbvme + rf2-4ic0f). Internal namespace; the public
  facade is `re-frame.routing` — direct consumers should reach for that
  facade. This ns isolates the encode/decode/parse primitives so the
  routing facade can compose them without the implementations
  cluttering its cohesion surface.

  Per the rf2-icrxv cohesion-split audit (Phase-2 Option C): URL seam.")

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
  inverse of url-encode."
  [s]
  #?(:clj  (java.net.URLDecoder/decode (str s) "UTF-8")
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
