(ns day8.re-frame2-xray.static.persistence
  "localStorage round-trip for Xray's Dynamic ↔ Static mode selection.

  Per `tools/xray/spec/007-UX-IA.md` §Static mode + the findings doc
  `ai/findings/2026-05-19-xray-explorer-mode.md`: Xray exposes TWO
  modes — Dynamic (the event-coupled spine + 4-layer chrome) and
  Static (event-independent browse of what's registered). The user's
  mode choice survives reloads via localStorage under the canonical
  key `xray.mode`.

  ## Shape

      ;; localStorage value (raw string — not EDN-quoted):
      \"dynamic\"   ;; or \"static\"

  A bare string keeps the value cheap to read + cheap to inspect from
  the browser's devtools — modes are an enum, not a structured value.
  Unrecognised values fall back to `:dynamic` (the conservative
  default; the existing chrome).

  ## Why the namespace prefix is `xray.mode` (not `re-frame2.xray…`)

  Mirror the spec-published key name from the findings doc: `xray.mode`
  is shorter and reads naturally in browser devtools. The filter
  persistence ns uses the longer `re-frame2.xray.filters.v1` because
  that slot grew an explicit version axis (the filter shape may evolve);
  the mode slot is a fixed enum so versioning is overkill.

  ## Production posture

  Rides Xray's dev-only preload (gated on `interop/debug-enabled?`),
  so production builds DCE this ns. Reads / writes guard `js/window`
  existence so the JVM test path (which loads this ns transitively
  via the registry) doesn't blow up on classpath load.

  ## Test-only override

  Tests that need a deterministic starting mode without touching the
  real browser localStorage call `with-storage-stub` which threads a
  per-call stub through the load/save fns. Standalone tests that need
  a clean slate call `clear!` in their `:each` fixture."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.local-storage :as ls]))

(def storage-key
  "Canonical localStorage key for Xray's Dynamic ↔ Static mode
  selection. Constant — not configurable via `configure!` (unlike the
  filter pills which support per-instance isolation for Story
  testbeds; mode is a process-global UX choice)."
  "xray.mode")

;; ---- mode normalisation -------------------------------------------------

(def valid-modes
  "The two modes Xray knows. Anything outside this set is normalised
  back to `:dynamic` (conservative default — the existing chrome)."
  #{:dynamic :static})

(defn normalise-mode
  "Coerce a raw mode value (keyword OR string OR nil) to the canonical
  `:dynamic | :static` keyword. Unknown values fall back to `:dynamic`.

  Pure data — JVM-runnable so the JVM test corpus can cover the
  normalisation round-trip."
  [v]
  (cond
    (keyword? v) (if (contains? valid-modes v) v :dynamic)
    (string? v)  (let [kw (keyword v)]
                   (if (contains? valid-modes kw) kw :dynamic))
    :else        :dynamic))

(defn ->raw
  "Serialise a mode keyword to its localStorage string form."
  [mode]
  (name (normalise-mode mode)))

(defn <-raw
  "Parse a raw localStorage string back to a mode keyword. Nil / unknown
  → `:dynamic`."
  [s]
  (normalise-mode s))

;; ---- localStorage helpers -----------------------------------------------
;; Raw browser access + the no-op-when-unavailable / swallow-throws
;; posture live in the shared `local-storage` seam.

(defn read-raw
  "Read the raw string Xray wrote under `storage-key`. Returns nil
  when the slot is empty or localStorage is unavailable."
  []
  (ls/get-item storage-key))

(defn write-raw!
  "Write `s` under `storage-key`. No-op when localStorage is
  unavailable. Swallows any throw — a quota error must not poison
  the dispatch chain."
  [s]
  (ls/set-item! storage-key s))

(defn clear!
  "Remove the persisted mode slot. Used by tests to reset between
  scenarios. No-op when localStorage is unavailable."
  []
  (ls/remove-item! storage-key))

;; ---- public API ---------------------------------------------------------

(defn load
  "Read + parse the persisted mode slot. Always returns a valid mode
  keyword (`:dynamic` is the conservative fallback when the slot is
  empty, unavailable, or carries an unknown value)."
  []
  (<-raw (read-raw)))

(defn save!
  "Write `mode` to localStorage. No-op when localStorage is
  unavailable. Normalises unknown inputs back to `:dynamic`."
  [mode]
  (write-raw! (->raw mode))
  nil)

;; ---- re-frame fx --------------------------------------------------------

(defn install-fx!
  "Install the `:rf.xray.static/persist-mode` effect. Idempotent —
  re-frame's registrar replaces in place. The registry's `:rf.xray/
  set-mode` + `:rf.xray/toggle-mode` handlers attach this fx so the
  post-mutation slot lands in localStorage in one place.

  Handler signature `(fn [ctx args])` per the v2 reg-fx contract."
  []
  (rf/reg-fx :rf.xray.static/persist-mode
    (fn [_ctx mode]
      (save! mode)))
  nil)
