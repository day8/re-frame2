(ns day8.re-frame2-causa.frame-switcher
  "Causa's hardened L1 frame-switcher slot (rf2-iwwou) — the single
  contractually-anchored surface every frame-aware feature reaches
  through.

  ## Why a dedicated ns

  Causa is increasingly frame-aware: App-DB Diff, Views, Routing, the
  machine-inspector scrubber, and the Cmd-K palette's `:select-frame`
  verb all need to read 'which frame is the user currently focused on?'
  and need to write that selection back when the user picks a
  different frame. Pre-bead the picker lived inline in `shell.cljs`'s
  ribbon and the spine's `:rf.causa/set-frame` event was the de-facto
  write surface; the palette dispatched the same event. That worked,
  but the contract was implicit — nothing in source said 'this is THE
  way to read / write frame focus' and new frame-aware features had no
  obvious primitive to require.

  This ns formalises the contract.

  ## The contract

  Three public symbols make up the slot's external surface — every
  frame-aware feature SHOULD reach through these only:

  - **Sub `:rf.causa/current-frame`** — returns the frame id the user
    has focused (or the default when no explicit selection has been
    made). Composes off the spine's `:rf.causa/focus-slot` so the
    sub re-fires automatically when the spine flips frames via any
    code path (ribbon picker, palette, headless test driver).

  - **Sub `:rf.causa/available-frames`** — returns a first-seen-order
    vec of distinct frames present in the live cascade list, filtered
    by the `show-tool-frames?` setting (spec/018 §8 I1). The single
    source of truth for 'which frames is it meaningful to pick right
    now'. Composes off `:rf.causa/cascades` so the list re-fires as
    new frames appear in the trace stream.

  - **Event-fx `:rf.causa/select-frame <frame-id>`** — canonical write
    surface. Dispatches the spine's `:rf.causa/set-frame` (which
    re-seeds `:target-frame` + `:epoch-history`, see
    `spine/set-frame-reducer` docstring for the full propagation
    story) AND persists the selection to localStorage so the next
    Causa session restores the same focus. The Cmd-K palette's
    `:palette/select-frame` verb dispatches THIS event, not the spine
    primitive directly — that way every persistence / instrumentation
    layer we add lives in one place.

  The L1 ribbon picker reads `:rf.causa/current-frame` +
  `:rf.causa/available-frames`, dispatches `:rf.causa/select-frame`.
  No ad-hoc frame access from the ribbon view code — the picker is
  one thin call site against the canonical contract.

  ## Pure helpers

  `distinct-frames` + `internal-frames` are pure data ops the sub
  composer + tests both reach through. Exported so tests can hit them
  without bouncing through a subscribe.

  ## Persistence shape

  localStorage key `re-frame2.causa.frame-switcher.v1` (per-instance
  overridable via the direct setter `frame-switcher/set-storage-key!`;
  a future `configure! :rf.causa/frame-switcher-storage-key` plumb
  is straightforward but not wired today). Value is a one-key EDN map
  `{:frame :rf/cart-frame}` — wrapping
  a bare keyword would force callers to handle 'literal nil means no
  selection' vs 'no entry means no selection' separately; the map
  envelope keeps room for future fields (e.g. last-N-frames history,
  per-frame UI state) without a versioned migration.

  ## Hydration

  `hydrate!` runs at install time (preload) AND from
  `mount/ensure-causa-frame!` on first open. Re-entrant — the dispatch
  is a wholesale `(assoc db :focus (assoc focus :frame …))`. Guards
  on `(frame/frame :rf/causa)` so the pre-mount call short-circuits
  cleanly. Mirrors the `filters/hydrate!` shape so the two surfaces
  share an ergonomic pattern."
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale sans-stack]]))

;; ---- public contract: which frames Causa filters out by default ---------

(def internal-frames
  "Frames Causa filters out of the picker by default per spec/018 §8 I1.
  `:rf/causa` is Causa's own state; `:rf/re-frame2-pair` is the future
  MCP-pair frame. A future Settings 'Show tool frames in picker' toggle
  will re-include them under a `── Power user ──` divider; the toggle
  UI is not built yet, so the picker is hardcoded to exclude them.

  Public so a future Settings ns can read the canonical set when it
  surfaces the toggle, and so tests can assert the membership without
  duplicating the literal."
  #{:rf/causa :rf/re-frame2-pair})

;; ---- pure helpers --------------------------------------------------------

(defn distinct-frames
  "Pure helper — returns the distinct frames present in `cascades` in
  first-seen order. Drives the `:rf.causa/available-frames` sub.

  Filters `:rf/causa` (and other tool frames per `internal-frames`)
  out by default per spec/018 §8 I1 — passing `show-tool-frames?` true
  reincludes them. nil-frame cascades are dropped (an `:ungrouped`
  cascade carries nil `:frame`)."
  [cascades show-tool-frames?]
  (let [seen (volatile! #{})]
    (reduce
      (fn [acc cascade]
        (let [f (:frame cascade)]
          (cond
            (nil? f)                              acc
            (contains? @seen f)                   acc
            (and (not show-tool-frames?)
                 (contains? internal-frames f))   acc
            :else (do (vswap! seen conj f)
                      (conj acc f)))))
      []
      cascades)))

;; ---- persistence ---------------------------------------------------------

(def default-storage-key
  "Default localStorage key Causa writes the frame-switcher selection
  under. Hosts override via the direct setter
  `frame-switcher/set-storage-key!` for per-instance isolation
  (Story testbeds
  use this so per-scenario picks don't leak between scenarios)."
  "re-frame2.causa.frame-switcher.v1")

(defonce ^:private storage-key
  (atom default-storage-key))

(defn set-storage-key!
  "Replace the localStorage key Causa uses for frame-switcher
  persistence. `nil` resets to the default."
  [k]
  (reset! storage-key (or k default-storage-key))
  nil)

(defn get-storage-key
  "Return the current localStorage key for frame-switcher persistence."
  []
  @storage-key)

(defn- storage-available?
  "True when `js/window.localStorage` is reachable. JVM / node tests
  without jsdom land in the no-op branch."
  []
  (and (exists? js/window)
       (some? (.-localStorage js/window))))

(defn- read-raw
  "Read the raw EDN string Causa wrote under `storage-key`. Returns nil
  when the slot is empty or localStorage is unavailable."
  []
  (when (storage-available?)
    (try
      (.getItem (.-localStorage js/window) (get-storage-key))
      (catch :default _ nil))))

(defn- write-raw!
  "Write `s` (an EDN string) under `storage-key`. No-op when
  localStorage is unavailable. Swallows any throw — a quota error
  must not poison the dispatch chain."
  [s]
  (when (storage-available?)
    (try
      (.setItem (.-localStorage js/window) (get-storage-key) s)
      (catch :default _ nil)))
  nil)

(defn clear!
  "Remove the persisted selection. No-op when localStorage is
  unavailable. Hosts use this when tearing down per-scenario state in
  Story testbeds."
  []
  (when (storage-available?)
    (try
      (.removeItem (.-localStorage js/window) (get-storage-key))
      (catch :default _ nil)))
  nil)

(defn ->edn
  "Serialise `frame-id` (a keyword or nil) into a stable EDN string.
  Wraps in a `{:frame …}` envelope so the round-trip distinguishes
  'no entry' (no key) from 'explicit nil' (key present, value nil) —
  the latter is reserved as a 'clear selection' marker."
  [frame-id]
  (pr-str {:frame frame-id}))

(defn <-edn
  "Parse a stored EDN string. Returns the parsed frame-id keyword on
  success, or nil on parse failure / unrecognised shape — the load
  path never throws into init."
  [s]
  (let [parsed (try (reader/read-string s)
                    (catch :default _ nil))]
    (when (map? parsed)
      (let [f (:frame parsed)]
        (when (keyword? f) f)))))

(defn load
  "Read + parse the persisted frame selection. Returns the frame-id
  keyword, or nil when:
    - localStorage is unavailable (no browser, no jsdom)
    - the slot is empty (first session)
    - the stored EDN is malformed
    - the stored value isn't a keyword"
  []
  (when-let [raw (read-raw)]
    (<-edn raw)))

(defn save!
  "Write `frame-id` into localStorage. No-op when localStorage is
  unavailable. Swallows quota / serialisation errors."
  [frame-id]
  (write-raw! (->edn frame-id))
  nil)

;; ---- re-frame fx ---------------------------------------------------------

(defn install-fx!
  "Install the `:rf.causa.frame-switcher/persist` effect. Idempotent
  (re-frame's registrar replaces in place). The `:rf.causa/select-
  frame` handler attaches this fx so the localStorage write happens in
  one place instead of being repeated at every call site.

  Handler signature is `(fn [ctx args])` per the v2 reg-fx contract
  (Spec 002 §`:fx` ordering). `ctx` is the frame-scoped envelope;
  `args` is the second element of the `[id args]` pair emitted by the
  event handler — here the frame id keyword."
  []
  (rf/reg-fx :rf.causa.frame-switcher/persist
    (fn [_ctx frame-id]
      (save! frame-id)))
  nil)

;; ---- hydration -----------------------------------------------------------

(defn hydrate!
  "Drive the localStorage → `:focus :frame` slot hydration so the next
  Causa session restores the user's last picked frame.

  Re-entrant. Safe to call from `install!` (preload-time, before the
  frame is registered) AND from `mount.cljs/ensure-causa-frame!`
  (first open, frame registered). Both invocations converge on the
  same slot because:

  - the load read is pure;
  - the hydrate dispatch is a wholesale `(assoc-in db [:focus :frame]
    …)` so re-running with the same source produces the same slot;
  - the frame guard short-circuits the pre-mount call without losing
    state — the localStorage value is still readable at the second
    call.

  Returns nil. No-op when localStorage has no stored selection."
  []
  (when-let [frame-id (load)]
    (when (some? (frame/frame :rf/causa))
      (rf/with-frame :rf/causa
        ;; Dispatch the canonical event so any future side-effects
        ;; (analytics, instrumentation, undo-stack) all run through
        ;; the same path. dispatch-sync because we're at boot — the
        ;; first subscribe needs to see the hydrated value.
        (rf/dispatch-sync [:rf.causa/select-frame frame-id])))
    nil))

;; ---- view ----------------------------------------------------------------

(rf/reg-view frame-switcher-view
  "L1 ribbon frame-switcher — STRICTLY single-select per spec/018 §1
  Non-goals + §3 Frame dropdown + Round-3 rf2-i74n7. No 'All frames
  (merged)' option; no `:multiple` attribute on the `<select>`.
  Excludes `:rf/causa` by default per spec/018 §8 I1. When the only
  available frame is the current selection, the dropdown collapses to
  a flat label (no chevron — no click target).

  Reads `:rf.causa/current-frame` + `:rf.causa/available-frames`;
  writes via `:rf.causa/select-frame <frame-id>` — the canonical
  contract documented at ns-top. Other frame-aware features (Cmd-K
  palette, future panels) reach through the same surface.

  `reg-view`-registered (rf2-in6l2) so its rendered React component
  carries `:contextType frame-context` and the closest enclosing
  `[rf/frame-provider {:frame :rf/causa}]` flows through React-context
  — subscribes resolve to `:rf/causa`."
  [_props]
  (let [selected-frame  @(rf/subscribe [:rf.causa/current-frame])
        frames          @(rf/subscribe [:rf.causa/available-frames])
        label-style     {:color       (:text-primary tokens)
                         :font-family sans-stack
                         :font-size   (:body type-scale)}]
    (if (<= (count frames) 1)
      [:span {:data-testid "rf-causa-ribbon-frame"
              :style (merge label-style {:color (:text-secondary tokens)})}
       (str "Frame: " (or selected-frame (first frames) ":rf/default"))]
      ;; rf2-lbutp — native `<select>` is keyboard- and screen-
      ;; reader-accessible by default, but assistive tech needs an
      ;; accessible NAME to read on focus. The visible "Frame:"
      ;; prefix lives inside each `<option>`, not as a sibling
      ;; `<label>`, so the picker arrives "blank, combobox" without
      ;; this `aria-label`. Matches the convention the audit
      ;; flagged (#16) — frame-switcher gets an explicit accessible
      ;; name.
      [:select {:data-testid "rf-causa-ribbon-frame-picker"
                :aria-label  "Select frame"
                :value       (str (or selected-frame (first frames)))
                :on-change   (fn [^js e]
                               (let [v   (.. e -target -value)
                                     kw  (when (and v (.startsWith v ":"))
                                           (keyword (subs v 1)))]
                                 (when kw
                                   (rf/dispatch [:rf.causa/select-frame kw]
                                                {:frame :rf/causa}))))
                :style       (merge label-style
                               {:background    (:bg-2 tokens)
                                :border        (str "1px solid "
                                                    (:border-default tokens))
                                :border-radius "4px"
                                :padding       "2px 6px"})}
       (for [f frames]
         ^{:key (str f)}
         [:option {:value (str f)} (str "Frame: " f)])])))

;; ---- install -------------------------------------------------------------

(defn install!
  "Idempotent install for the frame-switcher subsystem's Causa-side
  surface. Wires:

    - Subs:   `:rf.causa/current-frame`, `:rf.causa/available-frames`.
    - Events: `:rf.causa/select-frame <frame-id>` (event-fx) — the
              canonical write surface. Dispatches the spine's
              `:rf.causa/set-frame` so every per-frame composite re-
              fires off the new frame's slot AND fires the
              `:rf.causa.frame-switcher/persist` fx so localStorage
              records the choice.
    - Effects: `:rf.causa.frame-switcher/persist` — localStorage
              write fx.
    - Side-effect: hydrate `[:focus :frame]` from localStorage at
              first install via `hydrate!`.

  Called from `registry/register-causa-handlers!` after `spine/install!`
  so the canonical event-fx's `[:dispatch [:rf.causa/set-frame ...]]`
  resolves at install time. The sub/event sub-graph itself doesn't
  require any specific install order — re-frame resolves `:<-` lazily.

  ## Why event-fx (not event-db)

  We want THREE side-effects on every selection write:

    1. spine state mutation (target-frame + epoch-history re-seeding)
    2. localStorage persistence
    3. (future) instrumentation / undo-stack

  An event-fx lets us declaratively chain `[:dispatch …]` for #1 and
  fire `:rf.causa.frame-switcher/persist` for #2 from one handler,
  with room for #3 later without rewriting the call sites."
  []
  (install-fx!)

  ;; ---- subs ----------------------------------------------------------
  ;;
  ;; `:rf.causa/current-frame` composes off `:rf.causa/focus-slot` (the
  ;; spine's raw stored frame slot). This indirection means the sub re-
  ;; fires when ANY code path mutates the slot — the ribbon picker, the
  ;; palette, headless test drivers, the spine's auto-snap on first
  ;; cascade. Reading the composed `:rf.causa/focus` sub here would
  ;; introduce a circular dependency since the spine's `:focus` sub
  ;; already pulls in `:rf.causa/cascades`; the raw slot is the right
  ;; primitive.

  (rf/reg-sub :rf.causa/current-frame
    :<- [:rf.causa/focus-slot]
    (fn [focus _query]
      (:frame focus)))

  ;; `:rf.causa/available-frames` is the canonical 'which frames is it
  ;; meaningful to pick right now' list. Composes off `:rf.causa/
  ;; cascades` so it re-fires as new frames appear in the trace
  ;; stream. The `show-tool-frames?` toggle isn't a sub yet — when the
  ;; Settings UI for it lands (follow-on), this sub will :<- onto the
  ;; toggle's slot. Today the parameter is hardcoded to `false` to
  ;; match the pre-bead picker behaviour.

  (rf/reg-sub :rf.causa/available-frames
    :<- [:rf.causa/cascades]
    (fn [cascades _query]
      ;; show-tool-frames? hardcoded false — see ns docstring.
      (distinct-frames cascades false)))

  ;; ---- events --------------------------------------------------------
  ;;
  ;; `:rf.causa/select-frame <frame-id>` is the canonical write surface.
  ;; The Cmd-K palette's `:palette/select-frame` verb dispatches THIS
  ;; event (not the spine's `:rf.causa/set-frame` primitive directly)
  ;; so every persistence / instrumentation layer we add lives in one
  ;; place. See `palette/events.cljs`'s `:palette/select-frame` clause.

  (rf/reg-event-fx :rf.causa/select-frame
    (fn [_ctx [_ frame-id]]
      {:fx [[:dispatch [:rf.causa/set-frame frame-id]]
            [:rf.causa.frame-switcher/persist frame-id]]}))

  ;; Hydrate from localStorage. The actual logic lives in `hydrate!`
  ;; above so first-mount (`mount/ensure-causa-frame!`) can call the
  ;; same fn to converge.
  (hydrate!)

  nil)
