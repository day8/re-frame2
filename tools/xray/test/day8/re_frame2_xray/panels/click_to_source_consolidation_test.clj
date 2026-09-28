(ns day8.re-frame2-xray.panels.click-to-source-consolidation-test
  "Guard test for the click-to-source consolidation.

  Every panel-side 'open in editor' affordance routes through ONE of
  two shared helpers: `panels/shared/coord_chip.cljs` (icon-only chip)
  or `panels/shared/coord_link.cljs` (label-as-link + the shared
  `open-in-editor!` dispatch action). No panel file may inline the
  `[:rf.xray/open-in-editor …]` dispatch — that boilerplate-and-its-
  fallback-branch pattern lives in the shared helpers, not hand-rolled
  per panel.

  This is a SOURCE-TEXT guard (JVM, runs in the fast `clojure -M:test`
  gate): it greps the panel source tree for the event-vector literal and
  asserts it appears ONLY in the sanctioned homes — and that it DOES
  appear in each of them, so a pattern that stops seeing the real
  dispatch shape fails here rather than passing having matched nothing. A
  contributor who hand-rolls a bespoke open-in-editor button trips
  this immediately — the fix is to route through `coord-chip` /
  `coord-link` (or, for an SVG-node click that can't mount a button,
  bind `coord-link/open-in-editor!` to `:on-click`)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private dispatch-pattern
  "Matches the `[:rf.xray/open-in-editor …]` EVENT VECTOR written as
  code — the bespoke open-in-editor dispatch the consolidation
  eliminates. Keyed on the vector literal rather than on a `dispatch`
  call, because the sanctioned sites dispatch through a fallback,
  `((or dispatch-fn default-dispatch) [:rf.xray/open-in-editor …])`,
  with no `dispatch` token before the bracket. A backtick in front marks
  the vector as quoted in a docstring, and comment lines are dropped
  before matching (`code-text`), so prose that names the event does not
  trip the guard."
  #"(?<!`)\[:rf\.xray/open-in-editor(?=[\s\]])")

(defn- code-text
  "The file's text with whole-line `;` comments removed."
  [^java.io.File f]
  (->> (str/split-lines (slurp f))
       (remove #(str/starts-with? (str/triml %) ";"))
       (str/join "\n")))

(def ^:private sanctioned
  "Files allowed to carry the open-in-editor dispatch CALL:

  - `panels/shared/coord_chip.cljs` — the icon-only chip.
  - `panels/shared/coord_link.cljs` — the label-as-link companion +
    the shared `open-in-editor!` dispatch action. Every
    panel-side affordance funnels its dispatch through here (or
    through `coord-chip`).

  `open_in_editor.cljs` (the reg-event-fx receiver + the static-page
  `<a href>` anchor, excluded by design) is
  NOT listed because it never builds the event vector — it DEFINES the
  event (`reg-event :rf.xray/open-in-editor`), so the vector pattern
  never matches it."
  #{"coord_chip.cljs"
    "coord_link.cljs"})

(defn- src-root []
  ;; Resolve `src/day8/re_frame2_xray` on disk so the guard reads the
  ;; on-disk source text. Every shipped invocation runs from `tools/xray`
  ;; (`clojure -M:test`, typed by hand or driven by
  ;; `scripts/test-jvm-tools.sh`, which cds into the artefact), so a
  ;; cwd-relative `(io/file "src" …)` would happen to work there — but
  ;; keying the walk to cwd makes this guard fail OPEN anywhere else, and
  ;; silently: from any other working directory (a REPL or editor rooted at
  ;; the repo root) the path resolves to a non-directory, the walk yields no
  ;; files, and the `(is (empty? offenders))` below passes having scanned
  ;; nothing. `src` is a classpath `:paths` root, so a known `.cljs` source
  ;; is a classpath resource on the JVM regardless of cwd; its parent dir is
  ;; the src-root. Fall back to the cwd-relative path if the resource is
  ;; absent (e.g. a jar).
  (let [marker (io/resource "day8/re_frame2_xray/defaults.cljs")]
    (if (and marker (= "file" (.getProtocol marker)))
      (.getParentFile (io/file (.toURI marker)))
      (io/file "src" "day8" "re_frame2_xray"))))

(defn- cljs-source-files [^java.io.File dir]
  (->> (file-seq dir)
       (filter #(.isFile ^java.io.File %))
       (filter #(let [n (.getName ^java.io.File %)]
                  (or (str/ends-with? n ".cljs")
                      (str/ends-with? n ".cljc"))))))

(deftest no-bespoke-open-in-editor-dispatch-outside-shared-helpers
  (let [root (src-root)]
    (is (.exists root)
        (str "source root must resolve from the test's working "
             "directory (tools/xray); got " (.getPath root)))
    (let [files     (cljs-source-files root)
          matching  (filter #(re-find dispatch-pattern (code-text %)) files)
          offenders (->> matching
                         (remove #(sanctioned (.getName ^java.io.File %)))
                         (map #(.getPath ^java.io.File %)))]
      (is (= sanctioned
             (into #{} (comp (map #(.getName ^java.io.File %))
                             (filter sanctioned))
                   matching))
          "the pattern finds the dispatch in every sanctioned helper; if it
          misses one it has stopped seeing the real shape and the offender
          check below guards nothing")
      (is (empty? offenders)
          (str "These files inline a `[:rf.xray/open-in-editor …]` dispatch "
               "call but are not a sanctioned shared helper. Route the "
               "affordance through `panels/shared/coord_chip.cljs` "
               "(icon-only), `panels/shared/coord_link.cljs` (label), or "
               "bind `coord-link/open-in-editor!` for an SVG-node click:\n  "
               (str/join "\n  " offenders))))))
