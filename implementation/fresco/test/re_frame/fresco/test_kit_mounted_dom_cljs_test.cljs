(ns re-frame.hicasso.test-kit-mounted-dom-cljs-test
  "THE MOUNTED FACADE'S OWN WITNESSES.

  `re-frame.hicasso.test.mounted` is an instrument, so every row here is
  either a POSITIVE control — the facade does what it claims on a working
  app — or a SABOTAGE control — a deliberate fault, and the instrument
  moves. An instrument asserted only on the working case is satisfied by
  one that always answers *fine*.

  ## The dogfood claim, one rung up

  `test-kit-dogfood-cljs-test`'s L1 row proves that *an intent written in
  the authoring spelling, lowered through the codec's prop walk, invoked
  as the browser would invoke it, reaches a real re-frame2 event handler
  and moves a real app-db* — through `ht/fire!`, with no element anywhere.
  That row states plainly what it does NOT prove: \"No element exists, so
  there is no bubbling, no default action, no focus … and no React event
  system.\"

  [[l3-a-real-click-on-a-real-button-moves-the-real-page]] is that same
  claim with every one of those present. The button is a DOM node in
  `document.body`, the click is the browser's own `HTMLElement.click()` —
  which is what `user-event` ultimately performs — it travels React's
  event system, and the assertion is on the repainted page rather than on
  a returned intent vector.

  ## What `assert-clean!` is driven with

  Four leak kinds, each driven rather than assumed:

  | leak | driven by | seen as |
  |---|---|---|
  | a subscription | a second root left standing inside the mount's window | `:cells` / `:cell-refs` / `:boundaries` / `:edges` |
  | a read-set entry | the same root | `:entries` |
  | a live frame | `rf/make-frame` inside the mount's window | `:frames`, naming the id |
  | a mounted root | skipping `unmount!` | `:still-mounted?` |

  And four it does NOT see, stated here rather than left to be
  discovered:

  - an armed **timer** (`setTimeout` / `setInterval` /
    `requestAnimationFrame`) and a **DOM listener on `document` or
    `window`** have no census in this runtime, and taking one would mean
    monkeypatching the host's scheduler and `addEventListener` — an
    instrument that rewrites the platform under the code it is measuring;
  - a **pending effect** — an async `rf/dispatch` still queued, or an fx
    in flight — likewise. Core publishes no queue census, and
    [[hm/dispatch-and-settle!]] uses the runtime's SYNCHRONOUS door, which
    drains to fixed point before it returns; so what this facade could
    honestly see is exactly what it already settles, and what it cannot
    see is what a test put in flight by another route;
  - a **core-level subscription** — `rf/subscribe` held outside a Hicasso
    body — is in core's sub-cache and not in the cell table this census
    reads;
  - a retained foreign-host **callback** is visible only through what it
    retains: if it holds a subscription it is the first row, and if it
    holds nothing countable it is invisible here.

  A facade that claimed cleanliness while checking one kind of residue
  would be worse than one that names its scope, so the scope is named.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`), and
  each row degrades there to a STATED skip rather than to a false green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.hicasso.test.mounted :as rf.hicasso.test.mounted]
            [re-frame.routing :as rf.routing]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; The screen — the dogfood row's shape, local to this file
;; ---------------------------------------------------------------------------
;;
;; Registered ABOVE `use-fixtures`, deliberately: the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED, so a
;; `reg-sub` written below it is erased before the first row runs.

(rf/reg-sub ::todo (fn [db [_ id]] (get-in db [:todos id])))

(rf/reg-event ::seed
              (fn [_ [_ n]]
                {:db {:todos (into {} (map (fn [i] [i {:id i :title (str "todo " i)
                                                      :done? false}]))
                                   (range n))}}))
(rf/reg-event ::toggle (fn [{:keys [db]} [_ id]]
                         {:db (update-in db [:todos id :done?] not)}))

(defn- plain-child
  "An ORDINARY function, and that is the whole of it: in child head
  position the runtime refuses a plain function outright (HD-016,
  `:rf.error/hicasso-bad-head`) rather than calling it. Deliberately not
  a `defview`, and deliberately referred to through its var so nothing
  can decide the question before the render does."
  [_props]
  [:span.unreachable "never rendered"])

(rf.hicasso/defview row-with-a-plain-function-child
  "A VALID registered view whose BODY is malformed. The view itself mounts;
  what the runtime refuses is the child head inside its body, from inside
  React's own render, which is the position a refusal is most easily lost
  from."
  [_]
  [:li.row [plain-child {}]])

(rf.hicasso/defview row
  "One to-do, with a toggle button. Reads exactly ONE subscription, which
  is what makes the residue arithmetic below readable: one live row is one
  cell, one reader membership, one boundary and one edge.

  It takes no frame argument. The frame arrives from the root it is
  mounted under — see `roots-frames-isolation-dom-cljs-test`, which states
  the same rule for the same reason."
  [{:keys [id]}]
  (let [todo (rf.hicasso/sub [::todo id])]
    [:li.row {:data-id id}
     [:span.title (:title todo)]
     [:span.done (str (boolean (:done? todo)))]
     [:button.toggle {:on-click [::toggle id]} "toggle"]]))

;; ---------------------------------------------------------------------------
;; Two routes and a link between them — W7's page
;; ---------------------------------------------------------------------------
;;
;; Registered here rather than borrowed from a witness application, because
;; what W7 needs is the router's ASYNC door and nothing else: an application
;; would bring a seed, a shell and a dozen assertions this file has no claim
;; on, and the smallest page that leaves work enqueued is two routes and one
;; anchor.
;;
;; The paths carry a leading segment of their own (TESTING.md, *Every app in
;; the shared node test bundle namespaces its URL paths*). Route ids are
;; namespaced keywords and cannot collide; route PATHS are plain strings in a
;; process-global registrar, and `npm run test:cljs` loads a dozen
;; applications into one process, where the first registration of a path wins
;; every URL forever.
;;
;; And registration is a FUNCTION as well as a load-time effect, for the
;; reason `examples/todo/routes.cljs` records: `re-frame.test-support`'s reset
;; fixture restores the registrar to a baseline captured when the
;; `use-fixtures` FORM is evaluated, so a route registered above it is rolled
;; back before the first row runs. `reg-sub` and `reg-event` survive; routes
;; do not.

(def ^:private here ::here)
(def ^:private there ::there)

(defn- register-routes!
  "Register this file's two routes. Idempotent for an unchanged
  registration, so calling it from the fixture as well as at load costs
  nothing."
  []
  (rf.routing/reg-route here  {:doc "W7's first page."}  "/hicasso-test-kit-mounted")
  (rf.routing/reg-route there {:doc "W7's second page."} "/hicasso-test-kit-mounted/there")
  nil)

(register-routes!)

(rf.hicasso/defview two-pages
  "The smallest page that can leave work ENQUEUED: an `h/route-link` and a
  reading of where the router thinks we are.

  Nothing here calls `preventDefault` — `re-frame.routing/activate-link!`
  is what claims the click, and if it did not, this page would navigate
  the test runner away."
  [_]
  (let [id (rf.hicasso/sub [:rf.route/id])]
    [:div.pages
     (rf.hicasso/route-link {:to there :class "to-there"} "go there")
     [:span.where (if (= there id) "there" "here")]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; `nil` and not the default: a dynamic-var frame stamp left in ambient
     ;; scope would let a boundary that failed to resolve its own frame
     ;; answer that one instead, and the isolation row below would read a
     ;; rendering difference where the failure is a frame miss.
     :ambient-frame nil
     ;; The MAP shape, because every row here is `async`. `cljs.test` refuses
     ;; an async test under a fn-form fixture and aborts the whole run at
     ;; this namespace.
     :async?        true
     :init-fn       (fn []
                      (rf.hicasso.roots-frames-support/leave-act-environment!)
                      (rf.hicasso.impl.collector/reset-runtime!)
                      ;; The reset rolled the registrar back past the
                      ;; load-time registration above — see that comment.
                      (register-routes!))}))

;; ---------------------------------------------------------------------------
;; Reading the page
;; ---------------------------------------------------------------------------

(defn- node-at [m sel] (.querySelector (:container m) sel))
(defn- text-at [m sel] (some-> (node-at m sel) .-textContent))

(defn- seeded
  "One mount of the row, under its own frame, seeded with three to-dos."
  ([] (seeded 1))
  ([id] (rf.hicasso.test.mounted/mount! [row {:id id}] {:initial-events [[::seed 3]]})))

;; ---------------------------------------------------------------------------
;; W1 — the dogfood claim, one rung up: a REAL click on a REAL page
;; ---------------------------------------------------------------------------

(deftest l3-a-real-click-on-a-real-button-moves-the-real-page
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [m (seeded)]
        (testing "the mount rendered the seeded screen into a container that is
                  ATTACHED to the document — which is the whole of the Testing
                  Library interop contract: `screen`, `within` and `getBy*` take
                  this node, and the facade adds no selector language of its own"
          (is (some? (:container m)))
          (is (true? (.-isConnected (:container m))))
          (is (true? (.contains js/document.body (:container m))))
          (is (= "todo 1" (text-at m ".title")))
          (is (= "false"  (text-at m ".done"))))

        (testing "a REAL click — `HTMLElement.click()`, which is what a
                  user-event sequence ultimately performs — travels React's own
                  event system, reaches the handler, moves app-db, and the page
                  repaints. This is the claim ht/fire! states it CANNOT make:
                  at L1 no element exists, so there is no bubbling, no default
                  action and no React event system"
          (.click (node-at m ".toggle"))
          (rf.hicasso.test.mounted/settle! m)
          (is (= "true" (text-at m ".done")))
          (is (true? (:done? (rf/with-frame (:frame m)
                               (deref (rf/subscribe [::todo 1])))))))

        (testing "and dispatch-and-settle! drives the same page from OUTSIDE
                  it — the handle's frame, the runtime's synchronous door, the
                  commit landed before the next line"
          (rf.hicasso.test.mounted/dispatch-and-settle! m [::toggle 1])
          (is (= "false" (text-at m ".done"))))

        (testing "teardown is clean, and this is the positive control the
                  sabotage rows below are measured against: without it, an
                  assert-clean! that never went green would be satisfied by an
                  instrument that always reds"
          (-> (rf.hicasso.test.mounted/unmount! m)
              (rf.hicasso.test.mounted/assert-clean!)
              (.then (fn [report]
                       (is (true? (:clean? report)))
                       (is (nil? (:leaked report)))
                       (done)))))))))

;; ---------------------------------------------------------------------------
;; W2 — frames are isolated contexts
;; ---------------------------------------------------------------------------

(deftest l3-two-mounts-are-two-isolated-frames
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [a (seeded)
            b (seeded)]
        (testing "neither mount was handed a frame — each minted its own, and
                  they are different"
          (is (not= (:frame a) (:frame b)))
          (is (keyword? (:frame a))))

        (testing "one view, two frames, one read each — TWO cells, and the frame
                  is what tells them apart. React has no opinion about this
                  structure and cannot repair it, which is why the claim is read
                  here rather than off the page"
          (is (= #{[(:frame a) [::todo 1]] [(:frame b) [::todo 1]]}
                 (rf.hicasso.roots-frames-support/cell-keys))
              (str "got " (pr-str (rf.hicasso.roots-frames-support/cell-keys))))
          (is (= [1 1] [(rf.hicasso.roots-frames-support/readers-of [(:frame a) [::todo 1]])
                        (rf.hicasso.roots-frames-support/readers-of [(:frame b) [::todo 1]])])
              "one reader each — a leak is TWO readers on ONE key"))

        (testing "a dispatch into A's frame moves A's page and NOT B's. This is
                  the property the whole framework rests on, and a facade that
                  let one mount reach the other would have broken it"
          (rf.hicasso.test.mounted/dispatch-and-settle! a [::toggle 1])
          (is (= "true"  (text-at a ".done")))
          (is (= "false" (text-at b ".done"))))

        (testing "both come down clean, each measured against its own baseline"
          (rf.hicasso.test.mounted/unmount! a)
          (rf.hicasso.test.mounted/unmount! b)
          (-> (rf.hicasso.test.mounted/assert-clean! a)
              (.then (fn [ra]
                       (is (true? (:clean? ra)))
                       (rf.hicasso.test.mounted/assert-clean! b)))
              (.then (fn [rb]
                       (is (true? (:clean? rb)))
                       (done)))))))))

;; ---------------------------------------------------------------------------
;; W3 — SABOTAGE: a leaked subscription
;; ---------------------------------------------------------------------------

(deftest l3-assert-clean-sees-a-leaked-subscription
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [m      (seeded)
            ;; THE SABOTAGE. A second root, put up inside the mount's window
            ;; through the runtime's own door and left standing when the root
            ;; under test comes down — the shape a foreign host that mounts its
            ;; own root and returns no cleanup leaves behind. Its boundary keeps
            ;; its cell, its reader membership, its edge and its read-set entry.
            ;;
            ;; Manufactured through `impl.mount` rather than through the facade
            ;; deliberately: a facade mount is a PEER and says so in the report
            ;; (`:standing`), and a peer explained by the instrument is not the
            ;; fault this row exists to detect.
            orphan (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) (:frame m) [row {:id 2}])]
        (rf.hicasso.test.mounted/unmount! m)
        (-> (rf.hicasso.test.mounted/residue m)
            (.then (fn [report]
                     (testing "the instrument moved: the mount is not clean"
                       (is (false? (:clean? report)))
                       (is (false? (:still-mounted? report))
                           "and not because the root is still up — it came down")
                       (is (zero? (:standing report))
                           "and not because a peer mount was standing — none was"))

                     (testing "and it says precisely what leaked. One live row is
                               one cell, one reader membership (which is both the
                               reference and the edge), one boundary and one
                               read-set entry"
                       (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1 :entries 1}
                              (:leaked report))
                           (str "baseline " (pr-str (:baseline report))
                                " now " (pr-str (:now report)))))

                     (testing "the baseline it is measured against is this
                               mount's own, taken before its root existed"
                       (is (= 0 (:cells (:baseline report))))
                       (is (= 1 (:cells (:now report)))))

                     ;; Restore: the orphan is this row's, and it does not
                     ;; belong to the next one.
                     (rf.hicasso.impl.mount/release! orphan)

                     ;; And FINALISE. `residue` reads; only `assert-clean!`
                     ;; records the verdict and releases this mount's private
                     ;; bookkeeping, so a sabotage row that stops at the
                     ;; reading leaves the facade holding a mount that will
                     ;; never be read — and holds the reset gate off zero for
                     ;; every row after it. The tests for a cleanliness
                     ;; instrument have to be clean themselves.
                     (-> (rf.hicasso.test.mounted/assert-clean! m)
                         (.then (fn [after]
                                  (is (true? (:clean? after))
                                      "the induced fault was repaired before the verdict")
                                  (done)))))))))))

(deftest l3-assert-clean-sees-a-frame-the-app-left-behind
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [m (seeded)]
        ;; THE SABOTAGE. A frame opened inside the mount's window and never
        ;; destroyed — an app that spawns a sub-frame for a dialog, a tenant or
        ;; a preview and forgets it. A count would say "one more frame"; the
        ;; report names the id, which is the one thing here a reader can act on.
        (rf/make-frame {:id ::spawned})
        (rf.hicasso.test.mounted/unmount! m)
        (-> (rf.hicasso.test.mounted/residue m)
            (.then (fn [report]
                     (is (false? (:clean? report)))
                     (is (= #{::spawned} (:frames (:leaked report)))
                         (str "got " (pr-str (:leaked report))))
                     (testing "and nothing else moved — the frame leak is
                               reported alone, so a reader is not sent hunting a
                               subscription that is not there"
                       (is (= [:frames] (keys (:leaked report)))))
                     (rf/destroy-frame! ::spawned)

                     ;; And FINALISE — see the row above.
                     (-> (rf.hicasso.test.mounted/assert-clean! m)
                         (.then (fn [after]
                                  (is (true? (:clean? after))
                                      "the spawned frame was destroyed before the verdict")
                                  (done)))))))))))

(deftest l3-assert-clean-sees-a-root-that-was-never-unmounted
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [m (seeded)]
        ;; THE SABOTAGE. No unmount! at all. A live root's cells ARE residue by
        ;; every number in the census, so an instrument that only counted would
        ;; red with a leak report and send a reader hunting a subscription. The
        ;; missing teardown is reported as itself.
        (-> (rf.hicasso.test.mounted/residue m)
            (.then (fn [report]
                     (is (true? (:still-mounted? report)))
                     (is (false? (:clean? report))
                         "the live root's own cell is above the baseline")
                     (is (= 1 (:cells (:leaked report))))
                     (-> (rf.hicasso.test.mounted/unmount! m)
                         (rf.hicasso.test.mounted/assert-clean!)
                         (.then (fn [after]
                                  (testing "and after the teardown it does go
                                            clean — the control that says the
                                            row above is a missing unmount and
                                            not a real leak"
                                    (is (false? (:still-mounted? after)))
                                    (is (true? (:clean? after))))
                                  (done)))))))))))

;; ---------------------------------------------------------------------------
;; W3b — the reset gate: a verdict on one mount must not blind a sibling's
;; ---------------------------------------------------------------------------
;;
;; `assert-clean!` resets the page-wide runtime after the LAST open mount
;; is read, and only then — `!open`'s whole account in
;; `re-frame.hicasso.test.mounted` (the rf2-2rtt6.48 shape: a census taken
;; after a reset answers zero whether the teardown released anything or
;; not, the gate that cannot go red). Every row above takes its verdicts
;; in an order an EARLY reset would also satisfy: no row plants a leak
;; under one mount, takes a sibling's verdict first, and then requires the
;; leaked mount's reading to still move. This row is that ordering.
;;
;; ORDER IS THE ROW. B mounts first and takes its baseline; the leak is
;; planted inside B's window (W3's orphan, verbatim); A mounts AFTER the
;; leak, so A's own baseline absorbs it and A's verdict is honestly clean.
;; A's verdict is taken FIRST — `!open` drops to one, B unread — and the
;; reset must hold, because a reset here empties the very census B's
;; reading is about to take: B's leak — real, planted, still leaked —
;; would read as clean.

(deftest l3-the-reset-gate-holds-while-a-sibling-mount-awaits-its-verdict
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [mb     (seeded 1)
            orphan (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) (:frame mb) [row {:id 2}])
            _      (rf.hicasso.test.mounted/unmount! mb)
            ma     (seeded 2)]
        (rf.hicasso.test.mounted/unmount! ma)
        (-> (rf.hicasso.test.mounted/assert-clean! ma)
            (.then
              (fn [ra]
                (is (true? (:clean? ra))
                    (str "premise: A is clean — the orphan predates A's own "
                         "baseline, so nothing here is A's residue. Got: "
                         (pr-str (:leaked ra))))
                (-> (rf.hicasso.test.mounted/residue mb)
                    (.then
                      (fn [rb]
                        (is (false? (:clean? rb))
                            "THE GATE. B's reading still moves: A's verdict
                             — one mount read, one still waiting — did not
                             reset the page-wide tables out from under it")
                        (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1 :entries 1}
                               (:leaked rb))
                            (str "and it is still W3's precise arithmetic, "
                                 "read against B's own baseline — a reset "
                                 "between the two verdicts zeroes both "
                                 "sides and reads clean. baseline "
                                 (pr-str (:baseline rb))
                                 " now " (pr-str (:now rb))))

                        ;; Restore, then FINALISE — the discipline W3
                        ;; states: the tests for a cleanliness instrument
                        ;; have to be clean themselves, and B's verdict is
                        ;; what lets the gate reach zero and reset.
                        (rf.hicasso.impl.mount/release! orphan)
                        (-> (rf.hicasso.test.mounted/assert-clean! mb)
                            (.then (fn [after]
                                     (is (true? (:clean? after))
                                         "the induced fault was repaired
                                          before the verdict")
                                     (done))))))))))))))

;; ---------------------------------------------------------------------------
;; W4 — render!: a props change through the same root
;; ---------------------------------------------------------------------------

(deftest l3-render-changes-props-without-remounting
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [m  (seeded 1)
            li (node-at m ".row")]
        (rf.hicasso.test.mounted/rerender! m [row {:id 2}])

        (testing "the new props reached the body and the page moved"
          (is (= "todo 2" (text-at m ".title")))
          (is (= "2" (.getAttribute (node-at m ".row") "data-id"))))

        (testing "and the SAME DOM node is still there — React re-rendered the
                  root rather than replacing it, which is the whole reason this
                  door exists and a claim no semantic tree can make"
          (is (identical? li (node-at m ".row"))))

        (-> (rf.hicasso.test.mounted/unmount! m)
            (rf.hicasso.test.mounted/assert-clean!)
            (.then (fn [report] (is (true? (:clean? report))) (done))))))))

;; ---------------------------------------------------------------------------
;; W5 — hydrate!: the promise resolves on ADOPTION, not on the call returning
;; ---------------------------------------------------------------------------

(deftest l3-hydrate-adopts-the-server-nodes
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [;; A throwaway frame, purely to produce the bytes an SSR route
            ;; would deliver. Released before anything is measured, so the
            ;; runtime the hydration is measured on is empty.
            bytes-frame ::server
            _           (rf/make-frame {:id bytes-frame
                                        :initial-events [[::seed 3]]})
            html        (rf.hicasso.roots-frames-support/server-html! bytes-frame [row {:id 1}])
            _           (rf/destroy-frame! bytes-frame)
            _           (rf.hicasso.impl.collector/reset-runtime!)
            ;; The page as it arrives: the server's nodes, stamped with an
            ;; EXPANDO. An expando cannot survive serialisation and cannot be
            ;; reconstructed, so a node still carrying it is THE node the
            ;; server markup produced — the only observable that tells adoption
            ;; from a client render that happens to agree.
            container   (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html))]
        (is (rf.hicasso.roots-frames-support/every-server-node? container ".row") "premise: the bytes were stamped")
        (-> (rf.hicasso.test.mounted/hydrate! [row {:id 1}] {:container container
                                        :initial-events [[::seed 3]]})
            (.then (fn [m]
                     (testing "the promise resolved on THIS root's adoption
                               window shutting, so the DOM on this line is the
                               adopted one — hydrate-root! itself returns while
                               the page is still the server's"
                       (is (rf.hicasso.roots-frames-support/every-server-node? (:container m) ".row")
                           "the server's own nodes were adopted, not replaced")
                       (is (= "todo 1" (text-at m ".title"))))

                     (testing "and it is live: a dispatch reaches the adopted
                               tree"
                       (rf.hicasso.test.mounted/dispatch-and-settle! m [::toggle 1])
                       (is (= "true" (text-at m ".done")))
                       (is (rf.hicasso.roots-frames-support/every-server-node? (:container m) ".row")
                           "still the same nodes after a commit"))

                     (-> (rf.hicasso.test.mounted/unmount! m)
                         (rf.hicasso.test.mounted/assert-clean!)
                         (.then (fn [report]
                                  (is (true? (:clean? report)))
                                  (done)))))))))))

;; ---------------------------------------------------------------------------
;; W6 — construction is TRANSACTIONAL: a refused mount leaves NOTHING behind
;; ---------------------------------------------------------------------------
;;
;; Three rows, one claim: a `mount!` or a `hydrate!` that does not become a
;; mount puts the page back exactly as it found it and hands the caller the
;; runtime's own refusal.
;;
;; Each row drives a DIFFERENT failure channel, because the three fail in
;; three places and a repair to one says nothing about the others:
;;
;;   | row | fails in | reaches the caller as |
;;   |---|---|---|
;;   | W6a | a body's render, which React swallows | a throw |
;;   | W6b | the codec, on `hydrate-root!`'s own stack | a rejection |
;;   | W6c | adoption, which never completes | a rejection |
;;
;; And every row asserts the RESTORED STATE as well as the refusal, which is
;; the difference between a row that catches the fault this family is
;; about and one that does not: the fault is never that nothing was
;; refused — React reports the error at the window — it is that `mount!`
;; answers a handle and leaves a registered frame and an attached
;; container behind. A row that merely observes a refusal passes against
;; every one of those leaks.

(defn- body-children [] (.-childElementCount js/document.body))

(deftest l3-mount-propagates-the-runtimes-refusal-and-leaves-nothing-behind
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [before   (rf.hicasso.test.mounted/census)
            children (body-children)
            outcome  (try (rf.hicasso.test.mounted/mount! [row-with-a-plain-function-child {}])
                          (catch :default e e))]

        (testing "the refusal reached the CALLER, carrying the runtime's own id,
                  recovery and offending value — not a paraphrase minted here,
                  and not the handle a mount that rendered nothing used to
                  answer. React 19 does not re-throw a failed render out of
                  `flushSync`: it hands the error to the root's
                  `onUncaughtError`, whose default reports it at the window and
                  returns — which is exactly how a mount could refuse and
                  succeed at the same time (PR #7822's audit)"
          (is (instance? ExceptionInfo outcome)
              (str "mount! answered " (pr-str outcome) " instead of refusing"))
          (when (instance? ExceptionInfo outcome)
            (let [data (ex-data outcome)]
              (is (= :rf.error/hicasso-bad-head (:rf.error/id data)))
              (is (identical? plain-child (:head data))
                  "the offending head is the one the body wrote"))))

        (-> (rf.hicasso.test.runtime/quiesced!)
            (.then
              (fn [_]
                (testing "and the page is as the call found it. The frame this
                          mount minted is gone, the container it appended is
                          gone, and every residue counter is back — so a
                          programmer who catches this refusal has nothing left
                          to clean up and no handle they would have had to be
                          given one"
                  (is (= before (rf.hicasso.test.mounted/census))
                      (str "the census moved: " (pr-str before) " → "
                           (pr-str (rf.hicasso.test.mounted/census))))
                  (is (= children (body-children))
                      "the container the failed mount appended is still attached"))

                (testing "and the facade's own bookkeeping is untouched: the next
                          mount reports no standing peer and goes clean. A
                          `!standing` left incremented by the failed call shows
                          up here and nowhere else"
                  (let [m (seeded)]
                    (rf.hicasso.test.mounted/unmount! m)
                    (-> (rf.hicasso.test.mounted/assert-clean! m)
                        (.then (fn [report]
                                 (is (zero? (:standing report)))
                                 (is (true? (:clean? report)))
                                 (done)))))))))))))

(deftest l3-hydrate-rejects-a-form-the-codec-refuses-and-leaves-nothing-behind
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [;; The caller's OWN container, carrying the caller's own bytes —
            ;; the arm where the rollback must NOT tidy up, because the node is
            ;; not the facade's to delete (the same rule `unmount!` follows).
            container (js/document.createElement "div")
            _         (set! (.-innerHTML container) "<li class=\"row\">server</li>")
            _         (.appendChild js/document.body container)
            before    (rf.hicasso.test.mounted/census)
            children  (body-children)]
        ;; THE FAULT: an empty hiccup vector has no head, and the codec refuses
        ;; it while `hydrate-root!` is still building its root element — on
        ;; that call's own stack, before React is handed anything.
        (-> (rf.hicasso.test.mounted/hydrate! [] {:container container})
            (.then (fn [m]
                     (is false (str "hydrate! resolved with " (pr-str m)
                                    " for a form the codec refuses")))
                   (fn [e]
                     (testing "a promise-returning door refuses by REJECTING.
                               A synchronous throw would land outside every
                               `.catch` the caller attached and hang the async
                               test instead of failing it"
                       (is (instance? ExceptionInfo e))
                       (is (= :rf.error/hicasso-empty-vector
                              (:rf.error/id (ex-data e)))))))
            (.then (fn [_] (rf.hicasso.test.runtime/quiesced!)))
            (.then (fn [_]
                     (testing "the frame is destroyed and no counter moved"
                       (is (= before (rf.hicasso.test.mounted/census))
                           (str "the census moved: " (pr-str before) " → "
                                (pr-str (rf.hicasso.test.mounted/census)))))
                     (testing "and the caller's container is EXACTLY where they
                               left it, with the bytes they put in it. A
                               rollback that removed it would delete a node the
                               facade did not create"
                       (is (true? (.-isConnected container)))
                       (is (= children (body-children)))
                       (is (= "<li class=\"row\">server</li>" (.-innerHTML container))))
                     (.removeChild js/document.body container)
                     (done))))))))

(deftest l3-hydrate-that-never-adopts-rejects-and-leaves-nothing-behind
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [bytes-frame ::timeout-server
            _           (rf/make-frame {:id bytes-frame
                                        :initial-events [[::seed 3]]})
            html        (rf.hicasso.roots-frames-support/server-html! bytes-frame [row {:id 1}])
            _           (rf/destroy-frame! bytes-frame)
            _           (rf.hicasso.impl.collector/reset-runtime!)
            before      (rf.hicasso.test.mounted/census)
            children    (body-children)
            ;; THE ROLLBACK'S OWN NOISE, captured rather than suppressed.
            ;;
            ;; Taking down a root React has not yet hydrated IS a switch to
            ;; client rendering, and React says so: it queues a hydration
            ;; error, which reaches the root's `onRecoverableError` — the one
            ;; `impl.mount/hydrate-root!` installs, which always delegates to
            ;; React's default (the pageerror fail-open) — and so reaches the
            ;; window uncaught, where the browser runner treats it as fatal.
            ;;
            ;; That rule is right and this row does not soften it. The row
            ;; MANUFACTURES the fault and ASSERTS on the report, so the signal
            ;; moves into the row instead of being lost, and `preventDefault`
            ;; marks the event handled for the extent of this one rollback and
            ;; nowhere else. The bench lane's hydration witnesses spell the
            ;; same rule as `:swallow-uncaught?`; naming it is provenance, not
            ;; a dependency — the freeze gate forbids importing that tree.
            reported    (atom [])
            on-error    (fn [^js e]
                          (swap! reported conj (.-message e))
                          (.preventDefault e))
            _           (.addEventListener js/window "error" on-error)]
        ;; THE FAULT: a budget already spent when the first poll runs, which is
        ;; the deterministic way into the timeout branch. The alternative — a
        ;; body that throws during adoption — never resolves EITHER, but it
        ;; also reports an uncaught error at the window, and that is a
        ;; different fault with a channel of its own.
        (-> (rf.hicasso.test.mounted/hydrate! [row {:id 1}] {:html html :initial-events [[::seed 3]]} -1)
            (.then (fn [m]
                     (is false (str "hydrate! resolved with " (pr-str m)
                                    " on a spent budget")))
                   (fn [e]
                     (testing "it rejects rather than resolving with a handle
                               whose adoption never happened"
                       (is (instance? ExceptionInfo e))
                       (is (some? (re-find #"never shut" (ex-message e)))
                           (str "got " (pr-str (ex-message e)))))

                     (testing "and the frame it minted is DESTROYED. This is
                               what a rejection-only row cannot see: before the
                               repair the timeout branch rejected and did
                               nothing else — the frame stayed registered, the
                               container stayed attached, the root stayed up
                               with its adoption window open"
                       (let [frame (:frame (ex-data e))]
                         (is (keyword? frame))
                         (is (not (contains? (set (rf/frame-ids)) frame))
                             (str frame " is still registered"))))))
            (.then (fn [_] (rf.hicasso.test.runtime/quiesced!)))
            (.then (fn [_]
                     (.removeEventListener js/window "error" on-error)
                     (is (= before (rf.hicasso.test.mounted/census))
                         (str "the census moved: " (pr-str before) " → "
                              (pr-str (rf.hicasso.test.mounted/census))))
                     (testing "and the container the facade minted for the
                               server bytes is gone, because this one IS the
                               facade's to remove"
                       (is (= children (body-children))))
                     (testing "and React reported the teardown of a root it had
                               not yet hydrated, exactly once — the rollback's
                               own consequence, named here so that a reader
                               meeting it in their own suite knows what it is"
                       (is (= 1 (count @reported)) (str "got " (pr-str @reported)))
                       (is (some? (some #(re-find #"early update" %) @reported))
                           (str "got " (pr-str @reported))))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; W7 — settle-until!: the door for work the router has merely ENQUEUED
;; ---------------------------------------------------------------------------
;;
;; A door that WAITS has two failure modes, and a witness driving only the
;; first proves the door RETURNS rather than that it waits. Both are driven
;; here: it must return when the condition becomes true, and it must fail at
;; the deadline when the condition never does.
;;
;; The positive row carries its own CONTROL rather than describing one. The
;; navigation is asserted still-unlanded after `settle!` and landed after
;; `settle-until!` — the same reading, two lines apart, one door between them
;; — so the row goes red if `settle!` ever became sufficient, which is the
;; only way a reader can tell this door from decoration.
;;
;; What is NOT re-driven here is `poll-until`'s retry, deadline and error
;; matrix: this door COMPOSES that poll and copies none of it (commit
;; 88b20f1d22 deleted exactly such a duplicate), and `test_support_test` is
;; that contract's authority. What W7b asserts is the composition — that the
;; canonical rejection reaches THIS door's caller unchanged.

(defn- routed
  "One mount of [[two-pages]], opened on the first route."
  []
  (rf.hicasso.test.mounted/mount! [two-pages {}]
             {:initial-events [[:rf.route/navigate {:to here}]]}))

(defn- route-id-of
  "Where the ROUTER thinks the mount is — read from the frame, never off
  the page. That distinction is the whole of why the door ends in a
  flush: app-db moves when the drained handlers run, and React's commit
  for the store notification may still be pending at that instant."
  [m]
  (rf/subscribe-once [:rf.route/id] {:frame (:frame m)}))

(deftest l3-settle-until-lands-work-the-router-merely-enqueued
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [m (routed)]
        (is (= here (route-id-of m)) "premise: the page opened on the first route")
        (is (= "here" (text-at m ".where")))
        (is (= "/hicasso-test-kit-mounted/there"
               (.getAttribute (node-at m ".to-there") "href"))
            "premise: the link is routing's own, so clicking it is a real
             route-link click rather than a dispatch dressed as one")

        ;; The real event, through React's own event system, with routing's
        ;; own `activate-link!` deciding.
        (.click (node-at m ".to-there"))

        (testing "THE CONTROL, and the red this door repairs. `settle!` is an
                  empty flushSync, and a route-link's navigate ends in
                  `router/dispatch!` — the ASYNC door — so the drain rides
                  `interop/next-tick`, a next-turn TASK, and at this line
                  nothing has reached React for the flush to commit. Neither
                  app-db nor the page has moved"
          (rf.hicasso.test.mounted/settle! m)
          (is (= here (route-id-of m))
              "the navigation is enqueued, not landed")
          (is (= "here" (text-at m ".where"))))

        (-> (rf.hicasso.test.mounted/settle-until! m #(= there (route-id-of m))
                              {:label "the route-link's navigate to drain"})
            (.then
              (fn [answered]
                (testing "and after the door, the same two readings have both
                          moved — which is the claim, since the condition is
                          read off the FRAME and the assertion off the PAGE:
                          the poll answers the first and the trailing flush is
                          what makes the second safe on the next line"
                  (is (= there (route-id-of m)))
                  (is (= "there" (text-at m ".where"))))

                (testing "it answers the handle it was given, unchanged, so
                          the door threads like every other one here"
                  (is (identical? m answered))
                  (is (= (:frame m) (:frame answered))))))
            (.catch (fn [e]
                      (is false (str "settle-until! never settled: "
                                     (or (ex-message e) (str e)) " "
                                     (pr-str (ex-data e))))
                      nil))
            (.then (fn [_]
                     (-> (rf.hicasso.test.mounted/unmount! m)
                         (rf.hicasso.test.mounted/assert-clean!)
                         (.then (fn [report]
                                  (is (true? (:clean? report)))
                                  (done)))))))))))

(deftest l3-settle-until-fails-at-its-deadline-rather-than-hanging
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [m (routed)]
        ;; THE SABOTAGE: a condition that cannot ever hold, and nothing
        ;; clicked, so no navigation is even enqueued. The only way out of
        ;; this row is the deadline — which is what makes it a witness for
        ;; the WAITING rather than for the returning.
        (-> (rf.hicasso.test.mounted/settle-until! m (constantly false)
                              {:timeout-ms 50
                               :interval-ms 5
                               :label "a condition that never holds"})
            (.then (fn [answered]
                     (is false (str "settle-until! resolved with "
                                    (pr-str answered)
                                    " for a condition that never holds")))
                   (fn [e]
                     (testing "it REJECTS at the deadline, carrying
                               `poll-until`'s own canonical refusal
                               unchanged — same id, same `:elapsed-ms`, same
                               `:label` naming the assertion site. This door
                               mints no id of its own and wraps none of the
                               poll's, which is what *composes* means here"
                       (is (instance? ExceptionInfo e))
                       (let [data (ex-data e)]
                         (is (= :rf.error/poll-until-timeout (:rf.error/id data)))
                         (is (= "a condition that never holds" (:label data)))
                         (is (number? (:elapsed-ms data)))))

                     (testing "and it rejects rather than throwing on the
                               caller's own stack: a synchronous throw out of
                               a promise-returning door lands outside every
                               `.catch` attached to it and HANGS the async
                               test instead of failing it — this row reaching
                               its handler at all is that claim"
                       (is true "the rejection reached a handler"))))
            (.then (fn [_]
                     (-> (rf.hicasso.test.mounted/unmount! m)
                         (rf.hicasso.test.mounted/assert-clean!)
                         (.then (fn [report]
                                  (is (true? (:clean? report)))
                                  (done)))))))))))
