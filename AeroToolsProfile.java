import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.buffer.BufferParameters;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AeroToolsProfile implements Profile {

    private static final int BASEMAP_MAX_ZOOM = 12;
    private static final int AIRPORT_MAX_ZOOM = 16;

    private final List<Envelope> airportZones = new ArrayList<>();
    private final List<Envelope> areaRunwayWithLineZones = new ArrayList<>();
    private final Map<Long, Double> holdingBearings = new HashMap<>();
    private final Set<Long> grassIslandWayIds = new HashSet<>();
    private final GeometryFactory geomFactory = new GeometryFactory();

    public AeroToolsProfile(Path metadataPath) {
        loadAirportMetadata(metadataPath);
    }

    private void loadAirportMetadata(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 0) continue;
                switch (parts[0]) {
                    case "AIRPORT_BBOX" -> {
                        if (parts.length >= 5) {
                            airportZones.add(new Envelope(
                                Double.parseDouble(parts[1]), Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[2]), Double.parseDouble(parts[4])
                            ));
                        }
                    }
                    case "AREA_RWY_WITH_LINE" -> {
                        if (parts.length >= 5) {
                            areaRunwayWithLineZones.add(new Envelope(
                                Double.parseDouble(parts[1]), Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[2]), Double.parseDouble(parts[4])
                            ));
                        }
                    }
                    case "HOLD_BEARING" -> {
                        if (parts.length >= 3) {
                            holdingBearings.put(Long.parseLong(parts[1]), Double.parseDouble(parts[2]));
                        }
                    }
                    case "GRASS_ISLAND" -> {
                        if (parts.length >= 2) {
                            grassIslandWayIds.add(Long.parseLong(parts[1]));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to read airport metadata: " + e.getMessage());
        }
    }

    private boolean isInsideEnvelopeList(SourceFeature feature, List<Envelope> envelopes) {
        if (envelopes.isEmpty()) return false;
        try {
            Geometry geom = feature.worldGeometry();
            Coordinate c = geom.getCentroid().getCoordinate();
            double lon = GeoUtils.getWorldLon(c.x);
            double lat = GeoUtils.getWorldLat(c.y);
            for (Envelope env : envelopes) {
                if (env.contains(lon, lat)) {
                    return true;
                }
            }
        } catch (GeometryException e) {
            return false;
        }
        return false;
    }

    private static double parseMeters(String raw, double defaultVal) {
        if (raw == null || raw.isBlank()) return defaultVal;
        try {
            String lower = raw.toLowerCase().trim();
            boolean isFeet = lower.contains("ft") || lower.contains("'");
            String clean = lower.replaceAll("[^0-9.,]", "").replace(',', '.');
            if (clean.isEmpty()) return defaultVal;
            double val = Double.parseDouble(clean);
            return isFeet ? val * 0.3048 : val;
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static String normalizeSurface(String surface) {
        if (surface == null || surface.isBlank()) return "paved";
        String s = surface.toLowerCase();
        if (s.contains("grass") || s.contains("turf")) return "grass";
        if (s.contains("gravel") || s.contains("dirt") || s.contains("earth") || s.contains("sand") || s.contains("unpaved")) return "unpaved";
        return "paved";
    }

    private static double computeTrueBearing(Coordinate startWorld, Coordinate endWorld) {
        double lat1 = Math.toRadians(GeoUtils.getWorldLat(startWorld.y));
        double lon1 = Math.toRadians(GeoUtils.getWorldLon(startWorld.x));
        double lat2 = Math.toRadians(GeoUtils.getWorldLat(endWorld.y));
        double lon2 = Math.toRadians(GeoUtils.getWorldLon(endWorld.x));
        double dLon = lon2 - lon1;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private static double angularDiff(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    private void emitRunwayIdents(FeatureCollector features, String ref, Point startPt, Point endPt) {
        if (ref == null || !ref.contains("/")) return;
        String[] parts = ref.split("/");
        if (parts.length != 2) return;

        String identA = parts[0].trim();
        String identB = parts[1].trim();
        double lineBearing = computeTrueBearing(startPt.getCoordinate(), endPt.getCoordinate());

        String digitsA = identA.replaceAll("[^0-9]", "");
        if (!digitsA.isEmpty()) {
            try {
                double expectedBearingA = (Integer.parseInt(digitsA) * 10) % 360;
                if (angularDiff(lineBearing, expectedBearingA) > 90.0) {
                    String tmp = identA;
                    identA = identB;
                    identB = tmp;
                }
            } catch (NumberFormatException ignored) {}
        }

        double reverseBearing = (lineBearing + 180.0) % 360.0;

        features.geometry("aeroway_points", startPt)
            .setAttr("class", "runway_ident")
            .setAttr("ref", identA)
            .setAttr("bearing", Math.round(lineBearing))
            .setZoomRange(12, AIRPORT_MAX_ZOOM);

        features.geometry("aeroway_points", endPt)
            .setAttr("class", "runway_ident")
            .setAttr("ref", identB)
            .setAttr("bearing", Math.round(reverseBearing))
            .setZoomRange(12, AIRPORT_MAX_ZOOM);
    }

    @Override
    public void processFeature(SourceFeature feature, FeatureCollector features) {

        // 1. ROADS (Basemap z4-12)
        if (feature.canBeLine() && feature.hasTag("highway")) {
            String highway = feature.getString("highway");
            if ("motorway".equals(highway) || "trunk".equals(highway) ||
                "primary".equals(highway) || "secondary".equals(highway) ||
                "tertiary".equals(highway)) {

                int minZ = ("motorway".equals(highway) || "trunk".equals(highway)) ? 5 :
                           ("primary".equals(highway) ? 6 : ("secondary".equals(highway) ? 8 : 10));

                features.line("roads")
                    .setAttr("class", highway)
                    .setPixelTolerance(1.0)
                    .setZoomRange(minZ, BASEMAP_MAX_ZOOM);
            }
        }

        // 2. RAILWAYS (Basemap z7-12)
        if (feature.canBeLine() && "rail".equals(feature.getString("railway"))) {
            String service = feature.getString("service");
            if (service == null || service.isEmpty()) {
                features.line("railways")
                    .setPixelTolerance(1.0)
                    .setZoomRange(7, BASEMAP_MAX_ZOOM);
            }
        }

        // 3. POWER LINES (Basemap z8-12)
        if (feature.canBeLine() && "line".equals(feature.getString("power"))) {
            features.line("power_lines")
                .setPixelTolerance(1.0)
                .setZoomRange(8, BASEMAP_MAX_ZOOM);
        }

        // 4. RIVERS & CANALS (Basemap z6-12)
        if (feature.canBeLine() && (feature.hasTag("waterway", "river") || feature.hasTag("waterway", "canal"))) {
            double widthM = parseMeters(feature.getString("width"), "river".equals(feature.getString("waterway")) ? 12.0 : 6.0);
            features.line("waterways")
                .setAttr("class", feature.getString("waterway"))
                .setAttr("width", Math.round(widthM))
                .setPixelTolerance(0.8)
                .setZoomRange(6, BASEMAP_MAX_ZOOM);
        }

        // 5. LAKES & WATER POLYGONS (Basemap z4-12)
        if (feature.canBePolygon() && (feature.hasTag("natural", "water") ||
                                       feature.hasTag("landuse", "reservoir") ||
                                       feature.hasTag("waterway", "riverbank"))) {
            features.polygon("water_areas")
                .setAttr("class", "lake")
                .setPixelTolerance(0.8)
                .setZoomRange(4, BASEMAP_MAX_ZOOM);
        }

        // 6. BASEMAP LANDUSE & AIRPORT GRASS CUTOUTS
        if (feature.canBePolygon()) {
            if (feature.hasTag("landuse", "forest") || feature.hasTag("natural", "wood")) {
                features.polygon("landuse")
                    .setAttr("class", "forest")
                    .setPixelTolerance(1.5)
                    .setZoomRange(6, BASEMAP_MAX_ZOOM);

                if (grassIslandWayIds.contains(feature.id())) {
                    features.polygon("aeroways")
                        .setAttr("class", "airport_wood")
                        .setPixelTolerance(0.0)
                        .setZoomRange(12, AIRPORT_MAX_ZOOM);
                }
            } else if (feature.hasTag("landuse", "grass") || feature.hasTag("landuse", "meadow") ||
                       feature.hasTag("natural", "grassland") || feature.hasTag("natural", "scrub")) {
                if (isInsideEnvelopeList(feature, airportZones)) {
                    String grassClass = grassIslandWayIds.contains(feature.id()) ? "airport_grass_island" : "aerodrome_turf";
                    features.polygon("aeroways")
                        .setAttr("class", grassClass)
                        .setPixelTolerance(0.0)
                        .setZoomRange(11, AIRPORT_MAX_ZOOM);
                }
            } else if (feature.hasTag("landuse", "farmland") || feature.hasTag("landuse", "farm")) {
                features.polygon("landuse")
                    .setAttr("class", "field")
                    .setPixelTolerance(1.5)
                    .setZoomRange(7, BASEMAP_MAX_ZOOM);
            } else if (feature.hasTag("natural", "wetland")) {
                features.polygon("landuse")
                    .setAttr("class", "swamp")
                    .setPixelTolerance(1.5)
                    .setZoomRange(7, BASEMAP_MAX_ZOOM);
            } else if (feature.hasTag("landuse", "residential") || feature.hasTag("landuse", "commercial") ||
                       feature.hasTag("landuse", "industrial") || feature.hasTag("landuse", "retail")) {
                features.polygon("landuse")
                    .setAttr("class", "urban")
                    .setPixelTolerance(1.5)
                    .setZoomRange(5, BASEMAP_MAX_ZOOM);
            }
        }

        // 7. AIRPORT BUILDINGS (Hangars, Terminals, Towers — z12-16)
        if (feature.canBePolygon() && (feature.hasTag("aeroway") || feature.hasTag("building"))) {
            String aeroway = feature.getString("aeroway");
            String building = feature.getString("building");
            boolean isHangar = "hangar".equals(aeroway) || "hangar".equals(building);
            boolean isTerminal = "terminal".equals(aeroway) || "terminal".equals(building);
            boolean isTower = "tower".equals(aeroway) || "control_tower".equals(aeroway);

            if ((isHangar || isTerminal || isTower) && (feature.hasTag("aeroway") || isInsideEnvelopeList(feature, airportZones))) {
                String bldgClass = isTerminal ? "terminal" : (isTower ? "tower" : "hangar");
                String name = feature.getString("name");
                features.polygon("aeroways")
                    .setAttr("class", bldgClass)
                    .setAttr("name", name != null ? name : "")
                    .setPixelTolerance(0.0)
                    .setZoomRange(12, AIRPORT_MAX_ZOOM);

                if (name != null && !name.isBlank()) {
                    features.pointOnSurface("aeroway_points")
                        .setAttr("class", "building_label")
                        .setAttr("name", name)
                        .setZoomRange(14, AIRPORT_MAX_ZOOM);
                }
            }
        }

        // 8. OPERATIONAL AEROWAYS (z8-16)
        if (feature.hasTag("aeroway") || feature.hasTag("disused:aeroway") || feature.hasTag("abandoned:aeroway")) {
            boolean isClosed = feature.hasTag("disused:aeroway") || feature.hasTag("abandoned:aeroway") ||
                               feature.hasTag("aeroway", "closed");
            String aeroway = feature.getString("aeroway");
            if (aeroway == null) {
                aeroway = feature.getString("disused:aeroway", feature.getString("abandoned:aeroway"));
            }
            if (aeroway == null) return;

            String surface = normalizeSurface(feature.getString("surface"));
            String ref = feature.getString("ref");
            String name = feature.getString("name");

            // 8A. Polygon Aeroways
            if (feature.canBePolygon() && ("aerodrome".equals(aeroway) || "airstrip".equals(aeroway) ||
                "apron".equals(aeroway) || "runway".equals(aeroway) || "taxiway".equals(aeroway) ||
                "taxilane".equals(aeroway) || "stopway".equals(aeroway) || "blast_pad".equals(aeroway) ||
                "helipad".equals(aeroway))) {

                int minZ = ("aerodrome".equals(aeroway) || "airstrip".equals(aeroway)) ? 8 :
                           ("runway".equals(aeroway) ? 10 : 11);

                features.polygon("aeroways")
                    .setAttr("class", "blast_pad".equals(aeroway) ? "stopway" : aeroway)
                    .setAttr("surface", surface)
                    .setAttr("ref", ref != null ? ref : "")
                    .setAttr("name", name != null ? name : "")
                    .setAttr("closed", isClosed)
                    .setAttr("derived", false)
                    .setPixelTolerance(0.0)
                    .setZoomRange(minZ, AIRPORT_MAX_ZOOM);

                if ("apron".equals(aeroway) && (name != null || ref != null)) {
                    features.pointOnSurface("aeroway_points")
                        .setAttr("class", "apron_label")
                        .setAttr("name", name != null ? name : ref)
                        .setZoomRange(14, AIRPORT_MAX_ZOOM);
                }

                if ("runway".equals(aeroway) && !isInsideEnvelopeList(feature, areaRunwayWithLineZones)) {
                    try {
                        Geometry worldGeom = feature.worldGeometry();
                        MinimumDiameter minDiam = new MinimumDiameter(worldGeom);
                        Geometry rect = minDiam.getMinimumRectangle();
                        Coordinate[] coords = rect.getCoordinates();
                        if (coords.length >= 5) {
                            double d01 = coords[0].distance(coords[1]);
                            double d12 = coords[1].distance(coords[2]);
                            Coordinate midA;
                            Coordinate midB;
                            if (d01 < d12) {
                                midA = new Coordinate((coords[0].x + coords[1].x) / 2.0, (coords[0].y + coords[1].y) / 2.0);
                                midB = new Coordinate((coords[2].x + coords[3].x) / 2.0, (coords[2].y + coords[3].y) / 2.0);
                            } else {
                                midA = new Coordinate((coords[1].x + coords[2].x) / 2.0, (coords[1].y + coords[2].y) / 2.0);
                                midB = new Coordinate((coords[3].x + coords[0].x) / 2.0, (coords[3].y + coords[0].y) / 2.0);
                            }
                            LineString synthLine = geomFactory.createLineString(new Coordinate[]{midA, midB});
                            features.geometry("aeroway_lines", synthLine)
                                .setAttr("class", "runway")
                                .setAttr("surface", surface)
                                .setAttr("ref", ref != null ? ref : "")
                                .setAttr("closed", isClosed)
                                .setPixelTolerance(0.0)
                                .setZoomRange(11, AIRPORT_MAX_ZOOM);

                            emitRunwayIdents(features, ref, synthLine.getStartPoint(), synthLine.getEndPoint());
                        }
                    } catch (GeometryException ignored) {}
                }
            }

            // 8B. Line Aeroways
            else if (feature.canBeLine()) {
                if ("runway".equals(aeroway)) {
                    features.line("aeroway_lines")
                        .setAttr("class", "runway")
                        .setAttr("surface", surface)
                        .setAttr("ref", ref != null ? ref : "")
                        .setAttr("closed", isClosed)
                        .setPixelTolerance(0.0)
                        .setZoomRange(11, AIRPORT_MAX_ZOOM);

                    try {
                        Geometry worldGeom = feature.worldGeometry();
                        if (worldGeom instanceof LineString ls) {
                            emitRunwayIdents(features, ref, ls.getStartPoint(), ls.getEndPoint());
                        }
                        if (!isInsideEnvelopeList(feature, areaRunwayWithLineZones)) {
                            double widthMeters = parseMeters(feature.getString("width"), 30.0);
                            if (widthMeters > 0) {
                                double latDeg = GeoUtils.getWorldLat(worldGeom.getCentroid().getY());
                                double metersPerWorldUnit = GeoUtils.WORLD_CIRCUMFERENCE_METERS * Math.cos(Math.toRadians(latDeg));
                                double bufferWorldUnits = (widthMeters / 2.0) / metersPerWorldUnit;
                                Geometry buffered = worldGeom.buffer(bufferWorldUnits, 8, BufferParameters.CAP_FLAT);

                                features.geometry("aeroways", buffered)
                                    .setAttr("class", "runway")
                                    .setAttr("surface", surface)
                                    .setAttr("ref", ref != null ? ref : "")
                                    .setAttr("closed", isClosed)
                                    .setAttr("derived", true)
                                    .setPixelTolerance(0.0)
                                    .setZoomRange(10, AIRPORT_MAX_ZOOM);
                            }
                        }
                    } catch (GeometryException ignored) {}
                }
                else if ("taxiway".equals(aeroway) || "taxilane".equals(aeroway)) {
                    features.line("aeroway_lines")
                        .setAttr("class", aeroway)
                        .setAttr("surface", surface)
                        .setAttr("ref", ref != null ? ref : "")
                        .setAttr("closed", isClosed)
                        .setPixelTolerance(0.0)
                        .setZoomRange(12, AIRPORT_MAX_ZOOM);

                    try {
                        boolean isMajorTwy = (ref != null && !ref.isBlank()) && "paved".equals(surface);
                        double defaultWidth = "taxilane".equals(aeroway) ? 7.5 : (isMajorTwy ? 14.0 : 8.5);
                        double widthMeters = parseMeters(feature.getString("width"), defaultWidth);
                        if (widthMeters > 0) {
                            Geometry worldGeom = feature.worldGeometry();
                            double latDeg = GeoUtils.getWorldLat(worldGeom.getCentroid().getY());
                            double metersPerWorldUnit = GeoUtils.WORLD_CIRCUMFERENCE_METERS * Math.cos(Math.toRadians(latDeg));
                            double bufferWorldUnits = (widthMeters / 2.0) / metersPerWorldUnit;
                            Geometry buffered = worldGeom.buffer(bufferWorldUnits, 8, BufferParameters.CAP_ROUND);

                            features.geometry("aeroways", buffered)
                                .setAttr("class", aeroway)
                                .setAttr("surface", surface)
                                .setAttr("ref", ref != null ? ref : "")
                                .setAttr("closed", isClosed)
                                .setAttr("derived", true)
                                .setPixelTolerance(0.0)
                                .setZoomRange(12, AIRPORT_MAX_ZOOM);
                        }
                    } catch (GeometryException ignored) {}
                }
                else if ("holding_position".equals(aeroway)) {
                    features.line("aeroway_lines")
                        .setAttr("class", "holding_position")
                        .setAttr("ref", ref != null ? ref : (name != null ? name : ""))
                        .setPixelTolerance(0.0)
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);
                }
                else if ("parking_position".equals(aeroway)) {
                    features.line("aeroway_lines")
                        .setAttr("class", "stand_lead_in")
                        .setAttr("ref", ref != null ? ref : "")
                        .setPixelTolerance(0.0)
                        .setZoomRange(14, AIRPORT_MAX_ZOOM);
                }
            }

            // 8C. Operational Point Features
            else if (feature.isPoint()) {
                if ("holding_position".equals(aeroway)) {
                    double bearing = holdingBearings.getOrDefault(feature.id(), 0.0);
                    features.point("aeroway_points")
                        .setAttr("class", "holding_position")
                        .setAttr("ref", ref != null ? ref : (name != null ? name : ""))
                        .setAttr("bearing", Math.round(bearing))
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);
                }
                else if ("windsock".equals(aeroway)) {
                    boolean lit = "yes".equals(feature.getString("lit"));
                    features.point("aeroway_points")
                        .setAttr("class", "windsock")
                        .setAttr("lit", lit)
                        .setZoomRange(12, AIRPORT_MAX_ZOOM);
                }
                else if ("navigationaid".equals(aeroway)) {
                    String navaidType = feature.getString("navigationaid", "papi").toLowerCase();
                    features.point("aeroway_points")
                        .setAttr("class", navaidType.contains("vasi") ? "vasi" : "papi")
                        .setAttr("ref", ref != null ? ref : "")
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);
                }
                else if ("parking_position".equals(aeroway) || "gate".equals(aeroway)) {
                    String standLabel = ref != null ? ref : (name != null ? name : "");
                    features.point("aeroway_points")
                        .setAttr("class", "stand")
                        .setAttr("ref", standLabel)
                        .setZoomRange(14, AIRPORT_MAX_ZOOM);
                }
                else if ("fuel".equals(aeroway)) {
                    features.point("aeroway_points")
                        .setAttr("class", "fuel")
                        .setAttr("name", name != null ? name : "FUEL")
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);
                }
                else if ("helipad".equals(aeroway) || "heliport".equals(aeroway)) {
                    features.point("aeroway_points")
                        .setAttr("class", "helipad")
                        .setAttr("ref", ref != null ? ref : "H")
                        .setZoomRange(12, AIRPORT_MAX_ZOOM);
                }
                else if ("tower".equals(aeroway) || "control_tower".equals(aeroway)) {
                    features.point("aeroway_points")
                        .setAttr("class", "tower")
                        .setAttr("name", name != null ? name : "TWR")
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);
                }
            }
        }

        // 9. VFR OBSTACLES IN BASEMAP (z8-12)
        if (feature.isPoint() && (feature.hasTag("man_made", "mast") ||
                                  feature.hasTag("man_made", "tower") ||
                                  feature.hasTag("man_made", "chimney") ||
                                  feature.hasTag("generator:source", "wind"))) {
            String mslElevation = feature.getString("ele");
            String aglHeight = feature.getString("height");
            features.point("obstacles")
                .setAttr("class", feature.hasTag("generator:source", "wind") ? "wind_turbine" : "mast")
                .setAttr("ele", mslElevation != null ? mslElevation : "")
                .setAttr("height", aglHeight != null ? aglHeight : "")
                .setZoomRange(8, BASEMAP_MAX_ZOOM);
        }

        // 10. PLACE LABELS (Basemap z4-12)
        if (feature.isPoint() && feature.hasTag("place")) {
            String place = feature.getString("place");
            if ("city".equals(place) || "town".equals(place) || "village".equals(place)) {
                String name = feature.getString("name");
                if (name != null && !name.isEmpty()) {
                    int minZ = "city".equals(place) ? 4 : ("town".equals(place) ? 7 : 10);
                    features.point("labels")
                        .setAttr("name", name)
                        .setAttr("class", place)
                        .setZoomRange(minZ, BASEMAP_MAX_ZOOM);
                }
            }
        }
    }

    @Override
    public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom, List<VectorTile.Feature> items) {
        if ("landuse".equals(layer)) {
            try {
                double dist = zoom <= 9 ? 6.0 : 3.0;
                return FeatureMerge.mergeNearbyPolygons(items, 64, 64, dist, dist);
            } catch (Exception e) {
                return items;
            }
        }
        if ("roads".equals(layer)) {
            return FeatureMerge.mergeLineStrings(items, 0.5, 0.2, 4.0);
        }
        return items;
    }

    @Override
    public String name() {
        return "AeroTools VFR & High-Precision Aerodrome Map";
    }

    public static void main(String[] args) throws Exception {
        String envCountry = System.getenv("MAP_COUNTRY");
        String country = (envCountry != null && !envCountry.trim().isEmpty())
            ? envCountry.trim().toLowerCase()
            : "finland";

        Path metadataPath = Path.of("data", "airport_metadata.txt");

        Planetiler.create(Arguments.fromArgs("maxzoom=" + AIRPORT_MAX_ZOOM, "nodata=true", "force=true"))
            .setProfile(new AeroToolsProfile(metadataPath))
            .addOsmSource("osm", Path.of("data", "raw.osm.pbf"))
            .overwriteOutput(Path.of("data", country + "_vfr.pmtiles"))
            .run();
    }
}
