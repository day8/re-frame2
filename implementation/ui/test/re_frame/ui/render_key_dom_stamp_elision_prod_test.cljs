(ns re-frame.ui.render-key-dom-stamp-elision-prod-test
  "rf2-ny34u — the PRODUCTION-erasure twin of `render_key_dom_stamp_dom_cljs_test`.

  Runs ONLY in `:browser-test-prod-elision` (`shadow-cljs release` ⇒
  `:optimizations :advanced`, goog.DEBUG=false), where the ViewCell's DEV-only
  render-key DOM stamp DCEs. It mounts a compiled sub ViewCell — the same shape
  the dev twin stamps — and asserts the committed host node carries NO
  `data-rf-render-key` attribute (the whole `js/goog.DEBUG` arm in
  `re-frame.ui.viewcell/use-commit-and-lifecycle!` folds away: no host-node ref,
  no clone, no `setAttribute`). This is the I-12 production-erasure proof for the
  stamp (the DOM-attribute counterpart of the static-annotation erasure the
  parity corpus already pins for `data-rf-view` / `data-rf2-source-coord`).

  Two teeth guard against a vacuous pass:
    (i)  `debug-regime-is-off` — `interop/debug-enabled?` is false in-bundle, so
         the absence assertion cannot silently run in the dev regime.
    (ii) the same test asserts the host node DID commit (a real
         `[data-role='stamped']` element), so the missing attribute is a genuine
         erasure, not an unmounted view."
  (:require [cljs.test :refer [deftest is testing]]
            ["react-dom" :as ReactDOM]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]))

(defview prod-stamped-view
  [{:keys [label]}]
  [:div {:data-role "stamped"} (str label ":" (ui/sub [:rk/n]))])

(deftest debug-regime-is-off
  (testing "goog.DEBUG=false in-bundle — the erasure assertion below is not vacuous"
    (is (false? interop/debug-enabled?)
        "interop/debug-enabled? must constant-fold to false in this advanced build")))

(deftest render-key-dom-stamp-is-absent-in-production
  (reactive/reset-scheduler!)
  (rf/reg-sub :rk/n (fn [db _] (:n db)))
  (let [fa        (rf/make-frame {:initial-events [[:rf/set-db {:n 1}]]})
        fa-id     (frame/frame-target->id fa)
        container (js/document.createElement "div")
        root      (ui/create-root container {:root-id :render-key-stamp/prod-root})]
    (.appendChild js/document.body container)
    (try
      (ReactDOM/flushSync
       #(ui/render! root [ui/frame-provider {:frame fa}
                          [prod-stamped-view {:label "p"}]]))
      (let [node (.querySelector container "[data-role='stamped']")]
        (is (some? node)
            "positive control: the sub ViewCell committed a real host node")
        (is (= "p:1" (.-textContent node))
            "the compiled sub resolved its value (the mount is real)")
        (is (nil? (.getAttribute node "data-rf-render-key"))
            "ERASED: production carries no render-key DOM stamp"))
      (finally
        (ReactDOM/flushSync #(ui/unmount! root))
        (.remove container)))))
