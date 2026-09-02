(ns hicasso.login.core
  "A login flow, rendered through Hicasso — re-frame2's own native view layer.

   The same feature runs on Reagent (`login.core`) and on UIx
   (`uix.login.core`), and that is the point of this file. All three
   `:require` the identical substrate-free
   [`login.model`](../../../core/login/model.cljs) namespace — the ONE owner of
   the `auth.login` schemas, demo fx, five-state machine, form-slice events and
   named subs — and each adds only its own view layer and boot. Diff this
   `core.cljs` against either twin and the diff is the view layer, whole and
   entire.

   What changes here is the notation, and it changes more than on the UIx side:
   Hicasso interprets Hiccup at runtime, so a view is an `h/defview` boundary
   whose body reads subscriptions with `h/sub` and states its handlers as DATA.
   `{:on-change [:auth.login/edit-field :email ::h/value]}` IS the handler —
   there is no callback to write, and `::h/value` substitutes the event
   target's value at dispatch time. Reagent reaches for `reg-view` and an
   injected `dispatch`; UIx reaches for `defui` plus the `use-subscribe` hook.
   The subscription vectors and the event ids do not change one character
   across the three.

   Two handlers here are `h/event` callbacks rather than intent vectors,
   and both for a stated reason — see them below. `h/event` is Hicasso's one
   callback form: at an `on-*` position a returned vector is dispatched and any
   other return is ignored.

   For the view layer itself, see the Hicasso guide at
   docs/core/hicasso/ (start at `00-installation.md`); for the substrate
   boundary in general, docs/core/how-to/use-uix-or-slim.md.

   Examples are test-free: login's behaviour is covered by the substrate
   contract suite (`npm run test:cljs`) and the framework gates, not by a test
   alongside this file."
  (:require [re-frame.core :as rf]
            ;; The substrate-free model owner (examples/core/login/model.cljs).
            ;; Requiring it registers every shared `auth.login` schema, fx,
            ;; machine, event and sub, and hands us `model/frame-config` for the
            ;; boot below. It names no substrate — the Hicasso code lives only
            ;; here.
            [login.model :as model]
            [re-frame.hicasso :as h]
            ;; The client half of an SSR route: `ssr/hydrate!` installs the
            ;; server's app-db from `__rf_payload` BEFORE the first client
            ;; render. On a client-only load it finds no payload and is a
            ;; no-op, which is what lets ONE `run` serve both pages.
            [re-frame.ssr :as ssr]
            ;; Hicasso is a VIEW layer, not a substrate: it owns Hiccup
            ;; interpretation and the render boundary, while the reactive
            ;; container app-db lives in comes from an adapter. Hicasso ships
            ;; its own, in this namespace, so the choice costs no extra
            ;; coordinate (docs/core/hicasso/00-installation.md §Hicasso needs a
            ;; substrate adapter).
            [re-frame.hicasso.substrate :as substrate]))

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
;; VIEWS  (Hicasso — h/defview + h/sub + intents)
;; ============================================================================
;;
;; `h/defview` mints a real React function component whose head is a legal
;; hiccup tag: `[login-form]`. Inside the body `h/sub` reads a subscription
;; from the frame this root scoped — no deref, no hook, and legal inside a
;; `let`, a `when` or an inlined helper, because the edge is recorded where the
;; read happens.
;;
;; The inputs are controlled the same way as in the twins: each `:value` reads
;; the draft out of `:auth.login/draft`, and each change dispatches an edit
;; event. There is no view-local state anywhere in this file — the draft is
;; app-db, which is the whole reason there is nothing here to keep in step.

(h/defview login-form
  "The login form: email + password + submit + error display."
  [_]
  (let [draft     (h/sub [:auth.login/draft])
        busy?     (h/sub [:rf.machine/has-tag? :auth.login/flow :auth/busy])
        err       (h/sub [:auth.login/error])
        email-err (h/sub [:auth.login/field-error :email])
        pw-err    (h/sub [:auth.login/field-error :password])]
    [:form.login-form
     {:data-testid "login-form"
      ;; CALLBACK 1 of 2. `:on-submit` is the one position Hicasso prevents by
      ;; default, so `[:auth.login/submit-form]` alone would be the whole
      ;; handler — but the twins refuse a second submit while a request is in
      ;; flight, and an intent vector has nowhere to put that condition. So the
      ;; guard is written where a condition belongs: in a callback that
      ;; prevents unconditionally and returns an event only when there is one
      ;; to send.
      :on-submit   (h/event [e]
                     (.preventDefault e)
                     (when-not busy?
                       [:auth.login/submit-form]))}
     ;; The email is NOT a secret, so it rides the plain positional
     ;; `:auth.login/edit-field` — and the whole handler is the intent vector.
     ;; `::h/value` is substituted with the event target's current value at
     ;; dispatch time.
     [:input {:type        "email"
              :placeholder "Email"
              :disabled    busy?
              :data-testid "login-email"
              :value       (:email draft)
              :on-change   [:auth.login/edit-field :email ::h/value]}]
     (when email-err [:p.error {:data-testid "login-email-error"} email-err])
     ;; CALLBACK 2 of 2. The password's keystrokes ride a MAP payload —
     ;; `[:auth.login/edit-password {:value …}]` — because its registration
     ;; declares `:sensitive [[:value]]` and redaction is path-based, so a
     ;; positional secret would ship raw to every trace
     ;; (docs/core/how-to/keep-secrets-out-of-traces.md). `::h/value`
     ;; substitutes at the intent's TOP LEVEL only, by design, so building that
     ;; map is exactly the case `h/event` exists for. Flattening the secret
     ;; into a positional intent to save four characters would break the
     ;; classification.
     [:input {:type        "password"
              :placeholder "Password"
              :disabled    busy?
              :data-testid "login-password"
              :value       (:password draft)
              :on-change   (h/event [e]
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
(h/defview locked-panel
  "Locked-account panel shown when the login flow reaches :locked-out."
  [_]
  [:div.locked {:data-testid "locked-panel"}
   [:h2 "Account locked"]
   [:p "Too many failed attempts. Contact support to unlock."]])

;; The top-level switch: two tag reads, three faces. Views ask the machine a
;; QUESTION (`:rf.machine/has-tag?`) rather than matching exact state names.
(h/defview login-banner
  "Picks what to show by login state: welcome / locked panel / the form."
  [_]
  (let [authed? (h/sub [:rf.machine/has-tag? :auth.login/flow :auth/authenticated])
        locked? (h/sub [:rf.machine/has-tag? :auth.login/flow :auth/locked])]
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
;; (`re-frame.hicasso.login-server-crossing-ssr-dom-cljs-test`) is where the
;; key is filled, and it measures both the absence from `__rf_payload` and
;; the hydration cost.
(rf/reg-sub :auth.login/server-notice
  {:doc "A deployment notice a JVM host may place at the top-level app-db
         key of the same name. Absent on a client-only load, and absent
         from the hydration payload whenever the host declares it
         render-visible but not payload-visible."}
  (fn sub-auth-login-server-notice [db _]
    (:auth.login/server-notice db)))

(h/defview server-notice
  "The deployment notice, when there is one."
  [_]
  (when-some [notice (h/sub [:auth.login/server-notice])]
    [:p.server-notice {:data-testid "login-server-notice"} notice]))

(h/defview root-view
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
;;   1. `rf/init!` seats an adapter. Hicasso ships its own, so this is the one
;;      line that would change to run these same views on somebody else's.
;;      It is not optional: creating a frame asks the adapter for a state
;;      container, and a container asked for before `init!` fails loud with
;;      `:rf.error/no-adapter-installed`.
;;
;;   2. `rf/make-frame` creates the frame — ONCE, here, with
;;      `model/frame-config` merged in. That config is the substrate-free half
;;      of the boot, shared verbatim with the Reagent and UIx twins: its
;;      `:fx-overrides` points `:rf.http/managed` at the in-process demo stub
;;      (so the example needs no backend), and its `:initial-events` seed the
;;      form slice before the first paint — skip that and the inputs read `nil`
;;      for their `:value` and React quietly demotes them to uncontrolled.
;;
;;      Why here rather than inside the mount? Because `h/mount!`'s config
;;      carries exactly three keys — `:frame`, `:initial-events` and
;;      `:identifier-prefix` — and `:fx-overrides` is not among them. That is
;;      the root door's shape, not an omission, and the example bends to it
;;      rather than the other way around: no shim is added to `h/mount!` for
;;      this file's convenience.
;;
;;   3. `h/mount!` associates the DOM node, the frame and one root view.
;;      Mounting ENSURES its frame: it creates the frame when absent and JOINS
;;      the live one otherwise. Step 2 already created `:rf/default`, so this
;;      joins it untouched — no re-seed, no config refresh — which is why
;;      `:initial-events` is NOT repeated here. It rides `frame-config` at
;;      step 2, where the frame is actually made.
;;
;; Nothing above this line touched the DOM. Namespace load registers handlers
;; and defines views and does no more, so another namespace can require this
;; one for its registrations alone (docs/core/how-to/boot-and-mount-an-app.md).

(defonce ^{:doc "The one Hicasso root this page owns, kept so hot reload can
  re-render it rather than build a second one."}
  !root
  (atom nil))

;; Shadow's cue to re-run this after each reload. `h/render!` reconciles the new
;; tree against the DOM already on the page, so edited views meet their own
;; nodes and the frame beneath them is untouched. Calling `h/mount!` again would
;; `createRoot` a second time and throw away every node, subscription and scrap
;; of component state.
(defn ^:dev/after-load re-render! []
  (when-some [root @!root]
    (h/render! root [root-view])))

;; ONE boot, two pages. A client-only load has no `__rf_payload` in the
;; document, so `ssr/hydrate!` is a no-op and the root MOUNTS — exactly the
;; three lines above. A server-rendered load has one, so the payload is
;; installed first and the root ADOPTS the markup already on the page
;; instead of throwing it away.
;;
;; The branch is on the payload rather than on a build flag deliberately:
;; one bundle serves both, so `npm run dev:example -- examples/login-hicasso`
;; keeps working unchanged and the SSR route needs no second build.
;;
;; No `:render-tree-fn` is passed to `ssr/hydrate!`. The render-tree hash is
;; hiccup-tier-only, and this is an adoption-tier root: Hicasso's server
;; render ships no `:rf/render-hash` and there is nothing to compare
;; against. Adoption is verified by React itself — a divergence surfaces as
;; a recoverable error on this root's own stream.

(defn run []
  (rf/init! substrate/adapter)
  (rf/make-frame (merge {:id  frame-id
                         :doc "Login (Hicasso) demo frame."}
                        model/frame-config))
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById app-element-id))]
    (let [payload (ssr/read-server-payload)
          config  {:frame             frame-id
                   :identifier-prefix identifier-prefix}]
      (if (some? payload)
        (do (ssr/hydrate! {:frame frame-id :payload payload})
            (reset! !root (h/hydrate! el config [root-view])))
        (reset! !root (h/mount! el config [root-view])))))
  nil)
