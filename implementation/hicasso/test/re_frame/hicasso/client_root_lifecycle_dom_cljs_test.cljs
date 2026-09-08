(ns re-frame.hicasso.client-root-lifecycle-dom-cljs-test
  "THE CLIENT-ROOT GRAMMAR — Spec 006 §The client root, Hicasso's
  realisation (rf2-kuky.59).

  What is under test is the SHAPE all four React view adapters now share:
  an inert `h/client-root` handle, one `h/render!` whose FIRST call
  creates or adopts and whose later calls update, and an idempotent
  `h/unmount!`. The rows below mirror
  `implementation/adapters/reagent/test/re_frame/adapter_client_root_{,dom_}cljs_test.cljs`
  claim for claim, so a divergence between Hicasso's own root path and the
  spine's shows up as a red here rather than as a difference nobody reads.

  ## Why Hicasso needs its own copy of those rows

  Hicasso does NOT ride `spine/make-client-root-fns`. Its roots carry
  ownership the shared factory has no place for — a `flushSync` commit on
  every update, a per-root adoption window, a per-root recoverable-error
  reporter, and the stable wrapper tree `impl.mount/tree` puts over every
  post-hydration render so the client and
  `re-frame.hicasso.server/render` agree about `useId` — and it may not
  `:require` the spine at all, because that would put core's React-hook
  machinery into every Hicasso bundle (the optional-module invariant
  `hicasso/scripts/check_optional_module_reachability.py` guards). So the
  semantics are pinned by TEST rather than by a shared call, and this file
  is where.

  ## Which adapter, and why it matters for one row

  `rf/destroy-adapter!` releases a Hicasso root because
  `re-frame.hicasso.substrate/adapter` chains the package's own drain onto
  the spine's `dispose-adapter!`. That row therefore installs the HICASSO
  adapter; the others install UIx, as the rest of this package's suites do,
  and the difference is stated at the row.

  ## The readings

  A root whose runtime has been emptied under it still LOOKS right, so
  nothing here concludes from `innerHTML` alone. Update-without-remount is
  read as DOM NODE IDENTITY plus an uncontrolled input's value — the two
  things a `createRoot` would destroy and a reconcile cannot. Adoption is
  read as `roots-frames-support`'s server-node EXPANDO, which survives a
  reconcile and does not survive a replacement."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react" :as react]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.hicasso.substrate :as rf.hicasso.substrate]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-a ::frame-a)

(def ^:private label-q [::label])

(rf/reg-sub ::label (fn [db _] (:label db)))

(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-event ::relabel (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

;; `:async? true` because W5 is an `async` row, and `cljs.test` refuses a
;; FUNCTION fixture in any namespace that carries one — "Async tests require
;; fixtures to be specified as maps. Testing aborted." The flag is what makes
;; `make-reset-runtime-fixture` hand back the `{:before :after}` map form; the
;; async SSR suites in this package all set it for the same reason.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app
;; ---------------------------------------------------------------------------

(rf.hicasso/defview panel
  "One read, one tag the caller supplies, and an UNCONTROLLED input.

  The input is the state witness: it names no `:value`, so React never
  writes to it and whatever the DOM holds is host state React is merely
  preserving. A reconcile keeps it; a second `createRoot` cannot."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag}
   [:span.label (rf.hicasso/sub label-q)]
   [:input.draft {:type "text"}]])

;; A `useId` probe, rendered as TEXT for `identifier_prefix_ssr_dom_cljs_test`'s
;; reason: an id parked in an attribute diverges in silence, where text is what
;; React's own hydration contract covers.
(defn- id-probe
  [_props]
  (react/createElement "b" #js {:className "probe"} (react/useId)))

(rf.hicasso/defhost id-host id-probe {:server :render})

(rf.hicasso/defview id-panel
  [_]
  [:div.panel [id-host {}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip! [why] (rf.hicasso.roots-frames-support/skip! why))

(defn- fresh!
  "One frame, seeded, and an empty runtime."
  []
  (rf.hicasso.roots-frames-support/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/with-frame frame-a (rf/dispatch-sync [::seed "alpha"]))
  (rf.hicasso.impl.collector/reset-runtime!)
  nil)

(defn- node-at [container sel] (.querySelector container sel))
(defn- text-at [container sel] (some-> (node-at container sel) .-textContent))

(defn- detach!
  "Remove a container this suite minted. Only in `finally`, and only after
  the readings that prove the door left it alone."
  [container]
  (when-some [p (.-parentNode container)] (.removeChild p container))
  nil)

;; ---------------------------------------------------------------------------
;; W1 — allocation is INERT
;; ---------------------------------------------------------------------------
;;
;; The claim `defonce`-at-namespace-load rests on, and the reason it is a row
;; rather than a remark: an allocation that touched the DOM would make every
;; boot in the guide order-dependent, and would be invisible until somebody
;; loaded the namespace on Node.

(deftest client-root-allocates-an-inert-handle
  (let [handle (rf.hicasso/client-root)]
    (testing "a fresh handle holds no root — no `createRoot`, no DOM work,
              nothing to undo"
      (is (nil? @handle)))
    (testing "and tearing down a never-rendered handle is a no-op that
              answers nil rather than throwing"
      (is (nil? (rf.hicasso/unmount! handle)))
      (is (nil? @handle)))))

;; ---------------------------------------------------------------------------
;; W2 — CREATE ONCE, UPDATE LATER
;; ---------------------------------------------------------------------------
;;
;; The whole reason one verb is enough. The reading is not the markup: adopted,
;; re-rendered and re-created markup all look alike in `innerHTML`. It is node
;; IDENTITY plus an uncontrolled input's value — host state React preserves
;; across a reconcile and cannot preserve across a second `createRoot`.

(deftest the-first-render-creates-and-every-later-render-updates
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_  (fresh!)
          ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)]
      (rf.hicasso/render! a [rf.hicasso/frame-root {:id frame-a} [panel {:tag "first"}]] ca)
      (try
        (let [node  (node-at ca ".panel")
              input (node-at ca ".draft")]
          (testing "premise: the first render created the root and painted it"
            (is (some? node))
            (is (some? input))
            (is (= "alpha" (text-at ca ".label"))))

          ;; Host state the caller, not React, put there.
          (set! (.-value input) "half-typed")

          (testing "the second render UPDATES the same root: the same DOM
                    nodes, and the uncontrolled input's value with them"
            (rf.hicasso/render! a
                                [rf.hicasso/frame-root {:id frame-a} [panel {:tag "second"}]]
                                ca)
            (is (= "second" (.getAttribute (node-at ca ".panel") "data-tag"))
                "the update did not reach the page")
            (is (identical? node (node-at ca ".panel"))
                "the update replaced the root's DOM instead of reconciling it")
            (is (identical? input (node-at ca ".draft")))
            (is (= "half-typed" (.-value (node-at ca ".draft")))
                "a second `createRoot` ran: the uncontrolled input lost its
                 host state, which is exactly the loss the create-once half
                 of the handle contract rules out"))

          (testing "`render!` answers nil rather than the handle — the
                    Reagent/UIx trio's shape, and what stops a caller
                    threading a root value it is not meant to hold"
            (is (nil? (rf.hicasso/render! a
                                          [rf.hicasso/frame-root {:id frame-a}
                                           [panel {:tag "third"}]]
                                          ca))))

          (testing "the root is still wired — a dispatch reaches its paint"
            (rf.hicasso.impl.mount/dispatch! frame-a [::relabel "alpha-again"])
            (is (= "alpha-again" (text-at ca ".label")))))

        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W3 — UNMOUNT is idempotent, and a later render mounts afresh
;; ---------------------------------------------------------------------------

(deftest unmount-is-idempotent-and-a-later-render-mounts-afresh
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_  (fresh!)
          ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)]
      (rf.hicasso/render! a [rf.hicasso/frame-root {:id frame-a} [panel {:tag "a"}]] ca)
      (try
        (testing "premise: the root is live and painted"
          (is (some? @a))
          (is (= "alpha" (text-at ca ".label"))))

        (testing "unmount releases the root, returns the handle to inert, and
                  answers nil"
          (is (nil? (rf.hicasso/unmount! a)))
          (is (nil? @a))
          (is (= "" (.-innerHTML ca))
              "React did not empty the container"))

        (testing "a SECOND unmount is a no-op rather than a second host
                  unmount — liveness is the active set's to say, and the
                  handle is already inert"
          (is (nil? (rf.hicasso/unmount! a)))
          (is (nil? @a)))

        (testing "and the container survives: it was the CALLER's node, and a
                  teardown door may not delete a node it did not create"
          (is (true? (.-isConnected ca))))

        (testing "a later render through the same handle MOUNTS AFRESH"
          (rf.hicasso/render! a [rf.hicasso/frame-root {:id frame-a} [panel {:tag "b"}]] ca)
          (is (some? @a))
          (is (= "b" (.getAttribute (node-at ca ".panel") "data-tag")))
          (is (= "alpha" (text-at ca ".label"))
              "the re-mounted root did not read its frame"))

        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W4 — `rf/destroy-adapter!` RELEASES a live Hicasso root
;; ---------------------------------------------------------------------------
;;
;; The behaviour rf2-kuky.59 added rather than moved: before it, Hicasso's
;; `createRoot` sat outside every active-root set and `rf/destroy-adapter!`
;; released no Hicasso root at all. It works because the HICASSO adapter chains
;; the package's drain onto the spine's `dispose-adapter!` — so this row is the
;; one in the file that installs that adapter, and it says so out loud.
;;
;; `exactly once` is the half a count cannot show, so it is read the way Spec
;; 006 states the rule: the drain empties the active set, the handle's own
;; `unmount!` then finds nothing left to do and reaches React's
;; `root.unmount()` no second time, and a `render!` afterwards mounts afresh.

(deftest destroy-adapter-releases-a-live-handle-once-and-a-later-render-mounts-afresh
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)]
      (try
        ;; The fixture seated UIx; this row needs Hicasso's own adapter,
        ;; because the drain is chained onto ITS `dispose-adapter!`.
        (rf/destroy-adapter!)
        (rf/init! rf.hicasso.substrate/adapter)
        (fresh!)
        (rf.hicasso/render! a [rf.hicasso/frame-root {:id frame-a} [panel {:tag "a"}]] ca)

        (testing "premise: a live Hicasso root, painted, under the Hicasso
                  adapter"
          (is (some? @a))
          (is (= "alpha" (text-at ca ".label"))))

        (rf/destroy-adapter!)

        (testing "the adapter teardown released this root: React emptied the
                  container, and the caller's node is still in the document"
          (is (= "" (.-innerHTML ca))
              "`rf/destroy-adapter!` left a live Hicasso root mounted — the
               defect rf2-kuky.59 fixed, in which Hicasso's `createRoot` sat
               outside every active-root set")
          (is (true? (.-isConnected ca))))

        (testing "and the handle's own `unmount!` finds nothing left to do, so
                  the host unmount is reached exactly ONCE per root whichever
                  caller gets there first"
          (is (nil? (rf.hicasso/unmount! a)))
          (is (nil? @a)))

        (testing "a render after the release mounts afresh"
          (rf/init! rf.hicasso.substrate/adapter)
          (rf/make-frame {:id frame-a})
          (rf/with-frame frame-a (rf/dispatch-sync [::seed "beta"]))
          (rf.hicasso/render! a [rf.hicasso/frame-root {:id frame-a} [panel {:tag "b"}]] ca)
          (is (= "b" (.getAttribute (node-at ca ".panel") "data-tag")))
          (is (= "beta" (text-at ca ".label"))))

        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!)
          ;; Hand the page back to the fixture's adapter, whatever this row did.
          (try (rf/destroy-adapter!) (catch :default _ nil)))))))

;; ---------------------------------------------------------------------------
;; W5 — HYDRATE ONCE, then UPDATE; a later `{:hydrate? true}` is IGNORED
;; ---------------------------------------------------------------------------
;;
;; Three claims in one row, because they share one arrangement and the second
;; is only meaningful on the far side of the first.
;;
;; The server-node EXPANDO is the instrument, for `roots-frames-support`'s
;; stated reason: it survives `innerHTML` no better than a comment, so a node
;; still carrying it is the very node the bytes produced rather than a
;; replacement that looks alike. That is the entire difference between ADOPTED
;; and RE-RENDERED, and `innerHTML` cannot see it.

(deftest a-hydrating-first-render-adopts-once-and-later-renders-update
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (let [_         (fresh!)
            html      (rf.hicasso.roots-frames-support/server-html! frame-a [rf.hicasso/frame-provider {:frame frame-a}
                                                 [panel {:tag "server"}]])
            container (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html))
            watch     (rf.hicasso.roots-frames-support/watch-mismatches!)
            a         (rf.hicasso/client-root)]
        (is (str/includes? html "alpha")
            "premise: the server bytes carry the seeded label")
        (rf.hicasso/render! a
                            [rf.hicasso/frame-provider {:frame frame-a}
                             [panel {:tag "server"}]]
                            container
                            {:hydrate? true})
        (-> (rf.hicasso.roots-frames-support/adopted! a)
            (.then (fn [shut?]
                     (testing "the root's OWN adoption window shut — the
                               completion signal a hydrating first render has
                               in place of a flush, and it rides the handle"
                       (is (true? shut?)))
                     (testing "it ADOPTED rather than replaced: the server's
                               own nodes are still the page's nodes"
                       (is (rf.hicasso.roots-frames-support/every-server-node? container ".panel")))
                     (testing "and the framework reported no mismatch"
                       (is (= [] ((:stop! watch)))))

                     (let [node (node-at container ".panel")]
                       (testing "a later render UPDATES the adopted root — no
                                 remount, so the adopted node survives. This
                                 is the wrapper tree `impl.mount/tree` puts
                                 over every POST-hydration render: a bare
                                 tree where the Fragment stood would be a
                                 different top element, and React would
                                 discard every node the adoption established"
                         (rf.hicasso/render! a
                                             [rf.hicasso/frame-provider {:frame frame-a}
                                              [panel {:tag "client"}]]
                                             container)
                         (is (= "client" (.getAttribute (node-at container ".panel") "data-tag")))
                         (is (identical? node (node-at container ".panel"))
                             "the post-hydration update remounted the adopted
                              subtree instead of reconciling it")
                         (is (rf.hicasso.roots-frames-support/every-server-node? container ".panel")))

                       (testing "and `{:hydrate? true}` on a LATER call is
                                 IGNORED rather than hydrating a second time
                                 — the first-call rule. A second `hydrateRoot`
                                 on a live root would build a new root and
                                 throw the adopted nodes away"
                         (rf.hicasso/render! a
                                             [rf.hicasso/frame-provider {:frame frame-a}
                                              [panel {:tag "again"}]]
                                             container
                                             {:hydrate? true})
                         (is (= "again" (.getAttribute (node-at container ".panel") "data-tag")))
                         (is (identical? node (node-at container ".panel"))
                             "a later `{:hydrate? true}` hydrated a second time")
                         (is (rf.hicasso.roots-frames-support/every-server-node? container ".panel"))))))
            (rf.hicasso.roots-frames-support/settle-row! {:row      "the hydrate-once row"
                              :done     done
                              :release! (fn []
                                          ((:stop! watch))
                                          (rf.hicasso/unmount! a)
                                          (detach! container)
                                          (rf.hicasso.impl.collector/reset-runtime!))}))))))

;; ---------------------------------------------------------------------------
;; W6 — `:identifier-prefix` reaches the CONSTRUCTOR
;; ---------------------------------------------------------------------------
;;
;; A pass-through with no default and no coercion, and the reason it is worth a
;; row at the PUBLIC door rather than only at the impl one: it is the option a
;; hydrating root must share with `re-frame.hicasso.server/render`, so a door
;; that dropped it would turn every `useId` in an SSR tree into a mismatch and
;; nothing else on the page would say so.

(deftest identifier-prefix-reaches-create-root
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_  (fresh!)
          ca (rf.hicasso.impl.mount/fresh-container!)
          cb (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)
          b  (rf.hicasso/client-root)]
      (rf.hicasso/render! a [rf.hicasso/frame-provider {:frame frame-a} [id-panel {}]] ca
                          {:identifier-prefix "pfx-a-"})
      (rf.hicasso/render! b [rf.hicasso/frame-provider {:frame frame-a} [id-panel {}]] cb
                          {:identifier-prefix "pfx-b-"})
      (try
        (let [id-a (text-at ca ".probe")
              id-b (text-at cb ".probe")]
          (testing "premise: both roots painted a `useId`"
            (is (seq id-a))
            (is (seq id-b)))
          (testing "each root's ids carry the prefix that root was given —
                    the option reached `createRoot` untouched"
            (is (str/includes? id-a "pfx-a-")
                (str "the first root's id does not carry its prefix; got " (pr-str id-a)))
            (is (str/includes? id-b "pfx-b-")
                (str "the second root's id does not carry its prefix; got " (pr-str id-b))))
          (testing "and the two roots' ids differ, which is the whole point of
                    the option: React numbers `useId` per root from the same
                    start, so two roots on one page collide without it"
            (is (not= id-a id-b))))

        (finally
          (rf.hicasso/unmount! a)
          (rf.hicasso/unmount! b)
          (detach! ca)
          (detach! cb)
          (rf.hicasso.impl.collector/reset-runtime!))))))
