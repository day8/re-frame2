(ns re-frame.infinite-feed-example-cljs-test
  "Integration test: drives the infinite-feed example
   (`examples/reagent/infinite_feed/`) through the EP-0021 infinite-resource
   composition it teaches — route entry ensures PAGE 0 of the `:feed/timeline`
   infinite resource, the view reads the merged feed view-model passively via
   `[:rf.resource/infinite-state …]`, and accumulation is driven by the causal
   `:rf.resource/load-more` event. Closes the false-green gap the test-free
   examples policy (rf2-8cevm) leaves: `test:examples-compile` catches a missing
   namespace / init-fn and the resources artefact suites catch the generic
   infinite-resource runtime contract, but neither pins the EXAMPLE-SPECIFIC
   composition — its route `:resources` page-0 ensure, its enveloped
   `:page->items` flatten, its causal load-more wiring, the nil terminal, and
   the `:page-error` third channel as the view reads them.

   The fixture fns + the deterministic transport stub live HERE (the adapter
   test tree), not under examples/reagent/infinite_feed/ — the example source
   stays test-free per the locked policy. The ns requires the example's
   production source (`infinite-feed.core`) so its resource, routes, and views
   register at ns-load, then exercises them against a per-test `:rf/default`
   frame.

   DETERMINISM. The example's demo backend defers each canned reply 140 ms via
   `:after-ms` (`:dispatch-later`) so the load-more spinner is observable in the
   browser. A unit test wants synchronous settle, so each test installs its own
   `:rf.http/managed` override: a capturing no-op whose reply the test replays
   explicitly via the transport's real 3-element reply-event-append shape
   (`(conj on-success {:kind :success :value …})`) — the genuine shape the live
   managed-HTTP transport produces (Spec 014 §Reply addressing). Routing's URL
   push is stubbed so navigation is deterministic without a browser.

   Mirrors `re-frame.resources-example-cljs-test` (its sibling for the
   single-page resource example) for the ns-load snapshot/restore + trace-bus
   isolation discipline."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            ;; production HTTP + resources surfaces (so the resource runtime,
            ;; managed-HTTP lowering, the infinite sub family, and the
            ;; late-bound routing integration resolve); the actual fetch is
            ;; overridden by the capturing stub.
            [re-frame.http-managed]
            [re-frame.http-test-support]
            [re-frame.resources]
            [re-frame.resources.route :as resources-route]
            [re-frame.resources.state :as state]
            [re-frame.resources.test-support]
            [re-frame.routing :as routing]
            ;; the framework trace-ring buffer (Spec 009) — cleared around each
            ;; test so this resource-registering suite leaves no trace residue
            ;; for later cross-cutting tooling tests (same leak the resources-
            ;; example suite closes).
            [re-frame.trace.tooling :as trace-tooling]
            ;; the example's production source — registers its :feed/timeline
            ;; infinite resource, routes, and views at ns-load.
            [infinite-feed.core]))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(def ^:private last-managed-args (atom nil))

;; The shared `make-reset-runtime-fixture`'s post-dispose
;; `:resources/reset-resources!` hook CLEARS the `:resource` registrar kind
;; between tests. CLJS has no `(require … :reload)`, so snapshot the example's
;; ns-load `:resource` registration ONCE here (right after the
;; `infinite-feed.core` require above ran it) and re-install it in `init!`
;; (which runs AFTER the post-dispose hooks). This re-installs the EXACT example
;; registration — not a test-local copy. See the resources-example sibling for
;; the full rationale.
(def ^:private resource-kind-snapshot
  (get @registrar/kind->id->metadata :resource))

;; AT NS LOAD, remove THIS example's `:resource` registration (by id) from the
;; SHARED live registrar — after snapshotting it above. Reinstated per-test by
;; `init!`. Why: cljs.test loads every test ns into ONE bundle before running
;; ANY test, so without this our ns-load `reg-resource` sits in the global
;; registrar until some OTHER suite's reset clears it, mirroring a frameless
;; `:rf.registry/handler-cleared` burst into a cross-cutting tooling test's
;; cascade seed. We remove only OUR ids (not the whole kind).
(swap! registrar/kind->id->metadata update :resource
       (fn [m] (apply dissoc m (keys resource-kind-snapshot))))

(defn- init!
  "Per-test setup (after adapter install, registrar live). The infinite-feed
   example owns the URL through `:rf/default` (`:url-bound? true`), so
   re-register it that way, re-install the example's `:resource` registration
   the reset hook wiped, reset routing counters, re-publish the late-bound
   routing integration, and stub the managed-HTTP + url-push fx so route entry's
   page-0 ensure + navigation are deterministic without a fetch / browser."
  []
  (reset! last-managed-args nil)
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "infinite-feed-example default app frame."})
  (swap! registrar/kind->id->metadata assoc :resource resource-kind-snapshot)
  (routing/reset-counters!)
  (resources-route/install-routing-integration!)
  ;; Capturing no-op: the reply is replayed explicitly by the test so the
  ;; 3-element internal reply event matches what the live transport produces.
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- isolate-trace-bus-fixture
  "OUTER fixture: keep this resource-registering suite from leaking trace
   residue into later cross-cutting tooling tests (see the resources-example
   sibling for the full rationale of the frameless-handler-cleared burst)."
  [f]
  (trace-tooling/clear-listeners!)
  (trace-tooling/clear-trace-rings!)
  (f)
  (trace-tooling/clear-listeners!)
  (trace-tooling/clear-trace-rings!))

(use-fixtures :each
  isolate-trace-bus-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn init!}))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- runtime-db [] (rf/runtime-db-value :rf/default))

(defn- feed-key []
  (state/scoped-resource-key :rf.scope/global :feed/timeline {}))

(defn- entry [] (get-in (runtime-db) (state/entry-path (feed-key))))

(def ^:private feed-query
  {:resource :feed/timeline :scope :rf.scope/global :params {}})

(defn- feed-state
  "The passive infinite view-model the example's view reads, computed against
   :rf/default's frame-state (the same `[:rf.resource/infinite-state …]` sub)."
  []
  (rf/compute-sub [:rf.resource/infinite-state feed-query]
                  (rf/frame-state-value :rf/default)))

(defn- reply-success!
  "Replay the captured `:on-success` with the result appended as the LAST arg —
   the exact shape the live managed-HTTP transport produces (the PAGE reply
   handler for an infinite resource)."
  ([data] (reply-success! @last-managed-args data))
  ([args data]
   (rf/dispatch-sync (conj (:on-success args) {:kind :success :value data}))))

(defn- reply-failure!
  ([failure] (reply-failure! @last-managed-args failure))
  ([args failure]
   (rf/dispatch-sync (conj (:on-failure args) {:kind :failure :failure failure}))))

(defn- page
  "The enveloped page shape the example's demo backend produces:
   {:items [...] :page-info {:next-cursor …}} (nil next-cursor ⇒ terminal)."
  [items next-cursor]
  {:items items :page-info {:next-cursor next-cursor}})

(defn- load-more!
  "Dispatch the causal load-more the example's view dispatches — a `:cause`,
   NO `:owner` (the same shape as the button's on-click): the route already
   owns the feed for its lifetime, so load-more extends that one owned entry
   rather than minting a second owner."
  []
  (rf/dispatch-sync [:rf.resource/load-more
                     (assoc feed-query :cause [:user :feed/load-more])]))

;; ============================================================================
;; 1. ROUTE ENTRY ensures PAGE 0 of the infinite feed under the route owner
;; ============================================================================

(deftest timeline-route-entry-ensures-page-0-under-the-route-owner
  (testing "examples/reagent/infinite_feed — entering :infinite-feed.app/timeline
            ensures PAGE 0 of the :feed/timeline INFINITE resource under the route
            nav-token owner (not the whole accumulation); the view reads the
            passive infinite view-model and settles to the merged :items on the
            reply (the route CAUSES page 0; the view never asks)"
    (rf/dispatch-sync [:rf.route/navigate :infinite-feed.app/timeline])
    (let [slice     (get-in (runtime-db) [:rf.runtime/routing :current])
          nav-token (:nav-token slice)
          e         (entry)]
      (is (state/infinite-entry? e) "seeded an infinite feed entry (R1 — one entry per feed)")
      (is (= :loading (:status e)) "first load (page 0) → :loading")
      (is (contains? (:active-owners e) [:route :infinite-feed.app/timeline nav-token])
          "owned by the route nav-token owner [:route route-id nav-token]")
      (is (:loading? (feed-state)) ":loading? true while page 0 is in flight")
      ;; the page-0 request carries the reserved page ctx: index 0, nil cursor.
      (let [req-params (get-in @last-managed-args [:request :params])]
        (is (= 0 (:page-index req-params)) "page-0 index 0 (R8 reserved ctx)")
        (is (not (contains? req-params :cursor)) "page-0 cursor is nil"))
      ;; settle page 0 — the enveloped page flattens through :page->items.
      (reply-success! (page [{:id 0 :title "A"} {:id 1 :title "B"}] 2))
      (let [vm (feed-state)]
        (is (= :loaded (:status (entry))) "settles :loaded on the reply")
        (is (false? (:loading? vm)) "no longer loading")
        (is (true? (:has-data? vm)) "has usable data")
        (is (= [{:id 0 :title "A"} {:id 1 :title "B"}] (:items vm))
            "the merged :items is the flattened page-0 items (enveloped → :page->items)")
        (is (= 1 (:page-count vm)) "one page accumulated")
        (is (true? (:has-next-page? vm)) "next-cursor present ⇒ has-next? (the Load more button shows)")))))

;; ============================================================================
;; 2. LOAD-MORE is a causal event — appends the next page, advances the cursor
;; ============================================================================

(deftest load-more-appends-the-next-page-and-advances-the-cursor
  (testing "examples/reagent/infinite_feed — the causal :rf.resource/load-more
            (the button's on-click) derives the next page param from the loaded
            tail, fetches the next page, and APPENDS it to the merged :items; the
            view reads :fetching-next? while it is in flight (pages stay visible)"
    (rf/dispatch-sync [:rf.route/navigate :infinite-feed.app/timeline])
    (reply-success! (page [{:id 0 :title "A"}] 1))
    (is (= [{:id 0 :title "A"}] (:items (feed-state))) "page 0 loaded")
    (reset! last-managed-args nil)
    ;; the view dispatches load-more — runtime derives the next cursor from the tail.
    (load-more!)
    (let [vm (feed-state)]
      (is (true? (:fetching-next? vm)) ":fetching-next? true (the spinner shows)")
      (is (false? (:fetching? vm)) "a load-more is NOT a whole-feed refresh")
      (is (= [{:id 0 :title "A"}] (:items vm)) "the accumulated page stays visible (no skeleton)"))
    (let [req-params (get-in @last-managed-args [:request :params])]
      (is (= 1 (:page-index req-params)) "next page index 1")
      (is (= 1 (:cursor req-params)) "the cursor is the page-0-derived next param"))
    ;; settle the next page — it appends in order, cursor advances.
    (reply-success! (page [{:id 1 :title "B"}] 2))
    (let [vm        (feed-state)
          nav-token (get-in (runtime-db) [:rf.runtime/routing :current :nav-token])]
      (is (false? (:fetching-next? vm)) ":fetching-next? clears after the page lands")
      (is (= [{:id 0 :title "A"} {:id 1 :title "B"}] (:items vm))
          "the next page appended to the merged list in load order")
      (is (= 2 (:page-count vm)) "two pages accumulated as ONE entry")
      ;; load-more carried a :cause, NO :owner — the route nav-token owner is
      ;; still the SOLE liveness owner (load-more extends an already-owned feed).
      (is (= #{[:route :infinite-feed.app/timeline nav-token]}
             (:active-owners (entry)))
          "load-more added no owner; the route remains the sole liveness owner"))))

;; ============================================================================
;; 3. THE TERMINAL is nil — a nil next-cursor flips has-next? false (end-of-feed)
;; ============================================================================

(deftest nil-next-cursor-is-the-terminal-end-of-feed
  (testing "examples/reagent/infinite_feed — a page whose :page-info :next-cursor
            is nil is the SINGLE terminal: :has-next-page? flips false, so the
            view shows the end-of-feed marker instead of the Load more button, and
            a further load-more is a no-op (no request)"
    (rf/dispatch-sync [:rf.route/navigate :infinite-feed.app/timeline])
    ;; settle page 0 as a TERMINAL page (nil next-cursor).
    (reply-success! (page [{:id 0 :title "A"}] nil))
    (let [vm (feed-state)]
      (is (false? (:has-next-page? vm)) "nil next-cursor ⇒ has-next? false (end-of-feed)")
      (is (= [{:id 0 :title "A"}] (:items vm)) "the single page is shown"))
    (reset! last-managed-args nil)
    ;; the view would not render the button, but a stray load-more is a safe no-op.
    (load-more!)
    (is (nil? @last-managed-args) "a terminal load-more fires NO request")
    (is (= 1 (:page-count (feed-state))) "no page appended on a terminal load-more")))

;; ============================================================================
;; 4. THE THIRD ERROR CHANNEL — a load-more failure keeps the feed, sets :page-error
;; ============================================================================

(deftest load-more-failure-keeps-the-feed-and-surfaces-page-error
  (testing "examples/reagent/infinite_feed — a load-more page-fetch FAILURE keeps
            every accumulated page visible and surfaces :page-error (the THIRD
            channel — 'couldn't load more, retry'), NOT the first-load :error nor
            the whole-feed :refresh-error; a successful retry clears it"
    (rf/dispatch-sync [:rf.route/navigate :infinite-feed.app/timeline])
    (reply-success! (page [{:id 0 :title "A"}] 1))
    (load-more!)
    (reply-failure! {:kind :rf.http/server :status 503})
    (let [vm (feed-state)]
      (is (= {:kind :rf.http/server :status 503} (:page-error vm))
          ":page-error recorded (the third channel)")
      (is (nil? (:error vm)) "NOT the first-load :error channel")
      (is (nil? (:refresh-error vm)) "NOT the whole-feed :refresh-error channel")
      (is (= [{:id 0 :title "A"}] (:items vm)) "the accumulated page is KEPT (feed not lost)")
      (is (= :loaded (:status (entry))) "the feed returns to :loaded (couldn't-load-more, retry)"))
    ;; retry the load-more — it succeeds, clearing the page-error and appending.
    (load-more!)
    (reply-success! (page [{:id 1 :title "B"}] 2))
    (let [vm (feed-state)]
      (is (nil? (:page-error vm)) "the next successful load-more cleared :page-error")
      (is (= [{:id 0 :title "A"} {:id 1 :title "B"}] (:items vm)) "the retried page appended"))))

;; ============================================================================
;; 5. A PAGE 0 failure with no data surfaces via :page-error (the FULL error
;;    screen, split from the inline retry by :has-data?) — NOT the scalar :error
;; ============================================================================

(deftest page-0-failure-with-no-data-surfaces-via-page-error-not-scalar-error
  (testing "examples/reagent/infinite_feed — a PAGE 0 failure with no prior data
            surfaces via :page-error (an infinite feed's page fetches all lower
            the same way, so page 0's failure is the SAME channel as a load-more
            failure, keeping the feed :loaded; the scalar :error / :refresh-error
            stay nil). The example's view splits the full error screen from the
            inline retry by reading :has-data? alongside :page-error."
    (rf/dispatch-sync [:rf.route/navigate :infinite-feed.app/timeline])
    (reply-failure! {:kind :rf.http/server :status 500})
    (let [vm (feed-state)]
      (is (false? (:has-data? vm)) "no data loaded → the view shows the full error screen")
      (is (= {:kind :rf.http/server :status 500} (:page-error vm))
          "the page-0 failure surfaces via :page-error (a feed has one error axis)")
      (is (nil? (:error vm)) "the scalar first-load :error channel is unused by a feed")
      (is (nil? (:refresh-error vm)) "NOT the whole-feed :refresh-error channel either")
      (is (= :loaded (:status (entry))) "the feed entry stays :loaded (never the scalar :error state)"))))
