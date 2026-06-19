;;;; tests/shim/stub_nrepl.clj — bencode nREPL stub for shim integration tests.
;;;;
;;;; re-frame2-pair's shell scripts shell out to `bb ops.clj <subcmd>`, which in turn
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

;; The minimal bencode codec is shared with the production shim
;; (scripts/ops.clj); it lives in scripts/bencode.clj and is loaded off
;; this file's own location so cwd doesn't matter (rf2-qq7w2k). The stub
;; DECODES requests + ENCODES canned replies — the mirror of ops.clj.
(load-file (str (.getParent (.getParentFile (.getParentFile (java.io.File. *file*))))
                "/scripts/bencode.clj"))

(ns stub-nrepl
  (:require [bencode :as bc]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net ServerSocket Socket)
           (java.io PushbackInputStream)))

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
  "Given the raw CLJS form-string (e.g. `(re-frame2-pair.runtime/health)`),
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
  (.write out (.getBytes (bc/encode m) "UTF-8"))
  (.flush out))

(defn- handle-eval [out req cases]
  (let [code     (get req "code" "")
        ;; ops.clj wraps every cljs-eval as
        ;;   (shadow.cljs.devtools.api/cljs-eval :app "<form>" {})
        ;; but JVM-side evals (e.g. the running-build enumeration
        ;;   (vec (shadow.cljs.devtools.api/active-builds))
        ;; from rf2-ivlb3) are sent raw, with no inner string literal.
        cljs?    (str/includes? code "cljs-eval")
        ;; Extract the inner form (best-effort substring) for table lookup.
        inner    (let [start (.indexOf ^String code "\"")
                       end   (.lastIndexOf ^String code "\"")]
                   (if (and (>= start 0) (> end start))
                     (subs code (inc start) end)
                     code))
        canned   (canned-value-for cases inner)
        ;; cljs-eval results are wrapped in shadow's `{:results [...]}`
        ;; shape; a bare JVM eval returns its value verbatim (the canned
        ;; edn-string IS the pr-str'd value). ops.clj's `running-builds`
        ;; reads `(:value res)` directly, so it must see the raw vector.
        value    (if cljs? (wrap-shadow-result canned) canned)
        msg-id   (get req "id" "0")
        session  (get req "session" "stub-session")]
    (send-response out {"id" msg-id "session" session
                        "value" value})
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
        (let [req (try (bc/decode in) (catch Exception _ ::eof))]
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
