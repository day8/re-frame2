(ns re-frame.util-json
  "Tiny shared JSON helpers.

  Extracted from `re-frame.http-managed` per rf2-p7da. The decode path
  in `re-frame.http-encoding` needs a JSON reader; on CLJS that is the
  browser's `js/JSON.parse`, on the JVM it is Cheshire when available
  with a pure-Clojure fallback so the http artefact does not force a
  Cheshire dependency.

  ## Why this lives in the http artefact for now

  The reader is only used by the decode pipeline. Re-frame core has no
  business reading JSON. Keeping it in the http artefact (rather than
  promoting it to `day8/re-frame2`) avoids dragging the
  `json-stringify` / `json-read*` codepaths onto core consumers that
  never issue an HTTP request. If a second consumer needs JSON down the
  track, lift this namespace to core (the ns name is already neutral —
  `re-frame.util-json`, not `re-frame.http.util-json`).

  ## API

  - `json-stringify` — Clojure → JSON string. Uses Cheshire's
    `generate-string` on JVM if it's on the classpath, otherwise
    falls back to `pr-str` (good enough for the request bodies the
    `:rf.http/managed` fx emits). CLJS uses `js/JSON.stringify`.
  - `json-parse`     — string → Clojure data with keyword keys for
    objects. Uses Cheshire's `parse-string` on JVM if available,
    otherwise the pure-Clojure fallback below; CLJS uses
    `js/JSON.parse` + `js->clj :keywordize-keys true`. Accepts an
    optional `opts` map (`:max-decoded-keys` — per rf2-wu1n5).

  ## Keyword-interning cap (rf2-wu1n5)

  Per security audit 2026-05-14 §P1.4: JVM keywords are interned and
  never GC'd. An attacker-controlled JSON response with N unique keys
  permanently burns N keyword slots — a long-running SSR JVM is the
  worst case. Both readers enforce a per-call cap on the number of
  unique keys decoded (default `*default-max-decoded-keys*` = 10000;
  overridable per request via `(json-parse s {:max-decoded-keys N})`).
  Overflow throws `:rf.error/malformed-json :reason :too-many-keys` —
  the `:rf.http/managed` cascade classifies this as
  `:rf.http/decode-failure`."
  #?(:clj  (:require [clojure.string :as str])
     :cljs (:require [clojure.string :as str])))

(def ^:const default-max-decoded-keys
  "Default cap on the number of unique object keys a single `json-parse`
  call may decode. Per rf2-wu1n5 — a defensive ceiling against the
  keyword-interning DoS surface on long-running JVMs. Generous enough
  not to false-positive on legitimate large responses while finite
  enough to bound an attacker-controlled payload."
  10000)

#?(:clj
   (defn- json-read*
     "Tiny pure-Clojure JSON reader. Returns Clojure data with keyword
     keys for objects. Sufficient for the on-the-wire shapes :rf.http/managed
     decodes; not a full RFC-8259 parser. Used only when Cheshire isn't
     on the classpath.

     Each helper returns [value next-p]: the cursor is threaded through
     return values rather than carried in a mutable atom.

     `max-keys` (rf2-wu1n5) caps the number of unique object keys
     decoded across the whole call. The fallback reader tracks distinct
     keys in a HashSet stamped onto a closure-local `seen-keys` atom;
     on overflow the reader throws
     `:rf.error/malformed-json :reason :too-many-keys` so the
     `:rf.http/managed` cascade can classify as
     `:rf.http/decode-failure`."
     [^String s max-keys]
     (let [n          (count s)
           seen-keys  (java.util.HashSet.)
           note-key!  (fn [k]
                        (when-not (.contains seen-keys k)
                          (.add seen-keys k)
                          (when (> (.size seen-keys) (long max-keys))
                            (throw (ex-info "json: too many unique keys"
                                            {:kind   :rf.error/malformed-json
                                             :reason :too-many-keys
                                             :limit  max-keys})))))
           peek-c   (fn [p] (when (< p n) (.charAt s p)))
           skip-ws  (fn [p]
                      (loop [p p]
                        (let [c (peek-c p)]
                          (if (and c (Character/isWhitespace ^char c))
                            (recur (inc p))
                            p))))]
       (letfn [(read-string-lit [p]
                 ;; p points at opening ".
                 (loop [p  (inc p)
                        sb (StringBuilder.)]
                   (let [c (peek-c p)]
                     (cond
                       (nil? c)  (throw (ex-info "unterminated string" {}))
                       (= c \")  [(.toString sb) (inc p)]
                       (= c \\)
                       (let [esc (peek-c (inc p))]
                         (case esc
                           \u  (let [hex-start (+ p 2)
                                     hex-end   (+ p 6)]
                                 ;; rf2-263km — bounds-check + hex-digit
                                 ;; check before parseInt. A `\u` escape
                                 ;; near EOF would otherwise raise
                                 ;; StringIndexOutOfBoundsException, and a
                                 ;; non-hex char (e.g. the closing quote
                                 ;; falling inside the 4-char window) would
                                 ;; raise NumberFormatException — both
                                 ;; classified opaquely. Surface as a clean
                                 ;; `:rf.error/malformed-json` instead so
                                 ;; the failure-category cascade reports a
                                 ;; tagged `:rf.http/decode-failure` rather
                                 ;; than a host-runtime exception string.
                                 (when (> hex-end n)
                                   (throw (ex-info "truncated unicode escape"
                                                   {:kind   :rf.error/malformed-json
                                                    :reason :truncated-unicode-escape
                                                    :at     p})))
                                 (let [hex (subs s hex-start hex-end)]
                                   (when-not (every? (fn [ch]
                                                       (let [i (int ch)]
                                                         (or (and (>= i (int \0)) (<= i (int \9)))
                                                             (and (>= i (int \a)) (<= i (int \f)))
                                                             (and (>= i (int \A)) (<= i (int \F))))))
                                                     hex)
                                     (throw (ex-info "invalid unicode escape"
                                                     {:kind   :rf.error/malformed-json
                                                      :reason :invalid-unicode-escape
                                                      :hex    hex
                                                      :at     p})))
                                   (.append sb (char (Integer/parseInt hex 16)))
                                   (recur hex-end sb)))
                           (do (.append sb (case esc
                                             \"  \"
                                             \\ \\
                                             \/ \/
                                             \b \backspace
                                             \f \formfeed
                                             \n \newline
                                             \r \return
                                             \t \tab
                                             esc))
                               (recur (+ p 2) sb))))
                       :else
                       (do (.append sb c) (recur (inc p) sb))))))
               (read-number [p]
                 (let [end (loop [m p]
                             (let [c (peek-c m)]
                               (if (and c (or (Character/isDigit ^char c)
                                              (#{\- \+ \. \e \E} c)))
                                 (recur (inc m))
                                 m)))
                       t   (subs s p end)]
                   [(if (or (.contains t ".") (.contains t "e") (.contains t "E"))
                      (Double/parseDouble t)
                      (Long/parseLong t))
                    end]))
               (read-keyword-tok [p]
                 (cond
                   (.startsWith (subs s p) "true")  [true  (+ p 4)]
                   (.startsWith (subs s p) "false") [false (+ p 5)]
                   (.startsWith (subs s p) "null")  [nil   (+ p 4)]
                   :else (throw (ex-info "bad token" {:at p}))))
               (read-array [p]
                 (let [p (inc p)                    ;; consume [
                       p (skip-ws p)]
                   (if (= \] (peek-c p))
                     [[] (inc p)]
                     (loop [p   p
                            acc []]
                       (let [p          (skip-ws p)
                             [v p]      (read-val p)
                             p          (skip-ws p)
                             c          (peek-c p)
                             acc'       (conj acc v)]
                         (cond
                           (= c \,) (recur (inc p) acc')
                           (= c \]) [acc' (inc p)]
                           :else    (throw (ex-info "expected , or ]" {:at p}))))))))
               (read-object [p]
                 (let [p (inc p)                    ;; consume {
                       p (skip-ws p)]
                   (if (= \} (peek-c p))
                     [{} (inc p)]
                     (loop [p   p
                            acc {}]
                       (let [p         (skip-ws p)
                             [k p]     (read-string-lit p)
                             ;; rf2-wu1n5 — count BEFORE interning into a
                             ;; keyword; the cap-throw runs the
                             ;; HashSet#add then size-check inside
                             ;; `note-key!`. Counting distinct strings
                             ;; (not keywords) means an attacker can't
                             ;; bypass by repeating the same key; each
                             ;; unique string is one interned keyword.
                             _         (note-key! k)
                             p         (skip-ws p)
                             _         (when (not= \: (peek-c p))
                                         (throw (ex-info "expected :" {:at p})))
                             p         (inc p)
                             p         (skip-ws p)
                             [v p]     (read-val p)
                             p         (skip-ws p)
                             c         (peek-c p)
                             acc'      (assoc acc (keyword k) v)]
                         (cond
                           (= c \,) (recur (inc p) acc')
                           (= c \}) [acc' (inc p)]
                           :else    (throw (ex-info "expected , or }" {:at p}))))))))
               (read-val [p]
                 (let [p (skip-ws p)
                       c (peek-c p)]
                   (cond
                     (= c \")  (read-string-lit p)
                     (= c \{)  (read-object p)
                     (= c \[)  (read-array p)
                     (or (= c \-) (and c (Character/isDigit ^char c))) (read-number p)
                     :else     (read-keyword-tok p))))]
         (first (read-val (skip-ws 0)))))))

;; Memoised Cheshire resolves (per rf2-tja2y). Cheshire's vars never
;; rebind at runtime; resolving once per JVM is enough. The CLJS path
;; uses the browser's `JSON` builtin and never needs a resolve.
#?(:clj (defonce ^:private cheshire-generate-string (delay (try (requiring-resolve 'cheshire.core/generate-string)
                                                                (catch Throwable _ nil)))))
#?(:clj (defonce ^:private cheshire-parse-string    (delay (try (requiring-resolve 'cheshire.core/parse-string)
                                                                (catch Throwable _ nil)))))

(defn json-stringify
  "Clojure value → JSON string. JVM uses Cheshire if available, else
  falls back to `pr-str` (good enough for the request-body shapes
  `:rf.http/managed` emits — primarily Clojure maps and vectors that
  print as legal JSON-ish edn). CLJS uses `js/JSON.stringify`."
  [v]
  #?(:clj  (if-let [write @cheshire-generate-string]
             (write v)
             (pr-str v))
     :cljs (js/JSON.stringify (clj->js v))))

(defn json-parse
  "JSON string → Clojure data with keyword keys for object keys. JVM
  uses Cheshire's `parse-string` if available, otherwise the pure-
  Clojure reader above. On CLJS uses `js/JSON.parse` +
  `js->clj :keywordize-keys true`.

  `opts` (optional) — currently a single key:
   - `:max-decoded-keys` — cap on unique object keys (rf2-wu1n5).
     Default: `default-max-decoded-keys`. Overflow throws
     `:rf.error/malformed-json :reason :too-many-keys`.

  Per rf2-263km, the fallback reader's `:rf.error/malformed-json`
  ex-info is propagated rather than swallowed — the previous
  `(catch Throwable _ s)` masked malformed input as a string return,
  which then surfaced as an opaque schema-validation failure
  downstream. Surfacing the tagged ex-info lets the `:rf.http/managed`
  cascade classify the response as `:rf.http/decode-failure` with a
  precise `:cause` instead of a noisy schema rejection.

  Per rf2-wu1n5, both branches (Cheshire and pure-Clojure fallback)
  enforce the keyword-cap. The Cheshire branch installs a `:key-fn`
  callback that counts distinct keys in a HashSet and throws on
  overflow; the fallback reader threads the cap through `json-read*`."
  ([s] (json-parse s nil))
  ([s opts]
   (let [max-keys (long (or (:max-decoded-keys opts) default-max-decoded-keys))]
     #?(:clj  (cond
                (some? @cheshire-parse-string)
                (let [seen ^java.util.HashSet (java.util.HashSet.)
                      key-fn (fn [k]
                               (when-not (.contains seen k)
                                 (.add seen k)
                                 (when (> (.size seen) max-keys)
                                   (throw (ex-info "json: too many unique keys"
                                                   {:kind   :rf.error/malformed-json
                                                    :reason :too-many-keys
                                                    :limit  max-keys}))))
                               (keyword k))]
                  (@cheshire-parse-string s key-fn))

                (string? s) (json-read* s max-keys)
                :else       s)
        :cljs (let [parsed (js/JSON.parse s)
                    ;; rf2-wu1n5 — CLJS path: walk the parsed JS object
                    ;; tree counting unique object-keys BEFORE
                    ;; `js->clj :keywordize-keys true` interns them.
                    ;; The browser JS engine GCs unreferenced symbols
                    ;; (unlike the JVM), so the DoS surface is much
                    ;; smaller — but a 10000-key payload still gives
                    ;; the runtime garbage to clean up, so we cap
                    ;; consistently across hosts.
                    seen   (volatile! #{})
                    _      (letfn [(walk [v]
                                     (cond
                                       (array? v)
                                       (doseq [x v] (walk x))

                                       (and (object? v) (not (nil? v)))
                                       (let [ks (js-keys v)]
                                         (doseq [k ks]
                                           (when-not (contains? @seen k)
                                             (vswap! seen conj k)
                                             (when (> (count @seen) max-keys)
                                               (throw (ex-info "json: too many unique keys"
                                                               {:kind   :rf.error/malformed-json
                                                                :reason :too-many-keys
                                                                :limit  max-keys})))))
                                         (doseq [k ks] (walk (aget v k))))))]
                             (walk parsed))]
                (js->clj parsed :keywordize-keys true))))))
