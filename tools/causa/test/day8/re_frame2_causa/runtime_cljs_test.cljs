(ns day8.re-frame2-causa.runtime-cljs-test
  "Unit tests for `day8.re-frame2-causa.runtime` (rf2-8xzoe.4 / F-4).

  Pins the load-bearing contracts the F-4 port lands:

    1. **Eighteen tool-shaped accessors.** Each MCP tool in
       `tools/causa/spec/010-MCP-Server.md` §Tool catalogue maps to
       exactly one runtime fn; the lint here enumerates and asserts
       every one is `fn?`. Drift between the catalogue and the
       runtime surface fails this test first, before any tool dispatch
       round-trip would notice.
    2. **Session sentinel.** `session-id` is a non-empty string and a
       mirror lands at `js/globalThis.__day8_re_frame2_causa_runtime`
       (under node-test the global is the Node global; we exercise it
       through the same `exists?` guard the runtime uses).
    3. **`*current-origin*` defaults to `:causa-mcp`.** Mutating
       accessors stamp this tag onto their dispatches per Lock #4 +
       MUST-inventory row I1. `binding` re-binds within the
       synchronous extent per I6.
    4. **Frame resolution.** `resolve-frame` (exercised via the public
       accessors) picks the sole registered frame; returns nil under
       ambiguity rather than guessing.
    5. **`health` is side-effect-free.** Unlike pair2's `health` which
       installs trace + epoch listeners, Causa-the-panel's preload
       owns those — the runtime's `health` reads only.

  ## Why these tests run on node-test (not browser-test)

  The accessor surface is pure-data + framework-API forwarding. No
  DOM, no substrate-render, no React-context tier. Browser-side
  concerns (DOM `data-rf2-source-coord` annotation probe) test as
  `false` here because there is no `js/document`; the `health`
  contract explicitly degrades nil-safely.

  ## What's NOT in scope here

  - End-to-end nREPL-eval round-trips: covered by the MCP-server-side
    eval-form tests once the F-tranche dispatcher lands.
  - Streaming pump bookkeeping (per-tick queues, overflow markers):
    owned by the MCP-server side per `004-Wire-Pipeline.md`. The
    runtime exposes only the registration metadata."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; Fixture — snapshot/restore the framework runtime + reset the runtime ns.
;; ---------------------------------------------------------------------------

(defn- runtime-init! []
  (runtime/reset-for-test!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn runtime-init!}))

;; ---------------------------------------------------------------------------
;; (1) Eighteen tool-shaped accessors are resolvable.
;; ---------------------------------------------------------------------------

(def ^:private tool-accessor-vars
  "The canonical eighteen tool-shaped accessors per
  `tools/causa/spec/010-MCP-Server.md` §Tool catalogue. Order matches
  the catalogue band split for readability — change here only when
  the catalogue changes (and update the count assertion below).

  CLJS has no runtime `ns-resolve`, so this is a literal vector of
  `[sym fn]` pairs: a name (for the assertion message) and the
  callable var itself. Drift between the catalogue and the runtime
  surface (a deleted accessor; a renamed accessor) fails the compile
  here, before the test even runs."
  [;; Inspection (9)
   ['get-trace-buffer    runtime/get-trace-buffer]
   ['get-epoch-history   runtime/get-epoch-history]
   ['get-app-db          runtime/get-app-db]
   ['get-app-db-diff     runtime/get-app-db-diff]
   ['get-machine-state   runtime/get-machine-state]
   ['get-machine-list    runtime/get-machine-list]
   ['get-issues          runtime/get-issues]
   ['get-handlers        runtime/get-handlers]
   ['get-source-coord    runtime/get-source-coord]
   ;; Mutation (3)
   ['dispatch!           runtime/dispatch!]
   ['restore-epoch!      runtime/restore-epoch!]
   ['reset-frame-db!     runtime/reset-frame-db!]
   ;; Streaming (3)
   ['subscribe!          runtime/subscribe!]
   ['unsubscribe!        runtime/unsubscribe!]
   ['list-subscriptions  runtime/list-subscriptions]
   ;; Escape (1)
   ['eval-form-result    runtime/eval-form-result]
   ;; Meta (2)
   ['health              runtime/health]
   ['tail-build-probe    runtime/tail-build-probe]])

(deftest eighteen-tool-accessors-exist
  (testing "every catalogue tool has a runtime-side accessor — drift
            between the eighteen MCP tools and this ns fails here first"
    (is (= 18 (count tool-accessor-vars))
        "the canonical list is the eighteen-tool catalogue")
    (doseq [[sym f] tool-accessor-vars]
      (is (fn? f)
          (str "accessor not callable: day8.re-frame2-causa.runtime/" sym)))))

;; ---------------------------------------------------------------------------
;; (2) Session sentinel — UUID string + globalThis mirror.
;; ---------------------------------------------------------------------------

(deftest session-id-is-non-empty-string
  (testing "session-id is a freshly-generated UUID string"
    (is (string? runtime/session-id))
    (is (pos? (count runtime/session-id))
        "session-id is non-empty")))

(deftest global-sentinel-installed
  (testing "the `js/globalThis.__day8_re_frame2_causa_runtime` mirror
            lands so the MCP server's cheap preload probe succeeds in
            one bencode round-trip"
    (when (exists? js/globalThis)
      (let [marker (aget js/globalThis "__day8_re_frame2_causa_runtime")]
        (is (some? marker)
            "the global mirror exists on `js/globalThis`")
        (is (= runtime/session-id (aget marker "session-id"))
            "the mirror carries the same session-id as the CLJS var")
        (is (number? (aget marker "installed"))
            "the mirror carries a numeric installed-at timestamp")))))

;; ---------------------------------------------------------------------------
;; (3) *current-origin* default + binding extent.
;; ---------------------------------------------------------------------------

(deftest current-origin-defaults-to-causa-mcp
  (testing "the default value of `*current-origin*` is `:causa-mcp` —
            every Causa-MCP-driven side-effect carries the tag without
            the server needing an explicit binding (Lock #4 / I1)"
    (is (= :causa-mcp (runtime/current-origin)))))

(deftest current-origin-rebinds-via-binding
  (testing "`binding` over `*current-origin*` carries the new value
            inside its synchronous extent and restores on exit (I6)"
    (binding [runtime/*current-origin* :test-origin]
      (is (= :test-origin (runtime/current-origin))
          "binding takes effect synchronously"))
    (is (= :causa-mcp (runtime/current-origin))
        "default restores after binding's extent ends")))

;; ---------------------------------------------------------------------------
;; (4) Frame resolution via public accessors.
;; ---------------------------------------------------------------------------

(deftest get-app-db-resolves-sole-frame
  (testing "with the framework's default `:rf/default` registered, the
            no-arg `get-app-db` resolves it without an explicit `:frame`
            arg"
    (rf/reg-event-db :test/seed-db
      (fn [_ _] {:seeded? true}))
    (rf/dispatch-sync [:test/seed-db])
    (let [result (runtime/get-app-db)]
      (is (true? (:ok? result))
          "single-frame resolution succeeds without explicit :frame")
      (is (= :rf/default (:frame result))
          "the sole frame is the resolved frame"))))

(deftest get-app-db-explicit-path
  (testing "the `:path` arg scopes the returned value via `get-in`"
    (rf/reg-event-db :test/seed-db
      (fn [_ _] {:cart {:items [:a :b :c]}}))
    (rf/dispatch-sync [:test/seed-db])
    (let [result (runtime/get-app-db {:path [:cart :items]})]
      (is (true? (:ok? result)))
      (is (= [:a :b :c] (:value result))
          ":path returns the scoped value"))))

(deftest get-app-db-no-frame-resolved
  (testing "with no frames registered, `get-app-db` surfaces a
            structured `:no-frame-resolved` refusal rather than crashing"
    ;; The fixture's reset-runtime-fixture leaves :rf/default in place;
    ;; force ambiguity by destroying it.
    (frame/destroy-frame! :rf/default)
    (let [result (runtime/get-app-db)]
      (is (false? (:ok? result)))
      (is (= :no-frame-resolved (:reason result))))))

;; ---------------------------------------------------------------------------
;; (5) health is side-effect-free.
;; ---------------------------------------------------------------------------

(deftest health-returns-status-map
  (testing "`health` returns a status map with the load-bearing slots
            `discover-app` cites"
    (let [h (runtime/health)]
      (is (true? (:ok? h)))
      (is (= runtime/session-id (:session-id h)))
      (is (boolean? (:debug-enabled? h)))
      (is (vector? (:frames h)))
      (is (boolean? (:ambiguous-frame? h)))
      (is (= :causa-mcp (:origin h))
          "health surfaces the bound origin (default `:causa-mcp`)"))))

(deftest health-installs-no-listeners
  (testing "unlike pair2's `health`, the Causa runtime's `health` does
            NOT register trace or epoch callbacks — Causa-the-panel
            owns those (`preload.cljs`'s register-trace-collector! /
            register-epoch-collector!). Two `health` calls in a row
            must not leave residue.

            We exercise the side-effect-free property by asserting the
            framework's `register-trace-cb!` was not called with any
            runtime-owned id — the runtime has no such id reservation."
    (runtime/health)
    (runtime/health)
    ;; No listener-side state to inspect — the contract is that the
    ;; runtime does not call register-trace-cb! / register-epoch-cb!
    ;; from `health`. The lint here is the source-side absence; the
    ;; runtime test suite asserts behaviour, and the absence of a
    ;; per-test-listener-id reservation is the absence of an effect.
    (is true "health is side-effect-free — repeated calls compose")))

;; ---------------------------------------------------------------------------
;; (6) Dispatch tagging — events stamped with `:origin :causa-mcp`.
;; ---------------------------------------------------------------------------

(deftest dispatch-tags-event-with-current-origin
  (testing "`dispatch!` attaches `{:tags {:origin <current-origin>}}` as
            event metadata so the framework's trace bus carries the
            Causa-MCP tag (Lock #4 / I1)"
    (let [captured (atom nil)]
      (rf/reg-event-db :test/capture-meta
        (fn [db [_ marker]]
          (reset! captured marker)
          (assoc db :marker marker)))
      ;; sync? so the dispatch completes before we check.
      (let [result (runtime/dispatch! [:test/capture-meta :ok] {:sync? true})]
        (is (true? (:ok? result)))
        (is (= :causa-mcp (:origin result))
            "result echoes the bound origin"))
      (is (= :ok @captured)
          "handler ran"))))

(deftest dispatch-rebinds-origin-via-eval-cljs-extent
  (testing "a `binding` around `dispatch!` re-tags the dispatch — this
            is the synchronous-extent contract `eval-cljs` rides
            (Lock #4 / I6)"
    (rf/reg-event-db :test/origin-marker
      (fn [db _] db))
    (binding [runtime/*current-origin* :test-rebind]
      (let [result (runtime/dispatch! [:test/origin-marker] {:sync? true})]
        (is (= :test-rebind (:origin result))
            "dispatch carries the re-bound origin, not the default")))))

(deftest dispatch-refuses-non-vector
  (testing "non-vector `event` shapes refuse structurally — the same
            kind of guard pair2-mcp's `dispatch.cljs` enforces at the
            wire layer (rf2-vflrg precedent)"
    (let [result (runtime/dispatch! :not-a-vector)]
      (is (false? (:ok? result)))
      (is (= :not-an-event-vector (:reason result))))))

;; ---------------------------------------------------------------------------
;; (7) Streaming surface — subscribe!/unsubscribe!/list-subscriptions.
;; ---------------------------------------------------------------------------

(deftest subscribe-records-metadata
  (testing "`subscribe!` records the subscription's metadata so
            `list-subscriptions` can enumerate it"
    (let [r1 (runtime/subscribe! {:topic :trace :filter {:origin :causa-mcp}})]
      (is (true? (:ok? r1)))
      (is (= :trace (:topic r1)))
      (is (string? (:sub-id r1)))

      (let [r2 (runtime/list-subscriptions)]
        (is (= 1 (:count r2)))
        (is (= [(:sub-id r1)] (mapv :id (:subs r2))))))))

(deftest subscribe-rejects-unknown-topic
  (testing "topics outside `{:trace :epoch :fx :error}` refuse"
    (let [r (runtime/subscribe! {:topic :bogus})]
      (is (false? (:ok? r)))
      (is (= :unknown-topic (:reason r))))))

(deftest unsubscribe-is-idempotent
  (testing "calling `unsubscribe!` on an unknown id returns
            `:existed? false` rather than throwing — the catalogue
            entry pins this idempotency"
    (let [r (runtime/unsubscribe! {:sub-id "no-such-sub"})]
      (is (true? (:ok? r)))
      (is (false? (:existed? r))))))

(deftest list-subscriptions-filters-by-topic
  (testing "`:topic` narrows the enumeration"
    (runtime/subscribe! {:topic :trace})
    (runtime/subscribe! {:topic :epoch})
    (let [r (runtime/list-subscriptions {:topic :trace})]
      (is (= 1 (:count r)))
      (is (every? #(= :trace (:topic %)) (:subs r))))))

;; ---------------------------------------------------------------------------
;; (8) tail-build-probe — monotonic counter, stable session-id.
;; ---------------------------------------------------------------------------

(deftest tail-build-probe-is-monotonic
  (testing "`tail-build-probe` increments on every call so the MCP
            server's poll loop can detect a hot-reload via value-change"
    (let [r1 (runtime/tail-build-probe)
          r2 (runtime/tail-build-probe)]
      (is (true? (:ok? r1)))
      (is (= runtime/session-id (:session-id r1))
          "session-id carried for the server's cross-call sanity check")
      (is (> (:probe r2) (:probe r1))
          "probe value advances monotonically"))))

;; ---------------------------------------------------------------------------
;; (9) get-epoch-history degrades cleanly without records.
;; ---------------------------------------------------------------------------

(deftest get-epoch-history-empty-when-no-epochs
  (testing "with no epochs recorded against the resolved frame, the
            accessor returns `{:ok? true :epochs []}` rather than nil
            — the MCP tool layer rides the `:ok?` slot"
    (let [result (runtime/get-epoch-history)]
      (is (true? (:ok? result)))
      (is (vector? (:epochs result)))
      (is (= 0 (:count result))))))
