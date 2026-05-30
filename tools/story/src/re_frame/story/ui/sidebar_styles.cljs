(ns re-frame.story.ui.sidebar-styles
  "Style map for `re-frame.story.ui.sidebar`. Pure data; no Reagent
  dependency. Extracted from `sidebar.cljs` per rf2-gv5kq so the
  sidebar ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only."
  (:require [re-frame.story.theme.typography :as typography :refer [mono-stack sans-stack]]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.motion :as motion]
            [re-frame.story.theme.status :as status]))

(def styles
  {:wrap         {:width "260px"
                  :background (:bg-1 colors/tokens)
                  :color (:text-primary colors/tokens)
                  :font-family sans-stack
                  :font-size (:body-tight typography/type-scale)
                  :border-right (str "1px solid " (:border-default colors/tokens))
                  :overflow "auto"
                  :padding "8px 0 0 0"
                  :display "flex"
                  :flex-direction "column"}
   :tree         {:flex "1"
                  :overflow "auto"
                  :display "flex"
                  :flex-direction "column"
                  :padding-bottom "8px"}
   ;; rf2-p0wur — the sidebar header reads as Story's "Stories" lens
   ;; label, parity with the RHS section headers (rf2-8rvu4 §rhs-section-h
   ;; in shell-styles). Uppercase + wide tracking + nano scale + a
   ;; subtle amber underline so the sidebar matches the workshop
   ;; chrome's section-label vocabulary.
   :header       {:padding "10px 12px 8px 12px"
                  :font-family sans-stack
                  :font-weight (str (:semibold typography/weights))
                  :font-size (:nano typography/type-scale)
                  :text-transform "uppercase"
                  :letter-spacing (:label-wide typography/letter-spacing)
                  :color (:accent-amber colors/tokens)
                  :border-bottom (str "1px solid " (:border-subtle colors/tokens))
                  :box-shadow (str "0 1px 0 " (:accent-amber-soft colors/tokens))
                  :margin-bottom "6px"
                  :display "flex"
                  :align-items "center"
                  :gap "8px"}
   :section      {:padding "16px 0 6px 12px"
                  :margin-top "4px"
                  :border-top (str "1px solid " (:border-subtle colors/tokens))
                  :font-family sans-stack
                  :font-weight (str (:semibold typography/weights))
                  :color (:text-tertiary colors/tokens)
                  :text-transform "uppercase"
                  :font-size (:nano typography/type-scale)
                  :letter-spacing (:label-wide typography/letter-spacing)
                  :display "flex"
                  :align-items "center"}
   :tag-row      {:display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :padding "8px 12px 10px 12px"
                  :margin-bottom "6px"
                  :border-bottom (str "1px solid " (:border-subtle colors/tokens))}
   ;; rf2-7ncf9 — faceted tag-filter: one labelled chip row per axis.
   :axis-row     {:display "flex"
                  :flex-direction "column"
                  :gap "3px"}
   :axis-label   {:font-size (:nano typography/type-scale)
                  :color (:text-tertiary colors/tokens)
                  :letter-spacing "0.5px"
                  :text-transform "uppercase"
                  :font-weight "bold"}
   :axis-chips   {:display "flex"
                  :flex-wrap "wrap"
                  :gap "4px"}
   :tag          {:padding "2px 6px"
                  :background (:bg-3 colors/tokens)
                  :color (:text-primary colors/tokens)
                  :border-radius "10px"
                  :cursor "pointer"
                  :font-size (:micro typography/type-scale)
                  :user-select "none"
                  :transition (:chip motion/transitions)}
   :tag-active   {:background (:accent-amber colors/tokens)
                  :color (:text-on-accent colors/tokens)}
   ;; rf2-p0wur — top-level story rows + a generous inter-block gap so
   ;; the sidebar tree breathes. Story rows lead with the diamond
   ;; glyph (`re-frame.story.theme.glyphs/story-glyph`) — the sidebar
   ;; component renders the glyph inline; the glyph itself wears the
   ;; amber accent so each story's parent row reads as a labelled
   ;; chapter heading.
   :story-block  {:margin-bottom "12px"
                  :padding-bottom "2px"}
   :story-row    {:padding "6px 12px 6px 10px"
                  :font-family sans-stack
                  :color (:text-primary colors/tokens)
                  :font-weight (str (:semibold typography/weights))
                  :font-size (:body-tight typography/type-scale)
                  :letter-spacing "0.01em"
                  ;; rf2-8j7wg — story-row is now click-activated (opens
                  ;; the rollup docs page). The cursor flips from
                  ;; `default` to `pointer` so the affordance is obvious.
                  :cursor "pointer"
                  :display "flex"
                  :align-items "center"
                  :gap "8px"
                  :border-left (str "2px solid transparent")}
   :story-row-active {:background (:bg-active colors/tokens)
                      :color (:accent-amber colors/tokens)
                      :border-left (str "2px solid " (:accent-amber colors/tokens))}
   :story-glyph  {:flex-shrink "0"
                  :display "inline-flex"
                  :align-items "center"
                  :color (:accent-amber colors/tokens)}
   :variant-row  {:padding "3px 12px 3px 26px"
                  :cursor "pointer"
                  :color (:text-secondary colors/tokens)
                  :font-family mono-stack
                  :display "flex"
                  :align-items "center"
                  :gap "8px"
                  :border-left (str "2px solid transparent")
                  :transition (:row motion/transitions)}
   :variant-row-active {:background (:bg-active colors/tokens)
                        :color (:accent-amber colors/tokens)
                        :font-weight (str (:medium typography/weights))
                        :border-left (str "2px solid " (:accent-amber colors/tokens))}
   :variant-glyph {:flex-shrink "0"
                   :display "inline-flex"
                   :align-items "center"
                   :justify-content "center"
                   :width "10px"
                   :height "10px"
                   :opacity 0.55
                   :color (:text-tertiary colors/tokens)}
   :workspace-row {:padding "3px 12px 3px 14px"
                   :cursor "pointer"
                   :color (:text-secondary colors/tokens)
                   :font-family mono-stack
                   :display "flex"
                   :align-items "center"
                   :gap "8px"
                   :border-left (str "2px solid transparent")
                   :transition (:row motion/transitions)}
   :workspace-row-active {:background (:bg-active colors/tokens)
                          :color (:accent-amber colors/tokens)
                          :font-weight (str (:medium typography/weights))
                          :border-left (str "2px solid " (:accent-amber colors/tokens))}
   :workspace-glyph {:flex-shrink "0"
                     :display "inline-flex"
                     :align-items "center"
                     :color (:info colors/tokens)
                     :opacity 0.75}
   :empty        {:color (:text-tertiary colors/tokens)
                  :font-style "italic"
                  :padding "8px 12px"}
   ;; rf2-q0irb — status dot + chrome-level test widget. The dot tints
   ;; derive from the SHARED status vocabulary (rf2-ba86n.3 —
   ;; `theme.status`) so a `:pass` dot is the same green as a `:pass`
   ;; signal chip / result pill. Pending shows an empty ring (the
   ;; reserved-but-unrun slot).
   :dot          {:width "8px"
                  :height "8px"
                  :border-radius "50%"
                  :flex-shrink "0"
                  :display "inline-block"}
   :dot-pass     {:background (status/fg :pass)}
   :dot-fail     {:background (status/fg :fail)}
   :dot-running  {:background (status/fg :running)
                  :opacity "0.7"}
   :dot-pending  {:background "transparent"
                  :border (str "1px solid " (:border-strong colors/tokens))}
   :widget       {:border-top (str "1px solid " (:border-default colors/tokens))
                  :margin-top "auto"
                  :padding "10px 12px"
                  :display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :background (:bg-1 colors/tokens)}
   :widget-h     {:font-weight "bold"
                  :color (:text-secondary colors/tokens)
                  :text-transform "uppercase"
                  :font-size (:micro typography/type-scale)
                  :letter-spacing "0.5px"}
   :widget-counts {:display "flex"
                   :flex-wrap "wrap"
                   :gap "8px"
                   :font-family mono-stack
                   :font-size (:caption typography/type-scale)
                   :color (:text-primary colors/tokens)}
   :widget-pass  {:color (:success colors/tokens)}
   :widget-fail  {:color (:danger colors/tokens)}
   :widget-run   {:color (:warning colors/tokens)}
   :widget-pend  {:color (:text-tertiary colors/tokens)}
   :widget-btn   {:margin-top "4px"
                  :padding "4px 10px"
                  :background (:accent-amber colors/tokens)
                  :color (:text-on-accent colors/tokens)
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family mono-stack
                  :font-size (:caption typography/type-scale)}
   :widget-btn-disabled {:background (:bg-3 colors/tokens)
                         :color (:text-tertiary colors/tokens)
                         :cursor "not-allowed"}
   :widget-empty {:color (:text-tertiary colors/tokens)
                  :font-style "italic"
                  :font-size (:micro typography/type-scale)}
   ;; rf2-z1h0f — watch-mode eye-icon toggle on the chrome widget.
   :watch-row    {:display     "flex"
                  :align-items "center"
                  :gap         "8px"
                  :margin-top  "2px"}
   :watch-btn    {:padding         "2px 8px"
                  :background      "transparent"
                  :color           (:text-tertiary colors/tokens)
                  :border          (str "1px solid " (:border-default colors/tokens))
                  :border-radius   "10px"
                  :cursor          "pointer"
                  :font-family     mono-stack
                  :font-size       (:micro typography/type-scale)
                  :letter-spacing  "0.3px"
                  :display         "inline-flex"
                  :align-items     "center"
                  :gap             "4px"}
   :watch-btn-on {:background (:success-bg colors/tokens)
                  :color      (:success colors/tokens)
                  :border     (str "1px solid " (:success colors/tokens))}
   ;; rf2-nwiwr — tag-as-badge affordance on variant rows.
   :tag-badges   {:display     "inline-flex"
                  :flex-wrap   "wrap"
                  :gap         "3px"
                  :margin-left "4px"}
   :tag-badge    {:padding       "0 5px"
                  :background    (:bg-3 colors/tokens)
                  :color         (:text-primary colors/tokens)
                  :border-radius "8px"
                  :font-family   mono-stack
                  :font-size     (:nano typography/type-scale)
                  :line-height   "14px"
                  :user-select   "none"
                  :flex-shrink   "0"}
   ;; Per-tag palette — keys on the canonical seven from
   ;; spec/007 §Inclusion tags; unknown tags fall through to
   ;; the neutral :tag-badge above.
   :tag-badge-dev          {:background (:tag-dev-bg colors/tokens) :color (:info colors/tokens)}
   :tag-badge-docs         {:background (:tag-docs-bg colors/tokens) :color (:tag-docs-fg colors/tokens)}
   :tag-badge-test         {:background (:success-bg colors/tokens) :color (:success colors/tokens)}
   :tag-badge-screenshot   {:background (:warning-bg colors/tokens) :color (:warning colors/tokens)}
   :tag-badge-experimental {:background (:tag-experimental-bg colors/tokens) :color (:tag-experimental-fg colors/tokens)}
   :tag-badge-internal     {:background (:tag-internal-bg colors/tokens) :color (:danger colors/tokens)}
   :tag-badge-agent        {:background (:tag-agent-bg colors/tokens) :color (:success colors/tokens)}
   ;; rf2-yngai — search-as-you-type input row + amber-tint highlight.
   :search-row     {:padding "0 12px 8px 12px"
                    :display "flex"
                    :align-items "center"
                    :gap "6px"
                    :border-bottom (str "1px solid " (:border-subtle colors/tokens))
                    :margin-bottom "6px"
                    :position "relative"}
   :search-input   {:width "100%"
                    :box-sizing "border-box"
                    :background (:bg-input colors/tokens)
                    :color (:text-primary colors/tokens)
                    :border (str "1px solid " (:border-subtle colors/tokens))
                    :border-radius "4px"
                    :font-family mono-stack
                    :font-size (:caption typography/type-scale)
                    :padding "5px 24px 5px 8px"
                    :outline "none"
                    :transition (:chip motion/transitions)}
   :search-clear   {:position "absolute"
                    :right "18px"
                    :top "50%"
                    :transform "translateY(-50%)"
                    :background "transparent"
                    :border "none"
                    :color (:text-tertiary colors/tokens)
                    :cursor "pointer"
                    :padding "0 4px"
                    :font-family mono-stack
                    :font-size (:caption typography/type-scale)
                    :line-height "1"}
   :search-hit     {:background (:accent-amber-soft colors/tokens)
                    :color (:accent-amber colors/tokens)
                    :border-radius "2px"
                    :padding "0 1px"}
   ;; rf2-ba86n.4 — per-variant SIGNAL CHIPS (spec/018 §7.1 + §12.6). Five
   ;; DISTINCT axes — status / fidelity / world-inputs / runner-requirement
   ;; / frame-binding — rendered as adjacent-but-separate chip groups. The
   ;; per-axis tints keep the axes visually distinguishable so the labels
   ;; are NEVER read as one collapsed 'fidelity' concept. Chips sit on the
   ;; row below the variant id (a second, dense line) so a long signal set
   ;; never overflows the variant row itself (spec/018 §10 — text MUST NOT
   ;; overflow rows / chips).
   :signal-row      {:display      "flex"
                     :flex-wrap    "wrap"
                     :align-items  "center"
                     :gap          "4px"
                     :padding      "1px 12px 3px 26px"
                     :margin-top   "-1px"}
   :signal-group    {:display      "inline-flex"
                     :flex-wrap    "wrap"
                     :align-items  "center"
                     :gap          "2px"}
   ;; The base chip — each axis layers its tint on top via the per-axis /
   ;; per-status maps below.
   :signal-chip     {:padding       "0 5px"
                     :border-radius "8px"
                     :font-family   mono-stack
                     :font-size     (:nano typography/type-scale)
                     :line-height   "14px"
                     :letter-spacing "0.2px"
                     :user-select   "none"
                     :flex-shrink   "0"
                     :background    (:bg-3 colors/tokens)
                     :color         (:text-secondary colors/tokens)}
   ;; rf2-ba86n.3 / rf2-gsqbp — the status GLYPH channel (spec/018 §12.6).
   ;; Status chips lead with the descriptor's structural glyph (✓ ✗ ! …)
   ;; so the status survives colour-blindness AND Windows HCM, where
   ;; `forced-colors` strips the chip tint and the colour channel is gone.
   ;; A hair of right-margin separates the mark from the text label; it
   ;; inherits the chip's `:color` so the glyph rides the same hue as the
   ;; status (and goes mono in forced-colors, where SHAPE + glyph carry it).
   :signal-chip-glyph {:margin-right "3px"
                       :font-weight  "600"}
   ;; ── status axis (spec/018 §12.6 — distinguishable in colour, icon,
   ;;    text, and shape; NOT everything red/green) ──
   ;;
   ;; rf2-ba86n.3: the per-status tints now DERIVE from the shared status
   ;; vocabulary (`theme.status/chip-style`) rather than being respecified
   ;; here. This is the single-source-of-truth move — a `:fail` chip in
   ;; the sidebar, a `:fail` pill in test mode, and a `:fail` evidence
   ;; beat all resolve to the same colour + shape because they share one
   ;; constructor. The shape discriminator (outline / dashed / ring)
   ;; rides through `chip-style` so cannot-run / error / pending /
   ;; redacted stay distinguishable beyond hue.
   :signal-status-pass       (status/chip-style :pass)
   :signal-status-fail       (status/chip-style :fail)
   :signal-status-cannot-run (status/chip-style :cannot-run)
   :signal-status-error      (status/chip-style :error)
   :signal-status-running    (status/chip-style :running)
   :signal-status-pending    (status/chip-style :pending)
   :signal-status-blocked    (status/chip-style :blocked)
   :signal-status-dirty      (status/chip-style :dirty)
   :signal-status-redacted   (status/chip-style :redacted)
   ;; ── fidelity axis — purple-violet tint (shared with the :docs tag
   ;;    palette) so it reads as its own family, never as a world input ──
   :signal-fidelity {:background (:tag-docs-bg colors/tokens)
                     :color (:tag-docs-fg colors/tokens)}
   ;; ── world-inputs axis — info-blue tint (distinct from fidelity) ──
   :signal-world    {:background (:info-bg colors/tokens)
                     :color (:info colors/tokens)}
   ;; ── runner-requirement axis — teal tint (shared with the :agent tag
   ;;    palette) so a capability requirement never reads as a tier of
   ;;    fidelity ──
   :signal-runner   {:background (:tag-agent-bg colors/tokens)
                     :color (:tag-agent-fg colors/tokens)}
   ;; ── frame-binding axis — neutral mono tint; an attached / MCP-bound
   ;;    binding is a binding, not a runner tier (spec/018 §7.2) ──
   :signal-frame    {:background (:bg-3 colors/tokens)
                     :color (:text-secondary colors/tokens)
                     :border (str "1px solid " (:border-default colors/tokens))}
   :signal-frame-attached  {:background (:tag-experimental-bg colors/tokens)
                            :color (:tag-experimental-fg colors/tokens)
                            :border (str "1px solid " (:tag-experimental-fg colors/tokens))}
   ;; rf2-ba86n.4 — large-list virtualization / bounding (spec/018 §10 —
   ;; cap or page; the UI SHOULD fail by summarizing, not flooding). The
   ;; "+N more" affordance row a story-block renders when its variant count
   ;; exceeds the per-story cap.
   :variant-more    {:padding "2px 12px 4px 26px"
                     :color (:text-tertiary colors/tokens)
                     :font-family mono-stack
                     :font-size (:micro typography/type-scale)
                     :font-style "italic"
                     :cursor "pointer"
                     :user-select "none"}
   ;; rf2-ba86n.4 — variants-grid grouping affordance (spec/018 §7.1 —
   ;; 'visible grouping for :variants-grid generated variants'). A compact
   ;; sub-header above a generated grid's variant rows so the grid reads as
   ;; one scannable group rather than loose siblings.
   :grid-group      {:padding "2px 12px 2px 22px"
                     :margin-top "2px"
                     :display "flex"
                     :align-items "center"
                     :gap "6px"
                     :color (:text-tertiary colors/tokens)
                     :font-family sans-stack
                     :font-size (:nano typography/type-scale)
                     :text-transform "uppercase"
                     :letter-spacing (:label-wide typography/letter-spacing)}
   :grid-group-count {:color (:accent-amber colors/tokens)
                      :font-family mono-stack}})
