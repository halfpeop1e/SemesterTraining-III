package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.LineProfile;
import com.bjtu.railtransit.domain.model.StationGeo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class LineDataService {

    private LineProfile cachedLineProfile;
    private List<StationGeo> cachedStationGeoList;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LineProfile getLineProfile() {
        if (cachedLineProfile != null) {
            return cachedLineProfile;
        }
        try {
            ClassPathResource resource = new ClassPathResource("configs/line-profile.json");
            try (InputStream is = resource.getInputStream()) {
                cachedLineProfile = objectMapper.readValue(is, LineProfile.class);
            }
            return cachedLineProfile;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load line-profile.json", e);
        }
    }

    public List<StationGeo> getStationGeoList() {
        if (cachedStationGeoList != null) {
            return cachedStationGeoList;
        }
        try {
            ClassPathResource resource = new ClassPathResource("configs/line9-stations-geo.json");
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode stationsNode = root.get("stations");
                cachedStationGeoList = new ArrayList<>();
                if (stationsNode != null && stationsNode.isArray()) {
                    for (JsonNode node : stationsNode) {
                        StationGeo geo = objectMapper.treeToValue(node, StationGeo.class);
                        cachedStationGeoList.add(geo);
                    }
                }
            }
            return cachedStationGeoList;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load line9-stations-geo.json", e);
        }
    }
}
