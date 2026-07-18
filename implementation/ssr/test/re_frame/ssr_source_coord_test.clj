(ns re-frame.ssr-source-coord-test
  "INTERIM CONTRACT — no server-side source-coord annotation (rf2-j81hs).

  ## What this namespace used to pin, and why it no longer can

  Per Spec 011 §Source-coord annotation under SSR (rf2-z7f7 / rf2-z9n1)
  the JVM emitter injected `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"`
  on a registered view's root DOM element, mirroring Spec 006's client
  contract. Every assertion here drove that through a KEYWORD head —
  `(ssr/render-to-string [:rf.ssr-coord-test/banner] {})` — because the
  emitter's keyword branch was the only place the injection ever ran.

  rf2-j81hs deleted that branch: a keyword head is a DOM / custom element
  on every host, and views are referenced by callable head (the Var
  `reg-view` defs, or `(rf/view :id)`). The callable branch NEVER
  annotated. So server-side source-coord annotation is now unreachable —
  not disabled, not gated, structurally unreachable.

  That is the ruled outcome rather than a regression, on two grounds the
  rf2-j81hs ruling §5 states directly: the keyword-branch coord injection
  \"dies WITH the branch\", and per rf2-8vi4q's own evidence it never
  fired on any boundary a hydratable page can contain — an isomorphic
  page composes via `(rf/view :id)` fn-refs, where the JVM stores the raw
  unwrapped handler-fn, so the injection was already dead on every shape
  that matters. The shipped non-streaming example reaches its DOM root
  with no attribute server-side while the client stamps two.

  ## Why pin the absence at all

  Because \"the attribute stopped appearing\" must be a DELIBERATE,
  greppable fact rather than something a future reader discovers by
  finding no test. These assertions fail the moment anyone reintroduces
  emitter-side annotation — which is exactly the design rf2-8vi4q
  REJECTED (its Option A: emitter-side injection, rejected because it
  only ever reaches keyword-ref boundaries that hydratable pages cannot
  use).

  The production-elision property the old `debug-enabled?` tests guarded
  (rf2-wtd8z finding 3 — a production render must not leak internal
  source coordinates into public HTML) now holds VACUOUSLY and
  unconditionally: there is no annotation site left to gate. It is
  re-pinned below as an absolute rather than as a gate, so the guarantee
  survives the transition instead of quietly lapsing.

  ## Who takes it from here

  rf2-8vi4q owns the replacement: annotation moves to the reg-view
  REGISTRATION boundary as a debug-gated wrapper on the registered
  `:handler-fn`, so BOTH hosts annotate at the same architectural seam
  and the callable-head shape real pages use is covered for the first
  time. When that lands, this namespace should be REPLACED by tests that
  drive annotation through `[(rf/view :id) …]` / Var heads — not restored
  to its keyword-head form, and its production-gate arm reinstated at the
  new site.

  The orphaned `format-view-source-coord` / `inject-coord-on-root-hiccup`
  fns are left in `re-frame.ssr.emit` for rf2-8vi4q to delete; only their
  call sites are gone."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]))

;; Shared reset fixture lives in `re-frame.ssr.test-fixture` (rf2-i3qc0).
(use-fixtures :each tf/reset-runtime)

(defn- annotated?
  "True when `html` carries a source-coord annotation attribute."
  [html]
  (str/includes? html "data-rf2-source-coord="))

;; ---------------------------------------------------------------------------
;; The interim contract: nothing the JVM emitters produce is annotated
;; ---------------------------------------------------------------------------

(deftest callable-head-view-is-not-annotated-server-side
  (testing "rf2-j81hs — a registered view reached through a CALLABLE head
            (the shape every isomorphic page actually uses) renders with
            no source-coord attribute. This was ALREADY true before the
            keyword branch was deleted; the branch simply hid it, because
            nothing here exercised the callable shape."
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/banner} banner-view []
      [:h1 "hi"])

    (testing "Var head"
      (is (not (annotated? (ssr/render-to-string [banner-view] {})))))

    (testing "`(rf/view :id)` head"
      (is (not (annotated? (ssr/render-to-string
                             [(rf/view :rf.ssr-coord-test/banner)] {})))))

    (testing "the view genuinely rendered — otherwise this asserts nothing"
      (is (= "<h1>hi</h1>" (ssr/render-to-string [banner-view] {}))))))

(deftest keyword-head-is-an-element-and-is-not-annotated
  (testing "rf2-j81hs — the head that USED to be annotated is now an
            element. It carries no source-coord attribute because it
            resolves to no view at all: `:coord-demo/banner` renders
            `<banner>`, taking the keyword's name as the tag.

            Registered under an UNRESERVED namespace deliberately. The
            rest of this file registers under `:rf.ssr-coord-test/*`,
            which sits inside the framework-reserved `:rf.<area>/*` scheme
            — harmless as a registry id, but as a HEAD it now trips the
            reserved-head guard and throws instead of rendering an
            element. Using a reserved id here would test the guard, not
            the element branch."
    (rf/reg-view ^{:rf/id :coord-demo/banner} banner-view []
      [:h1 "hi"])
    (let [html (ssr/render-to-string [:coord-demo/banner] {})]
      (is (= "<banner></banner>" html))
      (is (not (annotated? html))))))

(deftest plain-hiccup-not-annotated
  (testing "unchanged by rf2-j81hs — ordinary tags were never annotated"
    (is (not (annotated? (ssr/render-to-string [:div [:span "x"]] {}))))))

(deftest streaming-walker-emits-no-source-coord
  (testing "rf2-j81hs — the streaming shell walker mirrored the sync
            emitter's injection on its OWN keyword branch. Both are gone,
            so a streamed shell is unannotated too. Pinned separately
            because the walker carried its own copy: a partial restore
            touching only one emitter would leave the two diverging again,
            which is the whole failure class this bead closes."
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/shell} shell-view []
      [:main "shell"])
    (let [{:keys [shell-html]} (streaming/render-shell [shell-view])]
      (is (= "<main>shell</main>" shell-html))
      (is (not (annotated? shell-html))))))

(deftest no-emitter-path-annotates
  (testing "rf2-j81hs — a sweep across the shapes the old tests covered
            (nested views, fragment roots, programmatic registration).
            None annotates. Stated as one sweep rather than five near-
            identical deftests: the contract is now uniform, and its
            uniformity is the point."
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/inner} inner-view []
      [:span "in"])
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/outer} outer-view []
      [:section [inner-view]])
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/frag} frag-view []
      [:<> [:p "a"] [:p "b"]])
    ;; Programmatic registration — no macro-captured coords at all.
    (rf/reg-view* :rf.ssr-coord-test/programmatic {} (fn [] [:div "prog"]))

    (doseq [[label tree]
            [["nested views"  [outer-view]]
             ["fragment root" [frag-view]]
             ["programmatic"  [(rf/view :rf.ssr-coord-test/programmatic)]]]]
      (is (not (annotated? (ssr/render-to-string tree {})))
          (str label " must not carry a source-coord annotation")))))

;; ---------------------------------------------------------------------------
;; Production elision (rf2-wtd8z finding 3) — now unconditional
;; ---------------------------------------------------------------------------

(deftest no-source-coord-leak-in-either-debug-mode
  (testing "rf2-wtd8z finding 3, restated for the post-rf2-j81hs world:
            a production render must never leak internal source
            coordinates into public HTML. The old tests proved this by
            flipping `interop/debug-enabled?` and checking the OFF arm
            elided. With no annotation site left, the guarantee is
            unconditional — so pin it against BOTH settings of the gate.

            Driving debug ON is the load-bearing half: it is the arm that
            used to annotate, so it is the arm that would catch an
            accidental reintroduction."
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/gated} gated-view []
      [:h2 "g"])
    (doseq [debug? [true false]]
      (with-redefs [interop/debug-enabled? debug?]
        (testing (str "sync emitter, debug-enabled? = " debug?)
          (let [html (ssr/render-to-string [gated-view] {})]
            (is (= "<h2>g</h2>" html))
            (is (not (annotated? html)))))

        (testing (str "streaming walker, debug-enabled? = " debug?)
          (let [{:keys [shell-html]} (streaming/render-shell [gated-view])]
            (is (= "<h2>g</h2>" shell-html))
            (is (not (annotated? shell-html)))))))))
