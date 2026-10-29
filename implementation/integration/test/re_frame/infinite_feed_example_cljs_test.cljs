(ns re-frame.infinite-feed-example-cljs-test
  "Integration test: drives the infinite-feed example
   (`examples/capabilities/resources/infinite_feed/`) through the EP-0021 infinite-resource
   composition it teaches — route entry ensures PAGE 0 of the `:feed/timeline`
   infinite resource, the view reads the merged feed view-model passively via
   `[:rf.resource/infinite-state …]`, and accumulation is driven by the causal
   `:rf.resource/load-more` event. Closes the false-green gap the test-free
   examples policy leaves: `test:examples-compile` catches a missing
   namespace / init-fn and the resources artefact suites catch the generic
   infinite-resource runtime contract, but neither pins the EXAMPLE-SPECIFIC
   composition — its route `:resources` page-0 ensure, its enveloped
   `:page->items` flatten, its causal load-more wiring, the nil terminal, and
   the `:page-error` third channel as the view reads them.

   The fixture and the deterministic transport stub live in the integration
   test tree, not under examples/capabilities/resources/infinite_feed/ — the example source
   stays test-free per the test-free-examples policy. The ns requires the example's
   production source (`infinite-feed.core`) so its resource, routes, and views
   register at ns-load, then exercises them against a per-test `:rf/default`
   frame.

   DETERMINISM. The example's demo backend answers each request after 140 ms
   so the load-more spinner is observable in the browser. A unit test wants synchronous settle, so each test installs its own
   `:rf.http/managed` override: a capturing no-op whose reply the test replays
   explicitly via the transport's real 3-element reply-event-append shape
   (`(conj on-success {:status :ok :value …})`) — the genuine shape the live
   managed-HTTP transport produces (Spec 014 §Reply addressing). Routing's URL
   push is stubbed so navigation is deterministic without a browser.

   The ns-load snapshot and per-test reinstall of the example's registrations,
   the trace-bus isolation and the frame-made-last order are
   `re-frame.integration-support/resource-example-harness`."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.integration-support :as rf.integration-support]
            [re-frame.views]
            ;; production HTTP + resources surfaces (so the resource runtime,
            ;; managed-HTTP lowering, the infinite sub family, and the
            ;; late-bound routing integration resolve); the actual fetch is
            ;; overridden by the capturing stub.
            [re-frame.http.managed]
            [re-frame.resources]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            ;; the example's production source — registers its :feed/timeline
            ;; infinite resource, routes, and views at ns-load.
            [infinite-feed.core]))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(def ^:private harness
  (rf.integration-support/resource-example-harness
    {:kinds     [:resource]
     :app-ns    "infinite-feed."
     :frame-doc "infinite-feed-example default app frame."}))

(def ^:private last-managed-args (:managed-args harness))

(use-fixtures :each (:trace-bus-fixture harness) (:reset-fixture harness))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- feed-key []
  (rf.resources.state/scoped-resource-key :rf.scope/global :feed/timeline {}))

(defn- entry [] (get-in (runtime-db) (rf.resources.state/entry-path (feed-key))))

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
   (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data}))))

(defn- reply-failure!
  ([failure] (reply-failure! @last-managed-args failure))
  ([args failure]
   (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure}))))

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
;; 1. LOAD-MORE is a causal event — appends the next page, advances the cursor
;; ============================================================================

(deftest load-more-appends-the-next-page-and-advances-the-cursor
  (testing "examples/capabilities/resources/infinite_feed — the causal :rf.resource/load-more
            (the button's on-click) derives the next page param from the loaded
            tail, fetches the next page, and APPENDS it to the merged :items; the
            view reads :fetching-next? while it is in flight (pages stay visible)"
    (rf/dispatch-sync [:rf.route/navigate {:to :infinite-feed.app/timeline}])
    (reply-success! (page [{:id 0 :title "A"}] 1))
    (reset! last-managed-args nil)
    ;; the view dispatches load-more — runtime derives the next cursor from the tail.
    (load-more!)
    (let [vm (feed-state)]
      (is (= [true false [{:id 0 :title "A"}]] ((juxt :fetching-next? :fetching? :items) vm))
          ":fetching-next? true (the spinner shows) but NOT a whole-feed refresh, and the accumulated page stays visible (no skeleton)"))
    (is (= [1 1] ((juxt :page-index :cursor) (get-in @last-managed-args [:request :params])))
        "next page index 1, with the page-0-derived next param as the cursor")
    ;; settle the next page — it appends in order, cursor advances.
    (reply-success! (page [{:id 1 :title "B"}] 2))
    (let [vm        (feed-state)
          nav-token (get-in (runtime-db) [:rf.runtime/routing :current :nav-token])]
      ;; load-more carried a :cause, NO :owner — the route nav-token owner is
      ;; still the SOLE liveness owner (load-more extends an already-owned feed).
      (is (= [false [{:id 0 :title "A"} {:id 1 :title "B"}] 2
              #{[:route :infinite-feed.app/timeline nav-token]}]
             [(:fetching-next? vm) (:items vm) (:page-count vm) (:active-owners (entry))])
          ":fetching-next? clears as the next page appends in load order, two pages accumulated as ONE entry, and the route remains the sole liveness owner"))))

;; ============================================================================
;; 2. THE TERMINAL is nil — a nil next-cursor flips has-next? false (end-of-feed)
;; ============================================================================

(deftest nil-next-cursor-is-the-terminal-end-of-feed
  (testing "examples/capabilities/resources/infinite_feed — a page whose :page-info :next-cursor
            is nil is the SINGLE terminal: :has-next-page? flips false, so the
            view shows the end-of-feed marker instead of the Load more button, and
            a further load-more is a no-op (no request)"
    (rf/dispatch-sync [:rf.route/navigate {:to :infinite-feed.app/timeline}])
    ;; settle page 0 as a TERMINAL page (nil next-cursor).
    (reply-success! (page [{:id 0 :title "A"}] nil))
    (let [vm (feed-state)]
      (is (= [false [{:id 0 :title "A"}]] ((juxt :has-next-page? :items) vm))
          "nil next-cursor ⇒ has-next? false (end-of-feed), with the single page shown"))
    (reset! last-managed-args nil)
    ;; the view would not render the button, but a stray load-more is a safe no-op.
    (load-more!)
    (is (= [nil 1] [@last-managed-args (:page-count (feed-state))])
        "a terminal load-more fires NO request and appends no page")))

;; ============================================================================
;; 3. THE THIRD ERROR CHANNEL — a load-more failure keeps the feed, sets :page-error
;; ============================================================================

(deftest load-more-failure-keeps-the-feed-and-surfaces-page-error
  (testing "examples/capabilities/resources/infinite_feed — a load-more page-fetch FAILURE keeps
            every accumulated page visible and surfaces :page-error (the THIRD
            channel — 'couldn't load more, retry'), NOT the first-load :error nor
            the whole-feed :refresh-error; a successful retry clears it"
    (rf/dispatch-sync [:rf.route/navigate {:to :infinite-feed.app/timeline}])
    (reply-success! (page [{:id 0 :title "A"}] 1))
    (load-more!)
    (reply-failure! {:kind :rf.http/server :http-status 503})
    (let [vm (feed-state)]
      (is (= [{:kind :rf.http/server :http-status 503} nil nil [{:id 0 :title "A"}] :loaded]
             [(:page-error vm) (:error vm) (:refresh-error vm) (:items vm) (:status (entry))])
          ":page-error recorded (the third channel), NOT the first-load :error or whole-feed :refresh-error; the accumulated page is KEPT and the feed returns to :loaded (couldn't-load-more, retry)"))
    ;; retry the load-more — it succeeds, clearing the page-error and appending.
    (load-more!)
    (reply-success! (page [{:id 1 :title "B"}] 2))
    (let [vm (feed-state)]
      (is (= [nil [{:id 0 :title "A"} {:id 1 :title "B"}]] ((juxt :page-error :items) vm))
          "the next successful load-more cleared :page-error and appended the retried page"))))

;; ============================================================================
;; 4. A PAGE 0 first-load failure with no data surfaces via the FIRST-LOAD
;;    :error channel (the full error screen) — NOT :page-error
;; ============================================================================

(deftest page-0-first-load-failure-surfaces-via-scalar-error-channel
  (testing "examples/capabilities/resources/infinite_feed — a PAGE 0 FIRST-load failure with no
            prior data settles the first-load :error channel + :status :error
            (Spec 016 reserves :error for first-load, :page-error for load-more
            ONLY). The example's view shows the full error screen on :error; the
            inline retry on :page-error — distinct axes."
    (rf/dispatch-sync [:rf.route/navigate {:to :infinite-feed.app/timeline}])
    (reply-failure! {:kind :rf.http/server :http-status 500})
    (let [vm (feed-state)]
      (is (= [false {:kind :rf.http/server :http-status 500} nil nil :error]
             [(:has-data? vm) (:error vm) (:page-error vm) (:refresh-error vm) (:status (entry))])
          "with no data loaded, the page-0 failure surfaces via the first-load :error channel (NOT :page-error, page 0 is not a load-more, nor :refresh-error) and the entry settles :status :error"))))
