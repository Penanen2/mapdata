import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.config.Arguments;

public class AeroToolsProfile implements Profile {

    @Override
    public void processFeature(SourceFeature feature, FeatureCollector features) {
        
        // 1. TIET
        if (feature.canBeLine() && feature.hasTag("highway")) {
            String highway = feature.getString("highway");
            if ("motorway".equals(highway) || "trunk".equals(highway) || 
                "primary".equals(highway) || "secondary".equals(highway) || 
                "tertiary".equals(highway)) {
                
                features.line("roads")
                    .setAttr("class", highway)
                    .setMinZoom("motorway".equals(highway) || "trunk".equals(highway) ? 4 : 6);
            }
        }

        // 2. RAUTATIET
        if (feature.canBeLine() && "rail".equals(feature.getString("railway"))) {
            features.line("railways").setMinZoom(7);
        }

        // 3. SÄHKÖLINJAT
        if (feature.canBeLine() && "line".equals(feature.getString("power"))) {
            features.line("power_lines").setMinZoom(8);
        }

        // 4. VESISTÖT: JOET
        if (feature.canBeLine() && "river".equals(feature.getString("waterway"))) {
            features.line("waterways").setMinZoom(6);
        }

        // 5. VESISTÖT: ALUEET
        if (feature.canBePolygon() && (feature.hasTag("natural", "water") || 
                                       feature.hasTag("landuse", "reservoir") || 
                                       feature.hasTag("waterway", "riverbank"))) {
            features.polygon("water_areas").setMinZoom(4);
        }

        // 6. MAASTOMUODOT & ASUTUSALUEET
        if (feature.canBePolygon()) {
            if (feature.hasTag("landuse", "forest") || feature.hasTag("natural", "wood")) {
                features.polygon("landuse").setAttr("class", "forest").setMinZoom(6);
            } 
            else if (feature.hasTag("landuse", "farmland") || feature.hasTag("landuse", "farm") || feature.hasTag("landuse", "meadow")) {
                features.polygon("landuse").setAttr("class", "field").setMinZoom(6);
            } 
            else if (feature.hasTag("natural", "wetland")) {
                features.polygon("landuse").setAttr("class", "swamp").setMinZoom(7);
            } 
            else if (feature.hasTag("landuse", "residential") || feature.hasTag("landuse", "commercial") || feature.hasTag("landuse", "industrial")) {
                features.polygon("landuse").setAttr("class", "urban").setMinZoom(5);
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

    @Override
    public String name() {
        return "AeroTools Custom VFR Base Map";
    }

    public static void main(String[] args) throws Exception {
        // Haetaan maa GitHub Actionsin ympäristömuuttujasta
        String country = System.getenv("MAP_COUNTRY");
        if (country == null || country.isEmpty()) {
            country = "finland"; // Fallback, jottei ohjelma kaadu lokaalissa testauksessa
        }
        
        // Määritetään Planetilerin parametrit suoraan koodissa -- etuliitteillä
        String[] planetilerArgs = new String[]{
            "--osm-path=data/raw.osm.pbf",
            "--output=data/" + country + "_vfr.pmtiles",
            "--maxzoom=11",
            "--nodata=true",
            "--force=true"
        };

        Planetiler.create(Arguments.fromArgs(planetilerArgs))
            .setProfile(new AeroToolsProfile())
            .run();
    }
}
