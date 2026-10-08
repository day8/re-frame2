(ns day8.re-frame2-xray.frame-singleton-guard-test
  "Guard test for the frame-singleton class.

  Xray's shell takes its frame as a parameter, and every out-of-render
  dispatch captures the SURROUNDING instance frame via a frame-bound
  dispatch (`reg-view`'s injected `dispatch` / `(:dispatch
  (rf/capture-frame))` / `rf/current-frame-id`), so N instances stay
  isolated. A shell hardcoding a scope-only `[frame-provider {:frame
  :rf/xray}]`, or an out-of-render affordance dispatching a bare
  `{:frame :rf/xray}` literal, would lock Xray to a singleton `:rf/xray`
  frame, and two shells on one page would collide on the one global
  app-db.

  This SOURCE-TEXT guard (JVM, runs in the fast `clojure -M:test`
  gate) flags the two singleton-class anti-patterns in every Xray source
  file:

    A. a bare `{:frame :rf/xray}` literal — the entrenched singleton
       envelope. The ONE permitted `:rf/xray` literal is
       `defaults/default-frame-id` (a `(def … :rf/xray)`, NOT a
       `{:frame :rf/xray}` map literal), so it never trips this guard.

    B. a global `rf/dispatch` / `rf/dispatch-sync` wired directly to an
       `:on-*` handler — the bare global dispatch that raises
       `:rf.error/no-frame-context` after render unwinds (EP-0002:
       there is no `:rf/default` floor, and React's no-provider context
       default is a sentinel rather than a frame id). The fix is to
       dispatch through a captured frame-aware dispatcher (the
       reg-view-injected `dispatch`, a threaded `dispatch-fn`, or
       `(:dispatch (rf/capture-frame))`).

  Every `.cljs`/`.cljc` file under `src/day8/re_frame2_xray/` is checked;
  there is no allowlist."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---- patterns -----------------------------------------------------------

(def ^:private frame-literal-pattern
  "Matches a bare `{:frame :rf/xray}` map literal. Whitespace-tolerant
  between the key and value so a multi-line dispatch's frame opt still
  matches. Docstring / comment occurrences are filtered out per-line
  before the match is counted (see `code-lines`)."
  #"\{\s*:frame\s+:rf/xray\s*\}")

(def ^:private subscribe-literal-pattern
  "Matches `(rf/subscribe :rf/xray …)` — the positional-frame subscribe
  form, which pins the read to the singleton frame rather than the
  instance frame."
  #"\(rf/subscribe\s+:rf/xray\b")

(def ^:private on-handler-global-dispatch-pattern
  "Matches an `:on-<event>` attr wired DIRECTLY to a global
  `rf/dispatch` / `rf/dispatch-sync` — e.g. `:on-click #(rf/dispatch …)`
  or `:on-click (rf/dispatch …)`. The fix is to route through a
  captured frame-aware dispatcher."
  #":on-[a-z-]+\s+#?\(rf/dispatch(-sync)?\b")

(def ^:private deferred-fn-bare-dispatch-pattern
  "Matches the DEFERRED-MULTILINE bare-dispatch leak the adjacency-only
  `on-handler-global-dispatch-pattern` above misses: an
  `:on-<event>` wired to a multi-line `(fn [args] …)` whose FIRST body
  form is a single-arg bare `(rf/dispatch [ev])` / `(rf/dispatch-sync
  [ev])` carrying NO `{:frame …}` opt —

      :on-click    (fn [_e]
                     (rf/dispatch [:some/event]))

  After render scope unwinds the ambient frame is gone, so the bare
  dispatch raises `:rf.error/no-frame-context`, leaving the instance's
  state untouched. `\\s` spans newlines (Java regex), so
  the callback body need not be single-line. NARROW by design: it does
  NOT trip a dispatch carrying an explicit `{:frame frame}` opt (the
  `\\]\\s*\\)` tail requires the event vector to close the dispatch call)
  nor a callback whose first form is a guard like `(.stopPropagation e)`
  — those are correct shapes. The fix is to call a
  captured frame-aware dispatcher (the reg-view-injected `dispatch`
  threaded down, a `dispatch-fn`, or `(:dispatch (rf/capture-frame))`)."
  #":on-[a-z-]+\s+#?\(fn\s+\[[^\]]*\]\s*\(rf/dispatch(-sync)?\s+\[[^\]]*\]\s*\)")

;; ---- file walk ----------------------------------------------------------

(defn- src-root []
  ;; Resolved through the classpath rather than the cwd, because a
  ;; cwd-relative path that misses yields no files and every guard below
  ;; then passes having scanned nothing.
  (let [marker (io/resource "day8/re_frame2_xray/defaults.cljs")]
    (if (and marker (= "file" (.getProtocol marker)))
      (.getParentFile (io/file (.toURI marker)))
      (io/file "src" "day8" "re_frame2_xray"))))

(defn- rel-path
  "Path of `f` relative to the `src/day8/re_frame2_xray/` root, with
  forward slashes."
  [^java.io.File root ^java.io.File f]
  (-> (.relativize (.toPath root) (.toPath f))
      (.toString)
      (str/replace "\\" "/")))

(defn- cljs-source-files [^java.io.File dir]
  (->> (file-seq dir)
       (filter #(.isFile ^java.io.File %))
       (filter #(let [n (.getName ^java.io.File %)]
                  (or (str/ends-with? n ".cljs")
                      (str/ends-with? n ".cljc"))))))

(defn- code-lines
  "Source lines that are NEITHER a comment (`;;`-leading) NOR a
  docstring/prose line that merely NAMES the pattern inside a backtick.
  Keeps the guard keyed on actual code forms (mirrors the
  click-to-source guard's docstring-immunity rationale)."
  [^String text]
  (->> (str/split-lines text)
       (remove #(re-find #"^\s*;;" %))
       (remove #(str/includes? % "`"))))

(defn- offenders
  "Return a seq of `{:file rel :pattern <kw> :line <text>}` for every
  file whose CODE lines match `pattern`."
  [pattern pattern-kw]
  (let [root (src-root)]
    (->> (cljs-source-files root)
         (mapcat (fn [^java.io.File f]
                   (->> (code-lines (slurp f))
                        (keep (fn [line]
                                (when (re-find pattern line)
                                  {:file    (rel-path root f)
                                   :pattern pattern-kw
                                   :line    (str/trim line)})))))))))

(defn- strip-comment-lines
  "Drop `;;`-leading comment lines so a whole-file (multiline) scan does
  not match a pattern that appears only in a line comment. Docstrings are
  left intact — the deferred-dispatch pattern is code-shaped and does not
  occur in prose, and stripping them would need a reader."
  [^String text]
  (->> (str/split-lines text)
       (remove #(re-find #"^\s*;;" %))
       (str/join "\n")))

(defn- multiline-offenders
  "Return a seq of `{:file rel :pattern <kw>}` for every file whose WHOLE
  SOURCE TEXT (comment lines stripped) matches `pattern` — the multiline
  scan the line-by-line `offenders` can't do."
  [pattern pattern-kw]
  (let [root (src-root)]
    (->> (cljs-source-files root)
         (keep (fn [^java.io.File f]
                 (when (re-find pattern (strip-comment-lines (slurp f)))
                   {:file (rel-path root f) :pattern pattern-kw}))))))

(defn- report [offs]
  (str/join "\n  "
            (map (fn [{:keys [file line]}]
                   (if line (str file " — " line) (str file)))
                 offs)))

;; ---- tests --------------------------------------------------------------

(deftest src-root-resolves
  (is (.exists (src-root))
      (str "source root must resolve from the test's working directory "
           "(tools/xray); got " (.getPath (src-root)))))

(deftest no-bare-frame-rf-xray-literal-in-migrated-files
  (let [offs (concat (offenders frame-literal-pattern :frame-literal)
                     (offenders subscribe-literal-pattern :subscribe-literal))]
    (is (empty? offs)
        (str "bare `{:frame :rf/xray}` / `(rf/subscribe "
             ":rf/xray …)` literal found in an Xray source "
             "file. The render-tree singleton literal entrenches the "
             "one-shell lock. Capture the surrounding instance frame "
             "instead — the reg-view-injected `dispatch` / `subscribe`, a "
             "threaded `dispatch-fn`, or `(:dispatch (rf/capture-frame))` / "
             "`(rf/current-frame-id)`. (The single permitted `:rf/xray` is "
             "`defaults/default-frame-id`, a bare `def`, not a map "
             "literal.) Offenders:\n  "
             (report offs)))))

(deftest no-global-dispatch-in-on-handler-in-migrated-files
  (let [offs (offenders on-handler-global-dispatch-pattern :on-handler-dispatch)]
    (is (empty? offs)
        (str "a global `rf/dispatch` / `rf/dispatch-sync` is "
             "wired directly to an `:on-*` handler in an Xray source "
             "file. After render unwinds the ambient frame is "
             "gone, so a bare global dispatch raises "
             "`:rf.error/no-frame-context`. "
             "Dispatch through a captured frame-aware dispatcher (the "
             "reg-view-injected `dispatch`, a threaded `dispatch-fn`, or "
             "`(:dispatch (rf/capture-frame))`). Offenders:\n  "
             (report offs)))))

(deftest no-deferred-fn-bare-dispatch-in-migrated-files
  ;; A DEFERRED `(fn [_e] (rf/dispatch [ev]))` on-click fires after render
  ;; scope unwinds (ambient frame gone), so its bare dispatch raises
  ;; `:rf.error/no-frame-context`, leaving Xray state untouched. The
  ;; adjacency-only guard above cannot see a multiline callback; this
  ;; whole-file scan can.
  (let [offs (multiline-offenders deferred-fn-bare-dispatch-pattern
                                  :deferred-fn-bare-dispatch)]
    (is (empty? offs)
        (str "a DEFERRED multiline bare `(fn [args] (rf/dispatch "
             "[ev]))` (no `{:frame …}` opt, dispatch as the callback's first "
             "form) is wired to an `:on-*` handler in an Xray source "
             "file. After render unwinds the ambient frame is gone, so "
             "the bare dispatch raises `:rf.error/no-frame-context` and the "
             "instance's state never changes. Call a captured frame-aware dispatcher "
             "(the reg-view-injected `dispatch` threaded down, a "
             "`dispatch-fn`, or `(:dispatch (rf/capture-frame))`). "
             "Offenders:\n  "
             (report offs)))))

(deftest deferred-fn-bare-dispatch-pattern-catches-multiline-callback
  ;; Positive control: the whole-file guard above can only fail if its
  ;; pattern matches the multiline bug shape.
  (is (re-find deferred-fn-bare-dispatch-pattern
               (str ":on-click    (fn [_e]\n"
                    "               (rf/dispatch [:rf.xray/reactive-toggle-unchanged]))"))))
