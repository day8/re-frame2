(ns re-frame.story-mcp-boundary-test
  "The Story side of the MCP-consumer contract (006-MCP-Surface): the public
  read and write Vars the story-mcp jar calls in process, and the late-bind
  `reg-story-panel` contract."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story            :as rf.story]
            [re-frame.story.registrar  :as rf.story.registrar]
            [re-frame.story.schemas    :as rf.story.schemas]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-story-registry [t]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (t))

(use-fixtures :each reset-story-registry)

(defn- unresolved [syms ok?]
  (remove #(some-> (ns-resolve 're-frame.story %) deref ok?) syms))

(deftest public-read-surface-resolves
  (testing "every spec/006 read primitive resolves on re-frame.story, so a
            rename fails here and not only in the MCP jar"
    (is (empty? (unresolved '[registrations handler-meta ids registered?
                              variants-of variants-with-tags
                              variant->edn workspace->edn
                              list-tags list-modes canonical-tags
                              run-variant reset-variant watch-variant
                              snapshot-identity
                              read-assertions assertions-passing?
                              canonical-assertion-ids
                              variant-share-url]
                            #(or (fn? %) (coll? %)))))))

(deftest public-write-surface-resolves
  (is (empty? (unresolved '[reg-story* reg-variant* reg-workspace* reg-mode*
                            reg-story-panel* reg-decorator* reg-tag*
                            unregister! clear-kind! clear-all!]
                          fn?))))

(deftest reg-variant-star-round-trips-through-the-read-surface
  (testing "a variant the MCP write path registers is visible to every read tool"
    (rf.story/reg-story :story.mcp.boundary {:doc "boundary fixture"})
    (rf.story/reg-variant* :story.mcp.boundary/probe
      {:doc "probe variant" :setup [[:probe/init]] :args {:n 7} :tags #{:dev}})
    (is (= [#{:story.mcp.boundary/probe} "probe variant" [[:probe/init]] {:n 7}]
           [(rf.story/variants-of :story.mcp.boundary)
            (:doc (rf.story/handler-meta :variant :story.mcp.boundary/probe))
            (:setup (rf.story/variant->edn :story.mcp.boundary/probe))
            (:args (rf.story/variant->edn :story.mcp.boundary/probe))]))))

(deftest reg-variant-star-preserves-mcp-supplied-source
  (testing "the programmatic write path stamps no :source of its own and keeps
            one the caller supplies"
    (doseq [[vid source] [[:story.mcp.no-source/probe nil]
                          [:story.mcp.src-bring/probe {:file "agent-supplied.cljs" :line 42}]]]
      (rf.story/reg-variant* vid (cond-> {:setup []} source (assoc :source source)))
      (is (= source (:source (rf.story/handler-meta :variant vid))) (str vid)))))

(deftest unregister-removes-from-read-surface
  (rf.story/reg-variant :story.mcp.unreg/probe {:setup []})
  (rf.story.registrar/unregister! :variant :story.mcp.unreg/probe)
  (is (= [false #{} nil]
         [(rf.story/registered? :variant :story.mcp.unreg/probe)
          (rf.story/variants-of :story.mcp.unreg)
          (rf.story/handler-meta :variant :story.mcp.unreg/probe)])))

(deftest clear-kind-leaves-siblings-untouched
  (rf.story/reg-variant :story.kindA/v {:setup []})
  (rf.story/reg-mode :Mode.theme/dark {:args {:theme :dark}})
  (rf.story/reg-workspace :Workspace.kind/grid {:layout :variants-grid})
  (rf.story.registrar/clear-kind! :variant)
  (is (= [#{} true true (into rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)]
         [(set (rf.story/ids :variant))
          (contains? (rf.story/list-modes) :Mode.theme/dark)
          (rf.story/registered? :workspace :Workspace.kind/grid)
          (rf.story/list-tags)])))

;; spec/006 §Late-bind `reg-story-panel` contract: a panel id registered by
;; one artefact can be replaced by another registering under the same id.

(deftest reg-story-panel-late-bind-replaces-stub
  (rf.story/reg-story-panel :rf.story/xray-epoch
    {:title "Epochs (stub)" :placement :bottom :render :rf.story.stubs/xray-epoch-stub})
  (rf.story/reg-story-panel :rf.story/xray-epoch
    {:title "Epochs (Xray)" :placement :bottom :render :day8.re-frame2-xray.panels.time-travel/Panel})
  (is (= ["Epochs (Xray)" :day8.re-frame2-xray.panels.time-travel/Panel]
         ((juxt :title :render) (rf.story/handler-meta :story-panel :rf.story/xray-epoch)))))
