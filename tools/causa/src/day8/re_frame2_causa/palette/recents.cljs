(ns day8.re-frame2-causa.palette.recents
  "localStorage round-trip for the Causa command palette's
  last-used-commands list (rf2-ybjkx).

  Per the bead's acceptance contract: the palette persists the top-3
  recently-invoked commands to localStorage so a reload surfaces the
  user's recent verbs first. The open-state itself is transient and
  NEVER persisted — only the recents list.

  ## Shape

  Storage value (raw string — EDN-encoded vector of command ids):

      ;; localStorage value
      \"[:rf.causa.cmd/toggle-theme :rf.causa.cmd/jump-tab-event ...]\"

  Most-recent-first order. The list is capped at 3 entries (per the
  bead — top-3 recent). `record!` dedups on identity so re-invoking
  the same command bubbles it back to position 0 without growing the
  list.

  ## Why a small bounded list

  Three is the sweet spot Vue DevTools and Linear converge on: enough
  to surface the user's last meaningful gestures, not so many that the
  recents block crowds out fresh fuzzy hits. The cap is a `def` so a
  future tuning bead can flip it without re-touching every call site.

  ## Production posture

  Mirrors `static/persistence.cljs` — `storage-available?` guard so
  the JVM test path (which loads this ns transitively via the
  registry) doesn't blow up on classpath load. All writes swallow
  throws so quota / serialisation failures never poison the dispatch
  chain that drove the record."
  (:require [cljs.reader :as reader]))

(def storage-key
  "Canonical localStorage key for the palette's recents list. Versioned
  so a future shape change (e.g. carrying timestamps alongside the ids
  for time-decay weighting) can ignore stale payloads cleanly."
  "re-frame2.causa.palette.recents.v1")

(def max-recents
  "Cap on the recents list. Three matches Vue DevTools / Linear /
  GitHub command palettes — enough to surface the user's recent
  gestures without crowding fuzzy hits. A `def` so a future tuning
  bead can flip the cap in one place."
  3)

;; ---- localStorage helpers -----------------------------------------------

(defn- storage-available?
  "True when `js/window.localStorage` is reachable. Node test runtimes
  return false here so the load/save fns no-op silently."
  []
  (and (exists? js/window)
       (some? (.-localStorage js/window))))

(defn- read-raw
  []
  (when (storage-available?)
    (try
      (.getItem (.-localStorage js/window) storage-key)
      (catch :default _ nil))))

(defn- write-raw!
  [s]
  (when (storage-available?)
    (try
      (.setItem (.-localStorage js/window) storage-key s)
      (catch :default _ nil)))
  nil)

(defn clear!
  "Remove the persisted slot. Test-only — fixtures reset between
  scenarios."
  []
  (when (storage-available?)
    (try
      (.removeItem (.-localStorage js/window) storage-key)
      (catch :default _ nil)))
  nil)

;; ---- public API ---------------------------------------------------------

(defn- sanitise
  "Drop any non-keyword entries from `v`. The persisted payload is
  authored by `record!` (keywords only), but a hand-edited storage
  slot or a stale schema could carry non-keyword garbage; treat the
  list as best-effort and drop the rest rather than throwing on
  first paint."
  [v]
  (vec (take max-recents (filter keyword? v))))

(defn load
  "Read the persisted recents list. Always returns a vector of command
  ids (most-recent-first). Empty vector when the slot is empty, the
  payload is malformed, or localStorage is unavailable."
  []
  (try
    (if-let [raw (read-raw)]
      (let [parsed (reader/read-string raw)]
        (if (sequential? parsed)
          (sanitise parsed)
          []))
      [])
    (catch :default _ [])))

(defn save!
  "Persist `recents` (a seq of command ids). Caps at `max-recents`
  before write so the payload never exceeds the contract."
  [recents]
  (write-raw! (pr-str (sanitise recents)))
  nil)

(defn record
  "Pure helper. Prepend `command-id` to `current` (a seq of command
  ids), dedup by identity, cap at `max-recents`. The freshly-invoked
  command always lands at index 0 — re-invoking an existing recent
  bubbles it back to the head.

  Returns a vector. Empty / nil `command-id` returns `current`
  unchanged (no-op for non-recordable invokes)."
  [current command-id]
  (if (nil? command-id)
    (vec current)
    (let [rest-keep (remove #(= command-id %) (or current []))]
      (vec (take max-recents (cons command-id rest-keep))))))
