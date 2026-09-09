(ns fresco.login.core
  "A login flow, rendered through Fresco — re-frame2's own native view layer.

   The same feature runs on Reagent (`login.core`) and on UIx
   (`uix.login.core`), and that is the point of this file. All three
   `:require` the identical substrate-free
   [`login.model`](../../../core/login/model.cljc) namespace — the ONE owner of
   the `auth.login` schemas, demo fx, five-state machine, form-slice events and
   named subs — and each adds only its own view layer and boot. Diff this
   `core.cljs` against either twin and the diff is the view layer, whole and
   entire.

   What changes here is the notation, and it changes more than on the UIx side:
   Fresco interprets Hiccup at runtime, so a view is an `rf.fresco/defview` boundary
   whose body reads subscriptions with `rf.fresco/sub` and states its handlers as DATA.
   `{:on-change [:auth.login/edit-field :email ::rf.fresco/value]}` IS the handler —
   there is no callback to write, and `::rf.fresco/value` substitutes the event
   target's value at dispatch time. Reagent reaches for `reg-view` and an
   injected `dispatch`; UIx reaches for `defui` plus the `use-sub` hook.
   The subscription vectors and the event ids do not change one character
   across the three.

   Two handlers here are `rf.fresco/event` callbacks rather than intent vectors,
   and both for a stated reason — see them below. `rf.fresco/event` is Fresco's one
   callback form: at an `on-*` position a returned vector is dispatched and any
   other return is ignored.

   For the view layer itself, see the Fresco guide at
   docs/core/fresco/ (start at `00-installation.md`); for the substrate
   boundary in general, docs/core/how-to/use-uix-or-slim.md.

   Examples are test-free: login's behaviour is covered by the substrate
   contract suite (`npm run test:cljs`) and the framework gates, not by a test
   alongside this file."
  (:require [re-frame.core :as rf]
            ;; The substrate-free model owner (examples/core/login/model.cljc).
            ;; Requiring it registers every shared `auth.login` schema, fx,
            ;; machine, event and sub, and hands us `model/frame-config` for the
            ;; boot below. It names no substrate — the Fresco code lives only
            ;; here.
            [login.model :as model]
            [re-frame.fresco :as rf.fresco]
            ;; The client half of an SSR route: `rf.ssr/hydrate!` installs the
            ;; server's app-db from `__rf_payload` BEFORE the first client
            ;; render. On a client-only load it finds no payload and is a
            ;; no-op, which is what lets ONE `run` serve both pages.
            [re-frame.ssr :as rf.ssr]
            ;; Fresco is a VIEW layer, not a substrate: it owns Hiccup
            ;; interpretation and the render boundary, while the reactive
            ;; container app-db lives in comes from an adapter. Fresco ships
            ;; its own, in this namespace, so the choice costs no extra
            ;; coordinate (docs/core/fresco/00-installation.md §Fresco needs a
            ;; substrate adapter).
            [re-frame.fresco.substrate :as rf.fresco.substrate]))

;; ============================================================================
;; THE SSR COORDINATES  (shared by the client boot and the server bundle)
;; ============================================================================
;;
;; Three constants both halves of the crossing have to agree on, declared
;; ONCE here because `server.cljs` requires this namespace and the client
;; boot below is in it. A second copy is a second thing to keep in step.

(def identifier-prefix
  "React's `identifierPrefix`, handed unchanged to the server render and to
  the hydrating client root. React numbers every `useId` per root from the
  same start and prefixes it with this string, so a hydrating root given a
  different one — or none, where the server had one — resolves every id in
  the tree differently from the bytes it is adopting."
  "rf-login-")

(def app-element-id
  "The element the JVM host's shell wraps Node's body markup in, and the one
  the client adopts."
  "app")

(def frame-id
  "The one frame this page owns, on both hosts."
  :rf/default)

;; ============================================================================
;; VIEWS  (Fresco — rf.fresco/defview + rf.fresco/sub + intents)
;; ============================================================================
;;
;; `rf.fresco/defview` mints a real React function component whose head is a legal
;; hiccup tag: `[login-form]`. Inside the body `rf.fresco/sub` reads a subscription
;; from the frame this root scoped — no deref, no hook, and legal inside a
;; `let`, a `when` or an inlined helper, because the edge is recorded where the
;; read happens.
;;
;; The inputs are controlled the same way as in the twins: each `:value` reads
;; the draft out of `:auth.login/draft`, and each change dispatches an edit
;; event. There is no view-local state anywhere in this file — the draft is
;; app-db, which is the whole reason there is nothing here to keep in step.

(rf.fresco/defview login-form
  "The login form: email + password + submit + error display."
  [_]
  (let [draft     (rf.fresco/sub [:auth.login/draft])
        busy?     (rf.fresco/sub [:rf.machine/has-tag? :auth.login/flow :auth/busy])
        err       (rf.fresco/sub [:auth.login/error])
        email-err (rf.fresco/sub [:auth.login/field-error :email])
        pw-err    (rf.fresco/sub [:auth.login/field-error :password])]
    [:form.login-form
     {:data-testid "login-form"
      ;; CALLBACK 1 of 2. `:on-submit` is the one position Fresco prevents by
      ;; default, so `[:auth.login/submit-form]` alone would be the whole
      ;; handler — but the twins refuse a second submit while a request is in
      ;; flight, and an intent vector has nowhere to put that condition. So the
      ;; guard is written where a condition belongs: in a callback that
      ;; prevents unconditionally and returns an event only when there is one
      ;; to send.
      :on-submit   (rf.fresco/event [e]
                     (.preventDefault e)
                     (when-not busy?
                       [:auth.login/submit-form]))}
     ;; The email is NOT a secret, so it rides the plain positional
     ;; `:auth.login/edit-field` — and the whole handler is the intent vector.
     ;; `::rf.fresco/value` is substituted with the event target's current value at
     ;; dispatch time.
     [:input {:type        "email"
              :placeholder "Email"
              :disabled    busy?
              :data-testid "login-email"
              :value       (:email draft)
              :on-change   [:auth.login/edit-field :email ::rf.fresco/value]}]
     (when email-err [:p.error {:data-testid "login-email-error"} email-err])
     ;; CALLBACK 2 of 2. The password's keystrokes ride a MAP payload —
     ;; `[:auth.login/edit-password {:value …}]` — because its registration
     ;; declares `:sensitive [[:value]]` and redaction is path-based, so a
     ;; positional secret would ship raw to every trace
     ;; (docs/core/how-to/keep-secrets-out-of-traces.md). `::rf.fresco/value`
     ;; substitutes at the intent's TOP LEVEL only, by design, so building that
     ;; map is exactly the case `rf.fresco/event` exists for. Flattening the secret
     ;; into a positional intent to save four characters would break the
     ;; classification.
     [:input {:type        "password"
              :placeholder "Password"
              :disabled    busy?
              :data-testid "login-password"
              :value       (:password draft)
              :on-change   (rf.fresco/event [e]
                             [:auth.login/edit-password
                              {:value (.. e -target -value)}])}]
     (when pw-err [:p.error {:data-testid "login-password-error"} pw-err])
     [:button {:type        "submit"
               :disabled    busy?
               :data-testid "login-submit"}
      (if busy? "Signing in…" "Sign in")]
     (when err [:p.error {:data-testid "login-error"} err])]))

;; The dead end. `:locked-out` is tagged `:auth/locked` and has no way out, so
;; the form is replaced rather than left on screen taking input and ignoring it.
(rf.fresco/defview locked-panel
  "Locked-account panel shown when the login flow reaches :locked-out."
  [_]
  [:div.locked {:data-testid "locked-panel"}
   [:h2 "Account locked"]
   [:p "Too many failed attempts. Contact support to unlock."]])

;; The top-level switch: two tag reads, three faces. Views ask the machine a
;; QUESTION (`:rf.machine/has-tag?`) rather than matching exact state names.
(rf.fresco/defview login-banner
  "Picks what to show by login state: welcome / locked panel / the form."
  [_]
  (let [authed? (rf.fresco/sub [:rf.machine/has-tag? :auth.login/flow :auth/authenticated])
        locked? (rf.fresco/sub [:rf.machine/has-tag? :auth.login/flow :auth/locked])]
    [:div.banner {:data-testid "login-banner"}
     (cond
       authed? [:span "Welcome!"]
       locked? [locked-panel]
       :else   [login-form])]))

;; The SERVER-ONLY read, and the one view in this file that only a server
;; render can fill.
;;
;; `[:auth.login/server-notice]` is a TOP-LEVEL app-db key a JVM host puts a
;; deployment notice in — a maintenance window, a region, whatever the
;; operator resolves per request. It is declared HERE rather than in the
;; shared `login.model` because only this arm has a server; the Reagent and
;; UIx twins never see it, and the model stays substrate-free.
;;
;; It is the example's demonstration that the two SSR policies are DISTINCT:
;; a host may name this key in `:render-state` (so the render can read it)
;; while leaving it out of `:payload` (so the browser never receives it).
;; **And that choice has a price, which is the rule to take away**: a
;; render-state key the payload does not carry must not CHANGE THE MARKUP,
;; because the hydrating client renders from the payload and React reports a
;; recoverable error for every node the two disagree about. So the shipped
;; host (`host.clj`) puts no notice in app-db and this banner renders
;; nothing on both halves; the witness
;; (`re-frame.fresco.login-server-crossing-ssr-dom-cljs-test`) is where the
;; key is filled, and it measures both the absence from `__rf_payload` and
;; the hydration cost.
(rf/reg-sub :auth.login/server-notice
  {:doc "A deployment notice a JVM host may place at the top-level app-db
         key of the same name. Absent on a client-only load, and absent
         from the hydration payload whenever the host declares it
         render-visible but not payload-visible."}
  (fn sub-auth-login-server-notice [db _]
    (:auth.login/server-notice db)))

(rf.fresco/defview server-notice
  "The deployment notice, when there is one."
  [_]
  (when-some [notice (rf.fresco/sub [:auth.login/server-notice])]
    [:p.server-notice {:data-testid "login-server-notice"} notice]))

(rf.fresco/defview root-view
  "The example's root boundary."
  [_]
  [:div.app
   [:h1 "Sign in"]
   [server-notice]
   [login-banner]])

;; ============================================================================
;; BOOT  (CLJS reference; client-only)
;; ============================================================================
;;
;; Three lines, in a fixed order, and the order is the interesting part.
;;
;;   1. `rf/init!` seats an adapter. Fresco ships its own, so this is the one
;;      line that would change to run these same views on somebody else's.
;;      It is not optional: creating a frame asks the adapter for a state
;;      container, and a container asked for before `init!` fails loud with
;;      `:rf.error/no-adapter-installed`.
;;
;;   2. `[h/frame-root {…}]` creates the frame — ONCE, in the tree, with
;;      `model/frame-config` merged in. That config is the substrate-free half
;;      of the boot, shared verbatim with the Reagent and UIx twins: its
;;      `:fx-overrides` points `:rf.http/managed` at the in-process demo stub
;;      (so the example needs no backend), and its `:initial-events` seed the
;;      form slice before the first paint — skip that and the inputs read `nil`
;;      for their `:value` and React quietly demotes them to uncontrolled.
;;
;;      `frame-root` takes the `rf/make-frame` option map WHOLE, so
;;      `:fx-overrides` rides it like any other option and there is no separate
;;      `rf/make-frame` call to keep in step with the mount. This example used
;;      to make the frame first and mount to JOIN it, purely because the root
;;      door's config could not carry `:fx-overrides`; the boundary in the tree
;;      is what retired that detour (rf2-kuky.58).
;;
;;   3. `rf.fresco/render!` associates the DOM node with one root view,
;;      through the handle allocated below. Its opts carry ROOT options only
;;      — `:hydrate?` and `:identifier-prefix` — and a key it does not own
;;      fails loud rather than being ignored.
;;
;; Nothing above this line touched the DOM. Namespace load registers handlers
;; and defines views and does no more, so another namespace can require this
;; one for its registrations alone (docs/core/how-to/boot-and-mount-an-app.md).

(defonce ^{:doc "The one Fresco client-root handle this page owns. Inert at
  allocation — no DOM work, no React call — so a load-time `defonce` costs
  nothing, and the FIRST render through it creates or adopts the Root while
  every later one updates that same Root. That is what lets the boot and the
  reload hook be one call with no root state of the page's own."}
  app-root
  (rf.fresco/client-root))

(defonce ^{:doc "The frame boundary this page booted with, as `[head opts]`.

  The root door carries root options only, so the FRAME lives in the tree —
  which means a re-render has to put the same boundary back or the tree it
  hands React has no frame at all. Held rather than rebuilt because the two
  boot paths choose DIFFERENT heads (`frame-provider` after SSR, `frame-root`
  client-only), and re-rendering with the other one is a React type change:
  the subtree unmounts and remounts, discarding exactly the DOM, subscriptions
  and component state this hook exists to preserve.

  The boundary is held; the VIEW is not. `root-view` is read fresh on every
  re-render, so the reload's newly minted head is what meets the position."}
  !boundary
  (atom nil))

;; Shadow's cue to re-run this after each reload. A later `rf.fresco/render!`
;; through a live handle UPDATES the Root it already owns, reconciling the new
;; tree against the DOM already on the page, so edited views meet their own
;; nodes and the frame beneath them is untouched. The create-once half of the
;; handle contract is what rules out a second `createRoot` throwing away every
;; node, subscription and scrap of component state.
;;
;; The mount point is named again and READ AGAIN BY NOBODY: `render!` takes it
;; on the first call through a handle only. It is passed because the door's
;; shape is (handle tree node), and looking the node up is cheaper than
;; explaining why a reload may omit it.
(defn ^:dev/after-load re-render! []
  (when-some [[head opts] @!boundary]
    (rf.fresco/render! app-root
                        [head opts [root-view]]
                        (js/document.getElementById app-element-id))))

;; ONE boot, two pages. A client-only load has no `__rf_payload` in the
;; document, so `rf.ssr/hydrate!` is a no-op and the root MOUNTS — exactly the
;; three lines above. A server-rendered load has one, so the payload is
;; installed first and the root ADOPTS the markup already on the page
;; instead of throwing it away.
;;
;; The branch is on the payload rather than on a build flag deliberately:
;; one bundle serves both, so `npm run dev:example -- examples/login-fresco`
;; keeps working unchanged and the SSR route needs no second build.
;;
;; No `:render-tree-fn` is passed to `rf.ssr/hydrate!`. The render-tree hash is
;; hiccup-tier-only, and this is an adoption-tier root: Fresco's server
;; render ships no `:rf/render-hash` and there is nothing to compare
;; against. Adoption is verified by React itself — a divergence surfaces as
;; a recoverable error on this root's own stream.

(defn run []
  (rf/init! rf.fresco.substrate/adapter)
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById app-element-id))]
    (let [payload (rf.ssr/read-server-payload)
          opts    {:identifier-prefix identifier-prefix}]
      (if (some? payload)
        ;; SSR: STATE COMES FIRST, and that is why this branch — and only
        ;; this branch — still calls `rf/make-frame` by hand. `rf.ssr/hydrate!`
        ;; dispatches `[:rf/hydrate payload]` INTO a frame; it does not make
        ;; one, and the frame it dispatches into needs `:fx-overrides` already
        ;; installed. So the frame is made, the payload replaces its app-db,
        ;; and the tree SCOPEs the result. An ENSURE in the tree here would
        ;; be the wrong SHAPE: its first render emits no descendant subtree,
        ;; where an adopting root has to render the server's element shape on
        ;; its first pass — which is exactly the mistake the frame-root /
        ;; frame-provider split exists to name. (It would not overwrite the
        ;; payload; re-ensuring a live frame preserves app-db.)
        (do (rf/make-frame (merge {:id  frame-id
                                   :doc "Login (Fresco) demo frame."}
                                  model/frame-config))
            (rf.ssr/hydrate! {:frame frame-id :payload payload})
            (reset! !boundary [rf.fresco/frame-provider {:frame frame-id}])
            (rf.fresco/render! app-root
              [rf.fresco/frame-provider {:frame frame-id} [root-view]]
              el
              (assoc opts :hydrate? true)))
        ;; Client-only: the tree ENSUREs, with the whole `make-frame` option
        ;; map on the head.
        (let [ensure (merge {:id  frame-id
                             :doc "Login (Fresco) demo frame."}
                            model/frame-config)]
          (reset! !boundary [rf.fresco/frame-root ensure])
          (rf.fresco/render! app-root
            [rf.fresco/frame-root ensure [root-view]]
            el
            opts)))))
  nil)
