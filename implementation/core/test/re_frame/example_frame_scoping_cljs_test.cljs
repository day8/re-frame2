(ns re-frame.example-frame-scoping-cljs-test
  "Framework-tree regressions carved out of rf2-9wc2ed / rf2-wbgb74
  (rf2-i6p308). These belong in the framework test tree, NOT under
  examples/ (examples stay test-free per rf2-8cevm), because they require
  the example ENTRY namespaces — which are `.cljs`-only (Reagent-coupled
  `reg-view`) and so cannot load on the JVM. They run under the
  consolidated `:node-test` CLJS build (`../examples/reagent` is on its
  source-paths) — the only runtime where these examples' ns-load + their
  durable handlers actually execute.

  Two contracts are pinned here:

  1. NS-LOAD FRAME-SCOPING (rf2-9wc2ed). `reg-app-schema` is EP-0002
     context-required frame-local: a bare ns-load call under no established
     scope raises `:rf.error/no-frame-context`. The seven_guis singletons +
     nine_states fixed this by wrapping their ns-load registration in
     `(with-frame :rf/default …)` so the schema binds to the app frame whose
     commits it validates. This test runs with the fixture's ambient
     `:rf/default` pin REMOVED (`:ambient-frame nil`) — the pin that MASKED
     the bug by silently supplying a frame to a bare call (cf rf2-lo28u: a
     fixture pin routing around the gap is a false-green). With the pin
     removed it asserts BOTH halves: (a) each example's ns-load schema landed
     on `:rf/default` (proving the fix's explicit frame fired — these survive
     in the per-frame schema snapshot because the example ns loaded at THIS
     test ns's require), AND (b) a bare `reg-app-schema` under this same
     no-ambient scope DOES raise `:rf.error/no-frame-context` (proving the
     guard is live exactly here — so had the example used a bare ns-load
     call, its load at require time would have thrown and aborted the whole
     `:node-test` bundle).

  3. RECORDABLE-COFX REPLAY-STABILITY (rf2-wbgb74 / EP-0017). A durable id
     (nine_states' new-todo `:id`; realworld's optimistic comment temp-id)
     must be FOLDED from a recordable `reg-cofx`, never read ambiently at the
     write site — otherwise replay mints a different id and the snapshot /
     join-key no longer reproduces (EP-0010 §Restore, Replay, And
     Hydration). This test dispatches `:new-todo/submit` and
     `:comment-form/submit` TWICE with the SAME recorded `:rf.cofx` token +
     `:rf.cofx/mint-policy :strict` (which re-feeds the recorded value
     verbatim and never re-mints) and asserts the durable id is the token's
     id on BOTH runs (identical across runs). Mirrors
     `cofx_envelope_test/supplied-uuid-replay-stable-where-ambient-would-diverge`,
     but executed against the EXAMPLES' OWN handlers — so a drift back to a
     write-site `(random-uuid)` (the rf2-wbgb74 bug) fails loud here.

  (Regression 2 — the SSR per-request schema validation assertion — lives in
  `re-frame.examples-test` (JVM), where the `.cljc` SSR example's
  `handle-request` runs.)"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; The affected example ENTRY namespaces. Requiring them here is
            ;; load-bearing twice over: (1) it fires their ns-load
            ;; `reg-app-schema` / `reg-cofx` / `reg-machine` / `reg-event`
            ;; forms (against the live registrar, captured into this ns's
            ;; fixture baseline), and (2) had any of these reverted to a bare
            ;; ns-load `reg-app-schema`, the require below would throw
            ;; `:rf.error/no-frame-context` and abort the whole `:node-test`
            ;; bundle — the strongest possible regression.
            [nine-states.core]
            [seven-guis.temperature.core]
            [seven-guis.flight-booker.core]
            [seven-guis.crud.core]
            [seven-guis.timer.core]
            [seven-guis.circle-drawer.core]
            [seven-guis.cells.core]
            [realworld-http.comments]))

;; The fixture's ambient `:rf/default` pin is REMOVED (`:ambient-frame nil`)
;; and `:clear-app-schemas?` is OMITTED so the example ns-load schemas
;; registered on `:rf/default` stay live through the test body. This is the
;; precise configuration the rf2-9wc2ed regression needs:
;;   - no ambient pin → a bare `reg-app-schema` would raise (the bug's
;;     failure mode is observable, not masked);
;;   - schemas retained → the example's explicit-frame registration is
;;     readable, proving the fix fired.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

;; ============================================================================
;; 1. NS-LOAD FRAME-SCOPING  (rf2-9wc2ed)
;; ============================================================================

;; (entry-ns . app-schema-path) pairs the fix bound to `:rf/default` at
;; ns-load via `(with-frame :rf/default (reg-app-schema path …))`.
(def ^:private example-app-schema-paths
  {"nine-states.core"               [:new-todo]
   "seven-guis.temperature.core"    [:temp]
   "seven-guis.flight-booker.core"  [:flight]
   "seven-guis.crud.core"           [:crud]
   "seven-guis.timer.core"          [:timer]
   "seven-guis.circle-drawer.core"  [:drawer]
   "seven-guis.cells.core"          [:cells]})

;; Snapshot, AT THIS NS'S LOAD (the moment the example `:require`s above have
;; finished firing their ns-load `(with-frame :rf/default (reg-app-schema …))`
;; forms), where each example's app-schema landed — BEFORE any `:each`
;; fixture runs and churns the per-frame schema side-table (rf2-cq1ak: that
;; table is per-frame state outside the registrar; sibling test nses snapshot/
;; restore it around their own bodies and can leave it cleared between runs).
;; This freeze captures the ground truth — "did the example ns-load register
;; against `:rf/default`?" — at the only point it is reliably observable: the
;; load itself. NB the deftest is what makes this assertion; this toplevel
;; merely records the evidence the way the example produced it.
(def ^:private example-app-schema-snapshot-at-load
  (into {}
        (map (fn [[ns-name path]]
               [ns-name {:on-default
                         (schemas/app-schema-at path {:frame :rf/default})
                         :on-unrelated
                         (schemas/app-schema-at path {:frame :test/never-registered})}]))
        example-app-schema-paths))

(deftest example-ns-load-schemas-bound-to-rf-default-not-an-ambient-pin
  (testing "rf2-9wc2ed — every affected example's ns-load `reg-app-schema`
            landed on `:rf/default` (its explicit-frame registration fired,
            captured at THIS ns's load), NOT on some ambient pin and NOT
            frameless. With seven_guis + nine_states having loaded at require
            time under NO `with-frame` fixture (the fixture below is `:each`,
            so it runs only around test bodies), a reverted bare ns-load
            `reg-app-schema` would have raised `:rf.error/no-frame-context`
            during those requires and aborted the whole `:node-test` bundle —
            so reaching this assertion at all already proves the loads were
            frame-scoped; the snapshot pins WHICH frame they targeted"
    (doseq [[ns-name path] example-app-schema-paths]
      (let [{:keys [on-default on-unrelated]}
            (get example-app-schema-snapshot-at-load ns-name)]
        (is (some? on-default)
            (str ns-name " registered its app-schema " (pr-str path)
                 " against :rf/default at ns-load (the with-frame fix fired)"))
        ;; And it did NOT bleed onto an unrelated frame — :rf/default
        ;; specifically is where the example's commits validate.
        (is (nil? on-unrelated)
            (str ns-name "'s schema is frame-SCOPED — absent on an unrelated frame"))))))

(deftest bare-reg-app-schema-without-scope-raises-no-frame-context
  (testing "rf2-9wc2ed (load-bearing guard) — under THIS test's no-ambient
            scope a bare `reg-app-schema` (no frame, no `:frame` opt — exactly
            what a reverted ns-load registration would do) raises
            `:rf.error/no-frame-context`. This proves the guard the examples'
            `with-frame` wrappers exist to satisfy is LIVE right here — so a
            reverted bare ns-load call would have thrown at this ns's require
            and aborted the bundle, never reaching a green assertion"
    (is (nil? (frame/current-frame))
        "precondition: no ambient frame (the pin is removed)")
    (let [ex (try
               (rf/reg-app-schema [:regression/bare] [:map])
               nil
               (catch :default e e))]
      (is (some? ex)
          "a bare frameless reg-app-schema throws (it does not silently
           default to a synthesised :rf/default)")
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
          "the throw is the EP-0002 no-frame-context error — the exact
           failure mode the examples' explicit-frame ns-load registration
           avoids")
      ;; The bad call must NOT have landed an entry anywhere.
      (is (nil? (schemas/app-schema-at [:regression/bare] {:frame :rf/default}))
          "the rejected registration left no entry on :rf/default"))))

;; ============================================================================
;; 3. RECORDABLE-COFX REPLAY-STABILITY  (rf2-wbgb74 / EP-0017)
;; ============================================================================

;; nine_states — the new todo's durable `:id` is folded from the
;; `:new-todo/todo-id` recordable cofx, written into the machine's `:data
;; :items` (durable runtime-db state) and used as a React `:key`.

(defn- run-new-todo-submit!
  "Boot a fresh nine-states frame (machine spawned via `:initialise`), seed a
  valid draft, then dispatch `:new-todo/submit` with `recorded` as the
  `:rf.cofx` token under `:rf.cofx/mint-policy :strict`. Returns the durable
  item id the handler folded in."
  [recorded]
  (let [f (frame/make-anon-frame-record! {:doc          "nine-states replay frame"
                          :fx-overrides {:rf.http/managed
                                         :nine-states.http/managed-demo}})]
    ;; `:initialise` seeds the form slice + resets/spawns the :ui/nine-states
    ;; machine snapshot into runtime-db (the submit reads its :data :items).
    (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
    ;; A title >= 3 chars passes `validate-new-todo`, so the handler takes the
    ;; valid branch that folds the recordable id into a new item.
    (rf/dispatch-sync [:new-todo/edit-field :title "Buy milk"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit]
                      {:frame               f
                       :rf.cofx             recorded
                       :rf.cofx/mint-policy :strict})
    ;; Read the durable item id back off the machine snapshot.
    (let [items (rf/compute-sub [:todos/items] (rf/frame-state-value f))]
      (-> items first :id))))

(deftest nine-states-new-todo-id-is-replay-stable-under-recorded-cofx
  (testing "rf2-wbgb74 / EP-0017 — re-running `:new-todo/submit` with the SAME
            recorded `:new-todo/todo-id` token (+ mint-policy :strict)
            reproduces the SAME durable item id; an ambient write-site
            `(random-uuid)` would have diverged run-to-run"
    (let [token #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
          recorded {:new-todo/todo-id token}
          id-1 (run-new-todo-submit! recorded)
          id-2 (run-new-todo-submit! recorded)]
      (is (= token id-1)
          "run 1 folded the TOKEN's id into the durable item (not a fresh
           ambient draw)")
      (is (= token id-2)
          "run 2 re-presented the SAME recorded id verbatim under :strict")
      (is (= id-1 id-2)
          "two runs of the same token produce IDENTICAL durable ids —
           replay-stable (the EP-0017 contract)"))))

;; realworld — the optimistic comment temp-id is folded from the
;; `:realworld/temp-comment-id` recordable cofx, conj'd into `[:comments
;; :data]` (durable app-db) and used as the correlation join key onto the
;; eventual save / rollback.

(defn- run-comment-form-submit!
  "Boot a fresh realworld frame, seed a non-blank comment draft + a stub auth
  user, then dispatch `:comment-form/submit` with `recorded` as the
  `:rf.cofx` token under `:rf.cofx/mint-policy :strict`. The managed-HTTP fx
  the success path fires is stubbed to a no-op so no backend is needed.
  Returns the durable optimistic card id the handler folded in."
  [recorded]
  (let [f (frame/make-anon-frame-record! {:doc "realworld replay frame"})]
    ;; Stub the managed-HTTP fx so the optimistic-post fx fires harmlessly
    ;; (the durable write under test is the temp card conj'd into
    ;; [:comments :data]; the request itself is irrelevant here).
    (rf/reg-fx :realworld-replay/noop-http (fn [_ _] nil))
    ;; Seed a non-blank draft + a user so the handler takes the valid branch.
    (rf/dispatch-sync [:comment-form/edit-field :body "Great article!"]
                      {:frame f})
    (rf/with-fx-overrides {:rf.http/managed :realworld-replay/noop-http}
      (rf/dispatch-sync [:comment-form/submit]
                        {:frame               f
                         :rf.cofx             recorded
                         :rf.cofx/mint-policy :strict}))
    (-> (rf/app-db-value f) :comments :data first :id)))

(deftest realworld-comment-temp-id-is-replay-stable-under-recorded-cofx
  (testing "rf2-wbgb74 / EP-0017 — re-running `:comment-form/submit` with the
            SAME recorded `:realworld/temp-comment-id` token (+ mint-policy
            :strict) reproduces the SAME optimistic-card id (the join key);
            an ambient write-site temp-id would have diverged run-to-run"
    (let [token "temp-018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
          recorded {:realworld/temp-comment-id token}
          id-1 (run-comment-form-submit! recorded)
          id-2 (run-comment-form-submit! recorded)]
      (is (= token id-1)
          "run 1 folded the TOKEN's temp-id into the optimistic card (not a
           fresh ambient draw)")
      (is (= token id-2)
          "run 2 re-presented the SAME recorded temp-id verbatim under :strict")
      (is (= id-1 id-2)
          "two runs of the same token produce IDENTICAL optimistic-card ids —
           the join key is replay-stable (the EP-0017 contract)"))))
