;;;; tests/shim/stub_nrepl.clj — bencode nREPL stub for shim integration tests.
;;;;
;;;; Pair2's shell scripts shell out to `bb ops.clj <subcmd>`, which in turn
;;;; opens a TCP socket to shadow-cljs's nREPL and speaks bencode. We don't
;;;; want a live shadow-cljs process in `tests/shim/` — that's `tests/e2e/`'s
;;;; job — so this stub is a self-contained babashka program that:
;;;;
;;;;   1. Picks a free TCP port and writes it to ./target/shadow-cljs/nrepl.port
;;;;      (the canonical location ops.clj's `read-port` checks).
;;;;   2. Accepts a single nREPL bencode connection.
;;;;   3. For each `op "eval"` request, returns a canned `:value` response
;;;;      keyed off a small pattern table that mimics shadow-cljs's
;;;;      `(shadow.cljs.devtools.api/cljs-eval :app "<form>" {})` response
;;;;      shape — i.e. wraps the canned cljs value in
;;;;      `{:results [<value-as-edn-string>]}` then re-stringifies the
;;;;      whole map.
;;;;   4. Closes after the test driver signals done.
;;;;
;;;; The pattern table is small on purpose — tests/shim cover the shell
;;;; surface, not the runtime semantics. tests/runtime/ already covers
;;;; the pure CLJS logic; tests/e2e/ covers live nREPL.

(ns stub-nrepl
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net ServerSocket Socket)
           (java.io PushbackInputStream)))

;; ---------------------------------------------------------------------------
;; bencode
;; ---------------------------------------------------------------------------

(defn- bencode ^String [v]
  (cond
    (integer? v)   (str "i" v "e")
    (string? v)    (let [bs (.getBytes ^String v "UTF-8")]
                     (str (alength bs) ":" v))
    (keyword? v)   (bencode (name v))
    (map? v)       (str "d"
                        (apply str (mapcat (fn [[k v]] [(bencode k) (bencode v)])
                                           (sort-by (fn [[k _]] (if (keyword? k) (name k) (str k))) v)))
                        "e")
    (sequential? v) (str "l" (apply str (map bencode v)) "e")
    (nil? v)       (bencode "")
    :else          (bencode (pr-str v))))

(defn- read-char [^PushbackInputStream in]
  (let [b (.read in)]
    (when (neg? b) (throw (ex-info "unexpected EOF" {})))
    (char b)))

(defn- bdecode [^PushbackInputStream in]
  (let [c (read-char in)]
    (case c
      \i (let [sb (StringBuilder.)]
           (loop [ch (read-char in)]
             (if (= ch \e)
               (Long/parseLong (.toString sb))
               (do (.append sb ch) (recur (read-char in))))))
      \l (loop [acc []]
           (let [b (.read in)]
             (cond (neg? b)          (throw (ex-info "unexpected EOF in list" {}))
                   (= b (int \e))    acc
                   :else             (do (.unread in b) (recur (conj acc (bdecode in)))))))
      \d (loop [acc {}]
           (let [b (.read in)]
             (cond (neg? b)          (throw (ex-info "unexpected EOF in dict" {}))
                   (= b (int \e))    acc
                   :else             (do (.unread in b)
                                         (let [k (bdecode in)
                                               v (bdecode in)]
                                           (recur (assoc acc k v)))))))
      (let [sb (StringBuilder.)]
        (.append sb c)
        (loop [ch (read-char in)]
          (if (= ch \:)
            (let [len (Long/parseLong (.toString sb))
                  buf (byte-array len)]
              (loop [read 0]
                (when (< read len)
                  (let [n (.read in buf read (- len read))]
                    (when-not (pos? n) (throw (ex-info "EOF in string body" {})))
                    (recur (+ read n)))))
              (String. buf "UTF-8"))
            (do (.append sb ch) (recur (read-char in)))))))))

;; ---------------------------------------------------------------------------
;; Canned response table
;; ---------------------------------------------------------------------------
;;
;; The driver writes a `:cases` edn map to ./target/shim-cases.edn before
;; spawning the stub. The stub reads it once and uses it as a needle ->
;; canned-cljs-value lookup. The match policy is `clojure.string/includes?`
;; (substring) so the test author writes the meaningful fragment of the
;; CLJS form rather than the whole `(shadow.cljs.devtools.api/cljs-eval ...)`
;; wrapper.
;;
;; A `:default` key catches any otherwise-unmatched eval; defaults to nil
;; (which `cljs-eval-value` parses to the CLJS nil — fine for the
;; "responds at all" smoke test).

(defn- load-cases []
  (let [f (io/file "target/shim-cases.edn")]
    (if (.exists f)
      (read-string (slurp f))
      {})))

(defn- canned-value-for
  "Given the raw CLJS form-string (e.g. `(re-frame-pair2.runtime/health)`),
   return the canned CLJS value (a string carrying its edn form).
   Falls back to `:default` in the cases map, else nil."
  [cases form-str]
  (let [{:keys [matches default]} cases]
    (or (some (fn [[needle v]] (when (str/includes? form-str needle) v))
              matches)
        default)))

(defn- wrap-shadow-result
  "Wrap a canned CLJS value (already an edn string) in the response shape
   that `shadow.cljs.devtools.api/cljs-eval` returns — see ops.clj
   `cljs-eval-value`: an outer map with `:results [<edn-string>]`."
  [cljs-value-edn]
  (str "{:results [" (pr-str cljs-value-edn) "]}"))

;; ---------------------------------------------------------------------------
;; nREPL response writer
;; ---------------------------------------------------------------------------

(defn- send-response [^java.io.OutputStream out m]
  (.write out (.getBytes (bencode m) "UTF-8"))
  (.flush out))

(defn- handle-eval [out req cases]
  (let [code     (get req "code" "")
        ;; ops.clj wraps every cljs-eval as
        ;;   (shadow.cljs.devtools.api/cljs-eval :app "<form>" {})
        ;; Extract the inner form (best-effort substring) for table lookup.
        inner    (let [start (.indexOf ^String code "\"")
                       end   (.lastIndexOf ^String code "\"")]
                   (if (and (>= start 0) (> end start))
                     (subs code (inc start) end)
                     code))
        canned   (canned-value-for cases inner)
        wrapped  (wrap-shadow-result canned)
        msg-id   (get req "id" "0")
        session  (get req "session" "stub-session")]
    (send-response out {"id" msg-id "session" session
                        "value" wrapped})
    (send-response out {"id" msg-id "session" session
                        "status" ["done"]})))

(defn- handle-clone [out req]
  (let [msg-id (get req "id" "0")]
    (send-response out {"id" msg-id "new-session" "stub-session"
                        "status" ["done"]})))

(defn- handle-describe [out req]
  (let [msg-id (get req "id" "0")]
    (send-response out {"id" msg-id "session" "stub-session"
                        "ops" {"eval" {} "clone" {} "describe" {}}
                        "status" ["done"]})))

(defn- handle-conn [^Socket sock cases]
  (try
    (with-open [in  (PushbackInputStream. (.getInputStream sock))
                out (.getOutputStream sock)]
      (loop []
        (let [req (try (bdecode in) (catch Exception _ ::eof))]
          (when-not (= req ::eof)
            (case (get req "op")
              "clone"    (handle-clone out req)
              "describe" (handle-describe out req)
              "eval"     (handle-eval out req cases)
              (let [msg-id (get req "id" "0")]
                (send-response out {"id" msg-id
                                    "status" ["done"]
                                    "err" (str "stub: unknown op " (get req "op"))})))
            (recur)))))
    (catch Exception e
      (binding [*err* *err*] (println "stub conn error:" (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& [_]]
  (let [server     (ServerSocket. 0)
        port       (.getLocalPort server)
        port-file  (io/file "target/shadow-cljs/nrepl.port")
        cases      (load-cases)]
    (.mkdirs (.getParentFile port-file))
    (spit port-file (str port))
    (binding [*out* *err*]
      (println "stub-nrepl listening on port" port "→" (.getPath port-file)))
    (try
      (loop []
        (let [sock (.accept server)]
          ;; one connection per call from ops.clj — handle in the same
          ;; thread because nREPL eval round-trips are sequential
          (handle-conn sock cases)
          (recur)))
      (finally
        (.delete port-file)
        (.close server)))))

(when (= *file* (str *file*))
  (apply -main *command-line-args*))
