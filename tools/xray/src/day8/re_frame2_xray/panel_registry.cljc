(ns day8.re-frame2-xray.panel-registry
  "Internal L4-tab registry — `reg-l4-tab!`.

  ## The seam

  The Dynamic and Static tab inventories are declared per-panel rather
  than as hard-coded vectors in `shell.cljs` / `static/shell.cljs`,
  avoiding the modify-shell coupling that a centralised tab list and a
  parallel `detail-panel` case-switch would impose.

  Each panel's `(defn install! [] ...)` already owns its subs / events
  / fxs. Threading the tab metadata through the same install! call
  closes the loop — adding a tab means:

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

    :id     keyword — keyword that lands on `:rf.xray/selected-tab`
            (Dynamic) or `:rf.xray.static/selected-tab` (Static)
            when the tab is selected.
    :label  string — visible tab label.
    :mnem   string — single-letter keyboard mnemonic.
    :modes  set    — subset of #{:dynamic :static}. Tabs registered
                     against multiple modes appear in every matching
                     tab bar.
    :order  number — sort key for the tab bar render order. Lower
                     comes first. The canonical 0..N integers reserve
                     stable positions per spec/018 §5 (Dynamic) +
                     `tools/xray/spec/007-UX-IA.md` §Static mode
                     (Static).
    :panel  fn     — view function rendered when the tab is selected.
                     Called with no args from a hiccup `[(:panel tab)]`
                     vector so reg-view shapes resolve through their
                     own React-context Provider. REQUIRED + must be
                     callable — `reg-l4-tab!` rejects a missing or
                     non-callable `:panel` so a malformed registration
                     fails at the registry seam, not later during UI
                     composition (`[(:panel tab)]`).

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
  only — the registry stores but never invokes `:panel`, so JVM
  tests register an `(fn [] nil)` stub: the `:pre` requires `:panel`
  to be callable but is content with any callable.")

;; ---- registry atom ------------------------------------------------------

(defonce ^{:doc "The L4 tab registry. Map of `[mode tab-id] → tab-entry`
                 — the key is composite because the same `:id` legitimately
                 registers against both modes via SEPARATE single-mode
                 entries: `:routes` is the Dynamic Routing tab
                 (`panels/routing.cljs`, focused-event lens) AND the Static
                 Routes catalogue tab (`static/routes/panel.cljs`,
                 browse-all) — same id, different panel, keyed apart by
                 mode. Atom (not a defonce on a literal map) so per-panel
                 `install!` mutations are visible to readers without
                 re-loading this ns; `defonce` preserves contents across
                 shadow-cljs `:after-load`."}
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
  `:rf.xray.static/select-tab` event's contains? guard so unknown
  ids land as no-ops."
  [mode]
  (into #{} (map :id) (tabs-for-mode mode)))

(defn default-tab-for-mode
  "First tab in `tabs-for-mode mode` order — the default landing tab
  when `:selected-tab` is unset. Returns nil when no tabs are
  registered for the mode (test-only state — production always has
  the canonical inventory installed via `register-xray-handlers!`)."
  [mode]
  (:id (first (tabs-for-mode mode))))

;; ---- mutation -----------------------------------------------------------

(defn reg-l4-tab!
  "Register an L4 tab entry under `[mode id]`. Idempotent —
  re-registering the same `[mode id]` replaces the prior entry (same
  posture as re-frame's registrar). Returns the registered entry.

  Each panel's `(defn install! [] ...)` calls this alongside its
  `reg-sub` / `reg-event` / `reg-fx` registrations so the tab
  inventory is declarative-per-panel rather than hard-coded in the
  shell.

  `:modes` is a single-element set (`#{:dynamic}` or `#{:static}`) —
  a panel belongs to exactly one mode's tab bar. The same `:id` in
  both modes is two separate registrations of two separate panels
  (e.g. Dynamic Routing vs Static Routes), keyed apart by mode."
  [{:keys [id label mnem modes panel order] :as tab}]
  {:pre [(keyword? id)
         (string? label)
         (or (nil? mnem) (string? mnem))
         (set? modes)
         (= 1 (count modes))
         (every? #{:dynamic :static} modes)
         (or (nil? order) (number? order))
         (fn? panel)]}
  (swap! registry assoc [(first modes) id] tab)
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
  fns (or `register-xray-handlers!` end-to-end) to repopulate."
  []
  (reset! registry {})
  nil)
