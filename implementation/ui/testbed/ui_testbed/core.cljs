(ns ui-testbed.core
  "Tiny standalone counter app — the re-frame.ui substrate's smoke fixture
   (rf2-nojiwy, the four-suites rule's new-UI smoke).

   Per TESTING.md §Test surface ownership: examples/ are for humans;
   per-substrate smoke lives with the substrate. re-frame.ui is not an
   adapter, so its testbed is homed here rather than under
   implementation/adapters/ — but the smoke rides the SAME shared runner
   (`npm run test:adapter-smokes`) and mirrors the three adapter testbeds'
   shape exactly: mount, subscribe, dispatch, re-render.

   Minimal by design. Don't grow it. Real coverage is the re-frame.ui
   conformance suites (`npm run test:cljs` / `npm run test:browser`) and
   the G-gates (`test:ui-g1` / `test:ui-g13` / `test:ui-g8`)."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview]]))

;; -- Events / subs ----------------------------------------------------------

(rf/reg-event :counter/init
  (fn [{:keys [db]} _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

;; The ONE component form (Spec 004): `ui/sub` reads the committed value at
;; a compiler-proven site, and the literal event vector on `:on-click` rides
;; the per-site-stable committed dispatch door — no captured dispatch fn, no
;; handler closure. The enclosing `ui/frame-provider` (see `init` below)
;; scopes both to the testbed's frame.
(defview root []
  [:div
   [:h1 {:data-testid "rf-ui-testbed"}
    "re-frame.ui testbed"]
   [:p [:span {:data-testid "rf-ui-counter"} (str (ui/sub [:counter/value]))]]
   [:button {:data-testid "rf-ui-inc"
             :on-click     [:counter/inc]}
    "+1"]])

;; -- Mount ------------------------------------------------------------------

(defn ^:export init []
  ;; The runtime never synthesises a frame from absence — `:rf/default` is
  ;; this testbed's app frame, created explicitly (init! installs only the
  ;; substrate adapter) and seeded through :initial-events. `ui/mount` is
  ;; the one-shot client mount (create-root + frame preflight + render!);
  ;; the `frame-provider` in the literal root form scopes every in-tree
  ;; sub/dispatch to the frame. Mirrors the g13 prod entry-point's public
  ;; mount shape and the adapter testbeds' explicit-frame discipline.
  (rf/init! ui/adapter)
  (let [frame (rf/make-frame {:id             :rf/default
                              :initial-events [[:counter/init]]})]
    (ui/mount [ui/frame-provider {:frame frame}
               [root]]
              (js/document.getElementById "app")
              {:root-id :ui-testbed/root})))
