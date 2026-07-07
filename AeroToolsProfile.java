import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;

public class AeroToolsProfile implements Profile {

    @Override
    public void processFeature(SourceFeature feature, FeatureCollector features) {
        
        // 1. TIET (Vain pää- ja maantiet, ilman kaistatietoja tai pikkuteitä)
        if (feature.canBeLine() && feature.hasTag("highway")) {
            String highway = feature.getString("highway");
            if ("motorway".equals(highway) || "trunk".equals(highway) || 
                "primary".equals(highway) || "secondary".equals(highway) || 
                "tertiary".equals(highway)) {
                
                features.line("roads")
                    .setAttr("class", highway)
                    // Moottoritiet näkyviin aiemmin (zoom 4), pienemmät maantiet myöhemmin (zoom 6)
                    .setMinZoom("motorway".equals(highway) || "trunk".equals(highway) ? 4 : 6);
            }
        }

        // 2. RAUTATIET (Tärkeät VFR-kiintopisteet)
        if (feature.canBeLine() && "rail".equals(feature.getString("railway"))) {
            features.line("railways").setMinZoom(7);
        }

        // 3. SÄHKÖLINJAT (Suuret voimalinjat maamerkkeinä ja esteinä)
        if (feature.canBeLine() && "line".equals(feature.getString("power"))) {
            features.line("power_lines").setMinZoom(8);
        }

        // 4. VESISTÖT: JOET (Vain merkittävät joet viivoina, ei puroja/ojia)
        if (feature.canBeLine() && "river".equals(feature.getString("waterway"))) {
            features.line("waterways").setMinZoom(6);
        }

        // 5. VESISTÖT: ALUEET (Järvet, lammet ja merialueet)
        if (feature.canBePolygon() && (feature.hasTag("natural", "water") || 
                                       feature.hasTag("landuse", "reservoir") || 
                                       feature.hasTag("waterway", "riverbank"))) {
            features.polygon("water_areas").setMinZoom(4);
        }

        // 6. MAASTOMUODOT & ASUTUSALUEET (Metsät, pellot, suot ja kaupunkiblokit)
        if (feature.canBePolygon()) {
            // Metsät
            if (feature.hasTag("landuse", "forest") || feature.hasTag("natural", "wood")) {
                features.polygon("landuse").setAttr("class", "forest").setMinZoom(6);
            } 
            // Pellot
            else if (feature.hasTag("landuse", "farmland") || feature.hasTag("landuse", "farm") || feature.hasTag("landuse", "meadow")) {
                features.polygon("landuse").setAttr("class", "field").setMinZoom(6);
            } 
            // Suot
            else if (feature.hasTag("natural", "wetland")) {
                features.polygon("landuse").setAttr("class", "swamp").setMinZoom(7);
            } 
            // Asutusalueet (Yhdistetään asuin-, teollisuus- ja liikealueet yhdeksi blokiksi)
            else if (feature.hasTag("landuse", "residential") || feature.hasTag("landuse", "commercial") || feature.hasTag("landuse", "industrial")) {
                features.polygon("landuse").setAttr("class", "urban").setMinZoom(5);
            }
        }

        // 7. TEKSTIT JA NIMISTÖ (Vain kaupungit ja suuret taajamat)
        if (feature.isPoint() && feature.hasTag("place")) {
            String place = feature.getString("place");
            if ("city".equals(place) || "town".equals(place)) {
                String name = feature.getString("name");
                if (name != null && !name.isEmpty()) {
                    features.point("labels")
                        .setAttr("name", name)
                        .setAttr("class", place)
                        // Kaupungit näkyviin laajassa mittakaavassa, taajamat vasta lähempänä
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
        Planetiler.create(args)
            .setProfile(new AeroToolsProfile())
            .run();
    }
}
