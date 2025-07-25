;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.selection
  "Selection handlers component."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.css-cursors :as cur]
   [app.util.array :as array]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def rotation-handler-size 20)
(def resize-point-radius 4)
(def resize-point-circle-radius 10)
(def resize-point-rect-size 8)
(def resize-side-height 8)
(def selection-rect-color-normal "var(--color-accent-tertiary)")
(def selection-rect-color-component "var(--assets-component-hightlight)")
(def selection-rect-width 1)
(def min-selrect-side 10)
(def small-selrect-side 30)
(def min-selrect-width 10)
(def min-selrect-height 10)

(mf/defc selection-rect
  {::mf/wrap-props false}
  [{:keys [transform rect zoom color on-move-selected on-context-menu]}]
  (let [x      (dm/get-prop rect :x)
        y      (dm/get-prop rect :y)
        width  (dm/get-prop rect :width)
        height (dm/get-prop rect :height)

        ;; This is a calculation to create a "minimum" interactable rect
        ;; Is necesary so that small shapes in x/y (like lines) can be moved
        ;; better
        [x width]
        (if (< width (/ min-selrect-width zoom))
          (let [width' (/ min-selrect-width zoom)]
            [(- x (/ (- width' width) 2)) width'])
          [x width])

        [y height]
        (if (< height (/ min-selrect-height zoom))
          (let [height' (/ min-selrect-height zoom)]
            [(- y (/ (- height' height) 2)) height'])
          [y height])]
    [:rect.main.viewport-selrect
     {:x x
      :y y
      :width (max width (/ 10 zoom))
      :height (max height (/ 10 zoom))
      :transform (str transform)
      :on-pointer-down on-move-selected
      :on-context-menu on-context-menu
      :style {:stroke color
              :stroke-width (/ selection-rect-width zoom)
              :fill "none"}}]))

(defn- calculate-handlers
  "Calculates selection handlers for the current selection."
  [selection shape zoom]
  (let [x                (dm/get-prop selection :x)
        y                (dm/get-prop selection :y)
        width            (dm/get-prop selection :width)
        height           (dm/get-prop selection :height)

        threshold-small  (/ 25 zoom)
        threshold-tiny   (/ 10 zoom)

        small-width?     (<= width threshold-small)
        tiny-width?      (<= width threshold-tiny)

        small-height?    (<= height threshold-small)
        tiny-height?     (<= height threshold-tiny)

        path?            (cfh/path-shape? shape)
        vertical-line?   (and ^boolean path? ^boolean tiny-width?)
        horizontal-line? (and ^boolean path? ^boolean tiny-height?)

        align            (if (or ^boolean small-width? ^boolean small-height?)
                           :outside
                           :inside)

        result           #js [#js {:type :rotation
                                   :position :top-left
                                   :props #js {:cx x :cy y}}

                              #js {:type :rotation
                                   :position :top-right
                                   :props #js {:cx (+ x width) :cy y}}

                              #js {:type :rotation
                                   :position :bottom-right
                                   :props #js {:cx (+ x width) :cy (+ y height)}}

                              #js {:type :rotation
                                   :position :bottom-left
                                   :props #js {:cx x :cy (+ y height)}}]]


    (when-not ^boolean horizontal-line?
      (array/conj! result
                   #js {:type :resize-side
                        :position :top
                        :props #js {:x (if ^boolean small-width?
                                         (+ x (/ (- width threshold-small) 2))
                                         x)
                                    :y y
                                    :length (if ^boolean small-width?
                                              threshold-small
                                              width)
                                    :angle 0
                                    :align align
                                    :show-handler tiny-width?}}
                   #js {:type :resize-side
                        :position :bottom
                        :props #js {:x (if ^boolean small-width?
                                         (+ x (/ (+ width threshold-small) 2))
                                         (+ x width))
                                    :y (+ y height)
                                    :length (if small-width? threshold-small width)
                                    :angle 180
                                    :align align
                                    :show-handler tiny-width?}}))

    (when-not vertical-line?
      (array/conj! result
                   #js {:type :resize-side
                        :position :right
                        :props #js {:x (+ x width)
                                    :y (if small-height? (+ y (/ (- height threshold-small) 2)) y)
                                    :length (if small-height? threshold-small height)
                                    :angle 90
                                    :align align
                                    :show-handler tiny-height?}}

                   #js {:type :resize-side
                        :position :left
                        :props #js {:x x
                                    :y (if ^boolean small-height?
                                         (+ y (/ (+ height threshold-small) 2))
                                         (+ y height))
                                    :length (if ^boolean small-height?
                                              threshold-small
                                              height)
                                    :angle 270
                                    :align align
                                    :show-handler tiny-height?}}))

    (when (and (not tiny-width?) (not tiny-height?))
      (array/conj! result
                   #js {:type :resize-point
                        :position :top-left
                        :props #js {:cx x :cy y :align align}}
                   #js {:type :resize-point
                        :position :top-right
                        :props #js {:cx (+ x width) :cy y :align align}}
                   #js {:type :resize-point
                        :position :bottom-right
                        :props #js {:cx (+ x width) :cy (+ y height) :align align}}
                   #js {:type :resize-point
                        :position :bottom-left
                        :props #js {:cx x :cy (+ y height) :align align}}))))

(mf/defc rotation-handler
  {::mf/wrap-props false}
  [{:keys [cx cy transform position rotation zoom on-rotate] :as props}]
  (let [size    (/ rotation-handler-size zoom)
        delta-x (if (or (= position :top-left)
                        (= position :bottom-left))
                  size
                  0)
        delta-y (if (or (= :top-left position)
                        (= :top-right position))
                  size
                  0)

        x       (- cx delta-x)
        y       (- cy delta-y)
        angle   (case position
                  :top-left 0
                  :top-right 90
                  :bottom-right 180
                  :bottom-left 270)]
    [:rect {:x x
            :y y
            :class (cur/get-dynamic "rotate" (+ rotation angle))
            :width size
            :height size
            :fill (if (dbg/enabled? :handlers) "blue" "none")
            :stroke-width 0
            :transform (dm/str transform)
            :on-pointer-down on-rotate}]))

(mf/defc resize-point-handler
  {::mf/wrap-props false}
  [{:keys [cx cy zoom position on-resize transform rotation color align scale-text]}]
  (let [cursor     (if (or (= position :top-left)
                           (= position :bottom-right))
                     (if ^boolean scale-text
                       (cur/get-dynamic "scale-nesw" rotation)
                       (cur/get-dynamic "resize-nesw" rotation))
                     (if ^boolean scale-text
                       (cur/get-dynamic "scale-nwse" rotation)
                       (cur/get-dynamic "resize-nwse" rotation)))

        pt          (gpt/transform (gpt/point cx cy) transform)
        cx'         (dm/get-prop pt :x)
        cy'         (dm/get-prop pt :y)]

    [:g.resize-handler
     [:circle {:r (/ resize-point-radius zoom)
               :style {:fillOpacity "1"
                       :strokeWidth "1px"
                       :vectorEffect "non-scaling-stroke"}
               :fill "var(--app-white)"
               :stroke color
               :cx cx'
               :cy cy'}]

     (if (= align :outside)
       (let [resize-point-circle-radius (/ resize-point-circle-radius zoom)
             offset-x (if (or (= position :top-right)
                              (= position :bottom-right))
                        0
                        (- resize-point-circle-radius))
             offset-y (if (or (= position :bottom-left)
                              (= position :bottom-right))
                        0
                        (- resize-point-circle-radius))
             cx         (+ cx offset-x)
             cy         (+ cy offset-y)
             pt         (gpt/transform (gpt/point cx cy) transform)
             cx'        (dm/get-prop pt :x)
             cy'        (dm/get-prop pt :y)]
         [:rect {:x cx'
                 :y cy'
                 :data-position (name position)
                 :class cursor
                 :style {:fill (if (dbg/enabled? :handlers) "red" "none")
                         :stroke-width 0}
                 :width resize-point-circle-radius
                 :height resize-point-circle-radius
                 :transform (when (some? rotation)
                              (dm/fmt "rotate(%, %, %)" rotation cx' cy'))
                 :on-pointer-down on-resize}])

       [:circle {:on-pointer-down on-resize
                 :r (/ resize-point-circle-radius zoom)
                 :data-position (name position)
                 :cx cx'
                 :cy cy'
                 :data-x cx'
                 :data-y cy'
                 :class cursor
                 :style {:fill (if (dbg/enabled? :handlers) "red" "none")
                         :stroke-width 0}}])]))

;; The side handler is always rendered horizontally and then rotated
(mf/defc resize-side-handler
  {::mf/wrap-props false}
  [{:keys [x y length align angle zoom position rotation transform on-resize color show-handler scale-text shape-id shape-type]}]
  (let [height        (/ resize-side-height zoom)
        offset-y      (if (= align :outside) (- height) (- (/ height 2)))
        target-y      (+ y offset-y)
        transform-str (dm/str (gmt/multiply transform (gmt/rotate-matrix angle (gpt/point x y))))
        cursor        (if (or (= position :left)
                              (= position :right))
                        (if ^boolean scale-text
                          (cur/get-dynamic "scale-ew" rotation)
                          (cur/get-dynamic "resize-ew" rotation))
                        (if ^boolean scale-text
                          (cur/get-dynamic "scale-ns" rotation)
                          (cur/get-dynamic "resize-ns" rotation)))

        on-double-click
        (mf/use-fn
         (mf/deps shape-id position shape-type)
         (fn [_event]
           (when (= shape-type :text)
             (cond
               (= position :right)
               (st/emit! (dwsh/update-shapes [shape-id] #(assoc % :grow-type :auto-width)))
               (= position :bottom)
               (st/emit! (dwsh/update-shapes [shape-id] #(assoc % :grow-type :auto-height)))))))]

    [:g.resize-handler
     (when ^boolean show-handler
       [:circle {:r (/ resize-point-radius zoom)
                 :style {:fillOpacity 1
                         :stroke color
                         :strokeWidth "1px"
                         :fill "var(--app-white)"
                         :vectorEffect "non-scaling-stroke"}
                 :data-position (name position)
                 :cx (+ x (/ length 2))
                 :cy y
                 :transform transform-str}])
     [:rect {:x x
             :y target-y
             :width length
             :height height
             :class cursor
             :data-position (name position)
             :transform transform-str
             :on-pointer-down on-resize
             :on-double-click on-double-click
             :style {:fill (if (dbg/enabled? :handlers) "yellow" "none")
                     :stroke-width 0}}]]))

(def workspace-selrect-transform
  (l/derived :workspace-selrect st/state))

(defn get-selrect
  [selrect-transform shape]
  (if (some? selrect-transform)
    (let [{:keys [center width height transform]} selrect-transform]
      [(gsh/center->rect center width height)
       (gmt/transform-in center transform)])
    [(dm/get-prop shape :selrect)
     (gsh/transform-matrix shape)]))

(mf/defc controls-selection*
  [{:keys [shape zoom color on-move-selected on-context-menu disabled]}]
  (let [selrect-transform (mf/deref workspace-selrect-transform)
        transform-type    (mf/deref refs/current-transform)
        [selrect transform] (get-selrect selrect-transform shape)]

    (when (and (some? selrect)
               (not (or (= transform-type :move)
                        (= transform-type :rotate))))
      [:g.controls {:pointer-events (if ^boolean disabled "none" "visible")}
       ;; Selection rect
       [:& selection-rect {:rect selrect
                           :transform transform
                           :zoom zoom
                           :color color
                           :on-move-selected on-move-selected
                           :on-context-menu on-context-menu}]])))

(mf/defc controls-handlers*
  {::mf/private true}
  [{:keys [shape zoom color on-resize on-rotate disabled]}]
  (let [selrect-transform (mf/deref workspace-selrect-transform)
        transform-type (mf/deref refs/current-transform)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)

        layout         (mf/deref refs/workspace-layout)
        scale-text?    (contains? layout :scale-text)

        [selrect transform] (get-selrect selrect-transform shape)

        rotation       (-> (gpt/point 1 0)
                           (gpt/transform (:transform shape))
                           (gpt/angle)
                           (mod 360))

        flip-x         (get shape :flip-x)
        flip-y         (get shape :flip-y)
        half-flip?     (or (and flip-x (not flip-y))
                           (and flip-y (not flip-x)))]

    (when (and (not ^boolean read-only?)
               (not (or (= transform-type :move)
                        (= transform-type :rotate))))

      [:g.controls {:pointer-events (if ^boolean disabled "none" "visible")}
       (for [handler (calculate-handlers selrect shape zoom)]
         (let [type     (obj/get handler "type")
               position (obj/get handler "position")
               props    (obj/get handler "props")
               rotation (cond
                          (and ^boolean half-flip?
                               (or (= position :top-left)
                                   (= position :bottom-right)))
                          (- rotation 90)

                          (and ^boolean half-flip?
                               (or (= position :top-right)
                                   (= position :bottom-left)))
                          (+ rotation 90)

                          :else
                          rotation)

               props    (obj/merge!
                         #js {:key (dm/str (name type) "-" (name position))
                              :scale-text scale-text?
                              :zoom zoom
                              :position position
                              :on-rotate on-rotate
                              :on-resize on-resize
                              :transform transform
                              :rotation rotation
                              :color color
                              :shape-id (dm/get-prop shape :id)
                              :shape-type (dm/get-prop shape :type)}
                         props)]
           (case type
             :rotation [:> rotation-handler props]
             :resize-point [:> resize-point-handler props]
             :resize-side [:> resize-side-handler props])))])))

;; --- Selection Handlers (Component)

(mf/defc text-edition-selection
  {::mf/wrap-props false}
  [{:keys [shape color zoom]}]
  (let [x      (dm/get-prop shape :x)
        y      (dm/get-prop shape :y)
        width  (dm/get-prop shape :width)
        height (dm/get-prop shape :height)]
    [:g.controls
     [:rect.main {:x x :y y
                  :transform (gsh/transform-str shape)
                  :width width
                  :height height
                  :pointer-events "visible"
                  :style {:stroke color
                          :stroke-width (/ 0.5 zoom)
                          :stroke-opacity 1
                          :fill "none"}}]]))

(mf/defc multiple-handlers*
  {::mf/private true}
  [{:keys [shapes selected zoom color disabled]}]
  (let [shape (mf/with-memo [shapes]
                (-> shapes
                    (gsh/shapes->rect)
                    (assoc :type :multiple)
                    (cts/setup-shape)))

        on-resize
        (mf/use-fn
         (mf/deps selected shape)
         (fn [event]
           (when (dom/left-mouse? event)
             (dom/stop-propagation event)
             (let [target   (dom/get-current-target event)
                   position (keyword (dom/get-data target "position"))]
               (st/emit! (dw/start-resize position selected shape))))))

        on-rotate
        (mf/use-fn
         (mf/deps shapes)
         (fn [event]
           (when (dom/left-mouse? event)
             (dom/stop-propagation event)
             (st/emit! (dw/start-rotate shapes)))))]

    [:> controls-handlers*
     {:shape shape
      :zoom zoom
      :color color
      :disabled disabled
      :on-resize on-resize
      :on-rotate on-rotate}]))

(mf/defc multiple-selection*
  {::mf/private true}
  [{:keys [shapes zoom color disabled on-move-selected on-context-menu]}]
  (let [shape (mf/with-memo [shapes]
                (-> shapes
                    (gsh/shapes->rect)
                    (assoc :type :multiple)
                    (cts/setup-shape)))]

    [:> controls-selection*
     {:shape shape
      :zoom zoom
      :color color
      :disabled disabled
      :on-move-selected on-move-selected
      :on-context-menu on-context-menu}]))

(mf/defc single-handlers*
  {::mf/private true}
  [{:keys [shape zoom color disabled]}]
  (let [shape-id (dm/get-prop shape :id)
        grow-type (dm/get-prop shape :grow-type)
        shape-type (dm/get-prop shape :type)

        on-resize
        (mf/use-fn
         (mf/deps shape-id shape grow-type shape-type)
         (fn [event]
           (when (dom/left-mouse? event)
             (dom/stop-propagation event)
             (let [target   (dom/get-current-target event)
                   position (-> (dom/get-data target "position")
                                (keyword))]
               (cond
                 ;; If text and in auto-width and the resize is horizontal, switch to auto-height and mark direction
                 (and (= shape-type :text)
                      (= grow-type :auto-width)
                      (or (= position :right) (= position :left)))
                 (st/emit! (dwsh/update-shapes [shape-id] #(-> % (assoc :grow-type :auto-height) (assoc :last-resize-direction :horizontal))))
                 ;; If text and in auto-height and the resize is horizontal, mark direction but do not change grow-type
                 (and (= shape-type :text)
                      (= grow-type :auto-height)
                      (or (= position :right) (= position :left)))
                 (st/emit! (dwsh/update-shapes [shape-id] #(assoc % :last-resize-direction :horizontal)))
                 ;; If text and in auto-height and the resize is vertical, mark direction
                 (and (= shape-type :text)
                      (= grow-type :auto-height)
                      (or (= position :top) (= position :bottom)))
                 (st/emit! (dwsh/update-shapes [shape-id] #(assoc % :last-resize-direction :vertical)))
                 :else
                 nil)
               (st/emit! (dw/start-resize position #{shape-id} shape))))))

        on-rotate
        (mf/use-fn
         (mf/deps shape)
         (fn [event]
           (when (dom/left-mouse? event)
             (dom/stop-propagation event)
             (st/emit! (dw/start-rotate [shape])))))]

    [:> controls-handlers*
     {:shape shape
      :zoom zoom
      :color color
      :disabled disabled
      :on-rotate on-rotate
      :on-resize on-resize}]))

(mf/defc single-selection*
  {::mf/private true}
  [{:keys [shape zoom color disabled on-move-selected on-context-menu]}]
  [:> controls-selection*
   {:shape shape
    :zoom zoom
    :color color
    :disabled disabled
    :on-move-selected on-move-selected
    :on-context-menu on-context-menu}])

(mf/defc area*
  [{:keys [shapes edition zoom disabled on-move-selected on-context-menu]}]
  (let [total    (count shapes)

        shape    (first shapes)
        shape-id (dm/get-prop shape :id)

        ;; Note that we don't use mf/deref to avoid a repaint dependency here
        objects (deref refs/workspace-page-objects)

        color   (if (and (= total 1) ^boolean
                         (or (ctn/in-any-component? objects shape)
                             (ctk/is-variant-container? shape)))
                  selection-rect-color-component
                  selection-rect-color-normal)]

    (cond
      (> total 1)
      [:> multiple-selection*
       {:shapes shapes
        :zoom zoom
        :color color
        :disabled disabled
        :on-move-selected on-move-selected
        :on-context-menu on-context-menu}]

      (and (cfh/text-shape? shape)
           (= edition shape-id))
      [:& text-edition-selection
       {:shape shape
        :zoom zoom
        :color color}]

      (= edition shape-id)
      nil

      :else
      [:> single-selection*
       {:shape shape
        :zoom zoom
        :color color
        :disabled disabled
        :on-move-selected on-move-selected
        :on-context-menu on-context-menu}])))

(mf/defc handlers*
  [{:keys [shapes selected zoom disabled]}]
  (let [total    (count shapes)
        shape    (first shapes)

        ;; Note that we don't use mf/deref to avoid a repaint dependency here
        objects (deref refs/workspace-page-objects)

        color   (if (and (= total 1)
                         ^boolean
                         (or (ctn/in-any-component? objects shape)
                             (ctk/is-variant-container? shape)))
                  selection-rect-color-component
                  selection-rect-color-normal)]

    (if (> total 1)
      [:> multiple-handlers*
       {:shapes shapes
        :selected selected
        :zoom zoom
        :color color
        :disabled disabled}]
      [:> single-handlers*
       {:shape shape
        :zoom zoom
        :color color
        :disabled disabled}])))
