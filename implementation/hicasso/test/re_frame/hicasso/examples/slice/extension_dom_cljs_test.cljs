(ns re-frame.hicasso.examples.slice.extension-dom-cljs-test
  "L3 — PAGINATION, RUNTIME-SELECTED CONTENT AND A NESTED ERROR REGION,
  MOUNTED (rf2-hic-074).

  The three things specification §12 Phase 4 asks of the product
  application, on a real React root: a feed the user pages through, a
  screen region whose hiccup shape is chosen from data at render time,
  and an error region INSIDE the shell's own so that a block which
  breaks takes its region down and leaves the page around it working.

  `flow-dom-cljs-test` is the sibling this file assumes: it owns the
  edit flow, and the two conventions it established are kept here
  unchanged — a Hicasso intent dispatches synchronously so `hm/settle!`
  is all a click owes, while a ROUTE-LINK and an async reply both leave
  work merely enqueued and are waited on with
  `re-frame.test-support/poll-until`.

  ## What the keyed-identity rows can and cannot claim

  A page flip replaces every row in the list, so it is the sharpest
  available reading of whether the list's keys are domain ids. But this
  file measures BEHAVIOUR and claims nothing about enforcement: the
  substrate does not police the keys a body writes, and rf2-hic-074's PR
  #8026 finding is the general form of that — Hicasso's unkeyed-children
  warning cannot reach a child array a foreign component owns, so *the
  key is the model id* is a rule an author keeps and not one the runtime
  imposes. What the rows below assert is therefore what React did with
  the keys the body wrote: which elements survived a flip, and whose
  per-row state came back with it.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`),
  and each row degrades there to a STATED skip rather than to a false
  green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.slice.db :as db]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.hicasso.examples.slice.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a mounted page needs a real React DOM — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The MAP shape, because rows here are `async`: `cljs.test` refuses
     ;; an async test under a fn-form fixture and aborts the NAMESPACE —
     ;; and, as of 2026-08-12, every namespace the runner had not reached
     ;; yet along with it (rf2-u0j8).
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; The reset restores the registrar to a baseline
                      ;; captured before `routes` finished loading. See
                      ;; that namespace on why `register!` is exposed.
                      (routes/register!))}))

;; ---------------------------------------------------------------------------
;; Reading and driving the page
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))
(defn- nodes [m sel] (vec (array-seq (.querySelectorAll (:container m) sel))))
(defn- text [m sel] (some-> (node m sel) .-textContent))
(defn- texts [m sel] (mapv #(.-textContent %) (nodes m sel)))
(defn- attrs-of [m sel a] (mapv #(.getAttribute % a) (nodes m sel)))

(defn- click!
  "A real click on a Hicasso intent, and then a settle. The intent's own
  dispatch is synchronous, so the settle is all that is owed."
  [m sel]
  (.click (node m sel))
  (hm/settle! m))

(defn- read-sub [m query-v] (rf/subscribe-once query-v {:frame (:frame m)}))

(defn- drained
  "Wait for `pred`, then flush React — the ROUTER-DRAIN counterpart of
  `hm/settle!`, for the two places a click leaves work merely enqueued: a
  route-link's navigate, and an async reply."
  [m pred label]
  (-> (test-support/poll-until pred {:label label})
      (.then (fn [_] (hm/settle! m)))))

(defn- at-page!
  "The whole application, mounted on the feed. `n` is the `?page=` the
  URL carries; `nil` is a bare `/slice`, which is what a first visit is."
  ([] (at-page! nil))
  ([n]
   (hm/mount! [views/app {}]
              {:initial-events [[::events/seed]
                                [:rf.route/navigate
                                 (cond-> {:to routes/feed}
                                   (some? n) (assoc :query {:page n}))]]})))

(defn- go-to-page!
  "Navigate to page `n` through routing's own event — the runtime's
  SYNCHRONOUS door, which is what Back and Forward ultimately reach
  through the history listener a real application installs."
  [m n]
  (hm/dispatch-and-settle! m [:rf.route/navigate {:to routes/feed :query {:page n}}]))

(defn- finish
  "Tear down, assert this mount left nothing behind, and end the row."
  [m done]
  (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))

(defn- finish-after
  "End the row when `p` settles, reporting a rejected `p` as a failure
  rather than letting the deadline hang the run — and tearing the mount
  down either way."
  [p m done]
  (-> p
      (.catch (fn [e]
                (is false (str "the page never settled: "
                               (or (ex-message e) (str e)) " "
                               (pr-str (ex-data e))))))
      (.then (fn [_] (finish m done)))))

;; ---------------------------------------------------------------------------
;; 1 — one page of the feed, and a pager that names the rest
;; ---------------------------------------------------------------------------

(deftest the-feed-shows-one-page-and-the-pager-offers-the-others
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-page!)]
        (is (= ["Hicasso, briefly" "Intents are data" "Controlled, synchronously"]
               (texts m ".article-link"))
            "three of the seven — a bare /slice is page one, and it is
             `:query-defaults` that decided so rather than a view")

        (testing "the pager names every page, and the current one is not a link"
          (is (= 3 (count (nodes m ".pager-page"))))
          (is (= ["1" "2" "3"] (texts m ".pager-page")))
          (is (= "1" (text m ".pager-current")))
          (is (= "page" (.getAttribute (node m ".pager-current") "aria-current")))
          (is (= ["/slice?page=2" "/slice?page=3"] (attrs-of m ".pager-link" "href"))
              "and the hrefs are routing's own synthesis — `views` builds no
               URL and writes no `?` anywhere"))

        (testing "the far end is a link and the near end is not a control at all"
          (is (= "SPAN" (.-tagName (node m ".pager-prev"))))
          (is (= "true" (.getAttribute (node m ".pager-prev") "aria-disabled")))
          (is (= "A" (.-tagName (node m ".pager-next"))))
          (is (= "/slice?page=2" (.getAttribute (node m ".pager-next") "href"))))

        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 2 — a real click on a page link
;; ---------------------------------------------------------------------------

(deftest a-real-click-on-a-page-link-moves-the-list-and-the-url-carries-the-page
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-page!)]
        ;; The real event, through React's own event system, with routing's
        ;; own `activate-link!` deciding. Nothing here calls preventDefault
        ;; — if the click were not claimed, this page would navigate away.
        (.click (node m ".pager-next"))
        (-> (drained m
                     #(= 2 (:page (read-sub m [:rf.route/query])))
                     "the page link's navigate to drain")
            (.then (fn [_]
                     (is (= ["Keys are domain ids" "Boundaries are components"
                             "Revision is a counter"]
                            (texts m ".article-link"))
                         "the list followed the URL, and it is the ONLY copy
                          of the page — nothing in the application's own
                          partition of app-db holds a second one")
                     (is (= "2" (text m ".pager-current")))
                     (is (= "A" (.-tagName (node m ".pager-prev")))
                         "and Previous became a control the moment there was
                          somewhere for it to go")))
            (finish-after m done))))))

;; ---------------------------------------------------------------------------
;; 3 — keyed identity across a page flip
;; ---------------------------------------------------------------------------

(deftest a-page-flip-reuses-no-rows-element-and-per-row-state-follows-the-SLUG
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m        (at-page!)
            intents  ".article-list li:nth-child(2)"
            expanded #(.getAttribute (node m (str % " .tags-toggle")) "aria-expanded")
            row-1    (node m ".article-list li:nth-child(1)")]
        (click! m (str intents " .tags-toggle"))
        (is (= "true" (expanded intents)) "the disclosure on *Intents are data* is open")
        (is (= ["intents"] (texts m ".tag-list .tag")))

        (go-to-page! m 2)

        (testing "page two replaced every row rather than repainting them"
          (is (= "Keys are domain ids" (text m ".article-list li:nth-child(1) .article-link")))
          (is (not (identical? row-1 (node m ".article-list li:nth-child(1)")))
              "a DIFFERENT element. Keyed by the slug, React unmounted page
               one's rows and mounted page two's; keyed by the index it
               would have kept these three elements and repainted their
               text, handing row one's mounted node, its open disclosure
               and its scroll position to a different article. This is a
               measurement of what React did with the keys the body wrote —
               the substrate does not police them (PR #8026)")
          (is (= ["false" "false" "false"]
                 (attrs-of m ".tags-toggle" "aria-expanded"))
              "and no row on this page inherited the open disclosure: the
               instance key is a domain id, so the state belongs to the
               ARTICLE and page two's articles have none")
          (is (empty? (nodes m ".tag-list"))))

        (go-to-page! m 1)

        (testing "and coming back, the state is where the article left it"
          (is (= "true" (expanded intents))
              "the disclosure came back with *Intents are data* — `h/reg-state`
               keyed it by the slug, so it survived the article leaving the
               screen entirely and rejoined it when it returned")
          (is (= ["intents"] (texts m ".tag-list .tag")))
          (is (= "false" (expanded ".article-list li:nth-child(1)"))
              "and the row beside it is still closed, which is the bug a
               single `[:ui :tags-open?]` flag would have made invisible"))

        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 4 — a page number nobody may trust
;; ---------------------------------------------------------------------------

(deftest a-page-outside-the-range-is-a-page-rather-than-an-error
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-page! 900)]
        (is (= ["Pages ride the URL"] (texts m ".article-link"))
            "the last page — clamped, because a URL is user input and
             `?page=900` is something anybody can type into the address bar")
        (is (= "3" (text m ".pager-current"))
            "and the pager says which page that turned out to be")
        (is (= 900 (:page (read-sub m [:rf.route/query])))
            "while the URL still says what the user asked for. Rewriting it
             would take the address bar off them to fix a typo they can see")
        (is (= "SPAN" (.-tagName (node m ".pager-next")))
            "there is no next page from the last one")
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 5 — runtime-selected content
;; ---------------------------------------------------------------------------

(deftest every-block-is-rendered-by-the-body-its-own-KIND-names
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-page!)]
        (is (= "Editor's digest" (text m ".digest-heading")))
        (is (= "Three ideas the archive keeps coming back to." (text m ".block-prose")))
        (is (= ["Keys are domain ids" "Boundaries are components" "Revision is a counter"]
               (texts m ".block-item")))

        (testing "the callouts pick their emphasis TAG from their tone"
          (is (= ["EM" "STRONG"] (mapv #(.-tagName %) (nodes m ".block-emphasis")))
              "a keyword in head position is a keyword in head position
               whether it was typed or computed (specification §3.3)")
          (is (= ["rgb(11, 107, 203)" "rgb(176, 32, 32)"]
                 (mapv #(.. % -style -color) (nodes m ".block-callout")))
              "and their colours are theme tokens read through a sub, which
               is why a witness can read them back"))

        (testing "a kind this build has no renderer for stays DATA"
          (is (= ":block/ticker" (text m ".block-kind"))
              "named on screen rather than swallowed")
          (is (nil? (node m ".digest-error"))
              "and NOTHING THREW. Content outlives the build that renders
               it; a region that threw on an unknown kind would make every
               new content type a deployment incident"))

        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 6 — the nested error region: inner catches, outer survives
;; ---------------------------------------------------------------------------

(deftest a-broken-block-takes-its-own-region-down-and-leaves-the-page-working
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-page!)]
        ;; A payload that arrived cut short — the list block without its
        ;; items — installed through the application's OWN arrival event,
        ;; because that is the door a real truncated response comes in by.
        (hm/dispatch-and-settle! m [::events/digest-arrived
                                    {:blocks db/digest-truncated}])

        (testing "the region's own boundary caught it"
          (is (some? (node m ".digest-error")))
          (is (= "alert" (.getAttribute (node m ".digest-error") "role"))
              "an error region a screen reader is not told about is one a
               screen-reader user does not get")
          (is (nil? (node m ".block-prose"))
              "and the whole body it wrapped is gone — a caught boundary
               shows its fallback INSTEAD of its children, not beside them"))

        (testing "THE OUTER BOUNDARY SURVIVED, which is the whole claim"
          (is (nil? (node m ".pane-error"))
              "the shell's own error region never rendered. One boundary at
               the root would have made this throw take the application
               down, and the only failure mode an application with one
               boundary has is *the screen went away*")
          (is (= ["Hicasso, briefly" "Intents are data" "Controlled, synchronously"]
                 (texts m ".article-link"))
              "the list is still on screen")
          (is (some? (node m ".pager")) "and the pager still works")
          (is (= "Editor's digest" (text m ".digest-heading"))
              "and the region's heading is OUTSIDE its boundary, so the page
               still says what it is that is missing"))

        (testing "the list is still operable, not merely painted"
          (go-to-page! m 2)
          (is (= "Keys are domain ids" (text m ".article-link")))
          (is (some? (node m ".digest-error"))
              "and the failed region is still failed — the pane did not
               remount, so nothing was quietly healed by the navigation"))

        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 7 — and the retry works
;; ---------------------------------------------------------------------------

(deftest the-regions-own-retry-reloads-the-content-and-the-region-comes-back
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-page!)]
        (hm/dispatch-and-settle! m [::events/digest-arrived
                                    {:blocks db/digest-truncated}])
        (is (some? (node m ".digest-retry")) "the fallback carries the retry")

        (click! m ".digest-retry")
        (testing "in flight — read on the line after the click, with no wait"
          (is (= "Reloading…" (text m ".digest-retry"))
              "the intent on a FALLBACK dispatched into the mount's frame
               like any other")
          (is (true? (.-disabled (node m ".digest-retry")))
              "so a second click cannot queue a second request"))

        (-> (drained m
                     #(= db/digest (read-sub m [::subs/digest-blocks]))
                     "the stand-in content server's reply")
            (.then
              (fn [_]
                (is (nil? (node m ".digest-error"))
                    "THE RETRY WORKED: different content arrived, the
                     region's `:reset-key` is the content, so the caught
                     failure cleared and the body ran again")
                (is (= ["Keys are domain ids" "Boundaries are components"
                        "Revision is a counter"]
                       (texts m ".block-item"))
                    "with the list block whole this time")
                (is (= "Three ideas the archive keeps coming back to."
                       (text m ".block-prose")))
                (is (nil? (node m ".pane-error"))
                    "and the shell's boundary never rendered at any point in
                     the whole sequence")))
            (finish-after m done))))))
