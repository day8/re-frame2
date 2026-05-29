(ns day8.re-frame2-xray.focus
  "Host-facing focus API for an embedded Xray surface (rf2-crtmq).

  ## What this owns

  A small, **data-shaped focus command** a host (Story) sends to focus
  an already-embedded Xray surface onto a specific panel + epoch +
  cascade + app-db path — driven from a narrative beat, a failed
  assertion, a canvas inspect command, or a docs/test link.

  This is the channel the StoryUI decision register
  (`ai/findings/StoryUI/06-open-decisions.md` §D3 \"Story-To-Xray Focus
  API\") settled on: a small one-way focus command, NOT a broad two-way
  embedding protocol.

  ## The ownership boundary (load-bearing)

  - **Story owns the narrative / action** — it constructs the command
    (which panel, which epoch, which path) and the `:source` provenance
    (which variant / assertion / beat triggered it), then calls
    `focus!`.
  - **Xray owns the diagnostic state + panel semantics** — it receives
    the command and routes it to the canonical `:rf.xray/*` spine / tab
    / path / frame events. Xray decides what \"focus the app-db panel on
    epoch 42 at path [:checkout :state]\" *means*.

  No second Xray runtime model is introduced. Every field in the
  command maps to an EXISTING canonical write surface:

  | Command field | Canonical Xray write | Owner ns |
  |---|---|---|
  | `:frame`       | `:rf.xray/select-frame <frame-id>` | `frame_switcher.cljs` |
  | `:panel`       | `:rf.xray/select-tab <tab-id>`     | `registry.cljs` |
  | `:epoch-id`    | `:rf.xray/focus-epoch <epoch-id>`  | `spine.cljs` |
  | `:dispatch-id` | `:rf.xray/focus-cascade <id> <frame>` | `spine.cljs` |
  | `:path`        | `:rf.xray/focus-slice-path <path>` | `app_db_diff_events.cljs` |

  This namespace is a thin composer over those events — it adds no new
  app-db slots, no new spine model, no new panel state. It mirrors the
  posture of `day8.re-frame2-xray.runtime` (the MCP read/mutate seam):
  a clean, host-agnostic, data-in / data-out façade over surfaces that
  already exist.

  ## Separate from open-full-Xray

  Mounting / opening / popping-out the whole Xray shell stays in
  `mount.cljs` (`open!` / `open-overlay!` / `popout!`). This namespace
  is purely *focus-a-panel-on-a-beat* — it assumes the Xray surface is
  already embedded (Story mounts it via the locked render-shell
  contract). Per §D3 rule: \"opening/pop-out of the full Xray shell is
  current; focusing a panel/beat/path is [the new surface].\"

  ## Command shape (the contract)

      {:frame       <frame-id>   ; the HOST frame Xray should observe
                                 ;   (optional — omit to keep current scope)
       :panel       <tab-id>     ; which L4 tab to surface
                                 ;   (one of `valid-panels`)
       :epoch-id    <epoch-id>   ; settling epoch to pin the spine to
       :dispatch-id <id>         ; cascade root to pin the spine to
                                 ;   (alternative to :epoch-id; both is fine)
       :path        [<k> ...]    ; app-db path to highlight in the App-db panel
       :source      {...}}       ; OPAQUE provenance — Story's intent context;
                                 ;   Xray does NOT interpret it, only echoes it
                                 ;   back for diagnostics / round-trip tests.

  Every field is optional. An empty command (`{}`) is a well-formed
  no-op focus (returns `:ok? true` with `:applied []`). A command with
  only `:panel` just flips the tab. This keeps narrative beats terse —
  a beat that only cares about \"show me the trace\" sends
  `{:panel :trace}`.

  The `:source` map is host-agnostic provenance. §D3's worked shape:

      {:kind        :story/assertion
       :variant/id  :story.checkout/form/submitted
       :assertion/id :checkout-state-submitted}

  Xray treats `:source` as opaque — it never reaches into its keys.
  Story (or any other host) is free to put whatever provenance shape
  it likes there. This is what makes the command host-agnostic: the
  channel carries Story's intent without Xray knowing it's Story.

  ## Pure core + thin CLJS shell

  `focus-command->dispatches` is a pure, `.cljc`, JVM-runnable
  translation from a command map to the ordered vector of event
  vectors. The CLJS `focus!` fn (below, reader-conditional) resolves
  the Xray frame and fires them via `re-frame.core/dispatch`. The pure
  core is what the test corpus drives; the CLJS shell is the runtime
  glue.

  ## Why ordered dispatches

  The events are dispatched in a fixed order — `:frame` FIRST (so the
  per-frame epoch ring is re-seeded before any epoch / cascade pin
  lands; `:rf.xray/set-frame` clears the pinned `:dispatch-id` and
  re-seeds `:epoch-history`, so a later `:focus-epoch` resolves against
  the correct frame's ring), THEN `:dispatch-id` / `:epoch-id` (the
  spine pin), THEN `:panel` (the tab) and `:path` (the app-db slice).
  Reversing frame and epoch would let the epoch pin resolve against the
  wrong frame's ring."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.defaults :as defaults]))

;; ---------------------------------------------------------------------------
;; The panel vocabulary
;; ---------------------------------------------------------------------------
;;
;; The canonical L4-tab ids the Dynamic shell surfaces (per
;; `tools/xray/spec/007-UX-IA.md` §The 4-layer chrome L3 + the
;; panel-registry inventory). The `:rf.xray/select-tab` event itself
;; does NOT guard the id (it writes any keyword onto `:selected-tab`,
;; and the L4 detail panel renders an unknown-tab stub for a stale id);
;; this set is the published, host-facing vocabulary so a host can
;; validate a panel selector before sending — and so the focus API
;; rejects a typo'd panel with a useful diagnostic rather than silently
;; selecting a tab that renders the unknown-tab stub.

(def valid-panels
  "The canonical host-facing Xray panel (L4 tab) ids a focus command
  may target. Matches the spine's 7-tab inventory (Epoch · app-db ·
  Views · Trace · Machine · Routes · Issues) — internal registry keys
  `:views` / `:routes` per `tools/xray/spec/007-UX-IA.md`."
  #{:epoch :app-db :views :trace :machines :routes :issues})

;; ---------------------------------------------------------------------------
;; Pure command → dispatches translation (JVM-runnable)
;; ---------------------------------------------------------------------------

(defn focus-command->dispatches
  "Pure translation of a focus `command` map into an ORDERED vector of
  `:rf.xray/*` event vectors. JVM-runnable; no re-frame runtime,
  no app-db. The CLJS `focus!` fn fires these in order.

  `command` is the §D3 shape (every field optional):

      {:frame :checkout
       :panel :app-db
       :epoch-id 42
       :dispatch-id 17
       :path [:checkout :state]
       :source {...}}

  Returns a vector of event vectors. Ordering (see ns docstring):

    1. `[:rf.xray/select-frame <frame>]` — re-scope FIRST so the
       per-frame epoch ring is re-seeded before any pin resolves.
    2. `[:rf.xray/focus-cascade <dispatch-id> <frame>]` — when
       `:dispatch-id` present. The cascade pin carries the frame so
       `cascade-by-id`'s per-frame disambiguation works.
    3. `[:rf.xray/focus-epoch <epoch-id>]` — when `:epoch-id` present
       AND `:dispatch-id` absent. (When both are supplied the
       cascade pin wins — it carries the frame and the spine resolves
       the settling epoch from it; an explicit `:epoch-id` is the
       lighter selector for callers that only have the epoch.)
    4. `[:rf.xray/select-tab <panel>]` — when `:panel` present.
    5. `[:rf.xray/focus-slice-path <path>]` — when `:path` present
       (App-db panel slice highlight).

  `:source` produces NO dispatch — it is opaque provenance Xray echoes
  back via `focus!`'s return map; it never mutates Xray state.

  Note this fn does NOT validate `:panel` against `valid-panels` — it
  is a faithful translator. Validation is `focus!`'s job (it owns the
  diagnostic return shape); a caller wanting the raw translation can
  pass any panel keyword here."
  [{:keys [frame panel epoch-id dispatch-id path] :as _command}]
  (cond-> []
    (some? frame)       (conj [:rf.xray/select-frame frame])
    (some? dispatch-id) (conj [:rf.xray/focus-cascade dispatch-id frame])
    (and (some? epoch-id)
         (nil? dispatch-id))
    (conj [:rf.xray/focus-epoch epoch-id])
    (some? panel)       (conj [:rf.xray/select-tab panel])
    (some? path)        (conj [:rf.xray/focus-slice-path path])))

;; ---------------------------------------------------------------------------
;; Host-facing focus entry point (CLJS — fires into the Xray frame)
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- apply-focus!
     "Validate + fire a (frame-merged) focus `command` into Xray's
     `:rf/xray` shell frame. Returns the `focus!` result shape. The two
     public `focus!` arities both funnel through here after resolving
     the positional host-frame."
     [{:keys [panel source sync?] :as command}]
     (if (and (some? panel) (not (contains? valid-panels panel)))
       {:ok?    false
        :reason :unknown-panel
        :given  panel
        :valid  valid-panels
        :hint   (str "Unknown Xray panel " (pr-str panel)
                     ". Use one of " (pr-str valid-panels) ".")}
       (let [dispatches (focus-command->dispatches command)]
         ;; Fire into Xray's own shell frame (the host never sees it —
         ;; the channel is the command, not the frame split). Mirrors
         ;; runtime.cljs's mutation accessors + share.cljs's restore
         ;; path, which both target `defaults/default-frame-id` for the
         ;; no-surrounding-frame case.
         (rf/with-frame defaults/default-frame-id
           (doseq [event-vec dispatches]
             (if sync?
               (rf/dispatch-sync event-vec)
               (rf/dispatch event-vec))))
         {:ok?     true
          :applied dispatches
          :source  source}))))

#?(:cljs
   (defn focus!
     "Focus an embedded Xray surface from a host (Story) beat / assertion
     / inspect command.

     Two arities mirror §D3's worked shape:

         (focus! command)             ; the command's own `:frame` scopes
         (focus! host-frame command)  ; `host-frame` is the frame to observe,
                                      ;   merged into the command as `:frame`
                                      ;   (an explicit command `:frame` wins)

     The 2-arity matches §D3's `(story.xray/focus! frame-id {...})` —
     the host names the frame Xray should observe as a positional arg,
     keeping the command map about WHAT to focus rather than WHERE.

     `command` is the §D3 focus-command map (see ns docstring +
     `focus-command->dispatches`). Every field optional.

     Dispatches each translated `:rf.xray/*` event into Xray's own
     `:rf/xray` shell frame (via `re-frame.core/with-frame` —
     mirroring `runtime.cljs`'s mutation accessors + `share.cljs`'s
     restore path). The host never sees Xray's internal frame; the
     channel is the command, not the frame split.

     Returns a data-shaped result (mirroring `runtime.cljs`'s
     `{:ok? ...}` idiom):

         {:ok?     true
          :applied [[:rf.xray/select-frame :checkout] ...]  ; events fired, in order
          :source  {...}}                                   ; echoed provenance (or nil)

     or, for a command naming an unknown panel:

         {:ok?     false
          :reason  :unknown-panel
          :given   :app-bd
          :valid   #{:epoch :app-db ...}
          :hint    \"...\"}

     Unknown panel is the one rejected case — a typo'd selector would
     otherwise silently land the L4 'unknown tab' stub. Every other
     field is permissive (a missing epoch / evicted cascade degrades
     through the spine's existing placeholder UX, not an error here).

     The command's optional `:sync?` control field fires `dispatch-sync`
     instead of `dispatch` (test rigs + same-tick host flows); it is a
     control key, never translated to a dispatch. Returns nothing useful
     outside a CLJS runtime.

     The two arities are EXACTLY §D3's worked shape — `(focus! command)`
     and `(focus! frame-id command)` — there is no opts arg (the only
     control knob, `:sync?`, rides inside the command map). This keeps
     the arities unambiguous: a map in head position is always the
     command; a non-map (a frame-id keyword) in head position is always
     the positional host-frame."
     ([command]
      (apply-focus! command))
     ([host-frame command]
      ;; positional host-frame fills :frame only when the command didn't
      ;; name one — an explicit command :frame is the more specific
      ;; intent and wins.
      (apply-focus! (cond-> command
                      (and (some? host-frame) (nil? (:frame command)))
                      (assoc :frame host-frame))))))
