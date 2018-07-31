(ns tau.alpha.example.automata)

(defn make-initial-conditions [width]
  (let [w (* 4 width)
        size (dec (* w width))
        a (js/Array. size)
        b (js/Uint8ClampedArray. (amap a i _ (if (= 0 (mod (inc i) 4)) 255 0)))]
    (.set b
          #js [255 255 255 255]
          (int (* width 2)))
    b))


(def white (js/Uint8ClampedArray. [0   0   0 255]))
(def black (js/Uint8ClampedArray. [255 255 255 255]))

(defn make-rule [v1 v2 v3 v4 v5 v6 v7 v8]
  {[255 255 255] ({0 white 1 black} v1)
   [255 255   0] ({0 white 1 black} v2)
   [255   0 255] ({0 white 1 black} v3)
   [255   0   0] ({0 white 1 black} v4)
   [0 255 255] ({0 white 1 black} v5)
   [0 255   0] ({0 white 1 black} v6)
   [0   0 255] ({0 white 1 black} v7)
   [0   0   0] ({0 white 1 black} v8)})

(defn wrap-sub [the-state the-index]
  (let [size (.-length the-state)
        norm (+ the-index (* 4 size))]
    (->> [-3 0 4]
         (map #(aget the-state (rem (+ norm %) size)))
         (vec))))

(defn make-stepper [the-rule width]
  (fn [state]
    (let [width (dec (* 4 width))
          x (- (.-length state) width 4)]
      (loop [the-state state i 0]
        (when (< i x)
          (let [pre (wrap-sub the-state i)
                res (the-rule pre)]
            (.set the-state res (+ width 1 i))
            (recur the-state (+ i 4))))))
    ;(println "width:" width)
    (let [r (* 4 width width)
          l (- r (* 4 width))
          sl (.slice state l r)]
      (.set state sl 0))
    (js/Uint8ClampedArray. state)))

(def rule30 (make-rule 0 0 0 1 1 1 1 0))

(def rule-30-stepper (make-stepper rule30 256))

(defn get-frame [a] (rule-30-stepper a))
