import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.VectorTile;
import java.util.List;

public class AeroToolsProfile implements Profile {

    @Override
    public void processFeature(SourceFeature feature, FeatureCollector features) {
        
        // MERIALUEET
        if ("ocean".equals(feature.getSource())) {
            features.polygon("water_areas")
                .setAttr("class", "ocean")
                .setPixelTolerance(2.0)
                .setMinZoom(4);
        }

        // 1. TIET
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

        // 2. RAUTATIET
        if (feature.canBeLine() && "rail".equals(feature.getString("railway"))) {
            features.line("railways").setPixelTolerance(1.0).setMinZoom(7);
        }

        // 3. SÄHKÖLINJAT
        if (feature.canBeLine() && "line".equals(feature.getString("power"))) {
            features.line("power_lines").setPixelTolerance(1.0).setMinZoom(8);
        }

        // 4. VESISTÖT: JOET
        if (feature.canBeLine() && "river".equals(feature.getString("waterway"))) {
            features.line("waterways").setPixelTolerance(2.0).setMinZoom(6);
        }

        // 5. VESISTÖT: ALUEET (Järvet ja altaat)
        if (feature.canBePolygon() && (feature.hasTag("natural", "water") || 
                                       feature.hasTag("landuse", "reservoir") || 
                                       feature.hasTag("waterway", "riverbank"))) {
            features.polygon("water_areas")
                .setPixelTolerance(2.0)
                .setMinZoom(4);
        }

        // 6. MAASTOMUODOT & ASUTUSALUEET
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

        // 7. LENTOKENTTÄALUEET JA RULLAUSTIET (Erittäin tarkka geometria)
        if (feature.hasTag("aeroway")) {
            String aeroway = feature.getString("aeroway");
            
            if (feature.canBePolygon() && ("apron".equals(aeroway) || "runway".equals(aeroway) || "taxiway".equals(aeroway) || "helipad".equals(aeroway))) {
                features.polygon("aeroways")
                    .setAttr("class", aeroway)
                    .setPixelTolerance(0.0) // Ei yksinkertaistusta polygoneille
                    .setMinZoom(10);
            } else if (feature.canBeLine() && ("runway".equals(aeroway) || "taxiway".equals(aeroway))) {
                features.line("aeroway_lines")
                    .setAttr("class", aeroway)
                    .setPixelTolerance(0.0) // Ei yksinkertaistusta viivoille
                    .setMinZoom(11);
            }
        }

        // 8. ESTEET OSM-DATASTA
        if (feature.isPoint() && (feature.hasTag("man_made", "mast") || feature.hasTag("man_made", "tower") || feature.hasTag("generator:source", "wind"))) {
            String mslElevation = feature.getString("ele"); 
            String aglHeight = feature.getString("height");
            features.point("obstacles")
                .setAttr("class", feature.hasTag("generator:source", "wind") ? "wind_turbine" : "mast")
                .setAttr("ele", mslElevation != null ? mslElevation : "")
                .setAttr("height", aglHeight != null ? aglHeight : "")
                .setMinZoom(8);
        }

        // 9. OFMX-DATAN LÄHTEET (GeoJSON)
        if ("reporting_points".equals(feature.getSource())) {
            features.point("reporting_points")
                .setAttr("name", feature.getString("name"))
                .setAttr("type", feature.getString("type"))
                .setMinZoom(8);
        }
        
        if ("approach_sectors".equals(feature.getSource())) {
            features.polygon("approach_sectors")
                .setAttr("rwy", feature.getString("runway_designator"))
                .setMinZoom(10);
        }
        
        if ("labels".equals(feature.getSource())) {
            features.point("vfr_labels")
                .setAttr("label", feature.getString("label_text"))
                .setMinZoom(9);
        }

        if ("airspaces".equals(feature.getSource())) {
            features.polygon("airspaces")
                .setAttr("type", feature.getString("type"))
                .setAttr("name", feature.getString("name"))
                .setAttr("lower_limit", feature.getString("lower_limit"))
                .setAttr("upper_limit", feature.getString("upper_limit"))
                .setMinZoom(6);
        }

        // 10. TEKSTIT JA NIMISTÖ
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

    // POST-PROCESSING: Yhdistetään toisiaan lähellä olevat alueet yhtenäisiksi blokeiksi
    @Override
    public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom, List<VectorTile.Feature> items) {
        if ("landuse".equals(layer)) {
            try {
                return FeatureMerge.mergeNearbyPolygons(
                    items,
                    64,    // minArea
                    64,    // minHoleArea
                    2,     // minDist
                    2      // buffer
                );
            } catch (Exception e) {
                return items;
            }
        }
        if ("roads".equals(layer)) {
            // Yhdistää lähekkäin menevät tien osat
            return FeatureMerge.mergeLineStrings(
                items,
                0.5, // minLength
                0.1, // tolerance
                4.0  // buffer
            );
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
            // Uudet OFMX-lähteet (edellyttää, että Python-skripti on ajettu ennen Planetileria)
            .addGeoJsonSource("reporting_points", java.nio.file.Path.of("data", "reporting_points.geojson"))
            .addGeoJsonSource("airspaces", java.nio.file.Path.of("data", "airspaces.geojson"))
            .addGeoJsonSource("approach_sectors", java.nio.file.Path.of("data", "approach_sectors.geojson"))
            .addGeoJsonSource("labels", java.nio.file.Path.of("data", "labels.geojson"))
            .overwriteOutput(java.nio.file.Path.of("data", country + "_vfr.pmtiles")) 
            .run();
    }
}
