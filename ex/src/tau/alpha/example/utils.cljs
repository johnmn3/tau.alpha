(ns tau.alpha.example.utils)

(def raf
  (when (exists? js/requestAnimationFrame)
    js/requestAnimationFrame))

(defn on-click [el afn]
  (.addEventListener el "click" afn))

(defn actx [astr]
  (let [acanvas (.getElementById js/document astr)
        ctx (.getContext acanvas "2d")
        w (.-width acanvas)
        h (.-height acanvas)
        idata (.getImageData ctx 0 0 w h)
        data {:canvas acanvas
              :ctx ctx
              :width w
              :height h
              :image-data idata}]
    data))
