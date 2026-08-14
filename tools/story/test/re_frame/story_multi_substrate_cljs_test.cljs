(ns re-frame.story-multi-substrate-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — multi-substrate
  side-by-side renderer. The JVM side has no DOM so the visual /
  React paths are CLJS-only."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.ui.multi-substrate :as multi]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  ;; Wipe the substrate registry so each test starts clean.
  (reset! multi/substrate->render-fn {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- registry surface ---------------------------------------------------

(deftest reagent-default-registered
  (testing "install-canonical-vocabulary! registers :reagent substrate"
    (is (contains? (multi/registered-substrates) :reagent))))

(deftest register-and-unregister
  (testing "register-substrate! + unregister-substrate! work"
    (multi/register-substrate! :uix
                               (fn [_ _ _] [:div "uix-stub"]))
    (is (contains? (multi/registered-substrates) :uix))
    (multi/unregister-substrate! :uix)
    (is (not (contains? (multi/registered-substrates) :uix)))))

(deftest public-register-substrate-on-story
  (testing "story/register-substrate! is the public form"
    (story/register-substrate! :custom (fn [_ _ _] [:div "custom-stub"]))
    (is (contains? (story/registered-substrates) :custom))))

;; ---- substrate-set resolution -------------------------------------------

(deftest substrate-set-from-variant
  (testing "resolve-substrate-set prefers variant :substrates when present"
    (is (= #{:reagent :uix}
           (multi/resolve-substrate-set
             {:substrates #{:reagent :uix}}
             {}
             :reagent)))))

(deftest substrate-set-from-story
  (testing "falls back to story body's :substrates"
    (is (= #{:reagent :uix}
           (multi/resolve-substrate-set
             {}
             {:substrates #{:reagent :uix}}
             :reagent)))))

(deftest substrate-set-defaults-host
  (testing "defaults to {host} when neither body nor story declares :substrates"
    (is (= #{:reagent}
           (multi/resolve-substrate-set {} {} :reagent)))))

;; ---- render-view dispatch -----------------------------------------------
;;
;; `render-view` is the seam the `render-variant` host-render hook consumes:
;; `re-frame.story.canonical/render-host-scope` → `render-decorated-view` →
;; here. It is NOT the canvas grid's path — `safe-render-cell` carries its
;; own registry lookup and its own diagnostic — so the `:uix` arms in
;; `story/ui/render_shell_cljs_test.cljs` exercise that copy and never reach
;; this one, which they only cover on `:reagent`.
;;
;; rf2-nfwbt: recovered from the retired re-frame.ui consumer test, which was
;; the only caller of `render-view` against a foreign substrate in the tree.
;; A stub render-fn is enough — the point is the dispatch and the degrade,
;; not any particular substrate, so this needs no adapter dependency.

(deftest render-view-dispatches-to-a-foreign-substrate
  (testing "a registered NON-:reagent substrate gets the render call, and the
            (variant-id view-id args) contract arrives intact"
    (let [seen (atom nil)]
      (multi/register-substrate! :uix
        (fn [variant-id view-id args]
          (reset! seen [variant-id view-id args])
          [:div.uix-stub "rendered by the stub"]))
      (is (= [:div.uix-stub "rendered by the stub"]
             (multi/render-view :uix :story.rv/probe :views/probe {:n 1}))
          "the substrate's own render result is returned verbatim")
      (is (= [:story.rv/probe :views/probe {:n 1}] @seen)
          "the substrate-render contract is (fn [variant-id view-id args])"))))

(deftest render-view-degrades-when-substrate-unregistered
  (testing "an unregistered substrate yields an inline diagnostic rather than
            throwing, so the render verb's caller always sees something back"
    (is (not (contains? (multi/registered-substrates) :uix))
        "precondition: :uix is not registered in this fixture")
    (let [rendered (multi/render-view :uix :story.rv/probe :views/probe {})]
      (is (vector? rendered)
          "a hiccup vector came back — nothing threw past the caller")
      (is (re-find #"is not registered" (last rendered))
          "the diagnostic names the contract the author has to satisfy")
      (is (re-find #"uix" (last rendered))
          "and names the substrate that is missing"))))

