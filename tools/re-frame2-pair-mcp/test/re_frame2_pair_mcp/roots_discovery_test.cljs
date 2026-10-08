(ns re-frame2-pair-mcp.roots-discovery-test
  "The workspace-roots discovery cascade with all I/O injected: `uri->path`,
  the bounded `shadow-cljs.edn` walk, the port-file candidate beside each
  edn, and the `discover-via-roots*` orchestrator's one / many / none /
  unsupported branches. Live verification is `test/roots-discovery-live.js`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.roots-discovery :as rd]
            ["path" :as node-path]))

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

(deftest uri->path-decodes-canonical-uri
  (is (re-find #"Users[\\/]me[\\/]proj" (rd/uri->path "file:///C:/Users/me/proj"))))

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
                                  {:name "pkg-b" :kind :dir}
                                  {:name "pkg-c" :kind :dir}]
              (jp root "pkg-a")  [{:name "shadow-cljs.edn" :kind :file}]
              (jp root "pkg-b")  [{:name "shadow-cljs.edn" :kind :file}]
              (jp root "pkg-c")  [{:name "README.md" :kind :file}]}]
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
;; project-home->candidate* — the port file beside a shadow-cljs.edn.
;; ===========================================================================

(deftest candidate-reads-the-first-port-file-present
  ;; The three locations are tried in order; a missing or non-numeric port
  ;; file yields no candidate (the project is not running).
  (let [reads (fn [pattern content]
                (fn [path]
                  (if (re-find pattern path) content (throw (js/Error. "ENOENT")))))]
    (is (= {:project-home "/proj"
            :port-file    (jp "/proj" "target" "shadow-cljs" "nrepl.port")
            :port         12345}
           (rd/project-home->candidate*
             (reads #"target[\\/]shadow-cljs[\\/]nrepl\.port$" "12345") "/proj")))
    (doseq [[read-fn port note]
            [[(reads #"\.shadow-cljs[\\/]nrepl\.port$" "5555") 5555
              "second candidate (.shadow-cljs/nrepl.port) wins if target/ absent"]
             [(reads #"\.nrepl-port$" "8765") 8765
              "third candidate (.nrepl-port) is the last fallback"]
             [(fn [_path] (throw (js/Error. "ENOENT"))) nil
              "no port file at any of the three locations → nil (project not running)"]
             [(reads #"target" "not-a-number") nil
              "isNaN port content → nil (don't return NaN as a port)"]]]
      (let [c (rd/project-home->candidate* read-fn "/proj")]
        (if port
          (is (= port (:port c)) note)
          (is (nil? c) note))))))

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

(deftest discover-via-roots-zero-candidates
  (async done
    (let [pairs (uri-decoding-pairs ["/empty"])]
      (-> (rd/discover-via-roots* (fn [] (js/Promise.resolve (roots-resp pairs)))
                                  (walk-returns {})
                                  (fn [_] (throw (js/Error. "ENOENT"))))
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
                                  (reads-ports {proj "9001"}))
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
                                  (reads-ports {a-path "1111" b-path "2222"}))
          (.then (fn [r]
                   (is (= :many (:status r)))
                   (is (= [1111 2222] (sort (map :port (:candidates r)))))
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
                                  (reads-ports {a-path "3333"}))
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
                                        (throw (js/Error. "ENOENT")))))
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
                                             (fn [_] (throw (js/Error. "ENOENT"))))
                     (fn [r]
                       (is (= [:error :workspace-discovery-unsupported]
                              [(:status r) (-> r :error :reason)])
                           note))))))
        (.then (fn [_] (done))))))
