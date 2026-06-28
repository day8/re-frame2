(ns reagent-slim.counter.core
  "The counter, again — this time on the reagent-slim substrate.

   This is the teaching file: plain, idiomatic re-frame2. The dataflow is
   exactly the counter from `examples/core/counter` — same `:counter/*`
   events, same sub. What changes is the substrate underneath: the view
   imports point at `reagent2.*`, and `rf/init!` gets the slim adapter.
   That's it. Picking a substrate is a one-line decision, and you can read
   the whole menu in docs/core/how-to/use-uix-helix-or-slim.md.

   One wrinkle in the require below, and it's a monorepo wrinkle, not a
   re-frame2 one: we name `re-frame.adapter.reagent-slim` directly because
   this repo shares a classpath with the stock adapter and the two would
   otherwise collide. Your app won't have that problem — you pick slim by
   its `deps.edn` coordinate (`day8/reagent-slim`) and require the ordinary
   adapter namespace. The guide page has the coordinate table.

   You'll also spot two sibling files here that have nothing to teach you:
   `bundle-isolation-entry` and `bundle-isolation-fixture`. They exist to
   prove a CI gate, live entirely off to the side, and boot the same app
   through the shared `boot!` helper below — the entry just hands it a hook
   that exercises the pure-CLJS SSR path the gate inspects. Read past them."
  (:require [reagent2.dom.client                :as rdc]
            [re-frame.core                      :as rf]
            [re-frame.views]
            ;; Monorepo-only spelling (see the ns docstring). Your app picks
            ;; slim by its deps coordinate, not by naming this namespace.
            [re-frame.adapter.reagent-slim      :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (the handler registry is app-global) ----------------------
;;
;; Here's the quietly remarkable part. These four forms — three pure handlers
;; and one pure subscription, all over plain data — are byte-for-byte the same
;; whether you render through Reagent, slim, UIx, or Helix. Swapping the
;; substrate doesn't touch a single character of them; only the boot changes.
;; That's the whole pitch of this example, hiding in plain sight.
;; See docs/core/glossary.md for event and subscription.

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _event] {:db {:counter/value 5}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- Views -------------------------------------------------------------------
;;
;; reg-view doesn't care about the substrate either. The same view form renders
;; on whichever adapter was installed at boot, because it looks up that adapter
;; at render time rather than baking one in. Here it finds slim (see `boot!`).
;; See docs/core/concepts/views.md.

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root lives in an atom and is created lazily inside `boot!`, never
;; at ns-load. The rule is simple: loading this namespace must touch no DOM.
;; That keeps co-loaded example namespaces from racing each other to call
;; `create-root` on the shared `#app`. Same mount shape as
;; `examples/core/counter`.

(defonce react-root (atom nil))

;; The app frame is born in exactly one place: the `frame-provider {:id
;; app-frame …}` at the render root below. On first mount the provider
;; creates the frame, applies its config, and fires `:initial-events` once
;; to seed it — that's the `[:counter/initialise]` dispatch. From then on
;; every `dispatch`/`subscribe` resolves to that frame. Hot reload reuses
;; the frame already there and skips the re-seed, so your count survives.
;;
;; And `:rf/default` is just a frame id — no magic, no privilege. You
;; establish it like any other; the runtime will never conjure it for you.
;; See docs/core/concepts/frames.md.
(def app-frame :rf/default)

(defn boot!
  "Stand the example up. Two steps: install the slim adapter, then lazily
   mount `counter-app` into `#app` under a `frame-provider {:id app-frame …}`.
   The provider does the frame work — creates it, seeds it (see the comment
   just above).

   There's one `boot!` and everyone goes through it. The teaching path
   (`run`, below) and the gate-owned path
   (`reagent-slim.counter.bundle-isolation-entry/run`) both land here, so
   the boot lives in a single place and can't drift between them.

   `on-frame` is an optional thunk, and you can cheerfully ignore it — only
   the bundle-isolation entry passes one; `run` does not. When supplied, it
   runs once just before the client mount, inside its own throwaway frame
   (`with-new-frame`, torn down on exit). That keeps the fixture's pre-mount
   SSR poking well away from the real app frame the provider sets up."
  ([] (boot! nil))
  ([on-frame]
   ;; The slim adapter — this single line is the whole difference from
   ;; `examples/core/counter`. Same `rf/init!`, different adapter.
   (rf/init! reagent-slim-adapter/adapter)
   (when on-frame
     ;; Fixture-only seam. Run the hook in a throwaway frame (gone on exit)
     ;; so it never touches the app frame the provider creates below.
     (rf/with-new-frame [_ (rf/make-frame {})]
       (on-frame)))
   (when (exists? js/document)
     (when-not @react-root
       (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
     (rdc/render @react-root
                 [rf/frame-provider {:id app-frame
                                     :initial-events [[:counter/initialise]]}
                  [counter-app]]))))

(defn run []
  (boot!))
