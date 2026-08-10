(ns re-frame.hicasso.hook-budget-cljs-test
  "THE ≤2-HOOK BUDGET, COUNTED AT REACT'S OWN DISPATCHER (rf2-wjag).

  > Hook budget ≤ 2 per boundary (paper rule), fully consumed by the
  > subscription/epoch hook and the frame-context hook (HD-020); refs are
  > callback refs, never `useRef` in the shell. A ViewCell-class
  > per-boundary object graph appearing means the arm has failed.
  >
  > — `docs/design/hicasso/architecture.md`, Arm 1

  A hard architectural line, and the package has never had a witness for
  it. [[re-frame.hicasso.impl.collector/shell-hook-ledger]] is a
  DECLARATION — a vector of two keywords the shell says it calls — and a
  budget a runtime reports about itself is not evidence. This file counts
  the calls React actually received, through
  [[re-frame.hicasso.hook-probe]], which wraps React's own dispatcher slot
  and records what was asked of it.

  ## The boundary is a COUNT, so the instrument has to be able to say 3

  Every assertion here is of the form *exactly these two*, and a
  prohibition is trivially satisfied by an instrument that cannot report
  more. So the first row drives a control component that calls three
  hooks — `useRef`, `useState`, `useMemo`, the first two of which
  HD-020(b) names as forbidden in a shell — and asserts the probe answers
  all three, by name and in call order. The two below are the same
  instrument, on the same page, in the same run.

  ## Three claims, and the third is the one that matters

  1. a boundary's shell calls exactly the two hooks its ledger declares,
     and neither is `useRef` or `useState`;
  2. the budget is PER BOUNDARY — a nested boundary costs its own two,
     and shares nothing that would make a page's total sublinear or a
     boundary's cost conditional on its parent;
  3. **the count does not move with the read count.** One read, seven,
     twenty — all cost two. That is the whole argument for the ambient
     collector over a per-read hook: N reads = N hooks cannot satisfy this
     budget on any rung, and no amount of care about the other two claims
     substitutes for it.

  ## Why a server render is the right lane for this

  `react-dom/server` runs bodies for real — the shell's hooks, the read
  collection, the codec's element emission — through React's own hook
  dispatcher, and it runs in the node lane, which IS in the fast-PR
  spine. The browser lane is not. A budget breach is a fact about the
  shell's source, not about the host, so counting it where a PR will
  actually run it is worth more than counting it in a lane a PR does not
  reach. `kernel_commit_owns_cljs_test` takes the same view for the same
  reason.

  ## The variant this file does not measure

  [[re-frame.hicasso.impl.collector/frame-prop-shell]] declares ONE hook,
  and its ledger says so. It is a hypothesis under measurement rather
  than the default — `h/defview` mints the context-fed shell — so what is
  asserted here is the shipped shell against its own ledger. The same
  probe answers the variant the day it is taken up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.hook-probe :as probe]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::hook-budget)

(rf/reg-sub :hookbudget/item (fn [db [_ i]] (get-in db [:items i])))

(rf/reg-event :hookbudget/seed (fn [_ [_ db]] {:db db}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:hookbudget/seed {:items (vec (range 20))}]))
  frame-id)

(defn- armed!
  "Install the probe, and fail loudly if React's internals have moved.
  Every count in this file is worthless if this is not true, so it is
  asserted rather than branched on."
  []
  (is (true? (probe/install!))
      "React's client-internals dispatcher slot was not found — the hook
       budget is UNWITNESSED, not satisfied"))

(defn- server-render!
  "Render `hiccup` to markup through React's own server renderer, and
  answer `{:html … :hooks [name …]}` — the hook names React was asked for
  while it ran, in call order."
  [hiccup]
  (let [!html (volatile! nil)
        hooks (probe/record!
                (fn []
                  (vreset! !html
                           (react-dom-server/renderToString
                             (mount/provider frame-id
                                             (codec/root-element frame-id hiccup))))))]
    {:html @!html :hooks hooks}))

;; ---------------------------------------------------------------------------
;; The boundaries under the probe
;; ---------------------------------------------------------------------------

(h/defview reader
  "One boundary reading `n` subscriptions through the ambient collector.
  The reads sit in a `for` — which no hook-shaped read surface can do,
  and which is exactly why the count is interesting."
  [{:keys [n]}]
  [:ul.reads
   (for [i (range n)]
     [:li.read {:key i} (str (h/sub [:hookbudget/item i]))])])

(h/defview outer
  "A boundary whose body mints another boundary's element."
  [_]
  [:div.outer (str (h/sub [:hookbudget/item 0])) [reader {:n 1}]])

(defn- three-hook-control
  "A plain React component calling three hooks, two of which HD-020(b)
  names as forbidden in a shell. Not a Hicasso boundary and not meant to
  be one: it exists so that `exactly two` below is a reading taken by an
  instrument that has been seen to answer three."
  [_props]
  (react/useRef nil)
  (react/useState 0)
  (react/useMemo (fn [] 1) #js [])
  (react/createElement "p" nil "control"))

;; ---------------------------------------------------------------------------
;; 1. The instrument can count, and can count past the budget
;; ---------------------------------------------------------------------------

(deftest the-probe-answers-what-react-was-asked-for-and-can-count-to-three
  (seeded!)
  (armed!)
  (let [hooks (probe/record!
                (fn [] (react-dom-server/renderToString
                         (react/createElement three-hook-control nil))))]

    (testing "three hooks, named, in call order. `useRef` and `useState`
              are the two HD-020(b) forbids a shell outright, so this row
              also establishes that the probe would SEE either of them if
              a shell ever called one"
      (is (= ["useRef" "useState" "useMemo"] hooks)))

    (testing "and the count is three, which is the number every `exactly
              two` below is a measurement against rather than a limit of"
      (is (= 3 (count hooks))))))

;; ---------------------------------------------------------------------------
;; 2. A boundary shell calls exactly the two hooks its ledger declares
;; ---------------------------------------------------------------------------

(deftest a-boundary-shell-calls-exactly-the-two-hooks-its-ledger-declares
  (seeded!)
  (armed!)
  (let [{:keys [html hooks]} (server-render! [reader {:n 1}])]

    (testing "the premise: React really rendered the boundary's body"
      (is (= 1 (count (re-seq #"<li" html)))))

    (testing "two hooks, and WHICH two: the frame-context hook and the
              subscription/epoch hook, in that order — the body runs
              between them, which is what lets the second close over the
              reads the first made possible"
      (is (= ["useContext" "useSyncExternalStore"] hooks)))

    (testing "the budget, as a number. HD-020(b) is a count, so it is
              asserted as one"
      (is (= 2 (count hooks))))

    (testing "and the ledger the shell declares agrees with what React was
              asked for — which is what makes the declaration a checked
              statement rather than a comment that can rot"
      (is (= (count collector/shell-hook-ledger) (count hooks))))

    (testing "neither is `useRef` and neither is `useState`: HD-020(b)'s
              two named prohibitions, stated as themselves rather than
              left implied by the sequence above"
      (is (= [] (filterv #{"useRef" "useState"} hooks))))))

;; ---------------------------------------------------------------------------
;; 3. The budget is per boundary
;; ---------------------------------------------------------------------------

(deftest a-nested-boundary-costs-its-own-two-and-shares-nothing
  (seeded!)
  (armed!)
  (let [{:keys [html hooks]} (server-render! [outer {}])]

    (testing "the premise: both bodies ran"
      (is (some? (re-find #"outer" html)))
      (is (= 1 (count (re-seq #"<li" html)))))

    (testing "two boundaries, two hooks each, in the order React renders
              them — the budget is PER BOUNDARY, so a page's cost is
              linear in its boundaries and no boundary's cost depends on
              its parent's"
      (is (= ["useContext" "useSyncExternalStore" "useContext" "useSyncExternalStore"]
             hooks)))

    (is (= 4 (count hooks)))))

;; ---------------------------------------------------------------------------
;; 4. The count does not move with the read count
;; ---------------------------------------------------------------------------

(deftest the-hook-count-does-not-move-with-the-read-count
  (seeded!)
  (armed!)
  ;; The claim the ambient collector exists to make. A per-read hook
  ;; surface answers N here, and cannot be brought under a budget of two
  ;; by any amount of care elsewhere.
  (doseq [n [1 7 20]]
    (let [{:keys [html hooks]} (server-render! [reader {:n n}])]

      (testing (str "the premise: the body really performed " n " reads")
        (is (= n (count (re-seq #"<li" html)))))

      (testing (str n " reads still cost exactly the same two hooks")
        (is (= ["useContext" "useSyncExternalStore"] hooks))))))
