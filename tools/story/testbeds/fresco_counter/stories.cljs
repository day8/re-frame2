(ns hicasso-counter.stories
  "The deck — Story variants over views authored in Hicasso.

  This is Phase D of rf2-5czki, and its whole claim is one sentence: a
  view a programmer wrote on the NATIVE substrate is storyable inside
  Story, on the same PR-path gate every Reagent deck rides. Not \"a
  Hicasso boundary can be embedded in a Reagent testbed\" — the deck below
  declares `:substrates #{:hicasso}` and nothing here paints under
  Reagent.

  ## What each variant is for

    :story.hicasso-counter/tally    the interaction, and the gate's row.
                                    Its `:script` mounts the real
                                    boundary, clicks a real button, and
                                    asserts BOTH the resulting app-db and
                                    the resulting DOM.
    :story.hicasso-counter/readout  a second, read-only boundary — so the
                                    deck says which Hicasso view painted
                                    rather than merely that one did.

  ## What is deliberately NOT here

  No expected-fail fixture, and no four-row inventory. The counter
  testbed owns proof of the runner's pass/fail semantics and keeps its
  both-sides invariant unchanged; manufacturing a failing hicasso variant
  would test the runner twice and the substrate no harder (ruled,
  rf2-kttom). This deck's floor is ONE meaningful play, and the roster
  entry in `examples/scripts/serve-and-run-story-play-scripts.cjs` says so.

  No app image either. `login-form.stories` declares one because its
  namespaces are CO-LOADED into the consolidated `:node-test` build by a
  sibling `*_cljs_test.cljs`, where cross-app registrations of the same
  `[kind id]` would collide in the default-image projection. This testbed
  ships no such test, so nothing co-loads it and there is no collision to
  scope away from.

  ## The `:hicasso` substrate must be REGISTERED, and the host does it

  Story ships no installer for `:hicasso`, exactly as it ships none for
  `:uix` — the renderer's one dependency is the host's. `hicasso-counter.core`
  registers it at boot. A variant below resolving to an unregistered
  substrate paints a red cell naming the missing `register-substrate!`
  call, which is what makes removing that registration a legible RED
  rather than a silent fall-back to Reagent."
  (:require [re-frame.story :as rf.story]
            ;; Sourcing these fires the registrations the variants name.
            [hicasso-counter.events]
            [hicasso-counter.subs]
            [hicasso-counter.views]))

(defn register-all!
  "Register the testbed's Story artefacts. Idempotent; fired once at
  namespace load. The canonical vocabulary auto-installs on the first
  `reg-*` below, so there is no boot step to remember."
  []
  (rf.story/reg-story :story.hicasso-counter
    {:doc        "A tally authored in Hicasso — `h/defview`, `h/sub` and
                 intent vectors, with no adapter call anywhere in the
                 view source."
     :component  :hicasso-counter.views/tally
     :args       {:heading "Tally"}
     :tags       #{:dev :docs}
     :substrates #{:hicasso}})

  ;; -------------------------------------------------------------------------
  ;; The gate's row — one meaningful play through the live Story shell.
  ;;
  ;; Sleep-free by construction (rf2-n0sz4): the runner holds each step
  ;; until its preconditions hold — the frame's event queue drained and
  ;; the named node present — committing the substrate through the live
  ;; adapter's `flush-render!` between attempts. `[:wait ms]` is spec/017's
  ;; determinism OPT-OUT, and its absence here is the point.
  ;;
  ;; The script is IDEMPOTENT (initialise → one bump → assert), so the
  ;; shell's deep-link auto-run and its watcher-edge auto-run reach the
  ;; same terminal state.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-variant :story.hicasso-counter/tally
    {:doc    "The interaction. Seeds the tally, reads it off the DOM the
             boundary painted, clicks the boundary's own button, and
             asserts that the click reached the frame (app-db) AND came
             back out as a repaint (the DOM).

             Three things have to be true for this to pass and each is
             part of the claim: the `:hicasso` render fn resolved the
             head off the view alias; the boundary read the VARIANT's
             frame through React context rather than a default; and a
             write into that frame re-ran the body."
     :args   {:heading "Tally"}
     :setup  [[:hicasso-counter/initialise 0]]
     :script
     {:name      "bump-reaches-the-frame-and-repaints-the-boundary"
      :auto-run? true
      :script    [[:dispatch-sync [:hicasso-counter/initialise 0]]
                  [:assert-dom "[data-test=hic-count]" :text "0"]
                  [:click      "[data-test=hic-bump]"]
                  [:assert-db  [:count] 1]
                  [:assert-dom "[data-test=hic-count]" :text "1"]]}
     :tags   #{:dev :docs :test}
     :substrates #{:hicasso}})

  (rf.story/reg-variant :story.hicasso-counter/readout
    {:doc        "A second Hicasso boundary under the same substrate, so
                 a green row names WHICH view painted. Read-only: it
                 carries no script, and exists to keep the deck honest
                 about resolution rather than to add gate coverage."
     :component  :hicasso-counter.views/readout
     :args       {:label "tally"}
     :setup      [[:hicasso-counter/initialise 7]]
     :tags       #{:dev :docs}
     :substrates #{:hicasso}}))

;; Fire the registrations once at namespace load.
(register-all!)
