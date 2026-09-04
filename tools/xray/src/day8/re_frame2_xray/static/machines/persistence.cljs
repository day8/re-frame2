(ns day8.re-frame2-xray.static.machines.persistence
  "localStorage round-trip for the Static Machines sub-tab's selection
  + per-machine sub-mode.

  Two slots ride localStorage so the user's choices survive reloads:

    `xray.static.machines.selected-id`     — string form of the
                                              currently selected
                                              machine-id keyword
                                              (`name` only — namespaced
                                              ids store as `ns/name`)
    `xray.static.machines.sub-mode-by-id`  — EDN map
                                              `{machine-id sub-mode}`

  ## Why two slots

  Selection is a single value — a bare string keeps it cheap to
  inspect in browser devtools (mirrors `static/persistence.cljs`'s
  `xray.mode` slot — same pattern, same rationale).

  Sub-mode is per-machine, so the map has to ride a single slot keyed
  by machine-id. EDN is the same serialiser the filter slot uses
  (`re-frame2.xray.filters.v1`); modes are an enum so versioning
  feels overkill, but the map grows new keys as new sub-modes land,
  and EDN handles that cleanly.

  ## Production posture

  Rides Xray's dev-only preload (gated on `interop/debug-enabled?`),
  so production builds DCE this ns. Every read / write guards
  `js/window` existence so the JVM test path doesn't blow up on
  classpath load.

  ## Test-only

  Tests that need a deterministic starting state call `clear!` in
  their `:each` fixture."
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.static.machines.helpers :as h]))

(def selection-key
  "Canonical localStorage key for the selected machine-id slot."
  "xray.static.machines.selected-id")

(def sub-mode-key
  "Canonical localStorage key for the per-machine sub-mode map slot."
  "xray.static.machines.sub-mode-by-id")

;; ---- localStorage helpers -----------------------------------------------
;; Raw browser access lives in the shared `local-storage` seam;
;; both slots are key-parameterised here.

;; ---- selection round-trip -----------------------------------------------

(defn save-selected-id!
  "Write the selected machine-id keyword to localStorage. nil clears
  the slot."
  [machine-id]
  (if (nil? machine-id)
    (ls/remove-item! selection-key)
    (when (keyword? machine-id)
      (ls/set-item! selection-key (subs (str machine-id) 1))))
  nil)

(defn load-selected-id
  "Read + parse the persisted selected-id keyword. Returns nil when the
  slot is empty / localStorage is unavailable / the value is not a
  valid keyword name."
  []
  (when-let [raw (ls/get-item selection-key)]
    (try
      (keyword raw)
      (catch :default _ nil))))

;; ---- per-machine sub-mode round-trip ------------------------------------

(defn save-sub-mode-by-id!
  "Write the `{machine-id sub-mode}` map to localStorage as pr-str
  EDN. Empty / nil map clears the slot."
  [by-id]
  (if (or (nil? by-id) (and (map? by-id) (empty? by-id)))
    (ls/remove-item! sub-mode-key)
    (ls/set-item! sub-mode-key (pr-str by-id)))
  nil)

(defn load-sub-mode-by-id
  "Read + parse the persisted sub-mode map. Returns `{}` when the slot
  is empty / unparseable. Every value normalises through
  `helpers/normalise-sub-mode` so a corrupted entry falls back to
  `:topology` rather than crashing the render."
  []
  (when-let [raw (ls/get-item sub-mode-key)]
    (try
      (let [parsed (reader/read-string raw)]
        (when (map? parsed)
          (into {}
                (keep (fn [[k v]]
                        (when (keyword? k)
                          [k (h/normalise-sub-mode v)])))
                parsed)))
      (catch :default _ {}))))

;; ---- clear! / test-only -------------------------------------------------

(defn clear!
  "Drop both slots. Used by tests to reset between scenarios. No-op
  when localStorage is unavailable."
  []
  (ls/remove-item! selection-key)
  (ls/remove-item! sub-mode-key)
  nil)

;; ---- re-frame fx + hydration -------------------------------------------

(defn install-fx!
  "Install the persist-selection + persist-sub-mode fxs. Idempotent —
  re-frame's registrar replaces in place. The panel's :select /
  :set-sub-mode events attach these fxs so the post-mutation slot
  lands in localStorage in one place."
  []
  (rf/reg-fx :rf.xray.static.machines/persist-selection
    (fn [_ctx machine-id]
      (save-selected-id! machine-id)))
  (rf/reg-fx :rf.xray.static.machines/persist-sub-mode
    (fn [_ctx by-id]
      (save-sub-mode-by-id! by-id)))
  nil)

(defn hydrate!
  "Lift the persisted selection + per-machine sub-mode map into the
  shell frame's app-db, so a reload restores the operator's last
  choices.

  Mirrors `views.resizable-table/hydrate!` and `frame-switcher/
  hydrate!`: re-entrant; safe to call from `install!` (orchestrator
  time, before the frame is registered) AND from `mount.cljs/
  ensure-xray-frame!`'s `::hydrate-static-machines` first-mount hook
  (first open, frame registered). Both invocations converge on the
  same slots because:

    - both loads are pure reads;
    - the hydrate event assocs each slot wholesale, so re-running
      against the same source produces the same app-db;
    - the frame guard short-circuits the pre-mount call without
      losing state — localStorage is still readable at the second
      call.

  ## Why the frame guard is load-bearing (rf2-qw0o)

  This fn used to dispatch UNGUARDED, on the documented premise that
  `dispatch` queues an event aimed at a not-yet-registered frame and
  replays it once that frame exists. It does not. `install!` runs from
  `registry/register-xray-handlers!`, which is orchestrator-time —
  well before `ensure-xray-frame!` registers `:rf/xray` — so the
  dispatch named a frame that did not exist, was refused with a
  promoted `:rf.error/frame-destroyed`, and was DROPPED. The persisted
  selection therefore never restored (silent state loss on every
  reload), and the refusal surfaced one promoted console error on every
  Xray-preloaded dev page load. Guarding here makes the early call an
  honest no-op; the first-mount hook is what lands the restore.

  `frame-id` (rf2-lnluk) defaults to the production singleton
  `defaults/default-frame-id` (`:rf/xray`). A second shell instance
  passes its own frame-id (threaded by `ensure-xray-frame!`'s
  first-mount hook) so the durable slots land on that instance's app-db.

  `dispatch-sync` because this runs at boot — the first subscribe must
  see the hydrated value rather than the registry default.

  Returns nil. No-op when neither slot holds anything to restore."
  ([] (hydrate! defaults/default-frame-id))
  ([frame-id]
   (let [selected-id    (load-selected-id)
         sub-mode-by-id (load-sub-mode-by-id)]
     ;; Nothing persisted → no dispatch at all. The hydrate event would
     ;; be a pure no-op on empty slots, and Xray's own trace ring is the
     ;; surface it inspects; a boot-time write of nothing is noise.
     (when (and (or (some? selected-id) (seq sub-mode-by-id))
                (some? (rf.frame/frame frame-id)))
       (rf/with-frame frame-id
         (rf/dispatch-sync [:rf.xray.static.machines/hydrate
                            {:selected-id    selected-id
                             :sub-mode-by-id sub-mode-by-id}]))))
   nil))
