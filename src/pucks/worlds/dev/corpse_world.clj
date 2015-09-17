(ns pucks.worlds.dev.corpse-world 
  (:use [pucks core globals]
        [pucks.agents vent nursery victim corpse barbarian]))

(defn agents []
  (vec (concat 
         (for [x (range 100 1901 400)
               y (range 100 1901 400)]
           (assoc (vent) :position [x y]))
         (for [x (range 200 1901 400)
               y (range 200 1901 400)]
           (assoc (nursery victim) :position [x y]))
         (for [x (range 300 1901 400)
               y (range 300 1901 400)]
           (assoc (nursery barbarian) :position [x y])))))

(defn settings []
  {:screen-size 2000
   :scale 0.5
   :single-thread-mode false
   :nursery-threshold 50})

;(run-pucks (agents) (settings))