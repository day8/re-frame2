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
    7. `:rf.resource/refetch` preserves the visible window by default (R6); the
       `:refetch-all-pages?` / `:refetch-window` opt-ins truncate the tail.

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
   [re-frame.http-managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
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
           :request          (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
                               {:request {:method :get :url "/api/feed"
                                          :params (cond-> {:filter filter :page-index page-index}
                                                    page-param (assoc :cursor page-param))}})
           :next-page-param  next-cursor
           :prev-page-param  prev-cursor
           :page->items      :items
           :tags             (fn [{:keys [filter]} _data] #{[:feed filter]})}
          overrides)))

(defn- feed-key [resource]
  (state/scoped-resource-key :rf.scope/global resource {:filter :recent}))

(defn- ensure! [resource]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent} :owner [:test :w]}]))

(defn- load-more! [resource]
  (rf/dispatch-sync [:rf.resource/load-more
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent} :owner [:test :w]
                      :cause [:user :feed/load-more]}]))

(defn- load-page-0!
  "Ensure (page-0) a feed and settle it with `pg`. Returns the scoped key."
  [resource pg]
  (rf/reg-resource resource (feed-spec))
  (ensure! resource)
  (reply-success! pg)
  (feed-key resource))

;; ===========================================================================
;; 1. ensure on an infinite resource fetches page 0 only (seed + page ctx)
;; ===========================================================================

(deftest ensure-infinite-seeds-feed-and-page-0-ctx
  (testing "ensure of an infinite resource seeds an empty infinite entry +
            fetches page 0 with the reserved page ctx (R8)"
    (rf/reg-resource :inf1/feed (feed-spec))
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
    (rf/reg-resource :nf/feed (feed-spec))
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
  (rf/reg-resource resource (feed-spec spec-overrides))
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

(deftest refetch-all-pages-opt-in-collapses-to-page-0
  (testing ":refetch-all-pages? opts OUT of window-preserving — the tail is
            dropped at refetch and the feed re-accumulates from page 0 (R6)"
    (let [k (accumulate-3! :ra/feed {:refetch {:refetch-all-pages? true}})]
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :ra/feed :scope :rf.scope/global
                          :params {:filter :recent} :cause [:test :refresh-all]}])
      (let [e (entry k)]
        (is (= 1 (state/page-count e)) "truncated to page 0 at refetch"))
      (reply-success! (page [:a*] "c1"))
      (let [e (entry k)]
        (is (= 1 (state/page-count e)) "feed re-accumulates from page 0")
        (is (= (page [:a*] "c1") (nth (:data e) 0)))))))

(deftest refetch-window-opt-in-bounds-the-kept-window
  (testing ":refetch-window n keeps the first n pages, drops beyond (R6)"
    (let [k (accumulate-3! :rwn/feed {:refetch {:refetch-window 2}})]
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :rwn/feed :scope :rf.scope/global
                          :params {:filter :recent} :cause [:test :window]}])
      (let [e (entry k)]
        (is (= 2 (state/page-count e)) "kept the first 2 pages, dropped the 3rd")
        (is (= [(page [:a] "c1") (page [:b] "c2")] (:data e))))
      (reply-success! (page [:a*] "c1"))
      (let [e (entry k)]
        (is (= 2 (state/page-count e)) "window held after the replacement")
        (is (= (page [:a*] "c1") (nth (:data e) 0)) "page-0 replaced in place")))))

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
