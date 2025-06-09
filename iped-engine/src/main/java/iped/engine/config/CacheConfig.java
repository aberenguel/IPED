package iped.engine.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Path;

import org.ehcache.PersistentCacheManager;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;

import iped.utils.UTF8Properties;

public class CacheConfig extends AbstractPropertiesConfigurable {

    private static final long serialVersionUID = 1L;
    public static final String CONFIG_FILE = "CacheConfig.txt";

    private String diskStoreDir;
    private int heapPoolSizeInMB;
    private int offHeapPoolSizeInMB;
    private int diskPoolSizeInMB;

    private PersistentCacheManager cacheManager;

    public static final DirectoryStream.Filter<Path> filter = new Filter<Path>() {
        @Override
        public boolean accept(Path entry) throws IOException {
            return entry.endsWith(CONFIG_FILE);
        }
    };

    public CacheConfig() {
        cacheManager = CacheManagerBuilder.newCacheManagerBuilder()//
                .with(CacheManagerBuilder.persistence(diskStoreDir))//
                .build(true);
    }

    @Override
    public Filter<Path> getResourceLookupFilter() {
        return filter;
    }

    @Override
    public void processProperties(UTF8Properties properties) {

        // String hashes = properties.getProperty("hashes");

    }

    public ResourcePoolsBuilder getDefaultResourcePoolsBuilder() {
        return ResourcePoolsBuilder.newResourcePoolsBuilder() //
                .heap(heapPoolSizeInMB, MemoryUnit.MB) //
                .offheap(offHeapPoolSizeInMB, MemoryUnit.MB) //
                .disk(diskPoolSizeInMB, MemoryUnit.MB, true);
    }

    public PersistentCacheManager getCacheManager() {
        return cacheManager;
    }

}
