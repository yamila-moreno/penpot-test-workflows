;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.colors
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.common.types.color :as clr]
   [app.common.types.fills :as types.fills]
   [app.common.types.library :as ctl]
   [app.common.types.shape :as shp]
   [app.common.types.shape.shadow :refer [check-shadow]]
   [app.common.types.text :as txt]
   [app.config :as cfg]
   [app.main.broadcast :as mbc]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as md]
   [app.main.data.workspace.layout :as layout]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; A set of keys that are used for shared state identifiers
(def ^:const colorpicker-selected-broadcast-key ::colorpicker-selected)
(def ^:const colorpalette-selected-broadcast-key ::colorpalette-selected)

(defn show-palette
  "Show the palette tool and change the library it uses"
  [selected]
  (ptk/reify ::show-palette
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (layout/toggle-layout-flag :colorpalette :force? true)
             (mbc/event colorpalette-selected-broadcast-key selected)))

    ptk/EffectEvent
    (effect [_ state _]
      (let [wglobal (:workspace-global state)]
        (layout/persist-layout-state! wglobal)))))

(defn start-picker
  []
  (ptk/reify ::start-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :picking-color?] true)))))

(defn stop-picker
  []
  (ptk/reify ::stop-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-global dissoc :picked-color-select :picked-shift?)
          (assoc-in [:workspace-global :picking-color?] false)))))

(defn pick-color
  [rgba]
  (ptk/reify ::pick-color
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :picked-color] rgba)))))

(defn pick-color-select
  [value shift?]
  (ptk/reify ::pick-color-select
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :picked-color-select] value)
          (assoc-in [:workspace-global :picked-shift?] shift?)))))


(defn- split-text-shapes
  "Split text shapes from non-text shapes"
  [objects ids]
  (loop [ids (seq ids)
         text-ids []
         shape-ids []]
    (if-let [id (first ids)]
      (let [shape (get objects id)]
        (if (cfh/text-shape? shape)
          (recur (rest ids)
                 (conj text-ids id)
                 shape-ids)
          (recur (rest ids)
                 text-ids
                 (conj shape-ids id))))
      [text-ids shape-ids])))

(defn assoc-shape-fill
  [shape position fill]
  (update shape :fills types.fills/assoc position fill))

(defn transform-fill*
  "A lower-level companion function for `transform-fill`"
  [state ids  transform options]
  (let [page-id (or (get options :page-id)
                    (get state :current-page-id))
        objects (dsh/lookup-page-objects state page-id)

        [text-ids shape-ids]
        (split-text-shapes objects ids)]

    (rx/concat
     (->> (rx/from text-ids)
          (rx/map #(dwt/update-text-with-function % transform options)))
     (rx/of (dwsh/update-shapes shape-ids transform options)))))

(defn transform-fill
  "A low level function that creates a shape fill transformations stream"
  ([state ids color transform]
   (transform-fill state ids color transform nil))
  ([state ids color transform options]
   (let [fill
         (cond-> {}
           (contains? color :color)
           (assoc :fill-color (:color color))

           (contains? color :ref-id)
           (assoc :fill-color-ref-id (:ref-id color))

           (contains? color :ref-file)
           (assoc :fill-color-ref-file (:ref-file color))

           (contains? color :gradient)
           (assoc :fill-color-gradient (:gradient color))

           (contains? color :opacity)
           (assoc :fill-opacity (:opacity color))

           (contains? color :image)
           (assoc :fill-image (:image color))

           :always
           (d/without-nils)

           :always
           (types.fills/check-fill))

         transform-attrs
         #(transform % fill)]

     (transform-fill* state ids transform-attrs options))))

(defn swap-attrs [shape attr index new-index]
  (let [first (get-in shape [attr index])
        second (get-in shape [attr new-index])]
    (-> shape
        (assoc-in [attr index] second)
        (assoc-in [attr new-index] first))))

(defn- swap-fills-index
  [fills index new-index]
  (let [first  (get fills index)
        second (get fills new-index)]
    (-> fills
        (assoc index second)
        (assoc new-index first))))

(defn reorder-fills
  [ids index new-index]
  (ptk/reify ::reorder-fills
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects
            (dsh/lookup-page-objects state)

            [text-ids shape-ids]
            (dsh/split-text-shapes objects ids)

            transform-attrs
            (fn [object]
              (update object :fills types.fills/update swap-fills-index index new-index))]

        (rx/concat
         (rx/from (map #(dwt/update-text-with-function % transform-attrs) text-ids))
         (rx/of (dwsh/update-shapes shape-ids transform-attrs)))))))

(defn change-fill
  ([ids color position]
   (change-fill ids color position nil))
  ([ids color position options]
   (assert (every? uuid? ids) "expect a coll of uuids for `ids`")
   (assert (number? position) "expect a number for position")

   (let [color (clr/check-color color)]
     (ptk/reify ::change-fill
       ptk/WatchEvent
       (watch [_ state _]
         (let [change-fn #(assoc-shape-fill %1 position %2)
               undo-id   (js/Symbol)]
           (rx/concat
            (rx/of (dwu/start-undo-transaction undo-id))
            (transform-fill state ids color change-fn options)
            (rx/of (dwu/commit-undo-transaction undo-id)))))))))

(defn change-fill-and-clear
  ([ids color] (change-fill-and-clear ids color nil))
  ([ids color options]
   (ptk/reify ::change-fill-and-clear
     ptk/WatchEvent
     (watch [_ state _]
       (let [change-fn (fn [shape attrs] (assoc shape :fills (types.fills/create attrs)))
             undo-id   (js/Symbol)]
         (rx/concat
          (rx/of (dwu/start-undo-transaction undo-id))
          (transform-fill state ids color change-fn options)
          (rx/of (dwu/commit-undo-transaction undo-id))))))))

(defn add-fill
  ([ids color] (add-fill ids color nil))
  ([ids color options]

   (assert  (every? uuid? ids) "expected a valid coll of uuid's")
   (let [color (clr/check-color color)]
     (ptk/reify ::add-fill
       ptk/WatchEvent
       (watch [_ state _]
         (let [change-fn
               (fn [shape attrs]
                 (update shape :fills types.fills/prepend attrs))
               undo-id
               (js/Symbol)]
           (rx/concat
            (rx/of (dwu/start-undo-transaction undo-id))
            (transform-fill state ids color change-fn options)
            (rx/of (dwu/commit-undo-transaction undo-id)))))))))

(defn detach-fill
  ([ids position] (detach-fill ids position nil))
  ([ids position options]

   (assert (number? position)
           "expected a valid number for position")
   (assert (every? uuid? ids)
           "expected a valid coll of uuid's")

   (ptk/reify ::detach-fill
     ptk/WatchEvent
     (watch [_ state _]
       (let [detach-fn
             (fn [fills index]
               (update fills index dissoc :fill-color-ref-id :fill-color-ref-file))

             change-fn
             ;; The `node` can be a shape or a text content node
             (fn [node]
               (update node :fills types.fills/update detach-fn position))

             undo-id
             (js/Symbol)]

         (rx/concat
          (rx/of (dwu/start-undo-transaction undo-id))
          (transform-fill* state ids change-fn options)
          (rx/of (dwu/commit-undo-transaction undo-id))))))))

(defn remove-fill
  ([ids position] (remove-fill ids position nil))
  ([ids position options]
   (assert (number? position)
           "expected a valid number for position")
   (assert (every? uuid? ids)
           "expected a valid coll of uuid's")

   (ptk/reify ::remove-fill
     ptk/WatchEvent
     (watch [_ state _]
       (let [remove-fill-by-index
             (fn [fills index]
               (into []
                     (comp
                      (map-indexed (fn [i o] (when (not= i index) o)))
                      (filter some?))
                     fills))

             change-fn
             ;; The `node` can be a shape or a text content node
             (fn [node]
               (update node :fills types.fills/update remove-fill-by-index position))

             undo-id
             (js/Symbol)]

         (rx/concat
          (rx/of (dwu/start-undo-transaction undo-id))
          (transform-fill* state ids change-fn options)
          (rx/of (dwu/commit-undo-transaction undo-id))))))))

(defn remove-all-fills
  ([ids] (remove-all-fills ids nil))
  ([ids options]
   (assert (every? uuid? ids) "expected a valid coll of uuid's")

   (ptk/reify ::remove-all-fills
     ptk/WatchEvent
     (watch [_ state _]
       (let [change-fn (fn [node] (assoc node :fills (types.fills/create)))
             undo-id   (js/Symbol)]
         (rx/concat
          (rx/of (dwu/start-undo-transaction undo-id))
          (transform-fill* state ids change-fn options)
          (rx/of (dwu/commit-undo-transaction undo-id))))))))

(defn change-hide-fill-on-export
  [ids hide-fill-on-export]
  (ptk/reify ::change-hide-fill-on-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id   (:current-page-id state)
            objects   (dsh/lookup-page-objects state page-id)
            is-text?  #(= :text (:type (get objects %)))
            shape-ids (filter (complement is-text?) ids)
            attrs {:hide-fill-on-export hide-fill-on-export}]
        (rx/of (dwsh/update-shapes shape-ids (fn [shape]
                                               (if (= (:type shape) :frame)
                                                 (d/merge shape attrs)
                                                 shape))))))))

(def ^:private stroke-style-attrs
  [:stroke-style
   :stroke-alignment
   :stroke-width
   :stroke-cap-start
   :stroke-cap-end])

;; FIXME: this function initializes an empty stroke, maybe we can move
;; it to common.types
(defn- build-stroke-style-attrs
  [stroke]
  (let [attrs (select-keys stroke stroke-style-attrs)]
    (cond-> attrs
      (not (contains? attrs :stroke-width))
      (assoc :stroke-width 1)

      (not (contains? attrs :stroke-style))
      (assoc :stroke-style :solid)

      (not (contains? attrs :stroke-alignment))
      (assoc :stroke-alignment :inner)

      :always
      (d/without-nils))))

(defn update-shape-stroke-color
  "Given a shape, update color attributes on the stroke on the specified
  `position`; if no stroke is found a new empty stroke is created."
  [shape position color]
  (update shape :strokes
          (fn [strokes]
            (let [stroke (if (nil? strokes)
                           (build-stroke-style-attrs nil)
                           (build-stroke-style-attrs (get strokes position)))
                  stroke (cond-> (build-stroke-style-attrs stroke)
                           (contains? color :color)
                           (assoc :stroke-color (:color color))

                           (contains? color :ref-id)
                           (assoc :stroke-color-ref-id (:ref-id color))

                           (contains? color :ref-file)
                           (assoc :stroke-color-ref-file (:ref-file color))

                           (contains? color :gradient)
                           (assoc :stroke-color-gradient (:gradient color))

                           (contains? color :opacity)
                           (assoc :stroke-opacity (:opacity color))

                           (contains? color :image)
                           (assoc :stroke-image (:image color))

                           :always
                           (d/without-nils))]
              (if (nil? strokes)
                [stroke]
                (assoc strokes position stroke))))))

(defn change-stroke-color
  ([ids color index] (change-stroke-color ids color index nil))
  ([ids color index options]
   (ptk/reify ::change-stroke-color
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of (let [options (assoc options :changed-sub-attr [:stroke-color])]
                (dwsh/update-shapes ids #(update-shape-stroke-color % index color) options)))))))

(defn change-stroke-attrs
  ([ids attrs index] (change-stroke-attrs ids attrs index nil))
  ([ids attrs index options]
   (ptk/reify ::change-stroke-attrs
     ptk/WatchEvent
     (watch [_ _ _]
       (let [changed-sub-attr (keys attrs)
             options (assoc options :changed-sub-attr changed-sub-attr)]
         (rx/of (dwsh/update-shapes
                 ids
                 (fn [shape]
                   (let [stroke      (get-in shape [:strokes index])
                         style-attrs (build-stroke-style-attrs stroke)
                         attrs       (merge stroke style-attrs attrs)]
                     (cond-> shape
                       (not (contains? shape :strokes))
                       (assoc :strokes [])

                       :always
                       (assoc-in [:strokes index] attrs))))
                 options)))))))

(defn change-shadow
  [ids attrs index]
  (ptk/reify ::change-shadow
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsh/update-shapes
              ids
              (fn [shape]
                (let [;; If we try to set a gradient to a shadow (for
                      ;; example using the color selection from
                      ;; multiple shapes) let's use the first stop
                      ;; color
                      attrs  (cond-> attrs
                               (:gradient attrs)
                               (dm/get-in [:gradient :stops 0]))

                      attrs' (-> (dm/get-in shape [:shadow index :color])
                                 (merge attrs)
                                 (d/without-nils))]
                  (assoc-in shape [:shadow index :color] attrs'))))))))

(defn add-shadow
  [ids shadow]

  (assert
   (check-shadow shadow)
   "expected a valid shadow struct")

  (assert
   (every? uuid? ids)
   "expected a valid coll of uuid's")

  (ptk/reify ::add-shadow
    ptk/WatchEvent
    (watch [_ _ _]
      (let [add-shadow (fn [shape]
                         (update shape :shadow #(into [shadow] %)))]
        (rx/of (dwsh/update-shapes ids add-shadow))))))

(defn add-stroke
  [ids stroke]

  (assert
   (shp/check-stroke stroke)
   "expected a valid stroke struct")

  (assert
   (every? uuid? ids)
   "expected a valid coll of uuid's")


  (ptk/reify ::add-stroke
    ptk/WatchEvent
    (watch [_ _ _]
      (let [add-stroke (fn [shape] (update shape :strokes #(into [stroke] %)))]
        (rx/of (dwsh/update-shapes ids
                                   add-stroke
                                   {:attrs [:strokes]}))))))

(defn remove-stroke
  [ids position]

  (assert
   (every? uuid? ids)
   "expected a valid coll of uuid's")

  (ptk/reify ::remove-stroke
    ptk/WatchEvent
    (watch [_ _ _]
      (letfn [(remove-fill-by-index [values index]
                (->> (d/enumerate values)
                     (filterv (fn [[idx _]] (not= idx index)))
                     (mapv second)))
              (remove-stroke [shape]
                (update shape :strokes remove-fill-by-index position))]
        (rx/of (dwsh/update-shapes ids
                                   remove-stroke
                                   {:attrs [:strokes]}))))))

(defn remove-all-strokes
  [ids]

  (assert
   (every? uuid? ids)
   "expected a valid coll of uuid's")


  (ptk/reify ::remove-all-strokes
    ptk/WatchEvent
    (watch [_ _ _]
      (let [remove-all #(assoc % :strokes [])]
        (rx/of (dwsh/update-shapes ids
                                   remove-all
                                   {:attrs [:strokes]}))))))

(defn reorder-shadows
  [ids index new-index]
  (ptk/reify ::reorder-shadow
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsh/update-shapes
              ids
              #(swap-attrs % :shadow index new-index))))))

(defn reorder-strokes
  [ids index new-index]
  (ptk/reify ::reorder-strokes
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsh/update-shapes
              ids
              #(swap-attrs % :strokes index new-index)
              {:attrs [:strokes]})))))

(defn picker-for-selected-shape
  []
  (let [sub (rx/subject)]
    (ptk/reify ::picker-for-selected-shape
      ptk/WatchEvent
      (watch [_ state stream]
        (let [ids   (dsh/lookup-selected state)
              stop? (rx/filter (ptk/type? ::stop-picker) stream)

              update-events
              (fn [color]
                (rx/of (change-fill ids color 0)))]

          (rx/merge
           ;; Stream that updates the stroke/width and stops if `esc` pressed
           (->> sub
                (rx/take-until stop?)
                (rx/merge-map update-events))

           ;; Hide the modal if the stop event is emitted
           (->> stop?
                (rx/take 1)
                (rx/map #(md/hide))))))

      ptk/UpdateEvent
      (update [_ state]
        (let [handle-change-color (fn [color] (rx/push! sub color))]
          (-> state
              (assoc-in [:workspace-global :picking-color?] true)
              (assoc ::md/modal {:id (random-uuid)
                                 :type :colorpicker
                                 :props {:data {:color clr/black
                                                :opacity 1}
                                         :disable-opacity false
                                         :disable-gradient false
                                         :on-change handle-change-color}
                                 :allow-click-outside true})))))))

(defn- color-att->text
  [color]
  (d/without-nils
   {:fill-color (when (:color color) (str/lower (:color color)))
    :fill-opacity (:opacity color)
    :fill-color-ref-id (:ref-id color)
    :fill-color-ref-file (:ref-file color)
    :fill-color-gradient (:gradient color)}))

(defn- change-text-color
  [old-color new-color index node]
  (update node :fills types.fills/update
          (fn [fills]
            (let [fills'
                  (map #(dissoc % :fill-color-ref-id :fill-color-ref-file) fills)

                  parsed-color
                  (-> (color-att->text old-color)
                      (dissoc :fill-color-ref-id :fill-color-ref-file))

                  parsed-new-color
                  (color-att->text new-color)

                  has-color?
                  (d/index-of fills' parsed-color)]

              (cond-> fills
                (some? has-color?)
                (assoc index parsed-new-color))))))

(def ^:private schema:change-color-operation
  [:map
   [:prop [:enum :fill :stroke :shadow :content]]
   [:shape-id ::sm/uuid]
   [:index :int]])

(def ^:private schema:change-color-operations
  [:vector schema:change-color-operation])

(def ^:private check-change-color-operations
  (sm/check-fn schema:change-color-operations))

(defn change-color-in-selected
  [operations new-color old-color]
  (assert (check-change-color-operations operations))
  (assert (clr/check-color new-color))
  (assert (clr/check-color old-color))

  (ptk/reify ::change-color-in-selected
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (->> (rx/from operations)
              (rx/map (fn [{:keys [shape-id index] :as operation}]
                        (case (:prop operation)
                          :fill    (change-fill [shape-id] new-color index)
                          :stroke  (change-stroke-color [shape-id] new-color index)
                          :shadow  (change-shadow [shape-id] new-color index)
                          :content (dwt/update-text-with-function
                                    shape-id
                                    (partial change-text-color old-color new-color index))))))
         (rx/of (dwu/commit-undo-transaction undo-id)))))))

(defn apply-color-from-palette
  [color stroke?]
  (let [color (clr/check-color color)]
    (ptk/reify ::apply-color-from-palette
      ptk/WatchEvent
      (watch [_ state _]
        (let [objects  (dsh/lookup-page-objects state)
              selected (->> (dsh/lookup-selected state)
                            (cfh/clean-loops objects))

              ids
              (loop [pending (seq selected)
                     result []]
                (if (empty? pending)
                  result
                  (let [cur (first pending)
                        group? (cfh/group-shape? objects cur)

                        pending
                        (if group?
                          (concat pending (dm/get-in objects [cur :shapes]))
                          pending)

                        result (cond-> result (not group?) (conj cur))]
                    (recur (rest pending) result))))]

          (if stroke?
            (rx/of (change-stroke-color ids color 0))
            (rx/of (change-fill ids color 0))))))))

(declare activate-colorpicker-color)
(declare activate-colorpicker-gradient)
(declare activate-colorpicker-image)
(declare update-colorpicker)

(defn apply-color-from-colorpicker
  [color]
  (let [color (clr/check-color color)]
    (ptk/reify ::apply-color-from-colorpicker
      ptk/UpdateEvent
      (update [_ state]
        (update state :colorpicker
                (fn [state]
                  (cond
                    (:image color)
                    (-> state
                        (assoc :type :image)
                        (dissoc :editing-stop :stops :gradient))

                    (:color color)
                    (-> state
                        (assoc :type :color)
                        (dissoc :editing-stop :stops :gradient))

                    :else
                    (let [gradient-type (dm/get-in color [:gradient :type])]
                      (cond
                        (= :linear gradient-type)
                        (-> state
                            (assoc :type :linear-gradient)
                            (assoc :editing-stop 0)
                            (update :current-color dissoc :image))

                        (= :radial gradient-type)
                        (-> state
                            (assoc :type :radial-gradient)
                            (assoc :editing-stop 0)
                            (update :current-color dissoc :image)))))))))))

(defn- recent-color-equal?
  [c1 c2]
  (or (= c1 c2)
      (and (some? (:color c1))
           (some? (:color c2))
           (= (:color c1) (:color c2)))))

(defn add-recent-color
  [color]
  (let [color (clr/check-color color)]
    (ptk/reify ::add-recent-color
      ptk/UpdateEvent
      (update [_ state]
        (let [file-id (:current-file-id state)]
          (update-in state [:recent-colors file-id]
                     (fn [colors]
                       (let [colors (d/removev (partial recent-color-equal? color) colors)
                             colors (conj colors color)]
                         (cond-> colors
                           (> (count colors) 15)
                           (subvec 1)))))))

      ptk/EffectEvent
      (effect [_ state _]
        (let [recent-colors (:recent-colors state)]
          (swap! storage/user assoc :recent-colors recent-colors))))))

(defn apply-color-from-assets
  [file-id color stroke?]
  (let [color (clr/check-library-color color)]
    (ptk/reify ::apply-color-from-asserts
      ptk/WatchEvent
      (watch [_ _ _]
        (let [color (clr/library-color->color color file-id)]
          (rx/of (apply-color-from-palette color stroke?)
                 (add-recent-color color)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COLORPICKER STATE MANAGEMENT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn split-color-components
  [{:keys [color opacity] :as data}]
  (let [value (if (clr/valid-hex-color? color) color clr/black)
        [r g b] (clr/hex->rgb value)
        [h s v] (clr/hex->hsv value)]
    (merge data
           {:hex (or value "000000")
            :alpha (or opacity 1)
            :r r :g g :b b
            :h h :s s :v v})))

(defn materialize-color-components
  [{:keys [hex alpha] :as data}]
  (-> data
      (assoc :color hex)
      (assoc :opacity alpha)
      (d/without-nils)))

(defn clear-color-components
  [data]
  (dissoc data :hex :alpha :r :g :b :h :s :v :image))

(defn clear-image-components
  [data]
  (dissoc data :hex :alpha :r :g :b :h :s :v :color))

(defn- create-gradient
  [type]
  {:start-x 0.5
   :start-y (if (= type :linear-gradient) 0.0 0.5)
   :end-x   0.5
   :end-y   1
   :width  1.0})

(defn get-color-from-colorpicker-state
  [{:keys [type current-color stops gradient opacity] :as state}]
  (cond
    (= type :color)
    (-> (clear-color-components current-color)
        (dissoc :offset))

    (= type :image)
    (clear-image-components current-color)

    :else
    (d/without-nils
     {:opacity opacity
      :gradient (-> gradient
                    (assoc :type (case type
                                   :linear-gradient :linear
                                   :radial-gradient :radial))
                    (assoc :stops (mapv clear-color-components stops))
                    (dissoc :shape-id))})))

(defn- colorpicker-onchange-runner
  "Effect event that runs the on-change callback with the latest
  colorpicker state converted to color object."
  [on-change]
  (ptk/reify ::colorpicker-onchange-runner
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [color (some-> state :colorpicker get-color-from-colorpicker-state)]
        (on-change color)))))

(defn initialize-colorpicker
  [on-change tab]
  (ptk/reify ::initialize-colorpicker
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/merge
                     (rx/filter (ptk/type? ::finalize-colorpicker) stream)
                     (rx/filter (ptk/type? ::initialize-colorpicker) stream))]
        (->> (rx/merge
              (->> stream
                   (rx/filter (ptk/type? ::update-colorpicker-gradient))
                   (rx/debounce 20))
              (rx/filter (ptk/type? ::update-colorpicker-color) stream)
              (->> (rx/filter (ptk/type? ::activate-colorpicker-gradient) stream)
                   (rx/debounce 20))
              (rx/filter (ptk/type? ::update-colorpicker-stops) stream)
              (rx/filter (ptk/type? ::update-colorpicker-gradient-opacity) stream)
              (rx/filter (ptk/type? ::update-colorpicker-add-stop) stream)
              (rx/filter (ptk/type? ::update-colorpicker-add-auto) stream)
              (rx/filter (ptk/type? ::remove-gradient-stop) stream))
             (rx/debounce 40)
             (rx/map (constantly (colorpicker-onchange-runner on-change)))
             (rx/take-until stopper))))

    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (-> state
                    (assoc :type tab)))))))

(defn finalize-colorpicker
  []
  (ptk/reify ::finalize-colorpicker
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :colorpicker))))

(defn update-colorpicker
  [{:keys [gradient] :as data}]
  (ptk/reify ::update-colorpicker
    ptk/UpdateEvent
    (update [_ state]
      (let [shape-id (-> state dsh/lookup-selected first)]
        (update state :colorpicker
                (fn [state]
                  (let [current-color (:current-color state)]
                    (if (some? gradient)
                      (let [stop  (or (:editing-stop state) 0)
                            new-stops (mapv split-color-components (:stops gradient))
                            new-gradient (-> gradient
                                             (dissoc :stops)
                                             (assoc :shape-id shape-id))]
                        (if (and (= (:stops state) new-stops) (= (:gradient state) new-gradient))
                          state
                          (-> state
                              (assoc :opacity (:opacity data))
                              (assoc :current-color (get new-stops stop))
                              (assoc :stops new-stops)
                              (assoc :gradient new-gradient)
                              (assoc :editing-stop stop))))

                      (-> state
                          (cond-> (or (nil? current-color)
                                      (not= (:color data) (:color current-color))
                                      (not= (:opacity data) (:opacity current-color)))
                            (assoc :current-color (split-color-components (dissoc data :gradient))))
                          (dissoc :editing-stop)
                          (dissoc :gradient)
                          (dissoc :stops))))))))))

(defn update-colorpicker-gradient-opacity
  [opacity]
  (ptk/reify ::update-colorpicker-gradient-opacity
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (-> state
                    (assoc :opacity opacity)))))))

(defn update-colorpicker-add-auto
  []
  (ptk/reify ::update-colorpicker-add-auto
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [{:keys [stops editing-stop] :as state}]
                (let [cap-stops? (or (features/active-feature? state "render-wasm/v1") (contains? cfg/flags :frontend-binary-fills))
                      can-add-stop? (or (not cap-stops?) (< (count stops) types.fills/MAX-GRADIENT-STOPS))]
                  (if can-add-stop?
                    (if (clr/uniform-spread? stops)
                      ;; Add to uniform
                      (let [stops (->> (clr/uniform-spread (first stops) (last stops) (inc (count stops)))
                                       (mapv split-color-components))]
                        (-> state
                            (assoc :current-color (get stops editing-stop))
                            (assoc :stops stops)))

                      ;; We add the stop to the middle point between the selected
                      ;; and the next one.
                      ;; If the last stop is selected then it's added between the
                      ;; last two stops.
                      (let [index
                            (if (= editing-stop (dec (count stops)))
                              (dec editing-stop)
                              editing-stop)

                            {from-offset :offset} (get stops index)
                            {to-offset :offset}   (get stops (inc index))

                            half-point-offset
                            (+ from-offset (/ (- to-offset from-offset) 2))

                            new-stop (-> (clr/interpolate-gradient stops half-point-offset)
                                         (split-color-components))

                            stops (conj stops new-stop)
                            stops (into [] (sort-by :offset stops))
                            editing-stop (d/index-of-pred stops #(= new-stop %))]
                        (-> state
                            (assoc :editing-stop editing-stop)
                            (assoc :current-color (get stops editing-stop))
                            (assoc :stops stops))))
                    state)))))))

(defn update-colorpicker-add-stop
  [offset]
  (ptk/reify ::update-colorpicker-add-stop
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (let [stops (:stops state)
                      cap-stops?
                      (or (features/active-feature? state "render-wasm/v1")
                          (contains? cfg/flags :frontend-binary-fills))

                      can-add-stop?
                      (or (not cap-stops?) (< (count stops) types.fills/MAX-GRADIENT-STOPS))]

                  (if can-add-stop?
                    (let [new-stop (-> (clr/interpolate-gradient stops offset)
                                       (split-color-components))
                          stops (conj stops new-stop)
                          stops (into [] (sort-by :offset stops))
                          editing-stop (d/index-of-pred stops #(= new-stop %))]
                      (-> state
                          (assoc :editing-stop editing-stop)
                          (assoc :current-color (get stops editing-stop))
                          (assoc :stops stops)))
                    state)))))))

(defn update-colorpicker-stops
  [stops]
  (ptk/reify ::update-colorpicker-stops
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (let [stop  (or (:editing-stop state) 0)
                      cap-stops? (or (features/active-feature? state "render-wasm/v1")
                                     (contains? cfg/flags :frontend-binary-fills))
                      stops (mapv split-color-components
                                  (if cap-stops?
                                    (take types.fills/MAX-GRADIENT-STOPS stops)
                                    stops))]
                  (-> state
                      (assoc :current-color (get stops stop))
                      (assoc :stops stops))))))))

(defn sort-colorpicker-stops
  []
  (ptk/reify ::sort-colorpicker-stops
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (let [stop     (or (:editing-stop state) 0)
                      stops    (mapv split-color-components (:stops state))
                      stop-val (get stops stop)
                      stops    (into [] (sort-by :offset stops))
                      stop     (d/index-of-pred stops #(= % stop-val))]
                  (-> state
                      (assoc :editing-stop stop)
                      (assoc :stops stops))))))))

(defn remove-gradient-stop
  ([]
   (remove-gradient-stop nil))

  ([index]
   (ptk/reify ::remove-gradient-stop
     ptk/UpdateEvent
     (update [_ state]
       (update state :colorpicker
               (fn [{:keys [editing-stop stops] :as state}]
                 (if (> (count stops) 2)
                   (let [delete-index (or index editing-stop 0)
                         delete-stop  (get stops delete-index)
                         stops (into [] (remove #(= delete-stop %)) stops)

                         editing-stop
                         (cond
                           (< editing-stop delete-index) editing-stop
                           (> editing-stop delete-index) (dec editing-stop)
                           (>= (count stops) editing-stop) (dec (count stops))
                           :else editing-stop)]
                     (-> state
                         (assoc :editing-stop editing-stop)
                         (assoc :stops stops)))

                   ;; Cannot delete
                   state)))))))

(defn update-colorpicker-color
  [changes add-recent?]
  (ptk/reify ::update-colorpicker-color
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (let [type (:type state)
                      state (-> state
                                (update :current-color merge changes)
                                (update :current-color materialize-color-components)
                                (update :current-color #(if (not= type :image) (dissoc % :image) %))
                                ;; current color can be a library one
                                ;; I'm changing via colorpicker
                                (update :current-color dissoc :ref-id :ref-file))]
                  (if-let [stop (:editing-stop state)]
                    (update-in state [:stops stop] (fn [data] (->> changes
                                                                   (merge data)
                                                                   (materialize-color-components))))

                    (-> state
                        (dissoc :gradient :stops :editing-stop)
                        (cond-> (not= :image type)
                          (assoc :type :color))))))))
    ptk/WatchEvent
    (watch [_ state _]
      (let [state (get-color-from-colorpicker-state (:colorpicker state))
            type  (get state :type)

            ;; Type is set to color on closing the colorpicker, but we
            ;; can can close it while still uploading an image fill
            ignore-color?
            (and (= type :color) (nil? (:color state)))]

        (when (and add-recent? (not ignore-color?))
          (let [color (select-keys state [:image :gradient :color :opacity])]
            (rx/of (add-recent-color color))))))))

(defn update-colorpicker-gradient
  [changes]
  (ptk/reify ::update-colorpicker-gradient
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:colorpicker :gradient] merge changes))))

(defn select-colorpicker-gradient-stop
  [index]
  (ptk/reify ::select-colorpicket-gradient-stop
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (if-let [color (get-in state [:stops index])]
                  (assoc state
                         :current-color color
                         :editing-stop index)
                  state))))))

(defn activate-colorpicker-color
  []
  (ptk/reify ::activate-colorpicker-color
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (-> state
                    (assoc :type :color)
                    (dissoc :editing-stop :stops :gradient)))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (update-colorpicker-color {} false)))))

(defn activate-colorpicker-gradient
  [type]
  (ptk/reify ::activate-colorpicker-gradient
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (let [gradient (create-gradient type)
                      color    (:current-color state)]
                  (-> state
                      (assoc :type type)
                      (assoc :gradient gradient)
                      (d/dissoc-in [:current-color :image])
                      (cond-> (not (:stops state))
                        (assoc :editing-stop 0
                               :stops  [(-> color
                                            (assoc :offset 0)
                                            (materialize-color-components))
                                        (-> color
                                            (assoc :alpha 0)
                                            (assoc :offset 1)
                                            (materialize-color-components))])))))))))

(defn activate-colorpicker-image
  []
  (ptk/reify ::activate-colorpicker-image
    ptk/UpdateEvent
    (update [_ state]
      (update state :colorpicker
              (fn [state]
                (-> state
                    (assoc :type :image)
                    (dissoc :editing-stop :stops :gradient)))))))

(defn- stroke->color-att
  [stroke file-id libraries]
  (let [ref-file   (:stroke-color-ref-file stroke)
        ref-id     (:stroke-color-ref-id stroke)

        colors     (-> libraries
                       (get ref-file)
                       (get :data)
                       (ctl/get-colors))

        is-shared? (contains? colors ref-id)
        has-color? (or (:stroke-color stroke)
                       (:stroke-color-gradient stroke))
        attrs      (cond-> (clr/stroke->color stroke)
                     (not (or is-shared? (= ref-file file-id)))
                     (dissoc :ref-id :ref-file))]

    (when has-color?
      {:attrs attrs
       :prop :stroke
       :shape-id (:shape-id stroke)
       :index (:index stroke)})))

(defn- shadow->color-att
  [shadow file-id libraries]
  (let [color    (get shadow :color)
        ref-file (get color :ref-file)
        ref-id   (get color :ref-id)
        colors   (-> libraries
                     (get ref-id)
                     (get :data)
                     (ctl/get-colors))
        shared?  (contains? colors ref-id)
        attrs    (cond-> (clr/shadow->color shadow)
                   (not (or shared? (= ref-file file-id)))
                   (dissoc :ref-file :ref-id))]
    {:attrs attrs
     :prop :shadow
     :shape-id (:shape-id shadow)
     :index (:index shadow)}))

(defn- text->color-att
  [fill file-id libraries]
  (let [ref-file (:fill-color-ref-file fill)
        ref-id   (:fill-color-ref-id fill)
        colors   (-> libraries
                     (get ref-id)
                     (get :data)
                     (ctl/get-colors))

        shared?  (contains? colors ref-id)
        attrs    (cond-> (types.fills/fill->color fill)
                   (not (or shared? (= ref-file file-id)))
                   (dissoc :ref-file :ref-id))]

    {:attrs attrs
     :prop :content
     :shape-id (:shape-id fill)
     :index (:index fill)}))

(defn- extract-text-colors
  [text file-id libraries]
  (let [treat-node
        (fn [node shape-id]
          (map-indexed #(assoc %2 :shape-id shape-id :index %1) node))]
    (->> (txt/node-seq txt/is-text-node? (:content text))
         (map :fills)
         (mapcat #(treat-node % (:id text)))
         (map #(text->color-att % file-id libraries)))))

(defn- fill->color-att
  [fill file-id libraries]
  (let [ref-file   (:fill-color-ref-file fill)
        ref-id     (:fill-color-ref-id fill)

        colors     (-> libraries
                       (get ref-id)
                       (get :data)
                       (ctl/get-colors))
        shared?    (contains? colors ref-id)
        has-color? (or (:fill-color fill)
                       (:fill-color-gradient fill))
        attrs      (cond-> (types.fills/fill->color fill)
                     (not (or shared? (= ref-file file-id)))
                     (dissoc :ref-file :ref-id))]

    (when has-color?
      {:attrs attrs
       :prop :fill
       :shape-id (:shape-id fill)
       :index (:index fill)})))

(defn extract-all-colors
  [shapes file-id libraries]
  (reduce
   (fn [result shape]
     (let [fill-obj   (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:fills shape))
           stroke-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:strokes shape))
           shadow-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:shadow shape))]
       (if (= :text (:type shape))
         (-> result
             (into (keep #(stroke->color-att % file-id libraries)) stroke-obj)
             (into (map #(shadow->color-att % file-id libraries)) shadow-obj)
             (into (extract-text-colors shape file-id libraries)))

         (-> result
             (into (keep #(fill->color-att % file-id libraries)) fill-obj)
             (into (keep #(stroke->color-att % file-id libraries)) stroke-obj)
             (into (map #(shadow->color-att % file-id libraries)) shadow-obj)))))
   []
   shapes))
