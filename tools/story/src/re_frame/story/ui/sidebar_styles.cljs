(ns re-frame.story.ui.sidebar-styles
  "Style map for `re-frame.story.ui.sidebar`. Pure data; no Reagent
  dependency. Extracted from `sidebar.cljs` per rf2-gv5kq so the
  sidebar ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only."
  (:require [re-frame.story.theme.typography :as rf.story.theme.typography :refer [mono-stack sans-stack]]
            [re-frame.story.theme.colors :as rf.story.theme.colors]
            [re-frame.story.theme.motion :as rf.story.theme.motion]
            [re-frame.story.theme.status :as rf.story.theme.status]))

(def styles
  {:wrap         {:width "260px"
                  :background (:bg-1 rf.story.theme.colors/tokens)
                  :color (:text-primary rf.story.theme.colors/tokens)
                  :font-family sans-stack
                  :font-size (:body-tight rf.story.theme.typography/type-scale)
                  :border-right (str "1px solid " (:border-default rf.story.theme.colors/tokens))
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
                  :font-weight (str (:semibold rf.story.theme.typography/weights))
                  :font-size (:nano rf.story.theme.typography/type-scale)
                  :text-transform "uppercase"
                  :letter-spacing (:label-wide rf.story.theme.typography/letter-spacing)
                  :color (:accent-amber rf.story.theme.colors/tokens)
                  :border-bottom (str "1px solid " (:border-subtle rf.story.theme.colors/tokens))
                  :box-shadow (str "0 1px 0 " (:accent-amber-soft rf.story.theme.colors/tokens))
                  :margin-bottom "6px"
                  :display "flex"
                  :align-items "center"
                  :gap "8px"}
   :section      {:padding "16px 0 6px 12px"
                  :margin-top "4px"
                  :border-top (str "1px solid " (:border-subtle rf.story.theme.colors/tokens))
                  :font-family sans-stack
                  :font-weight (str (:semibold rf.story.theme.typography/weights))
                  :color (:text-tertiary rf.story.theme.colors/tokens)
                  :text-transform "uppercase"
                  :font-size (:nano rf.story.theme.typography/type-scale)
                  :letter-spacing (:label-wide rf.story.theme.typography/letter-spacing)
                  :display "flex"
                  :align-items "center"}
   :tag-row      {:display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :padding "8px 12px 10px 12px"
                  :margin-bottom "6px"
                  :border-bottom (str "1px solid " (:border-subtle rf.story.theme.colors/tokens))}
   ;; rf2-7ncf9 — faceted tag-filter: one labelled chip row per axis.
   :axis-row     {:display "flex"
                  :flex-direction "column"
                  :gap "3px"}
   :axis-label   {:font-size (:nano rf.story.theme.typography/type-scale)
                  :color (:text-tertiary rf.story.theme.colors/tokens)
                  :letter-spacing "0.5px"
                  :text-transform "uppercase"
                  :font-weight "bold"}
   :axis-chips   {:display "flex"
                  :flex-wrap "wrap"
                  :gap "4px"}
   :tag          {:padding "2px 6px"
                  :background (:bg-3 rf.story.theme.colors/tokens)
                  :color (:text-primary rf.story.theme.colors/tokens)
                  :border-radius "10px"
                  :cursor "pointer"
                  :font-size (:micro rf.story.theme.typography/type-scale)
                  :user-select "none"
                  :transition (:chip rf.story.theme.motion/transitions)}
   :tag-active   {:background (:accent-amber rf.story.theme.colors/tokens)
                  :color (:text-on-accent rf.story.theme.colors/tokens)}
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
                  :color (:text-primary rf.story.theme.colors/tokens)
                  :font-weight (str (:semibold rf.story.theme.typography/weights))
                  :font-size (:body-tight rf.story.theme.typography/type-scale)
                  :letter-spacing "0.01em"
                  ;; rf2-8j7wg — story-row is now click-activated (opens
                  ;; the rollup docs page). The cursor flips from
                  ;; `default` to `pointer` so the affordance is obvious.
                  :cursor "pointer"
                  :display "flex"
                  :align-items "center"
                  :gap "8px"
                  :border-left (str "2px solid transparent")}
   :story-row-active {:background (:bg-active rf.story.theme.colors/tokens)
                      :color (:accent-amber rf.story.theme.colors/tokens)
                      :border-left (str "2px solid " (:accent-amber rf.story.theme.colors/tokens))}
   :story-glyph  {:flex-shrink "0"
                  :display "inline-flex"
                  :align-items "center"
                  :color (:accent-amber rf.story.theme.colors/tokens)}
   :variant-row  {:padding "3px 12px 3px 26px"
                  :cursor "pointer"
                  :color (:text-secondary rf.story.theme.colors/tokens)
                  :font-family mono-stack
                  :display "flex"
                  :align-items "center"
                  :gap "8px"
                  :border-left (str "2px solid transparent")
                  :transition (:row rf.story.theme.motion/transitions)}
   :variant-row-active {:background (:bg-active rf.story.theme.colors/tokens)
                        :color (:accent-amber rf.story.theme.colors/tokens)
                        :font-weight (str (:medium rf.story.theme.typography/weights))
                        :border-left (str "2px solid " (:accent-amber rf.story.theme.colors/tokens))}
   :variant-glyph {:flex-shrink "0"
                   :display "inline-flex"
                   :align-items "center"
                   :justify-content "center"
                   :width "10px"
                   :height "10px"
                   :opacity 0.55
                   :color (:text-tertiary rf.story.theme.colors/tokens)}
   :workspace-row {:padding "3px 12px 3px 14px"
                   :cursor "pointer"
                   :color (:text-secondary rf.story.theme.colors/tokens)
                   :font-family mono-stack
                   :display "flex"
                   :align-items "center"
                   :gap "8px"
                   :border-left (str "2px solid transparent")
                   :transition (:row rf.story.theme.motion/transitions)}
   :workspace-row-active {:background (:bg-active rf.story.theme.colors/tokens)
                          :color (:accent-amber rf.story.theme.colors/tokens)
                          :font-weight (str (:medium rf.story.theme.typography/weights))
                          :border-left (str "2px solid " (:accent-amber rf.story.theme.colors/tokens))}
   :workspace-glyph {:flex-shrink "0"
                     :display "inline-flex"
                     :align-items "center"
                     :color (:info rf.story.theme.colors/tokens)
                     :opacity 0.75}
   :empty        {:color (:text-tertiary rf.story.theme.colors/tokens)
                  :font-style "italic"
                  :padding "8px 12px"}
   ;; rf2-q0irb — status dot + chrome-level test widget. Only the dot's
   ;; GEOMETRY lives here; the per-status paint projects from the
   ;; canonical `theme.status` descriptors in `sidebar/dot-style`
   ;; (rf2-ba86n.3 / rf2-wh5to — one source, no duplicate status maps),
   ;; so a `:pass` dot is the same green as a `:pass` signal chip /
   ;; result pill and `:cannot-run` stays distinct from `:pending`.
   :dot          {:width "8px"
                  :height "8px"
                  :border-radius "50%"
                  :flex-shrink "0"
                  :display "inline-block"}
   :widget       {:border-top (str "1px solid " (:border-default rf.story.theme.colors/tokens))
                  :margin-top "auto"
                  :padding "10px 12px"
                  :display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :background (:bg-1 rf.story.theme.colors/tokens)}
   :widget-h     {:font-weight "bold"
                  :color (:text-secondary rf.story.theme.colors/tokens)
                  :text-transform "uppercase"
                  :font-size (:micro rf.story.theme.typography/type-scale)
                  :letter-spacing "0.5px"}
   :widget-counts {:display "flex"
                   :flex-wrap "wrap"
                   :gap "8px"
                   :font-family mono-stack
                   :font-size (:caption rf.story.theme.typography/type-scale)
                   :color (:text-primary rf.story.theme.colors/tokens)}
   :widget-pass  {:color (:success rf.story.theme.colors/tokens)}
   :widget-fail  {:color (:danger rf.story.theme.colors/tokens)}
   :widget-run   {:color (:warning rf.story.theme.colors/tokens)}
   :widget-pend  {:color (:text-tertiary rf.story.theme.colors/tokens)}
   :widget-btn   {:margin-top "4px"
                  :padding "4px 10px"
                  :background (:accent-amber rf.story.theme.colors/tokens)
                  :color (:text-on-accent rf.story.theme.colors/tokens)
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family mono-stack
                  :font-size (:caption rf.story.theme.typography/type-scale)}
   :widget-btn-disabled {:background (:bg-3 rf.story.theme.colors/tokens)
                         :color (:text-tertiary rf.story.theme.colors/tokens)
                         :cursor "not-allowed"}
   :widget-empty {:color (:text-tertiary rf.story.theme.colors/tokens)
                  :font-style "italic"
                  :font-size (:micro rf.story.theme.typography/type-scale)}
   ;; rf2-z1h0f — watch-mode eye-icon toggle on the chrome widget.
   :watch-row    {:display     "flex"
                  :align-items "center"
                  :gap         "8px"
                  :margin-top  "2px"}
   :watch-btn    {:padding         "2px 8px"
                  :background      "transparent"
                  :color           (:text-tertiary rf.story.theme.colors/tokens)
                  :border          (str "1px solid " (:border-default rf.story.theme.colors/tokens))
                  :border-radius   "10px"
                  :cursor          "pointer"
                  :font-family     mono-stack
                  :font-size       (:micro rf.story.theme.typography/type-scale)
                  :letter-spacing  "0.3px"
                  :display         "inline-flex"
                  :align-items     "center"
                  :gap             "4px"}
   :watch-btn-on {:background (:success-bg rf.story.theme.colors/tokens)
                  :color      (:success rf.story.theme.colors/tokens)
                  :border     (str "1px solid " (:success rf.story.theme.colors/tokens))}
   ;; rf2-nwiwr — tag-as-badge affordance on variant rows.
   :tag-badges   {:display     "inline-flex"
                  :flex-wrap   "wrap"
                  :gap         "3px"
                  :margin-left "4px"}
   :tag-badge    {:padding       "0 5px"
                  :background    (:bg-3 rf.story.theme.colors/tokens)
                  :color         (:text-primary rf.story.theme.colors/tokens)
                  :border-radius "8px"
                  :font-family   mono-stack
                  :font-size     (:nano rf.story.theme.typography/type-scale)
                  :line-height   "14px"
                  :user-select   "none"
                  :flex-shrink   "0"}
   ;; Per-tag palette — keys on the canonical seven from
   ;; /spec/007-Stories.md §Inclusion tags; unknown tags fall through to
   ;; the neutral :tag-badge above.
   :tag-badge-dev          {:background (:tag-dev-bg rf.story.theme.colors/tokens) :color (:info rf.story.theme.colors/tokens)}
   :tag-badge-docs         {:background (:tag-docs-bg rf.story.theme.colors/tokens) :color (:tag-docs-fg rf.story.theme.colors/tokens)}
   :tag-badge-test         {:background (:success-bg rf.story.theme.colors/tokens) :color (:success rf.story.theme.colors/tokens)}
   :tag-badge-screenshot   {:background (:warning-bg rf.story.theme.colors/tokens) :color (:warning rf.story.theme.colors/tokens)}
   :tag-badge-experimental {:background (:tag-experimental-bg rf.story.theme.colors/tokens) :color (:tag-experimental-fg rf.story.theme.colors/tokens)}
   :tag-badge-internal     {:background (:tag-internal-bg rf.story.theme.colors/tokens) :color (:danger rf.story.theme.colors/tokens)}
   :tag-badge-agent        {:background (:tag-agent-bg rf.story.theme.colors/tokens) :color (:success rf.story.theme.colors/tokens)}
   ;; rf2-yngai — search-as-you-type input row + amber-tint highlight.
   :search-row     {:padding "0 12px 8px 12px"
                    :display "flex"
                    :align-items "center"
                    :gap "6px"
                    :border-bottom (str "1px solid " (:border-subtle rf.story.theme.colors/tokens))
                    :margin-bottom "6px"
                    :position "relative"}
   :search-input   {:width "100%"
                    :box-sizing "border-box"
                    :background (:bg-input rf.story.theme.colors/tokens)
                    :color (:text-primary rf.story.theme.colors/tokens)
                    :border (str "1px solid " (:border-subtle rf.story.theme.colors/tokens))
                    :border-radius "4px"
                    :font-family mono-stack
                    :font-size (:caption rf.story.theme.typography/type-scale)
                    :padding "5px 24px 5px 8px"
                    :outline "none"
                    :transition (:chip rf.story.theme.motion/transitions)}
   :search-clear   {:position "absolute"
                    :right "18px"
                    :top "50%"
                    :transform "translateY(-50%)"
                    :background "transparent"
                    :border "none"
                    :color (:text-tertiary rf.story.theme.colors/tokens)
                    :cursor "pointer"
                    :padding "0 4px"
                    :font-family mono-stack
                    :font-size (:caption rf.story.theme.typography/type-scale)
                    :line-height "1"}
   :search-hit     {:background (:accent-amber-soft rf.story.theme.colors/tokens)
                    :color (:accent-amber rf.story.theme.colors/tokens)
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
                     :font-size     (:nano rf.story.theme.typography/type-scale)
                     :line-height   "14px"
                     :letter-spacing "0.2px"
                     :user-select   "none"
                     :flex-shrink   "0"
                     :background    (:bg-3 rf.story.theme.colors/tokens)
                     :color         (:text-secondary rf.story.theme.colors/tokens)}
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
   :signal-status-pass       (rf.story.theme.status/chip-style :pass)
   :signal-status-fail       (rf.story.theme.status/chip-style :fail)
   :signal-status-cannot-run (rf.story.theme.status/chip-style :cannot-run)
   :signal-status-error      (rf.story.theme.status/chip-style :error)
   :signal-status-running    (rf.story.theme.status/chip-style :running)
   :signal-status-pending    (rf.story.theme.status/chip-style :pending)
   :signal-status-blocked    (rf.story.theme.status/chip-style :blocked)
   :signal-status-dirty      (rf.story.theme.status/chip-style :dirty)
   :signal-status-redacted   (rf.story.theme.status/chip-style :redacted)
   ;; ── fidelity axis — purple-violet tint (shared with the :docs tag
   ;;    palette) so it reads as its own family, never as a world input ──
   :signal-fidelity {:background (:tag-docs-bg rf.story.theme.colors/tokens)
                     :color (:tag-docs-fg rf.story.theme.colors/tokens)}
   ;; ── world-inputs axis — info-blue tint (distinct from fidelity) ──
   :signal-world    {:background (:info-bg rf.story.theme.colors/tokens)
                     :color (:info rf.story.theme.colors/tokens)}
   ;; ── runner-requirement axis — teal tint (shared with the :agent tag
   ;;    palette) so a capability requirement never reads as a tier of
   ;;    fidelity ──
   :signal-runner   {:background (:tag-agent-bg rf.story.theme.colors/tokens)
                     :color (:tag-agent-fg rf.story.theme.colors/tokens)}
   ;; ── frame-binding axis — neutral mono tint; an attached / MCP-bound
   ;;    binding is a binding, not a runner tier (spec/018 §7.2) ──
   :signal-frame    {:background (:bg-3 rf.story.theme.colors/tokens)
                     :color (:text-secondary rf.story.theme.colors/tokens)
                     :border (str "1px solid " (:border-default rf.story.theme.colors/tokens))}
   :signal-frame-attached  {:background (:tag-experimental-bg rf.story.theme.colors/tokens)
                            :color (:tag-experimental-fg rf.story.theme.colors/tokens)
                            :border (str "1px solid " (:tag-experimental-fg rf.story.theme.colors/tokens))}
   ;; rf2-ba86n.4 — large-list virtualization / bounding (spec/018 §10 —
   ;; cap or page; the UI SHOULD fail by summarizing, not flooding). The
   ;; "+N more" affordance row a story-block renders when its variant count
   ;; exceeds the per-story cap.
   :variant-more    {:padding "2px 12px 4px 26px"
                     :color (:text-tertiary rf.story.theme.colors/tokens)
                     :font-family mono-stack
                     :font-size (:micro rf.story.theme.typography/type-scale)
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
                     :color (:text-tertiary rf.story.theme.colors/tokens)
                     :font-family sans-stack
                     :font-size (:nano rf.story.theme.typography/type-scale)
                     :text-transform "uppercase"
                     :letter-spacing (:label-wide rf.story.theme.typography/letter-spacing)}
   :grid-group-count {:color (:accent-amber rf.story.theme.colors/tokens)
                      :font-family mono-stack}})
