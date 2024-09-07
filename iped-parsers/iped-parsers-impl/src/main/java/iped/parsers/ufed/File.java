package iped.parsers.ufed;

import java.util.Collections;
import java.util.List;

import org.apache.tika.metadata.XMPDM;

import iped.data.IItemReader;
import iped.parsers.util.ChildPornHashLookup;
import iped.parsers.util.HashUtils;
import iped.properties.ExtraProperties;

public class File {

    private IItemReader item;

    private List<String> childPornSets = Collections.emptyList();

    public File(IItemReader item) {
        this.item = item;

        String hash = getHash();
        if (hash != null) {
            childPornSets = ChildPornHashLookup.lookupHashAndMerge(hash, childPornSets);
        }
    }

    public IItemReader getItem() {
        return item;
    }

    public List<String> getChildPornSets() {
        return childPornSets;
    }

    public String getHash() {
        String hash = item.getHash();
        if (HashUtils.isValidHash(hash)) {
            return hash;
        }
        return null;
    }

    public Long getLength() {
        return item.getLength();
    }

    public String getTrueExt() {
        return item.getType();
    }

    public byte[] getThumb() {
        return item.getThumb();
    }

    public String getTranscription() {
        return item.getMetadata().get(ExtraProperties.TRANSCRIPT_ATTR);
    }

    public String getTranscriptConfidence() {
        return item.getMetadata().get(ExtraProperties.CONFIDENCE_ATTR);
    }

    public Float getDuration() {
        String duration = item.getMetadata().get(XMPDM.DURATION);
        if (duration != null) {
            try {
                return Float.parseFloat(duration);
            } catch (NumberFormatException e) {
            }
        }
        return null;
    }
}
