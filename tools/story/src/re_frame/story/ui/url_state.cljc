(ns re-frame.story.ui.url-state
  "URL state engine — encode the shell's user-visible selection into the
  browser URL, and hydrate the shell from the URL on mount / popstate
  (rf2-o4u18).

  ## Why

  Per the workspace/variant audit (`ai/findings/2026-05-18-story-
  workspace-variant-navigation.md` §D), the testbed scored 4/10 on URL
  sharability: variant + modes + overrides + substrate were encoded,
  but workspace / mode-tab / viewport / background / tag-filter were
  NOT, and no `pushState` / `popstate` was wired so back-button and
  bookmarks didn't reflect navigation. For a devtool testbed
  sharability IS the value proposition — a teammate should be able to
  paste a URL and land on the exact same view.

  ## What

  This ns sits at the seam between `shell-state-atom` and
  `window.history`:

  - `url-from-state`     — pure: shell-state → URL string.
  - `params-from-state`  — pure: shell-state → param-vector (the same
                           shape `share/build-params` emits).
  - `install-popstate-listener!` — CLJS: subscribe to back/forward.
  - `install-state-watcher!`     — CLJS: subscribe to shell-state
                                   changes + push / replace URL.
  - `hydrate-from-url!`  — CLJS: parse `window.location` once on mount.
  - `tear-down!`         — CLJS: remove every subscription.

  All pure work lives in `re-frame.story.share` (encode + parse). This
  ns is the bind layer.

  ## pushState vs replaceState

  Primary navigation (variant / workspace selection, mode-tab switch,
  toolbar mode toggle, viewport / background preset change, tag-filter
  toggle) → `pushState` so the browser back-button restores the prior
  view.

  Cell-override edits are NOT pushed/replaced here — they're handled
  by the existing share-URL surface (the share popover) on demand.
  Threading every keystroke into the URL is too chatty for an
  interactive controls panel.

  ## Idempotence

  The state-watcher diffs the computed URL string against the current
  `window.location` before calling `pushState` — no-op when the URL
  hasn't changed (e.g. the watcher fires on a non-URL slot like
  `:hot-reload-tick`).

  popstate-driven hydration sets an internal `*hydrating?*` flag so
  the state-watcher's reaction does NOT bounce back a push for the
  state it just absorbed."
  (:require [clojure.string :as str]
            [re-frame.story.share :as share]))

;; ---- pure: shell-state → URL params -------------------------------------

(defn params-from-state
  "Project the URL-relevant slots out of a shell-state map into the
  arg-shape `share/build-params` consumes. Pure data → data;
  JVM-testable.

  Cell-overrides are projected from the per-variant overrides map for
  the currently-focused variant only — the share URL is variant-
  scoped, not the entire overrides side-table."
  [shell]
  (let [vid       (:selected-variant shell)
        wid       (:selected-workspace shell)
        tab       (when vid
                    (get-in shell [:active-mode-tab vid]))
        modes     (:active-modes shell)
        viewport  (:viewport shell)
        bg        (:background shell)
        tagf      (:tag-filter shell)
        overrides (when vid (get-in shell [:cell-overrides vid]))
        substrate (:substrate shell)]
    (cond-> {}
      vid       (assoc :variant-id    vid)
      wid       (assoc :workspace-id  wid)
      tab       (assoc :mode-tab      tab)
      (seq modes)     (assoc :active-modes   modes)
      viewport  (assoc :viewport       viewport)
      bg        (assoc :background     bg)
      (seq tagf)      (assoc :tag-filter     tagf)
      (seq overrides) (assoc :cell-overrides overrides)
      substrate (assoc :substrate      substrate))))

(defn query-string-from-state
  "Build the canonical `?<query>` string (no path / hash) for `shell`.
  Returns an empty string when no URL-relevant slots are populated.
  Pure data → data; JVM-testable."
  [shell]
  (let [params (share/build-params (params-from-state shell))]
    (if (seq params)
      (str "?" (str/join "&" params))
      "")))

(defn url-from-state
  "Compose the full URL (path + query + fragment) for `shell` against
  `location-shape`, a `{:pathname :hash}` map describing the current
  browser location. Returns a string. Pure data → data; JVM-testable.

  The encoder writes the query BEFORE the hash route (Story uses
  hash-based routing under `#/stories`); same convention
  `share/variant-share-url` honours so a hash-routed Story page keeps
  its query in `location.search`."
  [shell {:keys [pathname hash]}]
  (let [qs (query-string-from-state shell)
        path (or pathname "")
        hash (or hash "")]
    (str path qs hash)))

;; ---- diff predicate -----------------------------------------------------

(defn url-relevant-slots-changed?
  "True iff any URL-encoded slot differs between two shell-state maps.
  Pure data → data; the state-watcher uses this to skip pushState when
  the watcher fires on a non-URL slot (e.g. `:hot-reload-tick`).

  Compared slots: `:selected-variant`, `:selected-workspace`,
  `:active-mode-tab`, `:active-modes`, `:viewport`, `:background`,
  `:tag-filter`, `:substrate`. Cell-overrides are NOT included — they
  ride the share popover, not the live URL."
  [old new]
  (or (not= (:selected-variant   old) (:selected-variant   new))
      (not= (:selected-workspace old) (:selected-workspace new))
      (not= (:active-mode-tab    old) (:active-mode-tab    new))
      (not= (:active-modes       old) (:active-modes       new))
      (not= (:viewport           old) (:viewport           new))
      (not= (:background         old) (:background         new))
      (not= (:tag-filter         old) (:tag-filter         new))
      (not= (:substrate          old) (:substrate          new))))

;; ---- hydrator: URL params → shell-state apply ---------------------------

(defn apply-parsed-to-state
  "Pure: fold `parsed` (the output of `share/parse-params`) into a
  shell-state map. Returns the new state map.

  Validation: each slot is validated against the relevant registrar /
  preset table via the supplied `validators` map so this fn stays
  pure. Unknown / invalid ids are silently dropped (a stale URL
  degrades to the empty / default state rather than poisoning the
  shell). Missing validators mean 'accept any keyword'.

  `validators` keys:
    :variant?    fn   — registered? predicate for variant ids
    :workspace?  fn   — registered? predicate for workspace ids
    :viewport?   fn   — recognised? for preset kw / custom map
    :background? fn   — recognised? for preset kw / colour string

  Per rf2-hscut: workspace + variant are mutually exclusive in the
  sidebar (selecting one clears the other). If the URL carries both
  the variant wins (variant is the canonical sharable unit; workspace
  groupings are derived)."
  [state parsed validators]
  (let [{:keys [variant-id workspace-id mode-tab active-modes
                viewport background tag-filter substrate]} parsed
        variant-ok? (or (nil? (:variant? validators))
                        ((:variant? validators) variant-id))
        ws-ok?      (or (nil? (:workspace? validators))
                        ((:workspace? validators) workspace-id))
        vp-ok?      (or (nil? (:viewport? validators))
                        ((:viewport? validators) viewport))
        bg-ok?      (or (nil? (:background? validators))
                        ((:background? validators) background))
        ;; Variant wins over workspace when both are present (rf2-hscut).
        keep-variant?   (and variant-id variant-ok?)
        keep-workspace? (and (not keep-variant?) workspace-id ws-ok?)]
    (cond-> state
      keep-variant?
      (-> (assoc :selected-variant variant-id)
          (assoc :selected-workspace nil))

      keep-workspace?
      (-> (assoc :selected-workspace workspace-id)
          (assoc :selected-variant nil))

      (and (not keep-variant?) (not keep-workspace?))
      (-> (assoc :selected-variant   nil)
          (assoc :selected-workspace nil))

      (and keep-variant? mode-tab)
      (assoc-in [:active-mode-tab variant-id] mode-tab)

      (seq active-modes)
      (assoc :active-modes (vec active-modes))

      (and viewport vp-ok?)
      (assoc :viewport viewport)

      (and background bg-ok?)
      (assoc :background background)

      (seq tag-filter)
      (assoc :tag-filter (set tag-filter))

      substrate
      (assoc :substrate substrate))))

;; ---- CLJS-only: window.history + popstate -------------------------------

#?(:cljs
   (do

     (defn- ^:no-doc safe-window []
       (when (exists? js/window) js/window))

     (defn- ^:no-doc current-location-shape
       "Snapshot `window.location` into the pure `{:pathname :hash}`
       shape `url-from-state` consumes."
       []
       (when-let [w (safe-window)]
         (let [loc (.-location w)]
           {:pathname (.-pathname loc)
            :hash     (.-hash loc)})))

     (defn- ^:no-doc current-url-search+hash
       "Snapshot the `(search + hash)` portion of the current URL so the
       state-watcher can compare against its computed candidate
       without forcing an absolute-URL diff that includes the origin."
       []
       (when-let [w (safe-window)]
         (let [loc (.-location w)]
           (str (.-pathname loc) (.-search loc) (.-hash loc)))))

     (defn- ^:no-doc params->getter
       "Build the `{key → string}` getter map `share/parse-params`
       consumes from a `URLSearchParams` instance."
       [usp]
       (when usp
         {"variant"    (.get usp "variant")
          "workspace"  (.get usp "workspace")
          "mode-tab"   (.get usp "mode-tab")
          "modes"      (.get usp "modes")
          "viewport"   (.get usp "viewport")
          "background" (.get usp "background")
          "tag-filter" (.get usp "tag-filter")
          "overrides"  (.get usp "overrides")
          "substrate"  (.get usp "substrate")}))

     (defn parse-current-url
       "Parse the current `window.location.search` into the
       `share/parse-params` shape. Returns nil when no search params
       are present or window is unavailable."
       []
       (when-let [w (safe-window)]
         (let [search (.-search (.-location w))]
           (when (and (string? search) (seq search))
             (try
               (let [usp (js/URLSearchParams. search)]
                 (share/parse-params (params->getter usp)))
               (catch :default _ nil))))))

     (defn embed-flag-from-current-url
       "Read the `?embed=1` flag (rf2-pucku) from
       `window.location.search`. Returns true when the param is present
       and recognised as truthy (`1`/`true`/`yes`/`on`,
       case-insensitive); false otherwise.

       The flag is intentionally NOT round-tripped through
       `share/parse-params` because it's chrome-state, not shell-state
       — it never hydrates the registrar-indexed slots and never
       writes back to the URL."
       []
       (when-let [w (safe-window)]
         (let [search (.-search (.-location w))]
           (boolean
             (when (and (string? search) (seq search))
               (try
                 (let [usp (js/URLSearchParams. search)
                       raw (.get usp "embed")]
                   (when (string? raw)
                     (let [v (str/lower-case (str/trim raw))]
                       (contains? #{"1" "true" "yes" "on"} v))))
                 (catch :default _ false)))))))

     (defonce ^:private hydrating?-atom (atom false))

     (defn hydrating?
       "True while the popstate listener is mid-flight applying URL
       state back to the shell — the state-watcher checks this flag
       and skips its pushState reaction so we don't bounce a redundant
       URL write."
       []
       @hydrating?-atom)

     (defn- ^:no-doc with-hydration-guard
       "Run `f` with the hydration flag pinned true; clears it before
       returning so the state-watcher resumes pushing on the next
       user-driven change."
       [f]
       (reset! hydrating?-atom true)
       (try
         (f)
         (finally
           (reset! hydrating?-atom false))))

     (defn push!
       "`history.pushState(null, '', url)` if URL differs from the
       current location's `(pathname + search + hash)`. No-op
       otherwise. Idempotent."
       [url]
       (when-let [w (safe-window)]
         (let [cur (current-url-search+hash)]
           (when (and url (not= url cur))
             (try
               (.pushState (.-history w) nil "" url)
               (catch :default _ nil))))))

     (defn replace!
       "`history.replaceState(null, '', url)` if URL differs. No-op
       otherwise. Idempotent."
       [url]
       (when-let [w (safe-window)]
         (let [cur (current-url-search+hash)]
           (when (and url (not= url cur))
             (try
               (.replaceState (.-history w) nil "" url)
               (catch :default _ nil))))))

     ;; ---- watch wiring -----------------------------------------------------

     (defonce ^:private popstate-handler-atom (atom nil))

     (defn install-popstate-listener!
       "Subscribe to `window.popstate`. Each pop parses
       `window.location.search` via `share/parse-params` and folds the
       result into `shell-state-atom` via `apply-parsed-to-state`.

       `apply-fn` is the post-validation applicator — production wires
       it to `apply-parsed-to-state` curried with live validators (so
       the popstate handler doesn't need to import the registrar /
       viewport / backgrounds nss directly). Tests can pass a no-op /
       capturing apply-fn.

       Idempotent: re-installing replaces the previous handler. Returns
       the wired handler so tests can introspect."
       [shell-state-atom apply-fn]
       (when-let [w (safe-window)]
         (when-let [prev @popstate-handler-atom]
           (try (.removeEventListener w "popstate" prev)
                (catch :default _ nil)))
         (let [h (fn [_]
                   (when-let [parsed (parse-current-url)]
                     (with-hydration-guard
                       (fn []
                         (swap! shell-state-atom apply-fn parsed)))))]
           (.addEventListener w "popstate" h)
           (reset! popstate-handler-atom h)
           h)))

     (defn remove-popstate-listener! []
       (when-let [w (safe-window)]
         (when-let [h @popstate-handler-atom]
           (try (.removeEventListener w "popstate" h)
                (catch :default _ nil))
           (reset! popstate-handler-atom nil))))

     (def ^:private state-watcher-key ::url-state-watcher)

     (defn install-state-watcher!
       "Watch `shell-state-atom`; on every URL-relevant change push the
       canonical URL via `history.pushState`. No-op when the watcher
       fires on a non-URL slot or while a popstate hydration is in
       flight. Idempotent: re-installing replaces the previous watch
       (same watch-key)."
       [shell-state-atom]
       (add-watch shell-state-atom state-watcher-key
                  (fn [_ _ old new]
                    (when (and (not (hydrating?))
                               (url-relevant-slots-changed? old new))
                      (let [url (url-from-state new (current-location-shape))]
                        (push! url))))))

     (defn remove-state-watcher! [shell-state-atom]
       (remove-watch shell-state-atom state-watcher-key))

     (defn hydrate-from-url!
       "Parse the current `window.location.search` once at shell mount
       and fold the parsed slots into `shell-state-atom`. Sets a
       transient `*hydrating?*` flag so the state-watcher's first
       fire (the swap below) doesn't bounce a redundant pushState
       back to the same URL.

       Returns the parsed map (or nil when no params)."
       [shell-state-atom apply-fn]
       (when-let [parsed (parse-current-url)]
         (with-hydration-guard
           (fn []
             (swap! shell-state-atom apply-fn parsed)))
         parsed))

     (defn hydrate-embed-flag!
       "Seed `[:chrome-visibility :embed?]` from the `?embed=1` URL
       flag (rf2-pucku). One-shot at shell mount; embed-mode is URL-
       driven and not persisted, so this runs every mount without
       storage involvement.

       Wrapped in the hydration guard so the state-watcher does not
       react with a pushState back."
       [shell-state-atom]
       (let [embed? (embed-flag-from-current-url)]
         (with-hydration-guard
           (fn []
             (swap! shell-state-atom
                    update :chrome-visibility
                    (fn [m] (assoc (or m {}) :embed? embed?)))))
         embed?))

     (defn tear-down! [shell-state-atom]
       (remove-state-watcher! shell-state-atom)
       (remove-popstate-listener!))))
