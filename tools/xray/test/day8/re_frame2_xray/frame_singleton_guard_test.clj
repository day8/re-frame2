(ns day8.re-frame2-xray.frame-singleton-guard-test
  "Guard test for the EPIC rf2-1w07r frame-singleton class.

  Xray was historically locked to a singleton `:rf/xray` frame: the
  shell hardcoded a scope-only `[frame-provider {:frame
  :rf/xray}]` and every out-of-render affordance dispatched a bare `{:frame :rf/xray}`
  literal. Two shells on one page then collided on the one global
  app-db. The de-singleton refactor (rf2-lnluk + rf2-r0o63)
  parameterized the shell frame and made every out-of-render dispatch
  capture the SURROUNDING instance frame via a frame-bound dispatch
  (`reg-view`'s injected `dispatch` / `(:dispatch (rf/capture-frame))` /
  `rf/current-frame-id`), so N instances stay isolated.

  This SOURCE-TEXT guard (JVM, runs in the fast `clojure -M:test`
  gate) flags the two singleton-class anti-patterns so they cannot
  regress into a de-singletoned file:

    A. a bare `{:frame :rf/xray}` literal — the entrenched singleton
       envelope. The ONE permitted `:rf/xray` literal is
       `defaults/default-frame-id` (a `(def … :rf/xray)`, NOT a
       `{:frame :rf/xray}` map literal), so it never trips this guard.

    B. a global `rf/dispatch` / `rf/dispatch-sync` wired directly to an
       `:on-*` handler — the bare global dispatch that leaks to
       `:rf/default` after render unwinds. The fix is to dispatch
       through a captured frame-aware dispatcher (the reg-view-injected
       `dispatch`, a threaded `dispatch-fn`, or `(:dispatch (rf/capture-frame))`).

  ## Migration state — COMPLETE (rf2-1w07r EPIC closed via rf2-nesy9)

  The de-singleton chain ran to completion: the SHELL + named
  affordances (`shell.cljs`, `views/edn_inspector.cljs`,
  `views/resizable_table.cljs`, `panels/shared/coord_chip.cljs`,
  `panels/shared/coord_link.cljs`) landed first; the rf2-nesy9 sweep
  then migrated EVERY remaining surface — filters, palette, the static
  shell + machines + routes surfaces, the machine / cancellation /
  trace / issues / app-db-segment panels, the epoch pipeline, the share
  + settings + spine-filter modals, and the resize handles. (The
  edn-widget copy affordance was also migrated by that sweep; it has
  since been retired outright under rf2-6r9j.24.) The
  `pending-migration` allowlist below is now
  EMPTY: every file is LOCKED clean by this guard, as is any NEW file.
  Re-introducing a bare `{:frame :rf/xray}` literal or a global
  `rf/dispatch` in an `:on-*` handler trips the guard immediately.

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
  form (the shell-view's own out-of-render reads used this before
  rf2-lnluk threaded the instance frame-id)."
  #"\(rf/subscribe\s+:rf/xray\b")

(def ^:private on-handler-global-dispatch-pattern
  "Matches an `:on-<event>` attr wired DIRECTLY to a global
  `rf/dispatch` / `rf/dispatch-sync` — e.g. `:on-click #(rf/dispatch …)`
  or `:on-click (rf/dispatch …)`. The fix is to route through a
  captured frame-aware dispatcher."
  #":on-[a-z-]+\s+#?\(rf/dispatch(-sync)?\b")

(def ^:private deferred-fn-bare-dispatch-pattern
  "Matches the DEFERRED-MULTILINE bare-dispatch leak the adjacency-only
  `on-handler-global-dispatch-pattern` above misses (rf2-16y3x): an
  `:on-<event>` wired to a multi-line `(fn [args] …)` whose FIRST body
  form is a single-arg bare `(rf/dispatch [ev])` / `(rf/dispatch-sync
  [ev])` carrying NO `{:frame …}` opt —

      :on-click    (fn [_e]
                     (rf/dispatch [:some/event]))

  This is exactly the reactive-panel unchanged-subs toggle bug: after
  render scope unwinds the ambient frame is gone, so the bare dispatch
  leaks to `:rf/default` / emits `:rf.error/no-frame-context`, leaving
  the instance's state untouched. `\\s` spans newlines (Java regex), so
  the callback body need not be single-line. NARROW by design: it does
  NOT trip a dispatch carrying an explicit `{:frame frame}` opt (the
  `\\]\\s*\\)` tail requires the event vector to close the dispatch call)
  nor a callback whose first form is a guard like `(.stopPropagation e)`
  — those are the correct/pre-existing shapes. The fix is to call a
  captured frame-aware dispatcher (the reg-view-injected `dispatch`
  threaded down, a `dispatch-fn`, or `(:dispatch (rf/capture-frame))`)."
  #":on-[a-z-]+\s+#?\(fn\s+\[[^\]]*\]\s*\(rf/dispatch(-sync)?\s+\[[^\]]*\]\s*\)")

;; ---- allowlist ----------------------------------------------------------

(def ^:private pending-migration
  "Files NOT YET de-singletoned. The rf2-nesy9 sweep BURNED THIS DOWN TO
  EMPTY — every panel / modal / static surface now captures the
  surrounding instance frame (the reg-view-injected `dispatch`, a
  threaded `dispatch-fn`, or a render-time `(rf/current-frame-id)` capture)
  rather than pinning the `:rf/xray` singleton. This CLOSES the
  rf2-1w07r frame-singleton EPIC: N isolated Xray instances on one page
  each route to their own frame.

  The set is now EMPTY and must STAY empty — any file that trips a
  pattern is a regression. Keyed on the path RELATIVE to
  `src/day8/re_frame2_xray/` (it must never GROW).

  ## The ONE permitted `:rf/xray` reference — and why it isn't here

  The few legitimate production-singleton seams — the trace-collector
  `note-suppressed!` dual-write (`config.cljc`) and the per-feature
  `hydrate!` init-seams (`spine-filters`, `machine-canvas`,
  `static/machines/persistence`) — (the share-URL on-load restore in
  `share.cljs` was another such seam until rf2-nugvv removed the whole
  share surface) —
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
  — the multiline scan the line-by-line `offenders` can't do (rf2-16y3x).
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
        (str "rf2-1w07r — bare `{:frame :rf/xray}` / `(rf/subscribe "
             ":rf/xray …)` literal found in a de-singletoned (or new) "
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
        (str "rf2-1w07r — a global `rf/dispatch` / `rf/dispatch-sync` is "
             "wired directly to an `:on-*` handler in a de-singletoned "
             "(or new) file. After render unwinds the ambient frame is "
             "gone, so a bare global dispatch leaks to `:rf/default`. "
             "Dispatch through a captured frame-aware dispatcher (the "
             "reg-view-injected `dispatch`, a threaded `dispatch-fn`, or "
             "`(:dispatch (rf/capture-frame))`). Offenders:\n  "
             (report offs)))))

(deftest no-deferred-fn-bare-dispatch-in-migrated-files
  ;; rf2-16y3x — the reactive-panel unchanged-subs toggle installed a
  ;; DEFERRED `(fn [_e] (rf/dispatch [ev]))` on-click. The bare dispatch
  ;; fired after render scope unwound (ambient frame gone) → leaked to
  ;; `:rf/default` / `:rf.error/no-frame-context`, leaving Xray state
  ;; untouched. The adjacency-only guard above missed the multiline
  ;; callback; this whole-file scan catches it so it cannot regress.
  (let [offs (multiline-offenders deferred-fn-bare-dispatch-pattern
                                  :deferred-fn-bare-dispatch)]
    (is (empty? offs)
        (str "rf2-16y3x — a DEFERRED multiline bare `(fn [args] (rf/dispatch "
             "[ev]))` (no `{:frame …}` opt, dispatch as the callback's first "
             "form) is wired to an `:on-*` handler in a de-singletoned (or "
             "new) file. After render unwinds the ambient frame is gone, so "
             "the bare dispatch leaks to `:rf/default` and the instance's "
             "state never changes. Call a captured frame-aware dispatcher "
             "(the reg-view-injected `dispatch` threaded down, a "
             "`dispatch-fn`, or `(:dispatch (rf/capture-frame))`). "
             "Offenders:\n  "
             (report offs)))))

(deftest deferred-fn-bare-dispatch-pattern-catches-multiline-callback
  ;; Causal red/green for the guard (rf2-16y3x): the pattern MUST match the
  ;; deferred multiline bug shape (which the old adjacency-only pattern
  ;; missed) and MUST NOT match the fixed shape (a threaded `dispatch`) nor
  ;; a dispatch carrying an explicit `{:frame …}` opt nor a guard-first
  ;; callback. Proves the strengthened guard is causally sound without a
  ;; parser.
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
    ;; The OLD adjacency-only pattern demonstrably MISSED the multiline shape.
    (is (not (re-find on-handler-global-dispatch-pattern bug))
        "the pre-existing adjacency-only guard missed this multiline callback")
    (is (not (re-find deferred-fn-bare-dispatch-pattern fixed))
        "does NOT flag a threaded frame-aware `dispatch` (the fix)")
    (is (not (re-find deferred-fn-bare-dispatch-pattern framed))
        "does NOT flag a dispatch carrying an explicit {:frame …} opt")
    (is (not (re-find deferred-fn-bare-dispatch-pattern guarded))
        "does NOT flag a guard-first callback with an explicit frame opt")))

(deftest pending-migration-allowlist-stays-honest
  ;; The allowlist must only name files that ACTUALLY still carry a
  ;; legacy pattern. A stale entry (file already migrated but still
  ;; allowlisted) would silently mask a future regression in that file
  ;; — so flag it so the rf2-nesy9 sweep prunes the entry as it lands.
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
        (str "rf2-1w07r / rf2-nesy9 — these files are on the "
             "`pending-migration` allowlist but no longer carry any "
             "legacy frame-singleton pattern. Remove them from the "
             "allowlist so the guard locks them clean:\n  "
             (str/join "\n  " stale)))))
