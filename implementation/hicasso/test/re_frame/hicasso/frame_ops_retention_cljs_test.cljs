(ns re-frame.hicasso.frame-ops-retention-cljs-test
  "**A DESTROYED FRAME DROPS ITS `!frame-ops` ROW**.

  `impl.frames/!frame-ops` holds one row per frame — the `capture-frame`
  bundle and the ambient dispatch closure over it, pinned to the
  incarnation that minted them. Until this bead the table's ONLY eviction
  was [[re-frame.hicasso.impl.frames/frame-row]]'s replace branch: the
  SUCCESSOR's first lookup under the same key. That works for a client id,
  which is reused across incarnations, and it is inert for an id that can
  never recur.

  `server/render` mints exactly such an id — `fresh-frame-id`'s
  `(gensym \"request-\")` — so every request served left a row that
  nothing would ever look up again, each retaining that request's
  captured bundle. Linear growth in requests served, in a process built
  to be long-lived.

  ## What these rows measure, and why in this order

  §1 is the general claim and §2 the one that motivated it, and §1 comes
  first because the repair is general: the row belongs to an INCARNATION,
  so the moment that incarnation is destroyed the row is unreadable by
  anything and the eviction is `destroy-frame!`'s to make. The SSR path
  is the case where the omission is unbounded rather than merely untidy.

  §1's first assertion is its own control. `!frame-ops` is populated by
  RENDER — `run-once` binds `(frame-dispatch frame-kw)` around every
  boundary body — so a row that was never minted would make the
  after-destroy assertion vacuously green. It mints the row through
  `collector/frame-dispatch`, the exact door a body run takes, and
  asserts its presence before asking whether destruction removes it.

  §2 takes the bead's ACCEPTANCE literally: read the table's
  `hicasso.ssr` rows after one request, serve three more, and read them
  again. Bounded means the two readings agree at zero; leaking means the
  second is three higher. Its own control is `runtime/body-runs`, so a
  `render` that reached no boundary at all — which would leave the table
  empty for the wrong reason — reds instead of passing.

  ## Runtime

  `-cljs-test`, not `-dom-cljs-test`: every row is a `react-dom/server`
  render or a direct table read, and none touches a document."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.frames :as rf.hicasso.impl.frames]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.server :as rf.hicasso.server]
            [re-frame.test-support :as rf.test-support]))

;; Registered ABOVE `use-fixtures` for the sibling suites' reason: the
;; reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it
;; is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))

(rf.hicasso/defview page
  "One subscription read, so the render acquires a frame-keyed cell and
  the boundary body actually runs."
  [_]
  [:div.page [:p.value (rf.hicasso/sub [::label])]])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(def ^:private snapshot {:label "alpha"})

(defn- request
  "One request's options — the smallest set `server/render` requires."
  []
  {:hiccup   [page {}]
   :snapshot snapshot
   :payload  [:label]})

(defn- ssr-rows
  "The `!frame-ops` keys minted by a per-request frame.

  `fresh-frame-id` spells its gensym into the `hicasso.ssr` namespace, so
  this counts exactly the rows a request left behind and is blind to
  whatever else the bundle's other suites have in the table."
  []
  (filter #(= "hicasso.ssr" (namespace %)) (keys @rf.hicasso.impl.frames/!frame-ops)))

;; ---------------------------------------------------------------------------
;; 1 — the general claim: the row dies with the incarnation
;; ---------------------------------------------------------------------------

(deftest a-destroyed-frame-drops-its-frame-ops-row
  (rf/make-frame {:id ::live :initial-events [[:rf/set-db snapshot]]})

  (testing "control — a body run under a live frame mints the row"
    (is (nil? (get @rf.hicasso.impl.frames/!frame-ops ::live))
        "nothing has rendered under this frame yet")
    ;; The exact lookup `run-once` makes around every boundary body.
    (rf.hicasso.impl.collector/frame-dispatch ::live)
    (is (some? (get @rf.hicasso.impl.frames/!frame-ops ::live))
        "a row exists — without this the assertion below is vacuous"))

  (testing "and `destroy-frame!` drops it"
    (rf/destroy-frame! ::live)
    (is (nil? (get @rf.hicasso.impl.frames/!frame-ops ::live))
        (str "the row is the destroyed incarnation's `capture-frame` bundle and "
             "the closure over it; no successor can ever look it up, so the "
             "destruction is the eviction"))))

;; ---------------------------------------------------------------------------
;; 2 — the case that made it unbounded: one row per REQUEST
;; ---------------------------------------------------------------------------

(deftest a-server-render-leaves-no-row-behind
  (is (empty? (ssr-rows))
      "baseline: no per-request rows before the first render")

  (rf.hicasso.test.runtime/reset-body-runs!)

  (testing "control — a render really runs this page's boundary body"
    (is (str/includes? (:html (rf.hicasso.server/render (request))) "alpha")
        "the seeded snapshot reaches the bytes")
    (is (pos? (rf.hicasso.test.runtime/body-runs))
        (str "a `render` that reached no boundary would leave the table "
             "empty for the wrong reason; ran " (rf.hicasso.test.runtime/body-runs)
             " bodies")))

  (let [after-1 (count (ssr-rows))]
    (dotimes [_ 3] (rf.hicasso.server/render (request)))
    (let [after-4 (count (ssr-rows))]

      (testing "the table is BOUNDED in requests served, not linear in them"
        (is (= 0 after-1)
            "one request leaves no per-request row")
        (is (= after-1 after-4)
            (str "and three more leave the reading unchanged — under the "
                 "leak this read " after-4 " against " after-1))))))
