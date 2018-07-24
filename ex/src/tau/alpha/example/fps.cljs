(ns tau.alpha.example.fps
  (:require [tau.alpha.example.utils :refer [raf]]))

(defn fps-mon [times fps]
  (raf
   (fn []
     (let [now (js/performance.now)]
       (while (and (> (.-length @times) 0) (<= (aget @times 0) (- now 1000)))
         (swap! times #(do (.shift %) %)))
       (swap! times #(do (.push % now) %))
       (reset! fps (.-length @times))
       (fps-mon times fps)))))

(defn fps-atom []
  (when raf
    (let [times (atom #js [])
          fps (atom 0)]
      (fps-mon times fps)
      fps)))

(def fps (fps-atom))

