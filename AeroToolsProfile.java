import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import org.locationtech.jts.geom.Geometry;
import java.util.List;

public class AeroToolsProfile implements Profile {

    @Override
    public void processFeature(SourceFeature feature, FeatureCollector features) {

        // MERIALUEET (sea areas)
        if ("ocean".equals(feature.getSource())) {
            features.polygon("water_areas")
                .setAttr("class", "ocean")
                .setPixelTolerance(2.0)
                .setMinZoom(4);
        }

        // 1. TIET (roads)
        if (feature.canBeLine() && feature.hasTag("highway")) {
            String highway = feature.getString("highway");
            if ("motorway".equals(highway) || "trunk".equals(highway) ||
                "primary".equals(highway) || "secondary".equals(highway) ||
                "tertiary".equals(highway)) {

                features.line("roads")
                    .setAttr("class", highway)
                    .setPixelTolerance(1.0)
                    .setMinZoom("motorway".equals(highway) || "trunk".equals(highway) ? 4 : 6);
            }
        }

        // 2. RAUTATIET (railways)
        if (feature.canBeLine() && "rail".equals(feature.getString("railway"))) {
            features.line("railways").setPixelTolerance(1.0).setMinZoom(7);
        }

        // 3. SAHKOLINJAT (power lines)
        if (feature.canBeLine() && "line".equals(feature.getString("power"))) {
            features.line("power_lines").setPixelTolerance(1.0).setMinZoom(8);
        }

        // 4. VESISTOT: JOET (rivers)
        if (feature.canBeLine() && "river".equals(feature.getString("waterway"))) {
            features.line("waterways").setPixelTolerance(2.0).setMinZoom(6);
        }

        // 5. VESISTOT: ALUEET (lakes and reservoirs)
        if (feature.canBePolygon() && (feature.hasTag("natural", "water") ||
                                       feature.hasTag("landuse", "reservoir") ||
                                       feature.hasTag("waterway", "riverbank"))) {
            features.polygon("water_areas")
                .setPixelTolerance(2.0)
                .setMinZoom(4);
        }

        // 6. MAASTOMUODOT & ASUTUSALUEET (terrain & settlement areas)
        if (feature.canBePolygon()) {
            if (feature.hasTag("landuse", "forest") || feature.hasTag("natural", "wood")) {
                features.polygon("landuse").setAttr("class", "forest")
                    .setPixelTolerance(2.0)
                    .setMinZoom(6);
            }
            else if (feature.hasTag("landuse", "farmland") || feature.hasTag("landuse", "farm") || feature.hasTag("landuse", "meadow")) {
                features.polygon("landuse").setAttr("class", "field")
                    .setPixelTolerance(2.0)
                    .setMinZoom(6);
            }
            else if (feature.hasTag("natural", "wetland")) {
                features.polygon("landuse").setAttr("class", "swamp")
                    .setPixelTolerance(2.0)
                    .setMinZoom(7);
            }
            else if (feature.hasTag("landuse", "residential") || feature.hasTag("landuse", "commercial") || feature.hasTag("landuse", "industrial") || feature.hasTag("landuse", "retail")) {
                features.polygon("landuse").setAttr("class", "urban")
                    .setPixelTolerance(2.0)
                    .setMinZoom(5);
            }
        }

        // 7. LENTOKENTTAALUEET JA RULLAUSTIET (airport areas and taxiways -
        // real OSM geometry where it exists, derived geometry as a fallback)
        if (feature.hasTag("aeroway")) {
            String aeroway = feature.getString("aeroway");
            boolean isAreaKind = "apron".equals(aeroway) || "runway".equals(aeroway) ||
                "taxiway".equals(aeroway) || "helipad".equals(aeroway) ||
                "aerodrome".equals(aeroway) || "terminal".equals(aeroway) || "hangar".equals(aeroway);

            if (feature.canBePolygon() && isAreaKind) {
                // Real OSM polygon geometry exists - use it directly, unmodified.
                features.polygon("aeroways")
                    .setAttr("class", aeroway)
                    .setPixelTolerance(0.0)
                    .setMinZoom("aerodrome".equals(aeroway) ? 8 : 10);
            }
            else if (feature.isPoint() && "helipad".equals(aeroway)) {
                // Many real-world helipads are only mapped as a point, not a polygon.
                features.point("aeroways")
                    .setAttr("class", "helipad")
                    .setMinZoom(12);
            }
            else if (feature.canBeLine() && ("runway".equals(aeroway) || "taxiway".equals(aeroway))) {
                // Keep the precise centerline for markings/labels.
                features.line("aeroway_lines")
                    .setAttr("class", aeroway)
                    .setPixelTolerance(0.0)
                    .setMinZoom(11);

                // ALSO derive an accurate filled surface polygon from the OSM
                // width tag, for airports where only a centerline was mapped.
                try {
                    double widthMeters;
                    String widthTag = feature.getString("width");
                    if (widthTag != null) {
                        widthMeters = Double.parseDouble(widthTag.replaceAll("[^0-9.]", ""));
                    } else {
                        widthMeters = "runway".equals(aeroway) ? 30.0 : 18.0;
                    }
                    if (widthMeters > 0) {
                        Geometry worldGeom = feature.worldGeometry();
                        double latDeg = GeoUtils.getWorldLat(worldGeom.getCentroid().getY());
                        double metersPerWorldUnit = GeoUtils.WORLD_CIRCUMFERENCE_METERS * Math.cos(Math.toRadians(latDeg));
                        double bufferWorldUnits = (widthMeters / 2.0) / metersPerWorldUnit;
                        Geometry buffered = worldGeom.buffer(bufferWorldUnits);

                        features.geometry("aeroways", buffered)
                            .setAttr("class", aeroway)
                            .setAttr("derived", true)
                            .setPixelTolerance(0.0)
                            .setMinZoom(10);
                    }
                } catch (NumberFormatException | GeometryException e) {
                    // Bad/missing width tag or unbuildable geometry - skip the
                    // derived polygon, the centerline above is still emitted.
                }
            }
        }

        // 8. ESTEET (obstacles)
        if (feature.isPoint() && (feature.hasTag("man_made", "mast") || feature.hasTag("man_made", "tower") || feature.hasTag("generator:source", "wind"))) {
            String mslElevation = feature.getString("ele");
            String aglHeight = feature.getString("height");
            features.point("obstacles")
                .setAttr("class", feature.hasTag("generator:source", "wind") ? "wind_turbine" : "mast")
                .setAttr("ele", mslElevation != null ? mslElevation : "")
                .setAttr("height", aglHeight != null ? aglHeight : "")
                .setMinZoom(8);
        }

        // 9. TEKSTIT JA NIMISTO (labels)
        if (feature.isPoint() && feature.hasTag("place")) {
            String place = feature.getString("place");
            if ("city".equals(place) || "town".equals(place)) {
                String name = feature.getString("name");
                if (name != null && !name.isEmpty()) {
                    features.point("labels")
                        .setAttr("name", name)
                        .setAttr("class", place)
                        .setMinZoom("city".equals(place) ? 4 : 7);
                }
            }
        }
    }

    // POST-PROCESSING: merge nearby polygons/lines into unified blocks
    @Override
    public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom, List<VectorTile.Feature> items) {
        if ("landuse".equals(layer)) {
            try {
                return FeatureMerge.mergeNearbyPolygons(items, 64, 64, 2, 2);
            } catch (Exception e) {
                return items;
            }
        }
        if ("roads".equals(layer)) {
            return FeatureMerge.mergeLineStrings(items, 0.5, 0.1, 4.0);
        }
        return items;
    }

    @Override
    public String name() {
        return "AeroTools Custom VFR Base Map";
    }

    public static void main(String[] args) throws Exception {
        String country = System.getenv("MAP_COUNTRY");
        if (country == null || country.isEmpty()) {
            country = "finland";
        }

        Planetiler.create(Arguments.fromArgs("maxzoom=11", "nodata=true", "force=true"))
            .setProfile(new AeroToolsProfile())
            .addOsmSource("osm", java.nio.file.Path.of("data", "raw.osm.pbf"))
            .addShapefileSource("ocean", java.nio.file.Path.of("data", "water-polygons-split-4326.zip"))
            .overwriteOutput(java.nio.file.Path.of("data", country + "_vfr.pmtiles"))
            .run();
    }
}
