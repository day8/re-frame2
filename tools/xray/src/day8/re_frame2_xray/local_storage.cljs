(ns day8.re-frame2-xray.local-storage
  "Shared `window.localStorage` primitives — the one place Xray touches
  the browser storage API.

  ## Why this exists

  Several Xray namespaces persist a slot to localStorage (frame-switcher
  selection, column widths, command-palette recents, spine mute-set,
  the Static mode flag, the Static-Machines selection + sub-mode map,
  the machine-canvas chart-collapsed map). Only the storage key and the
  (de)serialisation around it vary between them, so the raw browser
  access lives in this one seam, which makes the
  no-op-when-unavailable / swallow-throws posture a one-file
  guarantee. Call-sites keep their own key constants + serialisation +
  public `read-raw`/`write-raw!`/`clear!` wrappers; those wrappers
  delegate here.

  ## Posture

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
  primitives no-op silently.

  The `js/window.localStorage` PROPERTY read is itself wrapped in a
  try: a sandboxed iframe (`sandbox` without `allow-same-origin`) or a
  cross-origin / cookie-blocked context throws a `SecurityError` on
  the property ACCESS — before any method is called. Per the ns
  §Posture swallow-throws rule, that access throw must never propagate
  into the dispatch
  chain that drove the read/write; it degrades to `false` (unavailable)
  exactly like the absent-window branch."
  []
  (and (exists? js/window)
       (try
         (some? (.-localStorage js/window))
         (catch :default _ false))))

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
