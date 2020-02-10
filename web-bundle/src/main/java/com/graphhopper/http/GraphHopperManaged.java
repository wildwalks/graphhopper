/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.http;

import com.conveyal.osmlib.OSM;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.GraphHopper;
import com.graphhopper.TimeDependentAccessRestriction;
import com.graphhopper.TimeDependentAccessWeighting;
import com.graphhopper.json.geo.JsonFeatureCollection;
import com.graphhopper.reader.gtfs.GraphHopperGtfs;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.routing.util.spatialrules.SpatialRuleLookupHelper;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.timezone.core.TimeZones;
import com.graphhopper.util.CmdArgs;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.BBox;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

import static com.graphhopper.util.Helper.UTF_CS;

public class GraphHopperManaged implements Managed {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GraphHopper graphHopper;
    private OSM osm;
    private TimeZones timeZones;

    public GraphHopperManaged(CmdArgs configuration, ObjectMapper objectMapper) {
        ObjectMapper localObjectMapper = objectMapper.copy();
        localObjectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String splitAreaLocation = configuration.get(Parameters.Landmark.PREPARE + "split_area_location", "");
        JsonFeatureCollection landmarkSplittingFeatureCollection;
        try (Reader reader = splitAreaLocation.isEmpty() ? new InputStreamReader(LandmarkStorage.class.getResource("map.geo.json").openStream(), UTF_CS) : new InputStreamReader(new FileInputStream(splitAreaLocation), UTF_CS)) {
            landmarkSplittingFeatureCollection = localObjectMapper.readValue(reader, JsonFeatureCollection.class);
        } catch (IOException e1) {
            logger.error("Problem while reading border map GeoJSON. Skipping this.", e1);
            landmarkSplittingFeatureCollection = null;
        }
        if (configuration.has("gtfs.file")) {
            graphHopper = new GraphHopperGtfs(configuration);
        } else {
            graphHopper = new GraphHopperOSM(landmarkSplittingFeatureCollection) {
                @Override
                public Weighting createWeighting(HintsMap hintsMap, FlagEncoder encoder, Graph graph, TurnCostProvider turnCostProvider) {
                    Weighting weighting = super.createWeighting(hintsMap, encoder, graph, turnCostProvider);
                    if (hintsMap.has("block_property")) {
                        return new TimeDependentAccessWeighting(osm, graphHopper, timeZones, weighting);
                    }
                    return weighting;
                }

            }.forServer();
        }
        String spatialRuleLocation = configuration.get("spatial_rules.location", "");
        if (!spatialRuleLocation.isEmpty()) {
            final BBox maxBounds = BBox.parseBBoxString(configuration.get("spatial_rules.max_bbox", "-180, 180, -90, 90"));
            try (final InputStreamReader reader = new InputStreamReader(new FileInputStream(spatialRuleLocation), UTF_CS)) {
                JsonFeatureCollection jsonFeatureCollection = localObjectMapper.readValue(reader, JsonFeatureCollection.class);
                SpatialRuleLookupHelper.buildAndInjectSpatialRuleIntoGH(graphHopper, maxBounds, jsonFeatureCollection);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        graphHopper.init(configuration);
    }

    @Override
    public void start() {
        graphHopper.importOrLoad();
        logger.info("loaded graph at:" + graphHopper.getGraphHopperLocation()
                + ", data_reader_file:" + graphHopper.getDataReaderFile()
                + ", encoded values:" + graphHopper.getEncodingManager().toEncodedValuesAsString()
                + ", " + graphHopper.getGraphHopperStorage().toDetailsString());
        timeZones = new TimeZones();
        try {
            timeZones.initWithWorldData(new File("world-data/tz_world.shp").toURI().toURL());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        osm = new OSM(graphHopper.getGraphHopperStorage().getDirectory().getDefaultType().isStoring() ? graphHopper.getGraphHopperStorage().getDirectory().getLocation()+"/osm.db" : null);
        if (osm.ways.isEmpty()) {
            osm.readFromFile(graphHopper.getDataReaderFile());
            TimeDependentAccessRestriction timeDependentAccessRestriction = new TimeDependentAccessRestriction(graphHopper.getGraphHopperStorage(), osm, timeZones);
            timeDependentAccessRestriction.markEdgesAdjacentToConditionalTurnRestrictions();
        }
    }

    public GraphHopper getGraphHopper() {
        return graphHopper;
    }

    public OSM getOsm() {
        return osm;
    }

    public TimeZones getTimeZones() {
        return timeZones;
    }

    @Override
    public void stop() {
        osm.close();
        graphHopper.close();
    }


}
