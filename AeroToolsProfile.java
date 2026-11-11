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
    private static final int AIRPORT_MAX_ZOOM = 15;

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

    private boolean intersectsEnvelopeList(SourceFeature feature, List<Envelope> envelopes) {
        if (envelopes.isEmpty()) return false;
        try {
            Geometry geom = feature.worldGeometry();
            if (geom == null || geom.isEmpty()) return false;
            Envelope we = geom.getEnvelopeInternal();
            double minLon = GeoUtils.getWorldLon(we.getMinX());
            double maxLon = GeoUtils.getWorldLon(we.getMaxX());
            double lat1 = GeoUtils.getWorldLat(we.getMinY());
            double lat2 = GeoUtils.getWorldLat(we.getMaxY());
            double minLat = Math.min(lat1, lat2);
            double maxLat = Math.max(lat1, lat2);
            Envelope featEnv = new Envelope(minLon, maxLon, minLat, maxLat);
            for (Envelope env : envelopes) {
                if (env.intersects(featEnv)) {
                    return true;
                }
            }
        } catch (Exception e) {
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

        // 0. SEA AREAS (Basemap z0-12, plus z13-15 near coastal airports so water never disappears)
        if ("ocean".equals(feature.getSource())) {
            int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
            features.polygon("water_areas")
                .setAttr("class", "ocean")
                .setPixelTolerance(1.5)
                .setZoomRange(0, maxZ);
            return;
        }

        // 1. ROADS (Basemap z5-12, extended to z15 around airports)
        if (feature.canBeLine() && feature.hasTag("highway")) {
            String highway = feature.getString("highway");
            if ("motorway".equals(highway) || "trunk".equals(highway) ||
                "primary".equals(highway) || "secondary".equals(highway) ||
                "tertiary".equals(highway)) {

                int minZ = ("motorway".equals(highway) || "trunk".equals(highway)) ? 5 :
                           ("primary".equals(highway) ? 6 : ("secondary".equals(highway) ? 8 : 10));
                int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;

                features.line("roads")
                    .setAttr("class", highway)
                    .setPixelTolerance(1.2)
                    .setZoomRange(minZ, maxZ);
            }
        }

        // 2. RAILWAYS (Main lines only — z7-12, extended to z15 around airports)
        if (feature.canBeLine() && "rail".equals(feature.getString("railway"))) {
            String service = feature.getString("service");
            if (service == null || service.isEmpty()) {
                int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
                features.line("railways")
                    .setPixelTolerance(1.2)
                    .setZoomRange(7, maxZ);
            }
        }

        // 3. POWER LINES (Basemap z8-12, extended to z15 around airports)
        if (feature.canBeLine() && "line".equals(feature.getString("power"))) {
            int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
            features.line("power_lines")
                .setPixelTolerance(1.2)
                .setZoomRange(8, maxZ);
        }

        // 4. RIVERS & CANALS (Main rivers from z6, canals from z9 — streams/ditches excluded)
        if (feature.canBeLine() && (feature.hasTag("waterway", "river") || feature.hasTag("waterway", "canal"))) {
            String ww = feature.getString("waterway");
            int minZ = "river".equals(ww) ? 6 : 9;
            int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
            double widthM = parseMeters(feature.getString("width"), "river".equals(ww) ? 12.0 : 6.0);
            features.line("waterways")
                .setAttr("class", ww)
                .setAttr("width", Math.round(widthM))
                .setPixelTolerance(1.0)
                .setZoomRange(minZ, maxZ);
        }

        // 5. LAKES & RIVERBANK POLYGONS (Generalize micro-islands & filter tiny ponds via minPixelSize)
        if (feature.canBePolygon() && (feature.hasTag("natural", "water") ||
                                       feature.hasTag("landuse", "reservoir") ||
                                       feature.hasTag("waterway", "riverbank"))) {
            int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
            features.polygon("water_areas")
                .setAttr("class", "lake")
                .setMinPixelSize(2.0)
                .setPixelTolerance(1.2)
                .setZoomRange(4, maxZ);
        }

        // 6. BASEMAP LANDUSE & AIRPORT GRASS CUTOUTS
        if (feature.canBePolygon()) {
            if (feature.hasTag("landuse", "forest") || feature.hasTag("natural", "wood")) {
                int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
                features.polygon("landuse")
                    .setAttr("class", "forest")
                    .setMinPixelSize(2.0)
                    .setPixelTolerance(1.8)
                    .setZoomRange(6, maxZ);
            } else if (feature.hasTag("landuse", "grass") || feature.hasTag("landuse", "meadow") ||
                       feature.hasTag("natural", "grassland") || feature.hasTag("natural", "scrub")) {
                if (grassIslandWayIds.contains(feature.id())) {
                    features.polygon("aeroways")
                        .setAttr("class", "airport_grass_island")
                        .setPixelTolerance(0.0)
                        .setZoomRange(11, AIRPORT_MAX_ZOOM);
                } else if (intersectsEnvelopeList(feature, airportZones)) {
                    features.polygon("aeroways")
                        .setAttr("class", "aerodrome_turf")
                        .setPixelTolerance(0.0)
                        .setZoomRange(11, AIRPORT_MAX_ZOOM);
                }
            } else if (feature.hasTag("landuse", "farmland") || feature.hasTag("landuse", "farm")) {
                int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
                features.polygon("landuse")
                    .setAttr("class", "field")
                    .setMinPixelSize(2.0)
                    .setPixelTolerance(1.8)
                    .setZoomRange(7, maxZ);
            } else if (feature.hasTag("natural", "wetland")) {
                int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
                features.polygon("landuse")
                    .setAttr("class", "swamp")
                    .setMinPixelSize(2.0)
                    .setPixelTolerance(1.8)
                    .setZoomRange(7, maxZ);
            } else if (feature.hasTag("landuse", "residential") || feature.hasTag("landuse", "commercial") ||
                       feature.hasTag("landuse", "industrial") || feature.hasTag("landuse", "retail")) {
                int maxZ = intersectsEnvelopeList(feature, airportZones) ? AIRPORT_MAX_ZOOM : BASEMAP_MAX_ZOOM;
                features.polygon("landuse")
                    .setAttr("class", "urban")
                    .setMinPixelSize(1.5)
                    .setPixelTolerance(1.5)
                    .setZoomRange(5, maxZ);
            }
        }

        // 7. AIRPORT BUILDINGS (Hangars, Terminals, Towers — z12-15)
        if (feature.canBePolygon() && (feature.hasTag("aeroway") || feature.hasTag("building"))) {
            String aeroway = feature.getString("aeroway");
            String building = feature.getString("building");
            boolean isHangar = "hangar".equals(aeroway) || "hangar".equals(building);
            boolean isTerminal = "terminal".equals(aeroway) || "terminal".equals(building);
            boolean isTower = "tower".equals(aeroway) || "control_tower".equals(aeroway);

            if ((isHangar || isTerminal || isTower) && (feature.hasTag("aeroway") || intersectsEnvelopeList(feature, airportZones))) {
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

        // 8. OPERATIONAL AEROWAYS (z8-15)
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

                if ("runway".equals(aeroway) && !intersectsEnvelopeList(feature, areaRunwayWithLineZones)) {
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
                    } catch (Exception ignored) {}
                }
            }

            // 8B. Line Aeroways
            else if (feature.canBeLine()) {
                if ("runway".equals(aeroway)) {
                    double widthMeters = parseMeters(feature.getString("width"), 30.0);
                    features.line("aeroway_lines")
                        .setAttr("class", "runway")
                        .setAttr("surface", surface)
                        .setAttr("ref", ref != null ? ref : "")
                        .setAttr("width_m", Math.round(widthMeters))
                        .setAttr("closed", isClosed)
                        .setPixelTolerance(0.0)
                        .setZoomRange(11, AIRPORT_MAX_ZOOM);

                    try {
                        Geometry worldGeom = feature.worldGeometry();
                        if (worldGeom instanceof LineString ls) {
                            emitRunwayIdents(features, ref, ls.getStartPoint(), ls.getEndPoint());
                        }
                        if (!intersectsEnvelopeList(feature, areaRunwayWithLineZones) && widthMeters > 0) {
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
                    } catch (Exception ignored) {}
                }
                else if ("taxiway".equals(aeroway) || "taxilane".equals(aeroway)) {
                    boolean isParkingAisle = "parking_aisle".equals(feature.getString("service")) || "taxilane".equals(aeroway);
                    double defaultWidth = isParkingAisle ? 8.0 : 15.0;
                    double widthMeters = parseMeters(feature.getString("width"), defaultWidth);

                    features.line("aeroway_lines")
                        .setAttr("class", aeroway)
                        .setAttr("surface", surface)
                        .setAttr("ref", ref != null ? ref : "")
                        .setAttr("width_m", Math.round(widthMeters))
                        .setAttr("closed", isClosed)
                        .setPixelTolerance(0.0)
                        .setZoomRange(12, AIRPORT_MAX_ZOOM);

                    try {
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
                                .setAttr("width_m", Math.round(widthMeters))
                                .setAttr("closed", isClosed)
                                .setAttr("derived", true)
                                .setPixelTolerance(0.0)
                                .setZoomRange(12, AIRPORT_MAX_ZOOM);
                        }
                    } catch (Exception ignored) {}
                }
                else if ("holding_position".equals(aeroway)) {
                    String holdLabel = (ref != null && !ref.isBlank()) ? ref : (name != null ? name : "");
                    features.line("aeroway_lines")
                        .setAttr("class", "holding_position")
                        .setAttr("ref", holdLabel)
                        .setAttr("name", holdLabel)
                        .setPixelTolerance(0.0)
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);
                }
                else if ("parking_position".equals(aeroway)) {
                    String standLabel = (ref != null && !ref.isBlank()) ? ref : (name != null ? name : "");
                    features.line("aeroway_lines")
                        .setAttr("class", "stand_lead_in")
                        .setAttr("ref", standLabel)
                        .setAttr("name", standLabel)
                        .setPixelTolerance(0.0)
                        .setZoomRange(14, AIRPORT_MAX_ZOOM);

                    if (!standLabel.isBlank()) {
                        features.pointOnSurface("aeroway_points")
                            .setAttr("class", "stand")
                            .setAttr("ref", standLabel)
                            .setAttr("name", standLabel)
                            .setZoomRange(13, AIRPORT_MAX_ZOOM);
                    }
                }
            }

            // 8C. Operational Point Features
            else if (feature.isPoint()) {
                if ("holding_position".equals(aeroway)) {
                    String holdLabel = (ref != null && !ref.isBlank()) ? ref : (name != null ? name : "");
                    double bearing = holdingBearings.getOrDefault(feature.id(), 90.0);

                    features.point("aeroway_points")
                        .setAttr("class", "holding_position")
                        .setAttr("ref", holdLabel)
                        .setAttr("name", holdLabel)
                        .setAttr("bearing", Math.round(bearing))
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);

                    // Emit a real 14m perpendicular hold-short line across the taxiway
                    try {
                        Geometry worldGeom = feature.worldGeometry();
                        Coordinate c = worldGeom.getCoordinate();
                        double latDeg = GeoUtils.getWorldLat(c.y);
                        double metersPerWorldUnit = GeoUtils.WORLD_CIRCUMFERENCE_METERS * Math.cos(Math.toRadians(latDeg));
                        double halfLenWorld = 7.0 / metersPerWorldUnit;
                        double rad = Math.toRadians(bearing);
                        // In Planetiler world coords: +X is East (sin), -Y is North (cos)
                        double dx = Math.sin(rad) * halfLenWorld;
                        double dy = -Math.cos(rad) * halfLenWorld;

                        LineString holdBar = geomFactory.createLineString(new Coordinate[]{
                            new Coordinate(c.x - dx, c.y - dy),
                            new Coordinate(c.x + dx, c.y + dy)
                        });
                        features.geometry("aeroway_lines", holdBar)
                            .setAttr("class", "holding_position")
                            .setAttr("ref", holdLabel)
                            .setAttr("name", holdLabel)
                            .setPixelTolerance(0.0)
                            .setZoomRange(13, AIRPORT_MAX_ZOOM);
                    } catch (Exception ignored) {}
                }
                else if ("windsock".equals(aeroway)) {
                    // Only include windsocks inside airport zones
                    if (!intersectsEnvelopeList(feature, airportZones)) {
                        return;
                    }
                    boolean lit = "yes".equals(feature.getString("lit"));
                    features.point("aeroway_points")
                        .setAttr("class", "windsock")
                        .setAttr("lit", lit)
                        .setZoomRange(12, AIRPORT_MAX_ZOOM);
                }
                else if ("navigationaid".equals(aeroway) || "papi".equals(aeroway) || "vasi".equals(aeroway)) {
                    String navaidTag = feature.getString("navigationaid", aeroway).toLowerCase();
                    // Strictly emit ONLY PAPI or VASI — ignore taxiway/runway edge lights (txe, txc, rwe, als)
                    if (navaidTag.contains("papi") || navaidTag.contains("vasi")) {
                        features.point("aeroway_points")
                            .setAttr("class", navaidTag.contains("vasi") ? "vasi" : "papi")
                            .setAttr("ref", ref != null ? ref : "")
                            .setZoomRange(13, AIRPORT_MAX_ZOOM);
                    }
                }
                else if ("parking_position".equals(aeroway) || "gate".equals(aeroway)) {
                    String standLabel = (ref != null && !ref.isBlank()) ? ref : (name != null ? name : "");
                    if (!standLabel.isBlank()) {
                        features.point("aeroway_points")
                            .setAttr("class", "stand")
                            .setAttr("ref", standLabel)
                            .setAttr("name", standLabel)
                            .setZoomRange(13, AIRPORT_MAX_ZOOM);
                    }
                }
                else if ("fuel".equals(aeroway)) {
                    features.point("aeroway_points")
                        .setAttr("class", "fuel")
                        .setAttr("name", name != null ? name : "FUEL")
                        .setZoomRange(13, AIRPORT_MAX_ZOOM);
                }
                else if ("helipad".equals(aeroway) || "heliport".equals(aeroway)) {
                    if (!intersectsEnvelopeList(feature, airportZones)) {
                        return;
                    }
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
        Path oceanPath = Path.of("data", "water-polygons-split-3857.zip");

        var runner = Planetiler.create(Arguments.fromArgs("maxzoom=" + AIRPORT_MAX_ZOOM, "nodata=true", "force=true"))
            .setProfile(new AeroToolsProfile(metadataPath))
            .addOsmSource("osm", Path.of("data", "raw.osm.pbf"));

        if (Files.exists(oceanPath)) {
            runner.addShapefileSource("ocean", oceanPath);
        }

        runner.overwriteOutput(Path.of("data", country + "_vfr.pmtiles")).run();
    }
}
