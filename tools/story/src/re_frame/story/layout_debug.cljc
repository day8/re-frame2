(ns re-frame.story.layout-debug
  "Layout-debug overlay trio. Per IMPL-SPEC §2.8.6 + Stage 6 (rf2-zhwd).

  Three `:hiccup`-kind decorators for visual layout debugging during
  variant authoring. Each registers under `re-frame.story.registrar`
  via `reg-decorator*` so they participate in the standard decorator
  composition pipeline (no new registries — per the
  `downstream-EPs-consume-foundation` discipline).

  ## The three decorators

  - `:rf.story/layout-debug.measure` — Storybook-style rulers + gap
    visualisation on hover. Adds a `[:rf.story.layout-debug/measure]`
    wrapper div with `data-rf-story-measure` so the host stylesheet can
    light up rulers on `:hover`.

  - `:rf.story/layout-debug.outline` — Pesticide-style coloured outlines
    on every descendant element. The wrap injects an inline `<style>`
    tag that selects within the wrapper's class so the rule is scoped
    to the variant being rendered.

  - `:rf.story/layout-debug.pseudo` — Pseudo-state forcing. Adds a
    class to the wrapper that, via the injected stylesheet, applies
    `:hover` / `:focus` / `:active` / `:visited` styling to all
    descendants. Ref-args supply which states to force as a set; the
    default is `#{:hover}`.

  ## Authoring

  Add one or more to a variant's `:decorators`:

      (rf/reg-variant :story.button/pressed
        {:decorators [[:rf.story/layout-debug.outline]
                      [:rf.story/layout-debug.pseudo #{:hover :focus}]]
         :events     [...]})

  ## Bundle isolation

  Layout-debug is part of the Story bundle but DCE under `:advanced`
  with `:rf.story/enabled?` false (per IMPL-SPEC §6.2). The decorator
  bodies are plain data + a closure over inline CSS — Closure's
  reachability analysis removes the entire ns when `enabled?` is
  flipped off because nothing reachable from a `:advanced` build root
  calls `install-canonical-layout-debug!`."
  (:require [clojure.string           :as str]
            [re-frame.story.config    :as config]
            [re-frame.story.registrar :as registrar]))

;; ---- decorator ids -------------------------------------------------------

(def ^:const id-measure  :rf.story/layout-debug.measure)
(def ^:const id-outline  :rf.story/layout-debug.outline)
(def ^:const id-pseudo   :rf.story/layout-debug.pseudo)

(def canonical-decorator-ids
  "The three layout-debug decorator ids registered at Story boot."
  #{id-measure id-outline id-pseudo})

;; ---- pure: id-generation -------------------------------------------------
;;
;; Each rendered wrap needs a stable, unique class name so the injected
;; stylesheet can target only the descendants inside this wrap (not the
;; entire document). We synthesise one per call using a process-wide
;; counter; the counter is pure-data and JVM-testable.

(defonce ^:private wrap-counter (atom 0))

(defn next-wrap-id
  "Return a fresh wrap-id of the form `rf-story-debug-<n>`. Pure
  side-effect: bumps the counter and returns the new value's class
  string. JVM-testable."
  []
  (str "rf-story-debug-" (swap! wrap-counter inc)))

(defn reset-wrap-counter!
  "Reset the wrap counter. Test-fixture helper."
  []
  (reset! wrap-counter 0)
  nil)

;; ---- pure: stylesheet builders ------------------------------------------

(def ^:private outline-palette
  "Six distinct outline colours, rotated per descendant level. Matches
  Pesticide's classic palette. Pure data."
  ["#E74C3C" "#F39C12" "#F1C40F" "#2ECC71" "#3498DB" "#9B59B6"])

(defn build-outline-stylesheet
  "Return the CSS string for the outline decorator, scoped to
  `.<wrap-class>`. Every descendant element gets an outlined coloured
  border; colours rotate by descendant tag name's first letter so
  nesting is visually obvious. Pure data → data."
  [wrap-class]
  (str
    "." wrap-class " * { outline: 1px solid " (nth outline-palette 0) "; }"
    "." wrap-class " div  { outline-color: " (nth outline-palette 0) "; }"
    "." wrap-class " span { outline-color: " (nth outline-palette 1) "; }"
    "." wrap-class " p    { outline-color: " (nth outline-palette 2) "; }"
    "." wrap-class " a    { outline-color: " (nth outline-palette 3) "; }"
    "." wrap-class " button, ." wrap-class " input "
        "{ outline-color: " (nth outline-palette 4) "; }"
    "." wrap-class " ul, ." wrap-class " ol, ." wrap-class " li "
        "{ outline-color: " (nth outline-palette 5) "; }"))

(defn build-measure-stylesheet
  "Return the CSS string for the measure decorator. On hover over any
  descendant a 1px crosshair lights up around the element + a
  `::before` label shows the box dimensions. Pure data → data."
  [wrap-class]
  (str
    "." wrap-class " *:hover {"
    " box-shadow: 0 0 0 1px #00f8;"
    " position: relative;"
    "}"
    "." wrap-class " *:hover::before {"
    " content: 'measure';"
    " position: absolute;"
    " top: -14px; left: 0;"
    " padding: 0 4px;"
    " background: #00f;"
    " color: #fff;"
    " font: 10px/14px monospace;"
    " z-index: 9999;"
    " pointer-events: none;"
    "}"))

(defn build-pseudo-stylesheet
  "Return the CSS string for the pseudo-state decorator. Forces the
  named pseudo-classes on every descendant by class-swapping. Pure
  data → data.

  `states` is a set of pseudo-class keywords from
  `#{:hover :focus :active :visited}`."
  [wrap-class states]
  (let [parts (for [s states]
                (case s
                  :hover    (str "." wrap-class " * { outline: 1px dashed #ff0; }"
                                 "." wrap-class ".force-hover *:hover, "
                                 "." wrap-class ".force-hover * { /* forced hover */ }")
                  :focus    (str "." wrap-class ".force-focus * { outline: 2px solid #4af; }")
                  :active   (str "." wrap-class ".force-active * { filter: brightness(0.92); }")
                  :visited  (str "." wrap-class ".force-visited a { color: #b58fff; }")
                  ""))]
    (apply str parts)))

;; ---- pure: classes from forced-state set --------------------------------

(defn forced-state-classes
  "Given a set of pseudo-states, return the space-separated class
  string the wrap div needs. Pure data → data — JVM-testable."
  [states]
  (->> (sort-by name states)
       (map (fn [s] (str "force-" (name s))))
       (str/join " ")))

;; ---- decorator bodies (CLJC; the :wrap fn is host-portable) -------------

(defn- measure-wrap
  "Wrap body for `:hiccup` decorator. Sets `data-rf-story-measure` and
  injects the measure stylesheet scoped to a fresh class. The wrap is
  a single `[:div]` with an inline `<style>`; the variant render goes
  inside the div.

  `args` is the resolved args map; `:decorator/args` carries the ref
  args (the tail of `[:rf.story/layout-debug.measure & args]`). Per
  the standard decorator-args convention. Not currently used."
  [body _args]
  (let [wc (next-wrap-id)]
    [:div {:data-rf-story-measure true
           :class                 wc}
     [:style (build-measure-stylesheet wc)]
     body]))

(defn- outline-wrap
  [body _args]
  (let [wc (next-wrap-id)]
    [:div {:data-rf-story-outline true
           :class                 wc}
     [:style (build-outline-stylesheet wc)]
     body]))

(defn- pseudo-wrap
  "Wrap body for `:hiccup` decorator. Ref-args (first element after
  the decorator id) is a set of pseudo-state keywords; falls back to
  `#{:hover}` when absent."
  [body args]
  (let [decor-args (:decorator/args args)
        states     (or (first decor-args) #{:hover})
        states     (cond
                     (set? states)            states
                     (sequential? states)     (set states)
                     (keyword? states)        #{states}
                     :else                    #{:hover})
        wc         (next-wrap-id)
        cls        (str wc " " (forced-state-classes states))]
    [:div {:data-rf-story-pseudo true
           :class                cls}
     [:style (build-pseudo-stylesheet wc states)]
     body]))

;; ---- registration -------------------------------------------------------

(defn install-canonical-layout-debug!
  "Register the three layout-debug decorators. Idempotent. Called from
  `re-frame.story/install-canonical-vocabulary!` so a single boot call
  brings them online.

  Each decorator is `:kind :hiccup` — the `:wrap` fn is the one
  fn-valued slot Story permits at the decorator's registration site
  (per IMPL-SPEC §3.1).

  Production builds (`config/enabled?` false) skip registration — the
  decorator bodies never enter the registrar. Per IMPL-SPEC §6.2 the
  Story registrar itself is DCE'd under `:advanced` with `enabled?`
  off, so this gate is belt-and-braces."
  []
  (when config/enabled?
    (registrar/reg-decorator*
      id-measure
      {:doc  "Storybook-style hover rulers + box-dimension labels."
       :kind :hiccup
       :wrap measure-wrap})
    (registrar/reg-decorator*
      id-outline
      {:doc  "Pesticide-style coloured outlines on every descendant."
       :kind :hiccup
       :wrap outline-wrap})
    (registrar/reg-decorator*
      id-pseudo
      {:doc  (str "Pseudo-state forcing — ref-args is a set from "
                  "#{:hover :focus :active :visited}; default #{:hover}.")
       :kind :hiccup
       :wrap pseudo-wrap})
    nil))
