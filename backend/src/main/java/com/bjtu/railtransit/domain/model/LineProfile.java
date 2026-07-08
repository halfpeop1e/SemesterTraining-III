package com.bjtu.railtransit.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LineProfile {

    private String lineId;
    private String lineName;
    private String direction;
    private double totalLengthKm;
    private List<Station> stations;

    public LineProfile() {
    }

    public String getLineId() {
        return lineId;
    }

    public void setLineId(String lineId) {
        this.lineId = lineId;
    }

    public String getLineName() {
        return lineName;
    }

    public void setLineName(String lineName) {
        this.lineName = lineName;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public double getTotalLengthKm() {
        return totalLengthKm;
    }

    public void setTotalLengthKm(double totalLengthKm) {
        this.totalLengthKm = totalLengthKm;
    }

    public List<Station> getStations() {
        return stations;
    }

    public void setStations(List<Station> stations) {
        this.stations = stations;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Station {
        private int id;
        private String name;
        private String code;
        private double km;

        public Station() {
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public double getKm() {
            return km;
        }

        public void setKm(double km) {
            this.km = km;
        }
    }
}
