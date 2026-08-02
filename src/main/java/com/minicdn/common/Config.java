package com.minicdn.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.util.List;
import java.util.Map;

public class Config {
    public List<EdgeInfo> edges;
    public Map<String, String> ipRegionMapping;
    public int routerPort;
    public String originHost;
    public int originPort;
    public long cacheTtlSeconds;
    public int cacheMaxSize;

    public static Config load(String path) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(path), Config.class);
    }
}