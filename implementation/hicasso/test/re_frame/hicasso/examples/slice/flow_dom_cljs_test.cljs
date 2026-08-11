(ns re-frame.hicasso.examples.slice.flow-dom-cljs-test
  "L3 — THE WHOLE FLOW, MOUNTED (rf2-hic-025).

  One complete pass through the application on a real React root: the
  feed, a real click on a real route-link, the editor's controlled
  fields under real keystrokes, a save the stand-in server REFUSES, the
  error region, a retry that succeeds, and a discard that re-baselines
  the field. Every row ends at `hm/assert-clean!`.

  ## This file reaches no internal namespace either

  The bead's acceptance fences the APPLICATION, and a test is allowed
  more — `re-frame.hicasso.test.mounted`'s own witnesses reach
  `impl.collector` and `impl.mount` for good reasons. This one does not,
  and the two helpers below are why: `browser?` and `skip!` are one line
  each, the act flag is one more inside the fixture, and writing them
  here keeps the whole slice — application and suite — readable as
  something a consumer could have written.

  ## TWO CLICKS, TWO SETTLING RULES — the report's findings 6 and 7

  A Hicasso intent dispatches through the runtime's own **synchronous**
  frame-locked door, so after a real click on `.save` or `.discard` the
  handlers have run, `app-db` has moved and React has committed: the next
  line reads the repainted page. That is what `hm/settle!` is for and it
  is all that is needed.

  A **route-link** is different. `re-frame.routing/activate-link!` ends in
  `router/dispatch!` — the ASYNC door — so the click returns with the
  navigation merely enqueued, and the router drains it on
  `interop/next-tick`, a next-turn TASK. `hm/settle!` is an empty
  `flushSync` and cannot help: there is nothing scheduled in React yet.
  Nothing at either call site says which of the two a click is.

  The same is true of the stand-in server's reply, and there it is the
  application being ordinary rather than routing being special: an fx
  that dispatches a reply uses `rf/dispatch`, exactly as
  `day8/re-frame2-http` does, so **every async mutation reply in any
  re-frame2 application arrives through a router drain.**

  So the rows below wait on the CONDITION rather than on a duration:
  `re-frame.test-support/poll-until`, which is the supported door for
  exactly this and returns a promise that composes with `async done`.

  ## Why there is no virtual clock here, although the facade offers one

  `hm/mount!`'s `{:clock true}` would fire the stand-in server's
  `setTimeout` on demand, and it was the first thing tried. It cannot
  work, for a reason worth recording: the clock **replaces the global
  `setTimeout`**, and `poll-until`'s own interval is a `js/setTimeout`.
  Under a virtual clock the poll never gets a second probe. And the clock
  alone is not enough either — firing the server's timer only ENQUEUES
  the reply, and the drain that delivers it is a macrotask the clock
  deliberately does not drive.

  Two supported waiting mechanisms, and an async mutation needs both at
  once. The poll is the one that works, and it is the honest instrument
  anyway: what this file is waiting for is a reply, not a duration.

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
;; The lane, and the two lines this file writes rather than imports
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

(defn- click!
  "A real click on a Hicasso intent, and then a settle. `HTMLElement.click()`
  is what a `user-event` sequence ultimately performs; the intent's own
  dispatch is synchronous, so the settle is all that is owed."
  [m sel]
  (.click (node m sel))
  (hm/settle! m))

(defn- type-into!
  "Type `v` into the field at `sel` — a foreign write followed by a real
  `input` event, which is what a keystroke is from React's side.

  Written through the PROTOTYPE's own `value` setter because React
  patches the instance setter to maintain its change tracker: a plain
  `set!` updates the tracker too, after which React reads the node as
  already agreeing and the `input` below reaches no handler."
  [m sel v]
  (let [n     (node m sel)
        proto (if (= "TEXTAREA" (.-tagName n))
                js/HTMLTextAreaElement.prototype
                js/HTMLInputElement.prototype)
        d     (js/Object.getOwnPropertyDescriptor proto "value")]
    (.call (.-set d) n v)
    (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
    (hm/settle! m)))

(defn- read-sub [m query-v] (rf/subscribe-once query-v {:frame (:frame m)}))

(defn- app-db [m] (rf/app-db-value (:frame m)))

(defn- drained
  "Wait for `pred` to hold, then flush React and answer a promise of the
  handle — the ROUTER-DRAIN counterpart of `hm/settle!`, for the two
  places a click leaves work merely enqueued (see the namespace
  docstring §TWO CLICKS).

  A bounded condition poll rather than a sleep: it returns as soon as the
  reply lands, and fails at a deadline with `:rf.error/poll-until-timeout`
  naming the label rather than hanging the run."
  [m pred label]
  (-> (test-support/poll-until pred {:label label})
      (.then (fn [_] (hm/settle! m)))))

(defn- mount-app!
  "The whole application, on its own root and its own frame, seeded and
  pointed at a route. `:initial-events` drain to fixed point before the
  first render, so the page a row opens on is already the seeded one."
  ([] (mount-app! [:rf.route/navigate {:to routes/feed}]))
  ([nav-event]
   (hm/mount! [views/app {}] {:initial-events [[::events/seed] nav-event]})))

(defn- at-article!
  "Mounted, with the article route already resolved."
  [slug]
  (mount-app! [:rf.route/navigate {:to routes/article :params {:slug slug}}]))

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
;; 1 — the feed, and a real click on a real link
;; ---------------------------------------------------------------------------

(deftest the-feed-lists-every-article-with-a-routing-owned-href
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (mount-app!)]
        (is (= "Chronicle" (text m ".slice-title")))
        (is (= "Articles" (text m ".feed h2")))
        (is (= ["Hicasso, briefly" "Intents are data" "Controlled, synchronously"]
               (mapv #(.-textContent %) (nodes m ".article-link"))))
        (is (= ["/article/hicasso" "/article/intents" "/article/controls"]
               (mapv #(.getAttribute % "href") (nodes m ".article-link")))
            "the hrefs are the routing artefact's; `views` builds no URL")
        (is (= 1 (count (nodes m ".draft-badge")))
            "one of the three is unpublished")
        (finish m done)))))

(deftest a-real-click-on-a-route-link-changes-the-page
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (mount-app!)]
        (is (nil? (node m ".editor")) "the feed is showing")
        ;; The real event, through React's own event system, with routing's
        ;; own `activate-link!` deciding. Nothing here calls preventDefault
        ;; — if the click were not claimed, this page would navigate away.
        (.click (node m ".article-list li:nth-child(2) .article-link"))
        (-> (drained m
                     #(= routes/article (read-sub m [:rf.route/id]))
                     "the route-link's navigate to drain")
            (.then (fn [_]
                     (is (= {:slug "intents"} (read-sub m [:rf.route/params])))
                     (is (= "Intents are data" (text m ".article-title")))
                     (is (some? (node m ".editor")))))
            (finish-after m done))))))

(deftest a-slug-nobody-published-renders-a-page-rather-than-throwing
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "nonsense")]
        (is (= "No such article." (text m ".article-missing")))
        (is (nil? (node m ".editor")))
        (is (nil? (node m ".pane-error"))
            "a missing article is data, not a thrown render — the error
             boundary above the pane has nothing to catch")
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 2 — the controlled fields
;; ---------------------------------------------------------------------------

(deftest the-editor-opens-on-the-article-with-no-load-step
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")]
        (is (= "Intents are data" (.-value (node m ".field-title"))))
        (is (= "A click carries a vector, and two renders are equal."
               (.-value (node m ".field-body"))))
        (is (true? (.-checked (node m "#slice-published"))))
        (is (false? (read-sub m [::subs/dirty? "intents"]))
            "opening an article is not editing it; `:drafts` is still empty")
        (finish m done)))))

(deftest a-keystroke-moves-the-model-and-the-glass-together
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")]
        (type-into! m ".field-title" "Intents, revisited")
        (is (= "Intents, revisited" (.-value (node m ".field-title")))
            "the glass")
        (is (= "Intents, revisited" (:title (read-sub m [::subs/draft "intents"])))
            "and the model — one place, not two")
        (is (= "A click carries a vector, and two renders are equal."
               (.-value (node m ".field-body")))
            "the field nobody touched is untouched: the first keystroke
             edited on top of the article rather than into an empty map")
        (is (= "Intents are data" (:title (db/article (app-db m) "intents")))
            "and the ARTICLE has not moved — a draft is not a save")
        (finish m done)))))

(deftest the-checkbox-writes-through-the-checked-marker
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "controls")]
        (is (false? (.-checked (node m "#slice-published"))))
        (click! m "#slice-published")
        (is (true? (:published? (read-sub m [::subs/draft "controls"])))
            "a Hicasso intent dispatches SYNCHRONOUSLY — no drain, no poll,
             the next line reads the moved model")
        (is (true? (.-checked (node m "#slice-published"))))
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 3 — the async mutation: refused, then retried, then saved
;; ---------------------------------------------------------------------------

(defn- replied
  "Wait for the save region of `slug` to leave `:saving`."
  [m slug]
  (drained m
           #(not= :saving (:status (read-sub m [::subs/save-state slug])))
           (str "the stand-in server's reply for " slug)))

(deftest a-save-the-server-refuses-shows-its-reason-and-keeps-the-draft
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "controls")]
        ;; A title another article already carries — the one rule the
        ;; client cannot check, so it can only arrive as a reply.
        (type-into! m ".field-title" "Intents are data")
        (click! m ".save")

        (testing "in flight — read on the line after the click, with no wait"
          (is (= "Saving…" (text m ".save")))
          (is (true? (.-disabled (node m ".save"))))
          (is (nil? (node m ".save-problem"))
              "nothing is claimed until the reply lands"))

        (-> (replied m "controls")
            (.then
              (fn [_]
                (is (= "That title is already used by another article. Try again"
                       (text m ".save-problem")))
                (is (= "alert" (.getAttribute (node m ".save-problem") "role")))
                (is (= "rgb(176, 32, 32)" (.. (node m ".save-problem") -style -color))
                    "the colour is the THEME TOKEN, read through a sub and
                     applied as a value — which is why a witness can read
                     it back")
                (is (= "Intents are data" (.-value (node m ".field-title")))
                    "the draft survived the refusal; losing what the user
                     typed because the server disagreed is the cruellest
                     failure mode")
                (is (= "Controlled, synchronously"
                       (:title (db/article (app-db m) "controls")))
                    "and the article did not move")))
            (finish-after m done))))))

(deftest retrying-with-a-title-the-server-accepts-saves-and-clears-the-region
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "controls")]
        (type-into! m ".field-title" "Intents are data")
        (click! m ".save")
        (-> (replied m "controls")
            (.then (fn [_]
                     (is (some? (node m ".save-problem")) "refused, as above")
                     (type-into! m ".field-title" "Controlled, revisited")
                     (click! m ".retry")
                     (replied m "controls")))
            (.then (fn [_]
                     (is (nil? (node m ".save-problem")))
                     (is (= "Saved." (text m ".save-ok")))
                     (is (= "Controlled, revisited"
                            (:title (db/article (app-db m) "controls")))
                         "the article moved")
                     (is (false? (read-sub m [::subs/dirty? "controls"]))
                         "and the draft was cleared by the commit")

                     (hm/dispatch-and-settle! m [:rf.route/navigate {:to routes/feed}])
                     (is (some #{"Controlled, revisited"}
                               (mapv #(.-textContent %) (nodes m ".article-link")))
                         "the feed shows the new title, because it reads the
                          article — and `dispatch-and-settle!` uses the
                          runtime's SYNCHRONOUS door, so this one needs no
                          poll")))
            (finish-after m done))))))

(deftest a-locally-invalid-draft-never-reaches-the-server
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")]
        (type-into! m ".field-title" "   ")
        (click! m ".save")
        ;; No poll and no drain: there is nothing in flight to wait for.
        ;; The region is on screen on the line after the click, which is
        ;; the assertion — a row that waited anyway would pass whether or
        ;; not the request was skipped.
        (is (= "A title is required. Try again" (text m ".save-problem")))
        (is (nil? (node m ".save-ok")))
        (is (= :failed (:status (read-sub m [::subs/save-state "intents"]))))
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 4 — the reset, in a real field
;; ---------------------------------------------------------------------------

(deftest discarding-re-baselines-the-field-without-remounting-it
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")]
        (is (true? (.-disabled (node m ".discard")))
            "nothing to discard yet")
        (type-into! m ".field-title" "typed and regretted")
        (is (false? (.-disabled (node m ".discard"))))

        (let [before (node m ".field-title")]
          (click! m ".discard")
          (is (= "Intents are data" (.-value (node m ".field-title")))
              "THE RESET LAW (HD-019): the model moved back to a value the
               field was already showing, so React alone sees nothing to
               do — the changed `::h/revision` is what re-baselines it")
          (is (identical? before (node m ".field-title"))
              "and it is the SAME element: re-baselined, not remounted, so
               focus, selection and scroll position survive a discard"))

        (is (false? (read-sub m [::subs/dirty? "intents"])))
        (is (true? (.-disabled (node m ".discard"))))
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; 5 — the intent script: what one edit session MEANS
;; ---------------------------------------------------------------------------

(deftest the-edit-flow-dispatches-exactly-this-script
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m   (at-article! "controls")
            ;; Captured at Spec 009's `:events` port — the substrate's own
            ;; public stream — so the script is what the PAGE dispatched
            ;; and holds no hook into the rendering under test.
            log (atom [])]
        (rf/register-listener! :events ::script
                               (fn [record]
                                 (when (= (:frame m) (:frame record))
                                   (swap! log conj (:event record)))))
        (type-into! m ".field-title" "Ready")
        (click! m "#slice-published")
        (click! m ".save")
        (-> (replied m "controls")
            (.then
              (fn [_]
                ;; STATED, not compared against a second capture: two
                ;; captures agree about a drift they share, and a
                ;; written-out vector does not. The materialized values
                ;; are in it — `::h/value` and `::h/checked` are
                ;; substituted before the handler ever runs, so this is
                ;; what a handler actually received.
                (is (= [[::events/edit "controls" :title "Ready"]
                        [::events/toggle-published "controls" true]
                        [::events/save {:slug "controls"}]
                        [::events/saved
                         {:slug  "controls"
                          :draft {:title      "Ready"
                                  :body       "The model is the only place the text lives."
                                  :published? true}}]]
                       @log))))
            (.finally (fn [] (rf/unregister-listener! :events ::script)))
            (finish-after m done))))))

;; ---------------------------------------------------------------------------
;; 6 — two mounts cannot see each other
;; ---------------------------------------------------------------------------

(deftest two-mounted-copies-of-the-application-are-isolated
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [a (at-article! "intents")
            b (at-article! "intents")]
        (type-into! a ".field-title" "only in A")
        (is (= "only in A" (.-value (node a ".field-title"))))
        (is (= "Intents are data" (.-value (node b ".field-title")))
            "frames are isolated contexts: the second mount minted its own
             frame, and neither view was handed the other's — there is no
             frame argument anywhere in this application to hand")
        ;; BOTH roots come down before EITHER is asserted clean. A residue
        ;; census is page-wide — a standing peer's cells are inside the
        ;; reading — so unmounting `a` and asserting it while `b` is still
        ;; up reports `b`'s live subscriptions as `a`'s leak. The facade
        ;; says so in the failure it raises, which is how this was found.
        (hm/unmount! a)
        (hm/unmount! b)
        (-> (hm/assert-clean! a)
            (.then (fn [_] (hm/assert-clean! b)))
            (.then done))))))
