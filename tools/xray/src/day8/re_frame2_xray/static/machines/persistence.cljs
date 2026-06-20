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
  "Dispatch a hydrate event carrying the persisted selection + sub-mode
  map. Runs once at install! time. Safe to call before frame
  `:rf/xray` is mounted — `dispatch` queues the event; it lands after
  the frame is registered. Per `mount.cljs/ensure-xray-frame!` the
  registry handlers install before the frame mounts, so the dispatch
  goes through the standard queue and is replayed against the new
  frame on the first dispatch-cycle."
  []
  ;; Init-time hydration targets the production Xray shell frame via the
  ;; named `defaults/default-frame-id` Var (production-singleton seam),
  ;; not a `{:frame :rf/xray}` literal.
  (rf/dispatch [:rf.xray.static.machines/hydrate
                {:selected-id    (load-selected-id)
                 :sub-mode-by-id (load-sub-mode-by-id)}]
               {:frame defaults/default-frame-id})
  nil)
