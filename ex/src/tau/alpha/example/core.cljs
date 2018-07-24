(ns tau.alpha.example.testing
  (:require-macros [tau.alpha.macros :refer [log on future later]])
  (:require [tau.alpha.core :refer [set-conf! on-screen? tauon tau]]
            [tau.alpha.example.automata :refer [get-frame make-initial-conditions]]
            [tau.alpha.example.utils :refer [raf on-click actx]]
            [tau.alpha.example.fps :refer [fps]]))

(set-conf! {:main "tau.alpha.example.core"
            :log? true})

;; On main thread

(defn start [on? tail ctx width height]
  (when @on?
    (raf
     #(let [_ (swap! tail get-frame)
            idata (.createImageData ctx width height)
            _ (.set (.-data idata) @tail)]
        (.putImageData ctx idata 0 0 0 0 width height)
        (start on? tail ctx width height)))))

(defn setup [ctx width]
  (let [tail (atom (make-initial-conditions width))
        on? (atom false)]
    (set! (.-fillColor ctx) "#ffffff")
    {:tail tail :on? on?}))

(defn hookup [astr]
  (let [{:keys [ctx width height canvas]} (actx astr)
        {:keys [tail on?]} (setup ctx width)]
    (on-click canvas
              #(do (swap! on? not)
                   (start on? tail ctx width height)))))


;;; On tauons

(def local-store (atom {}))

(defn process-results [lid]
  (raf
   #(let [{:keys [ctx width height tau trigger]} (get @local-store lid)
          idata (.createImageData ctx width height)]
      (.set (.-data idata) @tau)
      (.putImageData ctx idata 0 0 0 0 width height)
      (raf
       (fn []
         (swap! trigger (constantly nil)))))))


(defn send-work [tauon2 tau lid]
  (on tauon2 {:error-fn println} [tau lid]
      (swap! tau get-frame)
      (on "screen" {:error-fn println} [lid]
          (process-results lid))))

(defn run-loop-once [on? lid]
  (when @on?
    (raf
     #(let [{:keys [tau tauon]} (get @local-store lid)]
        (send-work tauon tau lid)))))

(defn setupb [{:keys [ctx width] :as aut}]
  (let [lid (keyword (gensym 'lid-))
        atauon (tauon)
        init (make-initial-conditions width)
        tau (tau init :ab true :size 1000000)]
    (swap! local-store assoc lid
           (merge aut
                  {:tau tau
                   :on? (atom false) :trigger (atom nil) :data (atom nil)
                   :tauon atauon}))
    (set! (.-fillColor ctx) "#ffffff")
    lid))

(defn hookupb [astr]
  (let [{:keys [canvas] :as aut} (actx astr)
        lid (setupb aut)
        {:keys [on? trigger tau]} (get @local-store lid)]
    (on-click canvas
              #(when @tau
                 (swap! on? not)
                 (run-loop-once on? lid)))
    (add-watch trigger lid #(run-loop-once on? lid))))


;; run time

(when (on-screen?)
  (def fps-div (.getElementById js/document "fps"))

  (add-watch fps :fps
             (fn [k a o n]
               (set! (.-innerHTML fps-div) (str "<p>" n "</p>")))))

(when (on-screen?)
  (hookup "a1")
  (hookup "b1")
  (hookup "c1")
  (hookup "d1")
  (hookup "e1")
  (hookup "f1"))

(when (on-screen?)
  (hookupb "a2")
  (hookupb "b2")
  (hookupb "c2")
  (hookupb "d2")
  (hookupb "e2")
  (hookupb "f2"))

