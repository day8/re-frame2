(ns re-frame.adapter.uix-client-root-cljs-test
  "The UIx adapter's reusable client root: `client-root`, `render!`,
  `unmount!` (Spec 006 §The client root). The node-safe half:
  inert allocation, the inert-handle no-ops, and the element-slot guard on
  the trio path. The behaviour that needs a real React Root — create-once /
  update-later, hydrate-once / update-later, unmount idempotence and the
  `dispose-adapter!` drain — is `re-frame.adapter.uix-client-root-dom-cljs-test`.

  The Reagent twin spies on `reagent.dom.client` with `with-redefs`; the
  React-hook spine mounts through the `react-dom/client` MODULE, which has no
  Vars to rebind, so the constructor-count proofs are read off the DOM in the
  browser twin instead."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react" :as React]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :ambient-frame nil}))

;; ---- 1. inert allocation ---------------------------------------------------

(deftest client-root-does-no-dom-work
  (testing "allocating a handle touches no DOM API — it runs on Node, where
            there is no `document` to touch, which is the whole point of the
            `defonce` boot idiom"
    (let [h (rf.adapter.uix/client-root)]
      (is (= [true false] [(some? h) (identical? h (rf.adapter.uix/client-root))])
          "client-root returns a handle, and each call allocates its own — there is no process-global registry")))
  (testing "an inert handle's unmount! is a no-op returning nil, however many times it is called"
    (let [h (rf.adapter.uix/client-root)]
      (is (= [nil nil] [(rf.adapter.uix/unmount! h) (rf.adapter.uix/unmount! h)])))))

;; ---- 2. the element-slot guard rides the trio path ------------------------

(deftest render-bang-refuses-cljs-data-in-the-element-slot
  (testing "hiccup / seq / map through render! raises ONE structured
            :rf.error/hiccup-on-element-render-slot, thrown BEFORE any Root is
            created (so this is node-safe) and carrying no tree content"
    (doseq [[label tree] [["hiccup vector" [:div "hiccup-secret-xyzzy"]]
                          ["seq"           (list [:div "hiccup-secret-xyzzy"])]
                          ["map"           {:hiccup "hiccup-secret-xyzzy"}]]]
      (let [h      (rf.adapter.uix/client-root)
            thrown (try (rf.adapter.uix/render! h tree nil) nil
                        (catch :default e e))]
        (is (= [:rf.error/hiccup-on-element-render-slot true false nil]
               [(:rf.error/id (ex-data thrown))
                (str/includes? (str (ex-message thrown)) "[:rf.error/hiccup-on-element-render-slot]")
                (boolean (re-find #"xyzzy" (pr-str (ex-data thrown))))
                (rf.adapter.uix/unmount! h)])
            (str label " is rejected with the canonical id and greppable message, no tree"
                 " content in the ex-data (EP-0015), and the handle left inert")))))
  (testing "a legitimate React element passes the guard"
    ;; The positive leg stops at the guard: mounting needs a real container,
    ;; which the browser twin supplies.
    (let [h      (rf.adapter.uix/client-root)
          thrown (try (rf.adapter.uix/render!
                        h (React/createElement "div" nil "ok") nil)
                      nil
                      (catch :default e e))]
      (is (or (nil? thrown)
              (not= :rf.error/hiccup-on-element-render-slot
                    (:rf.error/id (ex-data thrown))))
          "a React element is never refused by the element-slot guard"))))
