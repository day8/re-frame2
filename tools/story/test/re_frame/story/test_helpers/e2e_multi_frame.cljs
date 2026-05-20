(ns re-frame.story.test-helpers.e2e-multi-frame
  "Multi-frame end-to-end test harness for Story (rf2-piucm).

  Mirrors the Causa harness at
  `day8.re-frame2-causa.test-helpers.e2e-multi-frame` (rf2-7icrs).
  Story is structurally analogous to Causa — another re-frame2 tool
  that observes / embeds a host. The host here is a Story VARIANT
  frame (each `run-variant` call allocates one); the observer is the
  `:rf/causa` frame.

  ## Why a Story-specific helper

  Causa's harness assumes ONE host frame registered ahead of time.
  Story allocates a fresh frame per variant via
  `re-frame.story.frames/allocate!`, and the lifecycle is driven
  through a state machine (`:rf.story.lifecycle/machine`). Story-
  specific concerns:

  - Canonical vocabulary install (`install-canonical-vocabulary!`)
    needed before any variant lifecycle event fires.
  - The variant frame is allocated by `run-variant`; tests usually
    drive the four-phase lifecycle through that entry point.
  - The shell-state-atom is a separate Reagent ratom holding UI-only
    state (sidebar selection, chrome visibility, etc.) — its lifecycle
    is independent of re-frame frames.

  This helper exposes:

  - `with-story-and-causa-frames` — install Story canonical vocab +
    Causa under `:rf/causa`, run body, tear down. Used for surfaces
    1 + 6 (Causa-in-Story embed + panel routing) where the test needs
    both Story's variant-allocation lifecycle AND Causa's trace-bus
    pipeline live in one process.

  - `dispatch-into-variant` / `sub-in-variant` — convenience wrappers
    that thread the variant frame id through `rf/dispatch-sync` /
    `rf/subscribe` so the test reads as `(dispatch [:my/ev] variant-id)`
    instead of `(rf/dispatch-sync [:my/ev] {:frame variant-id})`.

  - `expand-tree` / `find-by-test-id` / `find-by-data-attr` — pure
    hiccup walkers. Story's chrome surfaces (sidebar, toolbar, chrome
    embed) build hiccup top-down through Reagent function components;
    the walker expands `[fn args...]` nodes by invoking the fn so the
    final hiccup is the tree the renderer would see.

  ## Helper choice — local vs framework

  The framework ships `re-frame.test-helpers` (rf2-irp6j) with a
  similar hiccup-walking surface — but it (a) keys on `:data-testid`
  while Story uses `:data-test`, and (b) eagerly invokes ANY fn-headed
  vector without a class-3 guard, which throws on the Causa embed's
  `r/create-class`-built `panel-host-component`. The walkers here
  handle both — `:data-test` lookups via `find-by-data-attr` and
  graceful-skip of class-3 components when their invocation returns
  non-hiccup. When a framework-level helper supports both, this ns
  can shrink to the harness fns.

  ## Cost

  Each invocation: ~10-15 ms (Story canonical-vocab install + Causa
  install + one variant allocate). Node CLJS, no DOM, no browser.

  ## Teardown

  The body runs inside a try/finally that clears Story registrar,
  clears Causa's trace-bus buffer, and resets the shell-state-atom
  so subsequent tests start from a fresh slate. The
  `reset-runtime-fixture-factory` (which the per-test-file fixture wraps
  around `use-fixtures :each`) handles frame disposal and registrar
  restoration."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.story.ui.state :as ui-state]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as causa-e2e]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- install helpers -----------------------------------------------------

(defn install-story-default!
  "Canonical Story install path used by `with-story-and-causa-frames`
  when the caller did not supply `:install-story`.

  - `story/clear-all!` — wipe the Story side-table. Per the impl,
    this is `registrar/clear-all!` — i.e. drops every registered
    handler, sub, fx, machine, etc. (NOT just Story-side entries).
  - Re-register the framework-shipped `:rf/machine` sub. The
    machines artefact's ns-load registers this; a full
    `registrar/clear-all!` drops it, and CLJS has no
    `require :reload` to re-fire ns-load side effects. We re-fire
    the registration manually here.
  - `story/install-canonical-vocabulary!` — register the
    `:rf.story.lifecycle/machine` + helper events the runtime
    depends on.
  - `ui-state/reset-shell-state!` — reset the shell ratom so prior
    tests' :selected-variant / :chrome-visibility leakage does not
    bleed into this run.

  Idempotent."
  []
  (story/clear-all!)
  ;; Re-register the framework's `:rf/machine` sub after the clear-
  ;; all. Mirrors the `reset-all!` pattern in
  ;; `re-frame.story-runtime-cljs-test` — without this the lifecycle
  ;; machine's snapshot reads cannot resolve.
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (story/install-canonical-vocabulary!)
  (ui-state/reset-shell-state!)
  nil)

;; ---- harness -------------------------------------------------------------

(defn with-story-and-causa-frames
  "Set up Story canonical vocab + the `:rf/causa` observer frame, run
  `body-fn`, tear down.

  Opts (all optional):

    :install-story   — zero-arg fn to install Story state. Defaults to
                       `install-story-default!`.
    :install-causa   — zero-arg fn to install Causa. Defaults to
                       `causa-e2e/install-causa-default!`.
    :register-stories — zero-arg fn that calls `(story/reg-story ...)`
                       and `(story/reg-variant ...)` so the registrar
                       knows about the variants the test will exercise.

  `body-fn` runs inside the harness; usage:

      (with-story-and-causa-frames
        {:register-stories (fn []
                             (story/reg-variant :story.counter/loaded
                               {:events [[:counter/initialise 5]]}))}
        (fn []
          (async done
            (-> (story/run-variant :story.counter/loaded)
                (async-lib/then (fn [r] ...))))))

  Teardown clears the trace-bus buffer + resets shell state."
  [opts body-fn]
  (let [{:keys [install-story install-causa register-stories]
         :or   {install-story    install-story-default!
                install-causa    causa-e2e/install-causa-default!}}
        opts]
    (install-story)
    (install-causa)
    (when register-stories (register-stories))
    (try
      (body-fn)
      (finally
        (trace-bus/clear-buffer!)
        (ui-state/reset-shell-state!)))))

;; ---- dispatch / subscribe helpers ----------------------------------------

(defn dispatch-into-variant
  "Synchronously dispatch `event` into the variant frame `variant-id`.
  Thin wrapper around `rf/dispatch-sync` that threads the `:frame` opt
  so call sites read as

      (dispatch-into-variant [:counter/inc] :story.counter/loaded)

  After the dispatch settles, drives a synchronous Causa trace-mirror
  + epoch-history sync so panel subs reflect the latest state without
  waiting for the production `next-tick` coalesce."
  [event variant-id]
  (rf/dispatch-sync event {:frame variant-id})
  (causa-e2e/sync-causa-trace-mirror!)
  (causa-e2e/sync-causa-epoch-history!))

(defn sub-in-variant
  "Subscribe in the variant frame and dereference. Returns the current
  value (never a Reaction)."
  [query variant-id]
  (rf/with-frame variant-id
    @(rf/subscribe query)))

;; ---- shell-state helpers -------------------------------------------------

(defn select-variant!
  "Set the shell-state-atom's `:selected-variant` slot — the embed
  surface reads this to decide which variant to mount. Mirrors what
  `sidebar/clickVariant` does in the browser without going through
  the DOM."
  [variant-id]
  (ui-state/swap-state! ui-state/select-variant variant-id)
  nil)

;; ---- hiccup walking ------------------------------------------------------
;;
;; Story's chrome surfaces are mostly function components. To inspect
;; the final rendered hiccup we need to invoke `[fn args...]` nodes
;; recursively so the test sees the same tree the renderer would.
;; `expand-tree` matches the pattern Causa's pills test uses
;; (`tools/causa/test/.../filters/pills_cljs_test.cljs`) — kept inline
;; here so this helper is dependency-free at the Story-side seam (the
;; pattern is small enough not to warrant a framework-level helper
;; yet; rf2-irp6j tracks promoting it).

(declare expand-tree)

(defn expand-tree
  "Recursively expand a hiccup tree, invoking any `[fn args...]` node
  so the final tree is plain hiccup. The bug-class this catches is
  the `[component-fn arg]` shape where `component-fn` returns hiccup
  on call — without expansion the tree has unresolved function-headed
  vectors and DOM-attribute walkers miss them.

  Reagent class-3 components (created via `r/create-class`) are NOT
  expanded — they require a live React render cycle to produce hiccup.
  Detection: we invoke the fn and only treat the result as the
  expansion if it's hiccup-shaped (vector or seq); React classes
  invoked without `new` typically return nil, the component class
  itself, or throw — we treat any non-hiccup return as 'don't
  expand'."
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (let [result (try (apply (first tree) (rest tree))
                      (catch :default _ ::expand-failed))]
      (cond
        ;; Expansion produced hiccup → recurse.
        (or (vector? result) (seq? result))
        (expand-tree result)
        ;; Expansion failed or returned non-hiccup → preserve the
        ;; unexpanded vector so positional tests can inspect it.
        :else
        (mapv expand-tree tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else tree))

(defn hiccup-seq
  "Flatten an expanded hiccup tree into a depth-first seq of every
  vector / seq node. Used by `find-by-*` to locate elements without
  re-implementing tree traversal at each call site."
  [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-tree tree)))

(defn- node-attrs
  "Return the attrs map of a hiccup node, or nil if the node has no
  attrs map (i.e. its second element is not a map)."
  [node]
  (when (and (vector? node) (map? (second node)))
    (second node)))

(defn find-by-data-attr
  "Return the first hiccup node whose attrs map has `attr-key` equal to
  `value`. Used by surface tests to locate elements by `:data-test`,
  `:data-active-panel`, `:data-cluster`, etc."
  [tree attr-key value]
  (some (fn [node]
          (when-let [attrs (node-attrs node)]
            (when (= value (get attrs attr-key)) node)))
        (hiccup-seq tree)))

(defn find-all-by-data-attr
  "Return every hiccup node whose attrs map has `attr-key` equal to
  `value`. Used when the surface emits multiple elements with the
  same data attribute (e.g. one chip per panel)."
  [tree attr-key value]
  (filterv (fn [node]
             (when-let [attrs (node-attrs node)]
               (= value (get attrs attr-key))))
           (hiccup-seq tree)))

(defn find-by-test-id
  "Return the first hiccup node whose `:data-test` attr equals `tid`.
  Convenience wrapper around `find-by-data-attr` for the common case."
  [tree tid]
  (find-by-data-attr tree :data-test tid))

(defn find-all-by-test-id
  "Return every hiccup node whose `:data-test` attr equals `tid`."
  [tree tid]
  (find-all-by-data-attr tree :data-test tid))

(defn text-nodes
  "Concatenate every string node in the expanded tree. Useful for
  asserting visible text content without coupling to the surrounding
  element structure."
  [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

(defn handler-for
  "Pull a handler fn (e.g. `:on-click`, `:on-change`, `:on-keydown`)
  off a hiccup node's attrs. Returns nil when no handler is wired."
  [node handler-key]
  (some-> node node-attrs (get handler-key)))

;; ---- DOM-event mocks -----------------------------------------------------

(defn ^:no-doc fake-event
  "Minimal synthetic event object for invoking handler fns extracted
  from hiccup. Handlers commonly read `.-target`, `.-target.-value`,
  `.-key`, `.preventDefault`, `.stopPropagation`, etc. — this helper
  builds a JS-object-shaped map satisfying the read sites used across
  Story's hiccup handlers.

  `opts` keys:
    :key        — string for keydown events
    :value      — string the handler should see as `event.target.value`
    :tag-name   — uppercased tag name for editable-target discrimination
    :meta?      — true if Meta is held
    :ctrl?      — true if Ctrl is held
    :alt?       — true if Alt is held
    :content-editable? — true if the target is contenteditable"
  [opts]
  (let [{:keys [key value tag-name meta? ctrl? alt? content-editable?]
         :or   {key               nil
                value             nil
                tag-name          "DIV"
                meta?             false
                ctrl?             false
                alt?              false
                content-editable? false}}
        opts
        prevented? (atom false)
        propagated? (atom true)
        target #js {:tagName          tag-name
                    :value            value
                    :isContentEditable content-editable?}]
    #js {:key             key
         :metaKey         meta?
         :ctrlKey         ctrl?
         :altKey          alt?
         :target          target
         :preventDefault  (fn [] (reset! prevented? true) nil)
         :stopPropagation (fn [] (reset! propagated? false) nil)}))

;; ---- accessors for the shell ratom + chrome visibility -------------------

(defn chrome-visibility
  "Return the current chrome-visibility map from the shell state. Thin
  read-wrapper so tests assert against a known shape (rather than the
  test reaching into the ratom directly)."
  []
  (ui-state/chrome-visibility (ui-state/get-state)))
