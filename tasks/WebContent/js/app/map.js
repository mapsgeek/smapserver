/*
 This file is part of SMAP.

 SMAP is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.
 uSMAP is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with SMAP.  If not, see <http://www.gnu.org/licenses/>.

 */

/*
 * Chart functions
 */

"use strict";

define([
        'jquery',
        'modernizr',
        'localise',
        'globals',
        'icheck'],
    function ($, modernizr, lang, globals) {

        var gMap,
            gLayers = [],
            gVectorSources = [],
            gMapUpdatePending = true;

        return {
            init: init,
            setLayers: setLayers,
            refreshLayer: refreshLayer,
            refreshAllLayers: refreshAllLayers
        };

        function setLayers(layers) {
            gLayers = layers;
            showLayerSelections();
        }

        function init() {

            // Create osm layer
            var osm = new ol.layer.Tile({source: new ol.source.OSM()});

            // Add the map
            if (!gMap) {
                gMap = new ol.Map({
                    target: 'map',
                    layers: [
                        new ol.layer.Group({
                            'title': 'Base maps',
                            layers: [
                                new ol.layer.Tile({
                                    title: 'Water color',
                                    type: 'base',
                                    visible: false,
                                    source: new ol.source.Stamen({
                                        layer: 'watercolor'
                                    })
                                }),
                                new ol.layer.Tile({
                                    title: 'OSM',
                                    type: 'base',
                                    visible: true,
                                    source: new ol.source.OSM()
                                })
                            ]
                        })
                    ],
                    view: new ol.View(
                        {
                            center: ol.proj.transform([0.0, 0.0], 'EPSG:4326', 'EPSG:3857'),
                            zoom: 1
                        }
                    )
                });

                var layerSwitcher = new ol.control.LayerSwitcher({
                    tipLabel: 'Legend' // Optional label for button
                });
                gMap.addControl(layerSwitcher);

                // Add a new map layer
                $('.addlayer').click(function () {
                    addLayer();
                });

                $('#layerEdit').on('shown.bs.modal', function () {
                    $('#ml_title').focus();
                });

                $('#addLayerSave').click(function () {
                    saveLayer();
                });

                // Show the layers selector
                $('#showlayers').click(function () {
                    globals.gMapLayersShown = !globals.gMapLayersShown;
                    if (globals.gMapLayersShown) {
                        $('#map_content').removeClass("col-md-12").addClass("col-md-8");
                        $('.map_layers').show();
                        gMap.updateSize();
                    } else {
                        $('#map_content').removeClass("col-md-8").addClass("col-md-12");
                        $('.map_layers').hide();
                        gMap.updateSize();
                    }
                });
            }

            if(gMapUpdatePending) {
                refreshAllLayers(true);
            }
            showLayerSelections();


        }

        /*
         * Show layer selections on the screen
         */
        function showLayerSelections() {
            var h = [],
                idx = -1,
                i;

            for (i = 0; i < gLayers.length; i++) {
                h[++idx] = '<tr>';

                h[++idx] = '<td>';      // Select
                    h[++idx] = '<div class="switch">';
                    h[++idx] = '<input type="checkbox" name="columnSelect"';
                    h[++idx] = ' class="layerSelect" value="';
                    h[++idx] = i;
                    h[++idx] = '"';
                    h[++idx] = '>';
                    h[++idx] = '</div>';
                h[++idx] = '</td>';
                h[++idx] = '<td>';      // Name
                h[++idx] = gLayers[i].title;
                h[++idx] = '</td>';
                h[++idx] = '<td>';      // Delete
                h[++idx] = '</td>';

                h[++idx] = '</tr>';
            }


            $('#layerSelect tbody').empty().html(h.join(''));
            $('input', '#layerSelect tbody').iCheck({
                checkboxClass: 'icheckbox_square-green',
                radioClass: 'iradio_square-green'
            });
        }

        /*
         * Redisplay a single layer
         */
        function refreshLayer(index) {

            if(gMap) {
                var results = globals.gMainTable.rows({
                    order: 'current',  // 'current', 'applied', 'index',  'original'
                    page: 'all',      // 'all',     'current'
                    search: 'applied',     // 'none',    'applied', 'removed'
                }).data();

                updateSingleLayer(index, results);
            }
        }

        /*
         * Redisplay all layers
         */
        function refreshAllLayers(mapView) {
            if (mapView) {
                if (gMap) {
                    var i;
                    var results = globals.gMainTable.rows({
                        order: 'current',  // 'current', 'applied', 'index',  'original'
                        page: 'all',      // 'all',     'current'
                        search: 'applied',     // 'none',    'applied', 'removed'
                    }).data();

                    for (i = 0; i < gLayers.length; i++) {
                        updateSingleLayer(i, results);
                    }
                }
                gMapUpdatePending = false;
            } else {
                gMapUpdatePending = true;
            }
        }

        /*
         * Set up a single layer
         * This function is called by both refreshLayer and refreshAllLayers as refreshLayer has to do some setup
         *  that is also done by refreshAllLayers
         */
        function updateSingleLayer(index, results) {

            var layer = gLayers[index];
            var geoJson = getGeoJson(results, layer);		// Get a geoson of data
            //var styles = getStyles(layer);					// Get the styles
            var defaultStyle = new ol.style.Style({
                fill: new ol.style.Fill({
                    color: 'rgba(255, 100, 50, 0.3)'
                }),
                stroke: new ol.style.Stroke({
                    width: 2,
                    color: 'rgba(255, 100, 50, 0.8)'
                }),
                image: new ol.style.Circle({
                    fill: new ol.style.Fill({
                        color: 'rgba(255, 0, 0, 0.5)'
                    }),
                    stroke: new ol.style.Stroke({
                        width: 1,
                        color: 'rgba(255, 255, 255, 0.8)'
                    }),
                    radius: 7
                }),
            });

            if(!gVectorSources[index]) {
                gVectorSources[index] = new ol.source.Vector();
            } else {
                gVectorSources[index].clear();
            }
            gVectorSources[index].addFeatures((new ol.format.GeoJSON()).readFeatures(geoJson,
                {
                    dataProjection: 'EPSG:4326',
                    featureProjection: 'EPSG:3857'
                }));


            // Add the layer if it does not exist, else up date it
            /*
            var vectorSource = new ol.source.Vector({
                features: (new ol.format.GeoJSON()).readFeatures(geoJson,
                    {
                        dataProjection: 'EPSG:4326',
                        featureProjection: 'EPSG:3857'
                    })
            });
            */


            var vectorLayer;
            if (layer.clump === "heatmap") {
                vectorLayer = new ol.layer.Heatmap({
                    source: gVectorSources[index],
                    radius: 5
                });
            } else {
                vectorLayer = new ol.layer.Vector({
                    source: gVectorSources[index],
                    style: [defaultStyle]
                });
            }

            gMap.addLayer(vectorLayer);
        }

        /*
         * User wants to add a new layer
         * Show the layer editor dialog
         */
        function addLayer() {
            $('#layerInfo').show();
            $('#ml_title').val("");
            $('#layerEdit').modal("show");
        }

        /*
         * Save a layer after the user presses save on the layer dialog
         */
        function saveLayer() {

            var title = $('#ml_title').val(),
                local = $('#usecurrent_tabledata').is(':checked'),
                layer = {};

            // Validation
            if (typeof title === "undefined" || title.trim().length === 0) {
                $('#layerInfo').show().removeClass('alert-success').addClass('alert-danger').html(localise.set["mf_tr"]);
                return false;
            }

            layer.title = title;
            layer.local = local;
            layer.clump = $('input[name=clump]:checked', '#mapForm').val();

            gLayers.push(layer);
            $('#layerEdit').modal("hide");	// All good close the modal

            refreshLayer(gLayers.length - 1);
            saveToServer(gLayers);
            showLayerSelections();

        };

        /*
         * Process the map data according to the layer specification
         */
        function getGeoJson(results, layer) {

            var i;

            var geoJson = {
                type: "FeatureCollection",

                features: []
            };

            for (i = 0; i < results.length; i++) {
                geoJson.features.push(
                    {
                        "type": "Feature",
                        "geometry": {"type": "Point", "coordinates": results[i]._geolocation},
                        "properties": {}
                    });
            }
            return geoJson;
        }

        /*
         * Save the layers to the server
         */
        function saveToServer(layers) {

            var saveString = JSON.stringify(layers);
            var viewId = globals.gViewId || 0;
            var url = "/surveyKPI/surveyview/" + viewId;
            url += '?survey=' + globals.gCurrentSurvey;
            url += '&managed=' + 0;
            url += '&query=' + 0;

            addHourglass();
            $.ajax({
                type: "POST",
                dataType: 'json',
                contentType: "application/json",
                cache: false,
                url: url,
                data: {mapView: saveString},
                success: function (data, status) {
                    removeHourglass();
                    globals.gViewId = data.viewId;
                }, error: function (data, status) {
                    removeHourglass();
                    alert(localise.set["msg_err_save"] + " " + data.responseText);
                }
            });
        }

    });
