package iped.parsers.apple.dmg;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.catacombae.dmg.udif.UDIFInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import iped.data.IItemReader;
import iped.parsers.standard.StandardParser;
import iped.properties.MediaTypes;

public class DmgParser extends AbstractParser {

    private static Logger logger = LoggerFactory.getLogger(DmgParser.class);

    private static final long serialVersionUID = 1L;

    private static Set<MediaType> supportedTypes = getTypes();

    private static synchronized Set<MediaType> getTypes() {
        return MediaType.set(MediaTypes.DMG_IMAGE);
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext arg0) {
        return supportedTypes;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {

        try {
            File streamFile = ((TikaInputStream) stream).getFile();

            Metadata subMeta = new Metadata();
            subMeta.set(StandardParser.INDEXER_CONTENT_TYPE, "application/x-raw-image");
            subMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) + " [uncompressed]");

            UDIFInputStream subitemStream = new UDIFInputStream(new RandomAccessFile(streamFile, "r"), streamFile.getAbsolutePath());

            EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class, new ParsingEmbeddedDocumentExtractor(context));
            extractor.parseEmbedded(subitemStream, handler, subMeta, false);

        } catch (Exception e) {

            // abort if the DMG file is the root evidence
            IItemReader item = context.get(IItemReader.class);
            if (item != null && item.getParentId() == null) {
                logger.error("Error processing DMG file", e);
                System.exit(1);
            }

            throw e;
        }

    }
}
