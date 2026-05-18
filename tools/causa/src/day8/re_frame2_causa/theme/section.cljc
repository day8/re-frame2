(ns day8.re-frame2-causa.theme.section
  "Shared stacked-section primitive (rf2-pie8q).

  Causa panels use a uniform rhythm of stacked sections — small upper-
  cased header (`▼ SECTION TITLE`) followed by a body, separated from
  the next section by a 1px dotted horizontal rule. The visual contract
  is documented in `tools/causa/spec/007-UX-IA.md` and was reinvented
  twice — once in `panels/managed_fx_template/section` (rf2-uyp86) and
  again in `panels/event_detail/section` (rf2-zh2qc PR #1480). Both
  invented identical typography (sans-stack, 11px, weight 600, letter-
  spacing 0.6px, uppercase, `:text-secondary`) and identical body
  styling (mono-stack, 12px, `:text-primary`, 6px/0/0/18px padding).

  Hoisting the shape here gives the two consumers a single source of
  truth so a future rhythm tweak (a tighter letter-spacing, say, or a
  swap of the dotted rule for a solid one) lands in one place.

  ## Shape

      (section-row
        {:label   \"REQUEST\"
         :testid  \"rf-causa-managed-fx-section-request\"
         :expanded? false}
        body-hiccup)

  Options:

    `:label`             — required; the uppercase title text.
    `:testid`            — required; base data-testid. The header div
                           gets `<testid>-header`; the body div gets
                           `<testid>-body`. The outer container carries
                           the plain `:testid`.
    `:expanded?`         — default `true`. When `false` the glyph is
                           `▶` and the body div is NOT rendered (matches
                           managed_fx_template's collapsed semantics).
                           When `true` the glyph is `▼` and the body
                           always renders.
    `:count*`            — optional; non-nil renders as ` (N)` after
                           the label in `:text-tertiary` (mono-stack).
                           Matches event_detail's INTERCEPTORS / EFFECTS
                           HANDLERS RAN sections.
    `:container-padding` — optional CSS string; default `\"8px 12px\"`
                           (event_detail's choice). Callers that want
                           the managed_fx_template rhythm pass `\"8px 0\"`
                           — the only divergent token between the two
                           consumers.

  Pure hiccup. `.cljc` so JVM consumers (the spec linter, future
  story snapshots) can require it without a CLJS runtime.

  ## What is intentionally NOT here

    - No interactivity. Click-to-toggle wiring is the caller's
      responsibility — the primitive only renders the visual state the
      caller passes via `:expanded?`. (Per the bead: 'sans tone-key,
      with collapse glyph'.)
    - No body-shape opinions. The body argument is opaque hiccup.
    - No section-stacking helper. Callers compose by listing sections
      inline; the dotted bottom border draws the rhythm."
  (:require [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn- header
  "Internal header row. See `section-row` for the public contract."
  [{:keys [label expanded? count* testid]}]
  [:div {:data-testid testid
         :style       {:display        "flex"
                       :align-items    "center"
                       :gap            "6px"
                       :padding        "4px 0"
                       :font-family    sans-stack
                       :font-size      "11px"
                       :font-weight    600
                       :letter-spacing "0.6px"
                       :text-transform "uppercase"
                       :color          (:text-secondary tokens)}}
   [:span {:style {:color       (:text-tertiary tokens)
                   :font-family mono-stack}}
    (if expanded? "▼" "▶")]
   label
   (when count*
     [:span {:style {:color       (:text-tertiary tokens)
                     :font-weight 400
                     :font-family mono-stack}}
      (str "(" count* ")")])])

(defn section-row
  "Render one stacked section.

  See ns docstring for the option map. `body` is appended as the second
  positional argument so call sites read top-to-bottom (`label …` then
  body)."
  [{:keys [label expanded? count* testid container-padding]
    :or   {expanded?         true
           container-padding "8px 12px"}}
   body]
  [:div {:data-testid testid
         :style       {:padding       container-padding
                       :border-bottom (str "1px dotted "
                                           (:border-subtle tokens))}}
   (header {:label     label
            :expanded? expanded?
            :count*    count*
            :testid    (str testid "-header")})
   (when expanded?
     [:div {:data-testid (str testid "-body")
            :style       {:padding     "6px 0 0 18px"
                          :font-family mono-stack
                          :font-size   "12px"
                          :color       (:text-primary tokens)}}
      body])])
