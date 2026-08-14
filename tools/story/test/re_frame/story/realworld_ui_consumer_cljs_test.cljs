(ns re-frame.story.realworld-ui-consumer-cljs-test
  "S7-ENTRY PROOF 2/2 — the STORY seam (rf2-vvdzx). Story hosts the REAL
  realworld re-frame.ui app (`examples/real-apps/realworld_resources`, the P-1
  full-app variant) as a CONSUMER: re-frame.ui enters Story's substrate
  roster, and a minimal ui deck exercising the app's own compiled view + real
  dataflow plays GREEN through the EXISTING Story shell.

  A third arm once drove the late-bound presence bridge end-to-end against
  Freehand's real presence clock. It followed the BRIDGE, and retired with it
  (rf2-5gka): Story ships no presence installer now, so there is no shipped
  integration for an acceptance test to accept. The rung's own coverage —
  seam, both arities, and the fail-closed refusal — is host-agnostic and
  lives in `play/presence_cljs_test`.

  Story is a CONSUMER here — the tool UI is NOT rewritten onto `defview`. The
  app CONTRIBUTES its compiled `counter` view (the smallest complete
  re-frame.ui app) + its plain-re-frame2 `:ui-counter/*` events; Story hosts
  and plays it. The two facts, one deftest each:

    1. re-frame.ui ENTERS Story's substrate roster through the SANCTIONED
       runtime path — `story/register-substrate!`, the seam
       `re-frame.story.ui.multi-substrate` documents for a host that loads an
       adapter Story does not ship. The registered render seam exports the
       app's OWN compiled view as a foreign React component through the
       ratified `ui/->react` shape (rf2-4rwtd) — proving the substrate can host
       the real app view, not a stub. (The authoring `:substrates` schema
       enum is a SEPARATE, milestone-gated set — see
       `re-frame.story.schemas/SubstrateSet` — so the deck declares its
       component, not a schema-reserved substrate id.)
    2. The ui deck's variant drives the app's REAL counter events through the
       Story play runner and its `:assert-db` on the app's real app-db key
       passes — the app hosted + played GREEN through the shell.

  COVERAGE SHAPE — a CLJS unit test on the node runtime, wired into the
  EXISTING `npm run test:cljs` suite (NOT Playwright). Pure `.cljs` (not
  `.cljc`): `story/run-variant` is async, and cljs.test requires the MAP
  fixture form for async bodies."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.source-store :as source-store]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.async :as async-lib]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.ui.multi-substrate :as multi]
            ;; re-frame.ui — the outward interop bridge that exports a compiled
            ;; view as a foreign React component (the substrate render seam).
            [re-frame.ui :as ui]
            ;; THE REAL APP (P-1). Requiring it registers the compiled `counter`
            ;; view + the `:ui-counter/*` events + `:ui-counter/count` sub.
            [realworld-resources.ui-counter :as ui-counter]))

;; ---------------------------------------------------------------------------
;; The deck — a minimal ui deck naming the app's OWN compiled view + driving
;; its OWN events. Registered per-test (Story side-table) by the fixture.
;; ---------------------------------------------------------------------------

(def ^:private counter-view-id :realworld-resources.ui-counter/counter)
(def ^:private ui-substrate :re-frame.ui)

(defn- ui-substrate-render-fn
  "The re-frame.ui substrate render seam Story's grid calls
  `(fn [variant-id view-id args] → react-element)`. Exports the app's OWN
  compiled `counter` view as a foreign React component through `ui/->react`
  (the rf2-4rwtd shape) and hands it to the host — Story consuming the app's
  compiled view, no tool-UI rewrite. A missing id yields an inline diagnostic,
  never a throw."
  [_variant-id view-id args]
  (if-let [view (get {counter-view-id ui-counter/counter} view-id)]
    [:> (ui/->react view) args]
    [:div (str "no re-frame.ui view registered for " (pr-str view-id))]))

(defn- register-deck!
  "Register the minimal realworld-ui counter deck against the Story
  side-table. `:component` names the app's OWN compiled view; the variant
  `:script` drives the app's OWN `:ui-counter/*` events and asserts the app's
  OWN app-db key."
  []
  (story/reg-tag :realworld-ui/consumer
    {:doc "The realworld re-frame.ui app hosted as a Story consumer."})
  (story/reg-story :story.realworld-ui-counter
    {:doc       "The realworld re-frame.ui counter — the smallest complete
                 re-frame.ui app — hosted as a Story consumer."
     :component counter-view-id
     :tags      #{:test :realworld-ui/consumer}})
  (story/reg-variant :story.realworld-ui-counter/counts-up
    {:doc    "Drives the app's real counter events through the Story play
              runner: init → +1 → +5, then asserts the app's real app-db."
     :script [[:dispatch [:ui-counter/init]]
              [:dispatch [:ui-counter/inc 1]]
              [:dispatch [:ui-counter/inc 5]]
              [:assert-db [:ui-counter/count] 6]]})
  nil)

;; ---------------------------------------------------------------------------
;; Fixture — Story-testbed shape (snapshot/restore registrar + stable ns-load
;; source-store baseline, per `counter_with_stories/stories_cljs_test`).
;; MAP form for the async bodies.
;; ---------------------------------------------------------------------------

(def ^:private registrar-snapshot (atom nil))

;; STABLE ns-load source-store baseline, captured AFTER the `:require` chain
;; fired every `reg-*`, so the app's `:ui-counter/*` descriptors this deck's
;; default variant image resolves are live. Restored per-`before!` so each
;; test resolves the app slices run-order-independently.
(def ^:private source-store-baseline @source-store/kind->id->ns->descriptor)

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (frame/ensure-default-frame!)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (story/clear-all!)
  (reset! source-store/kind->id->ns->descriptor source-store-baseline)
  (story/install-canonical-vocabulary!)
  (register-deck!)
  ;; re-frame.ui enters the substrate roster — exactly what a re-frame.ui-
  ;; hosting app does at boot (the documented `register-substrate!` seam).
  (story/register-substrate! ui-substrate ui-substrate-render-fn)
  nil)

(defn- after! []
  (multi/unregister-substrate! ui-substrate)
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {})
  nil)

(use-fixtures :each {:before before! :after after!})

;; ===========================================================================
;; 1 — re-frame.ui enters Story's substrate roster; the seam hosts the app view
;; ===========================================================================

(deftest re-frame-ui-enters-the-substrate-roster
  (testing "the substrate roster now carries re-frame.ui alongside the
            canonical :reagent"
    (is (contains? (story/registered-substrates) ui-substrate)
        "story/register-substrate! entered re-frame.ui into the roster")
    (is (contains? (story/registered-substrates) :reagent)
        "…without displacing the canonical substrate"))
  (testing "the substrate render seam exports the app's OWN compiled view as a
            foreign React component (the ui/->react shape) — Story hosting the
            real app view, not a stub"
    (let [rendered (multi/render-view ui-substrate
                                      :story.realworld-ui-counter/counts-up
                                      counter-view-id
                                      {})]
      (is (vector? rendered) "the seam returned a renderable")
      (is (= :> (first rendered))
          "…as a foreign React component (reagent's :> interop head)")
      (is (fn? (second rendered))
          "…the app's compiled `counter`, exported via ui/->react")))
  (testing "an unknown view id degrades to an inline diagnostic, never a throw"
    (let [rendered (multi/render-view ui-substrate :v :legacy/unregistered {})]
      (is (= :div (first rendered))))))

;; ===========================================================================
;; 2 — a ui deck hosts + plays GREEN through the existing Story shell
;; ===========================================================================

(deftest ui-deck-registers-and-plays-green-through-the-shell
  (testing "the deck registered against the Story side-table"
    (is (story/registered? :story :story.realworld-ui-counter))
    (is (contains? (story/variants-of :story.realworld-ui-counter)
                   :story.realworld-ui-counter/counts-up)))
  (testing "the variant plays through the Story play runner and its assertion
            on the app's real app-db passes — the app's real dataflow, hosted"
    (async done
      (-> (story/run-variant :story.realworld-ui-counter/counts-up)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result)) "the ui variant reached :ready")
              (is (= 6 (-> result :app-db :ui-counter/count))
                  "the app's REAL :ui-counter/* events drove app-db to 6")
              (is (story/assertions-passing? result)
                  (str "play assertions: " (pr-str (:assertions result))))
              (story/destroy-variant! :story.realworld-ui-counter/counts-up)
              (done)))
          (async-lib/catch*
            (fn [e]
              (is false (str "run-variant REJECTED: " (pr-str e)))
              (done)))))))
