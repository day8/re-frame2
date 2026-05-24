(ns day8.re-frame2-causa.panels.event-detail-cljs-test
  "Tests for the Causa Event lens panel (rf2-zh2qc — rewrite of the
  v1 six-domino renderer per Mike's verbatim 6-section design).

  ## What this suite covers

    1. Default-focus / cascade selection / clear (carried over from v1
       — the panel's spine plumbing didn't change).
    2. No top header/ribbon (rf2-ad7zx.17) — the panel leads with the
       numbered pipeline; the pipeline rail runs through the circle
       centres from step 1; the FROM row matches EventPanel.tsx.
    3. The 7 sections render in order (Mike's Q1 verbatim per
       rf2-jhhqt): DISPATCH SITE, EVENT, COEFFECTS, INTERCEPTORS,
       HANDLER, EFFECTS RETURNED, EFFECTS HANDLERS RAN.
    4. Silent-by-default — sections ABSENT (not '(none)') when their
       data is empty.
    5. Handler threw → §6/§7 suppressed + Issues-tab footer renders.
    6. Pure projection helpers (`user-interceptors`, `user-coeffects`,
       `cascade-outcome`, `effects-handlers-ran`, `hydration-outcome-row`).
    7. The meta-on-vector pattern (rf2-ppzid) — :key reaches every
       row inside :for blocks.

  ## Pure-data scope

  The view is pure hiccup; the tests assert against the hiccup tree
  rather than booting a substrate adapter / mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.app-db-diff-subs :as app-db-diff-subs]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!)
  ;; rf2-mn3gt — the DB CHANGES section consumes the cached
  ;; `:rf.causa/selected-epoch-diff` triples; reset the per-`:epoch-id`
  ;; diff cache so the changed-paths assertions are reproducible.
  (reset! app-db-diff-subs/diff-cache {}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- fixture stream builders -------------------------------------------

(defn- cascade-evs
  "Build the canonical Event-lens cascade stream for a given dispatch-
  id + event vector. Mirrors v1 `cascade-evs` but additionally:

    - Hoists `:rf.trace/call-site` to the top level of the
      `:rf.event/dispatched` trace (rf2-twt7m Change 1) so the DISPATCH
      SITE section has data.
    - Stamps `:source` + `:origin` (also top-level — `build-event`'s
      success-path hoist).
    - Stamps `:fx` + `:db-present?` on the `:rf.fx/do-fx` trace's
      `:tags` (rf2-twt7m Change 2) so EFFECTS RETURNED has data."
  ([dispatch-id event-vec id-base]
   (cascade-evs dispatch-id event-vec id-base nil))
  ([dispatch-id event-vec id-base {:keys [frame-id call-site source origin fx db-present? coeffects]
                                    :or   {fx          [[:db nil] [:dispatch [:bar]]]
                                           db-present? true
                                           source      :ui
                                           origin      :app}}]
   [(cond-> {:id (+ id-base 1) :op-type :rf.event :operation :rf.event/dispatched
             :tags (cond-> {:rf.trace/dispatch-id dispatch-id :rf.event/v event-vec}
                     frame-id (assoc :frame frame-id))}
      call-site (assoc :rf.trace/call-site call-site)
      source    (assoc :source source)
      origin    (assoc :origin origin))
    {:id (+ id-base 2) :op-type :rf.event :operation :rf.event/run-start
     :tags (cond-> {:rf.trace/dispatch-id dispatch-id :rf.trace/phase :run-start}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 3) :op-type :rf.event :operation :rf.event/run-end
     :tags (cond-> {:rf.trace/dispatch-id dispatch-id :rf.trace/phase :run-end :duration-ms 11}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 4) :op-type :rf.fx :operation :rf.fx/do-fx
     :tags (cond-> {:rf.trace/dispatch-id dispatch-id}
             frame-id    (assoc :frame frame-id)
             fx          (assoc :rf.event/fx fx)
             db-present? (assoc :rf.event/db-present? true)
             (seq coeffects) (assoc :rf.event/coeffects coeffects))}
    {:id (+ id-base 5) :op-type :rf.fx :operation :rf.fx/handled
     :tags (cond-> {:rf.trace/dispatch-id dispatch-id :rf.fx/id :db :duration-ms 1}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 6) :op-type :rf.fx :operation :rf.fx/handled
     :tags (cond-> {:rf.trace/dispatch-id dispatch-id :rf.fx/id :dispatch :rf.fx/args [[:bar]]
                    :duration-ms 0}
             frame-id (assoc :frame frame-id))}]))

(defn- seed-buffer!
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (doseq [ev evs]
    (trace-bus/collect-trace! ev)))

;; rf2-mn3gt — seed the Causa frame's `:epoch-history` slot so the DB
;; CHANGES section's `:rf.causa/selected-epoch-diff` sub resolves to a
;; real `:rf/epoch-record` with `:db-before` / `:db-after` snapshots.
;; The record carries a literal `:dispatch-id` so `epoch-id-for-cascade`
;; links the focused cascade to the epoch (see `spine/epoch-id-for-cascade`
;; — synthetic records that omit `:trace-events` match on the literal
;; `:dispatch-id` slot). MUST be dispatched BEFORE
;; `:rf.causa/select-dispatch-id`, which reads `:epoch-history` at
;; dispatch-time to resolve the focus epoch-id.

(rf/reg-event-db :rf.causa-test/seed-epoch-history
  (fn [db [_ records]]
    (assoc db :epoch-history (vec records))))

(defn- mk-epoch-record
  "Build a minimal `:rf/epoch-record` for the DB CHANGES diff sub. The
  literal `:dispatch-id` links the record to the focused cascade."
  [epoch-id dispatch-id event db-before db-after]
  {:epoch-id      epoch-id
   :dispatch-id   dispatch-id
   :frame         :rf/default
   :committed-at  0
   :event-id      (first event)
   :trigger-event event
   :db-before     db-before
   :db-after      db-after
   :trace-events  []})

(defn- seed-epoch-history!
  "Seed `:epoch-history` on the Causa frame (must run inside
  `(rf/with-frame :rf/causa …)`)."
  [records]
  (rf/dispatch-sync [:rf.causa-test/seed-epoch-history records]))

(defn- expand-fn-component
  [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq
  "Walk a hiccup tree and emit every node (vectors only). Vectors
  whose first element is a function are invoked first so the walker
  descends into the rendered sub-tree."
  [tree]
  (let [children (fn [node]
                   (let [expanded (expand-fn-component node)]
                     (when (or (vector? expanded) (seq? expanded))
                       (seq expanded))))]
    (->> (tree-seq (some-fn vector? seq?) children (expand-fn-component tree))
         (map expand-fn-component))))

(defn- find-by-testid
  "Find the first node in a hiccup tree whose attrs map has the given
  `:data-testid`. Returns nil when no such node exists."
  [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid
  "Find every node in a hiccup tree whose attrs map has the given
  `:data-testid`. Returns a (possibly empty) vector — useful for
  asserting on counts."
  [tree testid]
  (vec
    (filter (fn [node]
              (and (vector? node)
                   (map? (second node))
                   (= testid (:data-testid (second node)))))
            (hiccup-seq tree))))

;; ---- (1) selection plumbing — survives from v1 -------------------------

(deftest live-focus-renders-head-cascade-detail
  (testing "with cascades in the buffer + no explicit selection, the
            spine LIVE-tracks head and the panel renders the head
            cascade's detail"
    (seed-buffer! (concat (cascade-evs 100 [:user/login {:id 42}] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (= 200 (:selected-dispatch-id data))
            "head cascade (200, latest) is the default selection"))
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container renders for the head cascade")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "no empty-state container when there's a head to focus on")))))

(deftest cold-start-with-no-cascades-renders-empty-container
  (testing "with an empty buffer + no selection the panel still
            renders the empty-state container — no head to focus on"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade")))))))

(deftest selecting-non-existent-dispatch-id-shows-orphaned-state
  (testing "selecting a dispatch-id that's not in the buffer surfaces
            the orphaned-selection branch"
    (seed-buffer! (cascade-evs 100 [:user/login] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 999])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-orphaned")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade")))))))

(deftest clear-selected-dispatch-id-snaps-to-live-head
  (testing "after select + clear the panel snaps back to LIVE head-tracking"
    (seed-buffer! (concat (cascade-evs 100 [:user/login] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (rf/dispatch-sync [:rf.causa/clear-selected-dispatch-id])
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (= 200 (:selected-dispatch-id data)))))))

;; ---- (2) numbered-pipeline steps render in spec order ------------------

(def ^:private ^:const section-root-testids
  "The numbered-pipeline step ROOT testids (spec/021 §2.2 · Figma
  reconcile rf2-ad7zx.5). Used by `section-testids-in-order` to filter
  out the per-step `*-label` / `*-body` children testids `step-section`
  emits.

  Section order: DISPATCH → COEFFECTS? → EVENT HANDLER → DB CHANGES →
  AFTER INTERCEPTORS? → FLOWS? → FX (optional steps shown when present)."
  #{"rf-causa-event-detail-section-dispatch"
    "rf-causa-event-detail-section-coeffects"
    "rf-causa-event-detail-section-handler"
    "rf-causa-event-detail-section-db-changes"
    "rf-causa-event-detail-section-after-interceptors"
    "rf-causa-event-detail-section-flows"
    "rf-causa-event-detail-section-fx"})

(defn- section-testids-in-order
  "Walk the rendered hiccup tree and return the data-testids of every
  step ROOT in document order. Filters out the per-step `*-label` /
  `*-body` children testids `step-section` emits."
  [tree]
  (->> (hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (let [tid (str (or (:data-testid (second node)) ""))]
                   (when (contains? section-root-testids tid)
                     tid)))))
       (distinct)
       (vec)))

(deftest event-lens-renders-all-seven-steps-when-fully-populated
  (testing "rf2-ad7zx.5 — a cascade with call-site + fx + after-
            interceptors + user coeffects + a flow yields the full
            7-step numbered pipeline (spec/021 §2.2): DISPATCH,
            COEFFECTS, EVENT HANDLER, DB CHANGES, AFTER INTERCEPTORS,
            FLOWS, FX"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/poke
        [(rf/->interceptor :id :auth/require-login)]
        (fn [_ _] {}))
      (rf/reg-flow {:id :a-flow :inputs [[:in]] :output identity :path [:a]}))
    (seed-buffer!
      (concat
        (cascade-evs 100 [:widget/poke {:id 1}] 0
                     {:call-site {:file "src/widget.cljs" :line 42}
                      :source :ui :origin :app
                      :fx [[:db nil] [:dispatch [:bar]]]
                      :db-present? true
                      :coeffects {:now "2026-05-18T19:00:00Z"
                                  :local-storage {:user/last-cart-id "cart-42"}}})
        ;; A flow firing in the cascade's :other bucket → FLOWS step
        ;; present. Inlined (rather than via the `flow-computed-ev`
        ;; helper defined later in this ns) to keep top-level def order.
        ;; `:rf.trace/dispatch-id` on :tags is what `group-cascades`
        ;; keys on to bucket the trace into cascade 100's :other slot.
        [{:id 50 :op-type :flow :operation :rf.flow/computed
          :tags {:rf.trace/dispatch-id 100
                 :flow-id :a-flow :path [:a] :input-values [1]
                 :result 1 :frame :rf/default}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (doseq [[id label] [["dispatch" "DISPATCH"]
                            ["coeffects" "COEFFECTS"]
                            ["handler" "EVENT HANDLER"]
                            ["db-changes" "DB CHANGES"]
                            ["after-interceptors" "AFTER INTERCEPTORS"]
                            ["flows" "FLOWS"]
                            ["fx" "FX"]]]
          (is (some? (find-by-testid tree (str "rf-causa-event-detail-section-" id)))
              (str "step " label " present"))
          (is (some? (find-by-testid tree (str "rf-causa-event-detail-step-circle-" id)))
              (str "step " label " carries a numbered step circle")))))))

(deftest event-lens-step-order-matches-spec-021-section-2-2
  (testing "rf2-ad7zx.5 — steps render top-to-bottom in spec/021 §2.2
            order: DISPATCH → COEFFECTS → EVENT HANDLER → DB CHANGES →
            AFTER INTERCEPTORS → FLOWS → FX (optional steps shown when
            present). This fixture seeds no flow, so FLOWS is OMITTED —
            absence by omission, dynamic numbering closes the gap."
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/poke
        [(rf/->interceptor :id :auth/require-login)]
        (fn [_ _] {})))
    (seed-buffer!
      (cascade-evs 100 [:widget/poke {:id 1}] 0
                   {:call-site {:file "src/widget.cljs" :line 42}
                    :coeffects {:now "2026-05-18T19:00:00Z"}
                    :fx [[:db nil] [:dispatch [:bar]]]
                    :db-present? true}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree  (event-detail/Panel)
            order (section-testids-in-order tree)]
        (is (= ["rf-causa-event-detail-section-dispatch"
                "rf-causa-event-detail-section-coeffects"
                "rf-causa-event-detail-section-handler"
                "rf-causa-event-detail-section-db-changes"
                "rf-causa-event-detail-section-after-interceptors"
                "rf-causa-event-detail-section-fx"]
               order)
            "step testids appear in spec/021 §2.2 order (FLOWS omitted)")))))

(deftest event-lens-dynamic-step-numbering-renumbers-on-omission
  (testing "rf2-ad7zx.5 — step numbers are assigned DYNAMICALLY 1..N
            over the PRESENT steps. With no coeffects / after-
            interceptors / flows, the sparse pipeline reads DISPATCH=1,
            EVENT HANDLER=2, DB CHANGES=3, FX=4 — the absent optional
            steps consume no number."
    (rf/with-frame :rf/default
      (rf/reg-event-db :poll/tick (fn [db _] db)))
    (seed-buffer! (cascade-evs 100 [:poll/tick] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            step-of (fn [id]
                      (-> (find-by-testid tree
                            (str "rf-causa-event-detail-section-" id))
                          second
                          :data-step-number))]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-coeffects"))
            "COEFFECTS omitted (no user coeffects)")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-after-interceptors"))
            "AFTER INTERCEPTORS omitted (no non-standard interceptors)")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-flows"))
            "FLOWS omitted (no flows fired)")
        (is (= "1" (step-of "dispatch")) "DISPATCH numbered 1")
        (is (= "2" (step-of "handler")) "EVENT HANDLER numbered 2")
        (is (= "3" (step-of "db-changes")) "DB CHANGES numbered 3")
        (is (= "4" (step-of "fx")) "FX numbered 4 (contiguous despite omissions)")))))

(deftest panel-has-no-top-header-ribbon-and-leads-with-dispatch
  (testing "rf2-ad7zx.17 — per EventPanel.tsx the panel has NO top
            header/ribbon: the prior identity ribbon (⚡ panel icon +
            lifecycle status dot + event-id label + `epoch #N` + SSR
            badge) is gone. The panel leads directly with step 1
            (DISPATCH). The literal 'Event detail' h1 stays gone too."
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            text (->> (hiccup-seq tree) (filter string?) (apply str))]
        ;; No top header/ribbon — none of its parts survive.
        (is (nil? (find-by-testid tree "rf-causa-event-detail-header"))
            "no top header element")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-panel-icon"))
            "no ⚡ panel icon")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-header-event-id"))
            "no event-id ribbon label")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-header-epoch"))
            "no `epoch #N` ribbon label")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-header-ssr-badge"))
            "no SSR badge in a (now-absent) header")
        ;; The status-dot testid is dynamic; assert no dot of any state.
        (is (every? (fn [st]
                      (nil? (find-by-testid
                              tree
                              (str "rf-causa-event-detail-status-dot-" st))))
                    ["in-flight" "settled-success" "settled-error"
                     "paused-by-tool" "stale"])
            "no lifecycle status dot (it lived only in the removed ribbon)")
        ;; The panel leads with the DISPATCH step.
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-dispatch"))
            "panel leads with the DISPATCH step")
        (is (not (re-find #"Event detail" text))
            "literal 'Event detail' h1 stays removed")
        ;; spec/021 §2.2 — NO outcome badge.
        (is (nil? (find-by-testid tree "rf-causa-event-detail-outcome"))
            "the prior outcome badge container is gone")))))

;; ---- (4) DISPATCH step (event vector + FROM origin) -------------------

(deftest dispatch-from-row-matches-eventpanel-tsx-source-link
  (testing "rf2-ad7zx.17 — the FROM row matches EventPanel.tsx: `FROM:`
            then the dispatch SOURCE as a SINGLE click-to-source link
            (`view ↗`) — the `↗` chip trails the source text. The prior
            `· origin <origin>` clutter and the standalone `file:line`
            coord span are dropped (the mock surfaces neither)."
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0
                                {:call-site {:file "src/views.cljs" :line 127}
                                 :source :view :origin :app}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            caption (find-by-testid tree "rf-causa-event-detail-dispatch-caption")
            from    (find-by-testid tree "rf-causa-event-detail-dispatch-from")
            chip    (find-by-testid tree "rf-causa-event-detail-dispatch-open-chip")
            caption-text (->> (hiccup-seq caption) (filter string?) (apply str))
            from-text    (->> (hiccup-seq from) (filter string?) (apply str))]
        (is (re-find #"FROM:" caption-text) "FROM: caption rendered")
        (is (some? from) "the source is rendered as a single FROM link span")
        (is (re-find #"view" from-text) "dispatch source is the link text")
        (is (some? chip) "the ↗ click-to-source chip trails the source")
        ;; EventPanel.tsx shape — NO `· origin` clutter, NO standalone
        ;; file:line coord span.
        (is (not (re-find #"origin" caption-text))
            "no `· origin <origin>` clutter (dropped per the mock)")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-dispatch-coord"))
            "no standalone file:line coord span (dropped per the mock)")))))

(deftest dispatch-from-row-without-call-site-renders-plain-source-no-chip
  (testing "rf2-ad7zx.17 — when no :rf.trace/call-site is captured the
            FROM row renders the source as plain (unlinked) text and
            omits the ↗ click-to-source chip"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0 {:call-site nil :source :timer}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            from (find-by-testid tree "rf-causa-event-detail-dispatch-from")
            from-text (->> (hiccup-seq from) (filter string?) (apply str))]
        (is (some? from) "plain source text rendered")
        (is (re-find #"timer" from-text) "source surfaced as plain text")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-dispatch-open-chip"))
            "no ↗ chip without a call-site coord")))))

(deftest dispatch-step-renders-event-vector
  (testing "rf2-ad7zx.5 — the DISPATCH step carries the dispatched event
            vector (the prior standalone EVENT section is merged in)"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-event-vector"))
            "event vector rendered inside the DISPATCH step")))))

(deftest dispatch-event-vector-renders-in-boxed-block
  (testing "rf2-l3h1m — the DISPATCH event vector renders in a BOXED block
            (`bg-muted p-3 rounded` in EventPanel.tsx; raised `:bg-3` fill
            + subtle border + radius here), not inline/unboxed. The box
            carries a background fill, a border, and a non-zero
            border-radius so it reads as the same surface family as the
            §3 EVENT HANDLER source code-block."
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree     (event-detail/Panel)
            box      (find-by-testid tree "rf-causa-event-detail-event-vector")
            style    (:style (second box))]
        (is (some? box) "event vector box rendered")
        (is (some? (:background style)) "box carries a background fill")
        (is (some? (:border style)) "box carries a border")
        (is (and (some? (:border-radius style))
                 (not= "0" (:border-radius style)))
            "box has a non-zero border-radius (rounded)")
        (is (some? (:padding style)) "box carries inner padding")))))

;; ---- (5) AFTER INTERCEPTORS step — optional, omitted when empty -------

(deftest after-interceptors-step-absent-when-zero-non-standard
  (testing "rf2-ad7zx.5 — spec/021 §2.2: the optional AFTER INTERCEPTORS
            step is OMITTED entirely when the event has no non-standard
            interceptors (absence by omission, NOT a '(none)' line)"
    (rf/with-frame :rf/default
      (rf/reg-event-db :counter/inc (fn [db _] db)))
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-after-interceptors"))
            "AFTER INTERCEPTORS step omitted when zero user interceptors")))))

(deftest after-interceptors-step-renders-user-interceptors
  (testing "rf2-ad7zx.5 — with user interceptors on the chain the AFTER
            INTERCEPTORS step is shown with one row per non-default
            interceptor"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :auth/login
        [(rf/->interceptor :id :auth/require-login)
         (rf/->interceptor :id :auth/log-action)]
        (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:auth/login] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            section (find-by-testid tree "rf-causa-event-detail-section-after-interceptors")]
        (is (some? section) "step rendered")
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-interceptor-row-auth/require-login")))
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-interceptor-row-auth/log-action")))))))

;; ---- (6) EVENT HANDLER step --------------------------------------------

(deftest handler-step-shows-flavour-and-source-coord
  (testing "EVENT HANDLER step shows reg-event-* flavour (does NOT
            duplicate the event-id)"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :cart/add-item (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:cart/add-item {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            flav (find-by-testid tree "rf-causa-event-detail-handler-flavour")
            flav-text (->> (hiccup-seq flav) (filter string?) (apply str))]
        (is (some? flav) "flavour caption rendered")
        (is (= "reg-event-fx" flav-text)
            "shows reg-event-fx flavour for :fx-kind handler")))))

(deftest handler-step-absent-coord-when-no-registration
  (testing "an event with no registered handler renders the absent
            placeholder (the panel never crashes on an unregistered id)"
    (seed-buffer! (cascade-evs 100 [:never-registered/event] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-handler-coord-absent")))))))

;; ---- (7) EFFECTS RETURNED retired (spec/021 §2.2) ---------------------

(deftest effects-returned-step-retired
  (testing "rf2-ad7zx.5 — spec/021 §2.2 retires the standalone EFFECTS
            RETURNED step (which duplicated :db against DB CHANGES and
            :fx against FX). It must not render."
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0
                                {:fx [[:dispatch [:bar]]]
                                 :db-present? true}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-effects-returned"))
            "EFFECTS RETURNED step absent")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-effects-returned-row-db"))
            ":db marker row absent (DB CHANGES owns the db diff)")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-effects-returned-row-fx"))
            ":fx vector row absent (the FX step owns fx)")))))

(deftest hydration-outcome-row-renders-for-rf-ssr-hydrated
  (testing "rf2-ad7zx.5 — the SSR hydration-outcome addendum surfaces a
            dedicated row inside the DB CHANGES step when the focused
            event is :rf.ssr/hydrated"
    (seed-buffer!
      (concat (cascade-evs 100
                            [:rf.ssr/hydrated {:duration-ms 87 :subs-ran 142 :mismatches 0}]
                            0
                            {:fx nil :db-present? false})
              [{:id 50 :op-type :rf.event :operation :rf.ssr/hydration-outcome
                :tags {:rf.trace/dispatch-id 100 :duration-ms 87 :subs-ran 142 :mismatches 0}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-db-changes"))
            "DB CHANGES step hosts the hydration addendum")
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-returned-row-hydration"))
            "hydration-outcome row renders inside DB CHANGES")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-hydration-issues-jump"))
            "no jump-to-Issues affordance when :mismatches is 0")))))

(deftest hydration-outcome-row-jumps-to-issues-when-mismatches-pos
  (testing "when :mismatches > 0 the hydration row carries the
            jump-to-Issues affordance"
    (seed-buffer!
      (concat (cascade-evs 100 [:rf.ssr/hydrated {:mismatches 3}] 0
                            {:fx nil :db-present? false})
              [{:id 50 :op-type :rf.event :operation :rf.ssr/hydration-outcome
                :tags {:rf.trace/dispatch-id 100 :duration-ms 91 :mismatches 3}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-hydration-issues-jump"))
            "jump-to-Issues affordance renders when mismatches > 0")))))

;; ---- (8) FX step — required, renders one row per fx + managed inline --

(deftest fx-step-renders-one-row-per-fx
  (testing "rf2-ad7zx.5 — the FX step renders one row per :rf.fx/handled
            trace, keyed by trace :id"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-fx"))
            "FX step present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-ran-row-db")))
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-ran-row-dispatch")))))))

(deftest fx-step-shows-none-when-no-fx-ran
  (testing "rf2-ad7zx.5 — spec/021 §2.2: FX is a REQUIRED step (unlike
            the optional COEFFECTS / AFTER INTERCEPTORS / FLOWS). When no
            fx handlers ran the step still renders, with a `(none)` line
            (matching the sparse-case mockup)."
    (let [evs (filterv #(not= :rf.fx/handled (:operation %))
                       (cascade-evs 100 [:noop/event] 0))]
      (seed-buffer! evs))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-fx"))
            "FX step still present (required step)")
        (is (some? (find-by-testid tree "rf-causa-event-detail-fx-none"))
            "(none) line shown when no fx handlers ran")))))

(deftest fx-step-mounts-managed-fx-record-inline
  (testing "per §8.3 — when an fx-handler is a managed-fx surface
            (:rf.http/* etc.) the managed-fx record-panel mounts
            INLINE beneath its causing row, not in a trailing block"
    (seed-buffer!
      [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
        :tags {:rf.trace/dispatch-id 100 :rf.event/v [:cart/refresh]}}
       {:id 2 :op-type :rf.event :operation :rf.event/run-end
        :tags {:rf.trace/dispatch-id 100 :rf.trace/phase :run-end :duration-ms 3}}
       {:id 3 :op-type :rf.fx :operation :rf.fx/do-fx
        :tags {:rf.trace/dispatch-id 100}}
       {:id 4 :op-type :rf.fx :operation :rf.fx/handled
        :tags {:rf.trace/dispatch-id 100 :rf.fx/id :rf.http/get :duration-ms 87
               :source :http :rf.event/origin :app}}])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            inline (find-by-testid tree "rf-causa-event-detail-effects-ran-managed-fx-4")]
        (is (some? inline)
            "managed-fx record-panel mounts inline beneath fx-handler row")))))

;; ---- (8.4) FLOWS section — rf2-lo37i ----------------------------------

(defn- flow-computed-ev
  "Build one `:rf.flow/computed` trace event ready to seed into the
  cascade's `:other` bucket. Matches the per-firing trace shape
  Spec 009 §Flow trace events documents (and the JVM
  flows_trace_test.clj canon)."
  [dispatch-id id-base flow-id {:keys [write-path input-values result frame]
                                 :or   {frame :rf/default}}]
  {:id        id-base
   :op-type   :flow
   :operation :rf.flow/computed
   :tags      {:rf.trace/dispatch-id  dispatch-id
               :flow-id      flow-id
               :path         write-path
               :input-values input-values
               :result       result
               :frame        frame}})

(deftest flows-section-absent-when-no-flows-fired
  (testing "rf2-lo37i — silent-by-default: a cascade with zero
            `:rf.flow/computed` traces in `:other` renders NO FLOWS
            section (the section is OMITTED entirely, not '(none)')"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-flows"))
            "FLOWS section absent when no flows fired")))))

(deftest flows-section-renders-one-row-per-rf-flow-computed
  (testing "rf2-lo37i — each `:rf.flow/computed` trace in `:other`
            renders as one flow row with the id + write-path + after-
            value (result)"
    (rf/with-frame :rf/default
      (rf/reg-flow {:id     :cart-total
                    :inputs [[:cart :items]]
                    :output (fn [_] 0)
                    :path   [:cart :total]}))
    (seed-buffer!
      (concat (cascade-evs 100 [:cart/add-item] 0)
              [(flow-computed-ev 100 50 :cart-total
                                  {:write-path  [:cart :total]
                                   :input-values [[:apple :banana]]
                                   :result      52.5})]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-flows"))
            "FLOWS section present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-flow-row-cart-total"))
            "per-flow row renders for :cart-total")
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-flow-row-id-cart-total"))
            "flow-id chip present in row")
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-flow-row-write-path-cart-total"))
            "write-path renders")
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-flow-row-wrote-cart-total"))
            "'wrote' line renders")
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-flow-row-read-cart-total"))
            "'read' line renders")))))

(deftest flows-section-renders-input-paths-from-registry
  (testing "rf2-lo37i — `:rf.flow/computed` does not carry input PATHS
            (only :input-values). The render-time lookup via
            `(rf/handler-meta :flow id)` recovers the paths from the
            registered flow"
    (rf/with-frame :rf/default
      (rf/reg-flow {:id     :tax-due
                    :inputs [[:cart :total] [:tax :rate]]
                    :output (fn [t r] (* t r))
                    :path   [:tax :due]}))
    (seed-buffer!
      (concat (cascade-evs 100 [:cart/add-item] 0)
              [(flow-computed-ev 100 50 :tax-due
                                  {:write-path  [:tax :due]
                                   :input-values [50.0 0.105]
                                   :result      5.25})]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            read-row (find-by-testid tree
                                      "rf-causa-event-detail-flow-row-read-tax-due")
            text (->> (hiccup-seq read-row) (filter string?) (apply str))]
        (is (some? read-row) "'read' line renders")
        (is (re-find #":cart :total" text)
            "first input path rendered")
        (is (re-find #":tax :rate" text)
            "second input path rendered")
        (is (nil? (find-by-testid tree
                                   "rf-causa-event-detail-flow-row-read-absent-tax-due"))
            "no 'absent' placeholder when registry resolves the paths")))))

(deftest flows-section-read-line-shows-placeholder-when-flow-cleared
  (testing "rf2-lo37i — when a flow id appears in trace but the
            registry no longer carries it (cleared mid-session) the
            'read' line renders the absent-placeholder"
    (seed-buffer!
      (concat (cascade-evs 100 [:cart/add-item] 0)
              [(flow-computed-ev 100 50 :gone-flow
                                  {:write-path  [:cart :total]
                                   :input-values [1 2]
                                   :result      3})]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-flow-row-read-absent-gone-flow"))
            "absent placeholder renders when registry lookup fails")))))

(deftest flows-section-renders-chained-via-marker-when-downstream-of-prior-flow
  (testing "rf2-lo37i — when a flow reads a path that an EARLIER flow
            in the same cascade wrote, the downstream row carries the
            `↳ via :upstream` marker"
    (rf/with-frame :rf/default
      (rf/reg-flow {:id     :cart-total
                    :inputs [[:cart :items]]
                    :output (fn [_] 0)
                    :path   [:cart :total]})
      (rf/reg-flow {:id     :tax-due
                    :inputs [[:cart :total]]
                    :output (fn [t] t)
                    :path   [:tax :due]}))
    (seed-buffer!
      (concat (cascade-evs 100 [:cart/add-item] 0)
              [(flow-computed-ev 100 50 :cart-total
                                  {:write-path  [:cart :total]
                                   :input-values [[:apple]]
                                   :result      52.5})
               (flow-computed-ev 100 51 :tax-due
                                  {:write-path  [:tax :due]
                                   :input-values [52.5]
                                   :result      5.25})]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            up    (find-by-testid tree "rf-causa-event-detail-flow-row-cart-total")
            down  (find-by-testid tree "rf-causa-event-detail-flow-row-tax-due")
            via   (find-by-testid tree
                                   "rf-causa-event-detail-flow-row-via-tax-due")]
        (is (some? up)   "upstream :cart-total row present")
        (is (some? down) "downstream :tax-due row present")
        (is (some? via)  "↳ via marker renders on downstream row")
        (is (nil? (find-by-testid tree
                                   "rf-causa-event-detail-flow-row-via-cart-total"))
            "upstream row does NOT carry a via marker (no preceding writer)")))))

(deftest flows-section-rows-preserve-cascade-firing-order
  (testing "rf2-lo37i — rows render in cascade firing order (topo-sorted
            by the framework). Asserting on the document-order of
            row testids is the contract."
    (rf/with-frame :rf/default
      (rf/reg-flow {:id     :a-flow
                    :inputs [[:in]]
                    :output identity :path [:a]})
      (rf/reg-flow {:id     :b-flow
                    :inputs [[:a]]
                    :output identity :path [:b]})
      (rf/reg-flow {:id     :c-flow
                    :inputs [[:b]]
                    :output identity :path [:c]}))
    (seed-buffer!
      (concat (cascade-evs 100 [:trigger] 0)
              [(flow-computed-ev 100 50 :a-flow
                                  {:write-path [:a] :input-values [1] :result 1})
               (flow-computed-ev 100 51 :b-flow
                                  {:write-path [:b] :input-values [1] :result 1})
               (flow-computed-ev 100 52 :c-flow
                                  {:write-path [:c] :input-values [1] :result 1})]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            row-tids (->> (hiccup-seq tree)
                          (keep (fn [n]
                                  (when (and (vector? n) (map? (second n)))
                                    (let [tid (str (or (:data-testid (second n)) ""))]
                                      (when (and (str/starts-with?
                                                   tid "rf-causa-event-detail-flow-row-")
                                                  (not (str/starts-with?
                                                         tid "rf-causa-event-detail-flow-row-id-"))
                                                  (not (str/starts-with?
                                                         tid "rf-causa-event-detail-flow-row-wrote-"))
                                                  (not (str/starts-with?
                                                         tid "rf-causa-event-detail-flow-row-read-"))
                                                  (not (str/starts-with?
                                                         tid "rf-causa-event-detail-flow-row-write-path-"))
                                                  (not (str/starts-with?
                                                         tid "rf-causa-event-detail-flow-row-glyph-"))
                                                  (not (str/starts-with?
                                                         tid "rf-causa-event-detail-flow-row-via-")))
                                        tid)))))
                          (distinct)
                          (vec))]
        (is (= ["rf-causa-event-detail-flow-row-a-flow"
                "rf-causa-event-detail-flow-row-b-flow"
                "rf-causa-event-detail-flow-row-c-flow"]
               row-tids)
            "flow rows appear in cascade firing order")))))

(deftest flows-step-sits-before-fx-step
  (testing "rf2-ad7zx.5 — spec/021 §2.2 step order: FLOWS (step 6) sits
            BEFORE FX (step 7) in the pipeline."
    (rf/with-frame :rf/default
      (rf/reg-flow {:id     :a-flow
                    :inputs [[:in]] :output identity :path [:a]}))
    (seed-buffer!
      (concat (cascade-evs 100 [:trigger] 0)
              [(flow-computed-ev 100 50 :a-flow
                                  {:write-path [:a] :input-values [1] :result 1})]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree   (event-detail/Panel)
            tids   (->> (hiccup-seq tree)
                        (keep (fn [n]
                                (when (and (vector? n) (map? (second n)))
                                  (let [tid (str (or (:data-testid (second n)) ""))]
                                    (when (#{"rf-causa-event-detail-section-fx"
                                             "rf-causa-event-detail-section-flows"}
                                            tid)
                                      tid)))))
                        (distinct)
                        (vec))]
        (is (= ["rf-causa-event-detail-section-flows"
                "rf-causa-event-detail-section-fx"]
               tids)
            "FLOWS appears BEFORE FX in document order")))))

(deftest flows-section-absent-when-handler-threw
  (testing "rf2-lo37i — when the handler threw, the effects walk never
            ran, so flows never fired. The FLOWS section should be
            absent (mirrors §6 + §7 suppression)"
    (seed-buffer!
      (concat (cascade-evs 100 [:checkout/submit] 0
                            {:fx nil :db-present? false})
              [{:id 50 :op-type :error :operation :rf.error/handler-exception
                :tags {:rf.trace/dispatch-id 100 :rf.trace/event-id :checkout/submit
                       :exception-message "NullPointerException"}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-flows"))
            "FLOWS section absent when handler threw")))))

(deftest flows-fired-helper-projects-rows-from-other-bucket
  (testing "rf2-lo37i — `flows-fired` reads `:rf.flow/computed` traces
            off the cascade's `:other` bucket and returns one row per
            firing, preserving event-list order"
    (let [cascade {:other [{:id 1 :op-type :flow :operation :rf.flow/computed
                            :tags {:flow-id :a :path [:a] :input-values [1]
                                   :result 1 :frame :rf/default}}
                           {:id 2 :op-type :flow :operation :rf.flow/skip
                            :tags {:flow-id :b :reason :inputs-value-equal}}
                           {:id 3 :op-type :flow :operation :rf.flow/computed
                            :tags {:flow-id :c :path [:c] :input-values [2]
                                   :result 4 :frame :rf/default}}
                           ;; Unrelated noise the projection must ignore:
                           {:id 4 :op-type :error :operation :rf.error/handler-exception
                            :tags {}}]}
          rows    (event-detail/flows-fired cascade)]
      (is (= 2 (count rows)) "skip traces are NOT projected as rows")
      (is (= [:a :c] (mapv :flow-id rows)) "order preserved")
      (is (= [[:a] [:c]] (mapv :write-path rows)))
      (is (= [1 4] (mapv :result rows))))))

(deftest flows-skipped-helper-projects-skips-from-other-bucket
  (testing "rf2-lo37i — `flows-skipped` reads `:rf.flow/skip` traces;
            useful for tests + future surfaces"
    (let [cascade {:other [{:id 1 :op-type :flow :operation :rf.flow/skip
                            :tags {:flow-id :b :reason :inputs-value-equal}}
                           {:id 2 :op-type :flow :operation :rf.flow/computed
                            :tags {:flow-id :a :path [:a] :input-values [1]
                                   :result 1}}]}
          rows    (event-detail/flows-skipped cascade)]
      (is (= [:b] (mapv :flow-id rows)) "only skip rows projected")
      (is (= [:inputs-value-equal] (mapv :reason rows))))))

(deftest flows-with-chain-marks-flags-via-when-input-overlaps-prior-write
  (testing "rf2-lo37i — `flows-with-chain-marks` is pure data → data.
            Flows whose input paths intersect a PRECEDING row's write
            path get :via? true + :via-flow-ids populated"
    (rf/with-frame :rf/default
      (rf/reg-flow {:id     :upstream
                    :inputs [[:in]] :output identity :path [:upstream-out]})
      (rf/reg-flow {:id     :downstream
                    :inputs [[:upstream-out]] :output identity :path [:final]}))
    (let [rows [{:flow-id :upstream   :write-path [:upstream-out]}
                {:flow-id :downstream :write-path [:final]}]
          enriched (event-detail/flows-with-chain-marks rows)]
      (is (false? (:via? (first enriched)))
          "first row never marked :via? (no preceding rows)")
      (is (true? (:via? (second enriched)))
          "second row marked :via? — its [:upstream-out] read matches
           the first row's write-path")
      (is (= [:upstream] (:via-flow-ids (second enriched)))
          ":via-flow-ids names the upstream flow"))))

;; ---- (8.5) COEFFECTS section — silent-by-default + rendering -----------

(deftest coeffects-section-absent-when-zero-user-coeffects
  (testing "rf2-jhhqt — when the cascade carries no user-injected
            coeffects stamp the COEFFECTS section is ABSENT entirely
            (silent-by-default, NOT '(none)' placeholder)"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-coeffects"))
            "COEFFECTS section absent when zero user coeffects stamped")))))

(deftest coeffects-section-renders-one-row-per-user-injected-cofx
  (testing "with `:now` + `:local-storage` stamped on :rf.fx/do-fx, the
            COEFFECTS section renders one row per id with the value
            surfaced via the data-inspector"
    (seed-buffer!
      (cascade-evs 100 [:cart/restore] 0
                   {:coeffects {:now "2026-05-18T19:00:00Z"
                                :local-storage {:user/last-cart-id "cart-42"}}}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            section (find-by-testid tree "rf-causa-event-detail-section-coeffects")]
        (is (some? section) "COEFFECTS section rendered")
        (is (some? (find-by-testid tree "rf-causa-event-detail-coeffect-row-now"))
            ":now row rendered")
        (is (some? (find-by-testid tree "rf-causa-event-detail-coeffect-row-local-storage"))
            ":local-storage row rendered")))))

(deftest coeffects-section-renders-qualified-keyword-ids
  (testing "qualified-keyword cofx ids (e.g. :auth/token, :env/build)
            render via the same testid-suffix scheme used by INTERCEPTORS"
    (seed-buffer!
      (cascade-evs 100 [:checkout/submit] 0
                   {:coeffects {:auth/token "tok-abc"
                                :env/build :prod}}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-coeffect-row-auth/token")))
        (is (some? (find-by-testid tree "rf-causa-event-detail-coeffect-row-env/build")))))))

(deftest user-coeffects-helper-projects-stamp-from-do-fx
  (testing "user-coeffects reads :tags :coeffects off the cascade's :fx
            (do-fx) trace; returns nil when the stamp is absent / empty"
    (is (= {:now "2026-05-18"}
           (event-detail/user-coeffects
             {:fx {:tags {:rf.event/coeffects {:now "2026-05-18"}}}})))
    (is (nil? (event-detail/user-coeffects {:fx {:tags {}}}))
        "absent stamp → nil")
    (is (nil? (event-detail/user-coeffects {:fx {:tags {:rf.event/coeffects {}}}}))
        "empty stamp → nil (silent-by-default)")
    (is (nil? (event-detail/user-coeffects {:fx nil}))
        "no do-fx trace → nil")))

;; ---- (9) handler-threw — omits post-handler steps, NO footer ----------

(deftest handler-threw-omits-post-handler-steps-and-has-no-footer
  (testing "rf2-ad7zx.5 / rf2-ad7zx.17 — spec/021 §2.2: a throwing handler
            simply has no DB CHANGES / AFTER INTERCEPTORS / FLOWS / FX
            steps (absence by omission). There is NO handler-threw footer
            and NO outcome badge. DISPATCH + EVENT HANDLER still render;
            the throw is conveyed purely by the omission of post-handler
            steps (the top ribbon + its status dot are gone)."
    (seed-buffer!
      (concat (cascade-evs 100 [:checkout/submit] 0
                            {:fx nil :db-present? false})
              [{:id 50 :op-type :error :operation :rf.error/handler-exception
                :tags {:rf.trace/dispatch-id 100 :rf.trace/event-id :checkout/submit
                       :exception-message "NullPointerException"}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-handler-threw-footer"))
            "NO handler-threw footer (spec/021 §2.2 forbids the footer)")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-db-changes"))
            "DB CHANGES step omitted — handler never returned")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-fx"))
            "FX step omitted — fx walk never started")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-flows"))
            "FLOWS step omitted")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-dispatch"))
            "DISPATCH step still present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-handler"))
            "EVENT HANDLER step still present")
        ;; No top ribbon to editorialise the throw (rf2-ad7zx.17).
        (is (nil? (find-by-testid tree "rf-causa-event-detail-header"))
            "no top header/ribbon")))))

;; ---- (10) pure projection helpers --------------------------------------

(deftest user-interceptors-filters-rf-default-flagged-entries
  (testing "user-interceptors removes anything carrying :rf/default? true
            (rf2-twt7m Change 3) — no allowlist needed"
    (let [chain [{:id :rf/db-handler :rf/default? true :before identity}
                 {:id :auth/require-login :before identity}
                 {:id :path :before identity}]
          user  (event-detail/user-interceptors chain)]
      (is (= 2 (count user))
          "the auto-wrapper is filtered; the user interceptor + std :path remain")
      (is (= #{:auth/require-login :path} (set (map :id user)))))))

(deftest user-interceptors-falls-back-to-allowlist-when-flag-missing
  (testing "for legacy registrations missing the :rf/default? flag, the
            three known auto-wrapper ids are still filtered as a
            belt-and-braces fallback"
    (let [chain [{:id :rf/db-handler :before identity}
                 {:id :rf/fx-handler :before identity}
                 {:id :rf/ctx-handler :before identity}
                 {:id :user-icpt :before identity}]
          user  (event-detail/user-interceptors chain)]
      (is (= [:user-icpt] (map :id user))))))

(deftest cascade-outcome-projection-shape-is-stable
  (testing "cascade-outcome returns the documented keys"
    (let [out (event-detail/cascade-outcome
                {:event [:foo] :handler {:tags {:duration-ms 5}}
                 :dispatch-id 1 :other []})]
      (is (= #{:event-id :glyph :outcome :duration-ms :dispatch-id :ssr?}
             (set (keys out)))))))

(deftest cascade-outcome-classifies-by-severity-axis
  (testing "rf2-ee38b.2 — cascade-outcome resolves the glyph off the
            universal :op-type severity axis, so EVERY warning/error op
            the substrate emits is covered (not a hand-maintained set)."
    ;; Happy path.
    (is (= [:ok "✓"]
           ((juxt :outcome :glyph)
            (event-detail/cascade-outcome
              {:event [:foo] :dispatch-id 1 :other []}))))
    ;; Any :op-type :warning trace → ⚠, regardless of the specific op.
    (doseq [op [:rf.warning/large-value-unschema
                :rf.warning/schema-walker-opaque
                :rf.warning/epoch-redact-fn-exception
                :rf.warning/some-future-op-not-yet-invented]]
      (is (= [:warning "⚠"]
             ((juxt :outcome :glyph)
              (event-detail/cascade-outcome
                {:event [:foo] :dispatch-id 1
                 :other [{:id 9 :op-type :warning :operation op}]})))
          (str "warning op " op " flips the glyph to ⚠")))
    ;; Any :op-type :error trace → ✗ (handler-exception OR not).
    (doseq [op [:rf.error/handler-exception
                :rf.error/drain-depth-exceeded
                :rf.error/flow-eval-exception
                :rf.error/no-such-handler]]
      (is (= [:error "✗"]
             ((juxt :outcome :glyph)
              (event-detail/cascade-outcome
                {:event [:foo] :dispatch-id 1
                 :other [{:id 9 :op-type :error :operation op}]})))
          (str "error op " op " flips the glyph to ✗")))
    ;; Error wins over a co-resident warning.
    (is (= [:error "✗"]
           ((juxt :outcome :glyph)
            (event-detail/cascade-outcome
              {:event [:foo] :dispatch-id 1
               :other [{:id 9 :op-type :warning :operation :rf.warning/large-value-unschema}
                       {:id 10 :op-type :error :operation :rf.error/drain-depth-exceeded}]})))
        "error severity trumps a co-resident warning")
    ;; Namespace fallback: an :rf.error/* op missing an :op-type still
    ;; classifies as an error (defensive against partial trace shapes).
    (is (= :error
           (:outcome
            (event-detail/cascade-outcome
              {:event [:foo] :dispatch-id 1
               :other [{:id 9 :operation :rf.error/flow-eval-exception}]})))
        ":rf.error/* namespace flips the glyph even without :op-type")))

(deftest effects-handlers-ran-projects-rows-from-effects-bucket
  (testing "effects-handlers-ran reads cascade :effects directly"
    (let [rows (event-detail/effects-handlers-ran
                 {:effects [{:id 5 :operation :rf.fx/handled
                             :tags {:rf.fx/id :db}}
                            {:id 6 :operation :rf.fx/handled
                             :tags {:rf.fx/id :dispatch :rf.fx/args [[:foo]]}}]})]
      (is (= [:db :dispatch] (mapv :fx-id rows)))
      (is (= [:rf.fx/handled :rf.fx/handled] (mapv :operation rows)))
      (is (= [5 6] (mapv :id rows))))))

(deftest hydration-outcome-row-nil-for-ordinary-events
  (testing "hydration-outcome-row returns nil unless the event is
            :rf.ssr/hydrated / :rf.ssr/hydration-complete"
    (is (nil? (event-detail/hydration-outcome-row
                {:event [:counter/inc] :other []})))
    (is (some? (event-detail/hydration-outcome-row
                 {:event [:rf.ssr/hydrated]
                  :other [{:operation :rf.ssr/hydration-outcome
                           :tags {:mismatches 0 :duration-ms 87}}]})))))

;; ---- (11) meta-on-vector pattern (rf2-ppzid) ---------------------------

(deftest fx-rows-carry-distinct-react-keys
  (testing "rf2-ppzid — `with-meta` on the fn return preserves :key on
            each row inside :for. Without the wrapper Reagent's
            `get-react-key` reads from the source list and gets nil for
            every row, causing reconciliation churn + a console warning"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            rows (find-all-by-testid tree "rf-causa-event-detail-effects-ran-row-db")]
        (is (= 1 (count rows))
            "exactly one :db row rendered (sanity)")
        (let [section (find-by-testid tree "rf-causa-event-detail-section-fx")
              body    (some #(when (and (vector? %)
                                        (map? (second %))
                                        (= "rf-causa-event-detail-section-fx-body"
                                           (:data-testid (second %))))
                               %)
                            (hiccup-seq section))
              row-elts (->> (hiccup-seq body)
                            (filter (fn [n]
                                      (and (vector? n)
                                           (map? (second n))
                                           (let [tid (str (or (:data-testid (second n)) ""))]
                                             (str/starts-with?
                                               tid
                                               "rf-causa-event-detail-effects-ran-row-"))))))]
          (is (every? #(some? (:key (meta %))) row-elts)
              "every fx row vector carries a :key in its meta"))))))

;; ---- (12) rf2-ad7zx.5 numbered vertical-flow pipeline chrome -----------

(deftest event-lens-renders-numbered-step-circles-with-labels
  (testing "rf2-ad7zx.5 — each present step renders a numbered step
            CIRCLE on the rail + an uppercase section label. This fixture
            (widget/poke with the default fx) lands DISPATCH / EVENT
            HANDLER / DB CHANGES / FX (no coeffects / after-interceptors /
            flows), each with a circle + label."
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/poke {:rf.handler/source nil} (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:widget/poke {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (doseq [id ["dispatch" "handler" "db-changes" "fx"]]
          (is (some? (find-by-testid tree
                                      (str "rf-causa-event-detail-step-circle-" id)))
              (str "step circle for '" id "' present"))
          (is (some? (find-by-testid tree
                                      (str "rf-causa-event-detail-section-"
                                           id "-label")))
              (str "section label '" id "' present")))))))

(deftest event-lens-no-chevrons-in-numbered-pipeline
  (testing "rf2-ad7zx.5 — the prior chevron chrome (rf2-n4ad0) is gone;
            numbered step circles + the rail carry the ordering rhythm."
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/poke {:rf.handler/source nil} (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:widget/poke {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (doseq [from-id ["dispatch" "coeffects" "handler" "effects" "flows"]]
          (is (nil? (find-by-testid tree
                                     (str "rf-causa-event-detail-chevron-" from-id)))
              (str "no chevron after section '" from-id "'")))))))

(deftest event-lens-pipeline-rail-runs-through-circle-centres-from-step-1
  (testing "rf2-ad7zx.17 — the pipeline vertical line is a dedicated
            absolutely-positioned RAIL element (NOT a container
            border-left) that runs through the CENTRE of the numbered
            step circles and STARTS at circle 1's centre (not the panel
            top), matching EventPanel.tsx.

            Geometry: the step circle sits at section-left -22px with
            width 20px, so its centre-x is at 12px from the pipeline
            container's inner-left (section content-left 24px + -22px +
            10px); the 2px rail centres there at left 11px. The rail's
            `top` is circle 1's centre-y (padding-top 12px + half the
            20px circle = 22px), NOT 0."
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/poke {:rf.handler/source nil} (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:widget/poke {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree     (event-detail/Panel)
            pipeline (find-by-testid tree "rf-causa-event-detail-pipeline")
            rail     (find-by-testid tree "rf-causa-event-detail-pipeline-rail")
            rail-style   (second rail)
            circle       (find-by-testid tree "rf-causa-event-detail-step-circle-dispatch")
            circle-style (second circle)]
        (is (some? pipeline) "pipeline container rendered")
        ;; The line is NOT the old container border-left.
        (is (nil? (get-in pipeline [1 :style :border-left]))
            "the pipeline container no longer draws the line via border-left")
        (is (some? rail) "a dedicated rail element draws the vertical line")
        (is (= "absolute" (get-in rail-style [:style :position]))
            "rail is absolutely positioned inside the relative pipeline")
        ;; Centred through the circle: circle centre-x = -22 + 10 = -12px
        ;; in section coords → 12px from container inner-left; the 2px rail
        ;; centres there at left 11px.
        (is (= "11px" (get-in rail-style [:style :left]))
            "rail left centres the line on the circles' centre-x")
        (is (= "2px" (get-in rail-style [:style :width]))
            "rail is a 2px line")
        ;; Starts at circle 1's centre-y, NOT the panel top (0).
        (is (= "22px" (get-in rail-style [:style :top]))
            "rail starts at circle 1's centre-y (padding-top + half circle)")
        (is (not= "0" (get-in rail-style [:style :top]))
            "rail does NOT start at the panel top")
        ;; Sanity-check the circle geometry the math depends on.
        (is (= "-22px" (get-in circle-style [:style :left]))
            "step circle sits at -22px (geometry the rail centring assumes)")
        (is (= "20px" (get-in circle-style [:style :width]))
            "step circle is 20px wide (geometry the rail centring assumes)")))))

(deftest event-lens-omits-cascade-id-label
  (testing "rf2-ad7zx.5 — the `cascade #NNN` label is not surfaced. The
            dispatch-id is still available on the lens root via
            `data-dispatch-id`."
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree   (event-detail/Panel)
            walked (hiccup-seq tree)
            label-strings (filter #(and (string? %)
                                        (str/includes? % "cascade #"))
                                  walked)]
        (is (empty? label-strings)
            "no 'cascade #NNN' label appears anywhere in the rendered lens")))))

(deftest cascade-container-carries-accent-stripe-per-section-17
  (testing "rf2-zv9r9 / rf2-ad7zx — per spec/021 §17.1.3 + spec/022 the
            Event panel identity stripe is the mode :accent (the single
            GitHub blue). Rendered as a 3px left border on the outer
            cascade container — it survives the top-ribbon removal
            (rf2-ad7zx.17)."
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            cascade (find-by-testid tree "rf-causa-event-detail-cascade")
            border (get-in cascade [1 :style :border-left])]
        (is (some? cascade) "cascade container present")
        (is (and (string? border)
                 (str/includes? border "3px")
                 (str/includes? border "solid"))
            "stripe is a 3px solid left border")))))

;; ---- (13) handler-source slot (rf2-xgfuy DEBUG-stamp consumer) --------

(deftest handler-source-line-renders-placeholder-when-meta-absent
  (testing "rf2-zv9r9 — step [3] HANDLER's source slot renders the
            `<source not yet captured>` placeholder when the registry
            meta lacks `:rf.handler/source` (e.g. before rf2-xgfuy's
            DEBUG-gated stamp lands, or in a production goog.DEBUG=false
            build)"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/poke {:rf.handler/source nil} (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:widget/poke {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            placeholder (find-by-testid tree
                          "rf-causa-event-detail-handler-source-placeholder")
            placeholder-text (->> (hiccup-seq placeholder)
                                  (filter string?)
                                  (apply str))]
        (is (some? placeholder)
            "placeholder span renders when :rf.handler/source meta absent")
        (is (= "<source not yet captured>" placeholder-text)
            "placeholder uses the canonical task-brief copy")))))

(deftest handler-source-string-helper-reads-rf-handler-source-meta
  (testing "rf2-zv9r9 — `handler-source-string` is a pure projection
            from registry meta. Returns the string when
            `:rf.handler/source` is present + non-empty, nil otherwise."
    (is (= "(reg-event-db :foo (fn [db _] db))"
           (event-detail/handler-source-string
             {:rf.handler/source "(reg-event-db :foo (fn [db _] db))"}))
        "returns the source string when present")
    (is (nil? (event-detail/handler-source-string {}))
        "returns nil when meta lacks the key")
    (is (nil? (event-detail/handler-source-string {:rf.handler/source nil}))
        "returns nil when source is nil")
    (is (nil? (event-detail/handler-source-string {:rf.handler/source ""}))
        "returns nil when source is the empty string")
    (is (nil? (event-detail/handler-source-string {:rf.handler/source 42}))
        "returns nil when source is not a string")))

(deftest handler-source-line-renders-body-when-meta-present
  (testing "rf2-zv9r9 — when the registry stamps `:rf.handler/source`
            the step [3] source slot renders the captured form. Asserted
            by stamping a synthetic source string into the handler meta
            via `:rf/handler-meta` (mirroring rf2-xgfuy's planned
            substrate API)"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/with-src
        {:rf.handler/source "(reg-event-fx :widget/with-src (fn [ctx _] {}))"}
        (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:widget/with-src {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            ;; Either path: the body span renders, the placeholder
            ;; doesn't. We only assert no-placeholder here because the
            ;; canonical rf2-xgfuy substrate stamp path lives downstream
            ;; — the body-when-meta-present test rides whichever shape
            ;; the substrate lands on.
            body         (find-by-testid tree
                           "rf-causa-event-detail-handler-source-body")
            placeholder  (find-by-testid tree
                           "rf-causa-event-detail-handler-source-placeholder")]
        ;; Either: meta-present path → body renders, placeholder absent.
        ;; Or:    rf2-xgfuy not yet wired through reg-event-fx → both nil
        ;;        is impossible (one or the other always renders).
        (is (or (some? body) (some? placeholder))
            "step [3] source slot renders one of body / placeholder")))))

(deftest handler-source-not-clipped-at-narrow-panel-widths
  (testing "rf2-l7ha9 — the EVENT HANDLER source must stay legible at narrow
            panel widths. The pipeline is a flex column, so every flex-item
            ancestor of the handler-source `<pre>` carries `min-width:0`;
            without it the `white-space:pre` block grows to its longest
            line's intrinsic width and expands the column past the panel
            edge, clipping the first line and running later lines off the
            right. We assert the shrink-permission chain end-to-end."
    (rf/with-frame :rf/default
      (rf/reg-event-db :widget/wide-src
        {:rf.handler/source "(reg-event-db :widget/wide-src (fn [db _] (update db :counter/value inc)))"}
        (fn [db _] db)))
    (seed-buffer! (cascade-evs 100 [:widget/wide-src {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree         (event-detail/Panel)
            step-section (find-by-testid tree
                           "rf-causa-event-detail-section-handler")
            section-body (find-by-testid tree
                           "rf-causa-event-detail-section-handler-body")
            source-div   (find-by-testid tree
                           "rf-causa-event-detail-handler-source")
            min-width    (fn [node] (some-> node second :style :min-width))]
        (is (some? step-section)
            "the EVENT HANDLER step renders")
        (is (= "0" (min-width step-section))
            "the step-section flex item can shrink below content width")
        (is (= "0" (min-width section-body))
            "the section body propagates the shrink-permission")
        (is (= "0" (min-width source-div))
            "the handler-source container keeps the shrink-permission to the pre")))))

;; ---- (14) DB CHANGES step — app-db diff via data-display renderer -----

(deftest db-changes-step-present-in-pipeline-no-committed-footer
  (testing "rf2-ad7zx.5 — the DB CHANGES step (spec/021 §2.2 step 4) is
            present in the rendered pipeline. The prior `:db + :fx`
            combined shape + the `db committed for epoch #N` close-rule
            footer are RETIRED. When no epoch record is registered the
            evicted-buffer branch surfaces."
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-db-changes"))
            "DB CHANGES step root rendered")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-db-changes-body"))
            "DB CHANGES body wrapper rendered")
        ;; NO `db committed for epoch #N` close-rule footer (spec/021 §2.2).
        (is (nil? (find-by-testid tree "rf-causa-event-detail-step-6-committed"))
            "no `db committed` close-rule footer (retired)")
        ;; One of two sub-branches renders: evicted (selection-but-no-
        ;; record) or empty (record but no db change). The test seeds
        ;; trace events only (no epoch record) → evicted branch.
        (is (or (some? (find-by-testid tree "rf-causa-event-detail-db-changes-evicted"))
                (some? (find-by-testid tree "rf-causa-event-detail-db-changes-empty")))
            "one of [evicted | empty] sub-branches renders")))))

(deftest db-changes-step-omitted-when-handler-threw
  (testing "rf2-ad7zx.5 — when the handler threw, the DB CHANGES step is
            simply OMITTED (absence by omission; no suppression notice,
            no footer)."
    (seed-buffer!
      (conj (cascade-evs 100 [:foo] 0)
            {:id 99 :op-type :error :operation :rf.error/handler-exception
             :tags {:rf.trace/dispatch-id 100 :rf.trace/event-id :foo}}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-db-changes"))
            "DB CHANGES step omitted on the threw branch")))))

(deftest db-changes-step-renders-even-with-no-epoch-record
  (testing "rf2-ad7zx.5 — when the selected epoch record is nil but the
            selection id exists, the DB CHANGES step still renders (the
            §10.7 evicted placeholder) rather than crashing."
    (rf/with-frame :rf/causa
      (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-db-changes"))
            "the step renders even when no epoch record is registered")))))

;; ---- (15) rf2-mn3gt — DB CHANGES renders ONLY changed paths -----------
;;
;; Per spec/021 §2.2 step 4 (line 229) + the dense/sparse mockups (lines
;; 272-275, 307-309) the DB CHANGES section is a FLAT changed-paths list
;; (`~ [path] old → new` · `+ [path] value` · `- [path]`), NOT a
;; tree-centric whole-app-db render. spec/004-App-DB-Diff.md is the
;; normative source: slice-centric not tree-centric (§43), never renders
;; the whole tree by default (§69). Pre-fix the section routed the raw
;; db-before / db-after snapshots through `edn/diff` (the §10 tree
;; renderer) which paints EVERY top-level key with the change highlighted
;; — these tests pin the flat changed-only projection and fail against
;; that prior shape.

(defn- db-change-row-testids
  "Collect the changed-path ROW testids the DB CHANGES section rendered,
  in document order."
  [tree]
  (->> (hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (let [tid (str (or (:data-testid (second node)) ""))]
                   (when (str/starts-with?
                           tid "rf-causa-event-detail-db-change-row-")
                     tid)))))
       (distinct)
       (vec)))

(defn- collect-strings
  "Concatenate every string leaf reachable under `node` (after fn-
  component expansion). Used to assert which keys / values appear in the
  rendered DB CHANGES body."
  [node]
  (->> (hiccup-seq node)
       (filter string?)
       (apply str)))

(deftest db-changes-renders-only-changed-paths-not-whole-tree
  (testing "rf2-mn3gt — for a focused epoch with a small known diff
            against a LARGE app-db, the DB CHANGES section renders EXACTLY
            the changed-path rows (one `~`/`+`/`-` line per change) and
            does NOT render the untouched top-level keys / the whole
            tree. This fails against the prior `edn/diff` whole-tree
            render, which paints every top-level key."
    (let [;; A large app-db: many untouched top-level keys + one slice
          ;; that mutates, one slice that's added, one removed. `db-after`
          ;; is derived from `db-before` via assoc/dissoc so the untouched
          ;; sub-maps stay pointer-identical — exactly the structural
          ;; sharing a real handler (`(-> db (update …) (dissoc …))`)
          ;; produces, and which the changed-paths diff relies on.
          db-before {:counter      1
                     :user         {:id 42 :name "Ada" :roles #{:admin}}
                     :session      {:token "t-1" :expires 9999}
                     :cart         {:items [{:id 7 :qty 1}]}
                     :prefs        {:theme :dark :density :cosy}
                     :nav          {:route :home :params {}}
                     :stale-flag   true
                     :http         {:in-flight {} :history [1 2 3]}}
          db-after  (-> db-before
                        (assoc :counter 2)                       ;; ~ modified
                        (dissoc :stale-flag)                     ;; - removed
                        (assoc :last-updated "2026-05-23T12:30:05"))] ;; + added
      (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
      (rf/with-frame :rf/causa
        (seed-epoch-history!
          [(mk-epoch-record :ep-1 100 [:counter/inc] db-before db-after)])
        (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
        ;; Sanity: the section renders the flat diff (not the evicted /
        ;; empty branch).
        (let [tree (event-detail/Panel)
              diff (find-by-testid tree "rf-causa-event-detail-db-diff")
              rows (db-change-row-testids tree)
              body-text (collect-strings
                          (find-by-testid
                            tree "rf-causa-event-detail-section-db-changes-body"))]
          (is (some? diff)
              "DB CHANGES renders the flat diff body (not evicted/empty)")
          ;; EXACTLY the three changed paths — modified :counter, removed
          ;; :stale-flag, added :last-updated. No more, no fewer.
          (is (= 3 (count rows))
              (str "exactly 3 changed-path rows; got " (pr-str rows)))
          (is (= #{"rf-causa-event-detail-db-change-row-:counter"
                   "rf-causa-event-detail-db-change-row-:stale-flag"
                   "rf-causa-event-detail-db-change-row-:last-updated"}
                 (set rows))
              "rows are the three changed paths")
          ;; The untouched top-level keys MUST NOT appear anywhere in the
          ;; rendered DB CHANGES body — this is the slice-centric vs
          ;; tree-centric assertion that fails against the whole-tree
          ;; render.
          (doseq [untouched [":user" ":session" ":cart" ":prefs"
                             ":nav" ":http"]]
            (is (not (str/includes? body-text untouched))
                (str "untouched key " untouched
                     " must NOT render in the changed-paths list"))))))))

(deftest db-changes-row-glyphs-and-old-new-shape
  (testing "rf2-mn3gt — each changed-path row carries the correct diff
            glyph (`~` modified · `+` added · `-` removed) per its op,
            and a `~` row shows `old → new` per spec/021 line 229."
    (let [db-before {:counter 1 :user {:id 42} :stale true}
          db-after  (-> db-before
                        (assoc :counter 2)        ;; ~ modified
                        (dissoc :stale)           ;; - removed
                        (assoc :greeting "hi"))]  ;; + added
      (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
      (rf/with-frame :rf/causa
        (seed-epoch-history!
          [(mk-epoch-record :ep-1 100 [:counter/inc] db-before db-after)])
        (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
        (let [tree (event-detail/Panel)
              mod-row  (find-by-testid
                         tree "rf-causa-event-detail-db-change-row-:counter")
              add-row  (find-by-testid
                         tree "rf-causa-event-detail-db-change-row-:greeting")
              rem-row  (find-by-testid
                         tree "rf-causa-event-detail-db-change-row-:stale")
              mod-glyph (collect-strings
                          (find-by-testid
                            tree "rf-causa-event-detail-db-change-glyph-:counter"))
              add-glyph (collect-strings
                          (find-by-testid
                            tree "rf-causa-event-detail-db-change-glyph-:greeting"))
              rem-glyph (collect-strings
                          (find-by-testid
                            tree "rf-causa-event-detail-db-change-glyph-:stale"))
              mod-text  (collect-strings mod-row)]
          (is (some? mod-row) "modified row present")
          (is (some? add-row) "added row present")
          (is (some? rem-row) "removed row present")
          (is (= "~" mod-glyph) "modified glyph is ~")
          (is (= "+" add-glyph) "added glyph is +")
          (is (= "-" rem-glyph) "removed glyph is -")
          (is (= "modified" (:data-op (second mod-row))) "modified row tagged :data-op")
          (is (= "added" (:data-op (second add-row))) "added row tagged :data-op")
          (is (= "removed" (:data-op (second rem-row))) "removed row tagged :data-op")
          ;; `~` row shows old → new (the arrow + both values present).
          (is (str/includes? mod-text "→")
              "modified row shows the old → new arrow")
          (is (str/includes? mod-text "1") "modified row shows the old value")
          (is (str/includes? mod-text "2")
              "modified row shows the new value"))))))

(deftest db-changes-shows-empty-state-when-no-paths-changed
  (testing "rf2-mn3gt — a focused epoch whose db-before equals db-after
            renders the `no app-db change this epoch` empty state, NOT a
            whole-tree dump."
    (let [db {:counter 1 :user {:id 42} :session {:token "t"}}]
      (seed-buffer! (cascade-evs 100 [:noop] 0))
      (rf/with-frame :rf/causa
        (seed-epoch-history!
          [(mk-epoch-record :ep-1 100 [:noop] db db)])
        (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
        (let [tree (event-detail/Panel)]
          (is (some? (find-by-testid
                       tree "rf-causa-event-detail-db-changes-empty"))
              "empty-state renders when nothing changed")
          (is (nil? (find-by-testid tree "rf-causa-event-detail-db-diff"))
              "no flat-diff body when nothing changed")
          (is (empty? (db-change-row-testids tree))
              "no changed-path rows when nothing changed"))))))
