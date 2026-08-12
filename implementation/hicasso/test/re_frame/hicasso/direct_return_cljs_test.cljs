(ns re-frame.hicasso.direct-return-cljs-test
  "RUNG 3 — THE DIRECT RETURN, AND THE FOUR SURFACES IT WALKS PAST
  (rf2-hic-033).

  A `defview` body may answer an already-constructed React element
  instead of hiccup. The boundary is unchanged around it — same frame,
  same ambient reads, same props, same memo wrapper, same lifecycle —
  and the hiccup walk is skipped for that result. [spec §5, Rung 3]

  ## The accept path is not new, and this file does not re-establish it

  `codec/as-element` has had a total `:react-element` arm since
  rf2-hic-030, asked after `vector?`, so a returned element has always
  passed through untouched;
  `native-fence-cljs-test/the-two-languages-compose-without-either-rewriting-the-other`
  is the row that says so, and `native-hooks-dom-cljs-test/host` writes
  the spelling as ordinary source. What was never established is the
  half a reader actually has to trust before moving a hot boundary onto
  it: that **nothing else runs**. An escape whose cost is unpriced is a
  guess, and an escape that quietly still lowers is worse than no escape
  at all.

  ## The pair

  [[hiccup-body]] and [[direct-body]] are the same view written twice —
  the same props, the same two ambient reads, the same data, and DOM
  that is **byte-equal** under React's own server renderer. One writes
  hiccup; one writes `n/$`. Every row below is a difference between
  them, so a row can only be about the lowering.

  Byte equality is the fairness gate and it is checked before anything
  else is claimed, because two arms that render different pages can be
  made to differ on any axis at all.
  [[the-two-arms-are-the-same-page-and-the-gate-can-tell-when-they-are-not]]
  also drives them apart on purpose, so the gate is known to be able to
  fail.

  ## The deterministic delta — what a direct return does NOT do

  The four surfaces the spec says native semantics begin inside of, each
  counted at the shipping function React would have reached:

  | surface | probe | the arm that must reach it |
  |---|---|---|
  | hiccup walk | `codec/vec->element` | hiccup |
  | prop pipeline | `codec/convert-props` | hiccup |
  | event lowering | `intent/lower-prop` | hiccup |
  | controlled repair | `controlled/install!` | hiccup |

  and the key diagnostic, which has no function worth probing because
  its whole observable is the warning it prints, counted at
  `console.warn` (Hicasso's channel; React's own key warning is a
  `console.error` and is not this file's business —
  `keywarn-dom-cljs-test`).

  **Every count is read against a FLOOR, not against zero.** Both arms
  are reached through the same boundary crossing, and the crossing is
  itself hiccup: `[some-view {…}]` is a vector, so `root-element` walks
  it whatever the body answers. [[floor-body]] is that crossing carrying
  nothing, and the claim is `direct = floor < hiccup` — which says what
  a bare `zero?` could not, namely that the crossing is still paid and
  only the body's own lowering is gone.

  ### The reading, and the control that moves it

  One server render of the pair, counted at the four shipping functions.
  These are ENTRIES, not durations — a count is deterministic, so it is
  the same number on any machine under any load, which is why it is the
  half of the delta this file can honestly take:

  | surface | floor | hiccup arm | direct arm | what the escape drops |
  |---|---|---|---|---|
  | `codec/vec->element` | 1 | 4 | 1 | 3 — the div, the span, the input |
  | `codec/convert-props` | 0 | 3 | 0 | 3 — one per element |
  | `intent/lower-prop` | 0 | 2 | 0 | 2 — `:on-click`, `:on-input` |
  | `controlled/install!` | 0 | 3 | 0 | 3 — asked of every element |

  **The control that moves every one of them is one line**: write
  [[direct-body]] as hiccup and the four rows read 4 / 3 / 2 / 3 against
  floors of 1 / 0 / 0 / 0, all four go red, and so do the hook row and
  the L2 row. Taken 2026-08-12 on this revision; the table is the
  reading, `walks-past` is the gate, and the gate is deliberately the
  weaker `direct = floor < hiccup` so that a codec refactor which moves a
  call site does not red a file whose subject is the escape.

  This is the delta that can be taken on a loaded machine, and it is not
  the whole of the delta the bead asks for: see **What is NOT here**.

  ## Where the fence itself is established, and why not again here

  *Native semantics begin inside the returned element* is the fence, and
  the fence's own rows are `native-fence-cljs-test`'s: an intent vector
  at a native callback slot refuses, hiccup in a native child position
  refuses, and the two languages compose without either rewriting the
  other. They are cited rather than restated — a second copy of a
  refusal is a second thing to drift, and the literal-child half of it
  refuses at MACROEXPANSION, so a second copy could not even be written
  in the same shape.

  What this file adds past that fence is the direction the fence cannot
  see: the fence stops hiccup getting IN, and the rows below establish
  that the codec does not get OUT.

  ## What a direct return still costs — I9

  Exactly two hooks, counted at React's own dispatcher through
  [[re-frame.hicasso.hook-probe]]. The shell is the React-hook spine
  (rf2-hic-018) and I9 is frozen at two; a boundary that answered an
  element is still a boundary and pays the same two, which is the reason
  Rung 3 is an escape from the CODEC and not from the boundary.

  ## Hooks in the body — a law, and deliberately not a refusal

  Spec §5 Rung 3: *hooks do not belong in the dynamically composed
  `defview` body; hook-intensive behavior belongs in a separately
  defined native component so hook order cannot depend on Hicasso data
  paths.* There is no runtime refusal for it and this file does not add
  one. `defview` reads no body, by contract (`h/defview`'s own
  docstring), so detecting a hook call inside one needs a compiler that
  analyses the body — which
  `lanes/hot-path-architecture.md#explicit-refusals` forbids outright.
  React's rules of hooks are the enforcement, `n/defcomponent` is the
  place they are legal, and the law is stated where an author reads it.

  ## What is NOT here, and must not be read off it

  **The clock.** The bead's direct-return delta is a pinned interleaved
  run on both named reference profiles, published with witness,
  instrument and confidence. No figure in this file is a duration, and
  nothing here licenses a claim about how much faster a direct return
  is. What is established is the shape the clock would price — one
  page, two arms, byte-equal DOM — and which surfaces the second arm
  does not enter. The clock row is `rf2-hic-078`.

  Runtime: `-cljs-test`, no DOM. `react-dom/server` runs bodies for real
  — the shell's hooks, the ambient reads, the codec's emission — through
  React's own dispatcher, so the whole population this file measures is
  reachable in the node lane, which is in the fast-PR spine. The browser
  lane is not, and `hook-budget-cljs-test` takes the same view for the
  same reason."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.hook-probe :as hook-probe]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.controlled :as controlled]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::direct-return)

(rf/reg-sub :dr/label (fn [db [_ id]] (get-in db [:labels id])))
(rf/reg-sub :dr/tags  (fn [db [_ id]] (get-in db [:tags id])))

(rf/reg-event :dr/seed (fn [_ [_ db]] {:db db}))

(def ^:private seed-db
  {:labels {1 "quarterly"} :tags {1 ["clj" "react"]}})

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The pair — one page, written twice
;; ---------------------------------------------------------------------------
;;
;; Written as named fns and minted from them, which `h/defview`'s
;; anonymity contract makes safe, because L2 runs a BODY and the rows in
;; §5 need to hand it one.
;;
;; The prop keys are written in the SAME ORDER on both sides and the
;; class shorthand is spelled out rather than folded, because both sides
;; normalise through the one `impl.slot/prop-name` rule and React's
;; server renderer emits attributes in props order. Same rule plus same
;; order is what makes byte equality a property of the source rather
;; than something to discover.

(defn hiccup-body
  "The ordinary Rung 1 spelling. Two ambient reads, an intent vector at
  a callback slot, and a controlled field — one member of each
  population the four probes below count."
  [{:keys [id]}]
  (let [label (h/sub [:dr/label id])
        n     (count (h/sub [:dr/tags id]))]
    [:div {:class "row" :on-click [:dr/picked id]}
     [:span {:class "label"} (str label " " n)]
     [:input {:class "field" :value label :on-input [:dr/edited id ::h/value]}]]))

(defn direct-body
  "The Rung 3 spelling of the same page. The reads are the boundary's,
  unchanged — that is the whole of the escape: native semantics begin
  at the element, not at the frame.

  Past `n/$` there is no intent lowering, so the callback slot holds an
  ordinary function; and no controlled repair, so the field's echo is
  the author's to write. Both are the escape's price and both are
  visible in this source."
  [{:keys [id]}]
  (let [label (h/sub [:dr/label id])
        n     (count (h/sub [:dr/tags id]))]
    (n/$ :div {:class "row" :on-click (fn [_] nil)}
         (n/$ :span {:class "label"} (str label " " n))
         (n/$ :input {:class "field" :value label :on-change (fn [_] nil)}))))

(defn floor-body
  "The crossing carrying nothing. Every probe below reads its floor here
  — a boundary is reached through a hiccup vector whatever its body
  answers, so `zero?` is the wrong assertion and this is the right one."
  [_]
  nil)

(h/defview hiccup-arm [props] (hiccup-body props))
(h/defview direct-arm [props] (direct-body props))
(h/defview floor-arm  [props] (floor-body props))

;; ---------------------------------------------------------------------------
;; The key pair — a seq of boundary members, which is the population the
;; key diagnostic is FOR
;; ---------------------------------------------------------------------------
;;
;; `codec/check-member-key!` warns for a boundary-headed member of a
;; lowered seq and deliberately stays quiet where React already speaks —
;; the missing key on a plain tag, a host or a `[:>]` child, which it
;; declines on a costed ~150 ns/member argument. So a seq of `[:span …]`
;; would draw nothing from EITHER arm and the row below would be green
;; without meaning anything. The members are boundaries, and their Rung 4
;; counterpart is an `n/defcomponent` rendering the same span.

(h/defview tag-row  [{:keys [label]}] [:span {:class "tag"} label])

(n/defcomponent native-tag-row [^js props]
  (n/$ :span {:class "tag"} (.-label props)))

(h/defview key-hiccup-arm
  [{:keys [id]}]
  [:div {:class "tags"}
   (for [t (h/sub [:dr/tags id])] [tag-row {:label t}])])

(h/defview key-direct-arm
  [{:keys [id]}]
  (n/$ :div {:class "tags"}
       (map (fn [t] (n/$ native-tag-row {:label t})) (h/sub [:dr/tags id]))))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  ([] (seeded! seed-db))
  ([db]
   (support/leave-act-environment!)
   (rf/make-frame {:id frame-id})
   (rf/with-frame frame-id (rf/dispatch-sync [:dr/seed db]))
   frame-id))

(defn- render!
  "`view` as the page's root, to static markup. `renderToStaticMarkup`
  rather than `renderToString`: hydration markers are React's bookkeeping
  and would put a difference between the arms that is not in the page."
  [view]
  (react-dom-server/renderToStaticMarkup
    (mount/provider frame-id (codec/root-element frame-id [view {:id 1}]))))

(defn- probe
  "Entries into the shipping var `install!` wraps, while `view` renders.
  Restores in a `finally`, so a throwing arm cannot leave the codec
  wrapped for the rest of the suite."
  [install! restore! view]
  (let [n (atom 0)]
    (install! n)
    (try (render! view) (finally (restore!)))
    @n))

(defn- probes
  "The floor, the hiccup arm and the direct arm through one probe."
  [install! restore!]
  {:floor  (probe install! restore! floor-arm)
   :hiccup (probe install! restore! hiccup-arm)
   :direct (probe install! restore! direct-arm)})

(defn- walks-past
  "The one assertion shape every surface row makes: the hiccup arm
  reaches the surface past the crossing's floor, and the direct arm
  reaches exactly the floor and no more."
  [surface {:keys [floor hiccup direct]}]
  (is (> hiccup floor)
      (str "CONTROL — the hiccup arm must reach " surface " past the "
           "crossing's floor (" floor "), or the row below is read off a "
           "probe that was never live; got " hiccup))
  (is (= floor direct)
      (str "a direct return must not reach " surface ". The crossing's "
           "floor is " floor " and the direct arm read " direct)))

(defn- warnings-of
  "Hicasso's warning channel while `view` renders. React's own key
  warning is a `console.error` and does not arrive here."
  [view]
  (let [seen (atom [])
        real js/console.warn]
    (set! js/console.warn (fn [& args] (swap! seen conj (apply str args))))
    (try (render! view) (finally (set! js/console.warn real)))
    @seen))

;; ---------------------------------------------------------------------------
;; 1. The fairness gate
;; ---------------------------------------------------------------------------

(deftest the-two-arms-are-the-same-page-and-the-gate-can-tell-when-they-are-not
  (seeded!)
  (let [hiccup (render! hiccup-arm)
        direct (render! direct-arm)]

    (testing "byte-equal markup — same props, same reads, same data, same
              DOM, so every row below is about the lowering and nothing
              else"
      (is (= hiccup direct)))

    (testing "and the page is the page, not an empty string two arms agree
              on: both reads landed and both populations rendered"
      (is (= (str "<div class=\"row\"><span class=\"label\">quarterly 2</span>"
                  "<input class=\"field\" value=\"quarterly\"/></div>")
             hiccup)))

    (testing "THE MUTATION — move one arm's data and the gate parts them.
              An equality that cannot be made to fail is not a gate"
      (seeded! (assoc-in seed-db [:labels 1] "annual"))
      (is (not= hiccup (render! direct-arm))))))

;; ---------------------------------------------------------------------------
;; 2. The deterministic delta — the four surfaces
;; ---------------------------------------------------------------------------

(deftest a-direct-return-does-not-enter-the-hiccup-walk
  (seeded!)
  (let [real codec/vec->element]
    (walks-past
      "the hiccup walk (codec/vec->element)"
      (probes (fn [n] (set! codec/vec->element
                            (fn [form] (swap! n inc) (real form))))
              (fn [] (set! codec/vec->element real))))))

(deftest a-direct-return-does-not-enter-the-prop-pipeline
  (seeded!)
  (let [real codec/convert-props]
    (walks-past
      "the prop pipeline (codec/convert-props)"
      (probes (fn [n] (set! codec/convert-props
                            (fn [& args] (swap! n inc) (apply real args))))
              (fn [] (set! codec/convert-props real))))))

(deftest a-direct-return-does-not-enter-event-lowering
  (seeded!)
  (let [real intent/lower-prop]
    (walks-past
      "event lowering (intent/lower-prop)"
      (probes (fn [n] (set! intent/lower-prop
                            (fn [& args] (swap! n inc) (apply real args))))
              (fn [] (set! intent/lower-prop real))))))

(deftest a-direct-return-does-not-enter-controlled-repair
  (seeded!)
  (let [real controlled/install!]
    (walks-past
      "controlled repair (controlled/install!)"
      (probes (fn [n] (set! controlled/install!
                            (fn [& args] (swap! n inc) (apply real args))))
              (fn [] (set! controlled/install! real))))))

(deftest a-direct-return-is-not-inspected-by-the-key-diagnostic
  (seeded!)
  ;; THE READINGS COME FIRST, and the order is load-bearing: the warning
  ;; fires ONCE PER AUTHORING SITE for the life of the run, so a render
  ;; of `key-hiccup-arm` taken before the spy is installed spends the
  ;; site's one warning and leaves the control reading zero. That is not
  ;; hypothetical — it is what the pair check below did when it was
  ;; written above these two, and the dedupe made the CONTROL fail rather
  ;; than the claim, which is the way round one wants it.
  (let [hiccup-warned (warnings-of key-hiccup-arm)
        direct-warned (warnings-of key-direct-arm)]

    (testing "CONTROL — the hiccup arm's unkeyed boundary members draw
              Hicasso's warning, naming the view that lowered them, so
              the channel is live and the fixture does carry the
              population"
      (is (= 1 (count hiccup-warned)))
      (is (re-find #"direct-return-cljs-test/key-hiccup-arm" (first hiccup-warned)))
      (is (re-find #"direct-return-cljs-test/tag-row" (first hiccup-warned))))

    (testing "the same members built with `n/$` draw none. React's own key
              warning fires for BOTH arms on its own channel — a
              `console.error` — and that is the point: whether React wants
              a key here is React's affair, and Hicasso has not looked"
      (is (= [] direct-warned)))

    (testing "and the key pair is a pair — same DOM, so the two rows above
              differ in the diagnostic and in nothing else"
      (is (= (render! key-hiccup-arm) (render! key-direct-arm)))
      (is (= (str "<div class=\"tags\"><span class=\"tag\">clj</span>"
                  "<span class=\"tag\">react</span></div>")
             (render! key-hiccup-arm))))))

;; ---------------------------------------------------------------------------
;; 3. What the escape still costs — I9 holds at two
;; ---------------------------------------------------------------------------

(deftest a-boundary-that-returns-an-element-still-costs-exactly-two-hooks
  (seeded!)
  (is (true? (hook-probe/install!))
      "React's client-internals dispatcher slot was not found — the hook
       budget is UNWITNESSED, not satisfied")
  (let [hooks-of (fn [view] (hook-probe/record! (fn [] (render! view))))
        hiccup   (hooks-of hiccup-arm)
        direct   (hooks-of direct-arm)]

    (testing "the shell's two — the frame-context hook and the
              subscription/epoch hook, in the order the ledger declares
              them, with the body between"
      (is (= ["useContext" "useSyncExternalStore"] (vec direct)))
      (is (= (count collector/shell-hook-ledger) (count direct))))

    (testing "the boundary pays the same two on either arm: Rung 3 is an
              escape from the codec, not from the boundary. A third hook
              in the SHELL would break I9 (rf2-hic-018), frozen at two"
      (is (= direct (vec (take 2 hiccup)))))

    (testing "and the hiccup arm's THIRD hook is not the shell's. It is
              the controlled field's own shadow component — one
              `useState`, standing in front of the input
              (`impl.controlled`) — so it is a per-controlled-FIELD cost
              and not a per-boundary one. The direct arm does not pay it
              because it has no controlled field to repair, which is the
              escape's price restated as a number rather than as prose"
      (is (= ["useContext" "useSyncExternalStore" "useState"] (vec hiccup))))))

;; ---------------------------------------------------------------------------
;; 4. L2 refuses the direct return as opaque
;; ---------------------------------------------------------------------------

(def ^:private l2-fixtures
  {:subs {[:dr/label 1] "quarterly" [:dr/tags 1] ["clj" "react"]}})

(defn- outcome
  [thunk]
  (try {:value (thunk)} (catch :default e {:refused (ex-data e)})))

(deftest l2-refuses-a-direct-return-as-opaque
  (testing "CONTROL — the hiccup arm's body is exactly what L2 is for, and
            it answers a tree"
    (is (= :div (:tag (ht/tree [hiccup-body {:id 1}] l2-fixtures)))))

  (testing "the direct arm's body answers a value only React can
            interpret, so L2 refuses it and points at L3. This is the
            escape's OTHER price and the one an author meets first: a
            boundary moved to Rung 3 leaves the assertion tier that runs
            without a browser"
    (let [{:keys [refused]} (outcome #(ht/tree [direct-body {:id 1}] l2-fixtures))]
      (is (= :rf.error/hicasso-test-react-is-opaque (:rf.error/id refused)))
      (is (= :assert-it-at-l3 (:recovery refused)))))

  (testing "and it refuses at the DIRECT-RETURN position specifically —
            the body answered the element itself, with no tree around it
            for the refusal to have come from"
    (let [{:keys [refused]} (outcome #(ht/tree [(fn [_] (n/$ :b nil "x"))] {}))]
      (is (= :rf.error/hicasso-test-react-is-opaque (:rf.error/id refused))))))
