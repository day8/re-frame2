(ns day8.re-frame2-causa.static.persistence
  "localStorage round-trip for Causa's Runtime ↔ Static mode selection
  (rf2-o5f5f.1).

  Per `tools/causa/spec/007-UX-IA.md` §Static mode + the findings doc
  `ai/findings/2026-05-19-causa-explorer-mode.md`: Causa exposes TWO
  modes — Runtime (the existing event-coupled spine + 4-layer chrome)
  and Static (event-independent browse of what's registered). The
  user's mode choice survives reloads via localStorage under the
  canonical key `causa.mode`.

  ## Shape

      ;; localStorage value (raw string — not EDN-quoted):
      \"runtime\"   ;; or \"static\"

  A bare string keeps the value cheap to read + cheap to inspect from
  the browser's devtools — modes are an enum, not a structured value.
  Unrecognised values fall back to `:runtime` (the conservative
  default; the existing chrome).

  ## Why the namespace prefix is `causa.mode` (not `re-frame2.causa…`)

  Mirror the spec-published key name from the findings doc: `causa.mode`
  is shorter and reads naturally in browser devtools. The filter
  persistence ns uses the longer `re-frame2.causa.filters.v1` because
  that slot grew an explicit version axis (the filter shape may evolve);
  the mode slot is a fixed enum so versioning is overkill.

  ## Production posture

  Rides Causa's dev-only preload (gated on `interop/debug-enabled?`),
  so production builds DCE this ns. Reads / writes guard `js/window`
  existence so the JVM test path (which loads this ns transitively
  via the registry) doesn't blow up on classpath load.

  ## Test-only override

  Tests that need a deterministic starting mode without touching the
  real browser localStorage call `with-storage-stub` which threads a
  per-call stub through the load/save fns. Standalone tests that need
  a clean slate call `clear!` in their `:each` fixture."
  (:require [re-frame.core :as rf]))

(def storage-key
  "Canonical localStorage key for Causa's Runtime ↔ Static mode
  selection. Constant — not configurable via `configure!` (unlike the
  filter pills which support per-instance isolation for Story
  testbeds; mode is a process-global UX choice)."
  "causa.mode")

;; ---- mode normalisation -------------------------------------------------

(def valid-modes
  "The two modes Causa knows. Anything outside this set is normalised
  back to `:runtime` (conservative default — the existing chrome)."
  #{:runtime :static})

(defn normalise-mode
  "Coerce a raw mode value (keyword OR string OR nil) to the canonical
  `:runtime | :static` keyword. Unknown values fall back to `:runtime`.

  Pure data — JVM-runnable so the JVM test corpus can cover the
  normalisation round-trip."
  [v]
  (cond
    (keyword? v) (if (contains? valid-modes v) v :runtime)
    (string? v)  (let [kw (keyword v)]
                   (if (contains? valid-modes kw) kw :runtime))
    :else        :runtime))

(defn ->raw
  "Serialise a mode keyword to its localStorage string form."
  [mode]
  (name (normalise-mode mode)))

(defn <-raw
  "Parse a raw localStorage string back to a mode keyword. Nil / unknown
  → `:runtime`."
  [s]
  (normalise-mode s))

;; ---- localStorage helpers -----------------------------------------------

(defn- storage-available?
  "True when `js/window.localStorage` is reachable. Tests that drive
  the registry without a browser (node-test) need the load path to
  no-op silently."
  []
  (and (exists? js/window)
       (some? (.-localStorage js/window))))

(defn read-raw
  "Read the raw string Causa wrote under `storage-key`. Returns nil
  when the slot is empty or localStorage is unavailable."
  []
  (when (storage-available?)
    (try
      (.getItem (.-localStorage js/window) storage-key)
      (catch :default _ nil))))

(defn write-raw!
  "Write `s` under `storage-key`. No-op when localStorage is
  unavailable. Swallows any throw — a quota error must not poison
  the dispatch chain."
  [s]
  (when (storage-available?)
    (try
      (.setItem (.-localStorage js/window) storage-key s)
      (catch :default _ nil)))
  nil)

(defn clear!
  "Remove the persisted mode slot. Used by tests to reset between
  scenarios. No-op when localStorage is unavailable."
  []
  (when (storage-available?)
    (try
      (.removeItem (.-localStorage js/window) storage-key)
      (catch :default _ nil)))
  nil)

;; ---- public API ---------------------------------------------------------

(defn load
  "Read + parse the persisted mode slot. Always returns a valid mode
  keyword (`:runtime` is the conservative fallback when the slot is
  empty, unavailable, or carries an unknown value)."
  []
  (<-raw (read-raw)))

(defn save!
  "Write `mode` to localStorage. No-op when localStorage is
  unavailable. Normalises unknown inputs back to `:runtime`."
  [mode]
  (write-raw! (->raw mode))
  nil)

;; ---- re-frame fx --------------------------------------------------------

(defn install-fx!
  "Install the `:rf.causa.static/persist-mode` effect. Idempotent —
  re-frame's registrar replaces in place. The registry's `:rf.causa/
  set-mode` + `:rf.causa/toggle-mode` handlers attach this fx so the
  post-mutation slot lands in localStorage in one place.

  Handler signature `(fn [ctx args])` per the v2 reg-fx contract."
  []
  (rf/reg-fx :rf.causa.static/persist-mode
    (fn [_ctx mode]
      (save! mode)))
  nil)
