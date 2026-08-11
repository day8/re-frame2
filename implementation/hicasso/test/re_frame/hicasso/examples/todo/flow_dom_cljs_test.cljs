(ns re-frame.hicasso.examples.todo.flow-dom-cljs-test
  "L3 — THE WHOLE TODO FLOW, MOUNTED (rf2-hic-086).

  One pass through the application on a real React root: adding through
  a real form submission, a real checkbox, edit-in-place under real
  keystrokes, a real click on a real filter link, and — the bead's own
  acceptance — **keyed identity across a filter flip, asserted by DOM
  NODE IDENTITY**. Every row ends at `hm/assert-clean!`.

  ## This file reaches no internal namespace either

  The bead's acceptance fences the APPLICATION and a test is allowed
  more; this one takes none of that allowance. The three helpers below —
  `browser?`, `skip!` and the act flag inside the fixture — are one line
  each, and writing them here keeps the whole witness, application and
  suite, readable as something a consumer could have written.

  ## TWO CLICKS, TWO SETTLING RULES (rf2-hic-025's finding 6, confirmed)

  A Hicasso intent dispatches through the runtime's own SYNCHRONOUS
  frame-locked door, so after a real click on `.destroy` or
  `.clear-completed` the handlers have run, `app-db` has moved and React
  has committed: the next line reads the repainted page, and
  `hm/settle!` is all that is owed.

  A **route-link** is different. `re-frame.routing/activate-link!` ends
  in the ASYNC door, so the click returns with the navigation merely
  ENQUEUED and the router drains it on a next-turn task. `hm/settle!` is
  an empty `flushSync` and cannot help: nothing is scheduled in React
  yet. Nothing at either call site says which of the two a click is.

  So [[drained]] waits on the CONDITION —
  `re-frame.test-support/poll-until`, the supported condition-poll,
  which composes with `cljs.test/async`. There is no virtual clock here
  and there cannot be: `{:clock true}` replaces the global `setTimeout`
  that the poll's own interval uses, and firing a timer would not drain
  a router task anyway. That is finding 7, met from a second
  application, and this one meets it on a plain filter link rather than
  on an async mutation — so the trap is not a property of applications
  that talk to servers.

  ## What this tier states rather than proves

  `new-todo-box` claims that *Enter in a text field submits its form*.
  A synthetic `KeyboardEvent` is untrusted and triggers no default
  action, so implicit submission cannot be driven from a test at all.
  [[submit!]] calls `HTMLFormElement.requestSubmit`, which fires exactly
  the submit event the browser's own implicit submission fires — the
  application's half of the claim is therefore driven, and the browser's
  half is stated. `:on-key-down` is a React handler and IS driven, by a
  real synthetic key event, below.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`),
  and each row degrades there to a STATED skip rather than a false
  green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.todo.app :as app]
            [re-frame.hicasso.examples.todo.db :as db]
            [re-frame.hicasso.examples.todo.events :as events]
            [re-frame.hicasso.examples.todo.routes :as routes]
            [re-frame.hicasso.examples.todo.subs :as subs]
            [re-frame.hicasso.examples.todo.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a mounted flow needs a real React DOM — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
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
                      (routes/register!))}))

;; ---------------------------------------------------------------------------
;; Reading and driving the page
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))
(defn- nodes [m sel] (vec (array-seq (.querySelectorAll (:container m) sel))))
(defn- text [m sel] (some-> (node m sel) .-textContent))
(defn- titles [m] (mapv #(.-textContent %) (nodes m ".todo-title")))

(defn- row-node
  "The `<li>` for the to-do titled `title` — reached through the delete
  button's accessible name and `closest`, so the application needs no
  test-only attribute to be addressable."
  [m title]
  (some-> (node m (str "[aria-label='Delete " title "']"))
          (.closest ".todo-row")))

(defn- click!
  "A real click on a Hicasso intent, and then a settle. `HTMLElement.click()`
  is what a `user-event` sequence ultimately performs; the intent's own
  dispatch is synchronous, so the settle is all that is owed."
  [m sel]
  (.click (node m sel))
  (hm/settle! m))

(defn- double-click!
  "A real `dblclick`, which `.click()` cannot synthesise."
  [m sel]
  (.dispatchEvent (node m sel) (js/MouseEvent. "dblclick" #js {:bubbles true}))
  (hm/settle! m))

(defn- press!
  "A real `keydown` carrying `key`, at `sel`. React's synthetic
  `onKeyDown` is driven by exactly this event, so the key-map's `.key`
  lookup is the real one."
  [m sel key]
  (.dispatchEvent (node m sel) (js/KeyboardEvent. "keydown" #js {:key key :bubbles true}))
  (hm/settle! m))

(defn- submit!
  "Submit the form at `sel` the way the browser's own implicit
  submission does — see the namespace docstring on what this tier can
  and cannot drive."
  [m sel]
  (.requestSubmit (node m sel))
  (hm/settle! m))

(defn- type-into!
  "Type `v` into the field at `sel` — a foreign write followed by a real
  `input` event, which is what a keystroke is from React's side.

  Written through the PROTOTYPE's own `value` setter because React
  patches the instance setter to maintain its change tracker: a plain
  `set!` updates the tracker too, after which React reads the node as
  already agreeing and the `input` below reaches no handler."
  [m sel v]
  (let [n (node m sel)
        d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)
    (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
    (hm/settle! m)))

(defn- read-sub [m query-v] (rf/subscribe-once query-v {:frame (:frame m)}))

(defn- drained
  "Wait for `pred` to hold, then flush React and answer a promise of the
  handle — the ROUTER-DRAIN counterpart of `hm/settle!`, for the one
  place a click leaves work merely enqueued (see the namespace docstring
  §TWO CLICKS).

  A bounded condition poll rather than a sleep: it returns as soon as
  the navigation lands, and fails at a deadline with
  `:rf.error/poll-until-timeout` naming the label rather than hanging
  the run."
  [m pred label]
  (-> (test-support/poll-until pred {:label label})
      (.then (fn [_] (hm/settle! m)))))

(defn- follow-filter!
  "Click the filter tab labelled `label` and wait for the router to
  deliver the navigation."
  [m label expected-filter]
  (let [a (some (fn [a] (when (= label (.-textContent a)) a))
                (nodes m ".filters a"))]
    (is (some? a) (str "there is a tab labelled " (pr-str label)))
    (.click a)
    (drained m
             #(= expected-filter (read-sub m [::subs/showing]))
             (str "the " label " filter to land"))))

(defn- mount-app!
  "The whole application, on its own root and its own frame, seeded and
  pointed at *All*. `:initial-events` drain to fixed point before the
  first render, so a row opens on the seeded page."
  ([] (mount-app! [:rf.route/navigate {:to routes/all}]))
  ([nav-event]
   (hm/mount! [views/app {}]
              {:initial-events [[::events/seed app/sample-todos] nav-event]})))

(defn- finish
  "Tear down, assert this mount left nothing behind, and end the row."
  [m done]
  (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))

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
;; 1 — the seeded page, and adding one
;; ---------------------------------------------------------------------------

(deftest the-page-opens-seeded-and-a-real-submission-appends
  (if-not (browser?)
    (skip! "the seeded page and a form submission")
    (async done
      (let [m (mount-app!)]
        (is (= app/sample-todos (titles m))
            "the frame's :initial-events drained before the first render")
        (is (= "3 items left" (text m ".todo-count")))

        (type-into! m "#new-todo" "buy milk")
        (is (= "buy milk" (read-sub m [::subs/new-todo]))
            "controlled: the keystroke reached the model, not a local draft")

        (submit! m ".new-todo-form")
        (is (= (conj app/sample-todos "buy milk") (titles m)))
        (is (= "" (.-value (node m "#new-todo")))
            "and the box emptied itself, because its :value IS the model")
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 2 — a real checkbox, and the conditional chrome
;; ---------------------------------------------------------------------------

(deftest toggling-a-real-checkbox-moves-the-model-and-the-count
  (if-not (browser?)
    (skip! "a real checkbox")
    (async done
      (let [m (mount-app!)]
        (.click (node m ".todo-row .toggle"))
        (hm/settle! m)
        (is (= 2 (read-sub m [::subs/active-count])))
        (is (= "2 items left" (text m ".todo-count")))
        (is (true? (.-checked (node m ".todo-row .toggle")))
            "the box shows what the model says, which is the same fact it
             just sent there")
        (is (some? (node m ".clear-completed"))
            "and one completed to-do is what puts the button on the page")
        (finish m done)))))

(deftest clearing-completed-takes-the-chrome-with-it-when-nothing-is-left
  (if-not (browser?)
    (skip! "clear-completed and the conditional chrome")
    (async done
      (let [m (mount-app!)]
        (click! m "#toggle-all")
        (is (= "0 items left" (text m ".todo-count")))
        (is (= "Clear completed (3)" (text m ".clear-completed")))

        (click! m ".clear-completed")
        (is (= [] (titles m)))
        (is (nil? (node m ".todo-list")) "the list is gone")
        (is (nil? (node m ".footer")) "and so is the footer")
        (is (some? (node m "#new-todo"))
            "the header stays — it is what you add the next one with")
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 3 — edit in place, under real keys
;; ---------------------------------------------------------------------------

(deftest enter-commits-an-edit-and-escape-reverts-it
  (if-not (browser?)
    (skip! "edit-in-place under real keystrokes")
    (async done
      (let [m (mount-app!)]
        (testing "a double-click opens ONE editor, pre-filled"
          (double-click! m ".todo-row .todo-title")
          (is (= 1 (count (nodes m ".edit"))))
          (is (= "Read the spec" (.-value (node m ".edit")))))

        (testing "Enter commits the typed text"
          (type-into! m ".edit" "Read the whole spec")
          (press! m ".edit" "Enter")
          (is (= "Read the whole spec" (first (titles m))))
          (is (nil? (node m ".edit")) "and the editor closed")
          (is (nil? (read-sub m [db/draft 1])) "leaving no draft behind"))

        (testing "Escape reverts, and the blur that follows commits nothing"
          (double-click! m ".todo-row .todo-title")
          (type-into! m ".edit" "typed but cancelled")
          (press! m ".edit" "Escape")
          (is (nil? (node m ".edit")))
          (is (= "Read the whole spec" (first (titles m)))
              "the model still holds what Enter committed — the cancel
               beat the late blur because the commit handler reads the
               draft from app-db and Escape had already removed it"))

        (finish m done)))))

(deftest an-edit-opens-exactly-the-row-it-was-asked-for
  (if-not (browser?)
    (skip! "per-instance widget state")
    (async done
      (let [m (mount-app!)]
        ;; the SECOND row, reached through its delete button's accessible
        ;; name and `closest` — no test-only attribute on the application
        (.dispatchEvent (.querySelector (row-node m "Write the witness") ".todo-title")
                        (js/MouseEvent. "dblclick" #js {:bubbles true}))
        (hm/settle! m)
        (is (= 1 (count (nodes m ".edit")))
            "one editor, because the draft is keyed by the to-do's id. A
             hand-rolled [:ui :edit-draft] path would have opened all
             three, silently")
        (is (= "Write the witness" (.-value (node m ".edit"))))
        (is (nil? (read-sub m [db/draft 1])))
        (is (= "Write the witness" (read-sub m [db/draft 2])))
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 4 — the filter, through a real link, and the KEYED IDENTITY claim
;; ---------------------------------------------------------------------------

(deftest a-filter-flip-keeps-the-surviving-rows-DOM-NODES
  (if-not (browser?)
    (skip! "keyed identity across a filter flip")
    (async done
      (let [m (mount-app!)]
        (.click (node m ".todo-row .toggle"))          ;; "Read the spec" is done
        (hm/settle! m)
        (let [before-witness (row-node m "Write the witness")
              before-spec    (row-node m "Read the spec")]
          (is (some? before-witness))
          (is (= 3 (count (nodes m ".todo-row"))))

          (-> (follow-filter! m "Active" :active)
              (.then
                (fn [_]
                  (is (= routes/filtered (read-sub m [:rf.route/id]))
                      "a real click on a real anchor navigated the frame")
                  (is (= ["Write the witness" "Merge the PR"] (titles m))
                      "the completed row left the list")

                  ;; THE ACCEPTANCE. React reconciles by key, so a row that
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

(deftest the-filter-lives-on-the-url-and-nowhere-else
  (if-not (browser?)
    (skip! "the routed filter")
    (async done
      (let [m (mount-app!)]
        (-> (follow-filter! m "Completed" :completed)
            (.then
              (fn [_]
                (is (= [] (titles m)) "nothing is done yet")
                (is (= "selected" (.-className (node m ".filters a[href$='completed']")))
                    "the highlighted tab is derived from the URL")
                (is (= #{:todos :next-id :new-todo}
                       (set (keys (rf/app-db-value (:frame m)))))
                    "and app-db holds no copy of the filter to disagree
                     with the address bar")))
            (finish-after m done))))))

;; ---------------------------------------------------------------------------
;; 5 — an editor that survives a re-render it did not cause
;; ---------------------------------------------------------------------------

(deftest an-open-editor-survives-another-rows-toggle
  (if-not (browser?)
    (skip! "an open editor across an unrelated commit")
    (async done
      (let [m (mount-app!)]
        (.dispatchEvent (.querySelector (row-node m "Merge the PR") ".todo-title")
                        (js/MouseEvent. "dblclick" #js {:bubbles true}))
        (hm/settle! m)
        (type-into! m ".edit" "Merge it")
        (let [field (node m ".edit")]
          (.click (.querySelector (row-node m "Read the spec") ".toggle"))
          (hm/settle! m)
          (is (identical? field (node m ".edit"))
              "the editor is the same element across a commit that moved a
               different row — keyed rows again, and the reason a caret
               survives an unrelated change")
          (is (= "Merge it" (.-value field))))
        (finish m done)))))
