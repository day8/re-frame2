(ns panel-gallery.ai-co-pilot-stories
  "Story coverage for the Causa AI Co-Pilot panel under gallery
  framing (rf2-8r20i, Phase 2).

  Twelve variants, each one render of `ai-co-pilot-view` against a
  variant frame whose seven `:copilot-*` slots have been seeded by a
  REAL gallery-local init event fired into the variant frame.

  ## Why a gallery-local seed event

  Causa's panel reads seven slots (`:copilot-open?`,
  `:copilot-conversation`, `:copilot-provider`,
  `:copilot-cue-active?`, `:copilot-redaction-settings`,
  `:copilot-streaming-token-count`, `:copilot-input-text`) and the
  Causa registrations provide individual mutators
  (`:rf.causa/copilot-stream-token`, `:rf.causa/copilot-set-input-text`)
  but no SINGLE seed event that sets all the visible state at once.
  Variants seed via the gallery-local
  `:panel-gallery/seed-copilot` event (registered in `core.cljs`) —
  one `assoc` per slot — so Story's `:rf.story/*` runtime slots
  survive per `tools/story/spec/002-Runtime.md` §Coexistence with
  hosting application state. ZERO source-side changes to the panel;
  this seed event is testbed-only.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame. The Co-Pilot panel's seven `:rf.causa/copilot-*`
  subs read from the current frame's app-db — the seed event's
  assocs land on the variant frame. Each variant therefore observes
  its own bespoke state in isolation; no two variants share."
  (:require [re-frame.story :as story]
            [panel-gallery.ai-co-pilot-fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

(defn register-gallery-view! []
  (gallery-views/register!))

(defn register-all!
  "Register the AI Co-Pilot Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-ai-co-pilot
    {:axis :feature
     :doc  "Causa AI Co-Pilot panel — conversation column, input row,
            slash popover, structured citation chips, redaction
            policy, streaming cursor, provider picker."})

  (story/reg-story :story.causa.ai-co-pilot
    {:doc        "Visual gallery of the Causa AI Co-Pilot panel under
                 varying conversation state. Each variant seeds its
                 frame's seven :copilot-* slots via the gallery-local
                 :panel-gallery/seed-copilot event; the rendered
                 panel reads from the variant frame in isolation."
     :component  :panel-gallery.ai-co-pilot/Panel
     :tags       #{:dev :feature/causa-ai-co-pilot}
     :substrates #{:reagent}})

  ;; ----- 1. empty (no conversation) ----------------------------------
  (story/reg-variant :story.causa.ai-co-pilot/empty
    {:doc        "Empty conversation. Panel renders the 'Ask Causa
                 anything' empty-state with the sample-prompts
                 ladder."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/empty-convo)}]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. typing (input populated, no submit) ----------------------
  (story/reg-variant :story.causa.ai-co-pilot/typing
    {:doc        "Empty conversation but the input has user-typed-
                 but-not-submitted text. The panel surfaces the
                 input value verbatim — no slash popover."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/typing-convo)
                    :input-text "Why did :checkout/submit fire?"}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. mid-completion (streaming) -------------------------------
  (story/reg-variant :story.causa.ai-co-pilot/mid-completion
    {:doc        "Question + streaming answer with content. The
                 streaming cursor caret renders at the tail; the
                 token-count badge surfaces a non-zero value."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/mid-completion-convo)
                    :streaming-token-count 23}]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. error ----------------------------------------------------
  (story/reg-variant :story.causa.ai-co-pilot/error
    {:doc        "Question + answer reporting an LLM provider error.
                 The literal error string surfaces in the answer
                 column."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/error-convo)}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. large-context (vertical scroll) --------------------------
  (story/reg-variant :story.causa.ai-co-pilot/large-context
    {:doc        "Six-turn conversation exercising vertical scroll
                 behaviour. The panel's conversation column is
                 scrollable; under load the rail form clips to a
                 fixed height."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/large-context-convo)
                    :streaming-token-count 14}]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 6. redacted -------------------------------------------------
  (story/reg-variant :story.causa.ai-co-pilot/redacted
    {:doc        "Conversation where the answer's args arrive
                 redacted per Spec 009 §Privacy. The panel surfaces
                 the marker verbatim — it does not re-redact."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/redacted-convo)}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. with citation chips (panel-specific axis A) -------------
  ;;
  ;; Panel-specific axis: answers may carry inline structured chips
  ;; (`{:rf.copilot/chip <key> <value>}`) that render as clickable
  ;; citation chips. This variant surfaces three chip kinds in one
  ;; answer.
  (story/reg-variant :story.causa.ai-co-pilot/with-citation-chips
    {:doc        "Answer with three structured citation chips
                 (`:dispatch-id`, `:path`, `:epoch-number`). Each
                 chip renders with its glyph and is clickable.
                 Panel-specific axis."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/with-chips-convo)}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. slash popover open (panel-specific axis B) --------------
  ;;
  ;; Panel-specific axis: typing `/` in the input opens the slash
  ;; popover with matching command suggestions. This variant seeds
  ;; the input with a partial `/s` so the popover surfaces every
  ;; `/s*` command.
  (story/reg-variant :story.causa.ai-co-pilot/slash-popover
    {:doc        "Input contains a partial slash command (`/s`).
                 The slash popover surfaces every matching command
                 — `/state`, `/snapshot`, `/scrub`, etc.
                 Panel-specific axis."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/slash-typing-convo)
                    :input-text "/s"}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. provider override (panel-specific axis C) ---------------
  ;;
  ;; Panel-specific axis: the title bar surfaces the provider chip;
  ;; clicking cycles through (:claude / :openai / :gemini / :local /
  ;; :custom). This variant overrides to :openai so the picker's
  ;; chip displays the non-default value.
  (story/reg-variant :story.causa.ai-co-pilot/provider-openai
    {:doc        "Provider override to `:openai`. Settled two-turn
                 conversation; the picker chip surfaces the override
                 value. Panel-specific axis."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/provider-cycle-convo)
                    :provider :openai}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. multi-question burst (panel-specific axis D) -----------
  ;;
  ;; Panel-specific axis: three consecutive questions without
  ;; intervening answers — exercises the panel's question-stacking
  ;; behaviour (rapid-fire user input under rate-limited streams).
  (story/reg-variant :story.causa.ai-co-pilot/multi-question
    {:doc        "Three back-to-back questions without intervening
                 answers. The panel stacks them in chronological
                 order. Panel-specific axis: rapid-fire input
                 rendering."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/multi-question-convo)}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 11. long streaming (panel-specific axis E) -----------------
  ;;
  ;; Panel-specific axis: a long streaming answer exercises the
  ;; token-count badge (>100 tokens) + the streaming cursor caret
  ;; at the tail of a wrapped multi-line answer.
  (story/reg-variant :story.causa.ai-co-pilot/long-streaming
    {:doc        "One question + a long streaming answer wrapping
                 across multiple lines. The token-count badge
                 surfaces 127; the streaming cursor caret renders at
                 the tail. Panel-specific axis."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/long-streaming-convo)
                    :streaming-token-count 127}]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 12. redaction unmask (panel-specific axis F) ---------------
  ;;
  ;; Panel-specific axis: the panel's redaction-settings slot
  ;; controls whether trace events / app-db forward redacted or
  ;; unmasked to the LLM. This variant flips both unmask flags so
  ;; the redaction status (when surfaced in the chrome) shows the
  ;; non-default privacy posture.
  (story/reg-variant :story.causa.ai-co-pilot/redaction-unmask
    {:doc        "Redaction settings flipped to unmask both event
                 args and app-db leaves — the panel's privacy
                 posture is overtly non-default. Panel-specific
                 axis: settings drive the LLM-bound payload shape."
     :events     [[:panel-gallery/seed-copilot
                   {:conversation (fixtures/one-turn-convo)
                    :redaction-settings {:unmask-event-args true
                                         :unmask-app-db     true}}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.ai-co-pilot/all
    {:doc      "All twelve AI Co-Pilot variants in one auto-grid.
                Scroll to see the panel's response across empty /
                typing / mid-completion / error / large-context /
                redacted / citation-chips / slash-popover / provider-
                override / multi-question / long-streaming /
                redaction-unmask."
     :layout   :variants-grid
     :story    :story.causa.ai-co-pilot
     :columns  2
     :tags     #{:dev}}))

(register-all!)
