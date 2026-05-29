(ns day8.re-frame2-xray.panels.click-to-source-consolidation-test
  "Guard test for rf2-vw5pi — the click-to-source consolidation.

  Every panel-side 'open in editor' affordance routes through ONE of
  two shared helpers: `panels/shared/coord_chip.cljs` (icon-only chip)
  or `panels/shared/coord_link.cljs` (label-as-link + the shared
  `open-in-editor!` dispatch action). No panel file may re-inline the
  `[:rf.xray/open-in-editor …]` dispatch — that boilerplate-and-its-
  fallback-branch pattern was hand-rolled ~11 times before rf2-vw5pi.

  This is a SOURCE-TEXT guard (JVM, runs in the fast `clojure -M:test`
  gate): it greps the panel source tree for the dispatch literal and
  asserts it appears ONLY in the sanctioned homes. A future
  contributor who hand-rolls a new bespoke open-in-editor button trips
  this immediately — the fix is to route through `coord-chip` /
  `coord-link` (or, for an SVG-node click that can't mount a button,
  bind `coord-link/open-in-editor!` to `:on-click`)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private dispatch-pattern
  "Matches a `(... dispatch [:rf.xray/open-in-editor …])` CALL — the
  bespoke open-in-editor dispatch the consolidation eliminates. Keyed
  on the `dispatch [:` shape (not the bare keyword) so DOCSTRINGS and
  COMMENTS that merely NAME the event — e.g. the static-page surfaces
  that reference it in prose, or a panel docstring noting 'clicks
  dispatch :rf.xray/open-in-editor' — do not false-positive. Only an
  actual `dispatch`-call form trips the guard."
  #"dispatch\s*\[:rf\.xray/open-in-editor")

(def ^:private sanctioned
  "Files allowed to carry the open-in-editor dispatch CALL:

  - `panels/shared/coord_chip.cljs` — the icon-only chip (rf2-xjgdk).
  - `panels/shared/coord_link.cljs` — the label-as-link companion +
    the shared `open-in-editor!` dispatch action (rf2-vw5pi). Every
    panel-side affordance funnels its dispatch through here (or
    through `coord-chip`).

  `open_in_editor.cljs` (the reg-event-fx receiver + the static-page
  `<a href>` anchor, excluded by design per rf2-evgf5 / rf2-g5q8d) is
  NOT listed because it never emits a `dispatch`-CALL form — it
  DEFINES the event (`reg-event-fx :rf.xray/open-in-editor`), so the
  `dispatch [:` pattern never matches it."
  #{"coord_chip.cljs"
    "coord_link.cljs"})

(defn- src-root []
  ;; The `:test` alias runs from `tools/xray`; `src` is on the classpath
  ;; root. Resolve the source tree relative to the working directory so
  ;; the guard reads the on-disk text (not a classpath resource).
  (io/file "src" "day8" "re_frame2_xray"))

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
    (let [offenders
          (->> (cljs-source-files root)
               (keep (fn [^java.io.File f]
                       (let [name (.getName f)]
                         (when (and (not (sanctioned name))
                                    (re-find dispatch-pattern (slurp f)))
                           (.getPath f))))))]
      (is (empty? offenders)
          (str "These files inline a `dispatch [:rf.xray/open-in-editor …]` "
               "call but are not a sanctioned shared helper. Route the "
               "affordance through `panels/shared/coord_chip.cljs` "
               "(icon-only), `panels/shared/coord_link.cljs` (label), or "
               "bind `coord-link/open-in-editor!` for an SVG-node click:\n  "
               (str/join "\n  " offenders))))))
