(ns re-frame.bench.hicasso.arm1.frame-prop-dom-cljs-test
  "THE FRAME AS AN ORDINARY PROP — THE WITNESSES, NOT THE PRICE
  (rf2-2rtt6.39).

  HD-020(b) spends the whole ≤2-hook budget on two hooks: the
  subscription/epoch hook and the frame-context hook. A third hook in the
  shell is a budget breach, so v0 has no slot left for anything it later
  needs. rf2-2rtt6.39's hypothesis is that the second hook is avoidable —
  the frame is ordinary data flowing down the tree, and the codec knows it
  at the moment it mints each boundary element, so it can bake it in
  ([[re-frame.bench.hicasso.front.codec/mark-frame-prop!]]).

  **This file is the CORRECTNESS half only.** The bead's heap ladder, its
  mount/bulk clock and its studio row are measurement work on a quiet box
  and are not taken here (rf2-2rtt6.71). What is settled here is what a
  measurement is not allowed to be taken without:

  1. **The hook count, at React's own dispatcher.** Never self-reported —
     the same probe, the same page and the same root as
     [[re-frame.bench.hicasso.arm1.hook-ledger-dom-cljs-test]], so the two
     variants are a reading against a reading. Two hooks become one.
  2. **Multi-frame isolation, which is the hard part and not the heap.**
     Context gets frame isolation for free by construction; a prop must
     PROVE it. One app mounted in two isolated frames, and no read
     crosses. A red here kills the hypothesis outright regardless of any
     number.
  3. **A foreign component in the middle.** The bead's argument for why
     the prop survives interop is that a foreign component's children were
     CREATED by the Hicasso body above it, with the prop already in them —
     so passing `props.children` through preserves it. That is an argument
     about React, and it is asserted rather than reasoned about.
  4. **The memo does not swallow a frame change.** This one is a defect
     the variant CREATES and the incumbent does not have: React propagates
     a context change to its consumers ahead of the comparator and through
     a memo, so a context-fed boundary cannot be bailed out of a frame
     change. A frame-fed one can — the frame is a prop, and props are the
     one channel `React.memo` blocks. `boundary-props=` therefore compares
     `rfFrame`, and claim 4 is what says so.

  Claim 4 is the mutation witness: drop the `rfFrame` comparison from
  [[re-frame.bench.hicasso.front.codec/boundary-props=]] and it goes red
  while every other claim here stays green.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt :refer [sub]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.test-support :as test-support]
            ["react" :as react])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; Same reason as the hook-ledger fixture: the default leaves a
     ;; dynamic-var frame stamp in scope, and a frame resolved from there
     ;; instead of from the prop would make an isolation miss look like a
     ;; rendering difference.
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-a ::arm1-frame-prop-a)
(def ^:private frame-b ::arm1-frame-prop-b)

(def ^:private todos
  "Small — nothing here is a bulk claim."
  4)

;; ---------------------------------------------------------------------------
;; The two variants of ONE view
;; ---------------------------------------------------------------------------
;;
;; Identical bodies, minted twice. That is the whole point: everything
;; below reads the same markup from the same reads, and the only variable
;; is where the shell got its frame.

(defn- row-body
  "Reads one to-do's `done?` through the collector and prints it. The read
  is what isolation is asserted on: two frames hold different values for
  the same key, so a boundary that resolved the wrong frame prints the
  wrong word."
  [{:keys [id]}]
  [:span.row {:data-testid "row"} (if (sub [:dogfood/done? id]) "yes" "no")])

(defview context-row
  "The incumbent: the frame arrives through `useContext`."
  [props]
  (row-body props))

(def frame-prop-row
  "The challenger: the frame arrives as `rfFrame` on the element."
  (rt/mint-frame-prop-view! "frame-prop-row" row-body))

;; ---------------------------------------------------------------------------
;; The foreign component in the middle (claim 3)
;; ---------------------------------------------------------------------------

(defn- passthrough-component
  "A plain React component that owns none of Hicasso's machinery and
  simply renders whatever children it was given. Declared as a host below,
  which is the only door a foreign component has (HD-011)."
  [^js props]
  (react/createElement "div" #js {"className" "hatch"} (.-children props)))

(def ^:private hatch
  (codec/mint-host! "frame-prop-test/hatch" passthrough-component))

;; ---------------------------------------------------------------------------
;; Fixture helpers
;; ---------------------------------------------------------------------------

(defn- skip! [why] (is true (str "this claim needs a real React DOM — " why)))

(defn- unwitnessed! []
  (is false (str "React's internals slot was not found, so the hook count is "
                 "UNWITNESSED on this build. A gate nobody has watched fire is "
                 "not evidence — fix "
                 (pr-str 're-frame.bench.hicasso.arm1.hook-probe)
                 " rather than reading this as a pass.")))

(defn- frames! []
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-a todos)
  (dogfood/make-frame! frame-b todos)
  (dogfood/reseed! frame-a todos)
  (dogfood/reseed! frame-b todos))

(defn- mount! [frame-kw hiccup]
  (mount/root! (mount/fresh-container!) frame-kw hiccup))

(defn- text-of [handle]
  (.-textContent (:container handle)))

(defn- mount-and-count
  "Mount `hiccup` and answer the hook names React was asked for while it
  mounted — the hook-ledger witness's own helper, deliberately the same
  root, page and frame so only the component varies."
  [hiccup]
  (let [handle (volatile! nil)
        names  (probe/record! (fn [] (vreset! handle (mount! frame-a hiccup))))]
    (mount/release! @handle)
    names))

;; ---------------------------------------------------------------------------
;; 1. The hook count, at React's dispatcher
;; ---------------------------------------------------------------------------

(deftest the-frame-prop-shell-calls-one-hook-where-the-context-shell-calls-two
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (testing "the same body, minted twice, counted by the same probe on
               the same page — the frame hook is the whole difference"
        (frames!)
        (let [ctx  (mount-and-count [context-row {:id 0}])
              prop (mount-and-count [frame-prop-row {:id 0}])]
          (is (= ["useContext" "useSyncExternalStore"] ctx)
              (str "the incumbent's ledger, unchanged: " (pr-str ctx)))
          (is (= ["useSyncExternalStore"] prop)
              (str "and the frame-fed shell asks for one hook: " (pr-str prop)))
          (is (= (count rt/shell-hook-ledger) (count ctx))
              "each declared ledger is the measured one")
          (is (= (count rt/frame-prop-shell-hook-ledger) (count prop))
              "for both variants")
          (is (= 1 (- (count ctx) (count prop)))
              "one slot of the ≤2 budget is freed, and it is exactly one"))))))

(deftest the-freed-slot-does-not-come-back-with-the-read-count
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (testing "1, 7 and 20 reads all cost ONE hook — the budget claim the
               ledger test makes at two, restated at one"
        (frames!)
        (let [many (rt/mint-frame-prop-view!
                     "frame-prop-many"
                     (fn [{:keys [n]}]
                       [:ul.reads
                        (for [i (range n)]
                          [:li.read {:key i} (str (sub [:dogfood/todo i]))])]))]
          (doseq [n [1 7 20]]
            (let [names (mount-and-count [many {:n n}])]
              (is (= ["useSyncExternalStore"] names)
                  (str n " reads still cost one hook: " (pr-str names))))))))))

;; ---------------------------------------------------------------------------
;; 2. Multi-frame isolation — the claim that can kill the hypothesis
;; ---------------------------------------------------------------------------

(deftest one-app-in-two-isolated-frames-and-no-read-crosses
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (testing "the same view mounted in two frames reads two app-dbs, and a
             write to one moves only one"
      (frames!)
      (let [a (mount! frame-a [frame-prop-row {:id 0}])
            b (mount! frame-b [frame-prop-row {:id 0}])]
        (is (= "no" (text-of a)) "frame A starts undone")
        (is (= "no" (text-of b)) "frame B starts undone")

        (mount/dispatch! a [:dogfood/toggle 0])
        (is (= "yes" (text-of a)) "the write lands in the frame it was sent to")
        (is (= "no" (text-of b))
            "and NOTHING crosses — a prop-threaded frame isolates as
             completely as the context it replaces")

        (mount/dispatch! b [:dogfood/toggle 0])
        (is (= "yes" (text-of a)) "A is undisturbed by B's write")
        (is (= "yes" (text-of b)) "and B moves on its own")

        (mount/release! a)
        (mount/release! b)))))

(deftest the-two-variants-isolate-identically
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (testing "the incumbent is put through the identical witness, so
             'isolation holds' is a comparison and not an assertion about
             the challenger alone"
      (frames!)
      (let [a (mount! frame-a [context-row {:id 0}])
            b (mount! frame-b [context-row {:id 0}])]
        (mount/dispatch! a [:dogfood/toggle 0])
        (is (= "yes" (text-of a)))
        (is (= "no" (text-of b)))
        (mount/release! a)
        (mount/release! b)))))

;; ---------------------------------------------------------------------------
;; 3. A foreign component in the middle
;; ---------------------------------------------------------------------------

(deftest a-foreign-component-between-two-boundaries-preserves-the-frame
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (testing "the children handed to a foreign component were created by
             the Hicasso body ABOVE it, with the frame already in them, so
             `props.children` carries it through untouched"
      (frames!)
      (let [outer (rt/mint-frame-prop-view!
                    "frame-prop-outer"
                    (fn [_] [hatch {} [frame-prop-row {:id 0}]]))
            a     (mount! frame-a [outer {}])
            b     (mount! frame-b [outer {}])]
        (is (= "no" (text-of a)))
        (is (= "no" (text-of b)))
        (mount/dispatch! a [:dogfood/toggle 0])
        (is (= "yes" (text-of a))
            "the inner boundary resolved a frame at all — no crossing threw")
        (is (= "no" (text-of b))
            "and it resolved the RIGHT one, through a component that knows
             nothing about frames")
        (mount/release! a)
        (mount/release! b)))))

;; ---------------------------------------------------------------------------
;; 4. The memo does not swallow a frame change (the mutation witness)
;; ---------------------------------------------------------------------------

(deftest a-frame-change-with-unchanged-props-still-re-renders
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (testing "same root, same element, same props map, different frame.
             The props-equality bail-out would otherwise leave the body
             reading the frame it left — the one failure mode threading
             the frame INTRODUCES, and the reason `boundary-props=`
             compares `rfFrame`."
      (frames!)
      ;; Frame A's row is done; frame B's is not. Props are `{:id 0}` in
      ;; both renders, so `=` on `rfProps` is true and the comparator is
      ;; the only thing that can decide to re-render.
      (rt/dispatch! frame-a [:dogfood/toggle 0])
      (let [handle (mount! frame-a [frame-prop-row {:id 0}])]
        (is (= "yes" (text-of handle)) "mounted in A, reading A")
        (mount/render! (assoc handle :frame frame-b) [frame-prop-row {:id 0}])
        (is (= "no" (text-of handle))
            "re-rendered into B and reading B — the comparator saw the
             frame move even though the props did not")
        (mount/release! handle)))))

(deftest the-incumbent-survives-the-same-frame-change
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (testing "for free, and by a different mechanism: React propagates a
             context change to its consumers ahead of the comparator"
      (frames!)
      (rt/dispatch! frame-a [:dogfood/toggle 0])
      (let [handle (mount! frame-a [context-row {:id 0}])]
        (is (= "yes" (text-of handle)))
        (mount/render! (assoc handle :frame frame-b) [context-row {:id 0}])
        (is (= "no" (text-of handle)))
        (mount/release! handle)))))
