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
            else if (feature.hasTag("landuse",
