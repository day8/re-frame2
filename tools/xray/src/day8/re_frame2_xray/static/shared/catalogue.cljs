(ns day8.re-frame2-xray.static.shared.catalogue
  "Shared root + list + row chrome for Xray's Static flat-catalogue tabs.

  ## What it provides

  Three Static sub-tabs are flat, non-interactive catalogues — Flows,
  Interceptors, and Schemas. They each render the identical outer shell:

    - a `:section` root (fixed height, flex column, themed background);
    - a blank header spacer (`-header` testid — the L4 tab strip is the
      panel-name source of truth, so the header carries no heading);
    - the silent → search → no-match → list branching;
    - a `:ul` list container (`role=list`, fixed padding / overflow /
      flex layout, varying only on the inter-row `:gap`);
    - and, per row, the identical non-interactive `:li` chrome
      (`role=listitem`, block / mono / transparent style).

  `search-box` already owns the filtering algebra, the count-chip text,
  and the empty / no-match copy (see `static.shared.search-box`); this
  ns owns the remaining shell + row chrome so those three panels have a
  single owner rather than three copies.

  Each panel keeps its own **projection**, **haystacks**,
  **registrations**, **row keys**, and **domain row body** local — this
  ns is a pure presentational shell over that domain content.

  ## What is deliberately NOT here

  The interactive **Routes** browse list (rows toggle an expand surface)
  and the selectable **Machines** browse list (rows are a keyboard-
  navigable selection) are NOT flat catalogues — they carry row-level
  affordances this chrome does not model, so they keep their own shell.

  ## Frame-correctness + keys

  The interactive search box is frame-captured at each call-site and
  handed in as the `:search` slot — this ns never dispatches. Row React
  keys stay local too: the caller's `:row-render` fn attaches
  `^{:key …}` to its row form, so identity computation is domain-shaped
  and lives with the projection.

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup, no Reagent / UIx /
  references."
  (:require [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- shared style maps ---------------------------------------------------

(def ^:private root-style
  "The `:section` root chrome shared by every flat-catalogue tab."
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      (:body type-scale)})

(defn- list-style
  "The `:ul` list-container chrome. Every catalogue shares it; only the
  inter-row `gap` varies (Flows / Interceptors use `\"2px\"`, Schemas
  `\"4px\"`)."
  [gap]
  {:list-style     "none"
   :margin         "8px 0 0 0"
   :padding        "0 8px"
   :flex           1
   :overflow       "auto"
   :display        "flex"
   :flex-direction "column"
   :gap            gap})

(def ^:private row-style
  "The identical non-interactive `:li` chrome shared by every catalogue
  row (Flows / Interceptors / Schemas)."
  {:display       "block"
   :padding       "6px 12px"
   :font-family   mono-stack
   :font-size     "12px"
   :color         (:text-primary tokens)
   :background    "transparent"
   :border-left   "2px solid transparent"
   :border-radius "2px"
   :line-height   "18px"})

;; ---- row chrome ----------------------------------------------------------

(defn catalogue-row
  "The identical non-interactive `li` chrome shared by the flat-catalogue
  Static tabs (Flows / Interceptors / Schemas). Owns the listitem wrapper
  — `data-testid`, `role=listitem`, and the shared block / mono /
  transparent style — and splices the caller's domain-specific row body
  `children` in order.

  Carries no React key: the caller attaches `^{:key …}` to the row form
  it hands `catalogue-panel` as `:row-render`, so row identity stays
  local + domain-shaped.

  `opts`:
    :testid — the full row `data-testid` (e.g.
              `\"rf-xray-static-flows-row-user/full-name\"`).

  Pure hiccup — no Reagent / UIx references."
  [{:keys [testid]} & children]
  (into [:li {:data-testid testid
              :role        "listitem"
              :style       row-style}]
        children))

;; ---- root + list chrome --------------------------------------------------

(defn catalogue-panel
  "Shared shell for a flat, non-interactive Static catalogue tab
  (Flows / Interceptors / Schemas). Owns the `:section` root, the blank
  header spacer, the silent / no-match / list branching, the search slot,
  and the `:ul` list container. Domain code keeps its projection,
  haystacks, registrations, row keys, and row body local.

  `opts`:
    :testid     — `data-testid` stem (e.g. `\"rf-xray-static-flows\"`).
                  The section uses it bare; the header / list suffix
                  `-header` / `-list`; empty states suffix `-empty` /
                  `-empty-filtered`.
    :noun       — singular count / empty-state noun (`\"flow\"` /
                  `\"interceptor\"` / `\"schema\"`).
    :query      — current search query (for the no-match copy).
    :silent?    — true when the catalogue is cold-empty (no rows before
                  filtering) → renders the empty-state, no search box.
    :rows       — the post-filter row seq. Empty with `:silent? false`
                  renders the no-match surface.
    :search     — the caller's interactive search-box hiccup (frame-
                  captured at the call-site; stays local).
    :row-render — fn `row → keyed row hiccup` (typically wrapping
                  `catalogue-row`); the caller attaches the React key so
                  identity stays local.
    :gap        — inter-row flex gap (`\"2px\"` default; Schemas `\"4px\"`).

  Pure hiccup — no Reagent / UIx references."
  [{:keys [testid noun query silent? rows search row-render gap]
    :or   {gap "2px"}}]
  [:section {:data-testid testid
             :style       root-style}
   [:div {:data-testid (str testid "-header")
          :style       {:padding "4px 16px"}}]
   (cond
     silent?
     (search-box/empty-state testid noun)

     :else
     [:<>
      search
      (if (empty? rows)
        (search-box/empty-filtered testid noun query)
        (into [:ul {:data-testid (str testid "-list")
                    :role        "list"
                    :style       (list-style gap)}]
              (map row-render rows)))])])
