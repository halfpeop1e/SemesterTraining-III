package com.bjtu.railtransit.domain.model;

import java.util.List;

public class TrackGeometry {

    private List<TrackWaypoint> trackWaypoints;
    private List<TrackSegment> segments;
    private List<PlatformGeo> platforms;

    public List<TrackWaypoint> getTrackWaypoints() { return trackWaypoints; }
    public void setTrackWaypoints(List<TrackWaypoint> v) { this.trackWaypoints = v; }
    public List<TrackSegment> getSegments() { return segments; }
    public void setSegments(List<TrackSegment> v) { this.segments = v; }
    public List<PlatformGeo> getPlatforms() { return platforms; }
    public void setPlatforms(List<PlatformGeo> v) { this.platforms = v; }

    public static class TrackWaypoint {
        private double km;
        private double lat;
        private double lng;
        private String name;
        private int direction;

        public double getKm() { return km; }
        public void setKm(double v) { this.km = v; }
        public double getLat() { return lat; }
        public void setLat(double v) { this.lat = v; }
        public double getLng() { return lng; }
        public void setLng(double v) { this.lng = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public int getDirection() { return direction; }
        public void setDirection(int v) { this.direction = v; }
    }

    public static class TrackSegment {
        private int id;
        private double lengthM;
        private int startPointId;
        private int endPointId;
        private Integer forwardNextSegId;
        private Integer sideNextSegId;
        private Integer endForwardSegId;
        private Integer endSideSegId;
        private int zcId;
        private int atsId;
        private int ciId;

        public int getId() { return id; }
        public void setId(int v) { this.id = v; }
        public double getLengthM() { return lengthM; }
        public void setLengthM(double v) { this.lengthM = v; }
        public int getStartPointId() { return startPointId; }
        public void setStartPointId(int v) { this.startPointId = v; }
        public int getEndPointId() { return endPointId; }
        public void setEndPointId(int v) { this.endPointId = v; }
        public Integer getForwardNextSegId() { return forwardNextSegId; }
        public void setForwardNextSegId(Integer v) { this.forwardNextSegId = v; }
        public Integer getSideNextSegId() { return sideNextSegId; }
        public void setSideNextSegId(Integer v) { this.sideNextSegId = v; }
        public Integer getEndForwardSegId() { return endForwardSegId; }
        public void setEndForwardSegId(Integer v) { this.endForwardSegId = v; }
        public Integer getEndSideSegId() { return endSideSegId; }
        public void setEndSideSegId(Integer v) { this.endSideSegId = v; }
        public int getZcId() { return zcId; }
        public void setZcId(int v) { this.zcId = v; }
        public int getAtsId() { return atsId; }
        public void setAtsId(int v) { this.atsId = v; }
        public int getCiId() { return ciId; }
        public void setCiId(int v) { this.ciId = v; }
    }

    public static class PlatformGeo {
        private int id;
        private double km;
        private String direction;

        public int getId() { return id; }
        public void setId(int v) { this.id = v; }
        public double getKm() { return km; }
        public void setKm(double v) { this.km = v; }
        public String getDirection() { return direction; }
        public void setDirection(String v) { this.direction = v; }
    }
}
