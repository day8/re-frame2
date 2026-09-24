(ns re-frame.fresco.nested-frame-boundary-dom-cljs-test
  "MARKUP BELOW A FRAME BOUNDARY ACTS ON THAT BOUNDARY'S FRAME.

  ## The rule

  Everything lowered below an explicit frame head acts on the head's
  frame, root and body alike. What a body CALLS (`h/sub`,
  `h/route-link`, `rf/capture-frame`) runs before any lowering and stays
  in the body's frame; an intent with no frame head above it is still
  the loud refusal.

  ## The misroute it rules out

  A frame head written inside a body — `[h/frame-provider {:frame :b} …]`
  or `[h/frame-root {:id :b} …]` under a view rendering in `:a` — that
  lowered its children with only the frame's NAME rebound would leave the
  body's own frame-locked dispatch in scope, so an inline intent, an
  `h/event` or a render callback directly under the head would write
  `:a`, while the identical button one `h/error-boundary` deeper —
  re-lowered in the wrapper's own render under the frame read from React
  context — would write `:b`. Nothing would refuse and nothing would log.
  At a ROOT the same inline button would be the loud
  `:rf.error/fresco-intent-outside-boundary`, while the wrapped one
  dispatched into the head's frame.

  ## The rows, and what each is red against

  1. A nested `h/frame-provider` in a body: four spellings — inline
     intent, inside `h/error-boundary`, `h/event`, and an intent inside a
     render callback — all land in the provider's frame. A name-only
     rebind reds three of the four.
  2. A nested `h/frame-root` whose frame does not exist yet (a COLD
     ENSURE): an inline intent and an `h/event` land in the ensured frame.
     A name-only rebind reds both, which write the body's.
  3. THE DISCRIMINATING ROW. After row 2's tree has rendered, the ensured
     frame is destroyed and a same-id successor is made WITHOUT
     re-rendering anything. The retained button refuses — exactly one
     `:rf.error/frame-destroyed` — and the successor is untouched. This
     is what separates the head's mechanism (lower in the ready pass,
     after ENSURE) from a naive EAGER bind: under a cold frame-root an
     eager bind captures an address-directed dispatch, and a retained
     callback WRITES the successor. Row 3b builds that naive head out of
     the documented seams and shows it writing the successor — the
     negative control, on the model of
     `reincarnation_routing_cljs_test`'s section 3, so row 3's silence is
     the pin and nothing else.
  4. Roots: the inline button directly under a root `h/frame-provider` or
     `h/frame-root` dispatches into the named frame. A name-only rebind
     reds with `:rf.error/fresco-intent-outside-boundary`.

  Controls, green under either mechanism: the same four buttons in a body
  with NO nested head all land in the body's frame; and an intent with no
  frame head above it is still the loud refusal.

  ## Why each button carries a TAG

  `::bump` appends the button's tag to the frame it reached, so a
  misroute reads as the wrong tag in the wrong frame rather than as a
  count that merely failed to move.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs every row against a
  real React DOM; under `:node-test` the DOM rows degrade to a stated
  skip, while the two lowering-only rows (the root provider and the
  no-boundary control) run in both."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.intent :as rf.fresco.impl.intent]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views.frame-boundary :as rf.views.frame-boundary]
            ["react" :as react]))

(def ^:private main ::main)
(def ^:private preview ::preview)

;; Registered ABOVE `use-fixtures`: the reset fixture captures its baseline
;; when the `use-fixtures` form is EVALUATED, so a registration below it is
;; erased before the first row runs.

(rf/reg-event ::seed (fn [_ _] {:db {:by []}}))

(rf/reg-event ::bump
              (fn [{:keys [db]} [_ tag]]
                {:db (update db :by (fnil conj []) tag)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; nil, not the default: with no ambient dynamic-var frame stamp, the
     ;; only things that can name a frame are the heads and providers in
     ;; the tree — which is the question every row here asks.
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.fresco.impl.collector/reset-runtime!)
                      (rf.error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip! [why]
  (is true (str "a nested-frame-boundary claim needs a real React DOM — " why)))

(defn- bare!
  "Leave React's `act` environment — its queue is not the browser's
  scheduler, and every reading here is taken outside it — and start from
  an empty runtime."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf.fresco.impl.collector/reset-runtime!)
  nil)

(defn- seeded!
  "Make `frame-kw` live, seeded with an empty tag list."
  [frame-kw]
  (rf/make-frame {:id frame-kw})
  (rf/with-frame frame-kw (rf/dispatch-sync [::seed]))
  frame-kw)

(defn- live? [frame-kw] (some? (rf.frame/frame-incarnation-token frame-kw)))

(defn- by
  "The tags that reached `frame-kw`, in order — nil when none did."
  [frame-kw]
  (not-empty (:by (rf/app-db-value frame-kw))))

(defn- refusal
  "The `:rf.error/id` of what `f` throws, or nil if it returns."
  [f]
  (try (f) nil (catch :default e (:rf.error/id (ex-data e)))))

(defn- with-refusals
  "Run `thunk` and collect the always-on `:rf.error/frame-destroyed`
  records it fans — the only observable separating REFUSED from silently
  delivered to the wrong frame. Answers `{:result … :refusals […]}`."
  [thunk]
  (let [seen (atom [])
        k    (keyword "rf2-3x7nj.7.2" (name (gensym "refusal")))]
    (rf.error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try {:result (thunk) :refusals @seen}
         (finally (rf.error-emit/unregister-error-listener! k)))))

(defn- mount!
  "Render `tree` through the public door into a fresh container, and
  answer `[handle container]`."
  [tree]
  (let [c (rf.fresco.impl.mount/fresh-container!)
        h (rf.fresco/client-root)]
    (rf.fresco/render! h tree c)
    [h c]))

(defn- release! [[h c]]
  (rf.fresco/unmount! h)
  (when-some [p (.-parentNode c)] (.removeChild p c))
  nil)

(defn- click! [c sel]
  (let [node (.querySelector c sel)]
    (is (some? node) (str "premise: " sel " is on the page"))
    (when node
      (.click node)
      (rf.fresco.impl.mount/settle!))))

;; A foreign component that calls its render prop once, during its OWN
;; render — so the callback runs after the writing body's extent has
;; unwound, and only what it captured at lowering can name a frame.
(defn- one-row [^js props]
  (react/createElement "div" #js {:className "rows"} ((.-renderRow props) 0)))

(rf.fresco/defhost rows-host one-row {:callbacks {:render-row :render}})

(defn- four-buttons
  "The four spellings, each tagged. Called from a body, so the `h/event`
  callbacks are minted fresh per render as the form requires."
  []
  [:<>
   [:button.a {:on-click [::bump :a]} "a"]
   [rf.fresco/error-boundary {:fallback [:p.fell "fell"]}
    [:button.b {:on-click [::bump :b]} "b"]]
   [:button.c {:on-click (rf.fresco/event [_] [::bump :c])} "c"]
   [rows-host {:render-row (rf.fresco/event [_]
                             (rf.fresco/as-element
                               [:button.d {:on-click [::bump :d]} "d"]))}]])

(defn- click-all! [c]
  (doseq [sel [".a" ".b" ".c" ".d"]] (click! c sel)))

;; ---------------------------------------------------------------------------
;; Control — no nested head: every spelling lands in the body's frame
;; ---------------------------------------------------------------------------

(rf.fresco/defview flat-page
  "The four buttons with no frame head between them and the body."
  [_]
  [:div.page (four-buttons)])

(deftest control-with-no-nested-head-every-spelling-lands-in-the-bodys-frame
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (seeded! main)
          _ (seeded! preview)
          m (mount! [rf.fresco/frame-provider {:frame main} [flat-page {}]])]
      (try
        (click-all! (second m))
        (is (= [:a :b :c :d] (by main))
            "the control's own frame took all four — the instrument works")
        (is (nil? (by preview))
            "and the other live frame took none")
        (finally (release! m))))))

;; ---------------------------------------------------------------------------
;; 1 — a nested h/frame-provider in a body
;; ---------------------------------------------------------------------------

(rf.fresco/defview provider-page
  "A body in `main` that scopes a subtree to `preview`."
  [_]
  [:div.page
   [rf.fresco/frame-provider {:frame preview} (four-buttons)]])

(deftest under-a-nested-frame-provider-every-spelling-lands-in-the-providers-frame
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (seeded! main)
          _ (seeded! preview)
          m (mount! [rf.fresco/frame-provider {:frame main} [provider-page {}]])]
      (try
        (click-all! (second m))
        (is (= [:a :b :c :d] (by preview))
            (str "one subtree scoped to the provider's frame must act on it "
                 "with every spelling; the provider's frame took "
                 (pr-str (by preview)) " and the body's took " (pr-str (by main))))
        (is (nil? (by main))
            "the body's frame took a write meant for the scoped subtree — the
             misroute: the head rebound the frame's name and left the body's
             dispatch in scope")
        (finally (release! m))))))

;; ---------------------------------------------------------------------------
;; 2 — a nested h/frame-root under a COLD ENSURE
;; ---------------------------------------------------------------------------

(def ^:private ensured ::ensured)

(defn- two-buttons []
  [:<>
   [:button.a {:on-click [::bump :a]} "a"]
   [:button.c {:on-click (rf.fresco/event [_] [::bump :c])} "c"]])

(rf.fresco/defview root-page
  "A body in `main` that ENSUREs `ensured` for a subtree."
  [_]
  [:div.page
   [rf.fresco/frame-root {:id ensured} (two-buttons)]])

(deftest under-a-nested-frame-root-an-intent-lands-in-the-ensured-frame
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (seeded! main)
          _ (is (false? (live? ensured))
                "premise: the frame the head names must not exist yet — this
                 row is the COLD ensure")
          m (mount! [rf.fresco/frame-provider {:frame main} [root-page {}]])]
      (try
        (is (true? (live? ensured)) "premise: the head ensured its frame")
        (click! (second m) ".a")
        (click! (second m) ".c")
        (is (= [:a :c] (by ensured))
            (str "the ensured frame took " (pr-str (by ensured))
                 " and the body's took " (pr-str (by main))))
        (is (nil? (by main))
            "the body's frame took a write meant for the ensured subtree")
        (finally (release! m))))))

;; ---------------------------------------------------------------------------
;; 3 — a retained callback across a same-id reincarnation refuses
;; ---------------------------------------------------------------------------

(defn- reincarnate!
  "Destroy `frame-kw`'s live incarnation and make a same-id successor,
  touching no React tree — nothing re-renders the retained buttons."
  [frame-kw]
  (let [before (rf.frame/frame-incarnation-token frame-kw)]
    (rf/destroy-frame! frame-kw)
    (rf/make-frame {:id frame-kw})
    (is (not (identical? before (rf.frame/frame-incarnation-token frame-kw)))
        "premise: the successor is a NEW incarnation under the same id")))

(deftest a-callback-retained-under-a-cold-frame-root-refuses-after-a-reincarnation
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (seeded! main)
          _ (is (false? (live? ensured)) "premise: a COLD ensure")
          m (mount! [rf.fresco/frame-provider {:frame main} [root-page {}]])]
      (try
        (reincarnate! ensured)
        (doseq [sel [".a" ".c"]]
          (let [{:keys [refusals]} (with-refusals #(click! (second m) sel))]
            (is (= 1 (count refusals))
                (str sel ": a callback lowered under the destroyed incarnation "
                     "must refuse LOUDLY — exactly one :rf.error/frame-destroyed; "
                     "got " (count refusals)))
            (is (= ensured (:frame (first refusals)))
                (str sel ": attributed to the head's frame"))))
        (is (nil? (by ensured))
            "the SUCCESSOR's app-db is untouched by a predecessor-era callback")
        (is (nil? (by main))
            "and the body's frame took nothing either")
        (finally (release! m))))))

;; 3b — the NEGATIVE CONTROL. The same head built the naive way: the
;; children lowered EAGERLY, inside the head, under the frame's dispatch
;; captured while the frame did not yet exist. Everything else is the real
;; head's — the same validators, the same core element, the same lowering
;; closure — so the one difference from row 3 is WHEN the dispatch is
;; captured.

(def ^:private naive-frame-root
  (rf.fresco.impl.codec/mint-frame-boundary!
    "test/naive-frame-root"
    (fn [props lower]
      (let [frame-kw (:id props)]
        (rf.views.frame-boundary/frame-root-react-element
          props
          (rf.fresco.impl.intent/with-frame frame-kw
            (rf.fresco.impl.collector/frame-dispatch frame-kw)
            (fn [] (lower frame-kw)))
          're-frame.fresco/frame-root)))))

(rf.fresco/defview naive-root-page [_]
  [:div.page
   [naive-frame-root {:id ensured} (two-buttons)]])

(deftest negative-control-an-eagerly-bound-cold-frame-root-writes-the-successor
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (seeded! main)
          _ (is (false? (live? ensured)) "premise: a COLD ensure")
          m (mount! [rf.fresco/frame-provider {:frame main} [naive-root-page {}]])]
      (try
        (reincarnate! ensured)
        (let [{:keys [refusals]} (with-refusals #(click! (second m) ".a"))]
          (is (= [:a] (by ensured))
              "the eager bind captured an unpinned dispatch, so the retained
               callback WROTE the successor — row 3's untouched successor is
               therefore capable of failing, and passes there because the
               real head lowers after ENSURE")
          (is (empty? refusals)
              "and nothing refused, because nothing was pinned"))
        (finally (release! m))))))

;; ---------------------------------------------------------------------------
;; 4 — roots: the inline intent under a ROOT frame head
;; ---------------------------------------------------------------------------

(deftest an-intent-under-a-root-frame-provider-lowers-with-its-frames-dispatch
  ;; No DOM: the lowering is ordinary CLJS, and the handler it lowered is a
  ;; closure that ignores its event, so it is called here directly.
  (bare!)
  (seeded! preview)
  (let [el       (atom nil)
        refused  (refusal #(reset! el (rf.fresco/as-element
                                        [rf.fresco/frame-provider {:frame preview}
                                         [:button {:on-click [::bump :root]} "x"]])))
        on-click (some-> @el .-props .-children .-props .-onClick)]
    (is (nil? refused)
        (str "an inline intent directly under a root frame-provider was "
             "refused as " (pr-str refused) " — the head names a frame, so "
             "the markup below it has one"))
    (is (fn? on-click) "premise: the button's handler is on the lowered element")
    (when (fn? on-click)
      (on-click nil)
      (is (= [:root] (by preview))
          "the handler dispatched into the provider's frame"))))

(deftest an-intent-under-a-root-frame-head-dispatches-into-the-named-frame
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (seeded! preview)
          _ (is (false? (live? ensured)) "premise: a COLD ensure")
          mounts (atom {})]
      (try
        (testing "a root h/frame-provider"
          (is (nil? (refusal #(swap! mounts assoc :provider
                                (mount! [rf.fresco/frame-provider {:frame preview}
                                         [:button.p {:on-click [::bump :p]} "p"]]))))
              "the root refused an intent that has a frame head above it")
          (when-some [m (:provider @mounts)]
            (click! (second m) ".p")
            (is (= [:p] (by preview)))))
        (testing "a root h/frame-root, cold"
          (is (nil? (refusal #(swap! mounts assoc :root
                                (mount! [rf.fresco/frame-root {:id ensured}
                                         [:button.r {:on-click [::bump :r]} "r"]]))))
              "the root refused an intent that has a frame head above it")
          (when-some [m (:root @mounts)]
            (click! (second m) ".r")
            (is (= [:r] (by ensured)))))
        (finally (run! release! (vals @mounts)))))))

;; ---------------------------------------------------------------------------
;; Control — with NO frame head above it, an intent is still the loud refusal
;; ---------------------------------------------------------------------------

(deftest control-an-intent-with-no-frame-head-above-it-is-still-refused
  (bare!)
  (is (= :rf.error/fresco-intent-outside-boundary
         (refusal #(rf.fresco/as-element [:button {:on-click [::bump :x]} "x"])))
      "the refusal must survive exactly where no frame boundary exists"))
