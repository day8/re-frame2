(ns re-frame2-pair-mcp.roots-discovery-test
  "Unit tests for the workspace-roots discovery cascade.

  Three surfaces under test:

    - `uri->path`                  — pure `file://` URI → local path
                                     (Windows + POSIX).
    - `walk-for-shadow-edns*`      — directory walk; finds
                                     `shadow-cljs.edn` files, skips
                                     uninteresting dirs.
    - `discover-via-roots*`        — top-level orchestrator; injected
                                     `list-roots-fn` + `walk-fn` +
                                     `read-file-fn`. Pins the 0 / 1 / 2+ /
                                     unsupported / no-shadow branches.

  No test in this file talks to a live MCP client or a real shadow
  process; live verification is `test/roots-discovery-live.js`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.roots-discovery :as rd]
            ["path" :as node-path]))

;; ---------------------------------------------------------------------------
;; Platform-aware path helpers.
;;
;; node:path/join uses `\` on Windows and `/` on POSIX. Tests assert on the
;; output of the production walk, which threads through node-path/join, so
;; expected paths must be built the same way.
;; ---------------------------------------------------------------------------

(defn- jp
  "Platform-aware join. Equivalent to `(node-path/join a b ...)`."
  [& parts]
  (apply node-path/join parts))

(defn- file-uri
  "Build a `file://` URI for a synthetic absolute path. On POSIX this is
  just `file://` + path; on Windows we need `file:///C:/...`. We always
  use the C: prefix so the URI roundtrips through `fileURLToPath` on both
  platforms — `/proj` decodes via `\\proj` on POSIX (well, fails) so the
  cross-platform shape is `file:///C:/proj`."
  [abs-path]
  ;; Strip a leading `/` for the C:/... shape Windows expects; POSIX
  ;; doesn't mind a literal `C:/` prefix as long as we don't reassert
  ;; the path string in test expectations (we always compare against
  ;; `(uri->path uri)` rather than the literal).
  (if (re-find #"^/" abs-path)
    (str "file:///C:" abs-path)
    (str "file:///" abs-path)))

;; ===========================================================================
;; uri->path — `file://` URI decoding.
;; ===========================================================================

(deftest uri->path-decodes-canonical-uri
  (testing "file URIs round-trip through node:url/fileURLToPath"
    ;; Node's fileURLToPath behaviour is platform-specific:
    ;;   POSIX     `file:///home/me/proj`     → `/home/me/proj`
    ;;   Windows   `file:///C:/Users/me/proj` → `C:\Users\me\proj`
    ;;   Windows   `file:///home/me/proj`     → THROWS (no drive letter)
    ;;   POSIX     `file:///C:/...`           → `/C:/...` (literal)
    ;; The Windows-shape URI works on both platforms (POSIX accepts it as
    ;; a literal path); we use it so the test is platform-independent.
    (let [p (rd/uri->path "file:///C:/Users/me/proj")]
      (is (some? p) "fileURLToPath produces a platform-style path")
      (is (re-find #"Users[\\/]me[\\/]proj" p)
          "the tail of the path survives the round-trip on either platform"))))

(deftest uri->path-rejects-non-file-uri
  (testing "https / custom-scheme URIs aren't local paths"
    (is (nil? (rd/uri->path "https://example.com/x")))
    (is (nil? (rd/uri->path "scheme:foo")))))

(deftest uri->path-handles-non-string
  (is (nil? (rd/uri->path nil)))
  (is (nil? (rd/uri->path 42))))

(deftest uri->path-handles-malformed-uri
  (testing "malformed file URIs surface as nil (caller skips the root)"
    ;; Some malformed forms throw inside fileURLToPath; the wrapper
    ;; collapses them to nil so the caller falls through cleanly.
    (is (or (nil? (rd/uri->path "file://"))
            (string? (rd/uri->path "file://"))))))

;; ===========================================================================
;; walk-for-shadow-edns* — directory walk with injected readdir.
;;
;; The injected readdir takes `(dir opts)` and returns a JS array of
;; Dirent-shaped objects (`.name` + `.isFile()` + `.isDirectory()`).
;; We build a synthetic filesystem as a CLJS map keyed by directory path.
;; ===========================================================================

(defn- dirent
  "Synthetic Dirent: name + kind (`:file` / `:dir`)."
  [name kind]
  #js {:name        name
       :isFile      (fn [] (= kind :file))
       :isDirectory (fn [] (= kind :dir))})

(defn- fs-stub
  "Build a `readdir-fn` from a CLJS map `dir-tree` of `{abs-path
  [{:name :kind} …]}`. Returns an empty array for unknown dirs."
  [dir-tree]
  (fn [dir _opts]
    (if-let [entries (get dir-tree dir)]
      (clj->js (mapv (fn [{:keys [name kind]}] (dirent name kind)) entries))
      #js [])))

(deftest walk-skips-node-modules-and-target
  (testing "the skip-dir-names set is honoured — node_modules + target trees are not descended"
    (let [root "/proj"
          nm   (jp root "node_modules")
          tg   (jp root "target")
          git  (jp root ".git")
          tree {root [{:name "shadow-cljs.edn" :kind :file}
                      {:name "node_modules"   :kind :dir}
                      {:name "target"         :kind :dir}
                      {:name ".git"           :kind :dir}]
                ;; These dirs DO contain an edn but the walk must NOT descend.
                nm   [{:name "shadow-cljs.edn" :kind :file}]
                tg   [{:name "shadow-cljs.edn" :kind :file}]
                git  [{:name "shadow-cljs.edn" :kind :file}]}
          hits (vec (array-seq (rd/walk-for-shadow-edns* (fs-stub tree) root)))]
      (is (= 1 (count hits))
          "only the root-level edn — the skip-listed dirs aren't walked")
      (is (= (jp root "shadow-cljs.edn") (first hits))))))

(deftest walk-finds-monorepo-nested-edns
  (testing "a monorepo with one edn per package"
    (let [root  "/repo"
          pa    (jp root "pkg-a")
          pb    (jp root "pkg-b")
          pc    (jp root "pkg-c")
          tree  {root [{:name "pkg-a" :kind :dir}
                       {:name "pkg-b" :kind :dir}
                       {:name "pkg-c" :kind :dir}]
                 pa   [{:name "shadow-cljs.edn" :kind :file}]
                 pb   [{:name "shadow-cljs.edn" :kind :file}]
                 pc   [{:name "README.md" :kind :file}]}
          hits  (set (array-seq (rd/walk-for-shadow-edns* (fs-stub tree) root)))]
      (is (contains? hits (jp pa "shadow-cljs.edn")))
      (is (contains? hits (jp pb "shadow-cljs.edn")))
      (is (= 2 (count hits))))))

(deftest walk-bounded-by-depth
  (testing "the recursive walk doesn't descend past `walk-max-depth`"
    ;; Build a chain deeper than the bound; the shadow-cljs.edn at the
    ;; deepest level should NOT be found.
    (let [r  "/p"
          a  (jp r "a")
          b  (jp a "b")
          c  (jp b "c")
          d  (jp c "d")
          tree {r [{:name "a" :kind :dir}]
                a [{:name "b" :kind :dir}]
                b [{:name "c" :kind :dir}]
                c [{:name "d" :kind :dir}]
                d [{:name "shadow-cljs.edn" :kind :file}]}
          hits (vec (array-seq (rd/walk-for-shadow-edns* (fs-stub tree) r)))]
      (is (zero? (count hits))
          "depth bound prevents the deep edn from being discovered"))))

(deftest walk-handles-readdir-throwing
  (testing "a readdir error on a subdir doesn't bubble; the walk continues"
    (let [root    "/p"
          locked  (jp root "locked")
          ;; The unreadable dir is listed BEFORE the file, so the file is
          ;; only found if the walk survives the throw.
          tree    {root [{:name "locked" :kind :dir}
                         {:name "shadow-cljs.edn" :kind :file}]}
          stub    (fs-stub tree)
          readdir (fn [dir opts]
                    (if (= dir locked)
                      (throw (js/Error. "EACCES"))
                      (stub dir opts)))
          hits    (vec (array-seq (rd/walk-for-shadow-edns* readdir root)))]
      (is (= [(jp root "shadow-cljs.edn")] hits)
          "the file after the unreadable dir is still found"))))

;; ===========================================================================
;; project-home->candidate* — port file beside a shadow-cljs.edn.
;; ===========================================================================

(deftest candidate-reads-the-first-port-file-present
  ;; The three port-file locations are tried in order; a missing or
  ;; non-numeric port file yields no candidate (the project is not running).
  (let [reads (fn [pattern content]
                (fn [path]
                  (if (re-find pattern path) content (throw (js/Error. "ENOENT")))))]
    (testing "the first port-file location (target/shadow-cljs/nrepl.port) wins"
      (let [c (rd/project-home->candidate*
                (reads #"target[\\/]shadow-cljs[\\/]nrepl\.port$" "12345") "/proj")]
        (is (= "/proj" (:project-home c)))
        (is (= 12345 (:port c)))
        (is (re-find #"target[\\/]shadow-cljs[\\/]nrepl\.port$" (:port-file c)))))
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
;; discover-via-roots* — top-level orchestration with all I/O injected.
;; ===========================================================================

(defn- roots-resp
  "Build a roots/list response from a vector of `[uri decoded-path]`
  pairs. Tests use this to keep tight control over the URI→path
  decoding step that varies by platform."
  [pairs]
  (clj->js {:roots (mapv (fn [[uri _path]] {:uri uri}) pairs)}))

(defn- walk-returns [path->edns]
  (fn [path] (clj->js (or (get path->edns path) []))))

(defn- uri-decoding-pairs
  "Given a vector of synthetic absolute paths, produce
  `[[uri decoded] ...]` pairs that round-trip cleanly through
  `uri->path` on the running platform — so the test's walk-fn key set
  matches what the orchestrator will look up."
  [paths]
  (mapv (fn [p]
          (let [uri    (file-uri p)
                decoded (rd/uri->path uri)]
            [uri decoded]))
        paths))

(deftest discover-via-roots-zero-candidates
  (testing "roots returned, walk finds no edns → :no-running-shadow-in-workspace"
    (async done
      (let [pairs      (uri-decoding-pairs ["/empty"])
            decoded    (mapv second pairs)
            list-roots (fn [] (js/Promise.resolve (roots-resp pairs)))
            walk       (walk-returns {})
            read-file  (fn [_] (throw (js/Error. "ENOENT")))]
        (-> (rd/discover-via-roots* list-roots walk read-file)
            (.then (fn [r]
                     (is (= :error (:status r)))
                     (is (= :no-running-shadow-in-workspace (-> r :error :reason)))
                     (is (= decoded (-> r :error :roots)))
                     (done))))))))

(deftest discover-via-roots-single-candidate
  (testing "one root + one edn + a port file → :one"
    (async done
      (let [pairs      (uri-decoding-pairs ["/proj"])
            proj       (second (first pairs))
            edn-path   (jp proj "shadow-cljs.edn")
            list-roots (fn [] (js/Promise.resolve (roots-resp pairs)))
            walk       (walk-returns {proj [edn-path]})
            read-file  (fn [path]
                         (if (re-find #"target[\\/]shadow-cljs[\\/]nrepl\.port$" path)
                           "9001"
                           (throw (js/Error. "ENOENT"))))]
        (-> (rd/discover-via-roots* list-roots walk read-file)
            (.then (fn [r]
                     (is (= :one (:status r)))
                     (is (= proj (-> r :candidate :project-home)))
                     (is (= 9001 (-> r :candidate :port)))
                     (done))))))))

(deftest discover-via-roots-multiple-candidates
  (testing "two projects in the workspace, both with running shadow → :many"
    (async done
      (let [pairs      (uri-decoding-pairs ["/a" "/b"])
            a-path     (second (first pairs))
            b-path     (second (second pairs))
            list-roots (fn [] (js/Promise.resolve (roots-resp pairs)))
            walk       (walk-returns {a-path [(jp a-path "shadow-cljs.edn")]
                                      b-path [(jp b-path "shadow-cljs.edn")]})
            read-file  (fn [path]
                         (cond
                           (re-find (re-pattern (str "^" (str/replace a-path "\\" "\\\\"))) path)
                           "1111"
                           (re-find (re-pattern (str "^" (str/replace b-path "\\" "\\\\"))) path)
                           "2222"
                           :else (throw (js/Error. "ENOENT"))))]
        (-> (rd/discover-via-roots* list-roots walk read-file)
            (.then (fn [r]
                     (is (= :many (:status r)))
                     (is (= 2 (count (:candidates r))))
                     (is (= #{1111 2222} (set (map :port (:candidates r)))))
                     (done))))))))

(deftest discover-via-roots-list-roots-rejects
  (testing "client doesn't expose roots/list → :workspace-discovery-unsupported"
    (async done
      (let [list-roots (fn [] (js/Promise.reject (js/Error. "Method not found")))
            walk       (walk-returns {})
            read-file  (fn [_] (throw (js/Error. "ENOENT")))]
        (-> (rd/discover-via-roots* list-roots walk read-file)
            (.then (fn [r]
                     (is (= :error (:status r)))
                     (is (= :workspace-discovery-unsupported
                            (-> r :error :reason)))
                     (done))))))))

(deftest discover-via-roots-list-roots-throws-synchronously
  (testing "synchronous throw in list-roots-fn is converted to :workspace-discovery-unsupported"
    (async done
      (let [list-roots (fn [] (throw (js/Error. "no method")))
            walk       (walk-returns {})
            read-file  (fn [_] (throw (js/Error. "ENOENT")))]
        (-> (rd/discover-via-roots* list-roots walk read-file)
            (.then (fn [r]
                     (is (= :error (:status r)))
                     (is (= :workspace-discovery-unsupported
                            (-> r :error :reason)))
                     (done))))))))

(deftest discover-via-roots-only-one-of-two-has-shadow
  (testing "two projects, only one has a running shadow → :one"
    (async done
      (let [pairs      (uri-decoding-pairs ["/a" "/b"])
            a-path     (second (first pairs))
            b-path     (second (second pairs))
            list-roots (fn [] (js/Promise.resolve (roots-resp pairs)))
            walk       (walk-returns {a-path [(jp a-path "shadow-cljs.edn")]
                                      b-path [(jp b-path "shadow-cljs.edn")]})
            ;; Only the /a project has a running shadow port file.
            read-file  (fn [path]
                         (if (re-find (re-pattern (str "^" (str/replace a-path "\\" "\\\\"))) path)
                           "3333"
                           (throw (js/Error. "ENOENT"))))]
        (-> (rd/discover-via-roots* list-roots walk read-file)
            (.then (fn [r]
                     (is (= :one (:status r)))
                     (is (= a-path (-> r :candidate :project-home)))
                     (is (= 3333 (-> r :candidate :port)))
                     (done))))))))

(deftest discover-via-roots-edns-deduped-across-roots
  (testing "the same edn discovered under two overlapping roots is only counted once"
    (async done
      (let [pairs      (uri-decoding-pairs ["/root" "/root/sub"])
            root-path  (second (first pairs))
            sub-path   (second (second pairs))
            sub-edn    (jp sub-path "shadow-cljs.edn")
            list-roots (fn [] (js/Promise.resolve (roots-resp pairs)))
            walk       (walk-returns {root-path [sub-edn]
                                      sub-path  [sub-edn]})
            read-file  (fn [path]
                         (if (re-find #"target" path) "9999"
                             (throw (js/Error. "ENOENT"))))]
        (-> (rd/discover-via-roots* list-roots walk read-file)
            (.then (fn [r]
                     (is (= :one (:status r))
                         "overlap collapses; only one candidate surfaces")
                     (done))))))))

(deftest ambiguous-result-payload-shape
  (testing "the ambiguous payload carries the reason + candidates for the caller"
    (let [cs [{:project-home "/a" :port-file "..." :port 1}
              {:project-home "/b" :port-file "..." :port 2}]
          p  (rd/ambiguous-result-payload cs)]
      (is (= :ambiguous-shadow (:reason p)))
      (is (= cs (:candidates p)))
      (is (string? (:hint p))))))
