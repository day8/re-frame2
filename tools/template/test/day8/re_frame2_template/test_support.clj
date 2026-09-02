(ns day8.re-frame2-template.test-support
  "Shared harness for the tools/template JVM test suite.

   The sibling test files (`template_test.clj` /
   `template_emission_test.clj` / `emitted_test_run_test.clj` /
   `version_lockstep_test.clj`) all draw `tmp-dir` / `delete-recursively`
   / `template-resource-dir` / `run-template!` / `repo-root` from here.
   These are pure functions with no top-level mutable state, so a single
   shared copy is the right home.

   `repo-root` is a single walk-up that anchors on
   `implementation/core/src/re_frame` — the strongest, deepest repo
   marker. `implementation/package.json` lives under the same repo root,
   so this deeper marker subsumes it for every caller."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [org.corfield.new :as deps-new])
  (:import [java.nio.file Files LinkOption Path
            FileVisitResult SimpleFileVisitor]
           [java.nio.file.attribute FileAttribute]))

;; --- emitted-file readers --------------------------------------------------

(defn read-edn
  "Parse `f` (a `java.io.File`) as a single EDN value."
  [^java.io.File f]
  (edn/read-string (slurp f)))

(defn file-exists?
  "True if `path` (relative to `root`) exists as a regular file."
  [^java.io.File root path]
  (.isFile (io/file root path)))

;; --- tmp dirs --------------------------------------------------------------

(defn tmp-dir
  "Create a fresh temp directory and return its absolute `java.nio.file.Path`.
  Caller is responsible for cleanup (see `delete-recursively`)."
  [prefix]
  (.toAbsolutePath
    (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- reparse-point?
  "True when `path` is a symbolic link OR a Windows directory *junction*.
  `Files/isSymbolicLink` catches the former but NOT junctions — a junction
  is a distinct reparse-point type that the JDK reports as a plain
  directory. We detect it the only portable way: a junction's
  `BasicFileAttributes` reports `isDirectory` true AND `isOther` true
  (the reparse-point bit), whereas a real directory reports `isOther`
  false. (On non-Windows this simply never matches, which is correct —
  there are no junctions there.)"
  [^Path path]
  (or (Files/isSymbolicLink path)
      (try
        (let [attrs (Files/readAttributes
                      path
                      java.nio.file.attribute.BasicFileAttributes
                      (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
          (and (.isDirectory attrs) (.isOther attrs)))
        (catch java.io.IOException _ false))))

(defn delete-recursively
  "Recursively delete a directory tree (depth-first, deepest entries
  first). Swallows per-entry IO failures so a partially-removed tree on
  a locked OS file (Windows) doesn't fail the enclosing `finally`.

  CRITICAL: deletes symlinks and Windows *junctions* as a single unit —
  it never descends THROUGH them. The behavioural emitted-test tier
  junctions the project's `node_modules` to the shared
  `implementation/node_modules`; a naive `Files/walk` (which follows
  junctions, since the JDK treats them as plain directories) would walk
  into that junction and delete the shared React install. We use
  `walkFileTree` with a visitor that, on entering a reparse-point
  directory, deletes the link and skips its subtree."
  [^Path path]
  (when (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (Files/walkFileTree
      path
      (proxy [SimpleFileVisitor] []
        (preVisitDirectory [dir attrs]
          (if (reparse-point? dir)
            ;; A junction/symlinked dir: unlink it (removes only the
            ;; reparse point, never the target's contents) and do not
            ;; descend.
            (do (try (Files/deleteIfExists ^Path dir)
                     (catch java.io.IOException _ nil))
                FileVisitResult/SKIP_SUBTREE)
            FileVisitResult/CONTINUE))
        (visitFile [file attrs]
          (try (Files/deleteIfExists ^Path file)
               (catch java.io.IOException _ nil))
          FileVisitResult/CONTINUE)
        (visitFileFailed [file _exc]
          ;; Couldn't stat/open the entry — keep going; the postVisit
          ;; delete below sweeps what it can.
          FileVisitResult/CONTINUE)
        (postVisitDirectory [dir _exc]
          (try (Files/deleteIfExists ^Path dir)
               (catch java.io.IOException _ nil))
          FileVisitResult/CONTINUE)))))

;; --- repo-root + template-resource-dir -------------------------------------

(defn repo-root
  "Absolute repo-root `java.io.File`, derived from the JVM's `user.dir`.

  The template test JVM is launched from `tools/template/` (the clein
  default working dir for the `:test` alias), but a manual repo-root
  `clojure -X:test` invocation must also work — so we walk up from
  `user.dir` until we find a directory with a
  `implementation/core/src/re_frame` child (the deepest, most
  unambiguous repo marker)."
  []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d)
      (throw (ex-info (str "Couldn't locate repo root "
                           "(no implementation/core/src/re_frame above cwd)")
                      {:cwd (System/getProperty "user.dir")}))

      (.isDirectory (io/file d "implementation/core/src/re_frame"))
      d

      :else
      (recur (.getParentFile d)))))

(defn template-resource-dir
  "Absolute path of the deps-new template-source root
  (`tools/template/resources/`). The deps-new resolver walks the
  classpath; passing `:src-dirs [this]` makes it deterministic even when
  the test JVM is launched from a non-standard cwd."
  []
  (.getCanonicalPath (io/file (repo-root) "tools/template/resources")))

;; --- run-template! ---------------------------------------------------------

(defn run-template-opts!
  "Drive `org.corfield.new/create` to scaffold `project-name` inside `tmp`
  with `extra-opts` merged straight onto the deps-new opts map — so a test
  can pass any template argument (a retired flag, a typo key, …). Returns
  the emitted project root as a `java.io.File`. Equivalent to shelling out
  to `clojure -Tnew create :template … :name … …`, minus the JVM start-up
  cost."
  [tmp project-name extra-opts]
  (let [dir-str   (.toString ^Path tmp)
        ;; deps-new names the output dir after the artifact portion of
        ;; `acme/my-app` (the part after the `/`), so strip the group.
        dir-name  (-> project-name name (string/replace #"^.*?/" ""))
        proj-dir  (io/file dir-str dir-name)
        opts      (merge {:template   'day8/re-frame2-template
                          :name       (symbol project-name)
                          :target-dir (.getCanonicalPath proj-dir)
                          :src-dirs   [(template-resource-dir)]
                          :overwrite  :delete}
                         extra-opts)]
    (deps-new/create opts)
    proj-dir))

(defn run-template!
  "Scaffold `project-name` inside `tmp` for `substrate`, which may be nil
  (exercises the default-substrate path). See `run-template-opts!`."
  [tmp project-name substrate]
  (run-template-opts! tmp project-name
                      (cond-> {} substrate (assoc :substrate substrate))))
