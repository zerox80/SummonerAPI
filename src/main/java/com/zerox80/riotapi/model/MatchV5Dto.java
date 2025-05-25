package com.zerox80.riotapi.model;

public class MatchV5Dto {
    private MetadataDto metadata;
    private InfoDto info;

    public MetadataDto getMetadata() {
        return metadata;
    }
    public void setMetadata(MetadataDto metadata) {
        this.metadata = metadata;
    }
    public InfoDto getInfo() {
        return info;
    }
    public void setInfo(InfoDto info) {
        this.info = info;
    }
}