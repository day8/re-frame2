(ns re-frame.live-frame-teardown-hook-reload-jvm-test
  "`:live-frame/on-frame-destroyed!` re-arms when `re-frame.live-frame` is
  reloaded in a process that has already constructed a frame. The key is
  published at ns load rather than from the reprojection once-body, which a
  `defonce` flag skips on every reload after the first `make-frame`; published
  there, a reload would leave `destroy-frame!` with no release to call and
  provenance rows would leak. The discriminating state — once-flag already
  true, this one key missing — is constructed explicitly. JVM-only because
  `(require … :reload)` has no ClojureScript analogue; the publication is one
  top-level form in a `.cljc` file, so shadow-cljs hot reload re-runs it the
  same way."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core       :as rf]
            [re-frame.late-bind  :as rf.late-bind]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private hook-key :live-frame/on-frame-destroyed!)

(defn- provenance []
  (deref @#'rf.live-frame/frame-generation-pool))

(defn- row?
  "Key membership, not `get`: an ordinary frame's recorded pool IS nil."
  [id]
  (contains? (provenance) id))

(defn- once-flag []
  (deref @#'rf.live-frame/reprojection-installed?))

(defn- restore-hook!
  "Put `hook-key` back exactly as `before` had it — present with that fn, or
  absent — without clobbering anything else published meanwhile."
  [before]
  (if-let [f (get before hook-key)]
    (rf.late-bind/set-fn! hook-key f)
    (do (swap! rf.late-bind/hooks dissoc hook-key)
        (rf.late-bind/invalidate-cache! hook-key)))
  nil)

(deftest teardown-hook-re-arms-on-live-frame-reload-rf2-cq0yi
  (testing "with the once-flag already set and only the teardown key missing,
            reloading `re-frame.live-frame` re-publishes the key and
            `destroy-frame!` releases the frame's provenance row"
    (let [hooks-before @rf.late-bind/hooks]
      (try
        ;; a live process: a frame has been constructed, so the flag is latched
        (rf/destroy-frame! (rf/make-frame {:id :cq0yi-reload/warm}))
        (swap! rf.late-bind/hooks dissoc hook-key)
        (rf.late-bind/invalidate-cache! hook-key)
        (is (= [true nil] [(once-flag) (rf.late-bind/get-fn hook-key)])
            "precondition: once-flag latched, teardown key absent")
        (require 're-frame.live-frame :reload)
        (let [id       :cq0yi-reload/subject
              baseline (count (provenance))
              f        (rf/make-frame {:id id})]
          (is (= [true (inc baseline)] [(row? id) (count (provenance))])
              "make-frame recorded the row, so its absence below is a release")
          (rf/destroy-frame! f)
          (is (= [false baseline] [(row? id) (count (provenance))])
              "the reloaded hook released the row"))
        (finally
          (restore-hook! hooks-before))))))
