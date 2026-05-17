(ns day8.re-frame2-causa.filters.persistence
  "localStorage round-trip for Causa's IN/OUT filter pills (rf2-ak4ms).

  Per `tools/causa/spec/018-Event-Spine.md` §7 Filter persistence:
  ribbon pills persist via localStorage per host-app under a Causa-
  namespaced key. The Settings popup (follow-on) exposes the pill set
  + Recommended quick-add + factory-reset; this ns owns the data layer.

  ## Default is empty

  Per Mike's call (rf2-ak4ms / 10x-config R5 / spec/018 §7 'Empty
  defaults'): first-session honesty beats first-session quietness. No
  events are filtered out by default — the user surfaces the noise
  themselves with explicit filters. Shipping a default `:mouse-move`
  filter would silently hide events the user didn't know they were
  emitting.

  ## Per-instance namespacing

  The localStorage key is `re-frame2.causa.filters.v1`. Hosts that run
  multiple Causa instances (Story testbeds) can override the key via
  `(causa-config/configure! {:filters/storage-key \"<key>\"})` — Story
  testbeds set this so their pill state doesn't leak between scenarios.

  ## Production posture

  The whole filters subsystem rides Causa's dev-only preload (gated on
  `interop/debug-enabled?`), so production builds DCE this ns. Reads
  / writes guard `js/window` existence so the JVM test path (which
  loads this ns transitively via the registry) doesn't blow up on
  classpath loads.

  ## Shape

      ;; localStorage value (EDN string):
      {:in  [{:pattern \":auth/*\"}
             {:pattern \":order/*\"}]
       :out [{:pattern \":mouse-move\"}]}

  Hand-edited values that fail to parse are ignored (return nil from
  `load`); the slot stays at its registry-default empty shape rather
  than throwing at boot."
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.config :as config]))

(def default-storage-key
  "Default localStorage key Causa writes filter state under. Hosts
  override via `(causa-config/configure! {:filters/storage-key …})`
  for per-instance isolation (Story testbeds use this)."
  "re-frame2.causa.filters.v1")

;; ---- storage-key delegation ----------------------------------------------
;;
;; The authoritative storage-key lives in `config/filters-storage-key`
;; (CLJC so the JVM test corpus can exercise the configure! round-trip).
;; This ns thunks through so existing callers keep working — there's
;; one source of truth, no atom-drift risk.

(defn set-storage-key!
  "Replace the localStorage key Causa uses for filter persistence. `nil`
  resets to the default. Hosts that mount multiple Causa instances set
  distinct keys so each instance's pill state stays isolated.

  Thunk over `config/set-filters-storage-key!` so there is one source
  of truth for the key."
  [k]
  (config/set-filters-storage-key! k))

(defn get-storage-key
  "Return the current localStorage key."
  []
  (config/get-filters-storage-key))

;; ---- localStorage helpers ------------------------------------------------

(defn- storage-available?
  "True when `js/window.localStorage` is reachable. Tests that drive the
  registry without a browser environment (node + no jsdom) need the
  load path to no-op silently."
  []
  (and (exists? js/window)
       (some? (.-localStorage js/window))))

(defn read-raw
  "Read the raw EDN string Causa wrote under `storage-key`. Returns nil
  when the slot is empty or localStorage is unavailable."
  []
  (when (storage-available?)
    (try
      (.getItem (.-localStorage js/window) (get-storage-key))
      (catch :default _ nil))))

(defn write-raw!
  "Write `s` (an EDN string) under `storage-key`. No-op when
  localStorage is unavailable. Swallows any throw — a quota error must
  not poison the dispatch chain."
  [s]
  (when (storage-available?)
    (try
      (.setItem (.-localStorage js/window) (get-storage-key) s)
      (catch :default _ nil)))
  nil)

(defn clear!
  "Remove the persisted filter state. No-op when localStorage is
  unavailable."
  []
  (when (storage-available?)
    (try
      (.removeItem (.-localStorage js/window) (get-storage-key))
      (catch :default _ nil)))
  nil)

;; ---- (de)serialisation ---------------------------------------------------

(defn ->edn
  "Serialise `filters` (`{:in [...] :out [...]}`) into a stable EDN
  string. Pre-alpha the value is the verbatim slot; future versions can
  add a wrapping `{:v 1 :filters …}` envelope without breaking the load
  path (the loader normalises the shape)."
  [filters]
  (pr-str (or filters {:in [] :out []})))

(defn <-edn
  "Parse a stored EDN string. Returns the parsed map on success, or
  `{:in [] :out []}` on parse failure / unrecognised shape — the
  load path never throws into init."
  [s]
  (let [parsed (try (reader/read-string s)
                    (catch :default _ nil))]
    (if (map? parsed)
      {:in  (vec (get parsed :in []))
       :out (vec (get parsed :out []))}
      {:in [] :out []})))

;; ---- public API ----------------------------------------------------------

(defn load
  "Read + parse the persisted filter slot. Returns `{:in [] :out []}` —
  always a well-shaped map, never nil — so the registry's
  `:rf.causa/active-filters` default lines up with the load result.

  Returns the default empty shape when:
    - localStorage is unavailable (no browser, no jsdom)
    - the slot is empty (first-session honesty per spec/018 §7)
    - the stored EDN is malformed"
  []
  (if-let [raw (read-raw)]
    (<-edn raw)
    {:in [] :out []}))

(defn save!
  "Write `filters` into localStorage. No-op when localStorage is
  unavailable. Swallows quota / serialisation errors."
  [filters]
  (write-raw! (->edn filters))
  nil)

;; ---- re-frame fx ---------------------------------------------------------

(defn install-fx!
  "Install the `:rf.causa.filters/persist` effect. Idempotent (re-
  frame's registrar replaces in place). Handlers in the registry
  attach this fx to every event that mutates `:rf.causa/active-
  filters`; the fx writes the post-mutation slot to localStorage so
  the round-trip happens in one place instead of being repeated at
  every call site.

  Handler signature is `(fn [ctx args])` per the v2 reg-fx contract
  (Spec 002 §`:fx` ordering). `ctx` is the frame-scoped envelope;
  `args` is the second element of the `[id args]` pair emitted by
  the event handler — here the filters map."
  []
  (rf/reg-fx :rf.causa.filters/persist
    (fn [_ctx filters]
      (save! filters)))
  nil)
