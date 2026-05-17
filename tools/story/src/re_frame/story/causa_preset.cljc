(ns re-frame.story.causa-preset
  "Per-story Causa preset (rf2-q9kv5).

  A story (or variant) body may carry a `:causa` slot that
  pre-configures the Causa shell when it mounts inside the rendered
  variant's frame. Authors declare the preset declaratively; this
  namespace is the runtime that reads it and drives Causa.

  ## Schema (see `re-frame.story.schemas/CausaPreset`)

      {:causa {:open?    true                 ; auto-open the shell
               :tab      :issues              ; pre-select tab
               :filters  {:out [:my/noise]    ; filter pre-population
                          :in  []}
               :focus    {:event-pos 5}}}     ; pre-focus a cascade pos

  Every slot is optional. A missing `:causa` slot is the v0 behaviour
  (no auto-mount, no tab focus). The runtime is feature-detect for
  Causa itself AND for the optional filters API (rf2-ak4ms in flight)
  — when Causa is not on the classpath the preset no-ops silently;
  when filters is not present, only the filters step is skipped (with
  a console.warn breadcrumb so authors notice).

  ## Where it runs

  `apply-preset!` is invoked from `re-frame.story.ui.shell` after the
  variant's frame is allocated (per the variant-selection edge in the
  shell's `selection-watcher`). The variant-id arg lets the preset
  resolve the body via the registrar and dispatch into the right
  frame.

  ## Pure / impure split

  This namespace is `.cljc` so the pure helpers (`resolve-preset`,
  `merge-preset`) run on both JVM and CLJS. The Causa-driving side
  effects are CLJS-only.

  ## Elision

  Behind `re-frame.story.config/enabled?` per the same posture as the
  rest of `tools/story`. Production builds short-circuit before the
  preset is reached."
  (:require [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]
            #?(:cljs [re-frame.core           :as rf])
            #?(:cljs [re-frame.story.config   :as config])))

;; ---- pure: preset resolution ---------------------------------------------

(defn merge-preset
  "Merge a parent story's `:causa` preset with a variant's `:causa`
  preset. Variant slots override story slots; the `:filters` slot is
  deep-merged so an `:in` declared at the story level survives an
  `:out` declared at the variant level. Pure data → data."
  [story-preset variant-preset]
  (let [base   (or story-preset {})
        over   (or variant-preset {})
        merged (merge base over)]
    (cond-> merged
      (or (:filters base) (:filters over))
      (assoc :filters (merge (or (:filters base) {})
                             (or (:filters over) {}))))))

(defn resolve-preset
  "Read the resolved Causa preset for `variant-id` by merging the parent
  story's `:causa` slot with the variant's `:causa` slot. Returns nil
  when neither carries a preset. Pure-ish (reads the registrar)."
  [variant-id]
  (let [variant-body (registrar/handler-meta :variant variant-id)
        story-id     (pred/parent-story-id variant-id)
        story-body   (when story-id
                       (registrar/handler-meta :story story-id))
        story-preset (:causa story-body)
        var-preset   (:causa variant-body)]
    (when (or story-preset var-preset)
      (merge-preset story-preset var-preset))))

;; ---- feature detection (CLJS-only) ---------------------------------------

#?(:cljs
   (defn- resolve-fn
     "Resolve a fully-qualified `'ns/name` symbol to a fn via
     `cljs.core/find-ns-obj` + property lookup. Returns nil when the
     namespace or symbol is not loaded. Used to feature-detect Causa
     and the optional filters API without forcing a hard require.

     CLJS `find-ns-obj` returns the JS object backing the namespace
     and we read the property by munged name. Returns the live fn or
     nil."
     [sym]
     (try
       (let [ns-str   (namespace sym)
             name-str (name sym)
             ns-obj   (when ns-str (find-ns-obj (symbol ns-str)))]
         (when ns-obj
           (let [munged (-> name-str
                            (.replace #"-" "_")
                            (.replace #"\?" "_QMARK_")
                            (.replace #"\!" "_BANG_"))
                 v      (aget ns-obj munged)]
             (when (fn? v) v))))
       (catch :default _ nil))))

#?(:cljs
   (defn causa-available?
     "True iff the Causa mount surface is loaded — feature-detect
     `day8.re-frame2-causa.mount/open!`. When false the preset
     no-ops silently."
     []
     (some? (resolve-fn 'day8.re-frame2-causa.mount/open!))))

#?(:cljs
   (defn filters-available?
     "True iff the Causa filters API (rf2-ak4ms, in flight) exposes a
     `configure!` fn. Feature-detect — when false the `:filters` step
     of the preset is skipped with a `console.warn`."
     []
     (some? (resolve-fn 'day8.re-frame2-causa.filters.config/configure!))))

;; ---- preset application (CLJS-only) --------------------------------------

#?(:cljs
   (defn- safe-call!
     "Call `f` with `args` swallowing throwing only with a console
     breadcrumb. Used so a misbehaving Causa internal can't take down
     the variant render."
     [where f & args]
     (try
       (apply f args)
       (catch :default e
         (when (and (exists? js/console) (.-warn js/console))
           (.warn js/console
                  (str "[re-frame.story.causa-preset] " where " threw: "
                       (.-message e))))
         nil))))

#?(:cljs
   (defn- apply-open!
     "Drive the Causa shell open via `day8.re-frame2-causa.mount/open!`."
     []
     (when-let [open-fn (resolve-fn 'day8.re-frame2-causa.mount/open!)]
       (safe-call! "open!" open-fn))))

#?(:cljs
   (defn- apply-tab!
     "Select the Causa panel tab via `:rf.causa/select-panel`. Causa's
     registry registers this event-db handler against the `:rf/causa`
     frame."
     [tab]
     (when tab
       (safe-call! ":rf.causa/select-panel"
                   rf/dispatch* [:rf.causa/select-panel tab] {:frame :rf/causa}))))

#?(:cljs
   (defn- apply-filters!
     "Configure Causa filters via the optional filters API. Skipped with
     a warn breadcrumb when filters is not yet on the classpath
     (rf2-ak4ms in flight)."
     [filters]
     (cond
       (not (map? filters))
       nil

       (filters-available?)
       (when-let [configure! (resolve-fn 'day8.re-frame2-causa.filters.config/configure!)]
         (safe-call! "filters/configure!" configure! filters))

       :else
       (when (and (exists? js/console) (.-warn js/console))
         (.warn js/console
                "[re-frame.story.causa-preset] :filters preset skipped — "
                "day8.re-frame2-causa.filters.config/configure! not loaded "
                "(rf2-ak4ms in flight)")))))

#?(:cljs
   (defn- apply-focus!
     "Dispatch a focus pre-selection if `:event-pos` is set. We use
     `:rf.causa/focus-cascade` with the position when Causa exposes it
     via the spine — feature-detect for safety."
     [focus]
     (when (and (map? focus) (:event-pos focus))
       (safe-call! ":rf.causa/focus-cascade"
                   rf/dispatch*
                   [:rf.causa/focus-cascade {:event-pos (:event-pos focus)}]
                   {:frame :rf/causa}))))

#?(:cljs
   (defn apply-preset!
     "Apply the resolved Causa preset for `variant-id`. No-op when no
     preset is set OR when Causa is not on the classpath.

     Steps (only those whose slot is present run):

       1. `:open?` true → `mount/open!`.
       2. `:tab`   set → dispatch `:rf.causa/select-panel` into `:rf/causa`.
       3. `:filters` set → `filters.config/configure!` (or warn-skip).
       4. `:focus`   set → dispatch `:rf.causa/focus-cascade` with coords.

     Returns the resolved preset (or nil) so the shell can log /
     debug-introspect what fired."
     [variant-id]
     (when (and config/enabled? (causa-available?))
       (when-let [preset (resolve-preset variant-id)]
         (when (:open? preset)
           (apply-open!))
         (when (:tab preset)
           (apply-tab! (:tab preset)))
         (when (:filters preset)
           (apply-filters! (:filters preset)))
         (when (:focus preset)
           (apply-focus! (:focus preset)))
         preset))))

;; ---- selection-watcher hook (CLJS-only) ----------------------------------

#?(:cljs
   (defn on-variant-selected!
     "Hook the shell's selection-watcher calls when a variant becomes
     selected. Feature-detect-safe; cheap when no preset is registered.

     Re-applies the preset on every selection edge so a story author
     who edits `:causa` and hot-reloads sees the change without
     remount."
     [variant-id]
     (apply-preset! variant-id)))
