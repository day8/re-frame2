(ns re-frame.epoch-listener-generation-public-api-test
  "rf2-6ys5n — the generation-qualified silencing the delayed-silence law
  documents is implementable through the PUBLIC API.

  Spec 009 §The delayed-silence emission linearization law and Tool-Pair
  §Surface behaviour against destroyed frames tell a consumer of a
  generation-qualified `:rf.epoch.cb/silenced-on-frame-destroy` signal to
  SELF-FILTER a superseded signal: when the current generation for `cb-id`
  no longer equals the carried `:observed-gen`, discard it. That rule was
  documented but NOT implementable through any supported API — the current
  generation was reachable only through the private listener registry
  (`re-frame.epoch.state/listeners-snapshot`). `re-frame.epoch/epoch-listener-
  generation` (re-exported on `re-frame.core`) exposes it, so this whole suite
  reaches for NO private state — only `re-frame.core` / `re-frame.epoch` public
  vars.

  TOOTH: delete the public query and every assertion here fails to resolve; the
  self-filter it proves has nowhere to read the current generation from."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks, including
            ;; `:epoch/epoch-listener-generation`.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- superseded?
  "The self-filter a `:rf.epoch.cb/silenced-on-frame-destroy` consumer applies,
  reading ONLY the supported public generation query: a signal whose
  `:observed-gen` no longer matches the cb's CURRENT generation is a stale signal
  attributed to a superseded registration and must be discarded."
  [tags]
  (not= (:observed-gen tags) (rf/epoch-listener-generation (:cb-id tags))))

;; ---- the public query answers the current registration --------------------

(deftest epoch-listener-generation-answers-the-current-registration
  (testing "a registered listener has a generation; an absent id is nil; a
            same-id replacement mints a fresh, distinct generation; unregister
            clears it — all through the public boundary"
    (is (nil? (rf/epoch-listener-generation ::never-registered))
        "no registration → nil")

    (rf/register-listener! :epoch ::cb (fn [_] nil))
    (let [g1 (rf/epoch-listener-generation ::cb)]
      (is (some? g1) "a registered listener has a current generation")

      ;; A same-id replacement mints a FRESH generation (never reused).
      (rf/register-listener! :epoch ::cb (fn [_] nil))
      (let [g2 (rf/epoch-listener-generation ::cb)]
        (is (some? g2))
        (is (not= g1 g2)
            "re-registration under the same id mints a distinct generation"))

      ;; Unregistering clears the generation.
      (rf/unregister-listener! :epoch ::cb)
      (is (nil? (rf/epoch-listener-generation ::cb))
          "after unregister the id has no current generation"))))

;; ---- a real emitted silence self-filters at the public boundary -----------

(deftest superseded-silence-self-filters-through-the-public-generation-query
  (testing "a real :rf.epoch.cb/silenced-on-frame-destroy carries :observed-gen;
            a consumer decides whether it names its CURRENT registration using
            only rf/epoch-listener-generation — accepting the live signal and
            discarding a signal superseded by an intervening re-registration"
    (rf/make-frame {:id :test/short-lived})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [recorded (atom [])]
      (rf/register-listener! :trace ::recorder (fn [ev] (swap! recorded conj ev)))
      (rf/register-listener! :epoch ::watcher (fn [_] nil))

      ;; Drive a cascade so the cb observes the frame under its live generation.
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (let [g-observed (rf/epoch-listener-generation ::watcher)]
        (is (some? g-observed) "the observing cb has a current generation")

        ;; Destroy the frame → exactly one generation-qualified silence.
        (rf/destroy-frame! :test/short-lived)
        (let [tags (->> @recorded
                        (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                    (:operation %)))
                        first
                        :tags)]
          (is (some? tags) "a silence fired for the destroyed frame")
          (is (= ::watcher (:cb-id tags)))
          (is (contains? tags :observed-gen)
              "the signal is generation-qualified (:observed-gen present)")
          (is (= g-observed (:observed-gen tags))
              "the silence names the generation that observed the frame")

          ;; CONSUMER, current registration: :observed-gen still equals the cb's
          ;; live generation, so the signal names the current callback — APPLY.
          (is (not (superseded? tags))
              "the live signal is NOT self-filtered — it names the current cb")

          ;; Now supersede the registration (G → H) THROUGH THE PUBLIC VERB.
          (rf/register-listener! :epoch ::watcher (fn [_] nil))
          (is (not= (:observed-gen tags) (rf/epoch-listener-generation ::watcher))
              "the replacement made a fresh generation current")

          ;; CONSUMER, superseded: the SAME captured signal now self-filters —
          ;; the public query is the whole basis for the decision, no private
          ;; registry read. This is the comparison the law requires and that was
          ;; previously impossible through any supported API.
          (is (superseded? tags)
              "a signal for the replaced generation is self-filtered at the
               public boundary"))))))
