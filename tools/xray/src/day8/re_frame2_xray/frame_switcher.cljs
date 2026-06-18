(ns day8.re-frame2-xray.frame-switcher
  "Xray's hardened L1 frame-switcher slot (rf2-iwwou) — the single
  contractually-anchored surface every frame-aware feature reaches
  through.

  ## Why a dedicated ns

  Xray is increasingly frame-aware: App-DB Diff, Views, Routing, the
  machine-inspector scrubber, and the Cmd-K palette's `:select-frame`
  verb all need to read 'which frame is the user currently focused on?'
  and need to write that selection back when the user picks a
  different frame. Pre-bead the picker lived inline in `shell.cljs`'s
  ribbon and the spine's `:rf.xray/set-frame` event was the de-facto
  write surface; the palette dispatched the same event. That worked,
  but the contract was implicit — nothing in source said 'this is THE
  way to read / write frame focus' and new frame-aware features had no
  obvious primitive to require.

  This ns formalises the contract.

  ## The contract

  Three public symbols make up the slot's external surface — every
  frame-aware feature SHOULD reach through these only:

  - **Sub `:rf.xray/current-frame`** — returns the frame id the user
    has focused (or the default when no explicit selection has been
    made). Composes off the spine's `:rf.xray/focus-slot` so the
    sub re-fires automatically when the spine flips frames via any
    code path (ribbon picker, palette, headless test driver).

  - **Sub `:rf.xray/available-frames`** — returns a first-seen-order
    vec of distinct frames present in the live cascade list, filtered
    by the `show-tool-frames?` setting (spec/018 §8 I1). The single
    source of truth for 'which frames is it meaningful to pick right
    now'. Composes off `:rf.xray/cascades` so the list re-fires as
    new frames appear in the trace stream.

  - **Event-fx `:rf.xray/select-frame <frame-id>`** — canonical write
    surface. Dispatches the spine's `:rf.xray/set-frame` (which
    re-seeds `:target-frame` + `:epoch-history`, see
    `spine/set-frame-reducer` docstring for the full propagation
    story) AND persists the selection to localStorage so the next
    Xray session restores the same focus. The Cmd-K palette's
    `:palette/select-frame` verb dispatches THIS event, not the spine
    primitive directly — that way every persistence / instrumentation
    layer we add lives in one place.

  The L1 ribbon picker reads `:rf.xray/current-frame` +
  `:rf.xray/available-frames`, dispatches `:rf.xray/select-frame`.
  No ad-hoc frame access from the ribbon view code — the picker is
  one thin call site against the canonical contract.

  ## Pure helpers

  `distinct-frames` + `internal-frames` are pure data ops the sub
  composer + tests both reach through. Exported so tests can hit them
  without bouncing through a subscribe.

  ## Persistence shape

  localStorage key `re-frame2.xray.frame-switcher.v1` (per-instance
  overridable via the direct setter `frame-switcher/set-storage-key!`;
  a future `configure! :rf.xray/frame-switcher-storage-key` plumb
  is straightforward but not wired today). Value is a one-key EDN map
  `{:frame :rf/cart-frame}` — wrapping
  a bare keyword would force callers to handle 'literal nil means no
  selection' vs 'no entry means no selection' separately; the map
  envelope keeps room for future fields (e.g. last-N-frames history,
  per-frame UI state) without a versioned migration.

  ## Hydration

  `hydrate!` runs at install time (preload) AND from
  `mount/ensure-xray-frame!` on first open. Re-entrant — the dispatch
  is a wholesale `(assoc db :focus (assoc focus :frame …))`. Guards
  on `(frame/frame :rf/xray)` so the pre-mount call short-circuits
  cleanly. Mirrors the `filters/hydrate!` shape so the two surfaces
  share an ergonomic pattern."
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale sans-stack]]))

;; ---- public contract: realm grouping (EP-0013 disposition 3 · rf2-3caq85) --
;;
;; In a MULTI-REALM process the picker groups its frame options by the
;; runtime realm each frame belongs to (a `<optgroup>` per realm). In a
;; SINGLE-REALM process the picker render is BYTE-IDENTICAL to the
;; pre-bead flat option list — zero-ceremony extends to the tooling
;; (EP-0013 disposition 3: a single-realm app never spells a realm, and
;; neither does its devtool). The grouping appears ONLY when more than
;; one distinct realm is present across the pickable frames.
;;
;; The frame→realm map is the internal substrate seam
;; `re-frame.frame/frame-realm` — the frame-side half of the (realm,
;; frame) address (EP-0023 retained-internal; pl97nd.2 removed the
;; `rf/frame-realm` facade alias). Pairing it with `re-frame.realm/realm-ids`
;; (the installed realms) is the full address-space walk EP-0013 disposition 3
;; documents. The
;; grouping helper takes the resolver as an argument so the pure
;; data-shape logic runs under the unit-test target without a live
;; frame registry.

;; ---- public contract: which frames Xray filters out by default ---------

(def internal-frames
  "Frames Xray filters out of the picker by default per spec/018 §8 I1.
  `:rf/xray` is Xray's own state; `:rf/re-frame2-pair` is the future
  MCP-pair frame. A future Settings 'Show tool frames in picker' toggle
  will re-include them under a `── Power user ──` divider; the toggle
  UI is not built yet, so the picker is hardcoded to exclude them.

  Public so a future Settings ns can read the canonical set when it
  surfaces the toggle, and so tests can assert the membership without
  duplicating the literal."
  #{:rf/xray :rf/re-frame2-pair})

;; ---- pure helpers --------------------------------------------------------

(defn distinct-frames
  "Pure helper — returns the distinct frames present in `cascades` in
  first-seen order. Drives the `:rf.xray/available-frames` sub.

  Filters `:rf/xray` (and other tool frames per `internal-frames`)
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

(defn head-frame
  "Pure helper — the DEFAULT view-scope frame on load (rf2-4vp5j
  Decision 1): the frame of the head (most recent) pickable cascade.

  Walks `cascades` newest-first and returns the first frame that is
  pickable (non-nil, not an `internal-frames` tool frame). nil only
  when the stream carries no pickable frame at all (empty / tool-only).

  This is the seam that makes the frame a SINGLE defaulted view scope:
  on a fresh load the L2 list scopes to whichever frame produced the
  most recent event, rather than merging every frame. The user can
  override via the picker; the override lives on the dedicated
  `:view-scope-frame` slot (NOT persisted — rf2-swclw)."
  [cascades]
  (some (fn [cascade]
          (let [f (:frame cascade)]
            (when (and (some? f)
                       (not (contains? internal-frames f)))
              f)))
        (reverse cascades)))

;; ---- realm grouping (EP-0013 disposition 3 · rf2-3caq85) ----------------

(defn group-frames-by-realm
  "Pure helper — group `frames` (a first-seen-order vec from
  `distinct-frames`) by the runtime realm each frame belongs to, using
  `realm-of` (a `frame-id → realm-id` resolver, normally
  `re-frame.frame/frame-realm`). Returns a vector of `{:realm <realm-id> :frames
  [<frame-id> …]}` groups in first-realm-seen order, each group's
  `:frames` preserving the input frame order.

  Frames whose `realm-of` resolves to nil (an unknown / destroyed frame
  the picker still lists) fall into a single trailing group keyed by
  `:rf.realm/default` — absence is the default realm, the documented
  EP-0013 D1 rule, so an unstamped frame never strands the picker.

  The view calls `multi-realm?` on the result first: in a single-realm
  process this collapses to one group and the picker renders the FLAT
  option list (byte-identical to the pre-bead render); only a
  multi-realm result drives the `<optgroup>` grouping. Pure data → data;
  JVM-testable."
  [frames realm-of]
  (let [order (volatile! [])
        seen  (volatile! #{})]
    (->> frames
         (reduce
           (fn [acc f]
             (let [rid (or (realm-of f) :rf.realm/default)]
               (when-not (contains? @seen rid)
                 (vswap! seen conj rid)
                 (vswap! order conj rid))
               (update acc rid (fnil conj []) f)))
           {})
         (#(mapv (fn [rid] {:realm rid :frames (get % rid)}) @order)))))

(defn multi-realm?
  "Pure predicate — true when `groups` (the `group-frames-by-realm`
  result) spans more than one realm. Drives the picker's zero-ceremony
  branch: a single-realm process renders the flat option list, a
  multi-realm process renders `<optgroup>`s. Pure; JVM-testable."
  [groups]
  (> (count groups) 1))

;; ---- persistence ---------------------------------------------------------

(def default-storage-key
  "Default localStorage key Xray writes the frame-switcher selection
  under. Hosts override via the direct setter
  `frame-switcher/set-storage-key!` for per-instance isolation
  (Story testbeds
  use this so per-scenario picks don't leak between scenarios)."
  "re-frame2.xray.frame-switcher.v1")

(defonce ^:private storage-key
  (atom default-storage-key))

(defn set-storage-key!
  "Replace the localStorage key Xray uses for frame-switcher
  persistence. `nil` resets to the default."
  [k]
  (reset! storage-key (or k default-storage-key))
  nil)

(defn get-storage-key
  "Return the current localStorage key for frame-switcher persistence."
  []
  @storage-key)

;; Raw browser access + the no-op-when-unavailable / swallow-throws
;; posture live in the shared `local-storage` seam (rf2-jkake.24). The
;; key is resolved per call through `get-storage-key` so a runtime
;; `set-storage-key!` re-key takes effect immediately.

(defn- read-raw
  "Read the raw EDN string Xray wrote under `storage-key`. Returns nil
  when the slot is empty or localStorage is unavailable."
  []
  (ls/get-item (get-storage-key)))

(defn- write-raw!
  "Write `s` (an EDN string) under `storage-key`. No-op when
  localStorage is unavailable. Swallows any throw — a quota error
  must not poison the dispatch chain."
  [s]
  (ls/set-item! (get-storage-key) s))

(defn clear!
  "Remove the persisted selection. No-op when localStorage is
  unavailable. Hosts use this when tearing down per-scenario state in
  Story testbeds."
  []
  (ls/remove-item! (get-storage-key)))

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
  "Install the `:rf.xray.frame-switcher/persist` effect. Idempotent
  (re-frame's registrar replaces in place). The `:rf.xray/select-
  frame` handler attaches this fx so the localStorage write happens in
  one place instead of being repeated at every call site.

  Handler signature is `(fn [ctx args])` per the v2 reg-fx contract
  (Spec 002 §`:fx` ordering). `ctx` is the frame-scoped envelope;
  `args` is the second element of the `[id args]` pair emitted by the
  event handler — here the frame id keyword."
  []
  (rf/reg-fx :rf.xray.frame-switcher/persist
    (fn [_ctx frame-id]
      (save! frame-id)))
  nil)

;; ---- hydration -----------------------------------------------------------

(defn hydrate!
  "Drive the localStorage → `:focus :frame` slot hydration so the next
  Xray session restores the user's last picked frame.

  Re-entrant. Safe to call from `install!` (preload-time, before the
  frame is registered) AND from `mount.cljs/ensure-xray-frame!`
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
    (when (some? (frame/frame :rf/xray))
      (rf/with-frame :rf/xray
        ;; Dispatch the canonical event so any future side-effects
        ;; (analytics, instrumentation, undo-stack) all run through
        ;; the same path. dispatch-sync because we're at boot — the
        ;; first subscribe needs to see the hydrated value.
        (rf/dispatch-sync [:rf.xray/select-frame frame-id])))
    nil))

;; ---- view ----------------------------------------------------------------

(rf/reg-view frame-switcher-view
  "L1 chrome-ribbon frame-switcher — the `Frame ▾` dropdown BUTTON per
  the Figma design-reference (the `chrome-ribbon` component in
  `design-reference/xray_devtools_reference.cljs`) + spec/018 §3 Frame dropdown. STRICTLY single-select (spec/018
  §1 Non-goals + Round-3 rf2-i74n7): no 'All frames (merged)' option, no
  `:multiple` attribute. Excludes `:rf/xray` by default per spec/018 §8
  I1.

  ## Figma shape (rf2-pjjwh)

  The control's button face shows the CURRENTLY-SELECTED frame value
  (live) — the Figma mock's frame switcher surfaces the active frame, not
  a static `Frame` / `Main` placeholder. The label binds to
  `:rf.xray/current-frame`; it falls back to the literal `Frame` only when
  no frame is pickable yet (cold start). The option list also carries a
  `✓` on the active option. (rf2-pjjwh supersedes rf2-ad7zx.12's static
  `Frame ▾` label.)

  Implementation is the overlay pattern: a styled `Frame ▾` button face
  is the visible affordance; a native `<select>` is layered transparently
  on top so keyboard + screen-reader navigation + the native option
  popup all work out of the box (rf2-lbutp). The `<select>` carries the
  `aria-label` so assistive tech announces 'Select frame, combobox'.

  The button ALWAYS renders (the chrome shows a Frame control per Figma
  — the prior 'collapse to a flat label' branch is gone, rf2-ad7zx.12).
  The overlaid select is interactive whenever there is at least one frame
  (rf2-ad7zx.14): a native `<select>` with a lone option still opens and
  shows it, so a single-frame host (e.g. the step-deck, with one
  `:step-deck` frame) gets a working 1-entry dropdown. Only a zero-frame
  state disables the control.

  Reads `:rf.xray/current-frame` + `:rf.xray/available-frames`; writes
  via `:rf.xray/select-frame <frame-id>` — the canonical contract
  documented at ns-top. Other frame-aware features (Cmd-K palette, future
  panels) reach through the same surface.

  `reg-view`-registered (rf2-in6l2) so its rendered React component
  carries `:contextType frame-context` and the closest enclosing
  `[rf/frame-provider {:frame :rf/xray}]` flows through React-context
  — subscribes resolve to `:rf/xray`."
  [_props]
  (let [selected-frame  @(rf/subscribe [:rf.xray/current-frame])
        frames          @(rf/subscribe [:rf.xray/available-frames])
        ;; rf2-3caq85 — the realm grouping of the same pickable frames.
        ;; A single-realm process collapses to one group and renders the
        ;; flat option list below (byte-identical to the pre-bead render);
        ;; only a multi-realm process renders `<optgroup>`s.
        realm-groups    @(rf/subscribe [:rf.xray/available-frame-realm-groups])
        multi-realm     (multi-realm? realm-groups)
        active          (or selected-frame (first frames))
        ;; rf2-ad7zx.14 — interactive whenever there is >=1 frame. A native
        ;; <select> with one option opens + shows it (not inert), which is
        ;; what Mike expects in the step-deck (a single :step-deck frame).
        ;; The prior `(> (count frames) 1)` left the control disabled (and
        ;; click-dead) with a lone frame. STRICTLY single-select.
        pickable?       (pos? (count frames))]
    [:div {:data-testid "rf-xray-ribbon-frame"
           :title       (if active
                          (str "Frame scope: " active " — pick a frame to scope the inspector")
                          "Frame scope")
           :style {:position      "relative"
                   :display       "inline-flex"
                   :align-items   "center"
                   :gap           "4px"
                   :background    (:bg-2 tokens)
                   :border        (str "1px solid " (:border-default tokens))
                   :border-radius "4px"
                   :padding       "2px 8px"
                   :color         (:text-primary tokens)
                   :font-family   sans-stack
                   :font-size     (:body-tight type-scale)
                   :line-height   "1.3"
                   :cursor        (if pickable? "pointer" "default")
                   :flex-shrink   0}}
     ;; rf2-pjjwh — the button face shows the CURRENTLY-SELECTED frame
     ;; (live), per the Figma mock's frame switcher (which surfaces the
     ;; active frame, not a static `Main` / `Frame` placeholder). Binds to
     ;; `:rf.xray/current-frame`; falls back to the literal `Frame` only
     ;; when no frame is pickable yet (cold start, empty stream). The
     ;; option list still carries the `✓` on the active option.
     [:span {:data-testid "rf-xray-ribbon-frame-label"}
      (if active (str active) "Frame")]
     [:span {:data-testid "rf-xray-ribbon-frame-chevron"
             :aria-hidden "true"
             :style {:font-size (:caption type-scale)
                     :color     (:text-secondary tokens)
                     :line-height "1"}}
      "▾"]
     ;; rf2-lbutp — native `<select>` layered transparently over the
     ;; button face so keyboard + screen-reader + the native option
     ;; popup all work; the visible `Frame ▾` is the design affordance.
     [:select {:data-testid "rf-xray-ribbon-frame-picker"
               :aria-label  "Select frame"
               :disabled    (not pickable?)
               :value       (str active)
               :on-change   (fn [^js e]
                              (let [v   (.. e -target -value)
                                    kw  (when (and v (.startsWith v ":"))
                                          (keyword (subs v 1)))]
                                (when kw
                                  ;; rf2-nesy9 — dispatch through the
                                  ;; reg-view-injected frame-aware
                                  ;; `dispatch` so the frame select lands
                                  ;; on the surrounding instance frame.
                                  (dispatch [:rf.xray/select-frame kw]))))
               :style       {:position   "absolute"
                             :top        0
                             :left       0
                             :width      "100%"
                             :height     "100%"
                             :opacity    0
                             :cursor     (if pickable? "pointer" "default")
                             :border     "none"
                             :margin     0
                             :padding    0}}
      ;; In a MULTI-REALM process the options group by the internal
      ;; installation realm (a `<optgroup>` per realm). In a SINGLE-REALM
      ;; process this branch is skipped and the flat option list below
      ;; renders byte-identically to the pre-bead picker — zero-ceremony
      ;; extends to the tooling. The frame is the EP-0023 public addressing
      ;; unit; the realm is the RETAINED-INTERNAL installation boundary, so
      ;; the group header labels it as installation structure, not a peer
      ;; public browse dimension.
      (if multi-realm
        (for [{:keys [realm frames]} realm-groups]
          ^{:key (str realm)}
          [:optgroup {:label       (str "installation realm " realm)
                      :data-testid (str "rf-xray-ribbon-frame-realm-group-" (name realm))}
           (for [f frames]
             ^{:key (str f)}
             [:option {:value (str f)}
              (str (if (= f active) "✓ " "  ") f)])])
        (for [f frames]
          ^{:key (str f)}
          [:option {:value (str f)}
           (str (if (= f active) "✓ " "  ") f)]))]]))

;; ---- install -------------------------------------------------------------

(defn install!
  "Idempotent install for the frame-switcher subsystem's Xray-side
  surface. Wires:

    - Subs:   `:rf.xray/current-frame`, `:rf.xray/available-frames`.
    - Events: `:rf.xray/select-frame <frame-id>` (event-fx) — the
              canonical write surface. Dispatches the spine's
              `:rf.xray/set-frame` so every per-frame composite re-
              fires off the new frame's slot AND fires the
              `:rf.xray.frame-switcher/persist` fx so localStorage
              records the choice.
    - Effects: `:rf.xray.frame-switcher/persist` — localStorage
              write fx.

  Does NOT hydrate `[:focus :frame]` from localStorage (rf2-swclw): the
  frame pin is a transient exploration filter, reset to unpinned on
  every page load. `mount.cljs`'s `::reset-transient-filters` first-
  mount hook clears the stale slot. `hydrate!` / `load` remain as the
  data layer for tests and opt-in hosts, but init does not restore.

  Called from `registry/register-xray-handlers!` after `spine/install!`
  so the canonical event-fx's `[:dispatch [:rf.xray/set-frame ...]]`
  resolves at install time. The sub/event sub-graph itself doesn't
  require any specific install order — re-frame resolves `:<-` lazily.

  ## Why event-fx (not event-db)

  We want THREE side-effects on every selection write:

    1. spine state mutation (target-frame + epoch-history re-seeding)
    2. localStorage persistence
    3. (future) instrumentation / undo-stack

  An event-fx lets us declaratively chain `[:dispatch …]` for #1 and
  fire `:rf.xray.frame-switcher/persist` for #2 from one handler,
  with room for #3 later without rewriting the call sites."
  []
  (install-fx!)

  ;; ---- subs ----------------------------------------------------------
  ;;
  ;; `:rf.xray/current-frame` composes off `:rf.xray/focus-slot` (the
  ;; spine's raw stored frame slot). This indirection means the sub re-
  ;; fires when ANY code path mutates the slot — the ribbon picker, the
  ;; palette, headless test drivers, the spine's auto-snap on first
  ;; cascade. Reading the composed `:rf.xray/focus` sub here would
  ;; introduce a circular dependency since the spine's `:focus` sub
  ;; already pulls in `:rf.xray/cascades`; the raw slot is the right
  ;; primitive.

  ;; `:rf.xray/view-scope-frame` (rf2-4vp5j Workstream C) — the
  ;; dedicated VIEW-SCOPE slot. The frame is a single defaulted view
  ;; scope, NOT a filter and NOT the spine's `[:focus :frame]` slot
  ;; (which is also written by epoch auto-alignment + the focus-step
  ;; walk — reading it for the LIST scope was the conflation bug). The
  ;; picker writes `:view-scope-frame` directly; the L2 list +
  ;; hidden-count read it.
  ;;
  ;; Resolution order (first non-nil wins):
  ;;   1. the explicit picker slot (`:view-scope-frame`);
  ;;   2. the host-seeded `:target-frame` (rf2-ulpp8 — a host calling
  ;;      `core/set-target-frame!` is saying 'observe this frame', which
  ;;      IS the view scope on first mount);
  ;;   3. the head epoch's frame (`head-frame`) — the rf2-4vp5j Decision-1
  ;;      default so a fresh load with no host seed scopes to whichever
  ;;      frame produced the most recent event.
  ;; Composes off `:rf.xray/cascades` so the head default re-resolves
  ;; as the stream grows; once the user explicitly picks, slot #1 wins.
  (rf/reg-sub :rf.xray/view-scope-frame
    :<- [:rf.xray/view-scope-frame-slot]
    :<- [:rf.xray/target-frame-slot]
    :<- [:rf.xray/cascades]
    (fn [[slot target-slot cascades] _query]
      (or slot target-slot (head-frame cascades))))

  ;; Raw stored slots — separated so the composed sub above can default
  ;; off the cascade list without a cycle.
  (rf/reg-sub :rf.xray/view-scope-frame-slot
    (fn [db _query]
      (:view-scope-frame db)))

  ;; Raw `:target-frame` slot WITHOUT the `:rf.xray/target-frame`
  ;; sub's `default-target-frame` fallback — nil when no host seed has
  ;; been applied, so it only contributes to the view-scope default when
  ;; a host explicitly called `set-target-frame!`.
  (rf/reg-sub :rf.xray/target-frame-slot
    (fn [db _query]
      (:target-frame db)))

  ;; `:rf.xray/current-frame` — what the picker shows + writes against.
  ;; Reads the resolved VIEW-SCOPE frame (rf2-4vp5j) so the dropdown
  ;; reflects the single defaulted view scope, decoupled from the
  ;; spine's auto-tracking `[:focus :frame]`.
  (rf/reg-sub :rf.xray/current-frame
    :<- [:rf.xray/view-scope-frame]
    (fn [view-scope-frame _query]
      view-scope-frame))

  ;; `:rf.xray/available-frames` is the canonical 'which frames is it
  ;; meaningful to pick right now' list. Composes off `:rf.xray/
  ;; cascades` so it re-fires as new frames appear in the trace
  ;; stream. The `show-tool-frames?` toggle isn't a sub yet — when the
  ;; Settings UI for it lands (follow-on), this sub will :<- onto the
  ;; toggle's slot. Today the parameter is hardcoded to `false` to
  ;; match the pre-bead picker behaviour.

  (rf/reg-sub :rf.xray/available-frames
    :<- [:rf.xray/cascades]
    (fn [cascades _query]
      ;; show-tool-frames? hardcoded false — see ns docstring.
      (distinct-frames cascades false)))

  ;; `:rf.xray/available-frame-realm-groups` (rf2-3caq85) — the same
  ;; pickable frames, grouped by the runtime realm each belongs to
  ;; (EP-0013 disposition 3). Composes off `:rf.xray/available-frames`
  ;; and resolves each frame's realm through the internal substrate seam
  ;; `re-frame.frame/frame-realm` (the frame-side half of the (realm, frame)
  ;; address; EP-0023 retained-internal). In a
  ;; single-realm process every frame resolves to the default realm, so
  ;; the result is ONE group and the picker renders the flat option list
  ;; (byte-identical to the pre-bead render — `multi-realm?` is false);
  ;; only a multi-realm process drives the `<optgroup>` grouping in the
  ;; view. The resolver is `frame/frame-realm` (live frame registry); the
  ;; pure grouping is `group-frames-by-realm` so the data shape is
  ;; JVM-testable independently of a runtime.
  (rf/reg-sub :rf.xray/available-frame-realm-groups
    :<- [:rf.xray/available-frames]
    (fn [frames _query]
      (group-frames-by-realm frames frame/frame-realm)))

  ;; ---- events --------------------------------------------------------
  ;;
  ;; `:rf.xray/select-frame <frame-id>` is the canonical write surface.
  ;; The Cmd-K palette's `:palette/select-frame` verb dispatches THIS
  ;; event (not the spine's `:rf.xray/set-frame` primitive directly)
  ;; so every persistence / instrumentation layer we add lives in one
  ;; place. See `palette/events.cljs`'s `:palette/select-frame` clause.

  ;; Writes the dedicated `:view-scope-frame` slot (rf2-4vp5j) — the
  ;; single source of truth for the L2 list's view scope — AND still
  ;; dispatches the spine's `:rf.xray/set-frame` so the LEGITIMATE
  ;; per-frame epoch alignment (App-DB Diff / Views / machine-inspector
  ;; reading `:epoch-history`, rf2-ulpp8) follows the picker. The two
  ;; concerns are now distinct slots: `:view-scope-frame` scopes the
  ;; LIST; `[:focus :frame]` (via set-frame) aligns the per-frame
  ;; epoch reads. nil clears the view scope back to its head-frame
  ;; default. Persist fx is retained (the data layer); init does not
  ;; restore it (rf2-swclw — frame is transient).
  (rf/reg-event :rf.xray/select-frame
    (fn [{:keys [db]} [_ frame-id]]
      {:db (if (some? frame-id)
             (assoc db :view-scope-frame frame-id)
             (dissoc db :view-scope-frame))
       :fx [[:dispatch [:rf.xray/set-frame frame-id]]
            [:rf.xray.frame-switcher/persist frame-id]]}))

  ;; NO hydrate from localStorage on install (rf2-swclw). The frame pin
  ;; is a TRANSIENT exploration filter — it resets to unpinned on every
  ;; page load so a fresh session never silently scopes the inspector to
  ;; a frame chosen in a past session. The `[:focus :frame]` slot starts
  ;; at its registry default (unpinned) and `mount.cljs`'s
  ;; `::reset-transient-filters` first-mount hook clears the stale
  ;; localStorage slot so storage matches. `hydrate!` / `load` remain as
  ;; the data layer (exercised by the round-trip tests), but init does
  ;; not restore.

  nil)
