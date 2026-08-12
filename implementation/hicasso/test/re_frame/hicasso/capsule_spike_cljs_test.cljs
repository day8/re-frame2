(ns re-frame.hicasso.capsule-spike-cljs-test
  "THE §11 REPLAYABLE-VIEW-CAPSULE SPIKE (rf2-hic-082).

  [Specification §11] carries *replayable view capsules* as a **spike
  after L2**, with one deciding rule beside it — *one-shot, commit-owned,
  redacted; stop if representative views are mostly opaque*. This file is
  the whole of that spike's evidence. Its criteria were frozen first, in
  `docs/design/hicasso/product/capsule-replay-criteria.md`, and the
  verdict that applies them is
  `docs/design/hicasso/product/capsule-replay-verdict.md`.

  ## What a capsule is here

  A one-shot record of ONE committed boundary's world:

      {:view        the boundary's name
       :props       the props the call site passed
       :reads       {query-v value} — the read set the render RESOLVED
       :expectation the Spec 004B tree the body produced, on the live frame
       :build       what the runtime can say about the code that ran
       :opacity     what the capsule could not hold, named}

  and a REPLAY is `ht/tree` — the L2 harness — run on the same body with
  `:reads` installed as `:subs` fixtures. The claim under test is that
  the replay is **indistinguishable from the original run on that one
  axis**: the structural tree. Nothing else is claimed. Not markup, not
  lifecycle, not React identity, not commit, not hydration, not paint.

  ## Why this is a spike and not a surface

  Everything below lives INSIDE this namespace. There is no new public
  export, no new namespace, no `:source-paths` entry, no npm dependency
  and no hook: `re-frame.hicasso.impl.collector/shell-hook-ledger` is
  asserted unmoved in [[the-recorder-spends-no-hook-and-holds-no-frame]],
  because I9 is the fence a recorder is most likely to breach. The bead's
  surface line is *spike branch + report; nothing retained without
  graduation*, and this file is written so that deleting it deletes the
  whole experiment.

  ## The lane, and why the node lane is enough

  The recorder needs a real body run and a real commit, and both are
  reachable without a browser: `collector/render-body` is the call the
  shell makes between its two hooks, and `collector/commit-boundary!`
  hands a caller the same `subscribe` closure `useSyncExternalStore`
  would call. That is the seam `re-frame.hicasso.kernel-commit-owns-cljs-test`
  already drives and states the limits of, and this file inherits them:
  driving the seam says WHICH key acquired WHICH reader; it does not say
  that React abandoned anything. The one claim about React's own conduct
  made here is the opposite one — that a `react-dom/server` render is a
  genuine never-committed render — and React performs it.

  So every row runs on `npm run test:cljs`. No row here needs a DOM, and
  none is skipped."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.hicasso.examples.slice.views :as views]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::capsule)

;; The UIx adapter and not plain-atom, for the package's usual reason: a
;; subscription under plain-atom never notifies, so a commit assertion
;; would pass by never firing. `:ambient-frame nil` — this file seats its
;; own frame.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The capsule
;; ---------------------------------------------------------------------------

(defn- realised
  "Force every lazy seq in a hiccup form, WITHOUT turning it into a
  vector.

  Both halves matter. A `for` inside a body is a lazy seq whose elements
  are produced when something walks it, and the reads inside it are legal
  only inside the render window (I7) — so an unforced seq handed out of
  the window would drop reads from the record and refuse when the codec
  finally walked it. And a seq is not a vector to the codec: `child-kind`
  splices a seq and treats a vector as an element, so `(vec …)` here
  would silently change what the recorded hiccup MEANS.

  `walk/postwalk` does exactly this: it `doall`s a seq and hands back a
  seq. Called inside the render window, nowhere else."
  [form]
  (walk/postwalk identity form))

(defn- arm!
  "Arm a ONE-SHOT recorder.

  It holds an in-flight render's buffer and nothing else. Nothing is
  finalised until a commit lands, and after one commit it is spent: no
  second capsule, no continuous retention, no `app-db` snapshot."
  []
  (atom {:armed? true :pending nil :capsule nil}))

(defn- probe!
  "Run one boundary body on the LIVE frame, through the runtime's own
  body-run path, and buffer what the render produced.

  This is the SPECULATIVE half and it finalises nothing — which is the
  point: a render may be abandoned, retried or duplicated, and a capsule
  minted here would be a capsule of a page that never existed."
  [!rec body props]
  (let [!hiccup (volatile! nil)]
    (collector/render-body
      frame-id
      (fn capsule-probe [p] (vreset! !hiccup (realised (body p))) nil)
      props)
    (when (:armed? @!rec)
      (swap! !rec assoc :pending {:props  props
                                  :hiccup @!hiccup
                                  :entry  (collector/last-reads)}))
    nil))

(def ^:private build-identity
  "What the runtime can honestly say about the code that ran.

  Two facts, and they are the two a replay's correctness actually turns
  on: the structural-tree schema version the expectation is written in,
  and the shell's hook ledger, whose movement means the substrate under
  the recording is not the substrate under the replay.

  A build HASH is not among them, and that is a finding rather than an
  omission — nothing in the runtime carries one, so a capsule cannot
  record which bundle produced it. See the verdict's opacity record."
  {:tree-version ht/tree-version
   :shell-hooks  collector/shell-hook-ledger})

(defn- opacity-of
  "The capsule's own opacity record, computed from the expectation.

  `:nodes` is every map node in the tree. `:boundary-nodes` are the
  child boundaries: a capsule sees the CALL and never the rendering, so
  each one is a hole. `:opaque-values` are attribute and prop slots
  recorded as `{:rf.ui/opaque :fn}`."
  [tree]
  (let [nodes  (filterv map? (tree-seq map? :children tree))
        opaque (count (filter (fn [n]
                                (some (fn [[_ v]] (= {:rf.ui/opaque :fn} v))
                                      (merge (:events n) (:attrs n) (:props n))))
                              nodes))]
    {:nodes          (count nodes)
     :boundary-nodes (count (filter :view-id nodes))
     :opaque-values  opaque
     :read-order     :unrecoverable}))

(defn- commit!
  "Drive the seam React occupies, and mint the capsule THERE.

  `commit-boundary!` is the same `subscribe` closure `useSyncExternalStore`
  would call and hands back the same cleanup React would hold, so a
  capsule minted inside it is minted on a committed read set rather than
  on a speculative one. The cleanup is called before returning: a
  recorder that left a reader on a cell would be a leak wearing a
  capsule's name.

  Values are taken with `rf/subscribe-once` — the sanctioned snapshot —
  and not off the cell table, because a cold read retains no cell at all
  and a recorder that read the table would silently record a partial
  world."
  [!rec view-name]
  (let [{:keys [armed? pending]} @!rec]
    (when (and armed? pending)
      (let [entry   (:entry pending)
            cleanup (collector/commit-boundary! entry (fn [] nil))
            reads   (into {}
                          (map (fn [sub-key]
                                 (let [query-v (second sub-key)]
                                   [query-v (rf/with-frame frame-id
                                              (rf/subscribe-once query-v))])))
                          (collector/reads-of entry))
            tree    (ht/tree [(constantly (:hiccup pending)) {}] {})]
        (cleanup)
        (swap! !rec assoc
               :armed?  false
               :pending nil
               :capsule {:view        view-name
                         :props       (:props pending)
                         :reads       reads
                         :expectation tree
                         :build       build-identity
                         :opacity     (opacity-of tree)})))
    (:capsule @!rec)))

(defn- record!
  "Record one capsule for `[minted-view props]` on the live frame: arm,
  render, commit."
  [minted props]
  (let [!rec (arm!)]
    (probe! !rec (codec/retained-body minted) props)
    (commit! !rec (ht/view-name minted))))

(defn- replay
  "Replay a capsule through the L2 harness against `body` — by default
  the body it was recorded from.

  The world the replay runs in is the capsule and nothing else. A read
  the capsule does not answer REFUSES rather than resolving to nil, which
  is what makes a grown read set a red rather than a quiet pass."
  ([capsule minted] (replay capsule minted (codec/retained-body minted)))
  ([capsule _minted body]
   (ht/tree [body (:props capsule)] {:subs (:reads capsule)})))

;; ---------------------------------------------------------------------------
;; The world under the recording — the slice, seeded
;; ---------------------------------------------------------------------------

(defn- seeded!
  "The slice's own frame, seeded by the slice's own event.

  `IS_REACT_ACT_ENVIRONMENT` is set outright because a server render is
  not inside React's `act` queue, exactly as the commit-owns node witness
  sets it."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (routes/register!)
  (rf/make-frame {:id frame-id :initial-events [[::events/seed]]})
  frame-id)

(def ^:private slice-population
  "THE POPULATION, taken whole (criteria C1). Every `h/defview` boundary
  in the slice's `views.cljs`, with the props its call site passes —
  read off `views.cljs` itself, not chosen."
  [[views/chrome       {}]
   [views/article-row  {:slug "intents" :title "Intents are data"
                        :published? true :tags ["intents" "data"]}]
   [views/feed-page    {}]
   [views/editor       {:slug "intents"}]
   [views/article-page {}]
   [views/app          {}]])

;; ---------------------------------------------------------------------------
;; Two spike-local views, so the divergence control changes a real BODY
;; ---------------------------------------------------------------------------
;;
;; A "mutated body" that edited the recorded hiccup afterwards would be a
;; control over the comparison, not over the mechanism. These are three
;; real `defview` declarations differing by exactly one thing each.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-sub ::tone  (fn [db _] (:tone db)))
(rf/reg-event ::seed-panel (fn [_ [_ db]] {:db db}))

(h/defview panel
  "The recorded body."
  [{:keys [id]}]
  [:div.panel {:data-id id}
   [:button.act {:type "button" :on-click [::act id]} (h/sub [::label])]])

(h/defview panel-typo
  "The SAME body with one attribute changed — the regression a seed exists
  to catch."
  [{:keys [id]}]
  [:div.panel {:data-id id}
   [:button.act {:type "button" :on-click [::act id]} (h/sub [::label])]
   [:span.stray "added"]])

(h/defview panel-plus
  "The same body having GROWN a read. The capsule answers `::label` and
  nothing else, so this must refuse rather than render."
  [{:keys [id]}]
  [:div.panel {:data-id id}
   [:button.act {:type "button" :on-click [::act id]} (h/sub [::label])]
   [:em.tone (h/sub [::tone])]])

(h/defview crossing
  "A raw React escape — the only door through which a component that
  spends its own React hook (a `useId`, say) reaches a Hicasso view."
  [_]
  [:div [:> (fn [] nil) {}]])

(defn- panel-frame! []
  (rf/make-frame {:id frame-id
                  :initial-events [[::seed-panel {:label "Go" :tone "warm"}]]})
  frame-id)

(defn- refusal
  "The ex-data of the refusal `f` raises, or nil if it raises nothing."
  [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---------------------------------------------------------------------------
;; C1 — opacity, both registered readings
;; ---------------------------------------------------------------------------

(deftest c1-the-opacity-census-over-the-whole-population
  (seeded!)
  (let [rows (mapv (fn [[minted props]]
                     (let [c (record! minted props)]
                       (assoc (:opacity c)
                              :view (str/replace (:view c) #".*/" "")
                              :reads (count (:reads c)))))
                   slice-population)
        opaque-views (filterv #(zero? (:nodes %)) rows)
        ratios (mapv (fn [{:keys [nodes boundary-nodes opaque-values]}]
                       (/ (+ boundary-nodes opaque-values) (max nodes 1)))
                     rows)
        median (nth (vec (sort ratios)) (quot (count ratios) 2))]
    ;; Published as counts, so the ratio is re-checkable without re-running.
    (println "\n[rf2-hic-082] C1 opacity census, slice population:")
    (doseq [r rows]
      (println (str "  " (:view r)
                    " nodes=" (:nodes r)
                    " boundary-nodes=" (:boundary-nodes r)
                    " opaque-values=" (:opaque-values r)
                    " reads=" (:reads r))))
    (println (str "  C1a capsule-opaque views = " (count opaque-views) "/" (count rows)))
    (println (str "  C1b content-opacity ratios = " (mapv double ratios)))
    (println (str "  C1b median = " (double median)))

    (testing "C1a — a capsule can be recorded and replayed for every view"
      (is (= 6 (count rows)))
      (is (zero? (count opaque-views))
          "no view in the population is capsule-opaque: the L2 walk
           refused none of them, so C1a's threshold is not approached"))

    (testing "C1b — the median content opacity"
      (is (<= median 0.5)
          "the registered threshold is STOP above one half"))))

;; ---------------------------------------------------------------------------
;; The headline claim — a replay is the original run, on the stated axis
;; ---------------------------------------------------------------------------

(defn- de-addressed
  "The tree with every `:frame` slot replaced by one token.

  It exists because of what the measurement below found, and it is
  written here rather than folded into the recorder deliberately: a
  capsule that normalised addresses on the way in would be recording a
  PROJECTION of the tree the body produced, not the tree. This is a
  reader for the report, so the residual difference can be named."
  [tree]
  (walk/postwalk (fn [x] (if (and (map-entry? x) (= :frame (key x)))
                           [:frame ::any-frame]
                           x))
                 tree))

(deftest a-replay-is-the-original-run-except-where-an-address-was-baked-in
  (seeded!)
  (let [rows (mapv (fn [[minted props]]
                     (let [c (record! minted props)
                           r (replay c minted)]
                       {:view    (str/replace (:view c) #".*/" "")
                        :exact?  (= (:expectation c) r)
                        :modulo? (= (de-addressed (:expectation c)) (de-addressed r))
                        :names-a-frame? (str/includes? (pr-str (:expectation c))
                                                       (str frame-id))}))
                   slice-population)]
    (println "\n[rf2-hic-082] replay vs original run, slice population:")
    (doseq [r rows]
      (println (str "  " (:view r)
                    " exact=" (:exact? r)
                    " modulo-address=" (:modulo? r)
                    " expectation-names-the-frame=" (:names-a-frame? r))))
    (println (str "  exact = " (count (filter :exact? rows)) "/" (count rows)))

    (testing "modulo the frame address, every replay IS the original run"
      (is (every? :modulo? rows)
          "the body re-run under the capsule's world alone produces the
           tree the live frame produced, in every node but one slot"))

    (testing "and the exception is a runtime ADDRESS baked into the tree"
      (is (= [true false true true false true] (mapv :exact? rows))
          "article-row and article-page do NOT replay exactly. Both call
           `h/route-link`, which captures the frame AT RENDER because a
           click fires after the render scope has unwound — so the anchor's
           `[::h/navigate {:frame …}]` vector carries the recording frame's
           keyword as data. This is the incarnation law reaching the
           capsule: where a delayed operation lands is fixed when it is
           MINTED, never when it is invoked")
      (is (= (mapv :names-a-frame? rows) (mapv (complement :exact?) rows))
          "and the two sets coincide exactly — a view's expectation fails
           to replay if and only if the recording frame's keyword is
           somewhere inside it"))))

;; ---------------------------------------------------------------------------
;; C2 — the divergence control, DIRECTION A: it must diverge
;; ---------------------------------------------------------------------------

(deftest c2a-the-world-moved
  (panel-frame!)
  (let [capsule (record! panel {:id 1})
        moved   (assoc-in capsule [:reads [::label]] "Stop")]
    (is (= (:expectation capsule) (replay capsule panel))
        "premise: the unperturbed replay agrees")
    (is (not= (:expectation capsule) (replay moved panel))
        "one recorded read value differs, and the replay differs — the
         comparison CAN fail, which is what makes the row above a
         measurement rather than a coincidence")))

(deftest c2a-the-body-changed
  (panel-frame!)
  (let [capsule (record! panel {:id 1})]
    (is (not= (:expectation capsule)
              (replay capsule panel (codec/retained-body panel-typo)))
        "the SAME capsule, replayed against a body that differs by one
         element, does not reproduce the expectation. This is the
         regression-seed claim proper: the capsule holds the world, and
         the code is what is under test")))

(deftest c2a-the-read-set-grew
  (panel-frame!)
  (let [capsule (record! panel {:id 1})
        data    (refusal #(replay capsule panel (codec/retained-body panel-plus)))]
    (is (= :rf.error/hicasso-test-missing-read-fixture (:rf.error/id data))
        "a body that grew a read REFUSES against the old capsule rather
         than rendering a plausible tree")
    (is (= [[::tone]] (:missing data))
        "and it names the key, so the seed says what changed")))

;; ---------------------------------------------------------------------------
;; C2 — the divergence control, DIRECTION B: it must NOT diverge
;; ---------------------------------------------------------------------------

(deftest c2b-frame-identity-holds-for-a-body-that-mints-no-delayed-operation
  (panel-frame!)
  (let [capsule (record! panel {:id 1})]
    (is (not (str/includes? (pr-str capsule) (str frame-id)))
        "the panel's capsule names no frame anywhere in it — its intents
         are plain vectors and a plain vector has no address")
    (rf/destroy-frame! frame-id)
    (rf/make-frame {:id frame-id
                    :initial-events [[::seed-panel {:label "different" :tone "cold"}]]})
    (is (= (:expectation capsule) (replay capsule panel))
        "so a same-id REINCARNATION with a different app-db changes
         nothing: the replay's world is the capsule, and `ht/tree` mints a
         probe frame of its own for every call")))

(deftest c2b-frame-identity-FAILS-for-a-body-that-mints-one
  (seeded!)
  (let [capsule (record! views/article-row
                         {:slug "intents" :title "Intents are data"
                          :published? true :tags ["intents" "data"]})]
    (is (str/includes? (pr-str (:expectation capsule)) (str frame-id))
        "THE NEGATIVE RESULT. `h/route-link` captures the frame at render,
         so the recorded tree carries the recording frame's keyword as
         ordinary data inside the anchor's navigate intent")
    (is (not= (:expectation capsule) (replay capsule views/article-row))
        "and the replay, which necessarily runs on another frame, does not
         reproduce it. Direction B's frame-identity row FAILS as
         registered: the capsule is not address-free, and nothing about
         the recording says which of its values are addresses")
    (is (= (de-addressed (:expectation capsule))
           (de-addressed (replay capsule views/article-row)))
        "the divergence is confined to that one slot, which is what makes
         it a finding rather than noise")))

(deftest c2b-state-the-body-never-read-moves-nothing
  (panel-frame!)
  (let [before (record! panel {:id 1})]
    (rf/with-frame frame-id
      (rf/dispatch-sync [::seed-panel {:label "Go" :tone "cold" :unread :moved}]))
    (let [after (record! panel {:id 1})]
      (is (= (:expectation before) (:expectation after))
          "`::tone` and `:unread` both moved; the panel reads neither, so
           two recordings taken either side of the move are equal")
      (is (= (:reads before) (:reads after))
          "and the record itself did not widen — a capsule is the read set
           the render RESOLVED, never a projection of app-db"))))

(deftest c2b-tree-position-is-not-decidable-at-this-tier
  (panel-frame!)
  (let [capsule (record! panel {:id 1})]
    (is (= (replay capsule panel) (replay capsule panel))
        "the replay is deterministic across calls, each of which mints its
         own probe frame")
    (is (= :rf.error/hicasso-test-plain-fn-head
           (:rf.error/id (refusal #(ht/tree [(fn [p] [:section [(codec/retained-body panel) p]])
                                             (:props capsule)]
                                            {:subs (:reads capsule)}))))
        "STATED SKIP, and the refusal is the reason for it. L2 runs ONE
         body and always at the root: a body reached as a child is either
         a boundary CALL, whose body does not run, or a plain fn in head
         position, which the runtime and the kit both refuse. So there is
         no tree POSITION to vary at this tier and C2b's third row is not
         decidable here. What decides it is landed and cited rather than
         re-derived: `re-frame.hicasso.identifier-prefix-ssr-dom-cljs-test`
         measures a `useId` moving with position and not with prefix. The
         crossing row below is why that never reaches a capsule anyway")))

;; ---------------------------------------------------------------------------
;; C3 — one-shot and commit-owned
;; ---------------------------------------------------------------------------

(deftest c3-a-never-committed-render-finalises-no-capsule
  (seeded!)
  (let [!rec (arm!)]
    ;; A `react-dom/server` render runs every body for real and then
    ;; throws the tree away: React calls `getServerSnapshot` and never
    ;; `subscribe`. It is a genuine never-committed render performed by
    ;; React, not a stand-in for one.
    (collector/reset-body-runs!)
    (let [markup (react-dom-server/renderToString
                   (mount/provider frame-id (codec/root-element frame-id [views/chrome {}])))]
      (is (str/includes? markup "slice-chrome") "the bodies really ran")
      (is (pos? (collector/body-runs)) "and the runtime counted them"))
    (is (nil? (:capsule @!rec))
        "no commit reached the recorder, so it minted nothing")
    (is (true? (:armed? @!rec))
        "and it is still armed — a never-committed render does not spend
         a one-shot recorder")))

(deftest c3-a-commit-finalises-exactly-one-and-spends-the-recorder
  (panel-frame!)
  (let [!rec  (arm!)
        body  (codec/retained-body panel)]
    (probe! !rec body {:id 1})
    (is (nil? (:capsule @!rec)) "the render alone finalises nothing")
    (is (some? (:pending @!rec)) "it buffers ONE in-flight render")
    (let [first-capsule (commit! !rec "panel")]
      (is (some? first-capsule))
      (is (false? (:armed? @!rec)) "the recorder is spent")
      (is (nil? (:pending @!rec)) "and holds no buffer afterwards")
      (probe! !rec body {:id 2})
      (is (nil? (:pending @!rec)) "a spent recorder buffers nothing")
      (is (identical? first-capsule (commit! !rec "panel"))
          "and mints no second capsule"))))

(deftest c3-the-record-is-the-resolved-read-set-and-not-app-db
  (panel-frame!)
  (let [capsule (record! panel {:id 1})]
    (is (= #{[::label]} (set (keys (:reads capsule))))
        "one key — the one the body read. `::tone` is registered, live and
         readable, and it is not in the record")
    (is (not (contains? (:reads capsule) [::tone])))))

;; ---------------------------------------------------------------------------
;; C4 — the landed facts, and C5's fence
;; ---------------------------------------------------------------------------

(deftest the-recorder-spends-no-hook-and-holds-no-frame
  (is (= [:use-context/frame :use-sync-external-store/subscription-epoch]
         collector/shell-hook-ledger)
      "I9: an ordinary boundary's shell calls exactly two React hooks and
       the recorder did not add a third. The whole recorder is outside the
       shell — it drives `render-body` and `commit-boundary!`, the two
       doors the shell itself uses between its hooks"))

(deftest a-react-crossing-is-capsule-opaque
  (seeded!)
  (let [data (refusal #(record! crossing {}))]
    (is (= :rf.error/hicasso-test-react-is-opaque (:rf.error/id data))
        "a raw React escape refuses the walk, so no capsule exists for a
         view containing one. That is the whole answer to the `useId`
         fact: a component spending its own React hook reaches a Hicasso
         view only through a crossing, and every crossing is opaque to a
         capsule — the capsule cannot record a position-derived id
         because it cannot record the component at all")
    (is (= :assert-it-at-l3 (:recovery data))
        "and the refusal names the tier that can")))

(deftest the-capsule-carries-a-build-identity-it-can-actually-assert
  (panel-frame!)
  (let [capsule (record! panel {:id 1})]
    (is (= {:tree-version 1
            :shell-hooks  [:use-context/frame
                           :use-sync-external-store/subscription-epoch]}
           (:build capsule))
        "the two facts a replay's correctness turns on. A bundle hash is
         NOT among them, because nothing in the runtime carries one — a
         gap the opacity record names rather than papers over")
    (is (= :unrecoverable (:read-order (:opacity capsule)))
        "and the lane's `ordered read values` is not capturable at the
         published seam: `collector/reads-of` answers a SET, and the
         ordered scratch is private. The record says so rather than
         implying an order it does not have")))
