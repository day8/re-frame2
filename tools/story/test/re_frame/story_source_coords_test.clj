(ns re-frame.story-source-coords-test
  "Regression net for the source-coord Story's nine `reg-*` macros stamp.

  Two defects live here, and they are the two halves of one coordinate.

  ## rf2-ulxi — WHICH file string gets picked

  `:rf.assert/*` events surfaced from `:script` sequences were emitting
  source-coords with `:file \"NO_SOURCE_PATH\"`. Root cause: `coords-form`
  read `*file*` at macro-expansion time, but the CLJS analyzer never binds
  Clojure's `*file*` during macro expansion (it binds
  `cljs.analyzer/*cljs-file*` instead), so `*file*` retained the JVM
  compiler's default sentinel.

  The fix prefers `(:file (meta &form))` — the CLJS analyzer reads source
  files via tools.reader's `indexing-push-back-reader`, which attaches
  `{:file ...}` to every collection-form's metadata, so the form-meta path
  is the portable answer across both compilation hosts.

  ## rf2-3xq1v — whether that string is USABLE

  Picking the right string is not enough: both compilation hosts hand the
  macro only the CLASSPATH-RELATIVE portion of the path
  (`counter_with_stories/stories.cljs`, never the on-disk location). The
  browser-side checkout-root pipeline that used to supply the missing root
  was retired once the dev-server endpoint could resolve a relative
  coordinate itself — but the endpoint is only the PREFERRED path. On any
  non-2xx the open-in-editor client falls back to an `editor://` URI, and
  a 422 is exactly what the endpoint answers when `launch-editor` declines
  a coordinate-bearing request. With a relative `:file` and no project-root
  configured, that fallback ships
  `windsurf://file/counter_with_stories/stories.cljs:196:3` — a path no
  editor's scheme handler can resolve, and a chip that silently misses.

  Story's macro pipeline is separate from core's, so it did not inherit
  core's compile-time absolutisation (rf2-wvsxg) when core grew it.
  `re-frame.story.macros/coords-form` now delegates to
  `re-frame.source-coords/coords-form` outright, which resolves the
  classpath-relative `:file` through the context class-loader ON THE JVM,
  AT MACROEXPANSION, and bakes the absolute path into the emitted literal.
  Nothing resolves anything in the browser.

  ## Why the pins below are shape assertions, not literals

  The absolutised path is the BUILDING MACHINE's path, so every assertion
  here is computed — `absolute-path?`, an on-disk existence check, and a
  suffix match against the classpath-relative tail. A literal expected path
  would pin one developer's checkout.

  JVM-only: the macro helpers live in `.clj`, visible only from the JVM.
  The CLJS half — a real registration compiled by shadow-cljs, driven
  through the 422 decline into the URI fallback — is
  `re-frame.story-open-in-editor-cljs-test`
  §`endpoint-422-falls-back-to-an-absolute-uri-for-a-real-story-coord`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.story :as rf.story]
            [re-frame.story.macros :as rf.story.macros]))

;; ---- helpers ----------------------------------------------------------------

(defn- eval-coords-form
  "Evaluate what `coords-form` returns. Since rf2-3xq1v that is a MAP
  LITERAL built at expansion time (rf2-i3dvj evaluation-order
  transparency) rather than a `cond->` form — but its `:ns` slot holds a
  `(quote sym)` form, so `eval` is still how the test reads back the map
  the macro expansion would bind."
  [form-meta file ns-sym]
  (eval (rf.story.macros/coords-form form-meta file ns-sym)))

;; A real Story source file that IS on this JVM gate's classpath, so the
;; classpath probe inside `absolutise-file` has something to resolve.
;; `counter_with_stories/stories.cljs` — the coordinate the rf2-3xq1v audit
;; measured — resolves under the shadow-cljs compile (`tools/story/testbeds`
;; is a source path there) but not under `clojure -M:test`, whose classpath
;; is `src` + `test`. Absolutisation degrades to a pass-through for an
;; unresolvable path by design, so pinning that literal here would assert
;; nothing at all. The CLJS half of this net exercises a real testbed-shaped
;; registration under the real toolchain.
(def ^:private on-classpath-story-file
  "re_frame/story/macros.clj")

(defn- assert-absolute-and-real!
  "Shared shape assertions for an absolutised `:file`: absolute per the
  same predicate `rf.source-coords.editor-uri/compose-path` uses, present on disk, and
  still ending in the classpath-relative tail it started as."
  [path tail because]
  (is (rf.source-coords.editor-uri/absolute-path? path)
      (str because " — :file must be ABSOLUTE, got " (pr-str path)))
  (is (str/ends-with? (str/replace path "\\" "/") tail)
      (str because " — the absolutised path must still end in the "
           "classpath-relative tail " (pr-str tail)))
  (is (.exists (io/file path))
      (str because " — the absolutised path must name a file that "
           "exists on disk (a rename must fail loudly here)")))

;; ---- rf2-ulxi: which string gets picked ------------------------------------

(deftest file-prefers-form-meta-over-bound-file
  (testing "rf2-ulxi: when (meta &form) carries :file, that wins over the
            *file* arg — covers the CLJS analyzer case where *file* is
            unbound (or stuck at NO_SOURCE_PATH) but the reader has
            attached :file to the form's metadata"
    (let [coords (eval-coords-form
                   {:line 196 :column 3 :file on-classpath-story-file}
                   "NO_SOURCE_PATH"                ; the CLJS macro-expansion default
                   're-frame.story.macros)]
      (is (not= "NO_SOURCE_PATH" (:file coords))
          ":file must NOT be the cljs.analyzer NO_SOURCE_PATH sentinel")
      (assert-absolute-and-real!
        (:file coords) on-classpath-story-file
        "rf2-3xq1v: form-meta :file is picked AND absolutised")
      (is (= 196 (:line coords)) ":line still captured")
      (is (= 3 (:column coords)) ":column still captured")
      (is (= 're-frame.story.macros (:ns coords))
          ":ns still captured"))))

(deftest file-falls-back-to-bound-file-when-form-meta-missing
  (testing "JVM compilation: (meta &form) has no :file (clojure.lang.LispReader
            only attaches :line/:column), so coords-form falls back to the
            *file* arg the macro captured — and absolutises THAT too"
    (let [coords (eval-coords-form
                   {:line 42 :column 1}            ; JVM reader — no :file
                   on-classpath-story-file
                   're-frame.story.macros)]
      (assert-absolute-and-real!
        (:file coords) on-classpath-story-file
        "rf2-3xq1v: the *file* fallback is absolutised on the same path")
      (is (= 42 (:line coords)))
      (is (= 1  (:column coords)))
      (is (= 're-frame.story.macros (:ns coords))))))

(deftest unresolvable-file-passes-through-unchanged
  (testing "rf2-3xq1v: absolutisation is a RESOLUTION, not a string join —
            a path the class-loader cannot find (an in-jar source, a
            synthetic coord, a REPL eval) degrades to the input unchanged
            rather than being prefixed with a guessed root. This is the
            case the public :rf.story/project-root knob still serves, and
            it is why that knob was deliberately kept."
    (let [coords (eval-coords-form
                   {:line 7 :column 2 :file "no/such/story_file.cljs"}
                   "NO_SOURCE_PATH"
                   'some.ns)]
      (is (= "no/such/story_file.cljs" (:file coords))
          "unresolvable :file ships verbatim — no fabricated absolute path")
      (is (not (rf.source-coords.editor-uri/absolute-path? (:file coords)))))))

(deftest file-omitted-when-both-sources-are-sentinel
  (testing "rf2-ulxi: if BOTH (meta &form) :file and *file* resolve to
            NO_SOURCE_PATH (pathological cljs case), omit :file entirely
            rather than poisoning the slot with the sentinel"
    (let [coords (eval-coords-form
                   {:line 1 :column 1 :file "NO_SOURCE_PATH"}
                   "NO_SOURCE_PATH"
                   'some.ns)]
      (is (not (contains? coords :file))
          ":file is omitted rather than carrying the NO_SOURCE_PATH sentinel")
      (is (= 1 (:line coords)))
      (is (= 'some.ns (:ns coords))))))

(deftest file-omitted-when-both-sources-are-nil
  (testing "no :file anywhere — omit the slot"
    (let [coords (eval-coords-form
                   {:line 7 :column 3}             ; no :file in meta
                   nil                              ; no *file* either
                   'some.ns)]
      (is (not (contains? coords :file))
          ":file is omitted when no source is available")
      (is (= 7 (:line coords))))))

(deftest reg-variant-macro-end-to-end
  (testing "rf2-ulxi end-to-end: the gen-reg-call expansion (which
            reg-variant feeds) carries the form-meta :file through to
            the *pending-coords* binding form — covers the actual macro
            path Story uses for variants whose :script sequences emit
            :rf.assert/* events"
    (let [form-meta {:line 42 :column 3 :file on-classpath-story-file}
          expansion (rf.story.macros/gen-reg-call
                      form-meta
                      "NO_SOURCE_PATH"             ; simulate CLJS *file*
                      'my.app.stories
                      'irrelevant-reg-fn
                      :my.app/variant
                      {:doc "x"})
          ;; The expansion is `(when ... (binding [*pending-coords*
          ;; <coords-map>] ...))`. Pull the literal out of the binding
          ;; vector and evaluate it (its `:ns` slot is a `(quote ...)`).
          ;; Structure: (when _ (binding [_ <coords>] _))
          binding-form (-> expansion (nth 2))      ; (binding [...] ...)
          coords       (eval (-> binding-form second second))]
      (is (not= "NO_SOURCE_PATH" (:file coords))
          "NO_SOURCE_PATH must not leak into the coords map")
      (assert-absolute-and-real!
        (:file coords) on-classpath-story-file
        "rf2-3xq1v: the emitted *pending-coords* literal carries the
         absolutised path, not the classpath-relative one")
      (is (= 42 (:line coords)))
      (is (= 3  (:column coords)))
      (is (= 'my.app.stories (:ns coords))))))

;; ---- rf2-3xq1v: a REAL Story registration, pinned absolute -----------------
;;
;; Everything above drives `coords-form` directly. This drives the actual
;; `rf.story/reg-story` macro at a real source location in a real Story
;; artefact file, and reads the coordinate back off the registrar the way
;; every consumer does — `(rf.story/handler-meta :story id)` → `:source`.
;; The file this registration sits in IS on the gate's classpath, so the
;; class-loader probe has a genuine resolution to make.

(deftest a-real-story-registration-stamps-an-absolute-file
  (testing "rf2-3xq1v: a registration written the way a Story author writes
            one carries an ABSOLUTE :file in its :source slot. Before the
            fix this was `re_frame/story_source_coords_test.clj` — the
            classpath-relative tail on its own."
    (rf.story/reg-story :story.source-coords.absolute-pin
      {:doc "rf2-3xq1v fixture — the coordinate under test IS this form's."})
    (let [coord (:source (rf.story/handler-meta :story :story.source-coords.absolute-pin))]
      (is (some? coord) "the registration carries a :source coord at all")
      (assert-absolute-and-real!
        (:file coord) "re_frame/story_source_coords_test.clj"
        "rf2-3xq1v: a real Story registration's :file")
      (is (pos-int? (:line coord)) ":line is captured from the real form")
      (is (pos-int? (:column coord)) ":column is captured from the real form")
      (is (= 're-frame.story-source-coords-test (:ns coord))
          ":ns names the registering namespace"))))

;; ---- rf2-3xq1v: the 422 → URI fallback, with that coordinate ---------------
;;
;; `re-frame.source-coords.open-endpoint/fetch-launcher!` invokes the
;; caller's `fallback!` thunk on ANY non-2xx, and 422 is the status
;; `re-frame.testbed.open-in-editor-server` answers when launch-editor
;; declines a coordinate-bearing request (the Windsurf case the audit
;; measured). The fallback resolves the coord through
;; `rf.source-coords.editor-uri/editor-uri` with whatever `:project-root` the host set —
;; nil for a repository dev testbed, since the browser-side checkout-root
;; pipeline is gone. That composition is `.cljc` and pure, so the shape the
;; fallback ships is pinnable here; the CLJS half drives the same coordinate
;; through the real client seam.

(deftest declined-endpoint-falls-back-to-a-resolvable-uri
  (testing "rf2-3xq1v: with NO project-root configured — the state every
            repository dev testbed is in since the checkout-root pipeline
            was retired — the URI the 422 fallback ships for a real Story
            coordinate names an absolute path the editor can open"
    (rf.story/reg-story :story.source-coords.fallback-pin
      {:doc "rf2-3xq1v fixture — this form's coordinate feeds the URI below."})
    (let [coord (:source (rf.story/handler-meta :story :story.source-coords.fallback-pin))
          ;; Exactly what `open-in-editor/resolve-uri` builds when
          ;; `config/get-project-root` returns nil.
          uri   (rf.source-coords.editor-uri/editor-uri :windsurf coord {:project-root nil})]
      (is (str/starts-with? uri "windsurf://file/")
          "the fallback is the historic editor:// URI path, unchanged")
      (let [path (-> uri
                     (subs (count "windsurf://file/"))
                     (str/replace #":\d+:\d+$" ""))]
        (assert-absolute-and-real!
          path "re_frame/story_source_coords_test.clj"
          "rf2-3xq1v: the URI the declined endpoint falls back to")
        (is (not= "re_frame/story_source_coords_test.clj" path)
            "the pre-fix shape — a bare classpath-relative path in the URI —
             is exactly what the editor could not resolve"))))
  (testing "rf2-3xq1v: an explicitly configured :rf.story/project-root does
            NOT double-prefix an already-absolute coord. The knob is kept
            for external / static / non-shadow hosts, and it stays inert
            over a coordinate the macro already absolutised."
    (let [coord (:source (rf.story/handler-meta :story :story.source-coords.fallback-pin))
          bare  (rf.source-coords.editor-uri/editor-uri :windsurf coord {:project-root nil})
          rooted (rf.source-coords.editor-uri/editor-uri :windsurf coord
                                        {:project-root "/some/external/root"})]
      (is (= bare rooted)
          "an absolute :file passes through compose-path untouched"))))
