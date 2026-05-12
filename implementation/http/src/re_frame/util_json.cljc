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
  - `json-parse` — string → Clojure data with keyword keys for
    objects. Uses Cheshire's `parse-string` on JVM if available,
    otherwise the pure-Clojure fallback below; CLJS uses
    `js/JSON.parse` + `js->clj :keywordize-keys true`."
  #?(:clj  (:require [clojure.string :as str])
     :cljs (:require [clojure.string :as str])))

#?(:clj
   (defn- json-read*
     "Tiny pure-Clojure JSON reader. Returns Clojure data with keyword
     keys for objects. Sufficient for the on-the-wire shapes :rf.http/managed
     decodes; not a full RFC-8259 parser. Used only when Cheshire isn't
     on the classpath.

     Each helper returns [value next-p]: the cursor is threaded through
     return values rather than carried in a mutable atom."
     [^String s]
     (let [n        (count s)
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
                           \u  (let [hex (subs s (+ p 2) (+ p 6))]
                                 (.append sb (char (Integer/parseInt hex 16)))
                                 (recur (+ p 6) sb))
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

(defn json-stringify
  "Clojure value → JSON string. JVM uses Cheshire if available, else
  falls back to `pr-str` (good enough for the request-body shapes
  `:rf.http/managed` emits — primarily Clojure maps and vectors that
  print as legal JSON-ish edn). CLJS uses `js/JSON.stringify`."
  [v]
  #?(:clj  (let [write (try (requiring-resolve 'cheshire.core/generate-string)
                            (catch Throwable _ nil))]
             (if write
               (write v)
               (pr-str v)))
     :cljs (js/JSON.stringify (clj->js v))))

(defn json-parse
  "JSON string → Clojure data with keyword keys for object keys. JVM
  uses Cheshire's `parse-string` if available, otherwise the pure-
  Clojure reader above. On CLJS uses `js/JSON.parse` +
  `js->clj :keywordize-keys true`."
  [s]
  #?(:clj  (let [read (try (requiring-resolve 'cheshire.core/parse-string)
                           (catch Throwable _ nil))]
             (cond
               read       (read s true)
               (string? s) (try (json-read* s)
                                (catch Throwable _ s))
               :else      s))
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))
