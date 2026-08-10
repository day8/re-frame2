(ns re-frame.hicasso.test-kit-mounted-dom-cljs-test
  "THE MOUNTED FACADE'S OWN WITNESSES (rf2-hic-027).

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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

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

(h/defview row-with-a-plain-function-child
  "A VALID registered view whose BODY is malformed — PR #7822's audit
  witness, kept. The view itself mounts; what the runtime refuses is the
  child head inside its body, from inside React's own render, which is
  the reason the refusal used to be lost."
  [_]
  [:li.row [plain-child {}]])

(h/defview row
  "One to-do, with a toggle button. Reads exactly ONE subscription, which
  is what makes the residue arithmetic below readable: one live row is one
  cell, one reader membership, one boundary and one edge.

  It takes no frame argument. The frame arrives from the root it is
  mounted under — see `roots-frames-isolation-dom-cljs-test`, which states
  the same rule for the same reason."
  [{:keys [id]}]
  (let [todo (h/sub [::todo id])]
    [:li.row {:data-id id}
     [:span.title (:title todo)]
     [:span.done (str (boolean (:done? todo)))]
     [:button.toggle {:on-click [::toggle id]} "toggle"]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
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
                      (sup/leave-act-environment!)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Reading the page
;; ---------------------------------------------------------------------------

(defn- node-at [m sel] (.querySelector (:container m) sel))
(defn- text-at [m sel] (some-> (node-at m sel) .-textContent))

(defn- seeded
  "One mount of the row, under its own frame, seeded with three to-dos."
  ([] (seeded 1))
  ([id] (hm/mount! [row {:id id}] {:initial-events [[::seed 3]]})))

;; ---------------------------------------------------------------------------
;; W1 — the dogfood claim, one rung up: a REAL click on a REAL page
;; ---------------------------------------------------------------------------

(deftest l3-a-real-click-on-a-real-button-moves-the-real-page
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
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
          (hm/settle! m)
          (is (= "true" (text-at m ".done")))
          (is (true? (:done? (rf/with-frame (:frame m)
                               (deref (rf/subscribe [::todo 1])))))))

        (testing "and dispatch-and-settle! drives the same page from OUTSIDE
                  it — the handle's frame, the runtime's synchronous door, the
                  commit landed before the next line"
          (hm/dispatch-and-settle! m [::toggle 1])
          (is (= "false" (text-at m ".done"))))

        (testing "teardown is clean, and this is the positive control the
                  sabotage rows below are measured against: without it, an
                  assert-clean! that never went green would be satisfied by an
                  instrument that always reds"
          (-> (hm/unmount! m)
              (hm/assert-clean!)
              (.then (fn [report]
                       (is (true? (:clean? report)))
                       (is (nil? (:leaked report)))
                       (done)))))))))

;; ---------------------------------------------------------------------------
;; W2 — frames are isolated contexts
;; ---------------------------------------------------------------------------

(deftest l3-two-mounts-are-two-isolated-frames
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
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
                 (sup/cell-keys))
              (str "got " (pr-str (sup/cell-keys))))
          (is (= [1 1] [(sup/readers-of [(:frame a) [::todo 1]])
                        (sup/readers-of [(:frame b) [::todo 1]])])
              "one reader each — a leak is TWO readers on ONE key"))

        (testing "a dispatch into A's frame moves A's page and NOT B's. This is
                  the property the whole framework rests on, and a facade that
                  let one mount reach the other would have broken it"
          (hm/dispatch-and-settle! a [::toggle 1])
          (is (= "true"  (text-at a ".done")))
          (is (= "false" (text-at b ".done"))))

        (testing "both come down clean, each measured against its own baseline"
          (hm/unmount! a)
          (hm/unmount! b)
          (-> (hm/assert-clean! a)
              (.then (fn [ra]
                       (is (true? (:clean? ra)))
                       (hm/assert-clean! b)))
              (.then (fn [rb]
                       (is (true? (:clean? rb)))
                       (done)))))))))

;; ---------------------------------------------------------------------------
;; W3 — SABOTAGE: a leaked subscription
;; ---------------------------------------------------------------------------

(deftest l3-assert-clean-sees-a-leaked-subscription
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
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
            orphan (mount/root! (mount/fresh-container!) (:frame m) [row {:id 2}])]
        (hm/unmount! m)
        (-> (hm/residue m)
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
                     (mount/release! orphan)

                     ;; And FINALISE. `residue` reads; only `assert-clean!`
                     ;; records the verdict and releases this mount's private
                     ;; bookkeeping, so a sabotage row that stops at the
                     ;; reading leaves the facade holding a mount that will
                     ;; never be read — and holds the reset gate off zero for
                     ;; every row after it. The tests for a cleanliness
                     ;; instrument have to be clean themselves (PR #7822's
                     ;; audit).
                     (-> (hm/assert-clean! m)
                         (.then (fn [after]
                                  (is (true? (:clean? after))
                                      "the induced fault was repaired before the verdict")
                                  (done)))))))))))

(deftest l3-assert-clean-sees-a-frame-the-app-left-behind
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [m (seeded)]
        ;; THE SABOTAGE. A frame opened inside the mount's window and never
        ;; destroyed — an app that spawns a sub-frame for a dialog, a tenant or
        ;; a preview and forgets it. A count would say "one more frame"; the
        ;; report names the id, which is the one thing here a reader can act on.
        (rf/make-frame {:id ::spawned})
        (hm/unmount! m)
        (-> (hm/residue m)
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
                     (-> (hm/assert-clean! m)
                         (.then (fn [after]
                                  (is (true? (:clean? after))
                                      "the spawned frame was destroyed before the verdict")
                                  (done)))))))))))

(deftest l3-assert-clean-sees-a-root-that-was-never-unmounted
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [m (seeded)]
        ;; THE SABOTAGE. No unmount! at all. A live root's cells ARE residue by
        ;; every number in the census, so an instrument that only counted would
        ;; red with a leak report and send a reader hunting a subscription. The
        ;; missing teardown is reported as itself.
        (-> (hm/residue m)
            (.then (fn [report]
                     (is (true? (:still-mounted? report)))
                     (is (false? (:clean? report))
                         "the live root's own cell is above the baseline")
                     (is (= 1 (:cells (:leaked report))))
                     (-> (hm/unmount! m)
                         (hm/assert-clean!)
                         (.then (fn [after]
                                  (testing "and after the teardown it does go
                                            clean — the control that says the
                                            row above is a missing unmount and
                                            not a real leak"
                                    (is (false? (:still-mounted? after)))
                                    (is (true? (:clean? after))))
                                  (done)))))))))))

;; ---------------------------------------------------------------------------
;; W4 — render!: a props change through the same root
;; ---------------------------------------------------------------------------

(deftest l3-render-changes-props-without-remounting
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [m  (seeded 1)
            li (node-at m ".row")]
        (hm/render! m [row {:id 2}])

        (testing "the new props reached the body and the page moved"
          (is (= "todo 2" (text-at m ".title")))
          (is (= "2" (.getAttribute (node-at m ".row") "data-id"))))

        (testing "and the SAME DOM node is still there — React re-rendered the
                  root rather than replacing it, which is the whole reason this
                  door exists and a claim no semantic tree can make"
          (is (identical? li (node-at m ".row"))))

        (-> (hm/unmount! m)
            (hm/assert-clean!)
            (.then (fn [report] (is (true? (:clean? report))) (done))))))))

;; ---------------------------------------------------------------------------
;; W5 — hydrate!: the promise resolves on ADOPTION, not on the call returning
;; ---------------------------------------------------------------------------

(deftest l3-hydrate-adopts-the-server-nodes
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [;; A throwaway frame, purely to produce the bytes an SSR route
            ;; would deliver. Released before anything is measured, so the
            ;; runtime the hydration is measured on is empty.
            bytes-frame ::server
            _           (rf/make-frame {:id bytes-frame
                                        :initial-events [[::seed 3]]})
            html        (sup/server-html! bytes-frame [row {:id 1}])
            _           (rf/destroy-frame! bytes-frame)
            _           (collector/reset-runtime!)
            ;; The page as it arrives: the server's nodes, stamped with an
            ;; EXPANDO. An expando cannot survive serialisation and cannot be
            ;; reconstructed, so a node still carrying it is THE node the
            ;; server markup produced — the only observable that tells adoption
            ;; from a client render that happens to agree.
            container   (sup/stamp-server-nodes! (sup/server-dom! html))]
        (is (sup/every-server-node? container ".row") "premise: the bytes were stamped")
        (-> (hm/hydrate! [row {:id 1}] {:container container
                                        :initial-events [[::seed 3]]})
            (.then (fn [m]
                     (testing "the promise resolved on THIS root's adoption
                               window shutting, so the DOM on this line is the
                               adopted one — hydrate-root! itself returns while
                               the page is still the server's"
                       (is (sup/every-server-node? (:container m) ".row")
                           "the server's own nodes were adopted, not replaced")
                       (is (= "todo 1" (text-at m ".title"))))

                     (testing "and it is live: a dispatch reaches the adopted
                               tree"
                       (hm/dispatch-and-settle! m [::toggle 1])
                       (is (= "true" (text-at m ".done")))
                       (is (sup/every-server-node? (:container m) ".row")
                           "still the same nodes after a commit"))

                     (-> (hm/unmount! m)
                         (hm/assert-clean!)
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
;; the difference between a row that would have caught PR #7822's audit
;; finding and one that would not: the fault there was never that nothing
;; was refused — React reported the error at the window — it was that
;; `mount!` answered a handle and left a registered frame and an attached
;; container behind. A row that merely observed a refusal passes against
;; every one of those leaks.

(defn- body-children [] (.-childElementCount js/document.body))

(deftest l3-mount-propagates-the-runtimes-refusal-and-leaves-nothing-behind
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [before   (hm/census)
            children (body-children)
            outcome  (try (hm/mount! [row-with-a-plain-function-child {}])
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
              (is (= :call-it-or-make-it-a-view (:recovery data)))
              (is (identical? plain-child (:head data))
                  "the offending head is the one the body wrote"))))

        (-> (inventory/quiesced!)
            (.then
              (fn [_]
                (testing "and the page is as the call found it. The frame this
                          mount minted is gone, the container it appended is
                          gone, and every residue counter is back — so a
                          programmer who catches this refusal has nothing left
                          to clean up and no handle they would have had to be
                          given one"
                  (is (= before (hm/census))
                      (str "the census moved: " (pr-str before) " → "
                           (pr-str (hm/census))))
                  (is (= children (body-children))
                      "the container the failed mount appended is still attached"))

                (testing "and the facade's own bookkeeping is untouched: the next
                          mount reports no standing peer and goes clean. A
                          `!standing` left incremented by the failed call shows
                          up here and nowhere else"
                  (let [m (seeded)]
                    (hm/unmount! m)
                    (-> (hm/assert-clean! m)
                        (.then (fn [report]
                                 (is (zero? (:standing report)))
                                 (is (true? (:clean? report)))
                                 (done)))))))))))))

(deftest l3-hydrate-rejects-a-form-the-codec-refuses-and-leaves-nothing-behind
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [;; The caller's OWN container, carrying the caller's own bytes —
            ;; the arm where the rollback must NOT tidy up, because the node is
            ;; not the facade's to delete (rf2-31xm, the same rule `unmount!`
            ;; follows).
            container (js/document.createElement "div")
            _         (set! (.-innerHTML container) "<li class=\"row\">server</li>")
            _         (.appendChild js/document.body container)
            before    (hm/census)
            children  (body-children)]
        ;; THE FAULT: an empty hiccup vector has no head, and the codec refuses
        ;; it while `hydrate-root!` is still building its root element — on
        ;; that call's own stack, before React is handed anything.
        (-> (hm/hydrate! [] {:container container})
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
            (.then (fn [_] (inventory/quiesced!)))
            (.then (fn [_]
                     (testing "the frame is destroyed and no counter moved"
                       (is (= before (hm/census))
                           (str "the census moved: " (pr-str before) " → "
                                (pr-str (hm/census)))))
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
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [bytes-frame ::timeout-server
            _           (rf/make-frame {:id bytes-frame
                                        :initial-events [[::seed 3]]})
            html        (sup/server-html! bytes-frame [row {:id 1}])
            _           (rf/destroy-frame! bytes-frame)
            _           (collector/reset-runtime!)
            before      (hm/census)
            children    (body-children)
            ;; THE ROLLBACK'S OWN NOISE, captured rather than suppressed.
            ;;
            ;; Taking down a root React has not yet hydrated IS a switch to
            ;; client rendering, and React says so: it queues a hydration
            ;; error, which reaches the root's `onRecoverableError` — the one
            ;; `impl.mount/hydrate-root!` installs, which always delegates to
            ;; React's default (rf2-mwx08's fail-open) — and so reaches the
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
        (-> (hm/hydrate! [row {:id 1}] {:html html :initial-events [[::seed 3]]} -1)
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
            (.then (fn [_] (inventory/quiesced!)))
            (.then (fn [_]
                     (.removeEventListener js/window "error" on-error)
                     (is (= before (hm/census))
                         (str "the census moved: " (pr-str before) " → "
                              (pr-str (hm/census))))
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
