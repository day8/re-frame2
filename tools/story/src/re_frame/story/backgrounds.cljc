(ns re-frame.story.backgrounds
  "Backgrounds switcher — preset table + pure state model + localStorage
  helpers (rf2-zll4h).

  Mirrors Storybook's `addon-backgrounds`: a toolbar dropdown that swaps
  the canvas background colour so an author can review the same story
  against light / dark / paper / transparent backgrounds without
  modifying the story body.

  ## Surface

  - `presets`                — the canonical preset table.
  - `default-id`             — the neutral preset id (`:light`).
  - `resolve`                — pure: turn a per-story override or a
                               toolbar selection into the effective
                               preset map.
  - `valid-custom?`          — pure: validate a custom colour string.
  - `wrap-style`             — pure: hiccup-style map for the canvas
                               background.
  - `load-from-storage`      — CLJS-only: read the persisted id /
                               custom colour.
  - `save-to-storage!`       — CLJS-only: persist.

  ## Storage key

  Chrome-wide localStorage key — `re-frame.story/background`. Stored
  as a `pr-str`-encoded value: a keyword preset id (`:dark`) for a
  named preset, or a string `\"#abc123\"` for the custom case.

  ## Per-story override

  A story / variant body MAY carry `:background` — either a keyword
  preset id or a literal colour string (`\"#abc123\"`). When the canvas
  mounts, the override wins over the toolbar selection.

  ## Transparent / checkerboard

  The `:transparent` preset renders via a CSS `linear-gradient`
  checkerboard trick — two crossed gradients at 45° produce a familiar
  alpha-pattern background without an SVG asset."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---- preset table --------------------------------------------------------

(def presets
  "The canonical background preset table. Values are
  `{:label :color}` — `:color` is either a CSS colour string or the
  sentinel keyword `:checkerboard` for the transparent preset."
  {:light       {:label "Light"       :color "#ffffff"}
   :dark        {:label "Dark"        :color "#1a1a1a"}
   :paper       {:label "Paper"       :color "#f9f9f9"}
   :midnight    {:label "Midnight"    :color "#0a0a0a"}
   :transparent {:label "Transparent" :color :checkerboard}})

(def preset-order
  "Display order for the dropdown."
  [:light :dark :paper :midnight :transparent])

(def ^:const default-id
  "Neutral default — light background."
  :light)

(def ^:const ls-key
  "Chrome-wide localStorage key for the persisted background selection."
  "re-frame.story/background")

;; ---- pure: validate / coerce --------------------------------------------

(def ^:private hex-colour-re
  #"^#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

(defn valid-custom?
  "True iff `s` is a string that looks like a usable CSS colour. The
  validator accepts 3/6/8-digit hex strings — the colour input element
  produces 7-char `#rrggbb` strings, so that's the common path. Other
  shapes (named colours, `rgb()`) are intentionally not accepted at
  storage time; authors who need them set them on the canvas wrap via
  a decorator."
  [s]
  (boolean (and (string? s) (re-matches hex-colour-re (str/trim s)))))

(defn valid-selection?
  "True iff `sel` is a preset id we recognise OR a valid custom colour
  string."
  [sel]
  (or (and (keyword? sel) (contains? presets sel))
      (valid-custom? sel)))

(defn coerce
  "Coerce a `:background` slot into one of:

  - a recognised preset keyword,
  - a hex colour string,
  - nil when unusable.

  Pure data → data."
  [v]
  (cond
    (and (keyword? v) (contains? presets v)) v
    (valid-custom? v)                        (str/trim v)
    :else                                    nil))

(defn resolve
  "Return the effective background `{:label :color}` map.

  Args:
    - `story-override` — the variant / story body's `:background` slot
                         (or nil).
    - `selection`      — the chrome toolbar selection (or nil).

  Precedence: story-override > selection > `:light` default. Custom
  hex strings render with a synthetic label (`\"Custom #abc123\"`)."
  [story-override selection]
  (let [pick (or (coerce story-override) (coerce selection) default-id)]
    (if (keyword? pick)
      (get presets pick)
      {:label (str "Custom " pick)
       :color pick})))

(defn resolve-id
  "Return the resolved selection id (a preset keyword) or `:custom`
  for a custom colour string. Useful for `data-*` attributes."
  [story-override selection]
  (let [pick (or (coerce story-override) (coerce selection) default-id)]
    (if (keyword? pick) pick :custom)))

;; ---- pure: canvas-wrapper style -----------------------------------------

(def ^:private checkerboard-style
  "CSS-only checkerboard via two crossed linear-gradients. Renders a
  familiar alpha-pattern background without an SVG asset."
  {:background-color  "#ffffff"
   :background-image
   (str "linear-gradient(45deg, #cccccc 25%, transparent 25%), "
        "linear-gradient(-45deg, #cccccc 25%, transparent 25%), "
        "linear-gradient(45deg, transparent 75%, #cccccc 75%), "
        "linear-gradient(-45deg, transparent 75%, #cccccc 75%)")
   :background-size     "16px 16px"
   :background-position "0 0, 0 8px, 8px -8px, -8px 0px"})

(defn wrap-style
  "Hiccup-style map for the canvas wrapper, given the resolved preset
  `{:color}` value. Returns either a `:background-color` map for a
  flat colour or the checkerboard background-image set for the
  `:transparent` preset."
  [{:keys [color]}]
  (cond
    (= :checkerboard color) checkerboard-style
    (string? color)         {:background-color color}
    :else                   nil))

;; ---- CLJS-only: localStorage --------------------------------------------

#?(:cljs
   (defn- safe-local-storage []
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.-localStorage js/window)
            (catch :default _ nil)))))

(defn load-from-storage
  "Read the persisted background selection from localStorage. Returns
  one of: a preset id keyword, a hex colour string, or nil. JVM
  returns nil unconditionally."
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
  "Persist `sel` to localStorage. Silently no-ops on JVM and when
  localStorage is unavailable."
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
