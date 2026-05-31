(ns day8.re-frame2-xray.local-storage
  "Shared `window.localStorage` primitives — the one place Xray touches
  the browser storage API.

  ## Why this exists (rf2-jkake.24, dedup sweep)

  Seven Xray namespaces persist a slot to localStorage (frame-switcher
  selection, column widths, command-palette recents, spine mute-set,
  filter pills, the Static mode flag, the Static-Machines selection +
  sub-mode map, the machine-canvas view-mode + chart-collapsed maps).
  Each had carried a copy-pasted trio of private helpers:

      (defn- storage-available? [] (and (exists? js/window)
                                        (some? (.-localStorage js/window))))
      (defn- read-raw   [k] (when (storage-available?) (try (.getItem … k)   (catch :default _ nil))))
      (defn- write-raw! [k v] (when (storage-available?) (try (.setItem … k v) (catch :default _ nil))) nil)
      (defn- remove-raw! [k] (when (storage-available?) (try (.removeItem … k) (catch :default _ nil))) nil)

  Identical to the byte across every site — only the storage key and
  the (de)serialisation around it varied. Folding the raw browser
  access into this seam removes the duplication and makes the
  no-op-when-unavailable / swallow-throws posture a one-file
  guarantee. Call-sites keep their own key constants + serialisation +
  public `read-raw`/`write-raw!`/`clear!` wrappers; those wrappers now
  delegate here.

  ## Posture (unchanged from the folded copies)

  - **Availability guard.** Node / JVM test runtimes without a jsdom
    `window.localStorage` land in the no-op branch silently — the
    registry loads these namespaces transitively, so the load path
    must not blow up on classpath load.
  - **Swallow throws.** A quota error or a cross-origin
    `SecurityError` must never poison the dispatch chain that drove
    the write; reads degrade to `nil`.

  ## What this is NOT

  `config.cljc`'s storage shim is deliberately separate: it is `.cljc`
  and falls back to an in-process `memory-storage` atom (not a silent
  no-op) so the settings round-trip still exercises its get/set code
  paths under the JVM test corpus. That divergent behaviour stays
  local to `config.cljc`.")

(defn available?
  "True when `js/window.localStorage` is reachable. Node / JVM test
  runtimes without jsdom return false so the read/write/remove
  primitives no-op silently."
  []
  (and (exists? js/window)
       (some? (.-localStorage js/window))))

(defn get-item
  "Read the raw string stored under `k`. Returns nil when the slot is
  empty or localStorage is unavailable; swallows any access throw."
  [k]
  (when (available?)
    (try
      (.getItem (.-localStorage js/window) k)
      (catch :default _ nil))))

(defn set-item!
  "Write `v` under `k`. No-op when localStorage is unavailable.
  Swallows any throw — a quota error must not poison the dispatch
  chain. Returns nil."
  [k v]
  (when (available?)
    (try
      (.setItem (.-localStorage js/window) k v)
      (catch :default _ nil)))
  nil)

(defn remove-item!
  "Remove the slot at `k`. No-op when localStorage is unavailable.
  Swallows any throw. Returns nil."
  [k]
  (when (available?)
    (try
      (.removeItem (.-localStorage js/window) k)
      (catch :default _ nil)))
  nil)
