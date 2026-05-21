(ns day8.re-frame2-causa.panel-registry
  "Internal L4-tab registry — `reg-l4-tab!` (rf2-2moh1).

  ## The seam

  Before this ns, `shell.cljs` carried a hard-coded vector of the 7
  Dynamic tabs + a parallel case-switch in `detail-panel`; `static/
  shell.cljs` carried the same shape for the 6 Static tabs. Adding a
  tab (e.g. promoting Routing to its own L3 lens per rf2-nrbs9)
  required editing two files in lock-step — modify-shell coupling.

  Per the audit finding `ai/findings/2026-05-20-tools-causa-api-review.md`
  Finding #3: each panel's existing `(defn install! [] ...)` already
  owns its subs / events / fxs. Threading the tab metadata through the
  same install! call closes the loop — adding a tab now means:

    (defn install! []
      ...subs / events / fxs...
      (registry/reg-l4-tab!
        {:id    :foo
         :label \"Foo\"
         :mnem  \"f\"
         :modes #{:dynamic}
         :order 6
         :panel foo/Panel}))

  …and the L3 tab bar + L4 detail panel pick it up automatically.

  ## Public-API stance

  v1 'no plugin registration API' (`spec/API.md` §The plugin
  question) stays true. This is an INTERNAL seam — the registry atom
  + the `reg-l4-tab!` fn are not re-exported through `re-frame.core`
  nor any tool's public ns. Third parties cannot register tabs from
  outside. The seam exists purely so per-panel registrations stay
  colocated with the panel's other registrations.

  ## Shape

  Each tab entry carries:

    :id     keyword — keyword that lands on `:rf.causa/selected-tab`
            (Dynamic) or `:rf.causa.static/selected-tab` (Static)
            when the tab is selected.
    :label  string — visible tab label.
    :mnem   string — single-letter keyboard mnemonic.
    :modes  set    — subset of #{:dynamic :static}. Tabs registered
                     against multiple modes appear in every matching
                     tab bar.
    :order  number — sort key for the tab bar render order. Lower
                     comes first. The canonical 0..N integers reserve
                     stable positions per spec/018 §5 (Dynamic) +
                     `tools/causa/spec/007-UX-IA.md` §Static mode
                     (Static).
    :panel  fn     — view function rendered when the tab is selected.
                     Called with no args from a hiccup `[(:panel tab)]`
                     vector so reg-view shapes resolve through their
                     own React-context Provider.
    :placeholder-bead opt string — sibling bead id when the tab still
                                   renders a placeholder card (Static
                                   tabs do this during the rf2-o5f5f
                                   roll-out).

  ## Idempotency

  Re-registering a tab REPLACES the prior entry in place — same
  posture as re-frame's registrar so shadow-cljs `:after-load`
  cycles don't stack duplicates. The replace is silent (no warning)
  because the per-panel install! sentinel above already guards the
  hot-reload path; the registry's idempotency is just structural
  insurance.

  ## JVM-portable

  `.cljc` so the JVM test corpus can exercise the pure-data
  registry surface (registration shape, ordering, mode partition)
  without spinning a CLJS runtime. Panel `:panel` views are CLJS-
  only — JVM tests register stub `:panel` values (any value works;
  the registry doesn't invoke `:panel`).")

;; ---- registry atom ------------------------------------------------------

(defonce ^{:doc "The L4 tab registry. Map of `[mode tab-id] → tab-entry`
                 — composite key because the same `:id` may legitimately
                 register against multiple modes (e.g. `:routes` exists
                 as both the Dynamic Routing tab — `panels/routing.cljs`
                 (focused-event lens) — and the Static Routes catalogue
                 tab — `static/routes/panel.cljs` (browse-all); same id,
                 different content). Atom (not a defonce on a literal
                 map) so per-panel `install!` mutations are visible to
                 readers without re-loading this ns. Re-loading this ns
                 under shadow-cljs
                 `:after-load` preserves the atom's contents — `defonce`
                 semantics.

                 Tabs registered against multiple modes (a single tab
                 entry with `:modes #{:dynamic :static}`) materialise
                 as ONE entry per `[mode id]` key — the registration
                 mutation expands the `:modes` set across keys so
                 lookup-by-mode is a constant-time `get`. Today every
                 panel registers a single-mode entry so the expansion
                 is the identity; the multi-mode pathway is structural
                 insurance for the future routing/schemas-style verbs
                 that might span modes."}
  registry
  (atom {}))

;; ---- pure helpers (data-shape; JVM-portable) ----------------------------

(defn tab-entries
  "All registered tab entries as a seq. Order is undefined — use
  `tabs-for-mode` for ordered render."
  []
  (vals @registry))

(defn tabs-for-mode
  "Ordered seq of tab entries registered against `mode`. Sorted by
  `:order` (ascending; nil orders trail at +Inf so an unspecified
  order doesn't crash the sort)."
  [mode]
  (->> @registry
       (keep (fn [[[entry-mode _id] entry]]
               (when (= entry-mode mode) entry)))
       (sort-by #(or (:order %) ##Inf))
       vec))

(defn tab-by-id
  "Lookup a tab entry by `mode` + `id`. Returns nil when no matching
  tab is registered (the L4 detail panel renders an unknown-tab
  stub in that case so a stale `:selected-tab` doesn't crash the
  render)."
  [mode id]
  (get @registry [mode id]))

(defn tab-ids-for-mode
  "Set of tab ids registered for `mode`. Drives the
  `:rf.causa.static/select-tab` event's contains? guard so unknown
  ids land as no-ops."
  [mode]
  (into #{} (map :id) (tabs-for-mode mode)))

(defn default-tab-for-mode
  "First tab in `tabs-for-mode mode` order — the default landing tab
  when `:selected-tab` is unset. Returns nil when no tabs are
  registered for the mode (test-only state — production always has
  the canonical inventory installed via `register-causa-handlers!`)."
  [mode]
  (:id (first (tabs-for-mode mode))))

;; ---- mutation -----------------------------------------------------------

(defn reg-l4-tab!
  "Register an L4 tab entry. Idempotent — re-registering the same
  `[mode id]` replaces the prior entry (same posture as re-frame's
  registrar). Returns the registered entry.

  Each panel's `(defn install! [] ...)` calls this alongside its
  `reg-sub` / `reg-event-*` / `reg-fx` registrations so the tab
  inventory is declarative-per-panel rather than hard-coded in
  the shell.

  Tabs registered against multiple modes (`:modes #{:dynamic :static}`)
  materialise as one entry per `[mode id]` key — every mode in the
  set gets its own entry pointing at the same metadata. Today every
  panel registers a single-mode entry; the multi-mode pathway is
  insurance for cross-mode verbs that might land later."
  [{:keys [id label mnem modes panel order placeholder-bead] :as tab}]
  {:pre [(keyword? id)
         (string? label)
         (or (nil? mnem) (string? mnem))
         (set? modes)
         (seq modes)
         (every? #{:dynamic :static} modes)
         (or (nil? order) (number? order))
         (or (nil? placeholder-bead) (string? placeholder-bead))]}
  (swap! registry
         (fn [reg]
           (reduce (fn [acc mode] (assoc acc [mode id] tab))
                   reg
                   modes)))
  tab)

(defn unreg-l4-tab!
  "Test-only — drop tab entries for `id` (every mode). Production
  never calls this; the per-panel install! pattern always replaces
  in place."
  [id]
  (swap! registry
         (fn [reg]
           (into {} (remove (fn [[[_mode entry-id] _]] (= entry-id id))) reg)))
  nil)

(defn reset-for-test!
  "Test-only — clear the registry so fixtures can drive a clean-slate
  registration cycle. Paired with re-running the per-panel install!
  fns (or `register-causa-handlers!` end-to-end) to repopulate."
  []
  (reset! registry {})
  nil)
