(ns re-frame.resources-infinite-load-more-cljs-test
  "Runtime behaviour for the infinite-feed `:rf.resource/load-more` event +
  page reply handlers + the R6 refetch reset (EP-0021 wave 3, Spec 016
  §Infinite resources and load-more feeds).

  Wave 2 landed the PURE entry transitions (`empty-infinite-entry` /
  `next-param-for` / `entry-append-page` / `entry-page-failed` / …); this
  slice wires them to the EVENT layer:

    1. `:rf.resource/ensure` on an infinite resource fetches PAGE 0 only
       (page ctx `{:rf.resource/page-param nil :rf.resource/page-index 0}`),
       seeds an `empty-infinite-entry`, and addresses the PAGE reply handlers;
    2. `:rf.resource/load-more` derives the next page param from the tail,
       issues the next page (index = page-count), and APPENDS on success +
       advances the cursor;
    3. a TERMINAL feed (`:next-page-param` nil) load-more is a no-op;
    4. a load-more while a page fetch is in flight DEDUPES (no second request);
    5. a stale / superseded page reply is SUPPRESSED (never appends to a newer
       feed);
    6. a page-fetch failure is the THIRD error channel — the feed keeps its
       pages + records `:page-error` (NOT `:error` / `:refresh-error`);
    7. `:rf.resource/refetch` preserves the visible window by default (R6 —
       refresh page 0 in place); the `:refetch-all-pages?` / `:refetch-window`
       opt-ins refresh a MULTI-PAGE window IN SEQUENCE (rf2-byl7bk.3.3 — a
       chained sweep, one leg at a time, replacing each page in place; the
       accumulation is never truncated).

  The capturing transport REPLAYS the real reply-append shape (Spec 014 §Reply
  addressing — the live transport conj's its result as the LAST arg of the
  internal reply event), so the page reply handlers run against the genuine
  3-element event. The subscription family is wave 4 (out of scope)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the fetch itself is overridden by the capturing reply stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport that REPLAYS the real reply-append shape ----------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (rf/runtime-db-value frame-id)))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- reply-success!
  "Dispatch the captured `:on-success` reply with the transport's success
  result appended as the LAST arg — the live managed-HTTP transport shape."
  ([data] (reply-success! @last-managed-args data))
  ([args data]
   (rf/dispatch-sync (conj (:on-success args) {:kind :success :value data}))))

(defn- reply-failure!
  ([failure] (reply-failure! @last-managed-args failure))
  ([args failure]
   (rf/dispatch-sync (conj (:on-failure args) {:kind :failure :failure failure}))))

(def ^:private next-cursor
  "Read the next cursor off a page's `:page-info` envelope; nil ⇒ terminal."
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page
  "An enveloped page: items + a page-info cursor envelope."
  ([items next-c] (page items next-c nil))
  ([items next-c prev-c]
   {:items items :page-info {:next-cursor next-c :prev-cursor prev-c}}))

(defn- feed-spec
  "A minimal valid infinite-feed resource spec (global scope, a :filter param,
  cursor pagination via :page-info)."
  ([] (feed-spec {}))
  ([overrides]
   (merge {:scope            :rf.scope/global
           :infinite         true
           :params-schema    [:map [:filter :keyword]]
           :next-page-param  next-cursor
           :prev-page-param  prev-cursor
           :page->items      :items
           :tags             (fn [{:keys [filter]} _data] #{[:feed filter]})}
          overrides)))

(def ^:private feed-spec-request
  (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get :url "/api/feed"
               :params (cond-> {:filter filter :page-index page-index}
                         page-param (assoc :cursor page-param))}}))

(defn- feed-key [resource]
  (state/scoped-resource-key :rf.scope/global resource {:filter :recent}))

(defn- ensure! [resource]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent} :owner [:test :w]}]))

(defn- load-more! [resource]
  ;; The supported idiom (rf2-bi8vg1): a load-more is OWNERLESS — the feed's
  ;; liveness is the route/ensure owner's, never a per-page lease.
  (rf/dispatch-sync [:rf.resource/load-more
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent}
                      :cause [:user :feed/load-more]}]))

(defn- load-more-with-owner!
  "A load-more carrying a MISTAKEN `owner` (rf2-bi8vg1 — the unsupported,
  warn-and-ignored input). The feed identity (scope + resource + canonical
  params) is unchanged — only the stray owner differs from the route/ensure
  owner. A load-more is OWNERLESS by contract, so this dispatch is expected to
  emit `:rf.warning/resource-load-more-owner-ignored` and drop the owner."
  [resource owner]
  (rf/dispatch-sync [:rf.resource/load-more
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent} :owner owner
                      :cause [:user :feed/load-more]}]))

(defn- load-page-0!
  "Ensure (page-0) a feed and settle it with `pg`. Returns the scoped key."
  [resource pg]
  (rf/reg-resource resource (feed-spec) feed-spec-request)
  (ensure! resource)
  (reply-success! pg)
  (feed-key resource))

;; ===========================================================================
;; 1. ensure on an infinite resource fetches page 0 only (seed + page ctx)
;; ===========================================================================

(deftest ensure-infinite-seeds-feed-and-page-0-ctx
  (testing "ensure of an infinite resource seeds an empty infinite entry +
            fetches page 0 with the reserved page ctx (R8)"
    (rf/reg-resource :inf1/feed (feed-spec) feed-spec-request)
    (ensure! :inf1/feed)
    (let [e (entry (feed-key :inf1/feed))]
      (is (state/infinite-entry? e) "seeded an infinite entry (R1)")
      (is (= :loading (:status e)) "first load (no data) is :loading")
      (is (= [] (:data e)) "page vector still empty (page-0 in flight)"))
    (testing "the request received the page-0 ctx {:page-param nil :page-index 0}"
      (let [req-params (get-in @last-managed-args [:request :params])]
        (is (= 0 (:page-index req-params)) "page-index 0")
        (is (not (contains? req-params :cursor)) "page-0 cursor is nil")))
    (testing "the reply is addressed at the PAGE reply handler (not the scalar)"
      (is (= :rf.resource.internal/page-succeeded (first (:on-success @last-managed-args))))
      (is (= :rf.resource.internal/page-failed (first (:on-failure @last-managed-args)))))))

(deftest ensure-page-0-success-appends
  (testing "a page-0 success appends to :data + advances the cursor + :loaded"
    (let [k (load-page-0! :inf2/feed (page [:a :b] "c1"))
          e (entry k)]
      (is (= :loaded (:status e)))
      (is (= [(page [:a :b] "c1")] (:data e)) "page-0 accumulated")
      (is (= [nil] (:page-params e)) "page-0 param is nil")
      (is (= "c1" (:next-page-param e)) "cursor advanced from the page")
      (is (= 1 (state/page-count e))))))

;; ===========================================================================
;; 2. load-more appends + advances the cursor (the headline behaviour)
;; ===========================================================================

(deftest load-more-appends-and-advances-cursor
  (testing "load-more on a loaded feed fetches the NEXT page (derived param) +
            transitions to :fetching, then appends on success (R2)"
    (let [k (load-page-0! :lm/feed (page [:a] "c1"))]
      (load-more! :lm/feed)
      (let [e (entry k)]
        (is (= :fetching (:status e)) "feed has data → refresh-class :fetching")
        (is (= 1 (state/page-count e)) "page vector unchanged while in flight (pages stay visible)"))
      (testing "the load-more request carried the derived next param + index 1"
        (let [req-params (get-in @last-managed-args [:request :params])]
          (is (= 1 (:page-index req-params)))
          (is (= "c1" (:cursor req-params)) "the cursor is the page-0-derived next param")))
      (reply-success! (page [:b] "c2"))
      (let [e (entry k)]
        (is (= :loaded (:status e)))
        (is (= [(page [:a] "c1") (page [:b] "c2")] (:data e)) "appended in order")
        (is (= [nil "c1"] (:page-params e)) "param per page, page-0 = nil")
        (is (= "c2" (:next-page-param e)) "cursor advanced to page-1's next")
        (is (= 2 (state/page-count e)))))))

(deftest load-more-multiple-pages-accumulate
  (testing "successive load-more accumulate the feed in order"
    (let [k (load-page-0! :lm3/feed (page [:a] "c1"))]
      (load-more! :lm3/feed) (reply-success! (page [:b] "c2"))
      (load-more! :lm3/feed) (reply-success! (page [:c] "c3"))
      (let [e (entry k)]
        (is (= 3 (state/page-count e)))
        (is (= [(page [:a] "c1") (page [:b] "c2") (page [:c] "c3")] (:data e)))
        (is (= [nil "c1" "c2"] (:page-params e)))
        (is (= "c3" (:next-page-param e)))))))

(deftest load-more-recomputes-prev-mirror
  (testing "append re-derives :prev-page-param from the head (R7 mirror)"
    (let [k (load-page-0! :lmp/feed (page [:a] "c1" "p-head"))]
      (load-more! :lmp/feed)
      (reply-success! (page [:b] "c2" "p-tail"))
      (is (= "p-head" (:prev-page-param (entry k))) "prev comes from the FIRST page"))))

;; ===========================================================================
;; 3. terminal-nil load-more is a no-op (R2)
;; ===========================================================================

(deftest load-more-terminal-is-noop
  (testing "load-more on a feed whose next-param is nil fires NO request (R2)"
    ;; page-0 has a nil next-cursor → terminal
    (let [k (load-page-0! :term/feed (page [:a] nil))]
      (is (nil? (:next-page-param (entry k))) "feed is terminal")
      (reset! last-managed-args nil)
      (load-more! :term/feed)
      (is (nil? @last-managed-args) "no request issued on a terminal load-more")
      (let [e (entry k)]
        (is (= :loaded (:status e)) "feed unchanged (still :loaded)")
        (is (= 1 (state/page-count e)) "no page appended")
        (is (nil? (:current-work e)) "no work record created")))))

(deftest load-more-no-feed-is-noop
  (testing "load-more before page-0 exists is a no-op (the first page is ensure's)"
    (rf/reg-resource :nf/feed (feed-spec) feed-spec-request)
    (reset! last-managed-args nil)
    (load-more! :nf/feed)
    (is (nil? @last-managed-args) "no request when there is no accumulated feed")
    (is (nil? (entry (feed-key :nf/feed))) "no entry conjured by a load-more")))

;; ===========================================================================
;; 4. concurrent load-more dedupes against the in-flight page (R2)
;; ===========================================================================

(deftest concurrent-load-more-dedupes
  (testing "a second load-more while one is in flight JOINS — no second request,
            no new generation (R2 dedupe)"
    (let [k (load-page-0! :dd/feed (page [:a] "c1"))]
      (load-more! :dd/feed)
      (let [e1   (entry k)
            wid1 (:current-work e1)
            gen1 (:generation e1)
            args1 @last-managed-args]
        (is (= :fetching (:status e1)))
        (reset! last-managed-args nil)
        ;; a second load-more while the first is still in flight
        (load-more! :dd/feed)
        (let [e2 (entry k)]
          (is (nil? @last-managed-args) "no second request fired (deduped)")
          (is (= gen1 (:generation e2)) "no new generation on dedupe")
          (is (= wid1 (:current-work e2)) "same in-flight work record"))
        ;; the single in-flight page settles ONCE → exactly one append
        (reply-success! args1 (page [:b] "c2"))
        (let [e3 (entry k)]
          (is (= 2 (state/page-count e3)) "exactly one page appended despite two load-mores")
          (is (= [(page [:a] "c1") (page [:b] "c2")] (:data e3))))))))

;; ===========================================================================
;; 5. stale / superseded page reply is suppressed (mandatory boundary)
;; ===========================================================================

(deftest stale-page-reply-suppressed
  (testing "a late page reply carrying a superseded work-id / generation NEVER
            appends to the newer feed (Spec 016 §stale suppression)"
    (let [k (load-page-0! :st/feed (page [:a] "c1"))]
      ;; first load-more (generation N) — capture its in-flight args, do NOT reply
      (load-more! :st/feed)
      (let [args1 @last-managed-args
            wid1  (:current-work (entry k))
            gen1  (:generation (entry k))]
        ;; a refetch supersedes the in-flight load-more (forces a new generation)
        (rf/dispatch-sync [:rf.resource/refetch
                           {:resource :st/feed :scope :rf.scope/global
                            :params {:filter :recent} :cause [:test :supersede]}])
        (let [gen2 (:generation (entry k))]
          (is (not= gen1 gen2) "refetch forced a new generation")
          ;; the OLD load-more page reply lands late — it must be suppressed
          (reply-success! args1 (page [:STALE] "cX"))
          (let [e (entry k)]
            (is (not (some #(= % (page [:STALE] "cX")) (:data e)))
                "the stale page was NOT appended")
            (is (= gen2 (:generation e)) "entry generation unchanged by the stale reply"))
          (testing "the suppressed work row settles terminal :suppressed"
            (let [rec (work-ledger/get-record (runtime-db) wid1)]
              (is (= :suppressed (:status rec))))))))))

;; ===========================================================================
;; 6. page-fetch failure is the THIRD error channel (keep feed + :page-error)
;; ===========================================================================

(deftest page-failure-keeps-feed-records-page-error
  (testing "a load-more failure keeps ALL pages + records :page-error — NOT the
            first-load :error / whole-feed :refresh-error channel (R2)"
    (let [k        (load-page-0! :pf/feed (page [:a] "c1"))
          envelope {:kind :rf.http/server :status 503}]
      (load-more! :pf/feed)
      (reply-failure! envelope)
      (let [e (entry k)]
        (is (= :loaded (:status e)) "feed returns to :loaded (NOT :error)")
        (is (= 1 (state/page-count e)) "accumulated pages kept")
        (is (= [(page [:a] "c1")] (:data e)) "page vector untouched")
        (is (= "c1" (:next-page-param e)) "cursor untouched — retry is possible")
        (is (= envelope (:page-error e)) ":page-error recorded (third channel)")
        (is (nil? (:error e)) "NOT the first-load :error channel")
        (is (nil? (:refresh-error e)) "NOT the refresh :refresh-error channel")
        (is (nil? (:current-work e)) ":current-work cleared")))))

(deftest page-failure-recovers-on-next-success
  (testing "a successful load-more after a page failure clears :page-error"
    (let [k (load-page-0! :pfr/feed (page [:a] "c1"))]
      (load-more! :pfr/feed)
      (reply-failure! {:kind :rf.http/server :status 503})
      (is (some? (:page-error (entry k))) "failure recorded")
      ;; retry the load-more — it succeeds this time
      (load-more! :pfr/feed)
      (reply-success! (page [:b] "c2"))
      (let [e (entry k)]
        (is (nil? (:page-error e)) "the next success cleared the page-error")
        (is (= 2 (state/page-count e)) "the retried page appended")))))

;; ===========================================================================
;; 7. refetch reset — R6 window-preserving default + opt-ins
;; ===========================================================================

(defn- accumulate-3! [resource spec-overrides]
  "Register + load page 0 + two load-mores → a 3-page feed. Returns key."
  (rf/reg-resource resource (feed-spec spec-overrides) feed-spec-request)
  (ensure! resource) (reply-success! (page [:a] "c1"))
  (load-more! resource) (reply-success! (page [:b] "c2"))
  (load-more! resource) (reply-success! (page [:c] "c3"))
  (feed-key resource))

(deftest refetch-preserves-window-by-default
  (testing "the ruled R6 DEFAULT preserves the visible window — a refetch keeps
            the accumulated pages visible (does NOT collapse to page 0) and
            replaces page-0 in place on success"
    (let [k (accumulate-3! :rw/feed {})]
      (is (= 3 (state/page-count (entry k))) "3 pages accumulated")
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :rw/feed :scope :rf.scope/global
                          :params {:filter :recent} :cause [:test :refresh]}])
      (let [e (entry k)]
        (is (= :fetching (:status e)) "refetch is refresh-class (data kept)")
        (is (= 3 (state/page-count e)) "WINDOW PRESERVED — feed NOT collapsed to page 0"))
      (testing "the replacement fetches page-0 (index 0, nil cursor)"
        (let [req-params (get-in @last-managed-args [:request :params])]
          (is (= 0 (:page-index req-params)))
          (is (not (contains? req-params :cursor)))))
      (reply-success! (page [:a*] "c1"))
      (let [e (entry k)]
        (is (= :loaded (:status e)))
        (is (= 3 (state/page-count e)) "still 3 pages after the replacement succeeds")
        (is (= (page [:a*] "c1") (nth (:data e) 0)) "page-0 replaced in place")
        (is (= (page [:b] "c2") (nth (:data e) 1)) "tail preserved")
        (is (= (page [:c] "c3") (nth (:data e) 2)) "tail preserved")))))

(deftest refetch-all-pages-opt-in-refreshes-every-page-in-sequence
  ;; rf2-byl7bk.3.3 — :refetch-all-pages? re-fetches EVERY accumulated page
  ;; param IN SEQUENCE (TanStack parity), replacing each in place. The feed
  ;; never collapses — its length is preserved; one fetch is in flight at a
  ;; time (the chained sweep). This INVERTS the prior truncate-to-page-0 test.
  (testing ":refetch-all-pages? sweeps page 0, then page 1, then page 2 in order"
    (let [k (accumulate-3! :ra/feed {:refetch {:refetch-all-pages? true}})]
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :ra/feed :scope :rf.scope/global
                          :params {:filter :recent} :cause [:test :refresh-all]}])
      (testing "the issue-time fetch is page 0; the feed is NOT truncated"
        (is (= 3 (state/page-count (entry k))) "WINDOW PRESERVED — feed not collapsed")
        (is (= 0 (:rf.resource/page-index (second (:on-success @last-managed-args))))
            "the first fetch is page 0 (index 0)"))
      ;; page-0 reply replaces in place, then CHAINS the page-1 leg.
      (reply-success! (page [:a*] "c1"))
      (let [e (entry k)]
        (is (= 3 (state/page-count e)) "still 3 pages after page-0 replacement")
        (is (= (page [:a*] "c1") (nth (:data e) 0)) "page-0 replaced in place"))
      (testing "the sweep CHAINED a page-1 fetch (the next leg, in sequence)"
        (is (= 1 (:rf.resource/page-index (second (:on-success @last-managed-args))))
            "the second fetch is page 1")
        (is (= "c1" (:rf.resource/page-param (second (:on-success @last-managed-args))))
            "page 1 re-fetched with its original :page-param"))
      ;; page-1 reply replaces in place, then chains page-2.
      (reply-success! (page [:b*] "c2"))
      (let [e (entry k)]
        (is (= 3 (state/page-count e)))
        (is (= (page [:b*] "c2") (nth (:data e) 1)) "page-1 replaced in place"))
      (testing "the sweep CHAINED a page-2 fetch (the final leg)"
        (is (= 2 (:rf.resource/page-index (second (:on-success @last-managed-args))))
            "the third fetch is page 2"))
      ;; page-2 reply replaces in place — the sweep is now exhausted.
      (reply-success! (page [:c*] "c3"))
      (let [e (entry k)]
        (is (= 3 (state/page-count e)) "all 3 pages refreshed; length preserved")
        (is (= [(page [:a*] "c1") (page [:b*] "c2") (page [:c*] "c3")] (:data e))
            "every page replaced in place, in order")
        (is (not (contains? e :refetch-sweep)) "the sweep cursor is cleared when exhausted")
        (is (= :loaded (:status e)) "feed settled :loaded")))))

(deftest refetch-window-opt-in-refreshes-the-bounded-window-in-sequence
  ;; rf2-byl7bk.3.3 — :refetch-window n refreshes the first n pages in place
  ;; (page 0 + the chained legs up to n-1), keeping pages beyond n untouched.
  (testing ":refetch-window 2 refreshes pages 0 and 1, leaves page 2 alone"
    (let [k (accumulate-3! :rwn/feed {:refetch {:refetch-window 2}})]
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :rwn/feed :scope :rf.scope/global
                          :params {:filter :recent} :cause [:test :window]}])
      (is (= 3 (state/page-count (entry k))) "feed NOT truncated — full window preserved")
      (is (= 0 (:rf.resource/page-index (second (:on-success @last-managed-args)))) "page 0 first")
      (reply-success! (page [:a*] "c1"))
      (testing "the sweep chained the page-1 leg (the bounded window)"
        (is (= 1 (:rf.resource/page-index (second (:on-success @last-managed-args)))) "then page 1"))
      (reply-success! (page [:b*] "c2"))
      (let [e (entry k)]
        (is (= 3 (state/page-count e)) "feed length preserved (page 2 kept untouched)")
        (is (= (page [:a*] "c1") (nth (:data e) 0)) "page 0 refreshed")
        (is (= (page [:b*] "c2") (nth (:data e) 1)) "page 1 refreshed")
        (is (= (page [:c] "c3") (nth (:data e) 2)) "page 2 left UNTOUCHED (outside the window)")
        (is (not (contains? e :refetch-sweep)) "sweep cleared (window exhausted)")))))

(deftest refetch-sweep-failure-stops-the-sweep-keeps-pages
  ;; rf2-byl7bk.3.3 — a page failure DURING a sweep stops the chain (clears the
  ;; cursor) and records :page-error, keeping all accumulated pages.
  (testing "a page-1 sweep-leg failure stops the sweep + records :page-error"
    (let [k (accumulate-3! :rsf/feed {:refetch {:refetch-all-pages? true}})]
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :rsf/feed :scope :rf.scope/global
                          :params {:filter :recent} :cause [:test :refresh-all]}])
      (reply-success! (page [:a*] "c1"))          ;; page 0 replaced ⇒ chains page 1
      (is (= 1 (:rf.resource/page-index (second (:on-failure @last-managed-args)))) "page-1 leg in flight")
      (reply-failure! {:kind :rf.http/server :status 503})
      (let [e (entry k)]
        (is (= :loaded (:status e)) "feed stays :loaded (pages kept)")
        (is (= 3 (state/page-count e)) "all pages preserved")
        (is (some? (:page-error e)) ":page-error recorded for the failed sweep leg")
        (is (not (contains? e :refetch-sweep)) "the sweep cursor is cleared — chain stopped")))))

;; ===========================================================================
;; 8. ensure dedupe / fresh-skip still applies to an infinite feed's page-0
;; ===========================================================================

(deftest ensure-infinite-fresh-skip-serves-cache
  (testing "a second ensure of a fresh loaded infinite feed serves cache (no
            new page-0 fetch) — the scalar fresh-skip applies unchanged"
    (let [k (load-page-0! :fs/feed (page [:a] "c1"))
          gen0 (:generation (entry k))]
      (reset! last-managed-args nil)
      (ensure! :fs/feed)
      (is (nil? @last-managed-args) "fresh loaded feed served from cache, no refetch")
      (is (= gen0 (:generation (entry k))) "no new generation")
      (is (= 1 (state/page-count (entry k))) "feed untouched"))))

;; ===========================================================================
;; 9. rf2-bi8vg1 — a load-more given a MISTAKEN (non-route) owner is
;;    WARN-AND-IGNORED (the ruled guard, NOT the earlier characterization).
;; ===========================================================================
;;
;; A `:rf.resource/load-more` is OWNERLESS by contract (EP-0021): the feed's
;; liveness is the ROUTE owner's (the route that ensured page 0), and a
;; load-more is a user-caused page extension during that route's lifetime, NOT
;; a new lease. rf2-d095i1 first proved the page reply is NOT dropped (the
;; scoped key / work-id / reply payload never carry the owner; live-entry-for-
;; reply matches on :rf.frame/id + :work/id + :generation) — but characterized
;; a REAL failure mode: a stray owner attached a SECOND durable lease to the
;; feed (:active-owners + the derived :owner-index), silently extending its
;; liveness / GC lifetime until an explicit :rf.resource/release-owner.
;;
;; rf2-bi8vg1 RULED that footgun WARN-AND-IGNORE (Option 1a — reject ANY owner,
;; not a conflict predicate): a non-nil :owner on a load-more is recognised-
;; but-unhonourable input, so the runtime emits a loud, recoverable WARNING
;; (:rf.warning/resource-load-more-owner-ignored, per Conventions §No silent
;; swallow — a WARNING, not an error, because the cascade continues safely),
;; NORMALIZES the owner to nil (it reaches NEITHER :active-owners, the
;; :owner-index, NOR the work record), and STILL fetches + appends the page.
;; :cause is untouched (attribution preserved). These tests assert that guard:
;; the warning fires, no lease leaks, and the page still appends in order.

(defn- record-resource-traces!
  "Run `body-fn` with a trace listener installed; return the vector of every
  trace event whose :operation is a `:rf.resource/*` OR a `:rf.warning/*` op
  emitted during it (in capture order). The listener is unregistered in a
  `finally`. (The owner-ignored diagnostic is a `:rf.warning/*`-namespaced op,
  not a `:rf.resource/*` lifecycle op, so both namespaces are captured.)"
  [body-fn]
  (let [seen (atom [])
        k    ::resource-trace-recorder]
    (trace-tooling/register-listener!
      k (fn [ev]
          (when (and (keyword? (:operation ev))
                     (contains? #{"rf.resource" "rf.warning"}
                                (namespace (:operation ev))))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- owner-ignored-warnings
  "The captured `:rf.warning/resource-load-more-owner-ignored` events."
  [traces]
  (filterv #(= :rf.warning/resource-load-more-owner-ignored (:operation %))
           traces))

(defn- owner-index-keys
  "The set of OWNERS currently in the derived :owner-index."
  []
  (-> (runtime-db) (get-in (state/owner-index-path)) keys set))

(defn- owners-for-key
  "Owners in the :owner-index whose set contains this feed's key-id."
  [scoped-key]
  (let [kid (state/key-id scoped-key)]
    (->> (get-in (runtime-db) (state/owner-index-path))
         (filter (fn [[_owner key-ids]] (contains? key-ids kid)))
         (map key)
         set)))

(deftest load-more-mistaken-owner-WARNS-but-STILL-APPENDS-the-page
  ;; rf2-bi8vg1 (ruled WARN-AND-IGNORE) — a load-more carrying an owner DIFFERENT
  ;; from ensure's ([:wrong :owner] vs ensure's [:test :w]) emits a loud, RECOVER-
  ;; ABLE warning and STILL issues the request + appends: the owner is absent from
  ;; the scoped key, the work-id, and the reply verification (live-entry-for-reply),
  ;; so the user's data keeps loading.
  (testing "a load-more with a MISTAKEN owner WARNS, then still fires the request + appends"
    (let [k (load-page-0! :mo/feed (page [:a] "c1"))]
      (reset! last-managed-args nil)
      (let [traces (record-resource-traces!
                     #(load-more-with-owner! :mo/feed [:wrong :owner]))
            warns  (owner-ignored-warnings traces)]
        (testing "the recoverable warning fired at the point of the mistake"
          (is (= 1 (count warns))
              "exactly one :rf.warning/resource-load-more-owner-ignored")
          (let [w (first warns)]
            (is (= :warning (:op-type w)) "it is a WARNING (recoverable), not an error")
            ;; emit!'s third arg lands under :tags (per build-event)
            (is (= [:wrong :owner] (get-in w [:tags :owner]))
                "the warning names the offending owner")
            (is (some? (get-in w [:tags :hint]))
                "the warning carries a fix hint (remove :owner)"))))
      (is (some? @last-managed-args) "the load-more DID issue a page request (warn-and-PROCEED, not dropped)")
      (let [e (entry k)]
        (is (= :fetching (:status e)) "feed transitioned to :fetching (data kept)")
        (is (= "c1" (get-in @last-managed-args [:request :params :cursor]))
            "the request carried the page-0-derived next param — the ignored owner did not divert it"))
      ;; the page reply lands — verified by work-id + generation, NOT owner
      (reply-success! (page [:b] "c2"))
      (let [e (entry k)]
        (testing "the page APPENDED despite the ignored owner (warn-and-PROCEED)"
          (is (= :loaded (:status e)))
          (is (= 2 (state/page-count e)) "exactly the load-more page appended")
          (is (= [(page [:a] "c1") (page [:b] "c2")] (:data e)) "appended in order")
          (is (= "c2" (:next-page-param e)) "cursor advanced from the appended page"))))))

(deftest load-more-mistaken-owner-DROPS-the-stray-lease-NO-leak
  ;; rf2-bi8vg1 (ruled WARN-AND-IGNORE) — the guard that prevents the
  ;; rf2-d095i1 lease leak: a stray non-route owner is IGNORED. It is NORMALIZED
  ;; to nil before the entry update, so it reaches NEITHER :active-owners NOR the
  ;; derived :owner-index NOR the work record — :active-owners is UNCHANGED, no
  ;; second lease pins the feed, and the page still appends. :cause survives.
  (testing "page-0 ensure attaches ONLY the ensure owner"
    (let [k (load-page-0! :ml/feed (page [:a] "c1"))
          e (entry k)]
      (is (= #{[:test :w]} (:active-owners e))
          "before the mistaken load-more, only ensure's owner holds a lease")
      (is (= #{[:test :w]} (owners-for-key k))
          ":owner-index agrees — one owner for this key")
      (testing "a load-more with a MISTAKEN owner does NOT attach a second lease"
        (record-resource-traces!
          #(load-more-with-owner! :ml/feed [:wrong :owner]))
        (let [e' (entry k)]
          (is (not (contains? (:active-owners e') [:wrong :owner]))
              "the stray owner was DROPPED — NOT attached to :active-owners (no leak)")
          (is (contains? (:active-owners e') [:test :w])
              "the original ensure owner is still present")
          (is (= #{[:test :w]} (:active-owners e'))
              ":active-owners UNCHANGED — exactly one lease still holds the feed")))
      (testing "the dropped owner is absent from the derived :owner-index"
        (is (not (contains? (owner-index-keys) [:wrong :owner]))
            "the stray owner is NOT a key in :owner-index")
        (is (= #{[:test :w]} (owners-for-key k))
            ":owner-index lists only the original lease against this feed's key"))
      (testing "the work record carries NO owner (the in-flight page is ownerless)"
        (let [e' (entry k)
              rec (work-ledger/get-record (runtime-db) (:current-work e'))]
          (is (some? rec) "a work record exists for the in-flight load-more page")
          (is (empty? (:owners rec))
              "the work record's :owners is empty — the ignored owner never joined it")))
      (testing "no leak survives the page reply (the feed stays at one lease)"
        (reply-success! (page [:b] "c2"))
        (let [e' (entry k)]
          (is (= :loaded (:status e')) "feed settled :loaded")
          (is (= 2 (state/page-count e')) "the page still appended (warn-and-PROCEED)")
          (is (= #{[:test :w]} (:active-owners e'))
              "still exactly one lease after settle — no durable leak to release"))))))

(deftest load-more-mistaken-owner-PRESERVES-cause
  ;; rf2-bi8vg1 — :cause is UNTOUCHED by the owner-drop (attribution preserved):
  ;; the dropped owner does not strip the load-more's cause from the work record.
  (testing "the load-more's :cause survives the owner normalization"
    (let [k (load-page-0! :mc/feed (page [:a] "c1"))]
      (record-resource-traces!
        #(load-more-with-owner! :mc/feed [:wrong :owner]))
      (let [rec (work-ledger/get-record (runtime-db) (:current-work (entry k)))]
        (is (= [[:user :feed/load-more]] (:causes rec))
            "the load-more's :cause is recorded on the work record (owner dropped, cause kept)")))))

(deftest load-more-ownerless-emits-no-owner-ignored-warning
  ;; rf2-bi8vg1 — the bright-line guard fires ONLY on a supplied owner: a
  ;; correct, OWNERLESS load-more (the documented idiom) emits no warning.
  (testing "an ownerless load-more (the supported idiom) does NOT warn"
    (let [k (load-page-0! :ok/feed (page [:a] "c1"))
          traces (record-resource-traces!
                   #(rf/dispatch-sync [:rf.resource/load-more
                                       {:resource :ok/feed :scope :rf.scope/global
                                        :params {:filter :recent}
                                        :cause [:user :feed/load-more]}]))]
      (is (empty? (owner-ignored-warnings traces))
          "no :rf.warning/resource-load-more-owner-ignored for an ownerless load-more")
      (reply-success! (page [:b] "c2"))
      (let [e (entry k)]
        (is (= 2 (state/page-count e)) "the ownerless load-more appended normally")
        (is (= #{[:test :w]} (:active-owners e))
            "still exactly the route/ensure owner — load-more added no lease")))))
