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

  ## Coverage

  The `pending-migration` allowlist below is EMPTY, so every file under
  `src/day8/re_frame2_xray/` is LOCKED clean by this guard, as is any
  NEW file. A bare `{:frame :rf/xray}` literal or a global `rf/dispatch`
  in an `:on-*` handler anywhere in that tree trips the guard.

  Mirrors the source-text-guard shape of
  `panels/click_to_source_consolidation_test.clj`."
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

;; ---- allowlist ----------------------------------------------------------

(def ^:private pending-migration
  "Files exempt from the guard, keyed on the path RELATIVE to
  `src/day8/re_frame2_xray/`. The set is EMPTY and must STAY empty —
  every panel / modal / static surface captures the surrounding instance
  frame (the reg-view-injected `dispatch`, a threaded `dispatch-fn`, or a
  render-time `(rf/current-frame-id)` capture) rather than pinning the
  `:rf/xray` singleton, so N isolated Xray instances on one page each
  route to their own frame. Any file that trips a pattern is a
  regression.

  ## The ONE permitted `:rf/xray` reference — and why it isn't here

  The few legitimate production-singleton seams — the trace-collector
  `note-suppressed!` dual-write (`config.cljc`) and the per-feature
  `hydrate!` init-seams (`spine-filters`, `machine-canvas`,
  `static/machines/persistence`) —
  target the ONE production shell frame via the NAMED
  `defaults/default-frame-id` Var, NOT a bare `{:frame :rf/xray}` map
  literal. The named Var never trips `frame-literal-pattern`, so those
  seams stay clean without an allowlist entry. They are infra for the
  single in-app shell (no surrounding render/event frame exists at
  init / trace-bus time), not per-instance render affordances."
  #{})

;; ---- file walk ----------------------------------------------------------

(defn- src-root []
  ;; Resolve `src/day8/re_frame2_xray` on disk so the guard reads the
  ;; on-disk source text. Every shipped invocation runs from `tools/xray`
  ;; (`clojure -M:test`, typed by hand or driven by
  ;; `scripts/test-jvm-tools.sh`, which cds into the artefact), so a
  ;; cwd-relative `(io/file "src" …)` would happen to work there — but
  ;; keying the walk to cwd makes this guard fail OPEN anywhere else, and
  ;; silently: from any other working directory (a REPL or editor rooted at
  ;; the repo root) the path resolves to a non-directory, the walk yields no
  ;; files, and every `(is (empty? …))` below passes having scanned nothing.
  ;; `src` is a classpath `:paths` root, so a known `.cljs` source is a
  ;; classpath resource on the JVM regardless of cwd; its parent dir is the
  ;; src-root. Fall back to the cwd-relative path if the resource is absent
  ;; (e.g. a jar).
  (let [marker (io/resource "day8/re_frame2_xray/defaults.cljs")]
    (if (and marker (= "file" (.getProtocol marker)))
      (.getParentFile (io/file (.toURI marker)))
      (io/file "src" "day8" "re_frame2_xray"))))

(defn- rel-path
  "Path of `f` relative to the `src/day8/re_frame2_xray/` root, with
  forward slashes — the key shape `pending-migration` uses."
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
  non-allowlisted file whose CODE lines match `pattern`."
  [pattern pattern-kw]
  (let [root (src-root)]
    (->> (cljs-source-files root)
         (remove #(pending-migration (rel-path root %)))
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
  "Return a seq of `{:file rel :pattern <kw>}` for every non-allowlisted
  file whose WHOLE SOURCE TEXT (comment lines stripped) matches `pattern`
  — the multiline scan the line-by-line `offenders` can't do.
  No reader / parser: one DOTALL-free `re-find` over the file text, where
  `\\s` already spans newlines."
  [pattern pattern-kw]
  (let [root (src-root)]
    (->> (cljs-source-files root)
         (remove #(pending-migration (rel-path root %)))
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
  ;; Causal red/green for the guard: the pattern MUST match the deferred
  ;; multiline bug shape (which the adjacency-only pattern misses) and MUST
  ;; NOT match the correct shape (a threaded `dispatch`) nor a dispatch
  ;; carrying an explicit `{:frame …}` opt nor a guard-first callback.
  ;; Proves the guard is causally sound without a parser.
  (let [bug     (str ":on-click    (fn [_e]\n"
                     "               (rf/dispatch [:rf.xray/reactive-toggle-unchanged]))")
        bug-1ln ":on-click (fn [_e] (rf/dispatch [:x/y]))"
        fixed   (str ":on-click    (fn [_e]\n"
                     "               (dispatch [:rf.xray/reactive-toggle-unchanged]))")
        framed  (str ":on-click (fn [^js e]\n"
                     "            (rf/dispatch [:x/y] {:frame frame}))")
        guarded (str ":on-click (fn [e]\n"
                     "            (.stopPropagation e)\n"
                     "            (rf/dispatch [:x/y] {:frame frame}))")]
    (is (re-find deferred-fn-bare-dispatch-pattern bug)
        "catches the multiline deferred bare-dispatch bug shape")
    (is (re-find deferred-fn-bare-dispatch-pattern bug-1ln)
        "also catches the single-line form")
    ;; The adjacency-only pattern demonstrably MISSES the multiline shape.
    (is (not (re-find on-handler-global-dispatch-pattern bug))
        "the adjacency-only guard misses this multiline callback")
    (is (not (re-find deferred-fn-bare-dispatch-pattern fixed))
        "does NOT flag a threaded frame-aware `dispatch` (the fix)")
    (is (not (re-find deferred-fn-bare-dispatch-pattern framed))
        "does NOT flag a dispatch carrying an explicit {:frame …} opt")
    (is (not (re-find deferred-fn-bare-dispatch-pattern guarded))
        "does NOT flag a guard-first callback with an explicit frame opt")))

(deftest pending-migration-allowlist-stays-honest
  ;; The allowlist may only name files that ACTUALLY carry a
  ;; frame-singleton pattern. A stale entry (a clean file left on the
  ;; allowlist) would silently mask a future regression in that file —
  ;; so flag it for removal.
  (let [root  (src-root)
        files-by-rel (into {} (map (fn [f] [(rel-path root f) f]))
                           (cljs-source-files root))
        any-pattern (fn [text]
                      (let [code (code-lines text)]
                        (some (fn [line]
                                (or (re-find frame-literal-pattern line)
                                    (re-find subscribe-literal-pattern line)
                                    (re-find on-handler-global-dispatch-pattern line)))
                              code)))
        stale (->> pending-migration
                   (filter (fn [rel]
                             (when-let [f (files-by-rel rel)]
                               (not (any-pattern (slurp f)))))))]
    (is (empty? stale)
        (str "these files are on the "
             "`pending-migration` allowlist but carry no "
             "frame-singleton pattern. Remove them from the "
             "allowlist so the guard locks them clean:\n  "
             (str/join "\n  " stale)))))
