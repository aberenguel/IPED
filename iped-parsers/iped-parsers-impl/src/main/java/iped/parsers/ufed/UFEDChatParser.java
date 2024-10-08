package iped.parsers.ufed;

import static iped.parsers.ufed.UfedUtils.readUfedMetadata;
import static iped.parsers.ufed.UfedUtils.readUfedMetadataArray;
import static iped.parsers.ufed.UfedUtils.removeUfedMetadata;
import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.tika.config.Field;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.google.common.collect.ImmutableMap;

import iped.data.IItemReader;
import iped.parsers.standard.StandardParser;
import iped.parsers.util.ConversationUtils;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;
import iped.properties.MediaTypes;
import iped.search.IItemSearcher;
import iped.utils.EmptyInputStream;

public class UFEDChatParser extends AbstractParser {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    private static Logger logger = LoggerFactory.getLogger(UFEDChatParser.class);

    public static final MediaType UFED_CHAT_MIME = MediaType.application("x-ufed-chat"); //$NON-NLS-1$
    public static final MediaType UFED_CHAT_WA_MIME = MediaType.application("x-ufed-chat-whatsapp"); //$NON-NLS-1$
    public static final MediaType UFED_CHAT_TELEGRAM_MIME = MediaType.application("x-ufed-chat-telegram"); //$NON-NLS-1$

    public static final MediaType UFED_CHAT_PREVIEW_MIME = MediaType.application("x-ufed-chat-preview");

    public static final String UFED_REPLIED_MESSAGE_ATTR = "ufedOriginalMessage";

    public static final Map<String, MediaType> appToMime = ImmutableMap.of( //
            "whatsapp", MediaType.application("x-ufed-chat-preview-whatsapp"), //
            "telegram", MediaType.application("x-ufed-chat-preview-telegram"), //
            "skype", MediaType.application("x-ufed-chat-preview-skype"), //
            "facebook", MediaType.application("x-ufed-chat-preview-facebook"), //
            "instagram", MediaType.application("x-ufed-chat-preview-instagram"));

    public static final Property META_FROM_OWNER = Property
            .internalBoolean(ExtraProperties.UFED_META_PREFIX + "fromOwner"); //$NON-NLS-1$

    public static final String ATTACHED_MEDIA_MSG = "ATTACHED_MEDIA: ";

    private boolean extractMessages = true;
    private boolean extractActivityLogs = true;
    private boolean extractAttachments = true;
    private boolean extractSharedContacts = true;
    private boolean ignoreEmptyChats = false;
    private int minChatSplitSize = 6000000;

    private static Set<MediaType> supportedTypes = MediaType.set(UFED_CHAT_MIME, UFED_CHAT_WA_MIME,
            UFED_CHAT_TELEGRAM_MIME);

    private static final Map<String, String> chatTypeMap = ImmutableMap.of( //
            "OneOnOne", ConversationUtils.TYPE_PRIVATE, //
            "Group", ConversationUtils.TYPE_GROUP, //
            "Broadcast", ConversationUtils.TYPE_BROADCAST);

    public static void ignoreSupportedChats() {
        supportedTypes = MediaType.set(UFED_CHAT_MIME);
    }

    public static MediaType getMediaType(String source) {
        if (source != null) {
            source = source.split(" ")[0].toLowerCase();
            if (appToMime.containsKey(source)) {
                return appToMime.get(source);
            }
        }
        return UFED_CHAT_PREVIEW_MIME;
    }

    @Field
    public void setExtractMessages(boolean extractMessages) {
        this.extractMessages = extractMessages;
    }

    @Field
    public void setExtractActivityLogs(boolean extractActivityLogs) {
        this.extractActivityLogs = extractActivityLogs;
    }

    @Field
    public void setExtractAttachments(boolean extractAttachments) {
        this.extractAttachments = extractAttachments;
    }

    @Field
    public void setExtractSharedContacts(boolean extractSharedContacts) {
        this.extractSharedContacts = extractSharedContacts;
    }

    @Field
    public void setIgnoreEmptyChats(boolean ignoreEmptyChats) {
        this.ignoreEmptyChats = ignoreEmptyChats;
    }

    @Field
    public void setMinChatSplitSize(int minChatSplitSize) {
        this.minChatSplitSize = minChatSplitSize;
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext arg0) {
        return supportedTypes;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void parse(InputStream inputStream, ContentHandler handler, Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {

        // process Chat
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();
        try {
            IItemSearcher searcher = context.get(IItemSearcher.class);
            IItemReader chat = context.get(IItemReader.class);
            EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                    new ParsingEmbeddedDocumentExtractor(context));

            if (chat == null || searcher == null)
                return;

            updateChatMetadata(chatMeta, searcher);
            Chat chat = createChat(chatItem, searcher);

            List<Message> messages = new ArrayList<>();
            List<IItemReader> chatChildren = chatItem.getChildren();
            if (chatChildren != null) {
                for (IItemReader chatChild : chatChildren) {

                    if (chatChild.getMediaType().equals(MediaTypes.UFED_MESSAGE_MIME)) {

                        updateMessageMetadata(metadata, chatChild.getMetadata(), searcher);

                        Message message = createMessage(chatChild, searcher);
                        updateQuotedMessage(message, searcher, messages);
                        messages.add(message);
                    } else {
                        logger.error("Unknown chat child: {}", chatChild);
                    }
                }
            }

            int messagesCount = (int) messages.stream().filter(m -> !m.isSystemMessage()).count();
            if (messagesCount == 0 && ignoreEmptyChats) {
                return;
            }
            chatMeta.set(ExtraProperties.CONVERSATION_MESSAGES_COUNT, messagesCount);

            Collections.sort(messages);

            updateChatMetadata(metadata, messagesCount, searcher);

            String virtualId = readUfedMetadata(metadata, "id");
            String chatPrefix = getChatName(metadata);

            if (extractor.shouldParseEmbedded(metadata)) {
                ReportGenerator reportGenerator = new ReportGenerator(minChatSplitSize);
                byte[] bytes = reportGenerator.generateNextChatHtml(chat, messages);
                int frag = 0;
                int firstMsg = 0;
                MediaType previewMime = getMediaType(readUfedMetadata(metadata, "Source"));
                while (bytes != null) {
                    Metadata chatMetadata = new Metadata();
                    int nextMsg = reportGenerator.getNextMsgNum();
                    List<Message> subList = messages.subList(firstMsg, nextMsg);
                    storeLinkedHashes(subList, chatMetadata);

                    firstMsg = nextMsg;
                    byte[] nextBytes = reportGenerator.generateNextChatHtml(chat, messages);

                    // copy parent metadata
                    for (String name : metadata.names()) {
                        if (name.startsWith(ExtraProperties.UFED_META_PREFIX) || name.startsWith(ExtraProperties.CONVERSATION_PREFIX))
                            for (String val : metadata.getValues(name))
                                chatMetadata.add(name, val);
                    }

                    String chatName = chatPrefix;
                    if (frag > 0 || nextBytes != null)
                        chatName += "_" + frag++; //$NON-NLS-1$

                    chatMetadata.set(TikaCoreProperties.TITLE, chatName);
                    chatMetadata.set(ExtraProperties.ITEM_VIRTUAL_ID, virtualId);
                    chatMetadata.set(StandardParser.INDEXER_CONTENT_TYPE, previewMime.toString());
                    chatMetadata.set(ExtraProperties.DECODED_DATA, Boolean.TRUE.toString());

                    if (extractMessages && !subList.isEmpty()) {
                        chatMetadata.set(BasicProps.HASCHILD, Boolean.TRUE.toString());
                    }

                    ByteArrayInputStream chatStream = new ByteArrayInputStream(bytes);
                    extractor.parseEmbedded(chatStream, handler, chatMetadata, false);
                    bytes = nextBytes;

                    if (extractMessages) {
                        extractMessages(subList, virtualId, handler, extractor);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw e;

        } finally {
            xhtml.endDocument();
        }
    }

    private void updateChatMetadata(Metadata chatMetadata, int messagesCount, IItemSearcher searcher) {

        // Communication:Account
        fillAccountInfo(searcher, chatMetadata);

        // Communication:{Participants, Admins}
        fillParticipantInfo(searcher, chatMetadata, chatMetadata, "Participants", ExtraProperties.CONVERSATION_PARTICIPANTS);
        fillParticipantInfo(searcher, chatMetadata, chatMetadata, "Admins", ExtraProperties.CONVERSATION_ADMINS);

        // Communication:IsAdmin
        if (Boolean.parseBoolean(readUfedMetadata(chatMetadata, "IsOwnerGroupAdmin"))) {
            chatMetadata.set(ExtraProperties.CONVERSATION_IS_ADMIN, true);
        }
        removeUfedMetadata(chatMetadata, "IsOwnerGroupAdmin");

        // Communication:Type
        String ufedChatType = readUfedMetadata(chatMetadata, "ChatType");
        if (ufedChatType != null && chatTypeMap.containsKey(ufedChatType)) {
            chatMetadata.set(ExtraProperties.CONVERSATION_TYPE, chatTypeMap.get(ufedChatType));
        } else {
            chatMetadata.set(ExtraProperties.CONVERSATION_TYPE, ConversationUtils.TYPE_UNKONWN);
        }

        // Communication:{ID, Name, MessagesCount}
        chatMetadata.set(ExtraProperties.CONVERSATION_ID,                readUfedMetadata(chatMetadata,  "Identifier"));
        chatMetadata.set(ExtraProperties.CONVERSATION_NAME,                readUfedMetadata(chatMetadata,  "Name"));
        chatMetadata.set(ExtraProperties.CONVERSATION_MESSAGES_COUNT, messagesCount);
    }

    private void updateMessageMetadata(Metadata chatMetadata, Metadata messageMetadata, IItemSearcher searcher) {

        // Communication:Direction
        if (Boolean.parseBoolean(messageMetadata.get(META_FROM_OWNER))) {
            messageMetadata.set(ExtraProperties.COMMUNICATION_DIRECTION, ConversationUtils.DIRECTION_OUTGOING);
        } else {
            messageMetadata.set(ExtraProperties.COMMUNICATION_DIRECTION, ConversationUtils.DIRECTION_INCOMING);
        }

        // Fix missing "ufed:To" metadata
        List<String> fromIds = readUfedMetadataArray(messageMetadata, "From:ID");
        List<String> toIds = readUfedMetadataArray(messageMetadata, "To:ID");
        if (toIds.size() != 1) {
            List<String> toList;
            String toName = null;
            if (toIds.size() > 0) {
                toList = toIds;
            } else {
                toList = new ArrayList<>();
                List<String> partiesIds = readUfedMetadataArray( chatMetadata, "Participants:ID");
                for (int i = 0; i < partiesIds.size(); i++) {
                    String partyId = partiesIds.get(i);
                    if (!fromIds.contains(partyId)) {
                        toList.add(partyId);
                        toName = readUfedMetadataArray(chatMetadata, "Participants:Name").get(i);
                    }
                }
            }
            if (toList.size() == 1) {
                messageMetadata.set(ExtraProperties.UFED_META_PREFIX + "To:ID", defaultIfEmpty(toList.get(0), ""));
                messageMetadata.set(ExtraProperties.UFED_META_PREFIX + "To:Name", defaultIfEmpty(toName, ""));
            } else if (toList.size() > 1) {
                messageMetadata.set(ExtraProperties.UFED_META_PREFIX + "To:ID", defaultIfEmpty(readUfedMetadata(chatMetadata, "Identifier"), ""));
                messageMetadata.set(ExtraProperties.UFED_META_PREFIX + "To:Name", defaultIfEmpty(readUfedMetadata(chatMetadata, "name"), ""));
                messageMetadata.set(ExtraProperties.COMMUNICATION_IS_GROUP_MESSAGE, true);
            }

        }

        // Communication:{From, To}
        fillParticipantInfo(searcher, chatMetadata, messageMetadata, "From", ExtraProperties.COMMUNICATION_FROM);
        fillParticipantInfo(searcher, chatMetadata, messageMetadata, "To", ExtraProperties.COMMUNICATION_TO);
    }

    private IItemReader lookupAccount(IItemSearcher searcher, Metadata chatMetadata) {
        String account = readUfedMetadata(chatMetadata, "Account");
        if (StringUtils.isBlank(account)) {
            return null;
        }

        String source = readUfedMetadata(chatMetadata, "Source");
        String query = BasicProps.CONTENTTYPE + ":\"" + MediaTypes.UFED_USER_ACCOUNT_MIME.toString() + "\"" //
                + " && " + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "Source") + ":\"" + source + "\"" //
                + " && (" + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "UserID") + ":\"" + account + "\"" //
                + " || " + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "PhoneNumber") + ":\"" + account
                + "\"" //
                + " || " + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "Username") + ":\"" + account + "\"" //
                + ")";

        List<IItemReader> results = searcher.search(query);
        if (!results.isEmpty()) {
            if (results.size() > 1) {
                logger.warn("Found more than one account for [{}]: {}", account, results);
            }
            return results.get(0);
        }

        return null;
    }

    private void fillAccountInfo(IItemSearcher searcher, Metadata chatMetadata) {
        String name, id, phone = null, username = null;
        String source = readUfedMetadata(chatMetadata, "Source");
        IItemReader account = lookupAccount(searcher, chatMetadata);
        if (account != null) {
            name = readUfedMetadata(account, "name");
            id = readUfedMetadata(account, "UserID");
            phone = readUfedMetadata(account, "PhoneNumber");
            username = readUfedMetadata(account, "Username");
            if (phone != null) {
                phone = StringUtils.substringBefore(phone, " ");
            }

            chatMetadata.add(ExtraProperties.LINKED_ITEMS, BasicProps.ID + ":" + account.getId());
        } else {
            id = readUfedMetadata(chatMetadata, "Owner:ID");
            name = readUfedMetadata(chatMetadata, "Owner:Name");
        }

        chatMetadata.add(ExtraProperties.CONVERSATION_ACCOUNT, ConversationUtils.buidPartyString(name, id, phone, username));
        if (name != null)
            chatMetadata.add(ExtraProperties.CONVERSATION_ACCOUNT + ExtraProperties.CONVERSATION_SUFFIX_NAME, name);
        if (id != null)
            chatMetadata.add(ExtraProperties.CONVERSATION_ACCOUNT + ExtraProperties.CONVERSATION_SUFFIX_ID, id);
        if (phone != null)
            chatMetadata.add(ExtraProperties.CONVERSATION_ACCOUNT + ExtraProperties.CONVERSATION_SUFFIX_PHONE, phone);
        if (username != null)
            chatMetadata.add(ExtraProperties.CONVERSATION_ACCOUNT + ExtraProperties.CONVERSATION_SUFFIX_USERNAME, username);

        removeUfedMetadata(chatMetadata, "Owner:ID");
        removeUfedMetadata(chatMetadata, "Owner:Name");
    }

    private IItemReader lookupParticipant(IItemSearcher searcher, Metadata chatMetadata, String userID) {
        if (StringUtils.isBlank(userID)) {
            return null;
        }

        String account = readUfedMetadata(chatMetadata, "Account");
        String source = readUfedMetadata(chatMetadata, "Source");
        String query = BasicProps.CONTENTTYPE + ":\"" + MediaTypes.UFED_CONTACT_MIME.toString() + "\"" //
                + " && " + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "Account") + ":\"" + account + "\"" //
                + " && " + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "Source") + ":\"" + source + "\"" //
                + " && " + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "Type") + ":ChatParticipant" //
                + " && " + searcher.escapeQuery(ExtraProperties.UFED_META_PREFIX + "UserID") + ":\"" + userID + "\"";

        List<IItemReader> results = searcher.search(query);
        if (!results.isEmpty()) {
            if (results.size() > 1) {
                logger.warn("Found more than one participant for [{}]: {}", account, results);
            }
            return results.get(0);
        }

        return null;
    }

    private void fillParticipantInfo(IItemSearcher searcher, Metadata chatMetadata, Metadata targetMetadata,
            String ufedProperty, String conversationProperty) {
        String source = readUfedMetadata(chatMetadata, "Source");
        List<String> partyIDs = readUfedMetadataArray(targetMetadata, ufedProperty + ":ID");
        List<String> partyNames = readUfedMetadataArray(targetMetadata, ufedProperty + ":Name");
        for (int i = 0; i < partyIDs.size(); i++) {
            String partyID = partyIDs.get(i);
            String name = null, phone = null, username = null;

            IItemReader participant = lookupParticipant(searcher, chatMetadata, partyID);
            if (participant != null) {
                name = readUfedMetadata(participant, "name");
                List<String> ids = readUfedMetadataArray(participant, "UserID");
                phone = readUfedMetadata(participant, "PhoneNumber");
                if (phone != null) {
                    phone = StringUtils.substringBefore(phone, " ");
                }
                if (ids.size() >= 2) {
                    if (ids.get(0).equals(partyID)) {
                        username = ids.get(1);
                    } else {
                        username = ids.get(0);
                    }
                }
                targetMetadata.add(ExtraProperties.LINKED_ITEMS, BasicProps.ID + ":" + participant.getId());
            } else {
                name = partyNames.get(i);
                if (name.isEmpty())
                    name = null;
            }

            targetMetadata.add(conversationProperty, ConversationUtils.buidPartyString(name, partyID, phone, username));
            if (!partyID.isEmpty())
                targetMetadata.add(conversationProperty + ExtraProperties.CONVERSATION_SUFFIX_ID, partyID);
            if (name != null)
                targetMetadata.add(conversationProperty + ExtraProperties.CONVERSATION_SUFFIX_NAME, name);
            if (phone != null)
                targetMetadata.add(conversationProperty + ExtraProperties.CONVERSATION_SUFFIX_PHONE, phone);
            if (username != null)
                targetMetadata.add(conversationProperty + ExtraProperties.CONVERSATION_SUFFIX_USERNAME, username);
        }

        removeUfedMetadata(targetMetadata, ufedProperty + ":ID");
        removeUfedMetadata(targetMetadata, ufedProperty + ":Name");
    }

    private void extractMessages(List<Message> subList, String chatVirtualId, ContentHandler handler,
            EmbeddedDocumentExtractor extractor) throws SAXException, IOException {

        for (Message message : subList) {

            IItemReader messageItem = message.getItem();
            String messageVirtualId = message.getUfedId();

            Metadata messageMetaData = messageItem.getMetadata();
            messageMetaData.set(TikaCoreProperties.TITLE, messageItem.getName());
            messageMetaData.set(ExtraProperties.ITEM_VIRTUAL_ID, messageVirtualId);
            messageMetaData.set(StandardParser.INDEXER_CONTENT_TYPE, messageItem.getMediaType().toString());
            messageMetaData.set(ExtraProperties.PARENT_VIRTUAL_ID, chatVirtualId);
            messageMetaData.set(ExtraProperties.PARENT_VIEW_POSITION, message.getSourceIndex());
            messageMetaData.set(BasicProps.LENGTH, "");
            if (!message.getAttachments().isEmpty()) {
                messageMetaData.set(ExtraProperties.MESSAGE_ATTACHMENT_COUNT, message.getAttachments().size());
            }

            if (messageItem.isDeleted()) {
                messageMetaData.set(ExtraProperties.DELETED, Boolean.toString(true));
            }

            extractor.parseEmbedded(new EmptyInputStream(), handler, messageMetaData, false);

            if (extractActivityLogs) {
                extractActivityLog(message, messageVirtualId, handler, extractor);
            }
            if (extractAttachments) {
                extractAttachments(message, messageVirtualId, handler, extractor);
            }
            if (extractSharedContacts) {
                extractShareContacts(message, messageVirtualId, handler, extractor);
            }
        }
    }

    private void extractActivityLog(Message message, String messageVirtualId, ContentHandler handler,
            EmbeddedDocumentExtractor extractor) throws SAXException, IOException {

        for (MessageChatActivity activity : message.getActivityLog()) {
            IItemReader activityItem = activity.getItem();
            Metadata activityMeta = activityItem.getMetadata();
            activityMeta.set(TikaCoreProperties.TITLE, activityItem.getName());
            activityMeta.set(StandardParser.INDEXER_CONTENT_TYPE, activityItem.getMediaType().toString());
            activityMeta.set(ExtraProperties.PARENT_VIRTUAL_ID, messageVirtualId);
            activityMeta.set(BasicProps.LENGTH, "");
            extractor.parseEmbedded(new EmptyInputStream(), handler, activityMeta, false);
        }
    }

    private void extractAttachments(Message message, String messageVirtualId, ContentHandler handler,
            EmbeddedDocumentExtractor extractor) throws SAXException, IOException {

        for (MessageAttachment attach : message.getAttachments()) {
            IItemReader attachItem = attach.getItem();
            Metadata attachMeta = attachItem.getMetadata();
            attachMeta.set(TikaCoreProperties.TITLE, attachItem.getName());
            attachMeta.set(StandardParser.INDEXER_CONTENT_TYPE, attachItem.getMediaType().toString());
            attachMeta.set(ExtraProperties.PARENT_VIRTUAL_ID, messageVirtualId);
            attachMeta.set(BasicProps.LENGTH, "");
            extractor.parseEmbedded(new EmptyInputStream(), handler, attachMeta, false);
        }
    }

    private void extractShareContacts(Message message, String messageVirtualId, ContentHandler handler,
            EmbeddedDocumentExtractor extractor) throws SAXException, IOException {

        for (MessageContact shareContact : message.getSharedContacts()) {
            IItemReader shareContactItem = shareContact.getItem();
            Metadata shareContactMeta = shareContactItem.getMetadata();
            shareContactMeta.set(TikaCoreProperties.TITLE, shareContact.getType() + shareContactItem.getName());
            shareContactMeta.set(StandardParser.INDEXER_CONTENT_TYPE, shareContactItem.getMediaType().toString());
            shareContactMeta.set(ExtraProperties.PARENT_VIRTUAL_ID, messageVirtualId);
            shareContactMeta.set(BasicProps.LENGTH, "");
            extractor.parseEmbedded(new EmptyInputStream(), handler, shareContactMeta, false);
        }
    }

    @SuppressWarnings("unchecked")
    private Message createMessage(IItemReader messageItem, IItemSearcher searcher) {

        Message message = new Message(messageItem);
        handleMessagePosition(message, searcher);

        List<IItemReader> msgChildren = messageItem.getChildren();
        if (msgChildren != null) {
            for (IItemReader msgChild : msgChildren) {
                if (msgChild.getMediaType().equals(MediaTypes.UFED_CHATACTIVITY_MIME)) {
                    handleActivityLog(message, msgChild, searcher);
                } else if (msgChild.getMediaType().equals(MediaTypes.UFED_ATTACH_MIME)) {
                    handleMessageAttachment(message, msgChild, searcher);
                } else if (msgChild.getMediaType().equals(MediaTypes.UFED_CONTACT_MIME)) {
                    handleMessageSharedContact(message, msgChild, searcher);
                } else {
                    logger.error("Unknown message child: {}", msgChild);
                }
            }
        }

        Collections.sort(message.getAttachments());
        Collections.sort(message.getSharedContacts());

        return message;
    }

    private void handleActivityLog(Message message, IItemReader activityItem, IItemSearcher searcher) {
        message.addActivityLog(activityItem);
    }

    private void handleMessageAttachment(Message message, IItemReader attachmentItem, IItemSearcher searcher) {
        MessageAttachment attach = message.addAttachment(attachmentItem);

        // attachment "ufed:file_id" metadata contains the "ufed:id" metadata of the file
        String query = searcher.escapeQuery(ExtraProperties.UFED_ID) + ":\"" + attach.getFileId() + "\"";
        List<IItemReader> fileItems = searcher.search(query);
        if (!fileItems.isEmpty()) {
            if (fileItems.size() > 1) {
                logger.warn("Found more than 1 file for attachment: {}", fileItems);
            }
            attach.setReferencedFile(fileItems.get(0));
        }
    }

    private void handleMessageSharedContact(Message message, IItemReader sharedContactItem, IItemSearcher searcher) {

        MessageContact contact = message.addSharedContact(sharedContactItem);

        // shared contact and indexed contact have the same "ufed:id" metadata
        String query = searcher.escapeQuery(ExtraProperties.UFED_ID) + ":\"" + contact.getUfedId() + "\"";
        List<IItemReader> contactItems = searcher.search(query);
        if (!contactItems.isEmpty()) {
            if (contactItems.size() > 1) {
                logger.warn("Found more than 1 contact for shared contact: {}", contactItems);
            }
            contact.setReferencedContact(contactItems.get(0));
        }
    }

    private void handleMessagePosition(Message message, IItemSearcher searcher) {

        if (StringUtils.isBlank(message.getCoordinateId())) {
            return;
        }

        // the message and localizations shares the same "ufed:coordinate_id" that was added when merging in UfedXmlReader
        String query = searcher.escapeQuery(ExtraProperties.UFED_COORDINATE_ID) + ":\"" + message.getCoordinateId() + "\"";
        List<IItemReader> locatizationItems = searcher.search(query);
        if (!locatizationItems.isEmpty()) {
            if (locatizationItems.size() > 1) {
                logger.warn("Found more than 1 localization for coordinate: {}", locatizationItems);
            }
            message.setReferencedLocalization(locatizationItems.get(0));
        }
    }

    private void updateQuotedMessage(Message message, IItemSearcher searcher, List<Message> messagesSoFar) {
        if (!message.isQuoted()) {
            return;
        }

        // loookup in previous messages
        String originalMsgId = message.getOriginalMessageID();
        Message quotedMessage = null;
        if (originalMsgId != null && messagesSoFar != null) {
            for (Message prevMessage : messagesSoFar) {
                if (originalMsgId.equals(prevMessage.getIdentifier())) {
                    quotedMessage = prevMessage;
                    break;
                }
            }
        }

        // get the replied set in report.xml
        if (quotedMessage == null) {
            IItemReader repliedMessage = (IItemReader) message.getItem().getExtraAttribute(UFED_REPLIED_MESSAGE_ATTR);
            if (repliedMessage != null) {
                quotedMessage = createMessage(repliedMessage, searcher);
            }
        }

        message.getItem().getExtraAttributeMap().remove(UFED_REPLIED_MESSAGE_ATTR);

        message.setMessageQuote(quotedMessage);
    }

    public static String getChatName(Metadata metadata) {

        String accountId = metadata.get(ExtraProperties.CONVERSATION_ACCOUNT + ExtraProperties.CONVERSATION_SUFFIX_ID);
        String source = readUfedMetadata(metadata, "Source");
        String chatType = readUfedMetadata(metadata, "ChatType");
        String name = readUfedMetadata(metadata, "Name");
        String id = readUfedMetadata(metadata, "Identifier");
        String ufedId = readUfedMetadata(metadata, "id");
        String[] participants = metadata.getValues(ExtraProperties.CONVERSATION_PARTICIPANTS);
        String[] participantsIds = metadata.getValues(ExtraProperties.CONVERSATION_PARTICIPANTS + ExtraProperties.CONVERSATION_SUFFIX_ID);

        StringBuilder sb = new StringBuilder();
        sb.append(source).append(' ');

        if (!"Unknown".equalsIgnoreCase(chatType)) {
            if ("OneOnOne".equalsIgnoreCase(chatType)) {
                sb.append("Chat");
            } else if ("Telegram".equalsIgnoreCase(source) && "Broadcast".equalsIgnoreCase(chatType)) {
                sb.append("Channel");
            } else if (chatTypeMap.containsKey(chatType)) {
                sb.append(chatTypeMap.get(chatType));
            } else {
                sb.append(chatType);
            }
            sb.append(' ');
        }
        sb.append("- ");

        if (name != null) {
            sb.append(name);
            if (id != null) {
                sb.append(" (ID:").append(id).append(")");
            }
        } else if (participantsIds.length == 2) {
            if (participantsIds[0].equals(accountId)) {
                sb.append(participants[1]);
            } else if (participantsIds[1].equals(accountId)) {
                sb.append(participants[0]);
            } else {
                sb.append(participants[0]).append('_').append(participants[1]);
            }
        } else if (participants.length == 1) {
            sb.append(participants[0]);
        } else if (id != null) {
            sb.append("ID:").append(id);
        } else if (ufedId != null) {
            sb.append(ufedId);
        }

        return sb.toString();
    }

    private void storeLinkedHashes(List<Message> messages, Metadata chatMetadata) {
        for (Message message : messages) {

            for (MessageAttachment attachment : message.getAttachments()) {

                if (attachment.getReferencedFile() != null) {
                    String hash = attachment.getReferencedFile().getHash();

                    if (hash != null) {
                        chatMetadata.add(ExtraProperties.LINKED_ITEMS, BasicProps.HASH + ":" + hash);
                        if (message.isFromMe())
                            chatMetadata.add(ExtraProperties.SHARED_HASHES, hash);

                        // replace linkedItems metadata that used "ufed:id" as linker
                        attachment.getItem().getMetadata().set(ExtraProperties.LINKED_ITEMS, BasicProps.HASH + ":" + hash);
                    }
                }
            }
        }
    }
}
