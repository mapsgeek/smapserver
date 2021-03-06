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
 * Open layers 3 functions
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
            gVectorLayers = [],
            gMapUpdatePending = true;

        return {
            init: init,
            setLayers: setLayers,
            refreshLayer: refreshLayer,
            refreshAllLayers: refreshAllLayers,
            saveLayer: saveLayer
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
                                    title: 'HOT',
                                    type: 'base',
                                    visible: true,
                                    source: new ol.source.OSM({
                                        url: 'http://{a-c}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png'
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


                // Add additional maps specified in the shared resources page
                var sharedMaps = globals.gSelector.getSharedMaps();
                if(!sharedMaps) {
                    getServerSettings(getSharedMapsOL3, gMap);
                } else {
                    addSharedMapsOL3(gMap, sharedMaps)
                }

                var layerSwitcher = new ol.control.LayerSwitcher({
                    tipLabel: 'Legend' // Optional label for button
                });
                gMap.addControl(layerSwitcher);

                $('#layerEdit').on('shown.bs.modal', function () {
                    $('#ml_title').focus();
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

            if (gMapUpdatePending) {
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
                h[++idx] = '<button type="button" data-idx="';
                h[++idx] = i;
                h[++idx] = '" class="btn btn-default btn-sm rm_layer danger">';
                h[++idx] = '<span class="glyphicon glyphicon-trash" aria-hidden="true"></span></button>';
                h[++idx] = '</td>';

                h[++idx] = '</tr>';
            }


            $('#layerSelect tbody').empty().html(h.join(''));
            $('input', '#layerSelect tbody').iCheck({
                checkboxClass: 'icheckbox_square-green',
                radioClass: 'iradio_square-green'
            });
            $('.rm_layer', '#layerSelect tbody').click(function() {
                var idx = $(this).data("idx");
                deleteLayer(idx);
                gLayers.splice(idx, 1);
                saveToServer(gLayers);
                showLayerSelections();


            });
        }

        /*
         * Redisplay a single layer
         */
        function refreshLayer(index) {

            if (gMap) {
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

            if (!gVectorSources[index]) {
                gVectorSources[index] = new ol.source.Vector();
            } else {
                gVectorSources[index].clear();
            }
            gVectorSources[index].addFeatures((new ol.format.GeoJSON()).readFeatures(geoJson,
                {
                    dataProjection: 'EPSG:4326',
                    featureProjection: 'EPSG:3857'
                }));


            if (layer.clump === "heatmap") {
                gVectorLayers[index] = new ol.layer.Heatmap({
                    source: gVectorSources[index],
                    radius: 5
                });
            } else {
                gVectorLayers[index] = new ol.layer.Vector({
                    source: gVectorSources[index],
                    style: [defaultStyle]
                });
            }

            gMap.addLayer(gVectorLayers[index]);
        }

        function deleteLayer(index) {

            if (gVectorLayers[index]) {
                gMap.removeLayer(gVectorLayers[index]);
                gVectorSources.splice(index, 1);
                gVectorLayers.splice(index, 1);
            }


        }

        /*
         * Save a layer after the user specifies it in the layer dialog
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

            var i, j;

            var geoJson = {
                type: "FeatureCollection",

                features: []
            };

            for (i = 0; i < results.length; i++) {

                var keep = false;   // default

                if (!results[i]._geolocation) {                      // Invalid Geometry
                    keep = false;
                } else if (results[i]._geolocation.length < 2) {     // Invalid Geometry
                    keep = false;
                } else {
                    for (j = 1; j < results[i]._geolocation.length; j++) {
                        if (results[i]._geolocation[j] != 0) {
                            keep = true;                            // At least one non zero geometry
                            break;
                        }
                    }
                }

                if (keep) {
                    geoJson.features.push(
                        {
                            "type": "Feature",
                            "geometry": {"type": "Point", "coordinates": results[i]._geolocation},
                            "properties": {}
                        });
                }
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

        /*
         * Get the shared maps from the server
         */
        function getSharedMapsOL3(map) {

            var url = '/surveyKPI/shared/maps';
            $.ajax({
                url: url,
                dataType: 'json',
                cache: false,
                success: function(data) {
                    globals.gSelector.setSharedMaps(data);
                    addSharedMapsOL3(map, data);
                },
                error: function(xhr, textStatus, err) {
                    if(xhr.readyState == 0 || xhr.status == 0) {
                        return;  // Not an error
                    } else {
                        alert("Error: Failed to get list of shared maps: " + err);
                    }
                }
            });

        }

        /*
         * Add shared maps
         */
        function addSharedMapsOL3(map, sharedMaps) {

            var i,
                layerUrl,
                layer;

            if(sharedMaps) {


                var baseLayers = map.getLayers().item(0).getLayers().getArray();


                for(i = 0; i < sharedMaps.length; i++) {

                    layer = sharedMaps[i];

                    if(layer.type === "mapbox") {
                        //layerUrl = 'http://api.tiles.mapbox.com/v4/' + layer.config.mapid + ".jsonp?access_token=" + globals.gServerSettings.mapbox_default;
                        layerUrl = "http://a.tiles.mapbox.com/v4/" + layer.config.mapid + "/{z}/{x}/{y}.png?access_token=" + globals.gServerSettings.mapbox_default;
                        baseLayers.unshift(new ol.layer.Tile( {
                            title: layer.name,
                            type: 'base',
                            visible: false,
                            source: new ol.source.OSM({
                                url: layerUrl,
                                crossOrigin: 'anonymous'
                            })
                        }));

                    } /* else if(layer.type === "vector") {
                        layerUrl = "/surveyKPI/file/" + layer.config.vectorData + "/organisation";
                        var vectorLayer = new OpenLayers.Layer.Vector(layer.name + ".", {
                            projection: "EPSG:4326",
                            strategies: [new OpenLayers.Strategy.Fixed()],
                            protocol: new OpenLayers.Protocol.HTTP({
                                url: layerUrl,
                                format: new OpenLayers.Format.GeoJSON()
                            })
                        });
                        map.addLayer(vectorLayer);

                    }
                    */
                }
            }
        }

    });


