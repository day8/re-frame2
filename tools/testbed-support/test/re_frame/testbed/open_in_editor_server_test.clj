(ns re-frame.testbed.open-in-editor-server-test
  "JVM regression tests for the dev-only open-in-editor endpoint's
  `:file` resolution path.

  The endpoint is a JVM-only `.clj` (it runs on the shadow-cljs SERVER
  JVM), so it cannot be exercised by the node CLJS suites — this is its
  `clojure -M:test` gate.

  The load-bearing regression: a classpath checkout path containing a
  literal `+` (e.g. `C:/code/re-frame2+wip`) must survive `:file`
  resolution verbatim. The historic code decoded the `file:` resource URL
  with `URLDecoder`, which is a FORM-body decoder that maps `+` → space,
  so such a path resolved to a nonexistent `re-frame2 wip` dir and the
  endpoint launched the editor at the wrong place. `file-url->path` now
  decodes via `URI`, which leaves a literal `+` intact while still
  decoding `%20` / `%2B`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [re-frame.testbed.open-in-editor-server :as oies])
  (:import [java.net URL URLClassLoader]
           [java.io File]))

;; ---- file-url->path: the decode contract ---------------------------------

(deftest file-url->path-preserves-literal-plus
  (testing "a `file:` URL with a LITERAL `+` in the path keeps the `+`
            (it is NOT form-decoded to a space — the URLDecoder bug)"
    (is (= "/home/dev/re-frame2+wip/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/home/dev/re-frame2+wip/core.cljs")))
        "literal + survives verbatim, never becomes a space")))

(deftest file-url->path-decodes-percent-escapes
  (testing "percent-escapes still decode: `%20` → space, `%2B` → +"
    (is (= "/home/dev/my project/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/home/dev/my%20project/core.cljs")))
        "%20 decodes to a real space")
    (is (= "/home/dev/re-frame2+wip/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/home/dev/re-frame2%2Bwip/core.cljs")))
        "%2B (the encoded plus) decodes to a literal +")))

(deftest file-url->path-strips-windows-drive-leading-slash
  (testing "a Windows `file:` URL comes out as `/C:/...`; the leading
            slash before the drive letter is stripped to the canonical
            `C:/...` shape"
    (is (= "C:/code/re-frame2+wip/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/C:/code/re-frame2+wip/core.cljs")))
        "leading slash stripped AND the literal + preserved (the exact
         cross-of-the-two-bugs the Windows author hits)")
    (is (= "C:/Users/me/my project/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/C:/Users/me/my%20project/core.cljs")))
        "Windows drive shape with an encoded space round-trips")))

;; ---- resolve-file: end-to-end over a real `+`-bearing classpath ----------

(deftest resolve-file-resolves-classpath-path-with-plus
  (testing "resolve-file resolves a classpath-relative `:file` to its
            on-disk absolute path when the classpath root directory itself
            contains a literal `+` — the path is returned verbatim, not
            corrupted to a space-bearing nonexistent path"
    ;; Build a throwaway classpath root dir whose name carries a `+`, drop
    ;; a fake source file under it, push a class-loader rooted there onto
    ;; the context, and confirm resolve-file finds the real on-disk file.
    (let [tmp       (File. (System/getProperty "java.io.tmpdir")
                           (str "oies+test-" (System/nanoTime)))
          rel-path  "fake_ns/core.cljs"
          src-file  (io/file tmp "fake_ns" "core.cljs")]
      (try
        (io/make-parents src-file)
        (spit src-file ";; fixture\n")
        (let [root-url (.toURL (.toURI tmp))
              cl       (URLClassLoader. (into-array URL [root-url])
                                        (.getContextClassLoader (Thread/currentThread)))
              prev     (.getContextClassLoader (Thread/currentThread))]
          (try
            (.setContextClassLoader (Thread/currentThread) cl)
            (let [resolved (oies/resolve-file rel-path)]
              (is (some? resolved) "the classpath resource resolved")
              (is (.contains ^String resolved "+")
                  "the literal + in the classpath root survived resolution")
              (is (= (.getCanonicalPath src-file)
                     (.getCanonicalPath (File. ^String resolved)))
                  "resolved to the REAL on-disk fixture file, not a
                   space-corrupted sibling that does not exist"))
            (finally
              (.setContextClassLoader (Thread/currentThread) prev))))
        (finally
          ;; Best-effort cleanup of the throwaway tree.
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

;; ---- absolute / blank pass-through contract ------------------------------

(deftest resolve-file-passes-absolute-and-blank-through
  (testing "an already-absolute path is returned unchanged (incl. a + in it)"
    (is (= "/abs/re-frame2+wip/core.cljs"
           (oies/resolve-file "/abs/re-frame2+wip/core.cljs"))))
  (testing "nil / blank resolve to nil"
    (is (nil? (oies/resolve-file nil)))
    (is (nil? (oies/resolve-file "")))
    (is (nil? (oies/resolve-file "   ")))))
