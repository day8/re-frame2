(ns re-frame.hicasso.portal-dom-cljs-test
  "THE PORTAL HELPER (rf2-hic-028; spec SN §4.3).

      [h/portal {:target rack}
       [:button.toast {:on-click [:toast/dismiss]} \"dismiss\"]]

  Markup into a DOM container the application does not own, while the
  subtree stays where it was written in the React tree. Four claims, and
  each of them is one a caller can get wrong:

  1. **The frame and the context are preserved**, and those are two
     claims. An intent written inside the portal fires into the frame of
     the boundary that WROTE the crossing, because the children are
     lowered in that boundary's render window exactly as a `defhost`
     crossing's children are; and a BOUNDARY written inside the portal
     resolves its frame from React context a render later, inside the
     portal, because React keeps a portal in the tree it was written in.
     An intent alone would not witness the second — it carries its
     dispatch with it and would stay green with the context gone.
  2. **Events bubble through the REACT tree, not the DOM tree.** The
     sharp one, and the reason the row below measures the DOM
     relationship as well as the intent: the ancestor that handles the
     click is not a DOM ancestor of the node that was clicked. Nothing
     but a portal produces that shape.
  3. **A changed `:target` is a remount.** React reconciles a portal
     position by its container, so a new container is a new fiber and
     the subtree is destroyed and rebuilt. Measured — a new DOM node and
     a second mount effect — rather than asserted.
  4. **The portal is Client-only, on both arms.** `createPortal` needs a
     container that already exists and a server render has none, so the
     portalled subtree is absent from the response — with a declared
     `:fallback` standing at the tree position, and without one leaving
     it genuinely empty. The canonical matrix
     (`docs/design/hicasso/product/lanes/react-compatibility-notes.md`,
     row *Portal helper*) requires BOTH arms, in terms: *\"A row that
     drives only the fallback arm has not covered the shape most of its
     surfaces will ship in.\"*

  ## The declared policy reads `:render`, and the surface is Client-only

  Both are true, of different things. `:server` describes the head's
  COMPONENT, and that component is safe to run on the server: there it
  renders the caller's `:fallback`, or nothing, and never reaches for a
  container. The matrix row describes the SURFACE, which is Client-only
  because the portalled subtree never reaches the response.
  [[the-declaration-is-an-ordinary-host-crossing]] pins the first; §2
  measures the second.

  ## The mutation witnesses

  Route the portal through the host gate instead of its own
  (`:server :client-only` on the declaration) and
  [[a-declared-fallback-stands-at-the-tree-position-on-the-server]] goes
  red on the fallback missing from the server HTML — the gate would
  swallow the prop before the component that renders it ever ran. Lower
  the children anywhere but the writing window and
  [[an-intent-inside-a-portal-fires-into-the-owners-frame]] goes red on
  the loud `:rf.error/hicasso-intent-outside-boundary`. Replace
  `createPortal` with an ordinary element and
  [[the-subtree-renders-into-the-target-and-not-into-the-root]] goes red
  on the toast appearing in the root container, while
  [[a-click-inside-the-portal-reaches-a-react-ancestor-that-is-no-dom-ancestor]]
  goes red on its DOM-containment control. Drop the target check and
  [[a-target-that-is-not-a-container-is-refused-naming-it]] reads
  React's own opaque *Target container is not a DOM element* instead of
  a refusal that names the value.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The declaration rows and the `renderToString` rows need no
  DOM and run under `:node-test` too."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::portal)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :hicasso.portal/message (fn [db _] (:message db)))

(rf/reg-event :hicasso.portal/seed (fn [_ _] {:db {:message "saved" :log []}}))

(rf/reg-event :hicasso.portal/note
  (fn [{:keys [db]} [_ what]] {:db (update db :log conj what)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration row waits on a real clock, and `cljs.test` hard-errors
     ;; on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "a portal claim needs a real React DOM — " why)))

(defn- fresh! []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.portal/seed]))
  frame-id)

(defn- log [] (:log (rf/app-db-value frame-id)))

(defn- rack!
  "A container the application does not own — the vendor div, the toast
  rack — appended to the body BESIDE the root's own container, so that
  *in the target* and *in the root* are two distinguishable places."
  []
  (let [c (js/document.createElement "div")]
    (set! (.-className c) "rack")
    (.appendChild js/document.body c)
    c))

(defn- drop-rack! [c]
  (when-some [p (.-parentNode c)] (.removeChild p c)))

(defn- q [root sel] (.querySelector root sel))

(defn- click! [node]
  (.click node)
  (mount/settle!)
  (mount/settle!))

;; ---------------------------------------------------------------------------
;; The pages
;; ---------------------------------------------------------------------------

(def ^:private !child-mounts
  "How many times the counted child's mount effect ran. One is a mount;
  two is the destroy-and-rebuild React performs when a portal's container
  changes."
  (atom 0))

(defn- counted [_props]
  (react/useEffect (fn [] (swap! !child-mounts inc) js/undefined) #js [])
  (react/createElement "span" #js {:className "counted"} "in the rack"))

(h/defhost counted-host counted {:server :render})

(h/defview toast-body
  "A BOUNDARY inside the portal, and a second claim from the first one.
  An intent is lowered EAGERLY, in the writing window, and carries its
  frame-locked dispatch with it — so the intent row below would stay
  green even if React context stopped reaching the portalled subtree.
  This body runs a render LATER, inside the portal, and resolves its
  frame the way every boundary does: from the substrate's one React
  context. Context that did not reach here would be
  `:rf.error/no-frame-context` at the shell, before the body ran."
  [_]
  [:p.deep (str (collector/sub [:hicasso.portal/message]))])

(h/defview toast-page
  "THE PAGE, used on both sides of the hydration row and on the client
  rows alike. `:target` arrives as a prop because a server pass has no
  container to name and the remount row needs to hand the same page a
  different one — and because the unadopted branch never reads it, the
  two sides render identically whatever is passed."
  [{:keys [target]}]
  [:div.owner {:on-click [:hicasso.portal/note "ancestor"]}
   [:h1.title (str (collector/sub [:hicasso.portal/message]))]
   [h/portal {:target   target
              :fallback [:div.rack-placeholder "rack loading"]}
    [:button.toast {:on-click [:hicasso.portal/note "toast"]}
     (str (collector/sub [:hicasso.portal/message]))]
    [toast-body {}]]])

(h/defview bare-page
  "No `:fallback` — the DEFAULT arm, and the shape most portals ship in:
  the tree position is genuinely empty in the response."
  [_]
  [:div.owner
   [:h1.title (str (collector/sub [:hicasso.portal/message]))]
   [h/portal {:target nil}
    [:button.toast "THE PORTALLED SUBTREE"]]])

(h/defview counted-page
  [{:keys [target]}]
  [:div.owner
   [h/portal {:target target}
    [counted-host {}]]])

(h/defview bad-target-page
  "A `:target` that is not a container. Overwhelmingly this is a lookup
  that answered nothing — the rack renders later than the portal, or its
  id is spelled two ways."
  [{:keys [target]}]
  [:div.owner
   [h/portal {:target target}
    [:span.toast "never rendered"]]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- server-html [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(def ^:private !caught (atom nil))

(defn- watched-root!
  "Mount `hiccup` under an `h/boundary` whose only job is to hand the
  assertion whatever the subject threw. A render-phase refusal is
  precisely what React routes to a class boundary and nowhere else."
  [hiccup]
  (reset! !caught nil)
  (mount/root! (mount/fresh-container!) frame-id
               [h/boundary {:fallback [:p.escaped "the portal refused"]
                            :on-error (fn [e] (reset! !caught e))}
                hiccup]))

;; ---------------------------------------------------------------------------
;; 1 — the declaration (runs under :node-test too)
;; ---------------------------------------------------------------------------

(deftest the-declaration-is-an-ordinary-host-crossing
  (testing "the helper is a minted host head, so it is legal in hiccup head
            position and inherits the door's whole crossing — children
            lowered under the writing boundary's frame, a declared
            ReactNode slot, props by identity. There is no fifth element
            class here, and no new head kind for a reader or the test kit
            to learn"
    (is (codec/host-head? h/portal)))
  (testing "and its declared policy reads `:render`, which is a claim about
            the head's COMPONENT — safe on the server, where it renders the
            caller's fallback and never reaches for a container. The
            SURFACE is Client-only, and §2 is where that is measured rather
            than declared"
    (is (= :render (codec/host-server h/portal)))))

;; ---------------------------------------------------------------------------
;; 2 — the server render: Client-only, on both arms (no DOM needed)
;; ---------------------------------------------------------------------------

(deftest the-portalled-subtree-is-absent-from-the-server-response
  (fresh!)
  (testing "BARE — the default arm. `createPortal` needs a container that
            already exists and a server render has none, so the subtree
            contributes nothing and the tree position is genuinely empty.
            The sibling markup is asserted alongside so that 'the portal is
            absent' stays distinguishable from 'nothing rendered at all'"
    (let [html (server-html [bare-page {}])]
      (is (re-find #"saved" html)
          (str "the page rendered — the sibling native node is there: " html))
      (is (not (re-find #"THE PORTALLED SUBTREE" html))
          (str "and the portalled subtree is not in the response: " html))
      (is (not (re-find #"class=\"toast\"" html))
          (str "as markup either, not merely as text: " html)))))

(deftest a-declared-fallback-stands-at-the-tree-position-on-the-server
  (fresh!)
  (testing "the other arm. `:fallback` is a declared ReactNode position, so
            hiccup written there is lowered under the frame of the body
            that wrote the crossing — the same conversion the children take
            — and it is what the server writes where the portal sits"
    (let [html (server-html [toast-page {:target nil}])]
      (is (re-find #"rack-placeholder" html)
          (str "the caller's markup is in the server bytes: " html))
      (is (re-find #"rack loading" html)
          (str "with its content: " html))
      (is (not (re-find #"class=\"toast\"" html))
          (str "and still not the portalled subtree — a fallback is a
                placeholder AT THE TREE POSITION, never the portal's own
                content moved back into the response: " html)))))

;; ---------------------------------------------------------------------------
;; 3 — the client: where the subtree lands, and whose frame it carries
;; ---------------------------------------------------------------------------

(deftest the-subtree-renders-into-the-target-and-not-into-the-root
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [rack (rack!)
            h    (mount/root! (mount/fresh-container!) frame-id [toast-page {:target rack}])]
        (try
          (testing "a fresh `createRoot` mount consults no server snapshot, so
                    the portal is there on the first pass — asserted on the
                    line after `root!` returns, which is inside its own
                    flushSync, and therefore also the claim that the fallback
                    never flashed"
            (is (some? (q rack ".toast"))
                "the portalled subtree is in the TARGET container")
            (is (= "saved" (.-textContent (q rack ".toast")))
                "carrying the value its body read from the frame")
            (is (nil? (q (:container h) ".toast"))
                "and nowhere in the root's own container")
            (is (nil? (q (:container h) ".rack-placeholder"))
                "with no placeholder pass — a fallback is for a server's
                 markup, not for a client that has none"))
          (testing "while the rest of the view is exactly where it was written"
            (is (some? (q (:container h) ".title"))
                "the sibling markup stayed in the root container"))
          (testing "REACT CONTEXT REACHES THROUGH THE PORTAL, which is the
                    half of *frame/context preserved* an intent cannot
                    witness: an intent is lowered eagerly in the writing
                    window and carries its dispatch with it, while a boundary
                    body runs a render later, inside the portal, and resolves
                    its frame from the substrate's one React context. React
                    keeps a portal in the tree it was written in, so the
                    provider above the root is above this body too"
            (is (some? (q rack ".deep"))
                "the boundary inside the portal rendered rather than
                 refusing with :rf.error/no-frame-context")
            (is (= "saved" (.-textContent (q rack ".deep")))
                "and its `h/sub` read resolved against the owner's frame"))
          (testing "and teardown takes the portalled DOM with it. The nodes
                    live in somebody else's container, so nothing but React's
                    own cleanup can remove them — a portal that leaked here
                    would leave a page that grows one toast rack per mount"
            (mount/unmount! h)
            (mount/settle!)
            (is (nil? (q rack ".toast"))
                "the target container is empty after unmount"))
          (finally
            (mount/release! h)
            (drop-rack! rack)))))))

(deftest an-intent-inside-a-portal-fires-into-the-owners-frame
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [rack (rack!)
            h    (mount/root! (mount/fresh-container!) frame-id [toast-page {:target rack}])]
        (try
          (testing "THE FRAME IS PRESERVED. The children are lowered in the
                    render window of the boundary that wrote the crossing, so
                    an intent inside the portal closes over that boundary's
                    frame-locked dispatch — exactly as an intent in an
                    ordinary child does. A subtree lowered anywhere else
                    would raise `:rf.error/hicasso-intent-outside-boundary`
                    instead of dispatching"
            (click! (q rack ".toast"))
            (is (some #{"toast"} (log))
                (str "the intent reached the owner's frame: " (pr-str (log)))))
          (finally
            (mount/release! h)
            (drop-rack! rack)))))))

(deftest a-click-inside-the-portal-reaches-a-react-ancestor-that-is-no-dom-ancestor
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [rack (rack!)
            h    (mount/root! (mount/fresh-container!) frame-id [toast-page {:target rack}])]
        (try
          (let [owner (q (:container h) ".owner")
                toast (q rack ".toast")]
            (testing "the control first, because without it the row below is
                      satisfied by an ordinary child: the clicked node is NOT
                      a DOM descendant of the ancestor that handles it"
              (is (not (.contains owner toast))
                  "the toast's DOM node is outside the owner's subtree")
              (is (.contains js/document.body toast)
                  "and it really is on the page, in the rack"))
            (testing "EVENTS BUBBLE THROUGH THE REACT TREE. React dispatches
                      along the fiber path rather than the DOM one, so the
                      `:on-click` written on the portal's hiccup ancestor sees
                      the click although no DOM relationship connects them"
              (click! toast)
              (is (some #{"ancestor"} (log))
                  (str "the React ancestor's intent fired: " (pr-str (log))))
              (is (some #{"toast"} (log))
                  (str "and so did the clicked node's own: " (pr-str (log))))))
          (finally
            (mount/release! h)
            (drop-rack! rack)))))))

(deftest a-changed-target-is-a-remount
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (reset! !child-mounts 0)
      (let [rack-a (rack!)
            rack-b (rack!)
            h      (mount/root! (mount/fresh-container!) frame-id
                                [counted-page {:target rack-a}])]
        (try
          (mount/settle!)
          (let [first-node (q rack-a ".counted")]
            (testing "one mount, in the first container"
              (is (some? first-node))
              (is (= 1 @!child-mounts) (str "read " @!child-mounts)))
            (testing "**A CHANGED TARGET IS A REMOUNT**, and it is React's law
                      rather than this helper's: React compares a portal
                      position's container, so a different one is a different
                      fiber and the whole subtree is destroyed and rebuilt with
                      its state and its effects. Keep the target stable rather
                      than computing one per render"
              (mount/render! h [counted-page {:target rack-b}])
              (mount/settle!)
              (mount/settle!)
              (is (nil? (q rack-a ".counted"))
                  "the subtree left the first container")
              (is (some? (q rack-b ".counted"))
                  "and is in the second")
              (is (not (identical? first-node (q rack-b ".counted")))
                  "as a NEW DOM node — the old one was destroyed, not moved")
              (is (= 2 @!child-mounts)
                  (str "TWO MOUNTS: the subtree was rebuilt, and a caller who
                        computes a fresh target per render pays this on every
                        one. Read " @!child-mounts))))
          (finally
            (mount/release! h)
            (drop-rack! rack-a)
            (drop-rack! rack-b)))))))

;; ---------------------------------------------------------------------------
;; 4 — the refusal
;; ---------------------------------------------------------------------------

(deftest a-target-that-is-not-a-container-is-refused-naming-it
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (testing "React's own answer here is *Target container is not a DOM
                element*, which names neither the value, nor the option, nor
                anything the author wrote. This one carries the offending
                target and a recovery"
        (let [h (watched-root! [bad-target-page {:target nil}])]
          (try
            (let [data (ex-data @!caught)]
              (is (= :rf.error/hicasso-portal-no-target (:rf.error/id data)))
              (is (contains? data :target) "the offending value is carried")
              (is (nil? (:target data)) "and it is the one that was written")
              (is (= :give-the-portal-a-dom-container-that-exists (:recovery data))
                  "with a concrete recovery")
              (is (some? (:where data)) "the fn that refused (hic-007's shape)")
              (is (string? (:reason data)) "and a human sentence")
              (is (some? (q (:container h) ".escaped"))
                  "the refusal reached the watching boundary as a render-phase
                   throw, rather than being swallowed into a hole"))
            (finally (mount/release! h)))))
      (testing "and a target of the wrong KIND is the same fault with the same
                recovery — `createPortal` appends to a container, so anything
                without a node type is not one. ONE refusal, because there is
                one thing to do about it"
        (let [h (watched-root! [bad-target-page {:target "#rack"}])]
          (try
            (is (= :rf.error/hicasso-portal-no-target
                   (:rf.error/id (ex-data @!caught)))
                "a selector string is not a container — the portal does not
                 query the document for you")
            (is (= "#rack" (:target (ex-data @!caught))))
            (finally (mount/release! h))))))))

;; ---------------------------------------------------------------------------
;; 5 — hydration: nothing to reconcile, and no claim on absent bytes
;; ---------------------------------------------------------------------------

(defn- watch-errors!
  "Everything React has to say about a hydration, from all three channels
  it uses — the mismatch diff lands on `console.error`, the recovery on
  `onRecoverableError`, and a throw during a commit is routed to
  `reportError`, where `cljs.test` cannot see it and a row would read 0
  failures over a live exception."
  []
  (let [seen     (atom [])
        original (.-error js/console)
        on-error (fn [e] (swap! seen conj (str "window: " (.-message e))))]
    (set! (.-error js/console)
          (fn [& args] (swap! seen conj (str "console.error: " (pr-str (vec args))))))
    (.addEventListener js/window "error" on-error)
    {:seen    seen
     :restore (fn []
                (set! (.-error js/console) original)
                (.removeEventListener js/window "error" on-error))}))

(deftest a-fallback-hydrates-as-the-placeholder-and-the-portal-arrives-at-adoption
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [rack      (rack!)
              ;; The server pass has no container to name and does not need
              ;; one: the unadopted branch never reads `:target`, so the two
              ;; sides of the hydration are the SAME page rendering the same
              ;; markup, which is the only honest way to take this claim.
              html      (server-html [toast-page {:target nil}])
              container (mount/fresh-container!)
              {:keys [seen restore]} (watch-errors!)]
          (set! (.-innerHTML container) html)
          (let [root (react-dom-client/hydrateRoot
                       container
                       (mount/provider frame-id
                                       (codec/root-element frame-id [toast-page {:target rack}]))
                       #js {:onRecoverableError
                            (fn [err _info]
                              (swap! seen conj (str "onRecoverableError: " (ex-message err))))})]
            (js/setTimeout
              (fn []
                (try
                  (is (re-find #"rack-placeholder" html)
                      (str "the markup hydrated FROM carries the fallback at the
                            tree position — the row's own restatement of the
                            policy, so a portal that started emitting server
                            bytes is red HERE and not only in §2: " html))
                  (is (empty? @seen)
                      (str "REACT FOUND NOTHING TO RECONCILE. The client's first
                            pass rendered what the server did, because both read
                            the same unadopted answer — so there was no mismatch
                            to repair, and no hydration claim was made about
                            bytes nobody wrote: " (pr-str @seen)))
                  (is (nil? (q container ".rack-placeholder"))
                      "the placeholder is GONE after adoption")
                  (is (some? (q rack ".toast"))
                      "and the portal is mounted, in the target container")
                  (finally
                    (restore)
                    (.unmount root)
                    (when-some [p (.-parentNode container)] (.removeChild p container))
                    (drop-rack! rack)
                    (collector/reset-runtime!)
                    (done))))
              150)))))))
