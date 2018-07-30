(ns tau.alpha.example.core
  (:require-macros [tau.alpha.macros :refer [log on future later]])
  (:require [tau.alpha.core :refer [set-conf! on-screen? tauon tau db reconstruct-tau get-id]]
            [tau.alpha.example.automata :refer [get-frame make-initial-conditions]]
            [tau.alpha.example.utils :refer [raf on-click actx]]
            [tau.alpha.example.fps :refer [fps]]))

(set-conf! {:main "tau.alpha.example.core"
            :log? true})

(defn paint-automaton [ctx width height iref]
  (let [idata (.createImageData ctx width height)]
    (.set (.-data idata) @iref)
    (.putImageData ctx idata 0 0 0 0 width height)))

;; fps

(when (on-screen?)
  (def fps-div (.getElementById js/document "fps"))

  (add-watch fps :fps
             (fn [k a o n]
               (set! (.-innerHTML fps-div) (str "<p>" n "</p>")))))

;; On main thread

(defn start [on? state ctx width height]
  (when @on?
    (raf
     #(do (swap! state get-frame)
        (paint-automaton ctx width height state)
        (start on? state ctx width height)))))

(defn setup [ctx width]
  (let [state (atom (make-initial-conditions width))
        on? (atom false)]
    (set! (.-fillColor ctx) "#ffffff")
    {:state state :on? on?}))

(defn hookup [astr]
  (let [{:keys [ctx width height canvas]} (actx astr)
        {:keys [state on?]} (setup ctx width)]
    (on-click canvas
              #(do (swap! on? not)
                   (start on? state ctx width height)))))

(when (on-screen?)
  (hookup "a1")
  (hookup "b1")
  (hookup "c1")
  (hookup "d1")
  (hookup "e1")
  (hookup "f1"))

;;; On tauons

(def local-store (atom {}))

(defn get-automaton [tau]
  (get @local-store tau))

(defn process-results [tau]
  (raf
   #(let [{:keys [ctx width height on?]} (get-automaton tau)]
      (paint-automaton ctx width height tau)
      (swap! on? (constantly @on?)))))

(defn swap-on! [tauon tau work-fn]
  (on tauon [tau work-fn]
    (swap! tau work-fn)
    (on "screen" [tau]
      (process-results tau))))

(defn run-loop-once [on? tau]
  (when @on?
    (let [{:keys [tauon]} (get-automaton tau)]
      (swap-on! tauon tau get-frame))))

(defn setup-tauon [{:keys [width] :as automaton}]
  (let [tauon (tauon)
        tau (tau (make-initial-conditions width) :ab true :size (* width width 4))]
    (swap! local-store assoc tau
           (merge automaton {:on? (atom false) :tauon tauon :tau tau}))
    (get-automaton tau)))

(defn start-automaton [on? tau]
  (swap! on? not)
  (run-loop-once on? tau))

(defn hookup-automaton [astr]
  (let [{:keys [canvas] :as automaton} (actx astr)
        {:keys [tau on?]} (setup-tauon automaton)]
    (on-click canvas #(start-automaton on? tau))
    (add-watch on? tau #(run-loop-once on? tau))))

(when (on-screen?)
  (hookup-automaton "a2")
  (hookup-automaton "b2")
  (hookup-automaton "c2")
  (hookup-automaton "d2")
  (hookup-automaton "e2")
  (hookup-automaton "f2"))
