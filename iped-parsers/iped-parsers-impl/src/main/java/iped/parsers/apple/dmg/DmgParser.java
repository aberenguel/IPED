package iped.parsers.apple.dmg;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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
import org.catacombae.dmg.udif.UDIFFileView;
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

            Metadata rawImageMeta = new Metadata();
            rawImageMeta.set(StandardParser.INDEXER_CONTENT_TYPE, MediaTypes.RAW_IMAGE.toString());
            rawImageMeta.set(TikaCoreProperties.TITLE, "Uncompressed_Image");

            try (UDIFFileView dmgView = new UDIFFileView(streamFile)) {
                rawImageMeta.set("dmg:kolyData", dmgView.getKoly().toString());
                rawImageMeta.set("dmg:plist", new String(dmgView.getPlistData(), StandardCharsets.UTF_8));
            }

            try (UDIFInputStream rawImageStream = new UDIFInputStream(new RandomAccessFile(streamFile, "r"),
                    streamFile.getAbsolutePath())) {
                EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class, new ParsingEmbeddedDocumentExtractor(context));
                extractor.parseEmbedded(rawImageStream, handler, rawImageMeta, false);
            }

        } catch (Exception e) {

            // abort if the DMG file is the root evidence
            IItemReader item = context.get(IItemReader.class);
            if (item != null && item.isRoot()) {
                logger.error("Error processing DMG file", e);
                System.exit(1);
            }

            throw e;
        }
    }
}
