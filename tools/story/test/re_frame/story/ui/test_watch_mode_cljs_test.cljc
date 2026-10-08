(ns re-frame.story.ui.test-watch-mode-cljs-test
  "Tests for the chrome-level test widget's watch-mode auto-re-run
  (Storybook 9 Vitest-addon watch-toggle parity).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `set-test-watch-mode` / `test-watch-
    mode?` / `record-test-content-hashes` / `watch-mode-drift`. The
    drift helper is the load-bearing primitive — the detector uses it
    to decide which variants need a re-run.
  - **CLJS-only**: the chrome widget renders the eye-icon toggle chip
    with the correct `aria-pressed` state; toggling on flips the chip
    on, off clears the recorded hashes."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.identity :as rf.story.identity]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.watch :as rf.story.ui.watch]
            #?@(:cljs [[re-frame.late-bind        :as rf.late-bind]
                       [re-frame.story.ui.shell   :as rf.story.ui.shell]
                       [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: watch-mode flag ----------------------------------------------

(deftest set-test-watch-mode-toggle-off-clears-hashes
  (testing "toggle-off resets [:tests :content-hashes] so the next
            toggle-on seeds fresh from the current registry"
    (let [seeded (-> rf.story.ui.state/default-shell-state
                     (rf.story.ui.state/set-test-watch-mode true)
                     (rf.story.ui.state/record-test-content-hashes
                       {:story.x/a "deadbeef"}))
          off    (rf.story.ui.state/set-test-watch-mode seeded false)]
      (is (false? (rf.story.ui.state/test-watch-mode? off)))
      (is (= {} (get-in off [:tests :content-hashes]))))))

;; ---- pure: drift detection ----------------------------------------------

(deftest watch-mode-drift-table
  (are [prev current drifted] (= drifted (rf.story.ui.state/watch-mode-drift prev current))
    ;; current == prev → no drift
    {:story.x/a "aaaa" :story.x/b "bbbb"}
    {:story.x/a "aaaa" :story.x/b "bbbb"}
    []

    ;; changed hashes → every drifted variant, sorted
    {:story.x/a "1" :story.x/b "2" :story.x/c "3"}
    {:story.x/a "X" :story.x/b "2" :story.x/c "Y"}
    [:story.x/a :story.x/c]

    ;; a variant absent from prev is a fresh registration the user wants
    ;; exercised, so it counts as drifted
    {:story.x/a "aaaa"}
    {:story.x/a "aaaa" :story.x/b "bbbb"}
    [:story.x/b]

    ;; a variant absent from current is dropped — there is nothing to re-run
    {:story.x/a "aaaa" :story.x/b "bbbb"}
    {:story.x/a "aaaa"}
    []))

;; ---- CLJS-only: widget renders the watch toggle ------------------------

#?(:cljs
   (defn- find-by-data-test
     "Walk a hiccup tree and return every element whose props map has
     `:data-test` equal to `tag`."
     [tree tag]
     (let [hits (transient [])]
       (letfn [(walk [node]
                 (cond
                   (and (vector? node)
                        (map? (second node))
                        (= tag (get (second node) :data-test)))
                   (do (conj! hits node)
                       (doseq [c (drop 2 node)] (walk c)))

                   (vector? node)
                   (doseq [c (rest node)] (walk c))

                   (seq? node)
                   (doseq [c node] (walk c))

                   :else nil))]
         (walk tree))
       (persistent! hits))))

#?(:cljs
   (deftest widget-watch-toggle-reflects-the-flag
     (rf.story/reg-variant :story.x/a {:tags #{:test} :setup []
                                    :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (let [chip-attrs (fn []
                        (-> (rf.story.ui.sidebar/test-widget (rf.story.ui.state/get-state)
                                                             (rf.story.ui.state/registry-snapshot))
                            (find-by-data-test "story-test-widget-watch-toggle")
                            first
                            second
                            ((juxt :aria-pressed :data-state))))]
       (is (= ["false" "off"] (chip-attrs)) "off by default")
       (rf.story.ui.state/swap-state! rf.story.ui.state/set-test-watch-mode true)
       (is (= ["true" "on"] (chip-attrs))))))

;; ---- a cell-override edit triggers a re-run ----------------------------
;;
;; The watch hash folds in each variant's `:cell-overrides`, and the hash
;; cache keys on them: a key that omitted them would serve a stale hash on
;; every control edit, and the detector would miss the re-run.

#?(:cljs
   (deftest cell-override-edit-perturbs-watch-mode-hash
     (rf.story/reg-variant :story.x/a
       {:component :app.ui/echo
        :args      {:n 0}
        :tags      #{:test}
        :setup    []
        :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (rf.story.ui.state/swap-state! rf.story.ui.state/set-test-watch-mode true)
     ;; The first pass seeds the hashes (and, prev being empty, re-runs).
     (rf.story.ui.shell/detect-watch-drift!)
     (let [baseline (get-in (rf.story.ui.state/get-state) [:tests :content-hashes :story.x/a])]
       (is (some? baseline) "the first pass seeds the slot from the registry")
       (rf.story.ui.state/swap-state! rf.story.ui.state/clear-test-run :story.x/a)
       (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override :story.x/a [:n] 42)
       (rf.story.ui.shell/detect-watch-drift!)
       (let [s (rf.story.ui.state/get-state)]
         (is (not= baseline (get-in s [:tests :content-hashes :story.x/a]))
             "the edit moves the variant's watch hash")
         (is (= :running (get-in s [:tests :runs :story.x/a :status]))
             "the detector re-runs the edited variant")))))

;; ---- drift re-runs ONLY the drifted variant's dot ----------------------
;;
;; tools/story/spec/009-Test-Mode.md §Watch mode: watch mode "re-runs
;; drifted testable variants only". With a second testable variant this
;; pins the SELECTIVE half that a single-variant test cannot.

#?(:cljs
   (deftest drift-reruns-only-the-drifted-variant
     (rf.story/reg-variant :story.x/a
       {:component :app.ui/echo
        :args      {:n 0}
        :tags      #{:test}
        :setup    []
        :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (rf.story/reg-variant :story.x/b
       {:component :app.ui/echo
        :args      {:n 0}
        :tags      #{:test}
        :setup    []
        :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (rf.story.ui.state/swap-state! rf.story.ui.state/set-test-watch-mode true)
     ;; The first pass seeds both hashes; clear its :running stamps so only
     ;; the NEXT pass can stamp one. An unseeded baseline would re-run B too.
     (rf.story.ui.shell/detect-watch-drift!)
     (rf.story.ui.state/swap-state! rf.story.ui.state/clear-test-run :story.x/a)
     (rf.story.ui.state/swap-state! rf.story.ui.state/clear-test-run :story.x/b)
     (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override :story.x/a [:n] 99)
     (rf.story.ui.shell/detect-watch-drift!)
     (let [runs (get-in (rf.story.ui.state/get-state) [:tests :runs])]
       (is (= :running (get-in runs [:story.x/a :status]))
           (str "the edited variant A re-runs; runs was " (pr-str runs)))
       (is (nil? (get runs :story.x/b))
           (str "the undrifted sibling B does not; runs was " (pr-str runs))))))

;; ---- toggle-ON seeds the baseline → no spurious full re-run -------------
;;
;; An on-click that only flipped the flag would leave the slot {} on enable,
;; so the first detector tick would read every testable variant as drifted
;; and re-run the WHOLE :test suite the instant watch turned on.

#?(:cljs
   (deftest toggle-on-seeds-baseline-no-spurious-full-rerun
     (rf.story/reg-variant :story.x/a {:tags #{:test} :setup []
                                    :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (rf.story/reg-variant :story.x/b {:tags #{:test} :setup []
                                    :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     ;; The REAL toggle entry point (what the on-click calls).
     (rf.story.ui.sidebar/set-watch-mode! true)
     (is (every? (get-in (rf.story.ui.state/get-state) [:tests :content-hashes])
                 [:story.x/a :story.x/b])
         "toggle-on seeds the baseline with both testable variants")
     (rf.story.ui.shell/detect-watch-drift!)
     (let [runs (get-in (rf.story.ui.state/get-state) [:tests :runs])]
       (is (= {} (select-keys runs [:story.x/a :story.x/b]))
           (str "the first tick re-runs nothing; runs was " (pr-str runs))))))

;; ---- a view-schema hot-reload busts the testable-hash cache ------------
;;
;; A schema hot-reload changes the view-schema digest the watch hash folds
;; in without bumping the registrar mutation-tick, so a cache keyed without
;; the digests would serve the stale hash and the variant would never re-run.

#?(:cljs
   (deftest schema-hot-reload-busts-testable-hash-cache
     (testing "mutating the view-schema digest (registrar tick, modes,
               substrate, overrides all unchanged) must change the cached
               testable hash; a cache keyed without it would short-circuit
               the real drift"
       (rf.story/reg-variant :story.x/a {:tags #{:test} :setup []
                                      :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
       (let [prior (rf.late-bind/get-fn :schemas/app-schemas-digest)]
         (try
           ;; Schema generation 1.
           (rf.late-bind/set-fn! :schemas/app-schemas-digest
                              (fn [_opts] "sha256:0000000000000001"))
           (let [h1 (get (rf.story.ui.watch/compute-testable-content-hashes) :story.x/a)]
             ;; Schema hot-reload — digest moves, Story side-table untouched.
             (rf.late-bind/set-fn! :schemas/app-schemas-digest
                                (fn [_opts] "sha256:0000000000000002"))
             (let [h2 (get (rf.story.ui.watch/compute-testable-content-hashes) :story.x/a)]
               (is (some? h1) "the first compute must hash the variant")
               (is (not= h1 h2)
                   "the cache must bust on a view-schema hot-reload")))
           (finally
             (rf.late-bind/set-fn! :schemas/app-schemas-digest prior)))))))

;; ---- an expectation-only edit re-runs ----------------------------------
;;
;; Snapshot identity hashes a variant's declared RENDER inputs (spec/002
;; §Snapshot-identity computation) because it keys visual review and
;; sharing, so it leaves out the slots that only decide what a run JUDGES.
;; Keyed on identity alone, watch mode would leave the hash where it was
;; when a variant is re-registered with only its expectations changed, and
;; the dot would keep the previous verdict. The watch hash folds those slots
;; in; identity leaves them out.

(deftest expectation-only-edit-drifts-watch-hash
  (testing "re-registering :test variants with ONLY :assertions,
            :checks, :compose or :extends changed drifts exactly those
            variants' watch hashes (so detect-watch-drift! re-runs them) and
            leaves their snapshot identity unchanged; an identical
            re-registration does not drift"
    (rf.story/reg-check :story.x/c-is-zero {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-check :story.x/c-is-one  {:assertions [[:rf.assert/path-equals [:c] 1]]})
    (rf.story/reg-variant :story.x/parent-zero {:tags #{:dev} :checks [:story.x/c-is-zero]})
    (rf.story/reg-variant :story.x/parent-one  {:tags #{:dev} :checks [:story.x/c-is-one]})
    (rf.story/reg-variant :story.x/assertions {:tags #{:test} :assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.x/checks     {:tags #{:test} :checks [:story.x/c-is-zero]})
    (rf.story/reg-variant :story.x/compose    {:tags #{:test} :compose [:story.x/c-is-zero]})
    (rf.story/reg-variant :story.x/extends    {:tags #{:test} :extends :story.x/parent-zero})
    (rf.story/reg-variant :story.x/unedited   {:tags #{:test} :assertions [[:rf.assert/path-equals [:c] 0]]})
    (let [vids       [:story.x/assertions :story.x/checks :story.x/compose
                      :story.x/extends :story.x/unedited]
          identities (fn []
                       (into {}
                             (map (fn [vid]
                                    [vid (:content-hash (rf.story.identity/snapshot-identity vid))]))
                             vids))
          before     (rf.story.ui.watch/compute-testable-content-hashes)
          ids-before (identities)]
      (rf.story/reg-variant :story.x/assertions {:tags #{:test} :assertions [[:rf.assert/path-equals [:c] 1]]})
      (rf.story/reg-variant :story.x/checks     {:tags #{:test} :checks [:story.x/c-is-one]})
      (rf.story/reg-variant :story.x/compose    {:tags #{:test} :compose [:story.x/c-is-one]})
      (rf.story/reg-variant :story.x/extends    {:tags #{:test} :extends :story.x/parent-one})
      (rf.story/reg-variant :story.x/unedited   {:tags #{:test} :assertions [[:rf.assert/path-equals [:c] 0]]})
      (let [after (rf.story.ui.watch/compute-testable-content-hashes)]
        (is (= (set vids) (set (keys before)))
            "every edited variant is testable, so watch mode hashes it")
        (is (= [:story.x/assertions :story.x/checks :story.x/compose :story.x/extends]
               (rf.story.ui.state/watch-mode-drift before after))
            "an expectation-only edit drifts the variant, so the detector re-runs it")
        (is (= ids-before (identities))
            "snapshot identity is NOT widened — these slots are not render inputs")))))

;; ---- a composed fragment's render-input edit re-runs -------------------
;;
;; A fragment the variant `:compose`s folds its `:db-seed` / `:setup` into the
;; variant's world (spec/017 §Strict composition), so editing the fragment
;; changes the settled state and the verdict. Snapshot identity hashes those
;; render inputs, and the watch hash carries identity, so the edit re-runs
;; exactly the variants composing that fragment.

(deftest composed-fragment-render-edit-drifts-identity-and-watch-hash
  (testing "re-registering a composed fragment with a different
            :db-seed or :setup drifts the composing variant's snapshot
            identity and its watch hash; a variant that does not compose the
            fragment does not drift"
    (rf.story/reg-fragment :story.x/seed  {:db-seed {:c 0}})
    (rf.story/reg-fragment :story.x/setup {:setup [[:story.x/set-c 0]]})
    (rf.story/reg-variant :story.x/seeded    {:tags #{:test} :compose [:story.x/seed]
                                              :assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.x/set-up    {:tags #{:test} :compose [:story.x/setup]
                                              :assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.x/unrelated {:tags #{:test}
                                              :assertions [[:rf.assert/path-equals [:c] 0]]})
    (let [identity-of (fn [vid] (:content-hash (rf.story.identity/snapshot-identity vid)))
          before      (rf.story.ui.watch/compute-testable-content-hashes)
          seeded-id   (identity-of :story.x/seeded)
          set-up-id   (identity-of :story.x/set-up)]
      (rf.story/reg-fragment :story.x/seed  {:db-seed {:c 1}})
      (rf.story/reg-fragment :story.x/setup {:setup [[:story.x/set-c 1]]})
      (let [after (rf.story.ui.watch/compute-testable-content-hashes)]
        (is (= [:story.x/seeded :story.x/set-up]
               (rf.story.ui.state/watch-mode-drift before after))
            "a composed fragment's render-input edit re-runs exactly the variants composing it")
        (is (not= seeded-id (identity-of :story.x/seeded))
            "a composed :db-seed edit changes the composing variant's identity")
        (is (not= set-up-id (identity-of :story.x/set-up))
            "a composed :setup edit changes the composing variant's identity")))))

;; ---- an edit to a registration reached by reference re-runs ------------
;;
;; A run reads registrations the variant only NAMES: the checks it lists or
;; receives from an `:extends` ancestor, the fragments and checks it
;; `:compose`s, and the ancestors themselves, whose world flows down
;; (spec/017 §`:extends`). Re-registering one of those changes the verdict
;; while the variant's own body stays put, so the watch hash reaches them
;; too.

(deftest referenced-registration-edit-drifts-watch-hash
  (testing "re-registering a registration a :test variant
            reaches only by reference drifts exactly the variants that reach
            it; a cosmetic :doc edit, an identical re-registration and an
            edit to an unreferenced registration drift nothing"
    (rf.story/reg-check :story.x/own       {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-check :story.x/composed  {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-check :story.x/inherited {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-check :story.x/other     {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-check :story.x/unused    {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-fragment :story.x/argtypes {:argtypes {:label {:control :text}}})
    (rf.story/reg-variant :story.x/parent         {:tags #{:dev} :setup [[:story.x/set-c 0]]
                                                   :checks [:story.x/inherited]})
    (rf.story/reg-variant :story.x/names-check    {:tags #{:test} :checks [:story.x/own]})
    (rf.story/reg-variant :story.x/composes-check {:tags #{:test} :compose [:story.x/composed]})
    (rf.story/reg-variant :story.x/composes-frag  {:tags #{:test} :compose [:story.x/argtypes]
                                                   :assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.x/child          {:tags #{:test} :extends :story.x/parent})
    (let [drift (fn [edit!]
                  (let [before (rf.story.ui.watch/compute-testable-content-hashes)]
                    (edit!)
                    (rf.story.ui.state/watch-mode-drift
                      before (rf.story.ui.watch/compute-testable-content-hashes))))]
      (is (= [:story.x/names-check]
             (drift #(rf.story/reg-check :story.x/own {:assertions [[:rf.assert/path-equals [:c] 1]]})))
          "a check body the variant names")
      (is (= [:story.x/composes-check]
             (drift #(rf.story/reg-check :story.x/composed {:assertions [[:rf.assert/path-equals [:c] 1]]})))
          "a check body the variant composes")
      (is (= [:story.x/composes-frag]
             (drift #(rf.story/reg-fragment :story.x/argtypes {:argtypes {:label {:control :select}}})))
          "a composed fragment's body beyond its render inputs")
      (is (= [:story.x/child]
             (drift #(rf.story/reg-check :story.x/inherited {:assertions [[:rf.assert/path-equals [:c] 1]]})))
          "a check body the variant receives from its :extends parent")
      (is (= [:story.x/child]
             (drift #(rf.story/reg-variant :story.x/parent {:tags #{:dev} :setup [[:story.x/set-c 0]]
                                                            :checks [:story.x/other]})))
          "the parent's :checks")
      (is (= [:story.x/child]
             (drift #(rf.story/reg-variant :story.x/parent {:tags #{:dev} :setup [[:story.x/set-c 1]]
                                                            :checks [:story.x/other]})))
          "the parent's world, which flows down through :extends")
      (is (= [] (drift #(rf.story/reg-check :story.x/own {:doc        "c is one"
                                                           :assertions [[:rf.assert/path-equals [:c] 1]]})))
          "a :doc-only edit is cosmetic")
      (is (= [] (drift #(rf.story/reg-fragment :story.x/argtypes {:argtypes {:label {:control :select}}})))
          "an identical re-registration, whose :source coords differ, drifts nothing")
      (is (= [] (drift #(rf.story/reg-check :story.x/unused {:assertions [[:rf.assert/path-equals [:c] 1]]})))
          "an edit to a registration no variant reaches drifts nothing"))))
