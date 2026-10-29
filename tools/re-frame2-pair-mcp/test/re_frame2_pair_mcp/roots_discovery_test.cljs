(ns re-frame2-pair-mcp.roots-discovery-test
  "The workspace-roots discovery cascade with all I/O injected: `uri->path`,
  the bounded `shadow-cljs.edn` walk, the port-file candidate beside each
  edn, and the `discover-via-roots*` orchestrator's one / many / none /
  unsupported branches, including the candidates whose port refuses a
  connect. Live verification is `test/roots-discovery-live.js`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.roots-discovery :as rd]
            ["fs" :as fs]
            ["net" :as net]
            ["os" :as os]
            ["path" :as node-path]
            ["url" :as node-url]))

;; Expected paths go through node:path/join, as the production walk does,
;; so they carry the platform's separator.
(defn- jp [& parts]
  (apply node-path/join parts))

(defn- file-uri
  "A `file://` URI for a synthetic absolute path, in the `file:///C:/...`
  shape that decodes on both Windows and POSIX. Tests compare against
  `(uri->path uri)`, never the literal."
  [abs-path]
  (if (re-find #"^/" abs-path)
    (str "file:///C:" abs-path)
    (str "file:///" abs-path)))

;; ===========================================================================
;; uri->path
;; ===========================================================================

(deftest uri->path-returns-nil-for-a-root-it-cannot-use
  ;; The caller skips that root.
  (doseq [[uri note] [["https://example.com/x" "a non-file scheme"]
                      [nil "a missing uri"]
                      ["file:///a%2Fb" "a malformed file URI (fileURLToPath throws on both platforms)"]]]
    (is (nil? (rd/uri->path uri)) note)))

;; ===========================================================================
;; walk-for-shadow-edns* — a synthetic filesystem keyed by directory path.
;; ===========================================================================

(defn- dirent
  "A synthetic Dirent: `.name`, `.isFile()`, `.isDirectory()`."
  [name kind]
  #js {:name        name
       :isFile      (fn [] (= kind :file))
       :isDirectory (fn [] (= kind :dir))})

(defn- fs-stub
  "A `readdir-fn` over `{abs-path [{:name :kind} ...]}`; unknown dirs are empty."
  [dir-tree]
  (fn [dir _opts]
    (if-let [entries (get dir-tree dir)]
      (clj->js (mapv (fn [{:keys [name kind]}] (dirent name kind)) entries))
      #js [])))

(defn- walk [tree root]
  (vec (array-seq (rd/walk-for-shadow-edns* (fs-stub tree) root))))

(deftest walk-skips-node-modules-and-target
  (let [root "/proj"
        tree {root                     [{:name "shadow-cljs.edn" :kind :file}
                                        {:name "node_modules"    :kind :dir}
                                        {:name "target"          :kind :dir}
                                        {:name ".git"            :kind :dir}]
              (jp root "node_modules") [{:name "shadow-cljs.edn" :kind :file}]
              (jp root "target")       [{:name "shadow-cljs.edn" :kind :file}]
              (jp root ".git")         [{:name "shadow-cljs.edn" :kind :file}]}]
    (is (= [(jp root "shadow-cljs.edn")] (walk tree root)))))

(deftest walk-finds-monorepo-nested-edns
  (let [root "/repo"
        tree {root               [{:name "pkg-a" :kind :dir}
                                  {:name "pkg-b" :kind :dir}]
              (jp root "pkg-a")  [{:name "shadow-cljs.edn" :kind :file}]
              (jp root "pkg-b")  [{:name "shadow-cljs.edn" :kind :file}]}]
    (is (= #{(jp root "pkg-a" "shadow-cljs.edn") (jp root "pkg-b" "shadow-cljs.edn")}
           (set (walk tree root))))))

(deftest walk-bounded-by-depth
  ;; An edn four levels down is past `walk-max-depth`.
  (let [r    "/p"
        d    (jp r "a" "b" "c" "d")
        tree {r                  [{:name "a" :kind :dir}]
              (jp r "a")         [{:name "b" :kind :dir}]
              (jp r "a" "b")     [{:name "c" :kind :dir}]
              (jp r "a" "b" "c") [{:name "d" :kind :dir}]
              d                  [{:name "shadow-cljs.edn" :kind :file}]}]
    (is (= [] (walk tree r)))))

(deftest walk-handles-readdir-throwing
  ;; The unreadable dir is listed BEFORE the file, so the file is found only
  ;; if the walk survives the throw.
  (let [root    "/p"
        locked  (jp root "locked")
        stub    (fs-stub {root [{:name "locked" :kind :dir}
                                {:name "shadow-cljs.edn" :kind :file}]})
        readdir (fn [dir opts]
                  (if (= dir locked)
                    (throw (js/Error. "EACCES"))
                    (stub dir opts)))]
    (is (= [(jp root "shadow-cljs.edn")]
           (vec (array-seq (rd/walk-for-shadow-edns* readdir root)))))))

;; ===========================================================================
;; project-home->candidates* — the port files beside a shadow-cljs.edn.
;; ===========================================================================

(deftest candidates-read-every-port-file-present-in-precedence-order
  ;; Every location that reads is kept, in order, because a stale file can
  ;; precede the live one; a missing or non-numeric port file is skipped.
  (let [reads (fn [pattern content]
                (fn [path]
                  (if (re-find pattern path) content (throw (js/Error. "ENOENT")))))]
    (is (= [{:project-home "/proj" :port-file (jp "/proj" "target" "shadow-cljs" "nrepl.port") :port 1111}
            {:project-home "/proj" :port-file (jp "/proj" ".shadow-cljs" "nrepl.port") :port 2222}
            {:project-home "/proj" :port-file (jp "/proj" ".nrepl-port") :port 3333}]
           (rd/project-home->candidates*
             (fn [path]
               (condp re-find path
                 #"target[\\/]shadow-cljs[\\/]nrepl\.port$" "1111"
                 #"\.shadow-cljs[\\/]nrepl\.port$"          "2222"
                 #"\.nrepl-port$"                           "3333"))
             "/proj"))
        "all three read: target/shadow-cljs, then .shadow-cljs, then .nrepl-port")
    (doseq [[read-fn ports note]
            [[(reads #"\.shadow-cljs[\\/]nrepl\.port$" "5555") [5555]
              "only .shadow-cljs/nrepl.port reads"]
             [(reads #"\.nrepl-port$" "8765") [8765]
              "only .nrepl-port reads"]
             [(fn [_path] (throw (js/Error. "ENOENT"))) []
              "no port file at any of the three locations (project not running)"]
             [(reads #"target" "not-a-number") []
              "isNaN port content is skipped (don't return NaN as a port)"]]]
      (is (= ports (mapv :port (rd/project-home->candidates* read-fn "/proj"))) note))))

;; ===========================================================================
;; discover-via-roots* — orchestration with all I/O injected.
;; ===========================================================================

(defn- roots-resp
  "A roots/list response for `[uri decoded-path]` pairs."
  [pairs]
  (clj->js {:roots (mapv (fn [[uri _path]] {:uri uri}) pairs)}))

(defn- walk-returns [path->edns]
  (fn [path] (clj->js (or (get path->edns path) []))))

(defn- uri-decoding-pairs
  "`[[uri decoded] ...]` for synthetic paths, decoded on the running
  platform so the walk-fn keys match what the orchestrator looks up."
  [paths]
  (mapv (fn [p]
          (let [uri (file-uri p)]
            [uri (rd/uri->path uri)]))
        paths))

(defn- reads-ports
  "A read-file-fn answering, for a path under one of `dir->port`'s dirs,
  that dir's port; any other path is missing."
  [dir->port]
  (fn [path]
    (or (some (fn [[dir port]]
                (when (re-find (re-pattern (str "^" (str/replace dir "\\" "\\\\"))) path)
                  port))
              dir->port)
        (throw (js/Error. "ENOENT")))))

(defn- port-file-in [dir]
  (jp dir "target" "shadow-cljs" "nrepl.port"))

(def ^:private accepts-all
  "A port probe under which every candidate's port accepts a connect."
  (fn [_port] (js/Promise.resolve true)))

(deftest discover-via-roots-zero-candidates
  (async done
    (let [pairs (uri-decoding-pairs ["/empty"])]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {})
                                  (fn [_] (throw (js/Error. "ENOENT")))
                                  accepts-all)
          (.then (fn [r]
                   (is (= [:error :no-running-shadow-in-workspace (mapv second pairs)]
                          [(:status r) (-> r :error :reason) (-> r :error :roots)]))
                   (done)))))))

(deftest discover-via-roots-single-candidate
  (async done
    (let [pairs (uri-decoding-pairs ["/proj"])
          proj  (second (first pairs))]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {proj [(jp proj "shadow-cljs.edn")]})
                                  (reads-ports {proj "9001"})
                                  accepts-all)
          (.then (fn [r]
                   (is (= {:status    :one
                           :candidate {:project-home proj :port-file (port-file-in proj) :port 9001}}
                          r))
                   (done)))))))

(deftest discover-via-roots-multiple-candidates
  (async done
    (let [pairs  (uri-decoding-pairs ["/a" "/b"])
          a-path (second (first pairs))
          b-path (second (second pairs))]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {a-path [(jp a-path "shadow-cljs.edn")]
                                                 b-path [(jp b-path "shadow-cljs.edn")]})
                                  (reads-ports {a-path "1111" b-path "2222"})
                                  accepts-all)
          (.then (fn [r]
                   (is (= {:status     :many
                           :candidates #{{:project-home a-path :port-file (port-file-in a-path) :port 1111}
                                         {:project-home b-path :port-file (port-file-in b-path) :port 2222}}}
                          (update r :candidates set)))
                   (done)))))))

(deftest discover-via-roots-only-one-of-two-has-shadow
  ;; A project whose edn has no port file beside it is filtered out.
  (async done
    (let [pairs  (uri-decoding-pairs ["/a" "/b"])
          a-path (second (first pairs))
          b-path (second (second pairs))]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {a-path [(jp a-path "shadow-cljs.edn")]
                                                 b-path [(jp b-path "shadow-cljs.edn")]})
                                  (reads-ports {a-path "3333"})
                                  accepts-all)
          (.then (fn [r]
                   (is (= {:status    :one
                           :candidate {:project-home a-path :port-file (port-file-in a-path) :port 3333}}
                          r))
                   (done)))))))

(deftest discover-via-roots-edns-deduped-across-roots
  ;; The same edn found under two overlapping roots counts once.
  (async done
    (let [pairs     (uri-decoding-pairs ["/root" "/root/sub"])
          root-path (second (first pairs))
          sub-path  (second (second pairs))
          sub-edn   (jp sub-path "shadow-cljs.edn")]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {root-path [sub-edn]
                                                 sub-path  [sub-edn]})
                                  (fn [path]
                                    (if (re-find #"target" path) "9999"
                                        (throw (js/Error. "ENOENT"))))
                                  accepts-all)
          (.then (fn [r]
                   (is (= :one (:status r)))
                   (done)))))))

(deftest discover-via-roots-unsupported-roots-list
  ;; A client without roots/list rejects, or throws synchronously; both read
  ;; as unsupported so the caller falls through to the next discovery step.
  (async done
    (-> (js/Promise.all
          (into-array
            (for [[list-roots note] [[(fn [] (js/Promise.reject (js/Error. "Method not found")))
                                      "list-roots rejects"]
                                     [(fn [] (throw (js/Error. "no method")))
                                      "list-roots throws synchronously"]]]
              (.then (rd/discover-via-roots* list-roots
                                             (walk-returns {})
                                             (fn [_] (throw (js/Error. "ENOENT")))
                                             accepts-all)
                     (fn [r]
                       (is (= [:error :workspace-discovery-unsupported]
                              [(:status r) (-> r :error :reason)])
                           note))))))
        (.then (fn [_] (done))))))

;; ===========================================================================
;; Liveness — shadow-cljs deletes its port files from a JVM shutdown hook, so
;; a JVM that was force-killed leaves one naming a port nothing listens on.
;; Discovery neither attaches to it nor offers it as a choice, and names it.
;; ===========================================================================

(deftest discover-via-roots-every-candidate-refused
  ;; The orchestrator falls through (`:error`) rather than attaching.
  (async done
    (let [pairs (uri-decoding-pairs ["/proj"])
          proj  (second (first pairs))]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {proj [(jp proj "shadow-cljs.edn")]})
                                  (reads-ports {proj "9001"})
                                  (fn [_port] (js/Promise.resolve false)))
          (.then (fn [r]
                   (is (= [:error :no-running-shadow-in-workspace [(port-file-in proj)]]
                          [(:status r) (-> r :error :reason) (:stale-port-files r)]))
                   (done)))))))

(deftest discover-via-roots-skips-a-stale-preferred-port-file
  ;; One project, two port files: a force-killed JVM left
  ;; target/shadow-cljs/nrepl.port naming a dead port, and the running watch
  ;; wrote .shadow-cljs/nrepl.port. The first file must not hide the second.
  (async done
    (let [pairs (uri-decoding-pairs ["/proj"])
          proj  (second (first pairs))
          dead  (jp proj "target" "shadow-cljs" "nrepl.port")
          live  (jp proj ".shadow-cljs" "nrepl.port")]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {proj [(jp proj "shadow-cljs.edn")]})
                                  (fn [path]
                                    (condp = path
                                      dead "47101"
                                      live "47102"
                                      (throw (js/Error. "ENOENT"))))
                                  (fn [port] (js/Promise.resolve (= 47102 port))))
          (.then (fn [r]
                   (is (= {:status           :one
                           :candidate        {:project-home proj :port-file live :port 47102}
                           :stale-port-files [dead]}
                          r)
                       "the live second file attaches; the stale first one is named")))
          (.catch (fn [e] (is false (str "discovery rejected: " (.-message e)))))
          (.finally done)))))

(deftest discover-via-roots-keeps-one-candidate-per-project
  ;; Two live files in one project are one build, not an ambiguity: the
  ;; first in precedence order is the project's candidate.
  (async done
    (let [pairs (uri-decoding-pairs ["/proj"])
          proj  (second (first pairs))]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {proj [(jp proj "shadow-cljs.edn")]})
                                  (fn [path]
                                    (if (re-find #"nrepl\.port$" path) "9001" (throw (js/Error. "ENOENT"))))
                                  accepts-all)
          (.then (fn [r]
                   (is (= {:status    :one
                           :candidate {:project-home proj :port-file (port-file-in proj) :port 9001}}
                          r))))
          (.finally done)))))

(defn- listen!
  "Resolve a `net.Server` listening on an ephemeral 127.0.0.1 port. It drops
  each connection it accepts: a probe needs only the connect."
  []
  (js/Promise.
    (fn [resolve reject]
      (let [^js srv (net/createServer (fn [^js sock] (.destroy sock)))]
        (.once srv "error" reject)
        (.listen srv 0 "127.0.0.1" #(resolve srv))))))

(defn- port-of [^js srv]
  (.-port (.address srv)))

(defn- close-server! [^js srv]
  (js/Promise. (fn [resolve] (.close srv (fn [_] (resolve nil))))))

(defn- write-project!
  "A project directory `dir` under `root`, holding a `shadow-cljs.edn` and a
  `.shadow-cljs/nrepl.port` naming `port`. Returns the directory."
  [root dir port]
  (let [home (jp root dir)]
    (fs/mkdirSync (jp home ".shadow-cljs") #js {:recursive true})
    (fs/writeFileSync (jp home "shadow-cljs.edn") "{}\n")
    (fs/writeFileSync (jp home ".shadow-cljs" "nrepl.port") (str port))
    home))

(deftest discover-via-roots-drops-a-port-file-whose-port-refuses
  ;; Real files, real sockets and the production probe. One workspace root
  ;; holds two projects: one port file names a listening port, the other a
  ;; port that was bound and then released, so nothing listens on it.
  (async done
    (let [root (fs/mkdtempSync (jp (os/tmpdir) "pair-roots-stale-"))
          live (atom nil)]
      (-> (js/Promise.all #js [(listen!) (listen!)])
          (.then
            (fn [servers]
              (let [[live-srv dead-srv] (array-seq servers)
                    live-port           (port-of live-srv)
                    dead-port           (port-of dead-srv)]
                (reset! live live-srv)
                (-> (close-server! dead-srv)
                    (.then
                      (fn [_]
                        (let [live-home (write-project! root "live" live-port)
                              dead-home (write-project! root "dead" dead-port)
                              uri       (.-href (node-url/pathToFileURL root))]
                          (-> (rd/discover-via-roots
                                (fn [] (js/Promise.resolve (clj->js {:roots [{:uri uri}]})))
                                nrepl/port-accepts?)
                              (.then
                                (fn [r]
                                  (is (= {:status           :one
                                          :candidate        {:project-home live-home
                                                             :port-file    (jp live-home ".shadow-cljs" "nrepl.port")
                                                             :port         live-port}
                                          :stale-port-files [(jp dead-home ".shadow-cljs" "nrepl.port")]}
                                         r)
                                      "the live project attaches silently; the dead one is dropped and named")))))))))))
          (.catch (fn [e] (is false (str "discovery rejected: " (.-message e)))))
          (.finally
            (fn []
              (-> (if-let [srv @live] (close-server! srv) (js/Promise.resolve nil))
                  (.then (fn [_]
                           (fs/rmSync root #js {:recursive true :force true})
                           (done))))))))))
