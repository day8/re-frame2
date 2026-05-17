(ns re-frame.story.viewport
  "Viewport switcher — preset table + pure state model + localStorage
  helpers (rf2-zll4h).

  Mirrors Storybook's `addon-viewport`: a toolbar dropdown that frames
  the canvas at a chosen width / height so authors can preview a story
  at mobile / tablet / desktop sizes without reaching for the browser
  devtools.

  ## Surface

  - `presets`                   — the canonical preset table.
  - `default-id`                — the neutral preset id (`:full`).
  - `resolve`                   — pure: turn a per-story override or a
                                  toolbar selection into the effective
                                  preset map.
  - `valid-custom?`             — pure: validate a custom `{:width :height}`
                                  map.
  - `wrap-style`                — pure: hiccup-style map for the canvas
                                  wrapper (or nil when full-bleed).
  - `load-from-storage`         — CLJS-only: read the persisted id /
                                  custom map.
  - `save-to-storage!`          — CLJS-only: persist.

  ## Storage key

  Chrome-wide localStorage key — `re-frame.story/viewport`. Stored as a
  `pr-str`-encoded value: a keyword preset id (`:tablet`) for a named
  preset, or a `{:width N :height N}` map for the custom case. Read
  back via `clojure.edn/read-string`.

  ## Per-story override

  A story / variant body MAY carry `:viewport` — either a keyword preset
  id or a literal `{:width :height}` map. When the canvas mounts, the
  override wins over the toolbar selection. The chip surfaces the
  effective preset's label so the user knows which is in force.

  ## Elision

  Pure data + a defensive CLJS storage layer; no fn-valued slots. The
  CLJS surfaces guard against missing `js/window.localStorage` (private
  mode / file:// / Node test runner) by returning nil / no-op."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.edn :as edn]))

;; ---- preset table --------------------------------------------------------

(def presets
  "The canonical viewport preset table. Keys are preset ids; values are
  `{:label :width :height}` — `:width`/`:height` are nil for the
  `:full` (no resize) preset."
  {:full              {:label "Full"             :width nil  :height nil}
   :mobile-portrait   {:label "Mobile portrait"  :width 375  :height 667}
   :mobile-landscape  {:label "Mobile landscape" :width 667  :height 375}
   :tablet            {:label "Tablet"           :width 768  :height 1024}
   :desktop           {:label "Desktop"          :width 1280 :height 800}
   :desktop-wide      {:label "Desktop wide"     :width 1920 :height 1080}})

(def preset-order
  "Display order for the dropdown — `:full` first, then ascending width."
  [:full
   :mobile-portrait
   :mobile-landscape
   :tablet
   :desktop
   :desktop-wide])

(def ^:const default-id
  "Neutral default — no resize, full bleed."
  :full)

(def ^:const ls-key
  "Chrome-wide localStorage key for the persisted viewport selection."
  "re-frame.story/viewport")

;; ---- pure: resolve / validate -------------------------------------------

(defn valid-custom?
  "True iff `m` is a `{:width N :height N}` map with positive integer
  width and height. Pure."
  [m]
  (and (map? m)
       (let [w (:width m)
             h (:height m)]
         (and (integer? w) (pos? w)
              (integer? h) (pos? h)))))

(defn valid-selection?
  "True iff `sel` is a preset id keyword we recognise OR a valid custom
  `{:width :height}` map. Pure."
  [sel]
  (or (and (keyword? sel) (contains? presets sel))
      (valid-custom? sel)))

(defn coerce
  "Coerce a `:viewport` slot (per-story override or toolbar selection)
  into one of:

  - a recognised preset keyword,
  - a `{:width :height}` map,
  - nil when the input is unusable.

  Pure data → data. Strings are NOT accepted; the schema rejects them."
  [v]
  (cond
    (and (keyword? v) (contains? presets v)) v
    (valid-custom? v)                        (select-keys v [:width :height])
    :else                                    nil))

(defn resolve
  "Return the effective viewport `{:label :width :height}` map.

  Args:
    - `story-override` — the variant / story body's `:viewport` slot
                         (or nil).
    - `selection`      — the chrome toolbar selection (or nil).

  Precedence: story-override > selection > `:full` default. Custom
  `{:width :height}` selections render with a synthetic label
  (`\"Custom WxH\"`)."
  [story-override selection]
  (let [pick (or (coerce story-override) (coerce selection) default-id)]
    (if (keyword? pick)
      (get presets pick)
      {:label  (str "Custom " (:width pick) "x" (:height pick))
       :width  (:width pick)
       :height (:height pick)})))

(defn resolve-id
  "Like `resolve` but returns the resolved selection id (a preset
  keyword) or `:custom` for a custom map. Useful for `data-*`
  attributes."
  [story-override selection]
  (let [pick (or (coerce story-override) (coerce selection) default-id)]
    (if (keyword? pick) pick :custom)))

;; ---- pure: canvas-wrapper style -----------------------------------------

(defn wrap-style
  "Hiccup-style map for the canvas wrapper, given the resolved preset
  `{:width :height}` pair. Returns nil for the `:full` preset (caller
  skips the wrapper entirely).

  The wrapper draws a soft border + shadow around the framed canvas so
  the framed region is visually distinct from the surrounding chrome."
  [{:keys [width height]}]
  (when (and width height)
    {:width        (str width "px")
     :height       (str height "px")
     :max-width    "100%"
     :margin       "0 auto"
     :border       "1px solid #444"
     :box-shadow   "0 4px 14px rgba(0,0,0,0.4)"
     :box-sizing   "border-box"
     :overflow     "auto"}))

;; ---- CLJS-only: localStorage --------------------------------------------

#?(:cljs
   (defn- safe-local-storage []
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.-localStorage js/window)
            (catch :default _ nil)))))

(defn load-from-storage
  "Read the persisted viewport selection from localStorage. Returns
  one of: a preset id keyword, a `{:width :height}` custom map, or nil
  (no persisted value / unparseable). JVM returns nil unconditionally
  (no localStorage)."
  []
  #?(:clj  nil
     :cljs (when-let [ls (safe-local-storage)]
             (try
               (let [raw (.getItem ls ls-key)]
                 (when (string? raw)
                   (let [parsed (edn/read-string raw)]
                     (when (valid-selection? parsed)
                       (coerce parsed)))))
               (catch :default _ nil)))))

(defn save-to-storage!
  "Persist `sel` to localStorage. `sel` may be a preset id keyword, a
  `{:width :height}` custom map, or nil (clears the slot). Silently
  no-ops on JVM and when localStorage is unavailable."
  [sel]
  #?(:clj  nil
     :cljs (when-let [ls (safe-local-storage)]
             (try
               (if (nil? sel)
                 (.removeItem ls ls-key)
                 (when-let [normalised (coerce sel)]
                   (.setItem ls ls-key (pr-str normalised))))
               (catch :default _ nil))))
  nil)
