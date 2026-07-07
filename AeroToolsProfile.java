import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.VectorTile;
import java.util.List;
import org.locationtech.jts.geom.GeometryException;

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

        // 7. TEKSTIT JA NIMISTÖ
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
    public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom, List<VectorTile.Feature> items) throws GeometryException {
        if ("landuse".equals(layer)) {
            return FeatureMerge.mergeNearbyPolygons(
                items,
                64,    // minArea: Poistetaan pikkusirpaleet
                64,    // minHoleArea: Täytetään alueiden sisällä olevat pienet reiät
                2,     // minDist: Maksimietäisyys alueiden yhdistämiselle (kuroo teiden välit umpeen)
                2      // buffer: Turvottaa ja kutistaa reunoja tehden niistä pehmeämpiä
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
            .overwriteOutput("pmtiles", java.nio.file.Path.of("data", country + "_vfr.pmtiles"))
            .run();
    }
}
