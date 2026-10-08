(ns re-frame.story-source-coords-test
  "The source coordinate Story's `reg-*` macros stamp: `coords-form` prefers
  `(:file (meta &form))` over `*file*` (the CLJS analyzer never binds
  `*file*`), and resolves the classpath-relative `:file` to an absolute path
  at macroexpansion, so an `editor://` fallback URI names a file an editor can
  open. Paths are the building machine's, so the pins are shapes, not
  literals. The CLJS half is `re-frame.story-open-in-editor-cljs-test`
  §`endpoint-422-falls-back-to-an-absolute-uri-for-a-real-story-coord`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.story :as rf.story]
            [re-frame.story.macros :as rf.story.macros]))

(defn- eval-coords-form
  "`coords-form` returns a map literal whose `:ns` slot is a `(quote sym)`."
  [form-meta file ns-sym]
  (eval (rf.story.macros/coords-form form-meta file ns-sym)))

;; A Story source file on this JVM gate's classpath, so absolutisation has
;; something to resolve (an unresolvable path passes through by design).
(def ^:private on-classpath-story-file
  "re_frame/story/macros.clj")

(defn- assert-absolute-and-real!
  "Absolute per `editor-uri/absolute-path?`, on disk, and still ending in the
  classpath-relative tail it started as."
  [path tail because]
  (is (rf.source-coords.editor-uri/absolute-path? path) (str because " " (pr-str path)))
  (is (str/ends-with? (str/replace path "\\" "/") tail) because)
  (is (.exists (io/file path)) because))

(deftest file-omitted-when-both-sources-are-sentinel
  (is (= {:line 1 :column 1 :ns 'some.ns}
         (select-keys (eval-coords-form {:line 1 :column 1 :file "NO_SOURCE_PATH"}
                                        "NO_SOURCE_PATH" 'some.ns)
                      [:line :column :ns :file]))
      ":file is omitted rather than carrying the sentinel"))

(deftest gen-reg-call-carries-absolutised-file-into-pending-coords
  (testing "with a CLJS-style NO_SOURCE_PATH *file*, the expansion's
            *pending-coords* literal takes the form-meta :file, absolutised"
    (let [expansion (rf.story.macros/gen-reg-call
                      {:line 42 :column 3 :file on-classpath-story-file}
                      "NO_SOURCE_PATH" 'my.app.stories 'irrelevant-reg-fn
                      :my.app/variant {:doc "x"})
          ;; (when _ (binding [_ <coords>] _))
          coords    (eval (-> expansion (nth 2) second second))]
      (assert-absolute-and-real! (:file coords) on-classpath-story-file "pending coords")
      (is (= [42 3 'my.app.stories] ((juxt :line :column :ns) coords))))))

(deftest a-real-story-registration-stamps-an-absolute-file
  (rf.story/reg-story :story.source-coords.absolute-pin {:doc "fixture"})
  (let [coord (:source (rf.story/handler-meta :story :story.source-coords.absolute-pin))]
    (assert-absolute-and-real! (:file coord) "re_frame/story_source_coords_test.clj" "registration")
    (is (pos-int? (:line coord)))
    (is (pos-int? (:column coord)))
    (is (= 're-frame.story-source-coords-test (:ns coord)))))

;; On a 422 decline the open-in-editor client falls back to
;; `editor-uri/editor-uri` with the host's `:project-root` (nil on a dev
;; testbed); that composition is pure `.cljc`, so its output is pinned here.

(deftest declined-endpoint-falls-back-to-a-resolvable-uri
  (rf.story/reg-story :story.source-coords.fallback-pin {:doc "fixture"})
  (let [coord (:source (rf.story/handler-meta :story :story.source-coords.fallback-pin))
        uri   (rf.source-coords.editor-uri/editor-uri :windsurf coord {:project-root nil})]
    (testing "with no project-root, the fallback URI names an absolute path"
      (is (str/starts-with? uri "windsurf://file/"))
      (assert-absolute-and-real! (-> uri
                                     (subs (count "windsurf://file/"))
                                     (str/replace #":\d+:\d+$" ""))
                                 "re_frame/story_source_coords_test.clj"
                                 "fallback URI"))
    (testing "a configured project-root does not double-prefix an absolute coord"
      (is (= uri (rf.source-coords.editor-uri/editor-uri
                   :windsurf coord {:project-root "/some/external/root"}))))))
