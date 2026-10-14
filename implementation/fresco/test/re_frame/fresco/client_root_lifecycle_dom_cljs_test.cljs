(ns re-frame.fresco.client-root-lifecycle-dom-cljs-test
  "THE CLIENT-ROOT GRAMMAR — Spec 006 §The client root, Fresco's
  realisation.

  What is under test is the SHAPE all four React view adapters share:
  an inert `h/client-root` handle, one `h/render!` whose FIRST call
  creates or adopts and whose later calls update, and an idempotent
  `h/unmount!`. The rows below mirror
  `implementation/adapters/reagent/test/re_frame/adapter_client_root_{,dom_}cljs_test.cljs`
  claim for claim, so a divergence between Fresco's own root path and the
  spine's shows up as a red here rather than as a difference nobody reads.

  ## Why Fresco needs its own copy of those rows

  Fresco does NOT ride `spine/make-client-root-fns`. Its roots carry
  ownership the shared factory has no place for — a `flushSync` commit on
  every update, a per-root adoption window, a per-root recoverable-error
  reporter, and the stable wrapper tree `impl.mount/tree` puts over every
  post-hydration render so the client and
  `re-frame.fresco.server/render` agree about `useId` — and it may not
  `:require` the spine at all, because that would put core's React-hook
  machinery into every Fresco bundle (the optional-module invariant
  `fresco/scripts/check_optional_module_reachability.py` guards). So the
  semantics are pinned by TEST rather than by a shared call, and this file
  is where.

  ## Which adapter, and why the teardown rows install more than one

  A Fresco root is created by Fresco's own door whatever adapter is
  installed — `h/render!` never routes through the substrate contract's
  `render` slot — so a Fresco application may perfectly well install UIx
  or Reagent. `rf/destroy-adapter!` reaches the package's root drain
  through core's `:fresco/drain-client-roots!` late-bind hook, which is
  the PROCESS teardown boundary and therefore adapter-independent. W7
  reads that guarantee under each in turn — Fresco's own adapter and UIx;
  the rest of the file installs UIx, as the rest of this package's suites
  do.

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
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.fresco.substrate :as rf.fresco.substrate]
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
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app
;; ---------------------------------------------------------------------------

(rf.fresco/defview panel
  "One read, one tag the caller supplies, and an UNCONTROLLED input.

  The input is the state witness: it names no `:value`, so React never
  writes to it and whatever the DOM holds is host state React is merely
  preserving. A reconcile keeps it; a second `createRoot` cannot."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag}
   [:span.label (rf.fresco/sub label-q)]
   [:input.draft {:type "text"}]])

(rf.fresco/defview ensuring-shell
  "A view whose body returns the ENSURE boundary. A root door lowers
  `[ensuring-shell]` to an element and React runs this body later, during
  the render, so the `frame-root` it returns is outside the door's walk."
  [{:keys [tag]}]
  [rf.fresco/frame-root {:id frame-a} [panel {:tag tag}]])

;; A `useId` probe, rendered as TEXT for `identifier_prefix_ssr_dom_cljs_test`'s
;; reason: an id parked in an attribute diverges in silence, where text is what
;; React's own hydration contract covers.
(defn- id-probe
  [_props]
  (react/createElement "b" #js {:className "probe"} (react/useId)))

(rf.fresco/defhost id-host id-probe {:server :render})

(rf.fresco/defview id-panel
  [_]
  [:div.panel [id-host {}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip! [why] (rf.fresco.roots-frames-support/skip! why))

(defn- fresh!
  "One frame, seeded, and an empty runtime."
  []
  (rf.fresco.roots-frames-support/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/with-frame frame-a (rf/dispatch-sync [::seed "alpha"]))
  (rf.fresco.impl.collector/reset-runtime!)
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
;; W2 — CREATE ONCE, UPDATE LATER
;; ---------------------------------------------------------------------------
;;
;; The whole reason one verb is enough. The reading is not the markup: adopted,
;; re-rendered and re-created markup all look alike in `innerHTML`. It is node
;; IDENTITY plus an uncontrolled input's value — host state React preserves
;; across a reconcile and cannot preserve across a second `createRoot`.

(deftest the-first-render-creates-and-every-later-render-updates
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_  (fresh!)
          ca (rf.fresco.impl.mount/fresh-container!)
          a  (rf.fresco/client-root)]
      (rf.fresco/render! a [rf.fresco/frame-root {:id frame-a} [panel {:tag "first"}]] ca)
      (try
        (let [node  (node-at ca ".panel")
              input (node-at ca ".draft")]
          (testing "premise: the first render created the root and painted it"
            (is (= "alpha" (text-at ca ".label"))))

          ;; Host state the caller, not React, put there.
          (set! (.-value input) "half-typed")

          (testing "the second render UPDATES the same root: the same DOM
                    nodes, and the uncontrolled input's value with them"
            (rf.fresco/render! a
                                [rf.fresco/frame-root {:id frame-a} [panel {:tag "second"}]]
                                ca)
            (is (= "second" (.getAttribute (node-at ca ".panel") "data-tag"))
                "the update did not reach the page")
            (is (identical? node (node-at ca ".panel"))
                "the update replaced the root's DOM instead of reconciling it")
            (is (= "half-typed" (.-value (node-at ca ".draft")))
                "a second `createRoot` ran: the uncontrolled input lost its
                 host state, which is exactly the loss the create-once half
                 of the handle contract rules out"))

          (testing "`render!` answers nil rather than the handle — the
                    Reagent/UIx trio's shape, and what stops a caller
                    threading a root value it is not meant to hold"
            (is (nil? (rf.fresco/render! a
                                          [rf.fresco/frame-root {:id frame-a}
                                           [panel {:tag "third"}]]
                                          ca))))

          (testing "the root is still wired — a dispatch reaches its paint"
            (rf.fresco.impl.mount/dispatch! frame-a [::relabel "alpha-again"])
            (is (= "alpha-again" (text-at ca ".label")))))

        (finally
          (rf.fresco/unmount! a)
          (detach! ca)
          (rf.fresco.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W3 — UNMOUNT is idempotent, and a later render mounts afresh
;; ---------------------------------------------------------------------------

(deftest unmount-is-idempotent-and-a-later-render-mounts-afresh
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_  (fresh!)
          ca (rf.fresco.impl.mount/fresh-container!)
          a  (rf.fresco/client-root)]
      (rf.fresco/render! a [rf.fresco/frame-root {:id frame-a} [panel {:tag "a"}]] ca)
      (try
        (testing "premise: the root is live and painted"
          (is (some? @a))
          (is (= "alpha" (text-at ca ".label"))))

        (testing "unmount releases the root, returns the handle to inert, and
                  answers nil"
          (is (nil? (rf.fresco/unmount! a)))
          (is (nil? @a))
          (is (= "" (.-innerHTML ca))
              "React did not empty the container"))

        (testing "a SECOND unmount is a no-op rather than a second host
                  unmount — liveness is the active set's to say, and the
                  handle is already inert"
          (is (nil? (rf.fresco/unmount! a)))
          (is (nil? @a)))

        (testing "and the container survives: it was the CALLER's node, and a
                  teardown door may not delete a node it did not create"
          (is (true? (.-isConnected ca))))

        (testing "a later render through the same handle MOUNTS AFRESH"
          (rf.fresco/render! a [rf.fresco/frame-root {:id frame-a} [panel {:tag "b"}]] ca)
          (is (some? @a))
          (is (= "b" (.getAttribute (node-at ca ".panel") "data-tag")))
          (is (= "alpha" (text-at ca ".label"))
              "the re-mounted root did not read its frame"))

        (finally
          (rf.fresco/unmount! a)
          (detach! ca)
          (rf.fresco.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W4 — a hydrating first render REFUSES `h/frame-root` in its own tree
;; ---------------------------------------------------------------------------
;;
;; `frame-root`'s first render is empty: the frame is ensured at commit and the
;; children arrive on a second pass. A hydrating root built over it hands React
;; nothing to claim the server's nodes with, and React does not report an
;; unclaimed node directly under a root, so the server copy would stay and the
;; client tree would mount beside it: the page twice, and no mismatch. The door
;; refuses while it lowers the tree, before `hydrateRoot` runs. The reading that
;; matters is the container once any adoption could have finished, which is
;; when a second copy would land: one copy, still the server's own nodes.

(deftest a-hydrating-first-render-refuses-frame-root
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (let [_         (fresh!)
            html      (rf.fresco.roots-frames-support/server-html! frame-a [rf.fresco/frame-provider {:frame frame-a}
                                                 [panel {:tag "server"}]])
            container (rf.fresco.roots-frames-support/stamp-server-nodes! (rf.fresco.roots-frames-support/server-dom! html))
            a         (rf.fresco/client-root)
            refusal   (try (rf.fresco/render! a
                                              [rf.fresco/frame-root {:id frame-a}
                                               [panel {:tag "server"}]]
                                              container
                                              {:hydrate? true})
                           nil
                           (catch :default e (ex-data e)))]
        (testing "the door refuses with the error that names the supported recipe"
          (is (= :rf.error/fresco-frame-root-adopting (:rf.error/id refusal)))
          (is (= frame-a (:frame refusal))))
        ;; A refused door made no root, so there is only the clock to wait on;
        ;; an accepted one is waited out to its own adoption.
        (-> (if (some? @a)
              (rf.fresco.roots-frames-support/adopted! a)
              (js/Promise. (fn [resolve] (js/setTimeout #(resolve true) 50))))
            (.then (fn [_]
                     (testing "the container keeps exactly one copy of the page,
                               and it is the server's own nodes"
                       (is (= 1 (.-length (.querySelectorAll container ".panel"))))
                       (is (rf.fresco.roots-frames-support/every-server-node? container ".panel")))
                     (testing "and the handle holds no root"
                       (is (nil? @a)))))
            (rf.fresco.roots-frames-support/settle-row! {:row      "the frame-root refusal row"
                              :done     done
                              :release! (fn []
                                          (rf.fresco/unmount! a)
                                          (detach! container)
                                          (rf.fresco.impl.collector/reset-runtime!))}))))))

;; ---------------------------------------------------------------------------
;; W4b — a `frame-root` a VIEW returns is refused in the hydration pass
;; ---------------------------------------------------------------------------
;;
;; W4's refusal runs while the door lowers its own tree, and a frame-root a
;; view returns is not in that tree: the walk lowers `[ensuring-shell]` to an
;; element, and React runs the body during the hydration pass. So the head
;; refuses there. React answers a throw in a root's hydration pass by
;; rendering the root again on the client over a cleared container, so the
;; reading differs from W4's in one respect: the server's nodes are REPLACED.
;; There is still exactly one copy of the page, and it is live. The refusal
;; reaches the page as the cause of React's recovery error. W5 is the
;; control: the same server bytes adopted under `frame-provider` keep the
;; server's own nodes.

(defn- refusal-of
  "The refusal's data, from a reported error or from its cause — React
  reports a hydration-pass throw as the cause of its own recovery error."
  [e]
  (when (some? e)
    (or (ex-data e) (ex-data (.-cause ^js e)))))

(deftest a-hydrating-first-render-refuses-a-frame-root-a-view-returns
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (let [_         (fresh!)
            html      (rf.fresco.roots-frames-support/server-html! frame-a [rf.fresco/frame-provider {:frame frame-a}
                                                 [panel {:tag "server"}]])
            container (rf.fresco.roots-frames-support/stamp-server-nodes! (rf.fresco.roots-frames-support/server-dom! html))
            reported  (atom [])
            ;; The root's recoverable-error reporting reaches the window. The
            ;; row asserts on what arrives there, so it marks the event handled.
            on-error  (fn [^js e]
                        (swap! reported conj (.-error e))
                        (.preventDefault e))
            _         (.addEventListener js/window "error" on-error)
            a         (rf.fresco/client-root)]
        ;; No refusal can arrive here: the door returns before the tree is
        ;; adopted, and the walk it runs never reaches the view's body.
        (rf.fresco/render! a
                            [rf.fresco/frame-provider {:frame frame-a}
                             [ensuring-shell {:tag "server"}]]
                            container
                            {:hydrate? true})
        (-> (rf.fresco.roots-frames-support/adopted! a)
            (.then (fn [shut?]
                     (testing "premise: the root finished adopting"
                       (is (true? shut?)))
                     (testing "the refusal is reported, with the error that names
                               the supported recipe"
                       (let [refusals (keep refusal-of @reported)]
                         (is (= [:rf.error/fresco-frame-root-adopting]
                                (mapv :rf.error/id refusals))
                             (str "reported: " (pr-str (mapv str @reported))))
                         (is (= frame-a (:frame (first refusals))))))
                     (testing "the container holds exactly one copy of the page,
                               rendered by the client in place of the server's"
                       (is (= 1 (.-length (.querySelectorAll container ".panel")))
                           (str "DOM: " (.-innerHTML container)))
                       (is (not (rf.fresco.roots-frames-support/server-node?
                                  (node-at container ".panel")))))
                     (testing "and that copy is live — a dispatch reaches its paint"
                       (is (= "alpha" (text-at container ".label")))
                       (rf.fresco.impl.mount/dispatch! frame-a [::relabel "beta"])
                       (is (= "beta" (text-at container ".label"))))))
            (rf.fresco.roots-frames-support/settle-row! {:row      "the view-returned frame-root refusal row"
                              :done     done
                              :release! (fn []
                                          (.removeEventListener js/window "error" on-error)
                                          (rf.fresco/unmount! a)
                                          (detach! container)
                                          (rf.fresco.impl.collector/reset-runtime!))}))))))

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
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (let [_         (fresh!)
            html      (rf.fresco.roots-frames-support/server-html! frame-a [rf.fresco/frame-provider {:frame frame-a}
                                                 [panel {:tag "server"}]])
            container (rf.fresco.roots-frames-support/stamp-server-nodes! (rf.fresco.roots-frames-support/server-dom! html))
            watch     (rf.fresco.roots-frames-support/watch-mismatches!)
            a         (rf.fresco/client-root)]
        (is (str/includes? html "alpha")
            "premise: the server bytes carry the seeded label")
        (rf.fresco/render! a
                            [rf.fresco/frame-provider {:frame frame-a}
                             [panel {:tag "server"}]]
                            container
                            {:hydrate? true})
        (-> (rf.fresco.roots-frames-support/adopted! a)
            (.then (fn [shut?]
                     (testing "the root's OWN adoption window shut — the
                               completion signal a hydrating first render has
                               in place of a flush, and it rides the handle"
                       (is (true? shut?)))
                     (testing "it ADOPTED rather than replaced: the server's
                               own nodes are still the page's nodes"
                       (is (rf.fresco.roots-frames-support/every-server-node? container ".panel")))
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
                         (rf.fresco/render! a
                                             [rf.fresco/frame-provider {:frame frame-a}
                                              [panel {:tag "client"}]]
                                             container)
                         (is (= "client" (.getAttribute (node-at container ".panel") "data-tag")))
                         (is (identical? node (node-at container ".panel"))
                             "the post-hydration update remounted the adopted
                              subtree instead of reconciling it")
                         (is (rf.fresco.roots-frames-support/every-server-node? container ".panel")))

                       (testing "and `{:hydrate? true}` on a LATER call is
                                 IGNORED rather than hydrating a second time
                                 — the first-call rule. A second `hydrateRoot`
                                 on a live root would build a new root and
                                 throw the adopted nodes away"
                         (rf.fresco/render! a
                                             [rf.fresco/frame-provider {:frame frame-a}
                                              [panel {:tag "again"}]]
                                             container
                                             {:hydrate? true})
                         (is (= "again" (.getAttribute (node-at container ".panel") "data-tag")))
                         (is (identical? node (node-at container ".panel"))
                             "a later `{:hydrate? true}` hydrated a second time")
                         (is (rf.fresco.roots-frames-support/every-server-node? container ".panel"))))))
            (rf.fresco.roots-frames-support/settle-row! {:row      "the hydrate-once row"
                              :done     done
                              :release! (fn []
                                          ((:stop! watch))
                                          (rf.fresco/unmount! a)
                                          (detach! container)
                                          (rf.fresco.impl.collector/reset-runtime!))}))))))

;; ---------------------------------------------------------------------------
;; W6 — `:identifier-prefix` reaches the CONSTRUCTOR
;; ---------------------------------------------------------------------------
;;
;; A pass-through with no default and no coercion, and the reason it is worth a
;; row at the PUBLIC door rather than only at the impl one: it is the option a
;; hydrating root must share with `re-frame.fresco.server/render`, so a door
;; that dropped it would turn every `useId` in an SSR tree into a mismatch and
;; nothing else on the page would say so.

(deftest identifier-prefix-reaches-create-root
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_  (fresh!)
          ca (rf.fresco.impl.mount/fresh-container!)
          cb (rf.fresco.impl.mount/fresh-container!)
          a  (rf.fresco/client-root)
          b  (rf.fresco/client-root)]
      (rf.fresco/render! a [rf.fresco/frame-provider {:frame frame-a} [id-panel {}]] ca
                          {:identifier-prefix "pfx-a-"})
      (rf.fresco/render! b [rf.fresco/frame-provider {:frame frame-a} [id-panel {}]] cb
                          {:identifier-prefix "pfx-b-"})
      (try
        (let [id-a (text-at ca ".probe")
              id-b (text-at cb ".probe")]
          (testing "each root's ids carry the prefix that root was given —
                    the option reached `createRoot` untouched"
            (is (str/includes? id-a "pfx-a-")
                (str "the first root's id does not carry its prefix; got " (pr-str id-a)))
            (is (str/includes? id-b "pfx-b-")
                (str "the second root's id does not carry its prefix; got " (pr-str id-b)))))

        (finally
          (rf.fresco/unmount! a)
          (rf.fresco/unmount! b)
          (detach! ca)
          (detach! cb)
          (rf.fresco.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W7 — `rf/destroy-adapter!` RELEASES a live Fresco root, under every adapter
;; ---------------------------------------------------------------------------
;;
;; A Fresco `createRoot` outside every active-root set would leave
;; `rf/destroy-adapter!` releasing no Fresco root at all. The first pass reads
;; the guarantee with FRESCO's own adapter installed — the composition an
;; all-Fresco application has.
;;
;; `exactly once` is the half a count cannot show, so it is read the way Spec
;; 006 states the rule: the drain empties the active set, the handle's own
;; `unmount!` then finds nothing left to do and reaches React's
;; `root.unmount()` no second time, and a `render!` afterwards mounts afresh.
;;
;; The UIx pass is the composition a Fresco-only row cannot cover:
;; `h/render!` reaches `createRoot` through
;; `re-frame.fresco.impl.mount` whatever adapter is installed, so a Fresco
;; root is the PACKAGE's rather than any adapter's. Fresco over UIx or over
;; Reagent is supported use, not malformed input — the HMR testbed installs
;; UIx, Story's own-root control installs Reagent, and the migration skill
;; deliberately preserves an app's existing Reagent adapter.
;;
;; The drain lives at the process teardown boundary — core's
;; `:fresco/drain-client-roots!` late-bind hook, invoked by
;; `re-frame.substrate.adapter/dispose-adapter!` before the installed
;; adapter's own disposer — so the guarantee carries no adapter qualifier,
;; which is what the public `h/client-root` / `h/unmount!` docs promise. A
;; drain hung off the FRESCO adapter's own `dispose-adapter!` would fail
;; here: `rf/destroy-adapter!` under any other adapter would invoke that
;; adapter's disposer alone, the Fresco Root would stay mounted with its
;; `:live?` closure still true, and a `render!` through the retained handle
;; would UPDATE that never-released Root instead of mounting afresh.
;;
;; The reading is NODE IDENTITY, for W2's reason: a leaked Root's update and a
;; fresh Root's first render both paint the new tag, and only the identity of
;; the `.panel` node separates them. `innerHTML` is read as well, because an
;; unreleased Root leaves the container painted where a released one empties
;; it.

(defn- destroy-adapter-releases-fresco-roots!
  "W7's body for one adapter. `label` names it in the failure messages;
  `adapter` is the spec map to install.

  Two handles, because the two claims cannot share one: `a` takes the
  NO-INTERVENING-UNMOUNT path (destroy, re-init, render straight through the
  retained handle), and `b` is left untouched so its own `h/unmount!` can
  show the host unmount is reached exactly once per root whichever caller
  gets there first."
  [label adapter]
  (let [ca (rf.fresco.impl.mount/fresh-container!)
        cb (rf.fresco.impl.mount/fresh-container!)
        a  (rf.fresco/client-root)
        b  (rf.fresco/client-root)]
    (try
      ;; The fixture seated UIx; seat the adapter this pass is about.
      (rf/destroy-adapter!)
      (rf/init! adapter)
      (fresh!)
      (rf.fresco/render! a [rf.fresco/frame-root {:id frame-a} [panel {:tag "a"}]] ca)
      (rf.fresco/render! b [rf.fresco/frame-root {:id frame-a} [panel {:tag "b"}]] cb)

      (let [node-a (node-at ca ".panel")]
        (testing (str "premise: " label " is the installed adapter, and two
                       Fresco roots are live and painted under it")
          (is (= (:kind adapter) (:kind (rf/current-adapter)))
              (str "this pass did not seat " label))
          (is (some? node-a))
          (is (= "alpha" (text-at ca ".label")))
          (is (= "alpha" (text-at cb ".label"))))

        (rf/destroy-adapter!)

        (testing (str "adapter teardown released BOTH Fresco roots with "
                      label " installed: React emptied each container, and the
                      caller's nodes are still in the document")
          (is (= "" (.-innerHTML ca))
              (str "`rf/destroy-adapter!` left a live Fresco root mounted
                    under " label " — the Fresco root is the PACKAGE's, so
                    the drain may not hang off one adapter's disposer"))
          (is (= "" (.-innerHTML cb))
              (str "the drain stopped at the first root under " label))
          (is (true? (.-isConnected ca)))
          (is (true? (.-isConnected cb))))

        (rf/init! adapter)
        (rf/make-frame {:id frame-a})
        (rf/with-frame frame-a (rf/dispatch-sync [::seed "beta"]))

        (testing "the retained handle mounts AFRESH with NO intervening
                  `h/unmount!` — a leaked Root would have taken the update
                  path and kept its own DOM"
          (rf.fresco/render! a [rf.fresco/frame-root {:id frame-a} [panel {:tag "a2"}]] ca)
          (is (= "a2" (.getAttribute (node-at ca ".panel") "data-tag")))
          (is (not (identical? node-a (node-at ca ".panel")))
              (str "the render after `rf/destroy-adapter!` UPDATED the root
                    the teardown should have released under " label
                   " — the same node came back, which only a surviving Root
                     can produce"))
          (is (= "beta" (text-at ca ".label"))
              "the re-mounted root did not read its frame"))

        (testing "and the untouched handle's own `unmount!` finds nothing left
                  to do, so the host unmount is reached exactly ONCE per root
                  whichever caller gets there first"
          (is (nil? (rf.fresco/unmount! b)))
          (is (nil? @b))
          (is (= "" (.-innerHTML cb)))))

      (finally
        (rf.fresco/unmount! a)
        (rf.fresco/unmount! b)
        (detach! ca)
        (detach! cb)
        (rf.fresco.impl.collector/reset-runtime!)
        ;; Hand the page back to the fixture's adapter, whatever this pass did.
        (try (rf/destroy-adapter!) (catch :default _ nil))))))

(deftest destroy-adapter-releases-fresco-roots-under-every-adapter
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (destroy-adapter-releases-fresco-roots! "the Fresco adapter" rf.fresco.substrate/adapter)
      (destroy-adapter-releases-fresco-roots! "the UIx adapter" rf.adapter.uix/adapter))))
