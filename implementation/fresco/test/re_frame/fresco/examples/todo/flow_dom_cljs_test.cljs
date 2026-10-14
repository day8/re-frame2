(ns re-frame.fresco.examples.todo.flow-dom-cljs-test
  "L3 — THE WHOLE TODO FLOW, MOUNTED.

  The application on a real React root, and its central claim: **keyed
  identity across a filter flip — a real click on a real filter link —
  asserted by DOM NODE IDENTITY**. Every row ends at `hm/assert-clean!`.

  ## This file reaches no internal namespace either

  The `:require` fence is on the APPLICATION and a test is allowed
  more; this one takes none of that allowance. The three helpers below —
  `browser?`, `skip!` and the act flag inside the fixture — are one line
  each, and writing them here keeps the whole witness, application and
  suite, readable as something a consumer could have written.

  ## TWO CLICKS, TWO SETTLING RULES

  A Fresco intent dispatches through the runtime's own SYNCHRONOUS
  frame-locked door, so after a real click on `.toggle` the handlers
  have run, `app-db` has moved and React has committed: the next line
  reads the repainted page, and `hm/settle!` is all that is owed.

  A **route-link** is different. `re-frame.routing/activate-link!` ends
  in the ASYNC door, so the click returns with the navigation merely
  ENQUEUED and the router drains it on a next-turn task. `hm/settle!` is
  an empty `flushSync` and cannot help: nothing is scheduled in React
  yet. Nothing at either call site says which of the two a click is.

  So `hm/settle-until!` waits on the CONDITION — the facade's own door
  for the enqueued kind, a bounded `re-frame.test-support/poll-until`
  paired with a flush, and it composes with `cljs.test/async` the same
  way. There is no virtual clock here and there cannot be:
  `{:clock true}` replaces the global `setTimeout` that the poll's own
  interval uses, and firing a timer would not drain a router task
  anyway. This application meets that on a plain filter link rather
  than on an async mutation — so the trap is not a property of
  applications that talk to servers.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`),
  and each row degrades there to a STATED skip rather than a false
  green."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.examples.todo.app :as rf.fresco.examples.todo.app]
            [re-frame.fresco.examples.todo.events :as rf.fresco.examples.todo.events]
            [re-frame.fresco.examples.todo.routes :as rf.fresco.examples.todo.routes]
            [re-frame.fresco.examples.todo.subs :as rf.fresco.examples.todo.subs]
            [re-frame.fresco.examples.todo.views :as rf.fresco.examples.todo.views]
            [re-frame.fresco.test.mounted :as rf.fresco.test.mounted]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a mounted flow needs a real React DOM — " why)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; The MAP shape, because every row is `async`: `cljs.test` refuses an
     ;; async test under a fn-form fixture and aborts the namespace.
     :async?        true
     :init-fn       (fn []
                      ;; React's `act` queue is not the browser's scheduler,
                      ;; and every reading here is taken outside it.
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; The reset restores the registrar to a baseline
                      ;; captured when this form was EVALUATED, which is
                      ;; before `routes` finished loading. See that
                      ;; namespace on why `register!` is exposed at all.
                      (rf.fresco.examples.todo.routes/register!))}))

;; ---------------------------------------------------------------------------
;; Reading and driving the page
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))
(defn- nodes [m sel] (vec (array-seq (.querySelectorAll (:container m) sel))))
(defn- titles [m] (mapv #(.-textContent %) (nodes m ".todo-title")))

(defn- row-node
  "The `<li>` for the to-do titled `title` — reached through the delete
  button's accessible name and `closest`, so the application needs no
  test-only attribute to be addressable."
  [m title]
  (some-> (node m (str "[aria-label='Delete " title "']"))
          (.closest ".todo-row")))

(defn- read-sub [m query-v] (rf/subscribe-once query-v {:frame (:frame m)}))

(defn- follow-filter!
  "Click the filter tab labelled `label` and wait for the router to
  deliver the navigation."
  [m label expected-filter]
  (let [a (some (fn [a] (when (= label (.-textContent a)) a))
                (nodes m ".filters a"))]
    (is (some? a) (str "there is a tab labelled " (pr-str label)))
    (.click a)
    (rf.fresco.test.mounted/settle-until! m
                      #(= expected-filter (read-sub m [::rf.fresco.examples.todo.subs/showing]))
                      {:label (str "the " label " filter to land")})))

(defn- mount-app!
  "The whole application, on its own root and its own frame, seeded and
  pointed at *All*. `:initial-events` drain to fixed point before the
  first render, so a row opens on the seeded page."
  []
  (rf.fresco.test.mounted/mount! [rf.fresco.examples.todo.views/app {}]
             {:initial-events [[::rf.fresco.examples.todo.events/seed rf.fresco.examples.todo.app/sample-todos]
                               [:rf.route/navigate {:to rf.fresco.examples.todo.routes/all}]]}))

(defn- finish
  "Tear down, assert this mount left nothing behind, and end the row."
  [m done]
  (-> (rf.fresco.test.mounted/unmount! m) (rf.fresco.test.mounted/assert-clean!) (.then done)))

(defn- finish-after
  "End the row when `p` settles, reporting a rejected `p` as a failure
  rather than letting the deadline hang the whole run — and tearing the
  mount down either way, so one stuck row cannot make the NEXT row's
  residue reading wrong."
  [p m done]
  (-> p
      (.catch (fn [e]
                (is false (str "the flow never settled: "
                               (or (ex-message e) (str e)) " "
                               (pr-str (ex-data e))))))
      (.then (fn [_] (finish m done)))))

;; ---------------------------------------------------------------------------
;; The filter, through a real link, and the KEYED IDENTITY claim
;; ---------------------------------------------------------------------------

(deftest a-filter-flip-keeps-the-surviving-rows-DOM-NODES
  (if-not (browser?)
    (skip! "keyed identity across a filter flip")
    (async done
      (let [m (mount-app!)]
        (.click (node m ".todo-row .toggle"))          ;; "Read the spec" is done
        (rf.fresco.test.mounted/settle! m)
        (let [before-witness (row-node m "Write the witness")
              before-spec    (row-node m "Read the spec")]
          (is (some? before-witness))
          (is (= 3 (count (nodes m ".todo-row"))))

          (-> (follow-filter! m "Active" :active)
              (.then
                (fn [_]
                  (is (= rf.fresco.examples.todo.routes/filtered (read-sub m [:rf.route/id]))
                      "a real click on a real anchor navigated the frame")
                  (is (= ["Write the witness" "Merge the PR"] (titles m))
                      "the completed row left the list")

                  ;; THE CLAIM. React reconciles by key, so a row that
                  ;; survived the flip is the SAME DOM NODE — not an equal
                  ;; one. An index-keyed list would have renumbered the
                  ;; survivors and handed this node's identity, its caret
                  ;; and any open editor to a different to-do.
                  (is (identical? before-witness (row-node m "Write the witness"))
                      "the surviving row kept its DOM node across the flip")
                  (is (not (.contains (:container m) before-spec))
                      "and the filtered-out row's node left the document
                       rather than being recycled under a new title")))
              (.then (fn [_] (follow-filter! m "All" :all)))
              (.then
                (fn [_]
                  (is (= 3 (count (nodes m ".todo-row"))))
                  (is (identical? before-witness (row-node m "Write the witness"))
                      "and flipping back kept it again")
                  (is (not (identical? before-spec (row-node m "Read the spec")))
                      "while the row that LEFT comes back as a new node —
                       which is what makes the identity above a real
                       claim rather than a tautology about a list that
                       never changed")))
              (finish-after m done)))))))
